package com.opencode.chat.ui

/**
 * Decides whether the app must be unlocked before showing any content.
 *
 * Extracted as a pure function for the same reason [StartRoute] is: the deciding
 * logic behind a security gate is exactly the logic that must not be untested.
 * A wrong answer here either locks the user out of their own app or silently
 * leaves the key reachable, so every branch is pinned in AppLockPolicyTest.
 *
 * The design goal was "never prompt when a prompt would not actually be
 * required". The Keystore master key is gated with a 300-second validity window,
 * so for the first [graceSeconds] after backgrounding the Cipher still works and
 * the key is already in memory. Prompting there would be pure friction. Once
 * that window lapses, a prompt is functionally required, which is exactly when
 * this policy asks for one.
 */
object AppLockPolicy {

    enum class Gate {
        /** Show the app; the key is either absent or already usable. */
        OPEN,

        /** Cover the app with the lock screen and require authentication. */
        PROMPT
    }

    /**
     * @param hasCiphertext whether an encrypted key exists in prefs. False on a
     *        fresh install: there is nothing to protect, and Onboarding runs its
     *        own prompt when it creates the key.
     * @param alreadyUnlocked whether the decrypted key is held in memory AND was
     *        obtained recently enough to still be inside the grace window.
     *        Callers are responsible for ageing that flag out - see
     *        [AppLockGate] - because "in memory" alone is not enough.
     * @param isColdStart true for the first onResume of the process.
     * @param secondsSinceBackground elapsed time between onPause and onResume,
     *        or null when onPause was never observed. Retained for diagnostics
     *        and to fail closed on an unobserved pause.
     * @param graceSeconds how long an unlocked key stays trusted after
     *        backgrounding. 0 means strict app lock: prompt on every resume.
     */
    fun decide(
        hasCiphertext: Boolean,
        alreadyUnlocked: Boolean,
        isColdStart: Boolean,
        secondsSinceBackground: Long?,
        graceSeconds: Long
    ): Gate {
        // Nothing stored means nothing to unlock. Onboarding owns that prompt.
        if (!hasCiphertext) return Gate.OPEN

        // THE rule. A key exists and we do not have it in hand, so the app must
        // not be usable. This must come before any grace-window logic.
        //
        // The grace window is NOT permission to skip this. An earlier version
        // consulted the grace first and so returned OPEN on a resume 131s after
        // backgrounding - which dismissed a live lock screen the user had not
        // authenticated, leaving an empty KeySession and an unusable chat. The
        // window governs how long we TRUST AN UNLOCKED KEY, not whether an
        // unauthenticated app may be opened.
        if (!alreadyUnlocked) return Gate.PROMPT

        // Unlocked and inside the trust window: nothing to do. Cold start is
        // included because a real cold start always begins with no in-memory key.
        if (isColdStart) return Gate.PROMPT

        // No observed pause (or a backwards clock) fails CLOSED. We do hold a
        // key, so this is a conservative prompt rather than a security fix.
        val away = secondsSinceBackground ?: return Gate.PROMPT
        if (away < 0) return Gate.PROMPT

        return if (away >= graceSeconds) Gate.PROMPT else Gate.OPEN
    }

    /**
     * Whether the lock screen may show the biometric prompt by itself.
     *
     * Cancelling must not become a loop. BiometricPrompt reports
     * ERROR_USER_CANCELED / ERROR_NEGATIVE_BUTTON and returns immediately, so an
     * auto-prompt keyed only on gate state would re-open the dialog forever and
     * trap the user. After [maxAutoPrompts] we require a deliberate tap.
     */
    fun shouldAutoPrompt(autoPromptAttempts: Int, maxAutoPrompts: Int = 2): Boolean =
        autoPromptAttempts < maxAutoPrompts
}