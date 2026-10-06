package com.opencode.chat.data.local

/**
 * Process-scoped holder for the decrypted API key.
 *
 * This exists to fix the "no API key - add one in Settings" bug (B2). The
 * Keystore master key is gated by a 300-second auth window, so every read of the
 * key is a Cipher operation that throws UserNotAuthenticatedException once that
 * window lapses. Before this class, three separate call sites each did their own
 * `SecureKeyStore.read()`, so a single long session could decrypt the key at
 * bootstrap, fail 200 messages later when the window closed, and report that the
 * user had never supplied a key at all.
 *
 * The key is now decrypted at most once per unlock and served from memory after
 * that. Two things follow:
 *
 *  - The chat session no longer depends on the auth window staying open.
 *  - "Key present but locked" stops being a state the UI has to guess about;
 *    [isUnlocked] is the single source of truth.
 *
 * SECURITY: this is plaintext in the app process heap. That is the same
 * exposure the Keystore already accepts while its auth window is open, and it is
 * required for the chat session to survive past that window. It is never written
 * to disk, never logged, and [clear] is called when the app locks.
 */
class KeySession {

    @Volatile
    private var cached: String? = null

    val isUnlocked: Boolean
        get() = !cached.isNullOrBlank()

    /**
     * The decrypted key, or null. Never log the result of this.
     */
    fun peek(): String? = cached?.takeIf { it.isNotBlank() }

    /**
     * Marks the session unlocked. ONLY call this after the user has actually
     * authenticated, or when storing a brand-new key they just typed.
     *
     * Every other code path must go through [require], which serves the key
     * without changing the lock state. Getting this wrong is a full auth bypass:
     * [isUnlocked] is the single input [com.opencode.chat.ui.AppLockPolicy] uses
     * to decide whether to show the lock screen.
     */
    fun put(key: String) {
        if (key.isBlank()) return
        cached = key
    }

    /** Called when the app locks or the user removes the key. */
    fun clear() {
        cached = null
    }

    /**
     * The key, falling back to a decrypt when nothing is cached yet.
     *
     * SECURITY: the fallback decrypt does NOT unlock the app.
     *
     * Since the API-30 crash fix the Keystore key is encrypted but NOT
     * auth-gated, so [SecureKeyStore.read] always succeeds - including while the
     * app is locked. Caching that result here set `isUnlocked = true`, and
     * `isUnlocked` is what [com.opencode.chat.ui.AppLockPolicy] trusts to decide
     * whether to show the lock screen. Because bootstrap calls this during
     * startup, the app decrypted its own key and marked itself unlocked before
     * the user ever touched the fingerprint sensor - so cancelling the prompt
     * appeared to do nothing and a blank tap let the chat through.
     *
     * Only [LockOverlay]'s post-authentication path may call [put]. This method
     * serves the key to code that already decided it is allowed to run, and must
     * not change the lock state as a side effect.
     */
    fun require(store: SecureKeyStore): String {
        peek()?.let { return it }
        // Deliberately NOT cached. Reading is a one-off decryption for work that
        // is already authorised (a chat in progress, a settings screen the user
        // already unlocked); caching it here is what granted the bypass.
        return runCatching { store.read() }.getOrNull().orEmpty()
    }
}