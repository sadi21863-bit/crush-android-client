package com.opencode.chat.data.crush

import com.opencode.chat.util.AppLog
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
    private val client: OkHttpClient
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
     * Ensures a connection exists for [ws].
     *
     * Idempotent, and safe to call repeatedly: if the workspace id is unchanged
     * and the reader is alive this does nothing. A CHANGED workspace id means
     * the engine dropped and re-created it, so the old socket is pointing at a
     * dead workspace and must be replaced.
     */
    @Synchronized
    fun ensureAttached(ws: String, cid: String) {
        clientId = cid
        if (workspaceId == ws && running.get()) {
            // Already attached to the right workspace.
            if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return
        } else if (workspaceId != null && workspaceId != ws) {
            AppLog.i(TAG, "workspace changed ${workspaceId} -> $ws; reconnecting")
        }
        workspaceId = ws
        if (running.get()) return
        running.set(true)
        _state.value = State.CONNECTING
        val t = Thread({ readLoop() }, "crush-sse")
        t.isDaemon = true
        thread.set(t)
        t.start()
    }

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
                val call = client.newCall(request)
                inFlight.set(call)
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        // 404 here means the workspace is gone, not a transient
                        // blip. Retrying the same dead id forever would hide a
                        // real lifecycle event, so surface it via state and stop.
                        if (response.code == 404) {
                            AppLog.w(TAG, "events: workspace $ws is gone")
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
            val backoff = (INITIAL_BACKOFF_MS shl attempt.coerceAtMost(4)).toLong()
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

        val pType = CrushEvent.PayloadType.entries.firstOrNull { it.wire == outer } ?: return null
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

    private companion object {
        const val TAG = "CrushConnection"
        const val DATA_PREFIX = "data:"
        const val INITIAL_BACKOFF_MS = 250
        const val MAX_BACKOFF_MS = 10_000L
    }
}
