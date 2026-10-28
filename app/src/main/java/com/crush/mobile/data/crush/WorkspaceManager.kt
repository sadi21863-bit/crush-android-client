package com.crush.mobile.data.crush

import com.crush.mobile.util.AppLog
import java.util.UUID

/**
 * Owns the workspace lifecycle.
 *
 * Crush assigns a workspace a random UUID id and resolves identity by canonical
 * path instead. That id therefore CHANGES ON EVERY ENGINE RESTART, so it must
 * never be persisted — only the path is durable, and the id is re-acquired by
 * re-POSTing /workspaces after each boot.
 *
 * MEASURED ON DEVICE: a workspace with no attached clients is REAPED after about
 * 30 seconds.
 *
 *     t=0s   ALIVE
 *     t=15s  ALIVE
 *     t=30s  GONE (404)
 *
 * This is the single most important fact about workspace lifecycle here. A cached
 * id is therefore only good for seconds, not minutes, and any code that treats it
 * as durable will 404. See WorkspaceLease for the freshness rule and
 * [reattachIfStale] for the only correct way to use a cached id.
 *
 * SECOND fact, same class of bug: the id is minted per ENGINE PROCESS, so even a
 * seconds-old id dies the moment the engine restarts. The engine generation check
 * (see the companion's onEngineRestarted, wired from AppContainer) flushes a cached
 * id that predates the running engine run; flush is lazy-by-read, performed by
 * liveIdOrNull() the next time anything asks for the id.
 */
class WorkspaceManager(private val api: CrushApi) {

    /**
     * Engine-generation counter (P1-11).
     *
     * Crush mints a workspace a fresh UUID PER ENGINE PROCESS, so an id is
     * only valid for one engine run. The 15 s freshness lease on its own
     * cannot see an engine restart: an id attached 3 s before a restart is
     * "fresh" and completely dead. AppContainer registers exactly one
     * supervisor listener (see EngineSupervisor.onEngineStart) that calls
     * [onEngineRestarted]; every manager instance records the generation its
     * cached id was minted in and refuses - then flushes - any id whose
     * generation is older. This is companion-level because WorkspaceManager
     * instances are created OUTSIDE the DI graph (ChatViewModel builds its
     * own per bootstrap), so per-instance registration there would go through
     * a file this change does not own and would leak instances (a new manager
     * per bootstrap is routine).
     */
    companion object {
        /** Bumped once per NEW engine run; monotonic, any thread, at any time. */
        private val engineGeneration = java.util.concurrent.atomic.AtomicLong(0L)

        /**
         * Called by the DI wiring when a new engine run starts. Doing the bump
         * through a companion function (not per-instance listeners) is what
         * reaches manager instances the container never sees.
         */
        fun onEngineRestarted() {
            engineGeneration.incrementAndGet()
        }

        internal fun currentEngineGeneration(): Long = engineGeneration.get()

        /**
         * Pure trust rule, extracted so WorkspaceLeaseTest can pin it without
         * a live API: a cached id is trustworthy only if it was minted by the
         * generation currently running. Fails closed on unknown minting
         * generation, exactly like WorkspaceLease.isFresh fails closed on an
         * unknown attach time.
         */
        internal fun isIdTrustworthy(mintedAtGeneration: Long?, currentGeneration: Long): Boolean =
            mintedAtGeneration != null && mintedAtGeneration == currentGeneration
    }

    /**
     * Stable per-install identifier. Crush requires a valid UUID and uses it
     * purely as a routing label to group subscribers to a workspace, not as a
     * credential.
     */
    val clientId: String = UUID.randomUUID().toString()

    @Volatile
    private var workspaceId: String? = null

    /**
     * The engine generation that MINTED [workspaceId]. Written together with
     * the id on a successful open; read against [currentEngineGeneration] by
     * [liveIdOrNull]. Not synchronized with the two volatile writes because a
     * torn read can only ever be MORE conservative (an id seen with an older
     * generation is flushed, and reattach re-POSTs and rewrites both).
     */
    @Volatile
    private var workspaceGeneration: Long? = null

