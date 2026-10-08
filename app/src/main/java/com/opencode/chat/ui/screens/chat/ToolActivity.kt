package com.opencode.chat.ui.screens.chat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One tool invocation made by the agent, as shown to the user.
 *
 * WHY THIS EXISTS
 *
 * The engine can read files, write files, run shell commands and search. Until
 * now all of that happened invisibly: the user saw a text box and a sentence
 * appearing, with no indication that the agent had read their files or run
 * commands on their behalf. That is the single largest gap between this app and
 * a real coding agent - Claude Code and Codex show every tool call, its
 * arguments, and its output, because an agent you cannot watch is an agent you
 * cannot supervise.
 *
 * FIELD NAMES ARE A GUESS, AND THAT MATTERS
 *
 * The only fixture available while writing this was
 * `{"type":"tool_call","data":{"name":"read"}}`, which is not enough to build a
 * UI on. So [ToolActivity.from] reads each field through [pickString], which
 * tries several plausible wire names and returns null when none are present.
 * That is deliberate: guessing a single hard name and getting it wrong is how
 * this project shipped a build that failed every turn with "large model not
 * found in provider".
 *
 * Consequence: an unrecognised field yields null, not a crash and not a
 * fabricated value. The UI degrades to showing the tool name alone. When the
 * real wire format is confirmed, correct the names in WIRE_* below and delete
 * the fallbacks - nothing else in the UI needs to change.
 */
data class ToolActivity(
    /** Engine call id when present; otherwise synthesised so the list is stable. */
    val id: String,
    /** Tool name, e.g. read / write / bash / list. */
    val name: String,
    /** Arguments as pretty JSON, or empty when the engine sent none. */
    val args: String,
    val state: ToolState,
    /** Result or partial output. Truncated for display; full text is not kept. */
    val output: String,
    val error: String? = null
) {
    /** A running tool should read as live, not as finished. */
    val isRunning: Boolean get() = state == ToolState.RUNNING || state == ToolState.PENDING

    /** One-line summary for the collapsed row. */
    fun summary(): String = when (state) {
        ToolState.COMPLETED -> name
        ToolState.FAILED -> "$name failed"
        ToolState.RUNNING -> "$name running"
        ToolState.PENDING -> "$name pending"
        ToolState.UNKNOWN -> name
    }
}

enum class ToolState { PENDING, RUNNING, COMPLETED, FAILED, UNKNOWN }

object ToolActivityParser {

    // Pretty-printing, so tool arguments are readable in the UI. Plain
    // Gson.toJson() emits a single compact line, which is useless when the point
    // of showing a tool call is that a human can audit it.
    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ---- candidate wire names. Correct these once the format is confirmed. ----
    private val WIRE_ID = listOf("id", "call_id", "tool_call_id", "callId", "toolCallId")
    private val WIRE_NAME = listOf("name", "tool", "tool_name", "toolName")
    private val WIRE_ARGS = listOf("arguments", "args", "input", "parameters", "params")
    private val WIRE_STATE = listOf("state", "status", "statuses")
    private val WIRE_OUTPUT = listOf("output", "result", "content", "response")
    private val WIRE_ERROR = listOf("error", "err", "error_message", "errorMessage")

    private const val MAX_OUTPUT_CHARS = 4000

    /**
     * Builds a [ToolActivity] from a `tool_call` part's `data` object.
     *
     * Returns null only when there is no name to show, which would be useless
     * to the user. Every other field is optional by design.
     */
    fun from(part: JsonObject): ToolActivity? {
        val name = pickString(part, WIRE_NAME)?.takeIf { it.isNotBlank() }
        // Record which KEYS the engine actually sent, once per distinct shape.
        // The candidate lists above are guesses; this is how they get corrected
        // without guessing again on a device.
        //
        // Keys only, never values: argument values can contain file paths and
        // command text, and a log is the wrong place for those. Once the real
        // key names are known, WIRE_* can be trimmed to match and this removed.
        recordShape(part, name)
        if (name == null) return null
        val rawArgs = pickElement(part, WIRE_ARGS)
        return ToolActivity(
            id = pickString(part, WIRE_ID) ?: "tool-$name-${part.hashCode()}",
            name = name,
            args = prettify(rawArgs),
            state = parseState(pickString(part, WIRE_STATE)),
            output = truncate(pickString(part, WIRE_OUTPUT).orEmpty()),
            error = pickString(part, WIRE_ERROR)
        )
    }

