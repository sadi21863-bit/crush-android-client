package com.opencode.chat.data.local

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.opencode.chat.util.AppLog
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
class SecureKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True when an encrypted key is stored, regardless of whether it can be read
     * right now. Routing uses this so a user with a stored key is never sent back
     * to Onboarding.
     */
    fun hasStoredCiphertext(): Boolean =
        !prefs.getString(KEY_CIPHERTEXT, null).isNullOrBlank() &&
            !prefs.getString(KEY_IV, null).isNullOrBlank()

    /** True when the value has been decrypted and is held in memory. */
    var isUnlocked: Boolean = false
        private set

    /**
     * Returns the stored key, or null when absent or undecryptable.
     *
     * Cannot fail for want of authentication any more, so the result is
     * unambiguous: null means absent or corrupt, nothing else.
     */
    fun read(): String? {
        val cipherText = prefs.getString(KEY_CIPHERTEXT, null) ?: run {
            isUnlocked = false
            return null
        }
        val iv = prefs.getString(KEY_IV, null) ?: run {
            isUnlocked = false
            return null
        }
        return runCatching {
            val bytes = Base64.decode(cipherText, Base64.NO_WRAP)
            val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateMasterKey(),
                GCMParameterSpec(TAG_BITS, ivBytes)
            )
            String(cipher.doFinal(bytes), Charsets.UTF_8).also { isUnlocked = true }
        }.getOrElse { e ->
            // Losing the key is far worse than keeping one we cannot read, so
            // only a genuine tamper clears it.
            when (e) {
                // GCM auth-tag mismatch: the ciphertext is corrupt or tampered
                // with, which is unrecoverable.
                is javax.crypto.AEADBadTagException -> {
                    Log.w(TAG, "ciphertext failed authentication; clearing")
                    clear()
                }
                // The Keystore key is gone (device credentials changed, or the
                // app was restored onto different hardware).
                is android.security.keystore.KeyPermanentlyInvalidatedException -> {
                    Log.w(TAG, "keystore key invalidated; clearing")
                    clear()
                }
                else -> Log.w(TAG, "unexpected decrypt failure; key retained", e)
            }
            null
        }
    }

    fun write(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        isUnlocked = true
    }

    fun clear() {
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

    private companion object {
        const val TAG = "SecureKeyStore"
        const val PREFS = "secure_zen_key"
        const val KEY_CIPHERTEXT = "ct"
        const val KEY_IV = "iv"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zen_api_key_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
