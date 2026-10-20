package com.crush.mobile.data.local

import android.os.SystemClock

/**
 * Process-scoped temporal state of the app-lock gate.
 *
 * Lives in the DI container on purpose, exactly like [KeySession]: the temporal
 * facts the gate decides on must survive ACTIVITY recreation, because Android
 * recreates the activity freely (credential-screen closure recreated it 3s
 * after a successful unlock, resetting every instance field and re-prompting -
 * the double-prompt bug). A per-activity holder has same failure mode as the
 * per-activity key holder had; process death is the natural reset (fresh
 * process = nobody has authenticated).
 *
 * What lives here and why the lock honestly needs it:
 *  - [pausedAtElapsedMs]: the absence stamp. A recreated activity has no pause
 *    of its OWN; handing the decision only the new instance's (null) pause
 *    would fail closed and prompt a freshly authenticated user again.
 *  - [ownerVerified]: "a successful overlay authentication happened and has
 *    not been aged out". This is THE gate input, deliberately NOT
 *    [KeySession.isUnlocked]: the key cache has independent writers/clearers
 *    (SettingsDataStore.remove, chat logout) and a successful auth can
 *    legitimately complete without a cached key (blank read for a provider
 *    with no ciphertext). Keying the gate on the cache made a real
 *    authentication evaporate within milliseconds.
 *  - [lastUnlockElapsedMs]: when that authentication happened, to answer
 *    "did the user auth AFTER the absence began?" (fresh unlock). The overlay
 *    unlock runs while the app is paused-by-definition-absent; without the
 *    stamp the absence timer discards the auth it prompted for.
 *
 * CLOCK: everything is SystemClock.elapsedRealtime (monotonic), never
 * wall-clock. [clockMillis] is injectable for JVM tests, which cannot mock
 * SystemClock.
 */
class LockState(private val clockMillis: () -> Long = { SystemClock.elapsedRealtime() }) {

    @Volatile
    var pausedAtElapsedMs: Long? = null
        private set

    @Volatile
    var lastUnlockElapsedMs: Long? = null
        private set

    @Volatile
    var ownerVerified: Boolean = false
        private set

    /** The overlay calls this exactly once per successful authentication. */
    fun noteUnlock(nowElapsed: Long = clockMillis()) {
        ownerVerified = true
        lastUnlockElapsedMs = nowElapsed
    }

    /** MainActivity.onPause. Monotonic stamps only; null means "never paused". */
    fun notePause(nowElapsed: Long = clockMillis()) {
        pausedAtElapsedMs = nowElapsed
    }

    /**
     * True when the unlock happened at-or-after the last pause: the auth
     * completed in the same absence window that pause opened, so it is fresh
     * presence. A null pause cannot have preceded an unlock.
     */
    fun isFreshUnlock(nowElapsed: Long = clockMillis()): Boolean {
        val unlockAt = lastUnlockElapsedMs ?: return false
        val pauseAt = pausedAtElapsedMs
        return pauseAt == null || unlockAt >= pauseAt
    }

    /**
     * The value to feed [com.crush.mobile.ui.AppLockPolicy.decide]'s
     * secondsSinceBackground for this resume: 0 when the unlock is fresh (the
     * trusted key was re-established THIS resume, so its trust age restarts),
     * else the real absence. Null means "no pause observed" - decide fails
     * closed on that, which is the desired belt for an unobserved pause.
     */
    fun trustAgeSeconds(nowElapsed: Long = clockMillis()): Long? {
        val pauseAt = pausedAtElapsedMs ?: return null
        return if (isFreshUnlock(nowElapsed)) {
            0L
        } else {
            (nowElapsed - pauseAt) / 1000L
        }
    }

    /**
     * Ages the verification out after an absence at least as long as
     * [graceSeconds]. Returns true when it did, so the caller can also drop the
     * cached key. A fresh unlock is never aged by this: if the user just
     * authenticated, the absence that preceded the auth is not a reason to
     * discard it.
     */
    fun ageIfAbsent(graceSeconds: Long, nowElapsed: Long = clockMillis()): Boolean {
        val pauseAt = pausedAtElapsedMs ?: return false
        val awaySec = (nowElapsed - pauseAt) / 1000L
        if (!isFreshUnlock(nowElapsed) && awaySec >= graceSeconds) {
            ownerVerified = false
            return true
        }
        return false
    }

    /** Full reset (gate closed hard). Key cache is cleared by the caller. */
    fun reset() {
        ownerVerified = false
        lastUnlockElapsedMs = null
        pausedAtElapsedMs = null
    }
}
