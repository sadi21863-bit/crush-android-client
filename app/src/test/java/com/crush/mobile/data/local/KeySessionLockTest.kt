package com.crush.mobile.data.local

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Stub for the Keystore-backed store.
 *
 * Exists because the API-30 crash fix removed auth-gating from the Keystore key,
 * which means `SecureKeyStore.read()` now ALWAYS succeeds - there is no exception
 * left to distinguish "user has not authenticated" from "key is absent". That is
 * exactly the condition under which `require()` used to cache the key and report
 * the app unlocked, so a double that can return a key on demand is the only way
 * to exercise the boundary.
 *
 * A real Robolectric Context is passed rather than a hand-rolled one:
 * android.content.Context has hundreds of abstract members, and a fake that
 * throws on unexpected calls is more code to keep correct than the issue it
 * guards against.
 */
private class FakeSecureKeyStore(context: Context, private val value: String?) :
    SecureKeyStore(context) {
    var readCalls = 0
        private set

    // Ignore the provider id: this stub's job is to hand back a key without a
    // real Keystore, and the lock behaviour it guards is provider-independent.
    override fun read(providerId: String): String? {
        readCalls++
        return value
    }
}

/**
 * `KeySession` lock-state correctness.
 *
 * THE BUG THIS PINS
 *
 * `require()` used to cache the key it decrypted. Because the Keystore key is
 * encrypted but NOT auth-gated, `store.read()` always succeeds - so the first
 * bootstrap call decrypted the key, cached it, and set `isUnlocked = true`. The
 * lock gate reads `isUnlocked`, which means the app unlocked itself before the
 * user touched the fingerprint sensor: cancelling the prompt did nothing, and a
 * blank tap on the overlay let the chat straight through.
 *
 * It was invisible to every other suite because it is a property of the
 * interaction between `require()` and the real [KeySession], not of either alone.
 * The fix's own correctness is only observable by exercising that boundary, which
 * needs a store that can return a key without a Keystore - hence the stub.
 */
@RunWith(RobolectricTestRunner::class)
class KeySessionLockTest {

    private fun store(value: String? = "oc_sk_test") =
        FakeSecureKeyStore(RuntimeEnvironment.getApplication(), value)

    @Test
    fun `require serves the key without unlocking`() {
        val session = KeySession()
        val s = store()

        assertFalse("fresh session must start locked", session.isUnlocked)

        val key = session.require(s)

        assertEquals("oc_sk_test", key)
        // THE regression. Caching this result is a full authentication bypass.
        assertFalse(
            "require() must not unlock the app - isUnlocked is what the lock gate trusts",
            session.isUnlocked
        )
    }

    @Test
    fun `require does not cache, so it re-reads each time`() {
        val session = KeySession()
        val s = store()

        session.require(s)
        session.require(s)

        assertEquals(
            "each require() is a fresh decrypt; caching is what caused the bypass",
            2,
            s.readCalls
        )
    }

    @Test
    fun `only put unlocks`() {
        val session = KeySession()
        assertFalse(session.isUnlocked)
        session.put("oc_sk_test")
        assertTrue("put is the post-authentication path", session.isUnlocked)
    }

    @Test
    fun `a blank key never unlocks`() {
        val session = KeySession()
        session.put("")
        session.put("   ")
        assertFalse("a blank key is not an unlock", session.isUnlocked)
    }

    @Test
    fun `clear re-locks and drops the key from memory`() {
        val session = KeySession()
        session.put("oc_sk_test")
        assertTrue(session.isUnlocked)

        session.clear()

        assertFalse("clear must re-lock", session.isUnlocked)
        assertNull("clear must drop the key", session.peek())
    }

    @Test
    fun `require after clear re-reads but stays locked`() {
        val session = KeySession()
        val s = store()
        session.put("oc_sk_test")
        session.clear()

        val key = session.require(s)

        assertEquals("oc_sk_test", key)
        assertFalse("re-reading must not re-unlock", session.isUnlocked)
    }

    @Test
    fun `an absent key yields empty, not null, and does not unlock`() {
        val session = KeySession()
        val key = session.require(store(value = null))

        assertEquals("", key)
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `a throwing store is contained`() {
        // A decrypt that fails must not crash the app, and must never be read as
        // "the user is authenticated".
        val session = KeySession()
        val throwing = object : SecureKeyStore(RuntimeEnvironment.getApplication()) {
            override fun read(providerId: String): String? = throw IllegalStateException("keystore gone")
        }

        val key = session.require(throwing)

        assertEquals("", key)
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `peek exposes nothing while locked`() {
        assertNull(KeySession().peek())
    }

    @Test
    fun `peek exposes the key only after put`() {
        val session = KeySession()
        session.put("oc_sk_test")
        assertNotNull(session.peek())
        assertEquals("oc_sk_test", session.peek())
    }

    @Test
    fun `the gate and the key cannot disagree`() {
        // LockOverlay's contract: OPEN is only ever reachable with a key in hand.
        // If isUnlocked can be true while peek() is empty, the gate is trusting
        // something it cannot verify.
        val session = KeySession()
        val s = store()

        assertFalse(session.isUnlocked)
        assertNull(session.peek())

        // require() alone must never close that gap.
        session.require(s)
        assertFalse(session.isUnlocked)
        assertNull(session.peek())

        // Only the post-authentication path closes it, and it closes both.
        session.put("oc_sk_test")
        assertTrue(session.isUnlocked)
        assertNotNull(session.peek())
    }
}
