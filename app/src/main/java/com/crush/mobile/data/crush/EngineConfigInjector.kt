package com.crush.mobile.data.crush

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
     * the subset the engine reads). `cost` fields are 0 not because these
     * models are free but because the engine's own default (from its stale
     * catalogue) is what the picker overrides anyway.
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
    fun syncProviderModels(globalDataDir: File, providerId: String, overrides: List<ModelSpec>): SyncResult {
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
        addProperty("cost_per_1m_input_tokens", 0)
        addProperty("cost_per_1m_output_tokens", 0)
        addProperty("cost_per_1m_cached_input_tokens", 0)
    }
}
