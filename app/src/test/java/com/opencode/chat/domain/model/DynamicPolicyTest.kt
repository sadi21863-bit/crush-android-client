package com.opencode.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for DYNAMIC auto-tuning.
 *
 * The static [TuningPolicy] tests prove the app adapts to what a phone IS.
 * These prove it adapts to what a phone is DOING, which is the half that was
 * missing entirely.
 *
 * Two of these tests exist specifically to pin rules that, if violated, cause
 * silent data loss rather than a visible failure: the workActive guard and the
 * "never rewrite a user override" rule.
 */
class DynamicPolicyTest {

    private fun signals(
        thermal: Int = -1,
        availableMb: Int = 2000,
        totalMb: Int = 4000,
        lowMemory: Boolean = false,
        powerSave: Boolean = false,
        foreground: Boolean = true,
        workActive: Boolean = false
    ) = RuntimeSignals(thermal, availableMb, totalMb, lowMemory, powerSave, foreground, workActive)

    private fun midProfile() = DeviceProfile(
        apiLevel = 29,
        totalRamMb = 4000,
        availableRamMb = 2000,
        cores = 8,
        isLowRamDevice = false,
        hasStrongBox = false,
        hasSecureLockScreen = true,
        supportsModernAuthGate = false
    )

    // ---------- pressureOf ----------

    @Test
    fun `healthy phone reads OK`() {
        assertEquals(PressureLevel.OK, DynamicPolicy.pressureOf(signals()))
    }

    @Test
    fun `moderate thermal is elevated`() {
        assertEquals(
            PressureLevel.ELEVATED,
            DynamicPolicy.pressureOf(signals(thermal = 2)) // MODERATE
        )
    }

    @Test
    fun `severe thermal is critical`() {
        assertEquals(
            PressureLevel.CRITICAL,
            DynamicPolicy.pressureOf(signals(thermal = 3)) // SEVERE
        )
    }

    @Test
    fun `power saver alone is elevated`() {
        assertEquals(PressureLevel.ELEVATED, DynamicPolicy.pressureOf(signals(powerSave = true)))
    }

    @Test
    fun `os low memory verdict is critical`() {
        // The calibrated signal, and the one that actually matters. Escalating
        // here is what stops the OOM killer getting there first.
        assertEquals(
            PressureLevel.CRITICAL,
            DynamicPolicy.pressureOf(signals(availableMb = 3000, totalMb = 4000, lowMemory = true))
        )
    }

    @Test
    fun `free memory alone never invents pressure`() {
        // REGRESSION, twice over. Two percentage rules were written and both
        // were wrong: 2GB free is critical on a 4GB phone and completely healthy
        // on a 16GB one, so no single ratio works across the range. A version
        // that escalated below 20% would report nearly every modern phone as
        // starved and shed prewarm permanently. Only the OS verdict decides.
        for (total in listOf(1000, 2000, 4000, 8000, 12000, 16000)) {
            assertEquals(
                "total=${total}MB with 2GB free must not read as pressure",
                PressureLevel.OK,
                DynamicPolicy.pressureOf(signals(availableMb = 2000, totalMb = total))
            )
        }
    }

    @Test
    fun `low memory still works when the os reports it`() {
        // The flip side: dropping the ratio must not have made us deaf to real
        // memory exhaustion.
        assertEquals(
            PressureLevel.CRITICAL,
            DynamicPolicy.pressureOf(signals(availableMb = 90, totalMb = 4000, lowMemory = true))
        )
    }

    @Test
    fun `unknown total ram never reads as pressure`() {
        // totalRamMb == 0 means the OS withheld it. Treating that as "no memory"
        // would pin every such device to CRITICAL forever.
        assertEquals(
            PressureLevel.OK,
            DynamicPolicy.pressureOf(signals(availableMb = 0, totalMb = 0))
        )
    }

    @Test
    fun `unknown thermal does not invent pressure`() {
        assertEquals(PressureLevel.OK, DynamicPolicy.pressureOf(signals(thermal = -1)))
    }

    // ---------- apply ----------

    @Test
    fun `OK level leaves the resolved policy untouched`() {
        val base = TuningPolicy.resolve(midProfile(), TuningPolicy.Overrides())
        assertEquals(base, DynamicPolicy.apply(base, PressureLevel.OK))
    }

