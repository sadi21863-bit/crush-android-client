package com.crush.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.crush.mobile.util.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for the Zen API key.
 *
 * The value is AES/GCM ciphertext and the AES key lives in the Android Keystore,
 * so the file on disk is useless on its own. Backups are disabled and the app
 * refuses to render the credential (see [KeyMask]).
 *
 * WHY THERE IS NO AUTH GATING HERE
 *
 * This class used to call setUserAuthenticationRequired, which made the key
 * unusable until the user authenticated. That single decision produced a long
 * chain of defects, all of which were compatibility problems rather than
 * security wins:
 *
 *  - An auth-gated key throws UserNotAuthenticatedException from ANY Cipher
 *    operation until the user authenticates, so read() had to guess why it
 *    failed. It could not distinguish "no key stored" from "key fine, window
 *    lapsed", and reported the latter as the former - the "no API key - add one
 *    in Settings" lie that shipped to users.
 *  - A valid install with an expired 5-minute auth window re-prompted on every
 *    cold start, which is not what "ask once, keep it" means.
 *  - API 28/29 have no setUserAuthenticationParameters at all, only the
 *    deprecated validity-seconds API, so those devices needed a separate tier.
 *  - Gating the KEY forced biometric UI into the credential-save path, which is
 *    why onboarding crashed on an API 30+ device when it tried to prompt.
 *
 * The threat that gating defends against is "attacker has app-private storage
 * and code execution while the device is unlocked". The Android sandbox already
 * prevents the storage half of that on a non-rooted device, and the device lock
 * screen covers the rest. Meanwhile the app still locks itself on open via
 * AppLockPolicy, which is the control that is actually visible and useful to the
 * person holding the phone.
 *
 * If this ever ships to a store, reconsider: auth-gating belongs back, and the
 * failure modes above need solving properly rather than by removal.
 */
/**
 * `open` because two things must be substitutable in JVM tests:
 *
 *  - [read], the boundary `require()` was abusing. The bypass it covers (caching
 *    its result, which unlocked the app before any authentication) is the single
 *    worst security bug this project has had, and it is only observable by
 *    exercising the boundary between the two classes.
 *  - [encrypt]/[decrypt], because Robolectric ships no AndroidKeyStore provider.
 *    Tests of the per-provider SLOT LAYOUT need a reversible stand-in to reach
 *    that layout at all; the real cipher stays on-device.
 *
 * A test that cannot reach those boundaries cannot catch a regression in them.
 */
