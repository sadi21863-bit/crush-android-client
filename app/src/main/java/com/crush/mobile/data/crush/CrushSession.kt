package com.crush.mobile.data.crush

import com.crush.mobile.util.AppLog
import kotlinx.coroutines.CancellationException

/**
 * runCatching that does NOT swallow [CancellationException].
 *
 * Every handshake call below runs inside a suspend context, where a cancelled
 * coroutine is signalled by a CancellationException. Plain runCatching
 * converts that into Result.failure, which the retry logic downstream then
 * treats as "try again" - running retry work after the coroutine was already
 * told to abort. Cancellation must propagate: rethrow it, capture everything
 * else exactly as runCatching does, so non-cancellation behaviour is
 * byte-identical before and after.
 *
 * Top-level and [internal] rather than private-duplicated: WorkspaceManager
 * shares this helper (same package), so there is exactly one definition of the
 * rule per module.
 */
internal inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}

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
        permissionMode: com.crush.mobile.domain.model.PermissionMode =
            com.crush.mobile.domain.model.PermissionMode.ASK,
        providerId: String = com.crush.mobile.domain.provider.Providers.DEFAULT.id
    ): Ready {
        val ws = workspaces.open(
            path,
            dataDir,
            // ASK means do NOT skip: the engine then emits permission_request
            // events and blocks until the UI answers. This was hardcoded true
            // (yolo) before, so the agent approved its own file edits silently.
            yolo = permissionMode == com.crush.mobile.domain.model.PermissionMode.AUTO_APPROVE
        )
        return Ready(ws.id, providerId, false, listOf("workspace ${ws.id.take(8)} created"))
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
        modelId: String,
        /**
         * Engine agent id to seat as MAIN after init, or null for the engine's
         * own default (the build agent). groq's free tier cannot host the
         * build agent's request size at all (Provider.mainAgent).
         */
        mainAgent: String? = null
    ): Ready {
        val wsId = workspaces.currentWorkspaceId
            ?: return Ready("", providerId, false, listOf("no workspace"))
        val notes = mutableListOf<String>()

        // The retry exists for the boot race: engine v0.98.2 holds writeMu for
        // the whole workspace-config Load (measured ~5s on device while the
        // provider catalogue populates), and a setModel/init arriving inside
        // that window can fail without the engine logging anything we can see.
        // Re-running the whole chain once after it settles fixes today's
        // "slots never got re-seated at boot" symptom (device 2026-10-27).
        var outcome = runChain(wsId, apiKey, providerId, modelId, mainAgent, notes)
        if (!outcome) {
            AppLog.w(TAG, "ensureAgentReady: chain not ready, retrying once: " +
                notes.distinct().take(3).joinToString("; "))
            val retryNotes = mutableListOf<String>()
            val retried = runChain(wsId, apiKey, providerId, modelId, mainAgent, retryNotes)
            outcome = retried
            notes += retryNotes.map { "retry: $it" }
            if (!retried) {
                AppLog.w(TAG, "ensureAgentReady: retry also not ready: " +
                    retryNotes.distinct().take(3).joinToString("; "))
            }
        }

        // The note chain is the ONLY complete account of what the handshake
        // actually did, and until today it lived nowhere: init failures surfaced
        // only at send time (device 2026-10-27 15:25 boot configured nothing and
        // logged nothing), which turned a diagnosable config mismatch into an
        // opaque "no reply". Now every step lands in app.log.
        AppLog.i(TAG, "ensureAgentReady: ${notes.distinct().joinToString("; ")}")
        return Ready(wsId, providerId, outcome, notes)
    }

    /**
     * Runs one full chain: key, both model slots, coordinator, main agent,
     * readiness confirm. Returns whether the agent is ready. Appends every
     * step's result to [notes].
     */
    private suspend fun runChain(
        wsId: String,
        apiKey: String,
        providerId: String,
        modelId: String,
        mainAgent: String?,
        notes: MutableList<String>
    ): Boolean {
        notes.clear()

        // 1. key first - init depends on it.
        // Every runCatching below is the cancellation-safe variant (see
        // runCatchingNonCancellation): these calls sit in suspend contexts and
        // a plain runCatching turned an abort into "step failed, retry once"
        // - work executed after the coroutine was cancelled.
        runCatchingNonCancellation { api.setProviderKey(wsId, providerId, apiKey) }
            .onSuccess { notes += "key set for provider '$providerId'" }
            .onFailure { notes += "provider-key FAILED: ${it.message?.take(120)}" }

        // 2. then the model. Must precede init, otherwise the coordinator
        //    captures the retired default and every turn 400s. Both slots are
        //    re-seated on EVERY chain run even when they look fine: the engine
        //    persists them in files/crush/data/crush.json, and a value written
        //    by an older provider id silently fails agent/init with
        //    "large model provider not configured" - which reads as generic
        //    breakage unless the slots are rewritten with the CURRENT id.
        for (slot in listOf(SetModelRequest.LARGE, SetModelRequest.SMALL)) {
            runCatchingNonCancellation {
                api.setModel(wsId, slot, SelectedModel(model = modelId, provider = providerId))
            }
                .onSuccess { notes += "model[$slot] = $modelId" }
                .onFailure { notes += "model[$slot] FAILED: ${it.message?.take(120)}" }
        }

        // 3. then the coordinator.
        runCatchingNonCancellation { api.initAgent(wsId, interactive = true) }
            .onSuccess { notes += "agent/init ok" }
            .onFailure { notes += "agent/init FAILED: ${it.message?.take(120)}" }

        // 3.5 seat the main agent. AFTER init: /agent/main on a fresh or dead
        //     coordinator 400s with "agent coordinator not initialized"
        //     (measured), and init is what creates it.
        if (!mainAgent.isNullOrBlank()) {
            runCatchingNonCancellation { api.setMainAgent(wsId, mainAgent) }
                .onSuccess { notes += "main agent = $mainAgent" }
                .onFailure { notes += "main agent FAILED: ${it.message?.take(120)}" }
        }

        // 4. confirm
        val info = runCatchingNonCancellation { api.agentInfo(wsId) }.getOrNull()
        val ready = info?.isReady == true
        notes += "is_ready=${info?.isReady} is_busy=${info?.isBusy}"
        return ready
    }

    companion object {
        /** Log tag for the handshake chain (kept local rather than shared). */
        private const val TAG = "CrushSession"

        /**
         * Removed. A default model id was the single cause of a shipped
         * "large model not found in provider" failure, and no constant is a
         * safe substitute for a live catalogue lookup.
         */
        const val DEFAULT_MODEL = ""
    }
}