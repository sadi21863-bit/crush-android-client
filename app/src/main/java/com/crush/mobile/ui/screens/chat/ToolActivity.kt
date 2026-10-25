package com.crush.mobile.ui.screens.chat

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
 * FIELD NAMES ARE CONFIRMED ON THE WIRE (2026-10-20 / 2026-10-25 D3)
 *
 * The real `tool_call` part was captured two ways and both agree:
 *
 *   part:  {"type":"tool_call","data":{...}}
 *   data:  {"id":"call_...","name":"write",
 *           "input":"{\"file_path\":\"...\",\"content\":\"...\"}",
 *           "finished":true}
 *
 * Republished on every message update (whole-message replace), and the same
 * shape in the persisted message history via GET .../sessions/{id}/messages.
 * The fields land on the DATA object, not on the part itself - the first live
 * capture logged `name=<none>` precisely because this parser was reading the
 * part, not its `data`. [ToolActivity.from] now reads `data` with the part as
 * the fallback, so both the whole envelope and legacy reduced payloads parse.
 *
 * State: the engine sends NO text state for tool calls - it sends a `finished`
 * boolean. Streaming frames omit it (or carry `input:""`), history keeps it
 * set. So: finished==true -> COMPLETED; a `state` string still maps through
 * [parseState]; a part with an id but neither -> RUNNING; else UNKNOWN.
 * NEVER optimistically claimed COMPLETED without `finished:true`.
 *
 * Permission requests (the OTHER shape carrying tool_name/tool_call_id/
 * params/path/description) still parse through the same candidate lists -
 * spec/crush-openapi.json documents those names, but engine v0.98.1 does not
 * broadcast `permission_request` on the events stream at all (D3 finding),
 * so today the app cannot receive that shape live. The lists are kept per
 * the spec ahead of the engine fix.
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
    /** Human-readable intent, when the engine supplies one. */
    val description: String? = null,
    /** File or target the call touches, when the engine supplies one. */
    val path: String? = null,
    val error: String? = null
) {
    /** A running tool should read as live, not as finished. */
    val isRunning: Boolean get() = state == ToolState.RUNNING || state == ToolState.PENDING

    /**
     * One-line summary for the collapsed row.
     *
     * Prefers the path: "read src/main.kt" describes what happened, where
     * "read" alone only names the tool. A user scanning a long run needs to see
     * WHICH files were touched without expanding anything.
     *
     * The state word is appended to the whole "name target" phrase rather than
     * formatted inline, because "$name $suffix running" leaves a double space
     * when the engine sent no target - and trim() does not fix an inner one.
     */
    fun summary(): String {
        val who = when {
            path != null -> "$name $path"
            description != null -> "$name $description"
            else -> name
        }
        return when (state) {
            ToolState.COMPLETED, ToolState.UNKNOWN -> who
            ToolState.FAILED -> "$who failed"
            ToolState.RUNNING -> "$who running"
            ToolState.PENDING -> "$who pending"
        }
    }
}

enum class ToolState { PENDING, RUNNING, COMPLETED, FAILED, UNKNOWN }

object ToolActivityParser {

    // Pretty-printing, so tool arguments are readable in the UI. Plain
    // Gson.toJson() emits a single compact line, which is useless when the point
    // of showing a tool call is that a human can audit it.
    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ---- confirmed wire names -----------------------------------------------
    //
    // CONFIRMED ON THE LIVE WIRE (evidence: D3 captures 2026-10-20/25, engine
    // v0.98.1, session messages endpoint - see the class doc): a tool_call part
    // is `{"type":"tool_call","data":{"id","name","input","finished"}}` where
    // `input` is the tool arguments as a JSON STRING. The permission object
    // documented in spec/crush-openapi.json carries `tool_name`/`tool_call_id`/
    // `params`/`description`/`path`, which is why those names stay in the lists
    // (data-first, part-fallback covers both carriers).
    //
    // Every previously-guessed name with no wire evidence was deleted: `tool`,
    // `toolName`, `arguments`, `args`, `parameters`, `status`, `statuses`,
    // `result`, `response`, `err`, `errorMessage`, `file`, `filePath`,
    // `call_id`, `callId`, `toolCallId`-as-part-name. If the engine grows a new
    // shape, [recordShape] will surface it in one line per distinct shape.
    private val WIRE_ID = listOf("id", "tool_call_id")
    private val WIRE_NAME = listOf("tool_name", "name")
    private val WIRE_ARGS = listOf("input", "params")
    private val WIRE_STATE = listOf("state")
    private val WIRE_OUTPUT = listOf("content", "output")
    private val WIRE_ERROR = listOf("error")
    private val WIRE_DESCRIPTION = listOf("description")
    private val WIRE_PATH = listOf("path", "file_path")

    private const val MAX_OUTPUT_CHARS = 4000