open class SecureKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- per-provider entries -------------------------------------------
    //
    // ONE SLOT PER PROVIDER, and this is not tidiness.
    //
    // The store held a single `ct`/`iv` pair in a prefs file literally named
    // "secure_zen_key", with a master key aliased "zen_api_key_aes". So saving a
    // second provider's key DESTROYED the first one, and switching providers back
    // left the user with nothing. With Zen's free tier withdrawn, carrying a Zen
    // key and a Hyper key at the same time is the normal case, not an edge one.
    //
    // The master Keystore key stays SHARED on purpose: one AES key encrypting
    // several ciphertexts is fine, whereas a key per provider would multiply the
    // biometric/auth surface and the invalidation risk for no benefit.
    private fun ctKey(providerId: String) = "$KEY_CIPHERTEXT.$providerId"
    private fun ivKey(providerId: String) = "$KEY_IV.$providerId"

    /**
     * Moves the pre-provider single-slot layout onto [legacyProvider] on first
     * read, so an upgrade does not silently log the user out.
     *
     * Only ever promotes, never overwrites: if a scoped entry already exists the
     * legacy one is dropped, because the scoped one is the newer truth.
     */
    private fun migrateLegacyIfNeeded(providerId: String) {
        if (providerId != LEGACY_PROVIDER) return
        val scopedCt = prefs.getString(ctKey(providerId), null)
        if (!scopedCt.isNullOrBlank()) {
            // Already migrated. Drop the old pair so it cannot resurface.
            if (!prefs.getString(KEY_CIPHERTEXT, null).isNullOrBlank()) {
                prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
            }
            return
        }
        val legacyCt = prefs.getString(KEY_CIPHERTEXT, null)
        val legacyIv = prefs.getString(KEY_IV, null)
        if (legacyCt.isNullOrBlank() || legacyIv.isNullOrBlank()) return
        prefs.edit()
            .putString(ctKey(providerId), legacyCt)
            .putString(ivKey(providerId), legacyIv)
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .apply()
        Log.i(TAG, "migrated single-slot key onto provider $providerId")
    }

    /**
     * True when an encrypted key is stored for [providerId], regardless of
     * whether it can be read right now. Routing uses this so a user with a stored
     * key is never sent back to Onboarding.
     */
    fun hasStoredCiphertext(providerId: String = LEGACY_PROVIDER): Boolean {
        migrateLegacyIfNeeded(providerId)
        return !prefs.getString(ctKey(providerId), null).isNullOrBlank() &&
            !prefs.getString(ivKey(providerId), null).isNullOrBlank()
    }

    /** Provider ids that currently have a stored key. */
    fun providersWithKeys(): Set<String> {
        migrateLegacyIfNeeded(LEGACY_PROVIDER)
        return prefs.all.keys
            .filter { it.startsWith("$KEY_CIPHERTEXT.") }
            .map { it.removePrefix("$KEY_CIPHERTEXT.") }
            .toSet()
    }

    /** True when the value has been decrypted and is held in memory. */
    var isUnlocked: Boolean = false
        private set

    /**
     * Returns the stored key, or null when absent or undecryptable.
     *
     * Cannot fail for want of authentication any more, so the result is
     * unambiguous: null means absent or corrupt, nothing else.
     */
    open fun read(providerId: String = LEGACY_PROVIDER): String? {
        migrateLegacyIfNeeded(providerId)
        val cipherText = prefs.getString(ctKey(providerId), null) ?: run {
            isUnlocked = false
            return null
        }
        val iv = prefs.getString(ivKey(providerId), null) ?: run {
            isUnlocked = false
            return null
        }
        return runCatching {
            decrypt(cipherText, iv).also { isUnlocked = true }
        }.getOrElse { e ->
            // Losing the key is far worse than keeping one we cannot read, so
            // only a genuine tamper clears it.
            when (e) {
                // GCM auth-tag mismatch: the ciphertext is corrupt or tampered
                // with, which is unrecoverable.
                is javax.crypto.AEADBadTagException -> {
                    Log.w(TAG, "ciphertext failed authentication for $providerId; clearing")
                    clear(providerId)
                }
                // The Keystore key is gone (device credentials changed, or the
                // app was restored onto different hardware).
                is android.security.keystore.KeyPermanentlyInvalidatedException -> {
                    Log.w(TAG, "keystore key invalidated; clearing")
                    clearAll()
                }
                else -> Log.w(TAG, "unexpected decrypt failure; key retained", e)
            }
            null
        }
    }

    /**
     * Encrypts [value] with the Keystore master key, returning ciphertext and IV
     * as base64 strings.
     *
     * `open` so JVM tests can substitute a reversible stand-in. Robolectric ships
     * no AndroidKeyStore provider, so anything real here cannot run on the host -
     * and the behaviour worth pinning (WHICH slot a credential lands in) does not
     * depend on the ciphertext being authentic. The real cipher is exercised
     * on-device; see the Layer 2 notes.
     */
    protected open fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    /** Reverses [encrypt]. `open` for the same reason it is. */
    protected open fun decrypt(cipherText: String, iv: String): String {
        val bytes = Base64.decode(cipherText, Base64.NO_WRAP)
        val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateMasterKey(),
            GCMParameterSpec(TAG_BITS, ivBytes)
        )
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    fun write(value: String, providerId: String = LEGACY_PROVIDER) {
        // A blank key is not a credential. Storing one would make
        // hasStoredCiphertext true for a slot that can never authenticate,
        // routing the user past onboarding into a chat that reports "no API key"
        // - the exact failure this class exists to prevent.
        if (value.isBlank()) return
        val (cipherText, iv) = encrypt(value)
        // Scoped to the provider, so a second provider's key lands beside it
        // rather than on top of it.
        prefs.edit()
            .putString(ctKey(providerId), cipherText)
            .putString(ivKey(providerId), iv)
            .apply()
        isUnlocked = true
    }

    /**
     * Removes ONE provider's key.
     *
     * Used to be `prefs.edit().clear()`, which wiped every provider. That is right
     * for "remove my key" when there was only ever one provider, and wrong the
     * moment a second exists - it silently took the other provider's credential
     * with it.
     */
    fun clear(providerId: String = LEGACY_PROVIDER) {
        val editor = prefs.edit()
            .remove(ctKey(providerId))
            .remove(ivKey(providerId))
        if (providerId == LEGACY_PROVIDER) {
            // The pre-provider layout used unsuffixed `ct`/`iv` keys. A delete
            // must take those too, or on a device that has not been read from
            // since the upgrade, "delete my key" would leave the only copy
            // behind while reporting success.
            editor.remove(KEY_CIPHERTEXT).remove(KEY_IV)
        }
        editor.apply()
        isUnlocked = false
    }

    /** Removes every provider's key. Only for an explicit "forget everything". */
    fun clearAll() {
        prefs.edit().clear().apply()
        isUnlocked = false
    }

