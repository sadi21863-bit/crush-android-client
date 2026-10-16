package com.opencode.chat.data.local

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Per-provider credential storage.
 *
 * THE BUG THIS PINS
 *
 * The store held ONE `ct`/`iv` pair, in a prefs file literally named
 * "secure_zen_key", with a master key aliased "zen_api_key_aes". Saving a second
 * provider's key destroyed the first, and switching providers back left the user
 * with nothing. The failure was silent and looked like "the app forgot my key".
 *
 * That was survivable while there was one provider. It is not survivable now:
 * OpenCode Zen's free tier is withdrawn - measured, 33 models return 402 and the
 * ten `*-free` ids return 403 - so carrying a Zen key and a second provider's key
 * at the same time is the NORMAL case, not an edge one.
 *
 * WHAT IS AND IS NOT REAL HERE
 *
 * Robolectric supplies a real Context, so these exercise the real
 * SharedPreferences layout and the real migration path. They do NOT exercise the
 * AES-GCM cipher: Robolectric ships no AndroidKeyStore provider, so the subclass
 * below swaps [SecureKeyStore.encrypt]/[decrypt] for a reversible stand-in. That
 * is a deliberate trade - the property being pinned is which SLOT a credential
 * lands in, and that is independent of the ciphertext's authenticity. The real
 * cipher stays on-device (Layer 2).
 */
@RunWith(RobolectricTestRunner::class)
class SecureKeyStoreProviderScopeTest {

    /**
     * Reversible stand-in for the Keystore cipher. Also proves the round trip
     * goes through [SecureKeyStore.encrypt]/[decrypt] rather than anything
     * provider-specific, and makes a swapped-slot bug visible as a value
     * mismatch instead of an assertion that accidentally passes.
     */
    private class ReversibleStore(context: Context) : SecureKeyStore(context) {
        override fun encrypt(value: String): Pair<String, String> =
            value.reversed() to "iv"

        override fun decrypt(cipherText: String, iv: String): String = cipherText.reversed()
    }

    private fun store(): SecureKeyStore = ReversibleStore(RuntimeEnvironment.getApplication())

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(SecureKeyStore.PREFS, Context.MODE_PRIVATE)

    @Test
    fun `two providers can hold keys at the same time`() {
        val s = store()
        s.write("zen-key", "opencode-zen")
        s.write("hyper-key", "hyper")

        assertTrue(s.hasStoredCiphertext("opencode-zen"))
        assertTrue(s.hasStoredCiphertext("hyper"))
    }

    @Test
    fun `writing a second provider does NOT destroy the first`() {
        // THE regression. One shared slot made this assert fail by construction.
        val s = store()
        s.write("zen-key", "opencode-zen")

        s.write("hyper-key", "hyper")

        assertEquals("zen-key", s.read("opencode-zen"))
        assertEquals("hyper-key", s.read("hyper"))
    }

    @Test
    fun `writing the same provider twice replaces only that provider`() {
        val s = store()
        s.write("zen-old", "opencode-zen")
        s.write("hyper-key", "hyper")

        s.write("zen-new", "opencode-zen")

        assertEquals("zen-new", s.read("opencode-zen"))
        assertEquals(
            "replacing one provider's key must not disturb another's",
            "hyper-key",
            s.read("hyper")
        )
    }

    @Test
    fun `clearing one provider leaves the others alone`() {
        // clear() used to be prefs.edit().clear() - it took every provider with it.
        val s = store()
        s.write("zen-key", "opencode-zen")
        s.write("hyper-key", "hyper")

        s.clear("hyper")

        assertFalse(s.hasStoredCiphertext("hyper"))
        assertTrue("the other provider must survive", s.hasStoredCiphertext("opencode-zen"))
        assertEquals("zen-key", s.read("opencode-zen"))
    }

    @Test
    fun `clearAll removes every provider`() {
        val s = store()
        s.write("zen-key", "opencode-zen")
        s.write("hyper-key", "hyper")

        s.clearAll()

        assertFalse(s.hasStoredCiphertext("opencode-zen"))
        assertFalse(s.hasStoredCiphertext("hyper"))
    }

