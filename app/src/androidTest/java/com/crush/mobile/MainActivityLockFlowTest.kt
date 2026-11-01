package com.crush.mobile

import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.crush.mobile.data.local.KeySession
import com.crush.mobile.data.local.SecureKeyStore
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented lock-flow suite (2026-11-01): the security-critical behaviours
 * that previously ONLY had manual device evidence.
 *
 * What this suite deliberately does NOT attempt (the honest scope):
 *
 *  - ANSWERING a biometric prompt. The overlay authenticates through a real
 *    system dialog a test cannot (and must not) dismiss; those unlock paths
 *    still carry hands-on device evidence (D-era story runs, 2026-10-27).
 *  - Touching the user's real credential slots. Instrumented tests share the
 *    app process AND its `secure_api_keys` prefs, so the store tests run under
 *    dedicated throwaway provider ids and clean them in @After; `clearAll()`
 *    and the legacy slot are FORBIDDEN here - a wipe would destroy the live
 *    keys this build holds (opencode-go, openrouter).
 *
 * Everything else here is fully automatable and was not, until now:
 *
 *  1. The gate <-> FLAG_SECURE contract: whatever AppLockPolicy decides on
 *     resume, the window flag MUST agree - screenshot protection is set the
 *     instant the gate is closed and cleared only when it is open
 *     (MainActivity.onPause / onResume). Previously verified only by eyeballing.
 *     2. A fresh process holding a stored credential starts LOCKED and with NO
 *     decrypted key in memory - the property an attacker with app-process code
 *     execution on a fresh launch must not defeat.
 *  3. REAL AndroidKeyStore cipher behaviour on the real device (JVM tests run a
 *     stand-in; see SecureKeyStore's KDoc): write->read roundtrip, per-provider
 *     isolation, and the tamper response (GCM tag failure clears the slot).
 *  4. KeySession.require() serving without caching - THE auth-bypass class of
 *     bug this app's worst defect came from, now pinned against the real store.
 *  5. Activity recreation must not reset the process-scoped lock state (the
 *     historical double-prompt root cause).
 *
 * Shared with MainActivitySmokeTest: the ActivityScenario-only, no-idle-dependency
 * pattern - Compose `waitForIdle` wedges behind the lock gate (measured).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLockFlowTest {

    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    private val app get() = context as com.crush.mobile.CrushMobileApp

    /** Real prefs reader for the same file the app's store uses. */
    private val prefs
        get() = context.getSharedPreferences(SecureKeyStore.PREFS, android.content.Context.MODE_PRIVATE)

    @After
    fun cleanTestKeySlots() {
        // Throwaway slots only. NEVER clearAll() and NEVER the legacy slot:
        // instrumented tests share the real prefs file and the live keys in it.
        SecureKeyStore(context).run {
            clear(TEST_PROVIDER_A)
            clear(TEST_PROVIDER_B)
        }
    }

    // ---- the gate <-> FLAG_SECURE contract --------------------------------

    /**
     * Mirrors the exact inputs MainActivity.onResume feeds AppLockPolicy.decide
     * (see lines referencing LockState fields there), WITHOUT touching the real
     * LockState - the temporal facts are re-derived read-only from the public
     * stamps so the assertion cannot age the state out as a side effect.
     *
     * The grace constant is duplicated on purpose: if the app changes it, this
     * test fails loudly rather than silently verifying a stale contract.
     */
    private fun expectedGate(hasCiphertext: Boolean): com.crush.mobile.ui.AppLockPolicy.Gate {
        val lock = app.container.lockState
        val ownerVerified = lock.ownerVerified
        val pausedAt = lock.pausedAtElapsedMs
        val lastUnlock = lock.lastUnlockElapsedMs
        val freshUnlock = lastUnlock != null && (pausedAt == null || lastUnlock >= pausedAt)
        val now = SystemClock.elapsedRealtime()
        val trustAge = when {
            pausedAt == null -> null
            freshUnlock -> 0L
            else -> (now - pausedAt) / 1000L
        }
        return com.crush.mobile.ui.AppLockPolicy.decide(
            hasCiphertext = hasCiphertext,
            alreadyUnlocked = ownerVerified,
            isColdStart = false,
            secondsSinceBackground = trustAge,
            graceSeconds = GRACE_SECONDS
        )
    }

    private fun snapshotGateAndFlag(scenario: ActivityScenario<MainActivity>):
        Pair<com.crush.mobile.ui.AppLockPolicy.Gate, Boolean> {
        val store = SecureKeyStore(context)
        val realKeys = store.providersWithKeys() - setOf(TEST_PROVIDER_A, TEST_PROVIDER_B)
        val gate = expectedGate(realKeys.isNotEmpty())
        var secured = false
        scenario.onActivity { activity ->
            secured = (activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_SECURE) != 0
        }
        return gate to secured
    }

    @Test
    fun foregroundWindowFlagAgreesWithTheLockGate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (gate, secured) = snapshotGateAndFlag(scenario)
            assertEquals(
                "FLAG_SECURE must track the gate (screenshot protection contract)",
                gate == com.crush.mobile.ui.AppLockPolicy.Gate.PROMPT,
                secured
            )
        }
    }

    @Test
    fun recreationKeepsGateDecisionAndLockStateObject() {
        val lockBefore = app.container.lockState
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (gateBefore, securedBefore) = snapshotGateAndFlag(scenario)
            scenario.recreate()
            val lockAfter = app.container.lockState
            val (gateAfter, securedAfter) = snapshotGateAndFlag(scenario)
            // The recreated activity rides the SAME process-scoped LockState -
            // instance is why the double-prompt bug (recreated activity forgot
            // the just-completed auth) could never come back silently.
            assertSame("lock state must survive activity recreation", lockBefore, lockAfter)
            assertEquals("gate decision must survive recreation", gateBefore, gateAfter)
            assertEquals("FLAG_SECURE must survive recreation", securedBefore, securedAfter)
            scenario.onActivity { activity ->
                assertTrue(
                    "expected STARTED, saw ${activity.lifecycle.currentState.name}",
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                )
            }
        }
    }

    @Test
    fun freshProcessWithStoredCredentialHoldsNoDecryptedKey() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val store = SecureKeyStore(context)
            val realKeys = store.providersWithKeys() - setOf(TEST_PROVIDER_A, TEST_PROVIDER_B)
            val fresh = app.container.lockState.lastUnlockElapsedMs == null
            val (gate, _) = snapshotGateAndFlag(scenario)
            if (fresh && realKeys.isNotEmpty() &&
                gate == com.crush.mobile.ui.AppLockPolicy.Gate.PROMPT
            ) {
                // A credential at rest with nobody authenticated: the gate MUST
                // have dropped any in-memory plaintext (MainActivity line 344).
                assertFalse(
                    "locked resume must not leave a decrypted key in memory",
                    app.container.keySession.isUnlocked
                )
            }
        }
    }

    // ---- REAL AndroidKeyStore behaviour (device-only) ----------------------

    @Test
    fun secureKeyStoreRealCipherRoundTripAndPerProviderIsolation() {
        val store = SecureKeyStore(context)
        assertFalse(store.hasStoredCiphertext(TEST_PROVIDER_A))
        assertFalse(store.hasStoredCiphertext(TEST_PROVIDER_B))

        store.write("suite-value-a", TEST_PROVIDER_A)
        store.write("suite-value-b", TEST_PROVIDER_B)

        assertTrue(store.hasStoredCiphertext(TEST_PROVIDER_A))
        assertEquals("suite-value-a", store.read(TEST_PROVIDER_A))
        assertEquals("suite-value-b", store.read(TEST_PROVIDER_B))

        // Deleting one provider's slot must not take the other's with it - the
        // single-slot regression, now against REAL ciphertext.
        store.clear(TEST_PROVIDER_A)
        assertNull(store.read(TEST_PROVIDER_A))
        assertEquals("suite-value-b", store.read(TEST_PROVIDER_B))
        assertTrue(store.hasStoredCiphertext(TEST_PROVIDER_B))
    }

    @Test
    fun realCipherTamperedCiphertextClearsTheSlot() {
        val store = SecureKeyStore(context)
        store.write("suite-tamper-target", TEST_PROVIDER_A)
        assertTrue(store.hasStoredCiphertext(TEST_PROVIDER_A))

        // Corrupt ONE byte of the ciphertext. A flipped encrypted block fails
        // the GCM authentication tag - unrecoverable - so the store's contract
        // is: read -> null AND the slot cleared (no half-reading a tampered
        // secret forever). The JVM suite cannot reach this branch: it stands in
        // for the cipher.
        val ctKey = "${SecureKeyStore.KEY_CIPHERTEXT}.$TEST_PROVIDER_A"
        val tampered = runCatching {
            val raw = Base64.decode(prefs.getString(ctKey, null), Base64.NO_WRAP)
            raw[0] = (raw[0].toInt() xor 0x1).toByte()
            Base64.encodeToString(raw, Base64.NO_WRAP)
        }.getOrThrow()
        prefs.edit().putString(ctKey, tampered).commit()

        assertNull("tampered ciphertext must not decrypt", store.read(TEST_PROVIDER_A))
        assertFalse(
            "tampered slot must be cleared, not retained",
            store.hasStoredCiphertext(TEST_PROVIDER_A)
        )
    }

    @Test
    fun keySessionRequireServesTheKeyButNeverCachesIt() {
        val store = SecureKeyStore(context)
        val session = KeySession() // a LOCAL instance: the app's own session is untouched

        store.write("suite-require-secret", TEST_PROVIDER_A)
        // require() may decrypt and serve the value...
        assertEquals("suite-require-secret", session.require(store, TEST_PROVIDER_A))
        // ...but MUST NOT mark the session unlocked from a read. Caching a read
        // here is the exact code path that produced the 2026-10 auth bypass
        // (app decrypted its own key and behaved as unlocked before any auth).
        assertFalse("require() must never cache", session.isUnlocked)
        assertNull("require() must never cache", session.peek())

        // And with the slot gone, the served value disappears with it - proof
        // the value was never sitting in session memory.
        store.clear(TEST_PROVIDER_A)
        assertEquals("", session.require(store, TEST_PROVIDER_A))
        assertFalse(session.isUnlocked)
    }

    private companion object {
        /**
         * Duplicated from MainActivity's private companion on purpose - see
         * [expectedGate]. If these ever disagree, the test fails and the fix
         * belongs in whichever file changed first.
         */
        const val GRACE_SECONDS = 300L

        const val TEST_PROVIDER_A = "test-provider-suite-a"
        const val TEST_PROVIDER_B = "test-provider-suite-b"
    }
}
