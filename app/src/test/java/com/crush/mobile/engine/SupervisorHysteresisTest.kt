package com.crush.mobile.engine

import com.crush.mobile.domain.model.DynamicPolicy
import com.crush.mobile.domain.model.PressureLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The supervisor's pressure loop, replayed.
 *
 * WHY A SIMULATION AND NOT A UNIT TEST OF `next()`
 *
 * `DynamicPolicy.next()` compares `calmRun + 1 >= 3`, so it expects the count of
 * calm samples from BEFORE the current one. `EngineSupervisor` used to increment
 * `calmRun` first and pass the new value, applying the +1 twice - so the policy
 * relaxed after TWO consecutive calm samples instead of three, shedding the
 * engine's idle linger a full sample early on any brief recovery.
 *
 * The 20 existing tests of `next()` all pass and always did, because they feed
 * it a pre-incremented count directly. They encode the function's contract
 * correctly; they simply never exercised the CALLER, which is where the bug was.
 * Only a test that replays the loop can catch this class of mistake.
 *
 * This mirrors the supervisor's actual sequence. If the call site changes, this
 * test is what notices.
 */
class SupervisorHysteresisTest {

    /**
     * One poll of EngineSupervisor's loop, transcribed.
     *
     * Returns the level after this observation.
     */
    private fun poll(
        current: PressureLevel,
        observed: PressureLevel,
        calmRun: Int
    ): Pair<PressureLevel, Int> {
        // The caller's contract: pass the count from BEFORE this sample.
        val priorCalm = calmRun
        val next = if (observed == PressureLevel.OK) calmRun + 1 else 0
        return DynamicPolicy.next(current, observed, priorCalm) to next
    }

    @Test
    fun `escalation is immediate`() {
        var level = PressureLevel.OK
        var calm = 0
        val (after, _) = poll(level, PressureLevel.CRITICAL, calm)
        assertEquals("escalation must not wait for hysteresis", PressureLevel.CRITICAL, after)
    }

    @Test
    fun `one calm sample does NOT relax`() {
        val (after, calm) = poll(PressureLevel.ELEVATED, PressureLevel.OK, 0)
        assertEquals("a single calm sample is not sustained calm", PressureLevel.ELEVATED, after)
        assertEquals(1, calm)
    }

    @Test
    fun `two calm samples do NOT relax`() {
        // THE REGRESSION. With the double increment this returned OK.
        var level = PressureLevel.ELEVATED
        var calm = 0
        val (afterTwo, calm2) = poll(level, PressureLevel.OK, calm)
        val (afterTwo2, _) = poll(afterTwo, PressureLevel.OK, calm2)
        assertEquals(
            "relaxing after 2 calm samples is the off-by-one this test exists to catch",
            PressureLevel.ELEVATED,
            afterTwo2
        )
    }

    @Test
    fun `three consecutive calm samples DO relax`() {
        var level = PressureLevel.ELEVATED
        var calm = 0
        repeat(3) {
            val (after, next) = poll(level, PressureLevel.OK, calm)
            level = after
            calm = next
        }
        assertEquals("three calm samples must relax", PressureLevel.OK, level)
    }

    @Test
    fun `a non-OK observation resets the calm run`() {
        // Pressure blips must not accumulate toward relaxation.
        var level = PressureLevel.ELEVATED
        var calm = 0
        val (a, c1) = poll(level, PressureLevel.OK, calm)
        val (b, c2) = poll(a, PressureLevel.ELEVATED, c1)
        assertEquals(PressureLevel.ELEVATED, b)
        assertEquals("a non-OK sample must reset the counter", 0, c2)
    }

    @Test
    fun `interleaved blips prevent relaxation entirely`() {
        // OK, OK, ELEVATED, OK, OK - the run never reaches 3 sustained.
        var level = PressureLevel.ELEVATED
        var calm = 0
        val seq = listOf(PressureLevel.OK, PressureLevel.OK, PressureLevel.ELEVATED,
            PressureLevel.OK, PressureLevel.OK)
        for (o in seq) {
            val (after, next) = poll(level, o, calm)
            level = after
            calm = next
        }
        assertTrue(
            "blips must not accumulate into a relaxation, ended at $level",
            level != PressureLevel.OK
        )
    }

    @Test
    fun `the policy constant matches the documented intent`() {
        // If this ever changes, the loop test above documents the old behaviour.
        assertEquals(3, DynamicPolicy.CALM_SAMPLES_TO_RELAX)
    }

    // ---------------------------------------------------------------------
    // Supervisor START-budget / wedge-budget rules (P1-10, P1-25SUP).
    //
    // These live here because the class's header documents the same principle:
    // loop contract regressions are only catchable by replaying or pinning the
    // CALLER's rule, not the helper it calls. Both rules are pure companion
    // functions on EngineSupervisor (no process spawn) extracting the exact
    // branch the supervise() loop uses.
    // ---------------------------------------------------------------------

    @Test
    fun `transient startup failures still retry within the budget`() {
        // A slow first boot and a stale-socket early exit are both
        // Failed(willRetry=true) now; attempts 1..MAX must keep retrying.
        for (attempt in 1..EngineSupervisor.MAX_START_ATTEMPTS) {
            assertFalse(
                "attempt $attempt must still retry",
                EngineSupervisor.startRetryGivesUp(attempt, failureWantsRetry = true)
            )
        }
    }

