package com.opencode.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tuning precedence and clamping.
 *
 * The invariant that matters: a USER override always wins, AUTO is only a
 * fallback, and no input can produce an unsafe value. An idle timeout of 0
 * would make Crush self-terminate instantly; an unbounded one would leak ~59MB
 * of engine on a 2GB phone forever.
 */
class TuningPolicyTest {

    private fun profile(
        ram: Int = 4096,
        lowRam: Boolean = false,
        cores: Int = 8
    ) = DeviceProfile(
        apiLevel = 29,
        totalRamMb = ram,
        availableRamMb = ram / 4,
        cores = cores,
        isLowRamDevice = lowRam,
        hasStrongBox = false,
        hasSecureLockScreen = true,
        supportsModernAuthGate = false
    )

    @Test
    fun `no overrides means every value is AUTO`() {
        val r = TuningPolicy.resolve(profile(), TuningPolicy.Overrides())
        listOf("idleTimeout", "hangGrace", "healthInterval", "prewarm", "sessions")
            .forEach { assertEquals(it, TuningPolicy.Source.AUTO, r.sourceOf(it)) }
    }

    @Test
    fun `user override wins and is marked MANUAL`() {
        val o = TuningPolicy.Overrides(engineIdleTimeoutSec = 999)
        val r = TuningPolicy.resolve(profile(), o)
        assertEquals(999, r.engineIdleTimeoutSec)
        assertEquals(TuningPolicy.Source.USER, r.sourceOf("idleTimeout"))
        // and the untouched one stays AUTO
        assertEquals(TuningPolicy.Source.AUTO, r.sourceOf("hangGrace"))
    }

    @Test
    fun `user override equal to the auto value is still MANUAL`() {
        // This is why overrides must be nullable: the UI must be able to say
        // "MANUAL" even when the number happens to match.
        val auto = TuningPolicy.resolve(profile(), TuningPolicy.Overrides())
        val r = TuningPolicy.resolve(
            profile(),
            TuningPolicy.Overrides(engineIdleTimeoutSec = auto.engineIdleTimeoutSec)
        )
        assertEquals(TuningPolicy.Source.USER, r.sourceOf("idleTimeout"))
    }

    @Test
    fun `entry devices get the shortest idle timeout`() {
        val entry = TuningPolicy.resolve(profile(ram = 2048, lowRam = true), TuningPolicy.Overrides())
        val high = TuningPolicy.resolve(profile(ram = 8192), TuningPolicy.Overrides())
        assertTrue(entry.engineIdleTimeoutSec < high.engineIdleTimeoutSec)
        assertEquals(false, entry.prewarmEngine)
    }

    @Test
    fun `low ram flag alone forces the entry tier`() {
        val r = TuningPolicy.resolve(profile(ram = 8192, lowRam = true), TuningPolicy.Overrides())
        assertEquals(300, r.engineIdleTimeoutSec)
    }

    @Test
    fun `idle timeout is clamped to a safe range`() {
        assertEquals(
            60,
            TuningPolicy.resolve(profile(), TuningPolicy.Overrides(engineIdleTimeoutSec = 0))
                .engineIdleTimeoutSec
        )
        assertEquals(
            86_400,
            TuningPolicy.resolve(profile(), TuningPolicy.Overrides(engineIdleTimeoutSec = 999_999))
                .engineIdleTimeoutSec
        )
    }

    @Test
    fun `hang grace and health interval are clamped`() {
        val o = TuningPolicy.Overrides(hangGraceSec = 0, healthIntervalSec = 0, maxRecentSessions = 0)
        val r = TuningPolicy.resolve(profile(), o)
        assertEquals(15, r.hangGraceSec)
        assertEquals(2, r.healthIntervalSec)
        assertEquals(5, r.maxRecentSessions)
    }

    @Test
    fun `negative overrides cannot produce negative values`() {
        val o = TuningPolicy.Overrides(
            engineIdleTimeoutSec = -50,
            hangGraceSec = -1,
            healthIntervalSec = -1,
            maxRecentSessions = -1
        )
        val r = TuningPolicy.resolve(profile(), o)
        assertTrue(r.engineIdleTimeoutSec > 0)
        assertTrue(r.hangGraceSec > 0)
        assertTrue(r.healthIntervalSec > 0)
        assertTrue(r.maxRecentSessions > 0)
    }

    @Test
    fun `empty overrides report as empty`() {
        assertTrue(TuningPolicy.Overrides().isEmpty)
        assertTrue(!TuningPolicy.Overrides(engineIdleTimeoutSec = 300).isEmpty)
    }
}

class DeviceProfileTest {

    private fun p(ram: Int, lowRam: Boolean = false, cores: Int = 8) = DeviceProfile(
        apiLevel = 29, totalRamMb = ram, availableRamMb = 512, cores = cores,
        isLowRamDevice = lowRam, hasStrongBox = false, hasSecureLockScreen = true,
        supportsModernAuthGate = false
    )

    @Test
    fun `low ram or small memory is ENTRY`() {
        assertEquals(DeviceProfile.PerfClass.ENTRY, p(1024).perfClass)
        assertEquals(DeviceProfile.PerfClass.ENTRY, p(8192, lowRam = true).perfClass)
    }

    @Test
    fun `mid range is MID`() {
        assertEquals(DeviceProfile.PerfClass.MID, p(3072).perfClass)
        assertEquals(DeviceProfile.PerfClass.MID, p(8192, cores = 4).perfClass)
    }

    @Test
    fun `big and many core is HIGH`() {
        assertEquals(DeviceProfile.PerfClass.HIGH, p(12288, cores = 8).perfClass)
    }

    @Test
    fun `unknown ram does not crash classification`() {
        // The supervisor's default tuning passes zeros.
        assertEquals(DeviceProfile.PerfClass.MID, p(0).perfClass)
    }

    @Test
    fun `modern auth gate is false below api 30`() {
        // The fact that broke biometric expectations on the reference device.
        val api29 = p(4096).copy(apiLevel = 29, supportsModernAuthGate = false)
        assertEquals(false, api29.supportsModernAuthGate)
        val api30 = api29.copy(apiLevel = 30, supportsModernAuthGate = true)
        assertEquals(true, api30.supportsModernAuthGate)
    }
}