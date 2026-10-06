package com.opencode.chat.engine

import android.os.Build
import com.opencode.chat.domain.model.DeviceProfile

/**
 * Whether this device can run the bundled engine at all.
 *
 * WHY THIS EXISTS: the app ships exactly ONE native ABI. Crush's release
 * pipeline explicitly EXCLUDES 32-bit Android:
 *
 *   goarch: [amd64, arm64, "386", arm]
 *   ignore:
 *     - goos: android, goarch: amd64
 *     - goos: android, goarch: arm
 *     - goos: android, goarch: "386"
 *
 * so `crush_*_Android_arm64.tar.gz` is the only Android artifact that exists.
 * A 32-bit phone therefore installs this APK and then cannot start the engine.
 *
 * The alternative to this check is the worst possible failure mode: the app
 * installs, opens, and sits there with no explanation, because "engine failed"
 * is meaningless to someone who does not know an engine exists. Refusing to
 * pretend is the whole point.
 */
object EngineCompatibility {

    sealed class Result {
        /** The bundled engine should run. */
        object Supported : Result()

        /**
         * Cannot run. [reason] is written for a human, not an engineer.
         */
        data class Unsupported(val reason: String) : Result()
    }

    /**
     * The single ABI this app ships a native library for.
     *
     * Not derived from Build.SUPPORTED_ABIS_64BIT - it describes what is BUNDLED
     * in jniLibs, which is a property of the APK, not of the device. Hardcoded so
     * that adding a second ABI is a deliberate edit in two places (jniLibs and
     * here) rather than a silent mismatch.
     */
    const val BUNDLED_ABI = "arm64-v8a"

    /**
     * Pure decision so the whole matrix is unit-testable without a device.
     *
     * @param supportedAbis ABIs the OS reports for this process
     * @param hasBundledEngine whether the bundled lib was found in nativeLibraryDir
     */
    fun evaluate(supportedAbis: Array<String>, hasBundledEngine: Boolean): Result {
        if (supportedAbis.isEmpty()) {
            return Result.Unsupported("This device reports no supported CPU architecture.")
        }
        if (!hasBundledEngine) {
            return Result.Unsupported(
                "The on-device AI engine is missing from this build, so chat cannot run."
            )
        }
        if (supportedAbis.contains(BUNDLED_ABI)) return Result.Supported

        // The common real-world case: a 32-bit-only device.
        return Result.Unsupported(
            "This phone is 32-bit (${supportedAbis.first()}). The AI engine is only " +
                "published for 64-bit arm64 devices, so this app cannot run here. " +
                "It works on any 64-bit Android phone from roughly 2017 onward."
        )
    }

    /** Evaluates the real device. */
    fun evaluateDevice(hasBundledEngine: Boolean): Result =
        evaluate(Build.SUPPORTED_ABIS, hasBundledEngine)

    /**
     * Guidance for the settings screen: what a user on a 64-bit device should
     * still know about their configuration.
     */
    fun tierNote(profile: DeviceProfile): String = when {
        !profile.supportsModernAuthGate ->
            "Android ${profile.apiLevel} uses legacy auth gating. Your key is still " +
                "encrypted, but unlock relies on the deprecated validity-window API."
        profile.hasStrongBox ->
            "Hardware-backed StrongBox key storage is active."
        else ->
            "Standard Keystore key storage is active."
    }
}