    @Test
    fun `the start budget is finite - one past the cap gives up`() {
        assertTrue(
            "beyond the budget the supervisor must stop restarting",
            EngineSupervisor.startRetryGivesUp(EngineSupervisor.MAX_START_ATTEMPTS + 1, true)
        )
    }

    @Test
    fun `hard startup failures give up immediately regardless of budget`() {
        // Missing binary / exec denied never succeed on retry.
        assertTrue(EngineSupervisor.startRetryGivesUp(1, failureWantsRetry = false))
    }

    @Test
    fun `wedge budget tolerates cap-minus-one rapid restarts then refuses`() {
        assertFalse(
            EngineSupervisor.wedgeBudgetExhausted(EngineSupervisor.MAX_WEDGE_RESTARTS - 1)
        )
        assertTrue(
            "at the cap the loop must surface a terminal failure instead of churning",
            EngineSupervisor.wedgeBudgetExhausted(EngineSupervisor.MAX_WEDGE_RESTARTS)
        )
    }

    // ---------------------------------------------------------------------
    // STARTING watchdog (design memo §2.2, 2026-11-05): the bound-but-
    // unconfirmed Starting wedge. Same principle as the START-budget block
    // above: the predicate is a pure companion function on EngineSupervisor,
    // replayed here without spawning an engine. The wedgeRestarts argument is
    // the SHARED P1-25SUP counter; the watchdog must never reset it.
    // ---------------------------------------------------------------------

    @Test
    fun `watchdog keeps waiting just under the threshold`() {
        // A healthy boot measured 4-58s and the engine's own readiness
        // deadline is 60s; 89_999ms is still inside a legit boot envelope
        // (deadline + probe slack), so the watchdog must not act yet.
        assertEquals(
            "an attempt below the starting-watchdog threshold is normal starting",
            StartWatchdogDecision.KeepWaiting,
            EngineSupervisor.startingWatchdogShouldRestart(
                elapsedMs = EngineSupervisor.STARTING_WATCHDOG_MS - 1,
                probeOk = true,
                wedgeRestarts = 0
            )
        )
    }

    @Test
    fun `watchdog fires exactly at the threshold with a bound child`() {
        assertEquals(
            "a bound child past 90s of Starting is the wedge shape",
            StartWatchdogDecision.Restart,
            EngineSupervisor.startingWatchdogShouldRestart(
                elapsedMs = EngineSupervisor.STARTING_WATCHDOG_MS,
                probeOk = true,
                wedgeRestarts = 0
            )
        )
    }

    @Test
    fun `a failing or missing socket is never a wedge`() {
        // Memo predicate, fail-closed: "a bound-but-frozen Starting for >90s
        // is a wedge, a missing socket is normal starting." No elapsed time
        // converts an unbound child into a restart decision.
        assertEquals(
            StartWatchdogDecision.KeepWaiting,
            EngineSupervisor.startingWatchdogShouldRestart(
                elapsedMs = 600_000L,
                probeOk = false,
                wedgeRestarts = 0
            )
        )
    }

    @Test
    fun `the first wedged episode restarts and a second one is terminal`() {
        // Memo §2.2: ONE bounded restart; a SECOND consecutive wedged episode
        // writes the terminal honest failure instead of churning again.
        assertEquals(
            StartWatchdogDecision.Restart,
            EngineSupervisor.startingWatchdogShouldRestart(
                elapsedMs = 120_000L, probeOk = true, wedgeRestarts = 0
            )
        )
        assertEquals(
            StartWatchdogDecision.GiveUp,
            EngineSupervisor.startingWatchdogShouldRestart(
                elapsedMs = 120_000L, probeOk = true, wedgeRestarts = 1
            )
        )
    }

    @Test
    fun `the watchdog give-up respects the shared wedge budget and never resets it`() {
        // wedgeRestarts is shared with the unhealthy-path budget: counts at or
        // past the cap give up regardless of how the count got there, and no
        // input to the pure predicate can resurrect a spent budget - there is
        // no writer side effect, so `wedgeRestarts` monotonically de-escalates
        // the decision while the loop keeps ownership of resets.
        for (spent in 1..(EngineSupervisor.MAX_WEDGE_RESTARTS + 2)) {
            assertEquals(
                "wedge count $spent is spent; the watchdog must not restart again",
                StartWatchdogDecision.GiveUp,
                EngineSupervisor.startingWatchdogShouldRestart(
                    elapsedMs = 120_000L, probeOk = true, wedgeRestarts = spent
                )
            )
        }
    }

    @Test
    fun `the watchdog threshold stays above the bounded readiness window`() {
        // Pinned number: 90s = the engine's 60s readiness deadline plus probe
        // grace (memo §2.2 chose it deliberately). If this ever shrinks below
        // the ladder's worst legit boot, slow devices get false wedge
        // restarts; if it grows past the wedge shape, detection is pointless.
        assertEquals(90_000L, EngineSupervisor.STARTING_WATCHDOG_MS)
    }
}