    /**
     * Builds a [ToolActivity] from a `tool_call` part.
     *
     * Accepts BOTH carriers: the whole part `{"type":"tool_call","data":{...}}`
     * (what the message stream delivers and ChatViewModel passes) and a bare
     * `data` object (legacy reduced payloads and the unit tests). Fields are
     * read from the `data` object when one exists, falling back to the block
     * itself - that covers permission-style payloads, which put the fields on
     * the object directly.
     *
     * Returns null only when there is no name to show, which would be useless
     * to the user. Every other field is optional by design.
     */
    fun from(part: JsonObject): ToolActivity? {
        val data = part.optBlock("data") ?: part
        recordShape(part, data)
        val name = pickString(data, WIRE_NAME).orBlankFix(pickString(part, WIRE_NAME))
            ?.takeIf { it.isNotBlank() }
        if (name == null) return null
        val rawArgs = pickElement(data, WIRE_ARGS) ?: pickElement(part, WIRE_ARGS)
        val textState = pickString(data, WIRE_STATE) ?: pickString(part, WIRE_STATE)
        val state = stateFor(data, textState)
        val path = pickString(data, WIRE_PATH).orBlankFix(pickString(part, WIRE_PATH))
            ?: pathFromArgs(rawArgs)
        return ToolActivity(
            id = (pickString(data, WIRE_ID).orBlankFix(pickString(part, WIRE_ID)))
                ?: "tool-$name-${data.hashCode()}",
            name = name,
            args = prettify(rawArgs),
            state = state,
            output = truncate((pickString(data, WIRE_OUTPUT).orBlankFix(pickString(part, WIRE_OUTPUT))).orEmpty()),
            description = pickString(data, WIRE_DESCRIPTION).orBlankFix(pickString(part, WIRE_DESCRIPTION)),
            path = path,
            error = pickString(data, WIRE_ERROR).orBlankFix(pickString(part, WIRE_ERROR))
        )
    }

    /**
     * Reads the part's `data` member as an object, or null.
     *
     * Must NOT use getAsJsonObject(): a present-but-null or present-but-scalar
     * member must degrade to "no data object" (and then to the part itself),
     * never throw. Same rule as CrushEvent's data() helper, which was added
     * after `"data":null` crashed the app on malformed engine output.
     */
    private fun JsonObject.optBlock(key: String): JsonObject? {
        val el = get(key) ?: return null
        return if (el.isJsonNull || !el.isJsonObject) null else el.asJsonObject
    }

    /** `a ?: b` for Strings that skips blank firsts. */
    private fun String?.orBlankFix(fallback: String?): String? =
        if (!this.isNullOrBlank()) this else fallback

    /**
     * State resolution, evidence-pinned: the engine sends a `finished` boolean
     * - a present `true` means COMPLETED, absent or `false` with a call id
     * means the call is streaming (RUNNING). A legacy `state` string still maps
     * through [parseState]. Anything else is UNKNOWN - never guessed COMPLETED.
     */
    private fun stateFor(data: JsonObject, textState: String?): ToolState {
        val finished = data.get("finished")
        if (finished != null && finished.isJsonPrimitive && finished.asBoolean) {
            return ToolState.COMPLETED
        }
        val mapped = parseState(textState)
        if (mapped != ToolState.UNKNOWN) return mapped
        return if (data.has("id")) ToolState.RUNNING else ToolState.UNKNOWN
    }

    /**
     * Mines the call arguments for the touched path: the engine puts
     * `file_path` (et al.) INSIDE the `input` JSON string, not on the part.
     * Parsing a machine's own argument string must never crash parsing.
     */
    private fun pathFromArgs(rawArgs: String?): String? {
        if (rawArgs.isNullOrBlank()) return null
        val o = runCatching { JsonParser.parseString(rawArgs) }.getOrNull() ?: return null
        val obj = o.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val raw = pickString(obj, listOf("file_path", "path"))
        if (!raw.isNullOrBlank()) return raw
        val params = obj.optBlock("params") ?: return null
        return pickString(params, listOf("file_path", "path"))?.takeIf { it.isNotBlank() }
    }

    private val seenShapes = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Test seam: cleared between cases so the log-once behaviour is testable. */
    internal fun resetShapeLog() = seenShapes.clear()

    /**
     * Logs the observed envelope ONCE per distinct shape - both the part keys
     * and the `data` sub-keys. Keys only, never values: argument values can
     * contain file paths and command text, and a log is the wrong place for
     * those. This is the seam that caught the data-vs-part mismatch, so it
     * stays regardless of the names now being confirmed.
     */
    private fun recordShape(part: JsonObject, data: JsonObject) {
        val dataKeys = if (data === part) "<none>" else data.keySet().sorted().joinToString(",")
        val name = pickString(data, WIRE_NAME) ?: pickString(part, WIRE_NAME)
        val shape = "keys=[${part.keySet().sorted().joinToString(",")}]" +
            " data=[$dataKeys] name=${name ?: "<none>"}"
        // Log once per distinct key-set. Verbose per-frame logging of a shape
        // that arrives on every message frame would drown the log for no gain.
        if (seenShapes.add(shape)) {
            // Logging must never be able to break parsing. android.util.Log
            // throws in plain JVM unit tests, and a diagnostics aid that can
            // crash the feature it is diagnosing is worse than no aid at all.
            runCatching {
                com.crush.mobile.util.AppLog.i(
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
