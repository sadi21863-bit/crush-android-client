package com.opencode.chat.data.crush

import com.google.gson.JsonObject

/**
 * A decoded frame from `GET /v1/workspaces/{id}/events`.
 *
 * Crush writes only `data:` lines — no `event:`, no `id:`, no heartbeat — so
 * every message is a default SSE event and callers must discriminate on [type].
 * The payload is two levels deep:
 *
 *   {"type":"<PayloadType>","payload":{"type":"created|updated|deleted","payload":{...}}}
 */
data class CrushEvent(
    val type: PayloadType,
    val change: ChangeType,
    val body: JsonObject
) {
    enum class PayloadType(val wire: String) {
        MESSAGE("message"),
        RUN_COMPLETE("run_complete"),
        PERMISSION_REQUEST("permission_request"),
        PERMISSION_NOTIFICATION("permission_notification"),
        QUESTION_REQUEST("question_batch_request"),
        QUESTION_NOTIFICATION("question_batch_notification"),
        SESSION("session"),
        AGENT_EVENT("agent_event"),
        CONFIG_CHANGED("config_changed"),
        FILE("file"),
        LSP_EVENT("lsp_event"),
        MCP_EVENT("mcp_event"),
        SKILLS_EVENT("skills_event"),
        UPDATE_AVAILABLE("update_available"),
        /** wrapEvent silently drops unknown types, so tolerate them. */
        UNKNOWN("");

        companion object {
            fun from(wire: String) = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
        }
    }

    enum class ChangeType(val wire: String) {
        CREATED("created"), UPDATED("updated"), DELETED("deleted"), UNKNOWN("");
        companion object {
            fun from(wire: String) = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
        }
    }
}

/** Authoritative end-of-turn signal, emitted once per top-level agent turn. */
data class RunComplete(
    @com.google.gson.annotations.SerializedName("session_id") val sessionId: String = "",
    @com.google.gson.annotations.SerializedName("run_id") val runId: String = "",
    @com.google.gson.annotations.SerializedName("message_id") val messageId: String = "",
    val text: String = "",
    val cancelled: Boolean = false
)

/**
 * A message as delivered by `message` events.
 *
 * CRITICAL: Crush republishes the WHOLE message on every update rather than
 * appending a delta, so text parts must be REPLACED, never appended. Appending
 * produces quadratic duplicated text — the single most likely integration bug.
 */
data class StreamMessage(
    val id: String = "",
    val role: String = "",
    @com.google.gson.annotations.SerializedName("session_id") val sessionId: String = "",
    val parts: List<JsonObject> = emptyList()
) {
    /** Concatenated text across all non-hidden text parts. */
    val text: String
        get() = parts.filter { it.optString("type") == "text" }
            .joinToString("") { it.data()?.optString("text").orEmpty() }
            .takeIf { part -> parts.none { it.optString("type") == "text" && it.data()?.optBoolean("hidden") == true } }
            .orEmpty()

    val thinking: String
        get() = parts.filter { it.optString("type") == "reasoning" }
            .joinToString("\n") { it.data()?.optString("thinking").orEmpty() }

    val isFinished: Boolean
        get() = parts.any { it.optString("type") == "finish" }

    /**
     * A `finish` part with reason "error" means the turn produced no text because
     * the provider call failed (e.g. `Unauthorized / Invalid API key.`). A turn
     * can be `isFinished` and still have failed, so callers must check this too.
     */
    val finishError: String?
        get() = parts.firstOrNull { it.optString("type") == "finish" }
            ?.data()
            ?.takeIf { it.optString("reason") == "error" }
            ?.let { d ->
                listOfNotNull(d.optString("message"), d.optString("details"))
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
            }
            ?.ifBlank { null }

    val toolCalls: List<JsonObject>
        get() = parts.filter { it.optString("type") == "tool_call" }
}

/**
 * Reads the `data` sub-object, or null when absent/null/not an object.
 *
 * Must NOT use getAsJsonObject(): that throws ClassCastException when the value
 * is JsonNull (i.e. `"data": null`), which crashes the app on malformed engine
 * output. Caught by a unit test using {"type":"text","data":null}.
 */
private fun JsonObject.data(): JsonObject? {
    val d = get("data")
    return if (d != null && d.isJsonObject) d.asJsonObject else null
}

internal fun JsonObject.optString(key: String): String? =
    if (has(key) && !get(key).isJsonNull) get(key).asString else null

internal fun JsonObject.optBoolean(key: String): Boolean? =
    if (has(key) && !get(key).isJsonNull) get(key).asBoolean else null