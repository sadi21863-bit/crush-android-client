package com.opencode.chat.ui

/**
 * Start-destination routing.
 *
 * This exists because of a real bug: `SecureKeyStore.needsUnlock` is set as a
 * SIDE EFFECT of calling read(). MainActivity read the flag from a fresh
 * instance before read() had ever run, so it was always false. A user whose key
 * was perfectly stored was then sent back to Onboarding whenever the 5-minute
 * Keystore auth window expired - exactly the behaviour the user explicitly did
 * not want ("ask once, keep it until I delete it").
 *
 * Extracted as a pure function so it can be tested without a device, a
 * Keystore, or a fingerprint. See RoutingAndTierTest.
 */
object StartRoute {

    enum class Destination { CHAT, ONBOARDING }

    /**
     * @param decrypted value returned by SecureKeyStore.read(), or null when
     *        absent or not currently decryptable
     * @param needsUnlock SecureKeyStore.needsUnlock AFTER read() has been called
     * @param prefsHaveCiphertext whether ciphertext is actually stored. This
     *        covers the root of the original bug: read() had not yet run, so
     *        needsUnlock was false and decrypted was null.
     */
    fun decide(
        decrypted: String?,
        needsUnlock: Boolean,
        prefsHaveCiphertext: Boolean
    ): Destination =
        if (!decrypted.isNullOrBlank() || needsUnlock || prefsHaveCiphertext) {
            Destination.CHAT
        } else {
            Destination.ONBOARDING
        }
}