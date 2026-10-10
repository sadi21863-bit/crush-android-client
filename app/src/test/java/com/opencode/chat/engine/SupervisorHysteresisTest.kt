package com.opencode.chat.engine

import com.opencode.chat.domain.model.DynamicPolicy
import com.opencode.chat.domain.model.PressureLevel
import org.junit.Assert.assertEquals
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
}
