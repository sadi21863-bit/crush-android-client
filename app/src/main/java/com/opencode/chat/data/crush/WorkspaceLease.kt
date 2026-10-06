package com.opencode.chat.data.crush

/**
 * Why sends 404, established by measurement on the reference device.
 *
 * MEASURED, not assumed: a workspace created and then left alone is GONE after
 * roughly 30 seconds.
 *
 *     t=0s   ALIVE
 *     t=15s  ALIVE
 *     t=30s  GONE (404)
 *
 * Crush reaps a workspace that has no attached clients. So the sequence in the
 * UI was always doomed:
 *
 *   bootstrap -> workspace created -> "ready" shown
 *   ... user reads the screen, types a message ...        (30s+ elapsed)
 *   send -> POST /workspaces/{stale-id}/agent -> 404
 *
 * Every symptom already investigated was a red herring: it is not the port, not
 * the API key, not engine restarts, and not the send/re-bootstrap logic. The id
 * was already dead before send() ever ran.
 *
 * THE FIX: re-attach on every send. POST /workspaces is idempotent and keyed on
 * canonical path, so it returns a live workspace with the SAME id for the same
 * path. That makes the cached id safe to re-acquire rather than persist.
 */
object WorkspaceLease {

    /** Comfortably inside the measured ~30s window. */
    const val FRESH_SEC = 15

    /**
     * Whether the cached workspace id can still be trusted.
     *
     * Fails closed on an unknown timestamp: if we cannot prove the id is fresh,
     * re-attach. A redundant re-attach costs one cheap idempotent POST, whereas
     * trusting a stale id costs the user their message.
     */
    fun isFresh(lastAttachedAtMs: Long?, nowMs: Long, freshSec: Int = FRESH_SEC): Boolean {
        val last = lastAttachedAtMs ?: return false
        val age = nowMs - last
        // A backwards clock means we cannot reason about the age.
        if (age < 0) return false
        return age < freshSec * 1000L
    }

    /**
     * A user-visible message for a 404, so the app never shows a raw HTTP dump.
     *
     * The engine dropped the workspace mid-turn; this is recoverable and the
     * message must not read as a server fault.
     */
    fun explainReattach(error: String?): String =
        if (error != null && error.contains("workspace not found", ignoreCase = true)) {
            "The engine released the workspace. Retrying..."
        } else {
            "Send failed"
        }
}
