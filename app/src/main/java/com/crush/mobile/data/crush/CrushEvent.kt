package com.crush.mobile.data.crush

import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
     *
     * Real provider errors arrive in at least two shapes: `message` plus a
     * STRING `details`, and — on the ChatCompletions error bodies Crush forwards
     * verbatim — `details` as a nested OBJECT (`{"message":"…","error":{…}}`).
     * Reading an object with asString throws, which crashed the stream-update
     * lambda and replaced the server's actual wording with a generic exception
     * string mid-turn. Both shapes are handled; object `details` is mined one
     * level deep, and a reason-error with no extractable text falls back to an
     * explicit marker so the bubble cannot render as a mistimed "no reply
     * received" when the turn actually FAILED.
     */
    val finishError: String?
        get() = parts.firstOrNull { it.optString("type") == "finish" }
            ?.data()
            ?.takeIf { it.optString("reason") == "error" }
            ?.let { d ->
                val message = d.optString("message")
                val details = detailsText(d)
                listOfNotNull(message, details)
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
                    .takeIf { it.isNotBlank() } ?: "provider error with no message"
            }

    /**
     * The `details` field of a finish-error, as text when it is one:
     * a string is taken verbatim, an object is mined for a nested `message`
     * or `error` STRING (one level; second-level nesting is dropped rather
     * than guessed at), and anything else contributes nothing.
     */
    private fun detailsText(d: JsonObject): String? {
        return when {
            d.has("details") && d.get("details").isJsonObject ->
                d.optObject("details")?.let { obj ->
                    obj.optString("message") ?: obj.optString("error")
                }
            else -> d.optString("details")
        }
    }

    val toolCalls: List<JsonObject>
        get() = parts.filter { it.optString("type") == "tool_call" }

    /**
     * Engine `tool_result` parts, i.e. the result of a call that ACTUALLY ran.
     *
     * v0.100.0 persists each result as its own `role="tool"` message whose
     * only part is `{"type":"tool_result","data":{...}}`. The data carries
     * `tool_call_id`, `is_error`, and a `metadata` field that is a
     * JSON-ENCODED STRING (not an object) of engine execution facts:
     * `{"start_time":1794621312209,"end_time":…,"output":"…"}` (epoch ms,
     * confirmed from the on-device DB 2026-11-14).
     *
     * The start/end pair is the ONLY real execution duration on the wire.
     * The assistant tool-call frame's `finished` flag is part-final and can
     * precede execution by minutes (sleep-8 finding, 2026-11-14), so this
     * accessor is what lets the UI complete a row honestly and show a true
     * duration instead of frame latency.
     */
    val toolResults: List<ToolResult>
        get() = parts.filter { it.optString("type") == "tool_result" }
            .mapNotNull { part ->
                val d = part.data() ?: return@mapNotNull null
                ToolResult(
                    toolCallId = d.optString("tool_call_id").orEmpty(),
                    failed = d.optBoolean("is_error") ?: false,
                    durationMs = parseResultDurationMs(d.optString("metadata"))
                )
            }

    /**
     * Detached sub-agent reports attached to this message.
     *
     * v0.100.0 writes a completed/failed sub-agent run back as a NEW USER
     * message whose only part is `{"type":"sub_agent_report","data":{...}}`
     * (upstream `internal/proto/message.go`, `internal/agent/agent.go`). The
     * part carries `label`, `output`, and optional `failed`/`model`. A report
     * has no text part, which is exactly why the old replay dropped it.
     */
    val subAgentReports: List<SubAgentReport>
        get() = parts.filter { it.optString("type") == "sub_agent_report" }
            .mapNotNull { part ->
                val d = part.data() ?: return@mapNotNull null
                SubAgentReport(
                    label = d.optString("label").orEmpty(),
                    output = d.optString("output").orEmpty(),
                    failed = d.optBoolean("failed") ?: false,
                    model = d.optString("model").orEmpty()
                )
            }
}

/**
 * One detached sub-agent's result, as v0.100.0 publishes it.
 *
 * Field names and the user-role carrier are confirmed from the v0.100.0
 * source (not guessed): `proto/message.go` builds the part and
 * `agent/agent.go` appends it as a user message. `failed` and `model` are
 * omitempty on the wire, hence the safe defaults.
 */
data class SubAgentReport(
    val label: String = "",
    val output: String = "",
    val failed: Boolean = false,
    val model: String = ""
)

/**
 * One tool execution result, as a v0.100.0 `role="tool"` message carries it.
 *
 * [durationMs] is the engine's own `end_time - start_time` from the metadata
 * string, or 0 when the metadata is absent/malformed or the pair is inverted -
 * the UI then shows no duration rather than a guessed one.
 */
data class ToolResult(
    val toolCallId: String = "",
    val failed: Boolean = false,
    val durationMs: Long = 0L
)

/**
 * Extracts `end_time - start_time` (epoch ms) from a tool result's `metadata`.
 *
 * The field is a JSON STRING on the wire, not an object, so it needs a second
 * parse. Anything unexpected yields 0: a wrong duration is worse than none.
 */
private fun parseResultDurationMs(metadata: String?): Long {
    if (metadata.isNullOrBlank()) return 0L
    val root = runCatching { JsonParser.parseString(metadata) }.getOrNull() ?: return 0L
    if (!root.isJsonObject) return 0L
    val obj = root.asJsonObject
    val start = obj.optLongOrNull("start_time") ?: return 0L
    val end = obj.optLongOrNull("end_time") ?: return 0L
    return if (end >= start) end - start else 0L
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

/**
 * Reads the named member as a Json object, or null when absent/not an object.
 * Pair of [data] for nested-size access, same safety rule.
 */
private fun JsonObject.optObject(key: String): JsonObject? {
    val d = get(key)
    return if (d != null && d.isJsonObject) d.asJsonObject else null
}

/**
 * Reads the member as its string value ONLY when it is a JSON primitive.
 *
 * Must NOT use getAsJsonPrimitive().asString blindly: asString throws
 * UnsupportedOperationException on objects/arrays, which turned a nested
 * provider error (`"details":{...}`) into a mid-turn crash instead of a
 * message. Non-primitive members and JsonNull both return null.
 */
internal fun JsonObject.optString(key: String): String? =
    if (has(key) && get(key).isJsonPrimitive) get(key).asString else null

internal fun JsonObject.optBoolean(key: String): Boolean? =
    if (has(key) && !get(key).isJsonNull) get(key).asBoolean else null

/**
 * Reads a numeric primitive as Long, or null when it is absent, null, or not
 * a parseable number. Same safety rule as [optString]: never let a malformed
 * engine field throw out of a parser.
 */
private fun JsonObject.optLongOrNull(key: String): Long? =
    if (has(key) && get(key).isJsonPrimitive) {
        runCatching { get(key).asLong }.getOrNull()
    } else {
        null
    }