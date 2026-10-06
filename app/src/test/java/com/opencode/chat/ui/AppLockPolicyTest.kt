package com.opencode.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the app-lock gate.
 *
 * A wrong answer here is either "user locked out of their own saved chats" or
 * "the key was reachable with no authentication at all", so every branch is
 * pinned. The grace-window tests are the important ones: that behaviour is
 * derived from the Keystore auth window, and getting it wrong produces a prompt
 * on every app switch or no prompt at all.
 */
class AppLockPolicyTest {

    private fun decide(
        hasCiphertext: Boolean = true,
        alreadyUnlocked: Boolean = false,
        isColdStart: Boolean = false,
        secondsSinceBackground: Long? = 0,
        graceSeconds: Long = 300
    ) = AppLockPolicy.decide(
        hasCiphertext = hasCiphertext,
        alreadyUnlocked = alreadyUnlocked,
        isColdStart = isColdStart,
        secondsSinceBackground = secondsSinceBackground,
        graceSeconds = graceSeconds
    )

    @Test
    fun `fresh install is never locked`() {
        // Nothing is stored, so there is nothing to unlock. Onboarding owns its
        // own prompt when it creates the key. Locking here would deadlock a new
        // user on a screen they cannot leave.
        assertEquals(AppLockPolicy.Gate.OPEN, decide(hasCiphertext = false, isColdStart = true))
    }

    @Test
    fun `cold start with a stored key always prompts`() {
        // This is the app-lock guarantee.
        assertEquals(AppLockPolicy.Gate.PROMPT, decide(isColdStart = true))
    }

    @Test
    fun `resume inside the grace window does not prompt WHEN a key is held`() {
        // Only safe because alreadyUnlocked=true. The Cipher still works and the
        // key is in memory, so prompting would be pure friction.
        assertEquals(
            AppLockPolicy.Gate.OPEN,
            decide(alreadyUnlocked = true, secondsSinceBackground = 299)
        )
    }

    @Test
    fun `resume past the grace window prompts even when unlocked`() {
        assertEquals(
            AppLockPolicy.Gate.PROMPT,
            decide(alreadyUnlocked = true, secondsSinceBackground = 300)
        )
    }

    @Test
    fun `REGRESSION never open without a key no matter how brief the absence`() {
        // Observed on device: the lock screen was showing, the user had not
        // authenticated, and a resume 131s after backgrounding returned OPEN
        // because the grace check ran BEFORE the unlocked check. The app was
        // briefly usable with an empty KeySession.
        for (away in listOf(0L, 1L, 60L, 131L, 299L)) {
            assertEquals(
                "away=${away}s with no key in memory must not open",
                AppLockPolicy.Gate.PROMPT,
                decide(alreadyUnlocked = false, secondsSinceBackground = away, graceSeconds = 300)
            )
        }
    }

    @Test
    fun `grace window is never a licence to skip authentication`() {
        // A short trip to another app must not bypass the lock.
        for (grace in listOf(0L, 30L, 300L, 3600L)) {
            assertEquals(
                "grace=${grace}s must not open an unauthenticated app",
                AppLockPolicy.Gate.PROMPT,
                decide(alreadyUnlocked = false, secondsSinceBackground = 1, graceSeconds = grace)
            )
        }
    }

    @Test
    fun `unauthenticated prompt does not depend on elapsed time`() {
        // Cold start and warm resume must agree when no key is held.
        assertEquals(
            AppLockPolicy.Gate.PROMPT,
            decide(alreadyUnlocked = false, isColdStart = true, secondsSinceBackground = null)
        )
        assertEquals(
            AppLockPolicy.Gate.PROMPT,
            decide(alreadyUnlocked = false, isColdStart = false, secondsSinceBackground = 5)
        )
    }

    @Test
    fun `unlocked inside grace stays open regardless of how long ago the pause was`() {
        // Key held and within the trust window: the key still works, so opening
        // is correct even after a long pause elsewhere in a multi-window session.
        assertEquals(
            AppLockPolicy.Gate.OPEN,
            decide(alreadyUnlocked = true, isColdStart = false, secondsSinceBackground = 120)
        )
    }

    @Test
    fun `cold start always prompts even if a key is somehow held`() {
        // Belt and braces: a real cold start begins with an empty KeySession.
        assertEquals(
            AppLockPolicy.Gate.PROMPT,
            decide(alreadyUnlocked = true, isColdStart = true, secondsSinceBackground = 10)
        )
    }

    @Test
    fun `strict mode with zero grace prompts on every resume`() {
        assertEquals(
            AppLockPolicy.Gate.PROMPT,
            decide(secondsSinceBackground = 0, graceSeconds = 0)
        )
    }

    @Test
    fun `unobserved pause fails closed`() {
        // secondsSinceBackground == null means onPause never ran. Guessing "open"
        // here would let a locked app through.
        assertEquals(AppLockPolicy.Gate.PROMPT, decide(secondsSinceBackground = null))
    }

    @Test
    fun `backwards clock fails closed`() {
        // Monotonic time cannot really go negative; if it reports that it did,
        // the safe answer is a prompt, not an open door.
        assertEquals(AppLockPolicy.Gate.PROMPT, decide(secondsSinceBackground = -50))
    }

    @Test
    fun `auto prompt is capped so cancel cannot loop`() {
        // BiometricPrompt returns immediately on cancel, so an uncapped effect
        // keyed on gate state would re-open the dialog forever.
        assertTrue(AppLockPolicy.shouldAutoPrompt(0))
        assertTrue(AppLockPolicy.shouldAutoPrompt(1))
        assertFalse(AppLockPolicy.shouldAutoPrompt(2))
        assertFalse(AppLockPolicy.shouldAutoPrompt(99))
    }

    @Test
    fun `no key in memory means no prompt is attempted`() {
        // Guards the ordering the gate depends on: OPEN must win when there is
        // no ciphertext, otherwise the lock screen can appear over Onboarding
        // with nothing to unlock.
        assertEquals(
            AppLockPolicy.Gate.OPEN,
            AppLockPolicy.decide(
                hasCiphertext = false,
                alreadyUnlocked = false,
                isColdStart = true,
                secondsSinceBackground = null,
                graceSeconds = 0
            )
        )
    }
}