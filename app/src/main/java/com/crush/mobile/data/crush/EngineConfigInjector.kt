package com.crush.mobile.data.crush

import com.crush.mobile.util.AppLog
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Keeps a provider's model catalogue INSIDE the engine's own config file.
 *
 * WHY THE APP WRITES THE ENGINE'S CONFIG AT ALL
 *
 * Crush reads its provider catalogue from Catwalk (charm's catalogue server)
 * or falls back to an embedded snapshot. Both were measured stale for `groq`
 * on 2026-10-17: the embedded snapshot (0.97.1) and the LIVE server carry only
 * `moonshotai/kimi-k2-instruct-0905` and `qwen/qwen3-32b` for Groq, while the
 * live Groq API serves a different set - and says 404 for both carried ids.
 * The result was a zero-intersection catalogue: every model the engine offered
 * was dead, and every live id was rejected with
 * "large model not found in provider".
 *
 * The engine honors user config for this: `providers.<id>.models` in its
 * config file is merged over the catalogue. That is the only writable door,
 * because /config is GET-only on every engine build tested (0.97.1, 0.98.1).
 *
 * WHEN THIS MUST RUN
 *
 * The engine parses config ONCE per process (internal/config/load.go, called
 * from app.New). So the merge happens immediately BEFORE the engine process
 * is spawned, from CrushEngine.start(). A restart therefore picks it up, an
 * already-running engine keeps what it read - which is fine because this is
 * called on every process start, before the engine can be warm.
 *
 * NEVER PRINTED
 *
 * The same file holds the raw provider API key (the engine writes it
 * unmasked). This code parses and re-serialises JSON, and must not log any
 * of it - AppLog calls here would paste the key into adb logs.
 */
object EngineConfigInjector {

    /**
     * One model as the engine's config wants it (schema of Catwalk's Model,
     * the subset the engine reads). Deliberately carries NO cost fields - see
     * [toJson] for why a zero cost was banned from these specs.
     */
    data class ModelSpec(
        val id: String,
        val displayName: String,
        val contextWindow: Int,
        val defaultMaxTokens: Int,
        val canReason: Boolean = false,
        val reasoningEfforts: List<String> = emptyList(),
        val defaultReasoningEffort: String? = null
    )

    /**
     * The provider catalogues that need overriding, with the columns measured
     * live on 2026-10-17 against the provider's actual /models and error
     * constraints.
     *
     * groq specifics, all measured: gpt-oss-120b/20b are reasoning models
     * (efforts low/medium/high), TPM is 8000 for both; qwen3.8-27b is not a
     * reasoning model; allam-2-7b caps completion at 4096.
     */
    private val OVERRIDES: Map<String, List<ModelSpec>> = mapOf(
        "groq" to listOf(
            ModelSpec(
                id = "openai/gpt-oss-120b",
                displayName = "GPT-OSS 120B",
                contextWindow = 131072,
                defaultMaxTokens = 8192,
                canReason = true,
                reasoningEfforts = listOf("low", "medium", "high"),
                defaultReasoningEffort = "medium"
            ),
            ModelSpec(
                id = "openai/gpt-oss-20b",
                displayName = "GPT-OSS 20B",
                contextWindow = 131072,
                defaultMaxTokens = 8192,
                canReason = true,
                reasoningEfforts = listOf("low", "medium", "high"),
                defaultReasoningEffort = "medium"
            ),
            ModelSpec(
                id = "qwen/qwen3.8-27b",
                displayName = "Qwen3 27B",
                contextWindow = 131072,
                defaultMaxTokens = 8192
            ),
            ModelSpec(
                id = "allam-2-7b",
                displayName = "ALLaM 2 7B",
                contextWindow = 4096,
                defaultMaxTokens = 4096
            )
        )
    )

    /** The override list for [providerId], or empty when no override is needed. */
    fun providerCatalog(providerId: String): List<ModelSpec> = OVERRIDES[providerId].orEmpty()

    /** Types the outcome so callers can log meaningfully without printing keys. */
    data class SyncResult(
        val changed: Boolean,
        val ids: List<String>
    )

    /**
     * Merges the override list for [providerId] into `<globalDataDir>/crush.json`.
     *
     * Semantics, kept narrow on purpose:
     * - The file may not exist yet (first run) - treated as `{}`.
     * - `providers.<id>` is created if missing; an EXISTING object is preserved
     *   field by field (it may hold the user's `api_key`), only `models` is
     *   replaced.
     * - Idempotent: writing the same list twice leaves bytes unchanged and
     *   reports changed=false, so restart paths can call this every time.
     * - Unknown other providers in the file are left untouched.
     *
     * @param globalDataDir the directory the engine is told via CRUSH_GLOBAL_DATA.
     * @return what happened. Never throws; a malformed file is reported as
     *   unchanged rather than rewritten, because a half-parse rewrite could
     *   delete the engine's stored key.
     */
    fun syncProviderModels(
        globalDataDir: File,
        providerId: String,
        overrides: List<ModelSpec>,
        /**
         * Decision item 4 (single-writer contract, see
         * docs/key-path-single-writer-design.md): the ONLY legal write window
         * for this file is before an engine process is up. Callers pass
         * `engineRunning = true` to DEFER a write that arrived out of window
         * instead of racing the engine's own config writes (lost update).
         * The default `false` keeps the existing call shape working;
         * CrushEngine.start() passes it explicitly to pin the ordering.
         */
        engineRunning: Boolean = false
    ): SyncResult {
        if (engineRunning) {
            AppLog.w(
                "EngineConfigInjector",
                "sync refused: engine is running; the write window is 'engine down' only"
            )
            return SyncResult(false, emptyList())
        }
        val file = File(globalDataDir, "crush.json")
        if (overrides.isEmpty()) return SyncResult(false, emptyList())
        if (!globalDataDir.isDirectory && !globalDataDir.mkdirs()) {
            return SyncResult(false, emptyList())
        }
        val root: JsonObject = runCatching {
            val parsed = if (file.isFile) JsonParser.parseString(file.readText()) else JsonObject()
            // getAsJsonObject throws ClassCastException on a bare null member
            // ({"providers":null}), so the tree walk shares the same guard.
            val obj = parsed.asJsonObject
            val providers = obj.getAsJsonObject("providers") ?: JsonObject().also {
                obj.add("providers", it)
            }
            val entry = providers.getAsJsonObject(providerId) ?: JsonObject().also {
                providers.add(providerId, it)
            }

            val array = JsonArray()
            for (spec in overrides) {
                array.add(spec.toJson())
            }
            val before = if (entry.has("models")) entry.get("models").toString() else null
            if (before == array.toString()) {
                return SyncResult(false, overrides.map { it.id })
            }
            entry.add("models", array)
            obj
        }.getOrElse { return SyncResult(false, emptyList()) }

        runCatching {
            val tmp = File(globalDataDir, "crush.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                // rename across the same dir cannot fail for a real path, but
                // on FAT/emulated storage it has; fall back to copy semantics.
                file.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { return SyncResult(false, overrides.map { it.id }) }
        return SyncResult(true, overrides.map { it.id })
    }

    /**
     * S3 (2026-11-05, S1 device probe): the CHAT pool's tool policy.
     *
     * The chat pool runs CONVERSATION-ONLY: every built-in tool the upstream
     * engine knows is hidden from the agent via `options.disabled_tools`,
     * which the S1 probe PROVED is honored by the pinned v0.99.0 (asked to
     * run bash, the agent attempted a call, the engine answered
     * tool-unavailable, and NOTHING executed). This is the engine-boundary
     * half of deny-all Chat; the app-side auto-deny (S4) stays defense in
     * depth. Per-instance by construction: [globalDataDir] for the chat pool
     * resolves through the chat state dir, so per-project Code instances are
     * untouched.
     *
     * Caller contract: the ONLY legal window is before an engine process is
     * up (the same rule as [syncProviderModels]; the call site in
     * CrushEngine.start() is pre-exec). Idempotent: writing the same list
     * twice leaves bytes unchanged. Malformed files are reported unchanged,
     * never rewritten (a half-parse rewrite could delete the engine's stored
     * key - the same rule [syncProviderModels] runs under).
     *
     * @return SyncResult(changed, tools)
     */
    fun injectChatToolPolicy(globalDataDir: File): SyncResult {
        val file = File(globalDataDir, "crush.json")
        if (!globalDataDir.isDirectory && !globalDataDir.mkdirs()) {
            return SyncResult(false, emptyList())
        }
        val root: JsonObject = runCatching {
            val parsed = if (file.isFile) JsonParser.parseString(file.readText()) else JsonObject()
            val obj = parsed.asJsonObject
            val options = obj.getAsJsonObject("options") ?: JsonObject().also { obj.add("options", it) }
            val existing = options.getAsJsonArray("disabled_tools")
            val merged = JsonArray()
            val seen = HashSet<String>()
            if (existing != null) {
                for (el in existing) {
                    val name = el.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    if (seen.add(name)) merged.add(name)
                }
            }
            for (name in CHAT_DISABLED_TOOLS) {
                if (seen.add(name)) merged.add(name)
            }
            val before = if (options.has("disabled_tools"))
                options.get("disabled_tools").toString() else null
            if (before == merged.toString()) {
                return SyncResult(false, CHAT_DISABLED_TOOLS)
            }
            options.add("disabled_tools", merged)
            obj
        }.getOrElse { return SyncResult(false, emptyList()) }

        runCatching {
            val tmp = File(globalDataDir, "crush.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                // rename across the same dir cannot fail for a real path, but
                // on FAT/emulated storage it has; fall back to copy semantics.
                file.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { return SyncResult(false, emptyList()) }
        return SyncResult(true, CHAT_DISABLED_TOOLS)
    }

    /**
     * Every built-in tool name this engine family documents (docs/config +
     * the 2026-11-05 S1 device probe result). Q2 = deny ALL tools in Chat v1
     * (pure conversation), so the list covers all built-ins the pinned
     * release knows. Not enumerated: MCP- and skill-derived names (they are
     * user-configurable surfaces, absent from the built-in list); if a
     * future engine adds a NEW built-in, the injected list is the honest
     * best-effort of this app version - the MODE-01 acceptance drive on the
     * real engine is what proves a release.
     */
    val CHAT_DISABLED_TOOLS: List<String> = listOf(
        "agent", "bash", "job_output", "job_kill", "download",
        "edit", "multiedit", "lsp_diagnostics", "lsp_references", "lsp_restart",
        "fetch", "agentic_fetch", "glob", "grep", "ls", "sourcegraph",
        "write", "view"
    )

    private fun ModelSpec.toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", displayName)
        addProperty("provider", "")
        addProperty("context_window", contextWindow)
        addProperty("max_tokens", defaultMaxTokens)
        addProperty("default_max_tokens", defaultMaxTokens)
        if (canReason) {
            addProperty("can_reason", true)
            add("reasoning_levels", JsonArray().apply { reasoningEfforts.forEach { add(it) } })
            if (defaultReasoningEffort != null) {
                addProperty("default_reasoning_effort", defaultReasoningEffort)
            }
        }
        addProperty("supports_images", false)
        // NO cost fields (P2-21). The old 2026-10-17 frozen table wrote
        // cost_per_1m_* = 0 for every injected id, and ZenModelsApi treats an
        // engine-reported 0 as "known free" - so a paid Groq model was
        // labelled free on the strength of a spreadsheet snapshot. Zero is
        // also unfixable later: absence can be filled in by discovery, an
        // explicit 0 has to be actively overwritten. Cost truth comes from
        // LIVE catalogue discovery (the engine's own Catwalk cache, patched
        // through /providers pricing), never from this frozen table, so the
        // injected specs omit the fields and let the engine/its catalogue
        // carry real numbers - or no number, which is honest.
    }
}
