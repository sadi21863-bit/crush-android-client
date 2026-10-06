package com.opencode.chat.engine

import com.opencode.chat.domain.model.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "can this phone run the engine at all" decision.
 *
 * This is the difference between an app that explains itself and an app that
 * installs, opens, and sits there dead on a 32-bit phone. Every message here is
 * user-facing copy, so the wording is asserted as carefully as the logic.
 */
class EngineCompatibilityTest {

    private val arm64 = "arm64-v8a"

    @Test
    fun `64-bit device with the engine bundled is supported`() {
        assertEquals(
            EngineCompatibility.Result.Supported,
            EngineCompatibility.evaluate(arrayOf(arm64, "armeabi-v7a"), hasBundledEngine = true)
        )
    }

    @Test
    fun `most modern phones report several abis and still pass`() {
        val abis = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
        assertEquals(
            EngineCompatibility.Result.Supported,
            EngineCompatibility.evaluate(abis, hasBundledEngine = true)
        )
    }

    @Test
    fun `32-bit only device is refused with a human explanation`() {
        val r = EngineCompatibility.evaluate(arrayOf("armeabi-v7a", "armeabi"), true)
        assertTrue(r is EngineCompatibility.Result.Unsupported)
        val reason = (r as EngineCompatibility.Result.Unsupported).reason
        assertTrue("must name the arch: $reason", reason.contains("32-bit"))
        // Tell them what WILL work, otherwise the message is just a refusal.
        assertTrue("must say what works: $reason", reason.contains("64-bit"))
    }

    @Test
    fun `missing engine binary is refused even on 64-bit`() {
        val r = EngineCompatibility.evaluate(arrayOf(arm64), hasBundledEngine = false)
        assertTrue(r is EngineCompatibility.Result.Unsupported)
        assertTrue((r as EngineCompatibility.Result.Unsupported).reason.contains("missing"))
    }

    @Test
    fun `empty abi list is refused not crashed on`() {
        val r = EngineCompatibility.evaluate(emptyArray(), true)
        assertTrue(r is EngineCompatibility.Result.Unsupported)
    }

    @Test
    fun `x86_64 emulator is refused with the arch named`() {
        // An x86_64 emulator has no arm64 translation in this APK, so it must be
        // told rather than left to fail inside the exec.
        val r = EngineCompatibility.evaluate(arrayOf("x86_64"), true)
        assertTrue(r is EngineCompatibility.Result.Unsupported)
        assertTrue((r as EngineCompatibility.Result.Unsupported).reason.contains("x86_64"))
    }

    @Test
    fun `every reported arch produces a non-empty reason when refused`() {
        for (arch in listOf("armeabi-v7a", "armeabi", "x86", "x86_64", "mips", "riscv64")) {
            val r = EngineCompatibility.evaluate(arrayOf(arch), true)
            assertTrue(
                "arch=$arch must explain itself",
                r is EngineCompatibility.Result.Unsupported &&
                    r.reason.isNotBlank() && !r.reason.contains("null")
            )
        }
    }

    @Test
    fun `legacy tier note is honest about api 29`() {
        val p = DeviceProfile(
            apiLevel = 29, totalRamMb = 3700, availableRamMb = 900, cores = 8,
            isLowRamDevice = false, hasStrongBox = false, hasSecureLockScreen = true,
            supportsModernAuthGate = false
        )
        assertTrue(EngineCompatibility.tierNote(p).contains("29"))
    }

    @Test
    fun `strongbox note reflects real hardware`() {
        val p = DeviceProfile(
            apiLevel = 34, totalRamMb = 8000, availableRamMb = 4000, cores = 8,
            isLowRamDevice = false, hasStrongBox = true, hasSecureLockScreen = true,
            supportsModernAuthGate = true
        )
        assertTrue(EngineCompatibility.tierNote(p).contains("StrongBox"))
    }
}