    private val seenShapes = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Test seam: cleared between cases so the log-once behaviour is testable. */
    internal fun resetShapeLog() = seenShapes.clear()

    private fun recordShape(part: JsonObject, name: String?) {
        val keys = part.keySet().sorted().joinToString(",")
        val shape = "keys=[$keys] name=${name ?: "<none>"}"
        // Log once per distinct key-set. Verbose per-frame logging of a shape
        // that arrives on every message frame would drown the log for no gain.
        if (seenShapes.add(shape)) {
            // Logging must never be able to break parsing. android.util.Log
            // throws in plain JVM unit tests, and a diagnostics aid that can
            // crash the feature it is diagnosing is worse than no aid at all.
            runCatching {
                com.opencode.chat.util.AppLog.i(
                    "ToolActivity",
                    "observed tool_call shape: $shape"
                )
            }
        }
    }

    /**
     * Maps a wire state onto [ToolState].
     *
     * Crush's exact vocabulary is unconfirmed, so this matches case-insensitively
     * on substrings rather than on exact enum names. An unrecognised value
     * yields [ToolState.UNKNOWN] rather than being guessed into "completed",
     * because claiming a tool finished when it may not have is the same class
     * of lie as reporting a stop that never happened.
     */
    internal fun parseState(raw: String?): ToolState {
        val s = raw?.lowercase()?.trim().orEmpty()
        if (s.isEmpty()) return ToolState.UNKNOWN
        return when {
            s.contains("error") || s.contains("fail") || s.contains("denied") ||
                s.contains("cancel") -> ToolState.FAILED
            s.contains("complete") || s.contains("done") ||
                s.contains("success") || s.contains("finish") -> ToolState.COMPLETED
            s.contains("run") || s.contains("active") ||
                s.contains("progress") -> ToolState.RUNNING
            s.contains("pending") || s.contains("queued") ||
                s.contains("await") || s.contains("request") -> ToolState.PENDING
            else -> ToolState.UNKNOWN
        }
    }

    /** First present, non-blank string among [names]. */
    private fun pickString(o: JsonObject, names: List<String>): String? {
        for (n in names) {
            val e = o.get(n) ?: continue
            if (e.isJsonNull) continue
            // state can arrive as an array of {"status": "..."} objects
            if (e.isJsonArray) {
                val arr = e.asJsonArray
                for (i in 0 until arr.size()) {
                    val el = arr.get(i)
                    if (el.isJsonObject) {
                        pickString(el.asJsonObject, listOf("status", "state", "name"))
                            ?.let { return it }
                    }
                }
                continue
            }
            if (e.isJsonObject) {
                pickString(e.asJsonObject, listOf("status", "state", "name", "text", "content"))
                    ?.let { return it }
                continue
            }
            val v = runCatching { e.asString }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun pickElement(o: JsonObject, names: List<String>): String? {
        for (n in names) {
            val e = o.get(n) ?: continue
            if (e.isJsonNull) continue
            val text = if (e.isJsonPrimitive) runCatching { e.asString }.getOrNull()
            else prettifyElement(e)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun prettifyElement(e: com.google.gson.JsonElement): String? = runCatching {
        gson.toJson(gson.fromJson(e, com.google.gson.JsonElement::class.java))
    }.getOrNull()

    private fun prettify(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            gson.toJson(JsonParser.parseString(raw))
        }.getOrDefault(raw)
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_OUTPUT_CHARS) s else s.take(MAX_OUTPUT_CHARS) + "\n… (truncated)"
}
