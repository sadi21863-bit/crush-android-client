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
     * Typed outcome of [injectChatToolPolicy] (S-01/P0-2, 2026-11-05). The old
     * boolean SyncResult degraded EVERY failure shape (malformed file,
     * unusable dir, failed write) to "unchanged, carry on" - and the spawn
     * call site ignored even that, so Chat exec'd a coding-capable engine
     * with possibly NO deny list at all. The sealed shape makes the caller's
     * choices explicit and compiler-checked; [CrushEngine]'s chat gate refuses
     * to spawn on a failure (Chat cannot start as a coding-capable engine on
     * policy failure - accepted product decision, pack 05 item A).
     */
    sealed class ChatPolicyResult {
        /** A write landed AND the readback confirmed every required id on disk. */
        data class PolicyInstalled(
            val changed: Boolean,
            val tools: List<String>
        ) : ChatPolicyResult()

        /** Bytes were already canonical AND a fresh readback confirmed them. */
        data class AlreadyVerified(val tools: List<String>) : ChatPolicyResult()

        /**
         * Fail-CLOSED. [reason] is content-free BY CONSTRUCTION - failure
         * class names and counts only, never file text: this file can hold
         * the raw provider API key (class KDoc) and the reason can reach
         * logs and badges.
         */
        data class PolicyFailure(val reason: String) : ChatPolicyResult()
    }

    /** Per-outcome verdict of the pure readback verifier; content-free (see its KDoc). */
    internal sealed interface ChatPolicyReadback {
        /** Every required id is present in the on-disk `options.disabled_tools`. */
        data object Verified : ChatPolicyReadback

        /** The file parsed but does not cover the policy; [missing] is a count only. */
        data class ShortList(val missing: Int) : ChatPolicyReadback

        /** Not a JSON object / half-parsed / unreadable; [cause] is a class name only. */
        data class Unparsable(val cause: String) : ChatPolicyReadback
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
     * twice leaves bytes unchanged and reports
     * [ChatPolicyResult.AlreadyVerified]. Malformed files are reported as a
     * failure, never rewritten (a half-parse rewrite could delete the
     * engine's stored key - the same rule [syncProviderModels] runs under;
     * S-01/P0-2 now FAILS the call where the old code silently passed it).
     *
     * S-01/P0-2 (2026-11-05): VERIFIED-BY-READBACK. Success is claimed only
     * after the file is re-read from disk and confirmed to contain every
     * [CHAT_DISABLED_TOOLS] id - and NO harder claim than that: honouring the
     * list at the engine boundary is what the S1 device probe proved, not
     * this function. A write whose readback disagrees is a failure.
     *
     * KEY-PROTECTION RULE: no AppLog/Log call exists in this object and none
     * may be added - [ChatPolicyResult.PolicyFailure.reason] carries only
     * failure class names and counts (never file text, never parsed ids,
     * never paths). The engine layer may log the outcome and counts.
     *
     * @return the typed outcome. Never throws.
     */
    fun injectChatToolPolicy(globalDataDir: File): ChatPolicyResult {
        val file = File(globalDataDir, "crush.json")
        if (!globalDataDir.isDirectory && !globalDataDir.mkdirs()) {
            // Fail-CLOSED (S-01/P0-2): the old code returned SyncResult(false)
            // here and the spawn call site ignored it, so an unusable state
            // dir silently passed a pool whose deny list could not exist.
            return ChatPolicyResult.PolicyFailure(
                "chat state dir unavailable (mkdirs failed); no policy installed"
            )
        }
        val mergedRoot: JsonObject? = runCatching {
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
                // Byte-stable: the on-disk list already carries the policy in
                // canonical form. Nothing is written here; the outcome below
                // is decided by a fresh READBACK from disk (2026-11-05), never
                // by trusting this parse alone.
                null
            } else {
                options.add("disabled_tools", merged)
                obj
            }
        }.getOrElse {
            // S1 constraint kept: a malformed or half-parsed file is
            // reported, NEVER rewritten (a rewrite could delete the engine's
            // stored key). A wrong-shaped existing member (e.g.
            // "options": null) throws out of the tree walk the same way and
            // fails closed too. The reason carries only the failure class -
            // never file content (key-protection rule above).
            return ChatPolicyResult.PolicyFailure(
                "crush.json malformed in the chat state dir " +
                    "(${it.javaClass.simpleName}); never rewritten"
            )
        }

        if (mergedRoot == null) {
            // Second-call path: bytes already canonical. Verify fresh from
            // disk anyway - AlreadyVerified is claimed from the file the
            // ENGINE will read, not from this parse.
            return when (val back = readbackChatPolicy(file)) {
                is ChatPolicyReadback.Verified ->
                    ChatPolicyResult.AlreadyVerified(tools = CHAT_DISABLED_TOOLS)
                is ChatPolicyReadback.ShortList ->
                    ChatPolicyResult.PolicyFailure(
                        "policy not verified on disk: ${back.missing} of " +
                            "${CHAT_DISABLED_TOOLS.size} deny ids missing"
                    )
                is ChatPolicyReadback.Unparsable ->
                    ChatPolicyResult.PolicyFailure(
                        "policy readback unparsable (${back.cause})"
                    )
            }
        }

        val wrote = runCatching {
            val tmp = File(globalDataDir, "crush.json.tmp")
            tmp.writeText(mergedRoot.toString())
            if (!tmp.renameTo(file)) {
                // rename across the same dir cannot fail for a real path, but
                // on FAT/emulated storage it has; fall back to copy semantics.
                file.writeText(mergedRoot.toString())
                tmp.delete()
            }
        }
        if (wrote.isFailure) {
            // Fail-CLOSED (S-01/P0-2): a failed write no longer degrades to a
            // pass-through result; the class name is the only detail logged
            // anywhere (key-protection rule above).
            return ChatPolicyResult.PolicyFailure(
                "policy write failed (${wrote.exceptionOrNull()?.javaClass?.simpleName})"
            )
        }

        // VERIFIED-BY-READBACK (S-01/P0-2, 2026-11-05): success is claimed
        // only after the file is re-read from disk and every required deny id
        // is confirmed present in it.
        return when (val back = readbackChatPolicy(file)) {
            is ChatPolicyReadback.Verified ->
                ChatPolicyResult.PolicyInstalled(changed = true, tools = CHAT_DISABLED_TOOLS)
            is ChatPolicyReadback.ShortList ->
                ChatPolicyResult.PolicyFailure(
                    "readback after write disagrees: ${back.missing} of " +
                        "${CHAT_DISABLED_TOOLS.size} deny ids missing"
                )
            is ChatPolicyReadback.Unparsable ->
                ChatPolicyResult.PolicyFailure(
                    "readback after write unparsable (${back.cause})"
                )
        }
    }

    /**
     * Wraps the file read for the post-write/second-call verification: any
     * I/O surprise is an unparsable-and-failed readback, never an exception
     * out of the injector.
     */
    private fun readbackChatPolicy(file: File): ChatPolicyReadback = runCatching {
        verifyChatPolicyOnDisk(file.readText(), CHAT_DISABLED_TOOLS)
    }.getOrElse {
        ChatPolicyReadback.Unparsable(it.javaClass.simpleName)
    }

    /**
     * The readback seam for [injectChatToolPolicy]'s verify-after-write step,
     * pure over the on-disk TEXT so tests drive it directly (S-01/P0-2,
     * 2026-11-05) - faking a real post-write mismatch without a Context needs
     * an OS race the injector refuses to model.
     *
     * Contract, and NO harder claim than the requirement: the text must parse
     * to a JSON object whose `options.disabled_tools` contains EVERY id in
     * [required]. Extra ids, id order and byte shape are NOT checked - the
     * engine-side honouring of the list is what the S1 device probe proved.
     *
     * Content-freedom: the outcome never carries file text, parsed ids or
     * absolute paths, because this file can also hold the raw provider API
     * key (class KDoc) and the outcome can reach logs and badges.
     */
    internal fun verifyChatPolicyOnDisk(
        fileText: String,
        required: List<String>
    ): ChatPolicyReadback {
        val options: JsonObject? = runCatching {
            JsonParser.parseString(fileText).asJsonObject.getAsJsonObject("options")
        }.getOrElse { return ChatPolicyReadback.Unparsable(it.javaClass.simpleName) }
        val present: Set<String> = runCatching {
            options?.getAsJsonArray("disabled_tools")
        }.getOrElse { return ChatPolicyReadback.Unparsable(it.javaClass.simpleName) }
            ?.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.map { it.asString }
            ?.toSet()
            // No list to verify against counts as every id missing: absence
            // of coverage is a failure, never an implicit pass.
            ?: return ChatPolicyReadback.ShortList(missing = required.size)
        val missing = required.count { it !in present }
        return if (missing == 0) ChatPolicyReadback.Verified
        else ChatPolicyReadback.ShortList(missing)
    }

    /**
     * Every built-in tool name the pinned engine family documents, PLUS the
     * names the 2026-11-05 chat-pool drive caught the model actually
     * exercising: with bash/edit/view hidden, the model fell back to a
     * `question` TOOL CALL (ask-the-user) trying to get a deletion
     * confirmed - question and todos belong out of Chat too (they are
     * tools, Q2 = zero tools). The engine filters `options.disabled_tools`
     * by EXACT NAME (upstream `resolveAllowedTools` does a plain
     * contains-match over `allToolNames()`; a literal "lsp_*" string hides
     * NOTHING), so every catalogue id must be named here verbatim.
     *
     * 2026-11-08 CATALOGUE-PARITY PASS: the pinned v0.99.0 catalogue was
     * enumerated from the shipped binary itself (strings over
     * app/src/main/jniLibs/arm64-v8a/libcrush.so) and cross-checked against
     * upstream `internal/config/config.go#allToolNames()`. The pass closed a
     * 9-ID GAP the 2026-11-05-era list had:
     * - lsp_symbols / lsp_definition / lsp_call_hierarchy : LSP reads
     * - lsp_rename : workspace-wide symbol RENAME (LSP write)
     * - lsp_replace_symbol : replace/insert/delete a WHOLE SYMBOL by name
     *   (LSP write)
     * - crush_info / crush_logs : engine self-introspection; the 2026-11-07
     *   drive PROVED the model called crush_info in the chat pool and read
     *   the deny state back through it ("crush_info shows bash in the
     *   disabled tools list")
     * - list_mcp_resources / read_mcp_resource : BUILT-IN surfaces for MCP
     *   resources - the old note below claiming MCP names are "absent from
     *   the built-in list" was WRONG and is superseded here.
     * The two inert ids ("task", "diagnostics") stay: hiding an id the
     * engine does not know is inert, and unknown ids cover forks. The
     * MODE-01 acceptance drive on the real engine is what proves a release.
     */
    val CHAT_DISABLED_TOOLS: List<String> = listOf(
        "agent", "bash", "crush_info", "crush_logs", "job_output",
        "job_kill", "download", "edit", "multiedit",
        "lsp_call_hierarchy", "lsp_definition", "lsp_diagnostics",
        "lsp_references", "lsp_rename", "lsp_replace_symbol", "lsp_restart",
        "lsp_symbols",
        "fetch", "agentic_fetch", "glob", "grep", "ls", "sourcegraph",
        "write", "view",
        // 2026-11-05 chat-pool drive additions (seen exercised or named by
        // the model itself after the first-eight list):
        "todos", "question", "task", "diagnostics",
        // Built-in MCP-resource surfaces (2026-11-08 parity pass - upstream
        // allToolNames lists them; previously missing).
        "list_mcp_resources", "read_mcp_resource",
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
