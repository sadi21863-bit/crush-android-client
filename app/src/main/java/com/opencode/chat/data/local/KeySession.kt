package com.opencode.chat.data.local

/**
 * Process-scoped holder for the decrypted API key.
 *
 * This exists to fix the "no API key - add one in Settings" bug (B2). The
 * Keystore master key WAS gated by a 300-second auth window, so every read of
 * the key was a Cipher operation that threw UserNotAuthenticatedException once that
 * window lapsed. IT IS NO LONGER AUTH-GATED - see [SecureKeyStore], where removing
 * that gate fixed an API 30+ onboarding crash. The consequence is that read()
 * now ALWAYS succeeds, including while the app is locked, which is exactly why
 * [require] must not cache its result.
 *
 * Before this class, three separate call sites each did their own
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

    /** Which provider [cached] belongs to. Empty means nothing is cached. */
    @Volatile
    private var cachedProviderId: String = ""

    val isUnlocked: Boolean
        get() = !cached.isNullOrBlank()

    /**
     * The decrypted key, or null. Never log the result of this.
     */
    fun peek(): String? = cached?.takeIf { it.isNotBlank() }

    /**
     * The cached key ONLY when it belongs to [providerId].
     *
     * The cache holds one key at a time, so a plain [peek] while a different
     * provider is selected describes the wrong credential - a masked hint built
     * from it would show the user a fragment of a key that is not the one they
     * are configuring.
     */
    fun peekFor(providerId: String): String? =
        if (cachedProviderId == providerId) peek() else null

    /**
     * Marks the session unlocked. ONLY call this after the user has actually
     * authenticated, or when storing a brand-new key they just typed.
     *
     * Every other code path must go through [require], which serves the key
     * without changing the lock state. Getting this wrong is a full auth bypass:
     * [isUnlocked] is the single input [com.opencode.chat.ui.AppLockPolicy] uses
     * to decide whether to show the lock screen.
     */
    fun put(key: String, providerId: String = SecureKeyStore.LEGACY_PROVIDER) {
        if (key.isBlank()) return
        cached = key
        cachedProviderId = providerId
    }

    /** Called when the app locks or the user removes the key. */
    fun clear() {
        cached = null
        cachedProviderId = ""
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
    fun require(
        store: SecureKeyStore,
        providerId: String = SecureKeyStore.LEGACY_PROVIDER
    ): String {
        // Only serve the cache when it belongs to the provider being asked about.
        // A cache shared across providers is the same class of bug as a single
        // credential slot: switching provider and reading hands back the PREVIOUS
        // provider's token, which then fails against the new provider - or worse,
        // authenticates against the wrong account.
        if (cachedProviderId == providerId) peek()?.let { return it }
        // Deliberately NOT cached. Reading is a one-off decryption for work that is
        // already authorised (a chat in progress, a settings screen the user
        // already unlocked); caching it here is what granted the bypass.
        return runCatching { store.read(providerId) }.getOrNull().orEmpty()
    }
}
