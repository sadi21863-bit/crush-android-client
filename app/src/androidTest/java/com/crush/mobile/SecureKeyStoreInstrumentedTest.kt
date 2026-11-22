package com.crush.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.crush.mobile.data.local.SecureKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V2-9: the real cipher, on the real device.
 *
 * The JVM tests deliberately substitute `encrypt`/`decrypt` because
 * Robolectric ships no AndroidKeyStore provider; they pin WHICH slot a
 * credential lands in, not whether AES/GCM round-trips. This class closes that
 * gap by running the production [SecureKeyStore] against the device Keystore.
 *
 * Safety:
 *  - test-only provider ids, cleared in [clearTestSlots] after every test;
 *  - the app's real master key alias is used (that is the point), but no
 *    non-test slot is read, written or cleared;
 *  - no activity is launched, so the API 29 background-activity rules that
 *    blocked the O11 instrumentation attempt do not apply.
 *
 * Run:
 *   adb shell am instrument -w \
 *     -e class com.crush.mobile.SecureKeyStoreInstrumentedTest \
 *     com.crush.mobile.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SecureKeyStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SecureKeyStore(context)

    private val providerA = "v2-9-selftest-a"
    private val providerB = "v2-9-selftest-b"

    private fun prefs() =
        context.getSharedPreferences(SecureKeyStore.PREFS, Context.MODE_PRIVATE)

    private fun secret(tag: String) = "v2-9-selftest-$tag-${System.nanoTime()}"

    @After
    fun clearTestSlots() {
        store.clear(providerA)
        store.clear(providerB)
    }

    @Test
    fun realKeystoreRoundTripAndCiphertextAtRest() {
        val value = secret("roundtrip")

        store.write(value, providerA)

        assertEquals("real Keystore round trip", value, store.read(providerA))
        assertTrue(store.hasStoredCiphertext(providerA))
        assertTrue(providerA in store.providersWithKeys())

        val stored = prefs().getString(
            "${SecureKeyStore.KEY_CIPHERTEXT}.$providerA",
            null
        )
        assertFalse("ciphertext slot must not be blank", stored.isNullOrBlank())
        assertNotEquals("the plaintext key must not be stored", value, stored)
    }

    @Test
    fun tamperedCiphertextIsRejectedAndCleared() {
        val value = secret("tamper")
        store.write(value, providerA)

        val slot = "${SecureKeyStore.KEY_CIPHERTEXT}.$providerA"
        val cipherText = requireNotNull(prefs().getString(slot, null))
        val flipped = cipherText.toCharArray().also { chars ->
            chars[0] = if (chars[0] == 'A') 'B' else 'A'
        }.concatToString()
        assertTrue(
            "could not write the tampered ciphertext",
            prefs().edit().putString(slot, flipped).commit()
        )

        assertNull(
            "a GCM auth-tag failure must return null, not a forged value",
            store.read(providerA)
        )
        assertFalse(
            "a tampered slot must be cleared so it cannot keep failing",
            store.hasStoredCiphertext(providerA)
        )
    }

    @Test
    fun clearIsProviderScopedOnDevice() {
        val a = secret("a")
        val b = secret("b")
        store.write(a, providerA)
        store.write(b, providerB)

        store.clear(providerA)

        assertNull(store.read(providerA))
        assertEquals("the other provider must survive", b, store.read(providerB))
        assertFalse(store.hasStoredCiphertext(providerA))
        assertTrue(store.hasStoredCiphertext(providerB))
    }
}
