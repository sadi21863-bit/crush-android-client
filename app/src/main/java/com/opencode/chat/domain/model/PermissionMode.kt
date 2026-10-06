package com.opencode.chat.domain.model

/**
 * How the agent's tool calls are authorised.
 *
 * The default was AUTO_APPROVE (Crush calls it "yolo"), which meant the agent
 * could edit files and run commands with no prompt. That is acceptable while
 * developing and wrong to hand to another person: the whole point of asking is
 * that the human stays in the loop.
 *
 * Blast radius is bounded - the workspace is app-private storage, so the agent
 * cannot reach a friend's photos, messages or other apps - but "it decided on
 * its own" is still not something to ship without saying so.
 */
enum class PermissionMode {
    /** Prompt before each permission-gated action. The default. */
    ASK,

    /** Approve automatically. Faster, and only for a workspace you trust. */
    AUTO_APPROVE;

    /** Serialised form for settings storage. */
    val stored: String get() = if (this == AUTO_APPROVE) "auto_approve" else "ask"

    companion object {
        /**
         * Parses a stored value, defaulting to ASK.
         *
         * Fails SAFE: an unrecognised or absent value means "ask", never
         * "auto-approve". A corrupted preferences file must not silently hand
         * over the agent's permissions.
         */
        fun parse(raw: String?): PermissionMode =
            if (raw == AUTO_APPROVE.stored) AUTO_APPROVE else ASK
    }
}
