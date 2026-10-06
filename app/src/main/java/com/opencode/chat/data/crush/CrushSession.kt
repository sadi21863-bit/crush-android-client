package com.opencode.chat.data.crush

/**
 * Ordered bootstrap for a usable Crush agent.
 *
 * The order is not arbitrary and was established empirically on device:
 *
 *   1. Create the workspace.
 *   2. Apply yolo (first-wins, so always re-apply on attach).
 *   3. Inject the provider API key. Without this, step 4 fails with
 *      HTTP 500 {"message":"coder agent configuration is missing"}.
 *   4. Initialise the agent coordinator. Without this, prompting fails with
 *      HTTP 400 {"message":"agent coordinator not initialized"}.
 *
 * Steps 3 and 4 are separate because the key arrives from the user, long after
 * the workspace is created. [ensureAgentReady] re-runs 3-4 idempotently.
 */
class CrushSession(
    private val api: CrushApi,
    /** Exposed so the SSE probe can reuse the already-attached workspace. */
    val workspaces: WorkspaceManager
) {

    data class Ready(
        val workspaceId: String,
        val providerId: String,
        val isReady: Boolean,
        val notes: List<String>
    )

    /** Creates the workspace and applies the permission mode. Does NOT initialise the agent. */
    suspend fun openWorkspace(
        path: String,
        dataDir: String,
        permissionMode: com.opencode.chat.domain.model.PermissionMode =
            com.opencode.chat.domain.model.PermissionMode.ASK
    ): Ready {
        val ws = workspaces.open(
            path,
            dataDir,
            // ASK means do NOT skip: the engine then emits permission_request
            // events and blocks until the UI answers. This was hardcoded true
            // (yolo) before, so the agent approved its own file edits silently.
            yolo = permissionMode == com.opencode.chat.domain.model.PermissionMode.AUTO_APPROVE
        )
        return Ready(ws.id, "opencode-zen", false, listOf("workspace ${ws.id.take(8)} created"))
    }

    /**
     * Injects the key then initialises the agent. Safe to call repeatedly.
     *
     * @param modelId a model id obtained from a LIVE catalogue fetch. There is
     *   deliberately NO default: Crush validates the id against the provider's
     *   per-credential catalogue and rejects the whole handshake with
     *   HTTP 500 {"message":"large model not found in provider"} when it is
     *   absent. A hardcoded id is a guess, and this engine treats a guess as
     *   fatal - which is what a friend hit on a fresh install. Callers must pass
     *   something they just fetched; see ModelChoice.
     *   Both the large and small slots are set because Crush uses the small one
     *   for session titles.
     */
    suspend fun ensureAgentReady(
        apiKey: String,
        providerId: String,
        modelId: String
    ): Ready {
        val wsId = workspaces.currentWorkspaceId
            ?: return Ready("", providerId, false, listOf("no workspace"))
        val notes = mutableListOf<String>()

        // 1. key first - init depends on it.
        runCatching { api.setProviderKey(wsId, providerId, apiKey) }
            .onSuccess { notes += "key set for provider '$providerId'" }
            .onFailure { notes += "provider-key FAILED: ${it.message?.take(120)}" }

        // 2. then the model. Must precede init, otherwise the coordinator
        //    captures the retired default and every turn 400s.
        for (slot in listOf(SetModelRequest.LARGE, SetModelRequest.SMALL)) {
            runCatching {
                api.setModel(wsId, slot, SelectedModel(model = modelId, provider = providerId))
            }
                .onSuccess { notes += "model[$slot] = $modelId" }
                .onFailure { notes += "model[$slot] FAILED: ${it.message?.take(120)}" }
        }

        // 3. then the coordinator.
        runCatching { api.initAgent(wsId, interactive = true) }
            .onSuccess { notes += "agent/init ok" }
            .onFailure { notes += "agent/init FAILED: ${it.message?.take(120)}" }

        // 4. confirm
        val info = runCatching { api.agentInfo(wsId) }.getOrNull()
        val ready = info?.isReady == true
        notes += "is_ready=${info?.isReady} is_busy=${info?.isBusy}"

        return Ready(wsId, providerId, ready, notes)
    }

    companion object {
        /**
         * Removed. A default model id was the single cause of a shipped
         * "large model not found in provider" failure, and no constant is a
         * safe substitute for a live catalogue lookup.
         */
        const val DEFAULT_MODEL = ""
    }
}