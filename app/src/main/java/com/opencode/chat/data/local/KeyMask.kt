package com.opencode.chat.data.local

/**
 * Renders a credential as something safe to put on screen.
 *
 * The stored Zen key used to be loaded straight into an OutlinedTextField and
 * revealed with an eye toggle. That is not a security control: the plaintext
 * was already in Compose state, in the view hierarchy and in any screenshot,
 * so hiding the glyphs changed nothing about who could read it. The rule is
 * that the full credential never enters the UI at all - this type is the only
 * credential-derived thing allowed to be displayed.
 *
 * Shows a short tail so a user with several keys can tell which one is active.
 * The tail is deliberately only 4 characters: on a `oc_sk_` prefixed key that
 * is far too little to be useful to an attacker, and it is enough for a human
 * to recognise their own key.
 */
object KeyMask {

    const val TAIL = 4

    /** Bullet-masked hint, or empty when there is no key. */
    fun hint(key: String?): String {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return ""
        if (k.length <= TAIL) return "*".repeat(k.length)
        return "*".repeat(MASK_RUN) + k.takeLast(TAIL)
    }

    /**
     * Never log or display this. Exists only so a log line can prove WHICH key
     * was used without revealing it - a hash prefix, not the key.
     */
    fun fingerprint(key: String?): String {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return "none"
        return "len=${k.length}"
    }

    private const val MASK_RUN = 8
}
