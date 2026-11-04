package com.crush.mobile.data.crush

import com.crush.mobile.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One long-lived SSE connection per workspace, kept open for as long as the
 * workspace is wanted.
 *
 * WHY THIS REPLACES A STREAM PER TURN
 *
 * Crush reaps a workspace that has no attached client after roughly 30 seconds.
 * The app used to open a stream per turn, so the workspace was guaranteed to
 * die between turns. Everything painful about this app traces back to that: sends
 * failing with "workspace not found", delete addressing a dead workspace, a new
 * session minted on every re-attach so conversations split every third message,
 * and the WorkspaceLease heuristic plus force-retry paths written purely to
 * paper over it.
 *
 * Holding the connection open keeps the workspace attached, so none of that
 * happens. It also fixes an ordering bug for free: with the collector already
 * running, "subscribe before prompting" is trivially true, and the old
 * callbackFlow-per-turn design dropped events that arrived before collection
 * started.
 *
 * The demultiplexing lives in [CrushEventRouter]; this class only owns the
 * socket, the reader thread, and the reconnect policy.
 */
class CrushConnection(
    private val port: Int,
    private val router: CrushEventRouter,
    private val gson: com.google.gson.Gson,
    /**
     * The socket FILE this connection's transport resolves through (S0/F03):
     * transport identity is the socket file, NOT the URL port - every engine
     * reports the sentinel port 1, so identity-by-port could never notice an
     * instance switch.
     */
    private val socketFile: java.io.File,
    /**
     * Resolves THIS attempt's transport from [socketFile] (S0/F03+F11). The
     * client used to be pinned once at construction: a pinned factory kept the
     * PREVIOUS instance's socket when a connection was reused across an
     * instance switch within one ViewModel, and when that engine had been
     * stopped (Go's UnixListener unlinks its socket file on close) the retry
     * loop connected into "No such file or directory" for the whole turn while
     * call-time REST kept succeeding - the exact measured false
     * "no reply received" signature. Resolving per attempt also removes the
     * silent plain-TCP fallback: a missing socket is an explicit, logged
     * attempt failure, and a respawned engine's socket is picked up by the
     * next retry with no caller-side rebuild.
     */
    private val clientSource: (java.io.File) -> OkHttpClient?
) {

    enum class State { IDLE, CONNECTING, CONNECTED, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    /** Live when the reader thread should keep running. */
    private val running = AtomicBoolean(false)

    private val thread = AtomicReference<Thread?>(null)
    private val inFlight = AtomicReference<okhttp3.Call?>(null)

    @Volatile
    private var workspaceId: String? = null

    @Volatile
    private var clientId: String = ""

    /**
     * Unknown outer event `type` names -> frames dropped for each. Keyed lookup
     * doubles as the "already logged" set: a null value is the first occurrence
     * and logs; non-null only counts.
     *
     * TOLERANCE, stated on purpose (P1-15): decode is called from one reader
     * thread today, but the map is @Volatile-read and check-then-write is not
     * atomic - a hypothetical second writer could at worst log a name twice or
     * lose one count. That is acceptable for diagnostics: routing never reads
     * this map, so no race can change what happens to a frame.
     */
    @Volatile
    private var unknownOuterTypes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Ensures a connection exists for [ws].
     *
     * Idempotent, and safe to call repeatedly: if the workspace id is unchanged
     * and the reader is alive this does nothing. A CHANGED workspace id means
     * the engine dropped and re-created it, so the old socket is pointing at a
     * dead workspace and must be replaced.
     */
    @Synchronized
    fun ensureAttached(ws: String, cid: String) {
        val previousCid = clientId
        clientId = cid
        val previousWs = workspaceId

        // A change of EITHER identity invalidates the socket. Client id is not
        // cosmetic: Crush scopes workspace claims per client, so a stream opened
        // under an old client is reading on behalf of claims the engine may have
        // already released. Treating only a workspace change as a replacement
        // leaves that socket attached indefinitely.
        val identityChanged = (previousWs != null && previousWs != ws) ||
            (previousCid.isNotEmpty() && previousCid != cid)

        // Idempotent and safe to call repeatedly: same identity, live reader,
        // nothing to do.
        if (!identityChanged && previousWs == ws && running.get()) {
            if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return
        }

        if (previousWs != null && previousWs != ws) {
            AppLog.i(TAG, "workspace changed $previousWs -> $ws; replacing reader")
        } else if (previousCid.isNotEmpty() && previousCid != cid) {
            AppLog.i(TAG, "client changed $previousCid -> $cid; replacing reader")
        }

        workspaceId = ws

        // THE BUG THIS FIXES.
        //
        // The old code updated `workspaceId` and then returned whenever the reader
        // was alive. So a workspace change while a stream was open left the OLD
        // reader thread blocked on the OLD workspace's socket, while the field
        // claimed the new one. Every subsequent event was attributed to the new
        // workspace, and because the thread never exited the reconnect never
        // happened - so the workspace was reaped after ~30s and the app saw
        // silence until a turn failed.
        //
        // Crush re-attaches with a NEW workspace id on every restart, so this is
        // the common path, not an edge one.
        //
        // A change must REPLACE the reader, not reuse it. Cancelling the in-flight
        // call is what unblocks the old thread; without it the thread sits in
        // readUtf8Line() until the server closes, which is the leak the old
        // `running` flag was hiding rather than fixing.
        if (identityChanged) {
            running.set(false)
            _state.value = State.STOPPED
            inFlight.getAndSet(null)?.cancel()
        }

        if (running.get()) return
        running.set(true)
        _state.value = State.CONNECTING
        val t = Thread({ readLoop() }, "crush-sse")
        t.isDaemon = true
        thread.set(t)
        t.start()
    }

    /**
     * The reader thread identity, for tests.
     *
     * Identity is the whole point: the stale-socket bug was invisible to state
     * assertions because the connection still reported CONNECTED while the thread
     * belonged to the previous workspace. Comparing thread identity is what makes it
     * observable.
     */
    internal fun readerThreadForTest(): Thread? = thread.get()

    /** Tears the connection down. The workspace will then be reaped by Crush. */
    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        _state.value = State.STOPPED
        // Cancel the blocking read; without this the thread sits in
        // readUtf8Line() until the server closes, leaking a thread per stop.
        runCatching { inFlight.getAndSet(null)?.cancel() }
        thread.set(null)
        AppLog.i(TAG, "connection stopped")
    }

    /**
     * The reader loop.
     *
     * Reconnects with the same backoff ladder Crush's own client uses, and
     * gives up if the workspace disappears entirely - at which point the caller
     * must re-attach and call [ensureAttached] again, because a new workspace id
     * means a new socket.
     */
    private fun readLoop() {
        var attempt = 0
        while (running.get()) {
            val ws = workspaceId
            if (ws.isNullOrBlank()) {
                sleep(200)
                continue
            }
            val url = "http://127.0.0.1:$port/v1/workspaces/$ws/events?client_id=$clientId"
            val request = Request.Builder().url(url)
                .header("Accept", "text/event-stream")
                .get()
                .build()

            var sawStream = false
            try {
                // Per-attempt transport (S0/F03+F11): resolve the socket NOW.
                // Null means the file is absent - a connect would be ENOENT
                // against a pinned factory - so fail as one honest attempt
                // (carrying the scrubbed path) and let the backoff ladder
                // retry until the engine binds the file again.
                val transport = clientSource(socketFile) ?: throw IOException(
                    "events: socket not present yet: ${scrubbedPath(socketFile.absolutePath)}"
                )
                val call = transport.newCall(request)
                inFlight.set(call)
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        // 404 here means the workspace is gone, not a transient
                        // blip. Retrying the same dead id forever would hide a
                        // real lifecycle event, so surface it via state and stop.
                        if (response.code == 404) {
                            // THE 404-ZOMBIE BUG (P1-4).
                            //
                            // This branch used to return with `running` and
                            // `thread` still set, so a later ensureAttached with
                            // the SAME identity hit the "same workspace, live
                            // reader" guard and returned early: no reconnect ever
                            // happened. Workspace ids are path-derived, so the id
                            // the app holds does not change when the engine-side
                            // workspace does - re-attach with the same id IS the
                            // recovery path the comment below promised and never
                            // delivered. Meanwhile every turn silently used a dead
                            // socket and fell back to the per-turn stream, which
                            // reaps again after ~30s.
                            //
                            // A 404 means "this workspace id no longer exists on
                            // the engine", not "the client asked to stop" - so
                            // unwind the same state stop() unwinds (running, the
                            // thread reference, the in-flight call, state) while
                            // KEEPING the workspace id: the next ensureAttached,
                            // same id, starts a fresh reader against whatever the
                            // engine now serves under it.
                            AppLog.w(TAG, "events: workspace $ws is gone")
                            runCatching { inFlight.getAndSet(null)?.cancel() }
                            running.set(false)
                            thread.set(null)
                            _state.value = State.IDLE
                            return
                        }
                        throw IOException("events: HTTP ${response.code}")
                    }
                    sawStream = true
                    attempt = 0
                    _state.value = State.CONNECTED
                    AppLog.i(TAG, "connected to workspace $ws")

                    val source = response.body?.source()
                        ?: throw IOException("events: empty body")
                    while (running.get()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith(DATA_PREFIX)) continue
                        val json = line.removePrefix(DATA_PREFIX).trim()
                        if (json.isEmpty() || json == "[DONE]") continue
                        runCatching { decode(json) }
                            // decode returns null for a frame we cannot model
                            // (an unknown payload type). That must be skipped,
                            // not dispatched - and not be treated as a parse
                            // failure either, since it is expected whenever the
                            // engine adds an event type.
                            .onSuccess { decoded -> decoded?.let { router.dispatch(it) } }
                            .onFailure {
                                AppLog.w(TAG, "skip malformed frame: ${it.message}")
                            }
                    }
                }
            } catch (e: Exception) {
                if (!running.get()) break
                if (!sawStream) {
                    AppLog.w(TAG, "events connect failed (attempt $attempt): ${e.message}")
                }
            } finally {
                inFlight.set(null)
            }

            if (!running.get()) break
            if (_state.value != State.IDLE) _state.value = State.CONNECTING
            // P2-16: the shift bound was 4, i.e. 250ms shl 4 = 4s, so the
            // `.coerceAtMost(MAX_BACKOFF_MS)` clamp (10s) could never engage and
            // MAX_BACKOFF_MS was dead. The bound is now 6: 250ms shl 5 = 8s,
            // shl 6 = 16s -> clamped to the documented 10s ceiling at the 6th
            // retry. Same ladder shape Crush's own client uses, just with a
            // reachable top. The reset-to-0 on any successful connect is kept
            // DELIBERATELY (a genuinely fresh connect restarts the ladder) - the
            // fix here is the cap, not a redesign of the reset policy.
            val backoff = (INITIAL_BACKOFF_MS shl attempt.coerceAtMost(6)).toLong()
                .coerceAtMost(MAX_BACKOFF_MS)
            attempt++
            sleep(backoff)
        }
        _state.value = State.STOPPED
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Decodes one `data:` frame into a [CrushEvent].
     *
     * Shares the permissive parsing with [CrushEventStream]: an unknown payload
     * type must be dropped, not fatal, because the engine adds event types
     * between releases and a client that throws on an unknown type stops seeing
     * every event after it.
     *
     * NOTE: consolidating this decoder with [CrushEventStream]'s into a shared
     * helper on [CrushEvent] is DEFERRED (backlog 2026-10-27, deliberate) - the
     * connection path below is the live one; mirroring exact behaviour across
     * both paths stays pinned by their respective tests until then.
     */
    private fun decode(json: String): CrushEvent? {
        val root = com.google.gson.JsonParser.parseString(json)
        if (!root.isJsonObject) return null
        val o = root.asJsonObject
        val outer = o.optString("type")
        // Gson's JsonObject has no optJsonObject; has + isJsonObject + asJsonObject
        // is the safe form, because a present-but-null or present-but-scalar
        // "payload" must drop the frame rather than throw.
        val payloadEl = o.get("payload")
        if (payloadEl == null || payloadEl.isJsonNull || !payloadEl.isJsonObject) return null
        val payload = payloadEl.asJsonObject
        val inner = payload.optString("type")

        val pType = CrushEvent.PayloadType.entries.firstOrNull { it.wire == outer }
        if (pType == null) {
            // 2026-11-01 (0.99.0 promotion): `client_session` is an ADDITIVE
            // engine event first seen on 0.99.0 during the v099 experiment -
            // session-visibility metadata the client does not consume yet.
            // Recognised-but-ignored so each attach does not trip the
            // unknown-type diagnostic below; revisit when the payload is
            // specified upstream.
            if (outer == "client_session") return null
            // P1-15: the drop itself is correct (expecting unknown types is part
            // of the contract with the engine), but it USED to be invisible:
            // an engine release that added an event type left zero trace behind,
            // so "why is my app silently missing data" was undiagnosable from the
            // log. Log ONCE per unknown type name; later frames of the same type
            // drop quietly and only bump the count. Nothing in routing reads
            // this map - it is diagnostics, never behaviour.
            if (!outer.isNullOrEmpty()) {
                val seen = unknownOuterTypes[outer]
                if (seen == null) {
                    AppLog.w(
                        TAG,
                        "events: unknown engine event type \"$outer\"; frame skipped, " +
                            "further frames of this type drop silently"
                    )
                }
                unknownOuterTypes[outer] = (seen ?: 0) + 1
            }
            return null
        }
        val cType = CrushEvent.ChangeType.entries.firstOrNull { it.wire == inner }
            ?: CrushEvent.ChangeType.UPDATED

        val bodyEl = payload.get("payload")
        val body = if (bodyEl != null && bodyEl.isJsonObject) {
            bodyEl.asJsonObject
        } else {
            com.google.gson.JsonObject()
        }
        return CrushEvent(type = pType, change = cType, body = body)
    }

    companion object {
        private const val TAG = "CrushConnection"
        private const val DATA_PREFIX = "data:"
        private const val INITIAL_BACKOFF_MS = 250
        private const val MAX_BACKOFF_MS = 10_000L

        /** Log hygiene shared with CrushEngine: app-private prefix abbreviation. */
        internal fun scrubbedPath(path: String): String =
            path.replace("/data/user/0/", "<datadir>/")
    }
}
