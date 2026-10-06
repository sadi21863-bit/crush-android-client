package com.opencode.chat.domain.model

/**
 * STATIC auto-tuning. Maps a [DeviceProfile] to conservative runtime defaults.
 *
 * Precedence is always: USER OVERRIDE > AUTO (this file) > HARD SAFE DEFAULT.
 * Every value is nullable in [UserOverrides] so "user said nothing" is
 * distinguishable from "user chose the same number as the default"; that is what
 * lets the UI honestly show AUTO vs MANUAL.
 *
 * Design rule: auto-tuning may make the app FASTER or CHEAPER, but must never
 * silently reduce security. The security tier is chosen by the Keystore
 * capability ladder, not here, so there is deliberately no auto-knob for it.
 */
object TuningPolicy {

    data class Resolved(
        val engineIdleTimeoutSec: Int,
        val hangGraceSec: Int,
        val healthIntervalSec: Int,
        val prewarmEngine: Boolean,
        val maxRecentSessions: Int,
        val source: Map<String, Source>
    ) {
        fun sourceOf(key: String): Source = source[key] ?: Source.AUTO
    }

    enum class Source { AUTO, USER }

    /** Null means "no user choice", so auto-tuning applies. */
    data class Overrides(
        val engineIdleTimeoutSec: Int? = null,
        val hangGraceSec: Int? = null,
        val healthIntervalSec: Int? = null,
        val prewarmEngine: Boolean? = null,
        val maxRecentSessions: Int? = null
    ) {
        val isEmpty: Boolean
            get() = engineIdleTimeoutSec == null && hangGraceSec == null &&
                healthIntervalSec == null && prewarmEngine == null &&
                maxRecentSessions == null
    }

    fun resolve(profile: DeviceProfile, o: Overrides): Resolved {
        val autoIdle = when (profile.perfClass) {
            // Keeping a ~59MB engine resident on a 2GB phone is what gets it
            // OOM-killed. Shed it fast.
            DeviceProfile.PerfClass.ENTRY -> 300
            DeviceProfile.PerfClass.MID -> 900
            DeviceProfile.PerfClass.HIGH -> 3600
        }
        // A wedged engine is worse than a slow one, so entry devices are
        // actually MORE aggressive here, not less.
        val autoHang = when (profile.perfClass) {
            DeviceProfile.PerfClass.ENTRY -> 45
            else -> 60
        }
        val autoHealth = when (profile.perfClass) {
            DeviceProfile.PerfClass.ENTRY -> 8
            else -> 10
        }
        // Booting eagerly costs ~3s and ~59MB on a device that may not need it.
        val autoPrewarm = profile.perfClass != DeviceProfile.PerfClass.ENTRY
        val autoSessions = when (profile.perfClass) {
            DeviceProfile.PerfClass.ENTRY -> 20
            else -> 100
        }

        fun <T : Any> pick(key: String, user: T?, auto: T): Pair<T, Source> =
            if (user != null) user to Source.USER else auto to Source.AUTO

        val (idle, idleSrc) = pick("idleTimeout", o.engineIdleTimeoutSec, autoIdle)
        val (hang, hangSrc) = pick("hangGrace", o.hangGraceSec, autoHang)
        val (health, healthSrc) = pick("healthInterval", o.healthIntervalSec, autoHealth)
        val (prewarm, prewarmSrc) = pick("prewarm", o.prewarmEngine, autoPrewarm)
        val (sessions, sessionsSrc) = pick("sessions", o.maxRecentSessions, autoSessions)

        return Resolved(
            // Clamp user input so a hand-edited value cannot wedge the engine.
            engineIdleTimeoutSec = idle.coerceIn(60, 86_400),
            hangGraceSec = hang.coerceIn(15, 600),
            healthIntervalSec = health.coerceIn(2, 60),
            prewarmEngine = prewarm,
            maxRecentSessions = sessions.coerceIn(5, 1_000),
            source = mapOf(
                "idleTimeout" to idleSrc, "hangGrace" to hangSrc,
                "healthInterval" to healthSrc, "prewarm" to prewarmSrc,
                "sessions" to sessionsSrc
            )
        )
    }
}