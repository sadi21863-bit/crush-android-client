package com.opencode.chat.domain.model

data class AppSettings(
    /**
     * Whether a key is stored - NEVER the key itself.
     *
     * This used to be `apiKey: String` carrying the decrypted value, which put
     * the plaintext secret into Compose state, into every recomposition scope,
     * and into any screenshot or state dump taken while Settings was open. A
     * "hide" toggle over a field that already holds the secret is not a security
     * control. The credential now reaches exactly one place, KeySession, and the
     * UI is told only whether it exists.
     */
    val hasApiKey: Boolean = false,
    /** Last few characters only, so a user can tell WHICH key is stored. */
    val apiKeyHint: String = "",
    // Empty until a live catalogue fetch picks one. Never a hardcoded id.
    val selectedModel: String = "",
    val themeMode: String = "system",
    val enterSendMode: String = "newline",
    val temperature: Double = 0.7,
    /**
     * Provider id as Crush's Catwalk catalog names it. Verified on device by
     * listing /v1/workspaces/{id}/providers: the ids are `opencode-zen` and
     * `opencode-go`. Plain `opencode` does NOT exist and yields
     * HTTP 500 {"message":"provider with ID opencode not found"}.
     */
    val providerId: String = "opencode-zen",
    /**
     * How the agent's tool calls are authorised. Defaults to ASK.
     *
     * Was effectively hardcoded to auto-approve ("yolo") at workspace creation,
     * so the agent approved its own file edits with no prompt. Blast radius is
     * app-private storage, so it cannot reach a user's photos or messages, but it
     * is still not something to hand to someone else.
     */
    val permissionMode: PermissionMode = PermissionMode.ASK,
    /** User overrides for static auto-tuning. Null = "let auto decide". */
    val tuning: TuningPolicy.Overrides = TuningPolicy.Overrides()
)