    @Test
    fun `critical level cuts idle timeout and disables prewarm`() {
        val base = TuningPolicy.resolve(midProfile(), TuningPolicy.Overrides())
        val out = DynamicPolicy.apply(base, PressureLevel.CRITICAL)
        assertTrue(
            "idle should shrink: ${base.engineIdleTimeoutSec} -> ${out.engineIdleTimeoutSec}",
            out.engineIdleTimeoutSec < base.engineIdleTimeoutSec
        )
        assertFalse("prewarm must be shed under pressure", out.prewarmEngine)
    }

    @Test
    fun `hang grace is never reduced under pressure`() {
        // A wedged engine is worse than a hot one. Shortening the grace period
        // under pressure would restart Crush MORE eagerly exactly when the phone
        // is least able to afford a restart.
        val base = TuningPolicy.resolve(midProfile(), TuningPolicy.Overrides())
        val out = DynamicPolicy.apply(base, PressureLevel.CRITICAL)
        assertEquals(base.hangGraceSec, out.hangGraceSec)
    }

    @Test
    fun `idle timeout stays above the safe floor`() {
        val base = TuningPolicy.resolve(midProfile(), TuningPolicy.Overrides()).copy(
            engineIdleTimeoutSec = 60
        )
        val out = DynamicPolicy.apply(base, PressureLevel.CRITICAL)
        assertTrue(out.engineIdleTimeoutSec >= 60)
    }

    @Test
    fun `user override of idle timeout survives pressure`() {
        // The override contract is USER > AUTO. If pressure rewrote this, the
        // settings UI would show MANUAL while behaving as AUTO - a silent lie.
        val base = TuningPolicy.resolve(
            midProfile(),
            TuningPolicy.Overrides(engineIdleTimeoutSec = 3600)
        )
        val out = DynamicPolicy.apply(base, PressureLevel.CRITICAL)
        assertEquals(3600, out.engineIdleTimeoutSec)
    }

    @Test
    fun `user override of prewarm survives pressure`() {
        val base = TuningPolicy.resolve(
            midProfile(),
            TuningPolicy.Overrides(prewarmEngine = true)
        )
        val out = DynamicPolicy.apply(base, PressureLevel.CRITICAL)
        assertTrue(out.prewarmEngine)
    }

    // ---------- the two rules that prevent data loss ----------

    @Test
    fun `active work suppresses shedding entirely`() {
        // THE important test. Restarting the engine mid-stream is what made
        // sends vanish with no error, and pressure is exactly when a restart
        // looks tempting.
        val base = TuningPolicy.resolve(midProfile(), TuningPolicy.Overrides())
        val hot = signals(thermal = 3, workActive = true)
        val out = DynamicPolicy.applySafely(base, hot, DynamicPolicy.pressureOf(hot))
        assertEquals("live stream must keep its engine", base, out)
    }

    @Test
    fun `shedding resumes once work stops`() {
        val base = TuningPolicy.resolve(midProfile(), TuningPolicy.Overrides())
        val hot = signals(thermal = 3, workActive = false)
        val out = DynamicPolicy.applySafely(base, hot, DynamicPolicy.pressureOf(hot))
        assertFalse(out.prewarmEngine)
    }

    // ---------- hysteresis ----------

    @Test
    fun `escalation is immediate`() {
        // A genuinely hot phone must not wait out the calm counter.
        assertEquals(
            PressureLevel.CRITICAL,
            DynamicPolicy.next(PressureLevel.OK, PressureLevel.CRITICAL, calmRun = 0)
        )
        assertEquals(
            PressureLevel.ELEVATED,
            DynamicPolicy.next(PressureLevel.OK, PressureLevel.ELEVATED, calmRun = 0)
        )
    }

    @Test
    fun `de-escalation waits for consecutive calm samples`() {
        // Otherwise a phone hovering at the threshold flaps between tiers.
        assertEquals(PressureLevel.ELEVATED, DynamicPolicy.next(PressureLevel.ELEVATED, PressureLevel.OK, 0))
        assertEquals(PressureLevel.ELEVATED, DynamicPolicy.next(PressureLevel.ELEVATED, PressureLevel.OK, 1))
        assertEquals(PressureLevel.OK, DynamicPolicy.next(PressureLevel.ELEVATED, PressureLevel.OK, 2))
    }

    @Test
    fun `escalating mid-calm-run resets the optimism`() {
        assertEquals(
            PressureLevel.ELEVATED,
            DynamicPolicy.next(PressureLevel.OK, PressureLevel.ELEVATED, calmRun = 2)
        )
    }
}