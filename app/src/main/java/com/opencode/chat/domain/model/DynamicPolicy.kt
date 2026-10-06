package com.opencode.chat.domain.model

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * A DYNAMIC sample of what the phone is doing right now.
 *
 * Distinct from [DeviceProfile], which is static: total RAM and core count never
 * change while the app is installed, so they are read once. Thermal status,
 * available memory and power-saver state all change mid-session, which is why
 * they cannot live in the static profile.
 *
 * Every field degrades to a safe default when the platform will not report it,
 * because a missing signal must never read as "no pressure".
 */
data class RuntimeSignals(
    /** PowerManager.ThermalStatus, or -1 when unavailable (pre-API 29). */
    val thermalStatus: Int,
    val availableRamMb: Int,
    val totalRamMb: Int,
    /**
     * Android's own low-memory verdict from ActivityManager.MemoryInfo.
     *
     * Preferred over any threshold we compute. The OS already calibrates this
     * per device, and a hand-rolled percentage is wrong at both ends: a 16GB
     * phone rarely keeps 20% free, so a percentage rule would mark almost every
     * modern device as permanently under pressure and shed prewarm forever.
     */
    val lowMemory: Boolean,
    val powerSaveMode: Boolean,
    val isForeground: Boolean,
    /** A stream or tool call is live. Never shed the engine underneath one. */
    val workActive: Boolean
) {
    companion object {
        fun sample(context: Context, isForeground: Boolean, workActive: Boolean): RuntimeSignals {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

            // getCurrentThermalStatus is API 29+. Below that there is no signal
            // at all, so report unknown rather than a reassuring zero.
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { pm?.currentThermalStatus }.getOrNull() ?: THERMAL_UNKNOWN
            } else {
                THERMAL_UNKNOWN
            }

            return RuntimeSignals(
                thermalStatus = thermal,
                availableRamMb = (mi.availMem / (1024 * 1024)).toInt(),
                totalRamMb = (mi.totalMem / (1024 * 1024)).toInt(),
                lowMemory = mi.lowMemory,
                powerSaveMode = runCatching {
                    pm?.isPowerSaveMode == true
                }.getOrDefault(false),
                isForeground = isForeground,
                workActive = workActive
            )
        }

        const val THERMAL_UNKNOWN = -1
    }
}

/** How much the phone is struggling right now. */
enum class PressureLevel {
    OK,

    /** Hot, low on memory, or in power-saver. Shed optional work. */
    ELEVATED,

    /** Severely hot or nearly OOM. Shed aggressively. */
    CRITICAL;

    val worseThan: (PressureLevel) -> Boolean = { other -> ordinal > other.ordinal }
}

/**
 * DYNAMIC auto-tuning: reacts to conditions [TuningPolicy] cannot see.
 *
 * [TuningPolicy] answers "what does this phone look like". This answers "what is
 * this phone doing right now", which is the half that was missing: a MID-class
 * phone is fine when idle and dying when a video call is running next to it.
 *
 * TWO HARD RULES, both learned the hard way:
 *
 *  1. Never shed the engine while [RuntimeSignals.workActive] is true. The
 *     supervisor would restart Crush underneath a live SSE stream, which is the
 *     exact failure that made sends vanish with no error.
 *
 *  2. Hysteresis is asymmetric. Escalating is immediate because a genuinely
 *     hot phone should not keep a prewarmed engine around for the next 30s.
 *     De-escalating waits for several consecutive calm samples, otherwise a
 *     phone hovering near the threshold flaps between tiers and thrashes.
 */
object DynamicPolicy {

    /**
     * Samples of calm required before stepping back down. 3 polls at a 10s
     * health interval is ~30s of calm, long enough to avoid reacting to a
     * transient while a camera briefly opens.
     */
    const val CALM_SAMPLES_TO_RELAX = 3

    fun pressureOf(s: RuntimeSignals): PressureLevel {
        // Thermal is the signal to trust most: it is measured by the OS from the
        // actual sensors and already factors in the charging current, which
        // available-memory percentage cannot see.
        // The ladder is NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN.
        // There is no EXTREME constant; SEVERE is where a phone is too hot to
        // keep a prewarmed resident process around.
        val severe = s.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

        // Two attempts at a free-memory PERCENTAGE rule were written and both were
        // wrong, which is why there is no third. A ratio cannot express "low
        // memory" across a 1GB-to-16GB range: 2GB free is critical on a 4GB
        // phone and completely healthy on a 16GB one. Any single cutoff either
        // panics small devices or under-reacts on large ones, and in the
        // over-reactive direction the app sheds prewarm forever.
        //
        // ActivityManager.MemoryInfo.lowMemory is the platform's own calibrated
        // verdict for exactly this. availableRamMb/totalRamMb are kept for
        // display and diagnostics, not for control flow.

        return when {
            severe -> PressureLevel.CRITICAL
            // The OS verdict outranks anything we could compute.
            s.lowMemory -> PressureLevel.CRITICAL
            s.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> PressureLevel.ELEVATED
            s.powerSaveMode -> PressureLevel.ELEVATED
            else -> PressureLevel.OK
        }
    }

    /**
     * Scales resolved AUTO values down under pressure.
     *
     * Only AUTO-sourced values move. A value the user chose by hand is left
     * alone: the override contract is USER > AUTO, and silently rewriting a
     * user's explicit choice would break the one rule the settings UI relies on
     * to show AUTO vs MANUAL honestly.
     *
     * Hang grace is deliberately NOT reduced. A wedged engine is worse than a
     * hot one, so under pressure we keep watching for hangs and shorten only
     * things that are pure overhead.
     */
    fun apply(base: TuningPolicy.Resolved, level: PressureLevel): TuningPolicy.Resolved {
        if (level == PressureLevel.OK) return base

        val divisor = if (level == PressureLevel.CRITICAL) 4 else 2

        fun scale(key: String, current: Int, floor: Int): Int =
            if (base.sourceOf(key) == TuningPolicy.Source.USER) current
            else (current / divisor).coerceAtLeast(floor)

        return base.copy(
            engineIdleTimeoutSec = scale("idleTimeout", base.engineIdleTimeoutSec, 60),
            hangGraceSec = base.hangGraceSec,
            healthIntervalSec = scale("healthInterval", base.healthIntervalSec, 4),
            prewarmEngine = if (base.sourceOf("prewarm") == TuningPolicy.Source.USER) {
                base.prewarmEngine
            } else {
                false
            },
            source = base.source
        )
    }

    /**
     * Next level given a fresh observation.
     *
     * Escalation is immediate; relaxation needs [calmRun] consecutive OK
     * samples. [calmRun] is carried by the caller so the policy stays pure.
     */
    fun next(current: PressureLevel, observed: PressureLevel, calmRun: Int): PressureLevel = when {
        observed.ordinal > current.ordinal -> observed
        observed == PressureLevel.OK -> if (calmRun + 1 >= CALM_SAMPLES_TO_RELAX) PressureLevel.OK else current
        else -> current
    }

    /**
     * Applies pressure unless work is live.
     *
     * Called from the supervisor's poll, which is why the workActive guard lives
     * here rather than at the call sites: this is the one choke point every
     * tuning change flows through.
     */
    fun applySafely(
        base: TuningPolicy.Resolved,
        signals: RuntimeSignals,
        level: PressureLevel
    ): TuningPolicy.Resolved =
        if (signals.workActive) base else apply(base, level)

    /** Exposed for the settings/diagnostics surface. */
    fun hasSecureLockScreen(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceSecure == true
    }.getOrDefault(false)
}