    val currentWorkspaceId: String? get() = liveIdOrNull()

    /**
     * Returns the cached workspace id ONLY when it was minted by the engine
     * generation now running, flushing it otherwise.
     *
     * This flush is lazy-by-read rather than push: AppContainer's listener
     * bumps a counter, and the next consumer that asks for the id performs the
     * forget. A push-based flush is impossible without a registry of instances
     * (there is none, and building one would fight ChatViewModel's
     * construct-per-bootstrap pattern).
     */
    private fun liveIdOrNull(): String? {
        val id = workspaceId ?: return null
        if (!WorkspaceManager.isIdTrustworthy(workspaceGeneration, WorkspaceManager.currentEngineGeneration())) {
            // FLUSH (P1-11): the id predates the running engine. null out the
            // id AND the attach time - keeping the timestamp would let
            // isFresh() still say "fresh" for dead-on-arrival ids - but keep
            // path/dataDir (durable) and yoloMode (needed to re-open safely),
            // which is everything reattachIfStale asks for.
            workspaceId = null
            lastAttachedAtMs = null
            AppLog.w(
                "WorkspaceManager",
                "engine restarted; dropped workspace id minted by a previous engine run"
            )
            return null
        }
        return id
    }

    /**
     * When the workspace was last successfully attached, or null if never.
     * Consulted by [reattachIfStale] so a send does not have to assume.
     */
    @Volatile
    var lastAttachedAtMs: Long? = null
        private set

    /** The durable identity. Only this may be persisted across engine restarts. */
    @Volatile
    private var workspacePath: String? = null

    private var workspaceDataDir: String? = null

    /**
     * Creates (or re-attaches to) the workspace for [path] and starts its agent.
     *
     * Pass an explicit [id] derived from the path to get a deterministic one;
     * Crush accepts an arbitrary UUID here and returns it unchanged, which
     * survives engine restarts because [path] is what it actually keys on.
     *
     * Re-POSTing is idempotent: it re-attaches to the SAME logical workspace and
     * revives it if it was reaped, which is what makes the deterministic id safe.
     */
    /**
     * The permission mode this workspace was last opened with.
     *
     * CRITICAL, and this was a real regression: reattachIfStale() called
     * open(path, dir) which took the `yolo: Boolean = true` default, so it issued
     * skipPermissions(id, TRUE) on every re-attach. Since send(), openSession(),
     * newThread(), deleteSession(), loadSessions() and resolvePermission() all
     * re-attach first, and the workspace is reaped after ~30s against a 15s
     * freshness window, essentially EVERY send flipped the workspace back to
     * auto-approve. The permission dialog was unreachable in normal use while the
     * UI claimed the opposite.
     *
     * Null means never opened, which now fails SAFE to ASK rather than to
     * auto-approve.
     */
    @Volatile
    private var yoloMode: Boolean? = null

