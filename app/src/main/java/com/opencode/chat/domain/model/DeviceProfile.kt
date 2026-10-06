package com.opencode.chat.domain.model

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * STATIC device capability profile. Sampled once at first launch and cached;
 * these facts do not change while the app is installed.
 *
 * Deliberately separate from DYNAMIC tuning (thermal state, MemAvailable,
 * foreground/background, active streams) which is documented in
 * `docs/dynamic-autotuning.md` and not implemented yet. Keeping the two apart
 * matters: static values are safe to read on the main thread once, whereas
 * dynamic values must be sampled off it and can change mid-session.
 */
data class DeviceProfile(
    val apiLevel: Int,
    /** Total physical RAM in MB, or 0 when the OS will not say. */
    val totalRamMb: Int,
    val availableRamMb: Int,
    val cores: Int,
    val isLowRamDevice: Boolean,
    val hasStrongBox: Boolean,
    val hasSecureLockScreen: Boolean,
    val supportsModernAuthGate: Boolean
) {
    /**
     * Coarse performance class. Used only to pick CONSERVATIVE defaults; it must
     * never be used to weaken security, which is decided by the Keystore tier
     * ladder in SecureKeyStore instead.
     */
    val perfClass: PerfClass = when {
        isLowRamDevice || totalRamMb in 1..2048 -> PerfClass.ENTRY
        // Unknown RAM (0, e.g. when the OS withholds it) must NOT fall through
        // to HIGH. HIGH means the longest idle timeout and prewarm enabled, i.e.
        // the most expensive settings. When we do not know the device, the safe
        // answer is the middle tier.
        totalRamMb == 0 -> PerfClass.MID
        totalRamMb in 2049..4096 || cores <= 4 -> PerfClass.MID
        else -> PerfClass.HIGH
    }

    enum class PerfClass { ENTRY, MID, HIGH }

    companion object {
        fun detect(context: Context): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

            // The OS flags low-RAM devices explicitly; trust it over our own guess.
            val lowRam = am?.isLowRamDevice ?: false

            val lockScreen = runCatching {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE)
                    as? android.app.KeyguardManager
                km?.isDeviceSecure == true
            }.getOrDefault(false)

            return DeviceProfile(
                apiLevel = Build.VERSION.SDK_INT,
                totalRamMb = (info.totalMem / (1024 * 1024)).toInt(),
                availableRamMb = (info.availMem / (1024 * 1024)).toInt(),
                cores = Runtime.getRuntime().availableProcessors(),
                isLowRamDevice = lowRam,
                hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
                hasSecureLockScreen = lockScreen,
                // setUserAuthenticationParameters arrived in API 30. Below that
                // the legacy validity-seconds gate is the only option, which is
                // why an API 29 device with a 10-digit PIN still could not create
                // a modern auth-gated Keystore key.
                supportsModernAuthGate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            )
        }
    }
}