/**
 * Whether the master key is backed by a hardware secure element.
 *
 * Reporting only. StrongBox is preferred where the hardware exists, but it is
 * not required: without auth gating, falling back to the standard Keystore is a
 * modest change in protection rather than a loss of function.
 */
fun isStrongBoxBacked(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val ks: KeyStore = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull() ?: return false

    return runCatching {
        val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return false
        val factory = javax.crypto.SecretKeyFactory.getInstance("AES", ANDROID_KEYSTORE)
        val spec = factory.getKeySpec(entry.secretKey, android.security.keystore.KeyInfo::class.java)
        if (spec !is android.security.keystore.KeyInfo) return false
        // API 31+ prefers the tri-state security level; isInsideSecureHardware is
        // the older boolean and is deprecated but still populated.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            spec.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
        } else {
            @Suppress("DEPRECATION")
            spec.isInsideSecureHardware
        }
    }.getOrDefault(false)
}

private fun isStrongBoxHardwarePresent(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun getOrCreateMasterKey(): SecretKey {
        (KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) })
            .getEntry(KEY_ALIAS, null)
            ?.let { return (it as KeyStore.SecretKeyEntry).secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        // Try StrongBox first, then the standard Keystore. Both are the SAME
        // protection model now that auth is gone, so a fallback costs almost
        // nothing - unlike the old ladder where falling through could silently
        // drop a user to ungated storage.
        val strongBox = isStrongBoxHardwarePresent(appContext)

        val attempts = if (strongBox) {
            listOf(true, false)
        } else {
            listOf(false)
        }

        var lastError: Exception? = null
        for (useStrongBox in attempts) {
            runCatching {
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .apply { if (useStrongBox) setIsStrongBoxBacked(true) }
                    .build()
                generator.init(spec)
                generator.generateKey()
            }.onSuccess {
                AppLog.i(
                    "SecureKeyStore",
                    "master key created strongbox=$useStrongBox api=${Build.VERSION.SDK_INT}"
                )
                return it
            }.onFailure {
                lastError = it as? Exception ?: RuntimeException(it)
                Log.w(TAG, "strongbox=$useStrongBox unavailable: ${it.message}")
            }
        }
        throw IllegalStateException("no usable AES key in $ANDROID_KEYSTORE", lastError)
    }

    /**
     * Public, not private: [LEGACY_PROVIDER] is the default provider argument on
     * every method here, and callers in other files reference it to name which
     * credential they mean. A private companion made that impossible to express.
     */
    companion object {
        const val TAG = "SecureKeyStore"
        const val PREFS = "secure_api_keys"

    /**
     * The provider that owned the pre-multi-provider single slot.
     *
     * Its existing key is migrated onto this id on first access, so an upgrade
     * does not log the user out. It is also the default on every provider-scoped
     * method, so callers that have not been updated keep working.
     */
    const val LEGACY_PROVIDER = "opencode-zen"
        const val KEY_CIPHERTEXT = "ct"
        const val KEY_IV = "iv"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zen_api_key_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
