package com.opencode.chat.data.crush

import com.opencode.chat.util.AppLog
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
 */
class WorkspaceManager(private val api: CrushApi) {

    /**
     * Stable per-install identifier. Crush requires a valid UUID and uses it
     * purely as a routing label to group subscribers to a workspace, not as a
     * credential.
     */
    val clientId: String = UUID.randomUUID().toString()

    @Volatile
    private var workspaceId: String? = null

    val currentWorkspaceId: String? get() = workspaceId

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
        yoloMode = yolo
        workspacePath = path
        workspaceDataDir = dataDir
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
        val id = ws.id.ifBlank { requestedId }
        workspaceId = id
        lastAttachedAtMs = System.currentTimeMillis()

        // ALWAYS applied, in both directions.
        //
        // The create-time yolo flag is ignored when the workspace already exists
        // (first-wins), so sending skip=true only on auto-approve left every
        // previously-created workspace permanently in auto-approve: a user who
        // switched to ASK would still get silent approvals with no way to tell.
        // Explicitly sending false is what makes the setting reversible.
        runCatching { api.skipPermissions(id, yolo) }
            .onFailure { AppLog.w("WorkspaceManager", "skipPermissions($yolo) failed: ${it.message}") }

        // The coordinator must exist before a prompt is accepted, otherwise
        // POST .../agent returns 400 "agent coordinator not initialized".
        // Idempotent, so calling it on every attach is safe.
        val ready = runCatching { api.initAgent(id, interactive = true) }
            .onFailure { lastInitError = it.message }
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
        if (!force && WorkspaceLease.isFresh(lastAttachedAtMs, nowMs)) return true
        val path = workspacePath ?: return false
        val dir = workspaceDataDir ?: return false
        // Reuse the mode this workspace was opened with. `?: false` so an
        // unopened workspace fails SAFE to asking rather than to auto-approve.
        return runCatching { open(path, dir, yolo = yoloMode ?: false) }.isSuccess
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
        val wsId = workspaceId
            ?: throw IllegalStateException("no workspace attached - call open() first")
        return api.createSession(wsId, CreateSessionRequest(id = id, title = title))
    }

    fun forget() {
        workspaceId = null
        lastAttachedAtMs = null
    }
}