    suspend fun open(path: String, dataDir: String, yolo: Boolean): Workspace {
        // P2-22: createWorkspace can legitimately throw (engine cold-boot,
        // mid-restart socket churn). NOTHING below this line runs until it
        // succeeds, so a failed open leaves the PREVIOUS truthful state
        // untouched: the old yoloMode still describes what will be re-applied
        // on a later reattach, and no phantom path/claim lingers for
        // reattachIfStale to resurrect half-configured.
        val requestedId = UUID.nameUUIDFromBytes(path.toByteArray()).toString()
        val ws = api.createWorkspace(
            CreateWorkspaceRequest(
                id = requestedId,
                path = path,
                dataDir = dataDir,
                yolo = yolo,
                clientId = clientId
            )
        )
        // Success: ONLY NOW commit identity + permission mode (P2-22), then
        // the id with the generation that minted it (P1-11), then the lease
        // timestamp. Order matters even at success: an id is claimed for a
        // generation only after the POST that created it returned.
        yoloMode = yolo
        workspacePath = path
        workspaceDataDir = dataDir
        val id = ws.id.ifBlank { requestedId }
        workspaceId = id
        workspaceGeneration = WorkspaceManager.currentEngineGeneration()
        lastAttachedAtMs = System.currentTimeMillis()

        // ALWAYS applied, in both directions.
        //
        // The create-time yolo flag is ignored when the workspace already exists
        // (first-wins), so sending skip=true only on auto-approve left every
        // previously-created workspace permanently in auto-approve: a user who
        // switched to ASK would still get silent approvals with no way to tell.
        // Explicitly sending false is what makes the setting reversible.
        // runCatchingNonCancellation (not plain runCatching): this sits in a
        // suspend context; a cancelled caller must abort, not be logged as a
        // permission-mode failure and continue.
        runCatchingNonCancellation { api.skipPermissions(id, yolo) }
            .onFailure { AppLog.w("WorkspaceManager", "skipPermissions($yolo) failed: ${it.message}") }

        // The coordinator must exist before a prompt is accepted, otherwise
        // POST .../agent returns 400 "agent coordinator not initialized".
        // Idempotent, so calling it on every attach is safe.
        //
        // The failure is logged, not just swallowed into lastInitError: this
        // exact call failing on 2026-10-27 (stale opencode-zen model slots
        // left by an older provider config) produced no visible trace for the
        // whole session until a send surfaced "large model provider not
        // configured" minutes later.
        val ready = runCatchingNonCancellation { api.initAgent(id, interactive = true) }
            .onFailure {
                lastInitError = it.message
                AppLog.w("WorkspaceManager", "agent/init failed on open(): ${it.message?.take(160)}")
            }
            .isSuccess

        return ws.copy(id = id)
    }

    /**
     * Re-attaches if the cached workspace may have been reaped.
     *
     * MUST be called immediately before any operation that addresses a workspace
     * by id. Returns true when the caller may proceed, false when the workspace
     * could not be re-acquired (in which case the caller must surface an error
     * rather than send into a dead id).
     */
    suspend fun reattachIfStale(
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): Boolean {
        // P1-11: freshness AND generation must both hold. liveIdOrNull()
        // performs the generation check and, on a mismatch, flushes the dead
        // id plus its attach timestamp - which is what forces the re-POST
        // below even when the lease is seconds old: an id attached right
        // before a restart IS "fresh" and IS dead.
        if (!force &&
            WorkspaceLease.isFresh(lastAttachedAtMs, nowMs) &&
            liveIdOrNull() != null
        ) return true
        val path = workspacePath ?: return false
        val dir = workspaceDataDir ?: return false
        // Reuse the mode this workspace was opened with. `?: false` so an
        // unopened workspace fails SAFE to asking rather than to auto-approve.
        // runCatchingNonCancellation: a cancelled caller must NOT be recorded
        // as "reattach failed" and retried after the coroutine was already
        // told to stop.
        return runCatchingNonCancellation {
            open(path, dir, yolo = yoloMode ?: false)
        }.isSuccess
    }

    @Volatile
    var lastInitError: String? = null
        private set

    /**
     * Creates a session. Surfaces the failure rather than swallowing it - a
     * silent null previously looked like "no reply from the model" when the
     * real cause was a stale workspace after an engine restart.
     */
    suspend fun newSession(title: String): Session {
        val id = "ses_" + UUID.randomUUID().toString().replace("-", "").take(20)
        // Live id (through the generation flush), not the raw field: a
        // session created against an id from a PREVIOUS engine run would
        // 404 the same way a send would.
        val wsId = liveIdOrNull()
            ?: throw IllegalStateException("no workspace attached - call open() first")
        return api.createSession(wsId, CreateSessionRequest(id = id, title = title))
    }

    fun forget() {
        workspaceId = null
        workspaceGeneration = null
        lastAttachedAtMs = null
    }
}