    @Test
    fun `clearing the legacy provider also removes a pre-migration pair`() {
        // On a device that has not been read from since the upgrade, the only
        // copy of the Zen key is the unsuffixed pair. A delete that misses it
        // reports success while the credential stays on disk.
        val ctx: Context = RuntimeEnvironment.getApplication()
        prefs(ctx).edit()
            .putString(SecureKeyStore.KEY_CIPHERTEXT, "legacy-ct")
            .putString(SecureKeyStore.KEY_IV, "legacy-iv")
            .commit()
        val s = ReversibleStore(ctx)

        s.clear(SecureKeyStore.LEGACY_PROVIDER)

        assertFalse(s.hasStoredCiphertext(SecureKeyStore.LEGACY_PROVIDER))
        assertEquals(null, prefs(ctx).getString(SecureKeyStore.KEY_CIPHERTEXT, null))
        assertEquals(null, prefs(ctx).getString(SecureKeyStore.KEY_IV, null))
    }

    @Test
    fun `an absent provider reads empty rather than another provider's key`() {
        // The dangerous one: asking for a key you do not have must NOT fall back
        // to a key you do have.
        val s = store()
        s.write("zen-key", "opencode-zen")
        assertEquals(null, s.read("hyper"))
    }

    @Test
    fun `providersWithKeys lists exactly the stored providers`() {
        val s = store()
        s.write("zen-key", "opencode-zen")
        s.write("hyper-key", "hyper")
        s.write("or-key", "openrouter")

        assertEquals(
            setOf("opencode-zen", "hyper", "openrouter"),
            s.providersWithKeys()
        )
    }

    @Test
    fun `a blank key is not stored as a credential`() {
        val s = store()
        s.write("   ", "hyper")
        assertFalse(s.hasStoredCiphertext("hyper"))
    }

    @Test
    fun `the legacy single-slot layout migrates onto the default provider`() {
        // An upgrade must not log the user out. The old layout wrote ct/iv with no
        // provider suffix, so those bytes are promoted onto opencode-zen.
        val ctx: Context = RuntimeEnvironment.getApplication()
        val s = ReversibleStore(ctx)
        s.write("existing-key", SecureKeyStore.LEGACY_PROVIDER)

        // Simulate the pre-provider layout by re-scoping the stored entry.
        val ct = prefs(ctx).getString("ct.${SecureKeyStore.LEGACY_PROVIDER}", null)!!
        val iv = prefs(ctx).getString("iv.${SecureKeyStore.LEGACY_PROVIDER}", null)!!
        prefs(ctx).edit()
            .putString(SecureKeyStore.KEY_CIPHERTEXT, ct)
            .putString(SecureKeyStore.KEY_IV, iv)
            .remove("ct.${SecureKeyStore.LEGACY_PROVIDER}")
            .remove("iv.${SecureKeyStore.LEGACY_PROVIDER}")
            .commit()

        // A fresh instance performs the migration on first access.
        val migrated = ReversibleStore(ctx)
        assertTrue(
            "the pre-provider key must be found, not treated as absent",
            migrated.hasStoredCiphertext(SecureKeyStore.LEGACY_PROVIDER)
        )
        assertEquals("existing-key", migrated.read(SecureKeyStore.LEGACY_PROVIDER))
    }

    @Test
    fun `migration does not overwrite a key already scoped to the provider`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val s = ReversibleStore(ctx)
        s.write("scoped-key", SecureKeyStore.LEGACY_PROVIDER)
        s.write("hyper-key", "hyper")

        // A stale legacy pair must not clobber the newer scoped one.
        prefs(ctx).edit()
            .putString(SecureKeyStore.KEY_CIPHERTEXT, "stale-ct")
            .putString(SecureKeyStore.KEY_IV, "stale-iv")
            .commit()

        val migrated = ReversibleStore(ctx)
        assertEquals("scoped-key", migrated.read(SecureKeyStore.LEGACY_PROVIDER))
        assertEquals("hyper-key", migrated.read("hyper"))
        assertEquals(
            "a superseded legacy pair must not linger",
            null,
            prefs(ctx).getString(SecureKeyStore.KEY_CIPHERTEXT, null)
        )
    }
}
