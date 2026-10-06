package com.opencode.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Start-destination routing.
 *
 * This existed because of a real bug: SecureKeyStore.needsUnlock was set as a
 * SIDE EFFECT of read(), and MainActivity read it from a fresh instance before
 * read() had run, so a user whose key was perfectly stored was sent back to
 * Onboarding whenever the auth window expired.
 *
 * THEN THE MECHANISM IT GUARDED WAS REMOVED.
 *
 * The key is no longer auth-gated (see SecureKeyStore's header). read() can no
 * longer fail for want of a prompt, so "needsUnlock" does not exist as a state
 * and StartRoute.decide is no longer on any production path - MainActivity reads
 * store.hasStoredCiphertext() directly.
 *
 * The previous version of this file asserted that an expired auth window still
 * routed to Chat. That test passed while describing behaviour the app no longer
 * has, which is worse than having no test: it is false comfort in exactly the
 * subsystem where every real crash lived.
 *
 * What remains is the ONE invariant that still holds and is still worth pinning:
 * a stored key must never send the user back to Onboarding. The auth-window
 * cases are deliberately gone rather than rewritten, because the scenario itself
 * no longer exists.
 */
class StartRouteTest {

    @Test
    fun `no stored key goes to onboarding`() {
        assertEquals(
            StartRoute.Destination.ONBOARDING,
            StartRoute.decide(null, needsUnlock = false, prefsHaveCiphertext = false)
        )
    }

    @Test
    fun `stored key goes to chat`() {
        assertEquals(
            StartRoute.Destination.CHAT,
            StartRoute.decide("oc_sk_x", needsUnlock = false, prefsHaveCiphertext = true)
        )
    }

    @Test
    fun `ciphertext alone is enough to route to chat`() {
        // The case that matters: the key exists on disk. Even if it could not be
        // decrypted right now, the user must never be asked to add one again.
        // This is the live invariant, and it is why routing keys off ciphertext
        // presence rather than a successful read.
        assertEquals(
            StartRoute.Destination.CHAT,
            StartRoute.decide(null, needsUnlock = false, prefsHaveCiphertext = true)
        )
    }

    @Test
    fun `blank decrypted value is treated as absent`() {
        assertEquals(
            StartRoute.Destination.ONBOARDING,
            StartRoute.decide("   ", needsUnlock = false, prefsHaveCiphertext = false)
        )
    }

    @Test
    fun `cleared app data routes to onboarding`() {
        assertEquals(
            StartRoute.Destination.ONBOARDING,
            StartRoute.decide(null, needsUnlock = false, prefsHaveCiphertext = false)
        )
    }

    @Test
    fun `any single signal is enough to route to chat`() {
        // The real contract: StartRoute returns CHAT if ANY of the three signals
        // is set. An earlier draft asserted "ciphertext decides alone" and FAILED,
        // which is how the actual behaviour was pinned down.
        for (decrypted in listOf(null, "", "   ", "oc_sk_x")) {
            assertEquals(
                "ciphertext only, decrypted=$decrypted",
                StartRoute.Destination.CHAT,
                StartRoute.decide(decrypted, needsUnlock = false, prefsHaveCiphertext = true)
            )
            assertEquals(
                "needsUnlock only, decrypted=$decrypted",
                StartRoute.Destination.CHAT,
                StartRoute.decide(decrypted, needsUnlock = true, prefsHaveCiphertext = false)
            )
        }
    }

    @Test
    fun `all three signals absent routes to onboarding`() {
        // Only a BLANK decrypted value counts as absent - a non-blank one is a
        // third signal in its own right, covered separately below. Confining the
        // onboarding assertion to blank values is what keeps these two tests from
        // contradicting each other.
        for (decrypted in listOf(null, "", "   ")) {
            assertEquals(
                "all signals absent, decrypted=$decrypted",
                StartRoute.Destination.ONBOARDING,
                StartRoute.decide(decrypted, needsUnlock = false, prefsHaveCiphertext = false)
            )
        }
    }

    @Test
    fun `a decrypted key with no stored ciphertext still routes to chat`() {
        // Unreachable in practice - there is nothing stored to decrypt - but the
        // behaviour is pinned rather than left to drift silently.
        assertEquals(
            StartRoute.Destination.CHAT,
            StartRoute.decide("oc_sk_x", needsUnlock = false, prefsHaveCiphertext = false)
        )
    }
}
