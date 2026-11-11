package com.crush.mobile.data.crush

/**
 * THE ENGINE-CATALOGUE MANIFEST — one row per Crush engine version this
 * build knows, with the built-in tool ids that version documents.
 *
 * WHY IT EXISTS: the S-01 contract is CATALOGUE PARITY, not "whatever the
 * last drive happened to catch". The old ratchet test pinned ONE frozen
 * list; adopting a future engine required hand-editing that list and
 * hoping nothing else moved. This manifest makes the contract structural:
 * the ratchet test requires [EngineConfigInjector.CHAT_DISABLED_TOOLS] to
 * cover EVERY row below, so an engine version can only be adopted after
 * its full catalogue (and any new ids) is already denied.
 *
 * MAINTENANCE — the only edits a future engine bump needs, IN THIS ORDER:
 *  1. add the release's row below, from upstream `allToolNames()` IF the
 *     tag's source is reachable, else a `strings` scan of the OFFICIAL
 *     Android arm64 artifact (record which in docs/engine-acceptance-kit.md);
 *  2. run the JVM gate: the ratchet test names every id the deny list is
 *     missing — add exactly those to CHAT_DISABLED_TOOLS;
 *  3. only THEN fetch the artifact (tools/fetch-crush.ps1), bump
 *     [PINNED_VERSION], and run the device rails
 *     (docs/engine-acceptance-kit.md §device).
 *
 * The kit that consumes this manifest — acquisition, verification tiers,
 * device runbook, rollback — is docs/engine-acceptance-kit.md.
 */
object EngineCatalogues {

    /** One pinned engine version and its documented built-in tool ids. */
    data class EngineCatalogue(
        val version: String,
        val builtinTools: List<String>,
    )

    /**
     * The engine version whose official Android artifact ships in
     * app/src/main/jniLibs/arm64-v8a/libcrush.so (the app logs it at boot
     * as `version v0.99.0`). Bumping this is a DELIBERATE act that follows
     * the maintenance order above — never a side effect of a fetch.
     */
    const val PINNED_VERSION = "0.99.0"

    /**
     * Every engine version the kit knows, oldest first. Provenance:
     * - v0.99.0 — the 2026-11-08 parity enumeration (upstream
     *   `allToolNames()` + strings over the shipped libcrush.so);
     * - v0.100.0 — STRINGS-CONFIRMED 2026-11-09 over the official
     *   crush_0.100.0_Android_arm64.tar.gz out-of-tree: all ids below
     *   present, no additional built-in surfaced (the probe id
     *   "agent_task" is absent → the snapshot is exhaustive).
     * The inert extra ids in the deny list ("task", "diagnostics") are not
     * engine-raised built-ins and deliberately stay out of these rows.
     */
    val KNOWN: List<EngineCatalogue> = listOf(
        EngineCatalogue(
            version = "0.99.0",
            builtinTools = listOf(
                "agent", "bash", "crush_info", "crush_logs", "job_output", "job_kill",
                "download", "edit", "multiedit", "lsp_call_hierarchy", "lsp_definition",
                "lsp_diagnostics", "lsp_references", "lsp_rename", "lsp_replace_symbol",
                "lsp_restart", "lsp_symbols", "fetch", "agentic_fetch", "glob", "grep",
                "ls", "question", "sourcegraph", "todos", "view", "write",
                "list_mcp_resources", "read_mcp_resource",
            ),
        ),
        EngineCatalogue(
            version = "0.100.0",
            builtinTools = listOf(
                "agent", "bash", "crush_info", "crush_logs", "job_output", "job_kill",
                "download", "edit", "multiedit", "lsp_call_hierarchy", "lsp_definition",
                "lsp_diagnostics", "lsp_references", "lsp_rename", "lsp_replace_symbol",
                "lsp_restart", "lsp_symbols", "fetch", "agentic_fetch", "glob", "grep",
                "ls", "question", "sourcegraph", "todos", "view", "write",
                "list_mcp_resources", "read_mcp_resource",
                // v0.100.0 sub-agent lifecycle tools (release notes
                // 965d1a7/938fef2; strings-CONFIRM 2026-11-09).
                "agent_dispatch", "agent_send", "agent_status", "agent_stop",
            ),
        ),
    )

    /** The newest row — the engine the ladder would move to next. */
    val newest: EngineCatalogue get() = KNOWN.last()

    fun byVersion(version: String): EngineCatalogue? =
        KNOWN.firstOrNull { it.version == version }

    /** The deny list's gaps for ONE catalogue (empty = covered). */
    fun missingDenials(
        catalogue: EngineCatalogue,
        denied: Collection<String> = EngineConfigInjector.CHAT_DISABLED_TOOLS,
    ): List<String> = catalogue.builtinTools.filterNot(denied::contains)
}
