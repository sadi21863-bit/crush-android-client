package com.crush.mobile.execution

import com.crush.mobile.data.crush.CrushConnection
import com.crush.mobile.data.crush.CrushEvent
import com.crush.mobile.data.crush.CrushEventRouter
import com.crush.mobile.data.crush.runIdOrNull
import com.crush.mobile.engine.EngineInvalidationProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Minimal transport surface the process-scoped adapter owns. */
interface EventConnectionPort {
    val state: StateFlow<CrushConnection.State>
    fun ensureAttached(workspaceId: String, clientId: String)
    fun stop()
}

/** Production wrapper; the delegate is the only CrushConnection instance. */
class CrushConnectionPort(val delegate: CrushConnection) : EventConnectionPort {
    override val state: StateFlow<CrushConnection.State> = delegate.state
    override fun ensureAttached(workspaceId: String, clientId: String) =
        delegate.ensureAttached(workspaceId, clientId)
    override fun stop() = delegate.stop()
}

/**
 * Process-scoped event adapter for A1.
 *
 * Owns observation state and the provisional attachment transaction. It does
 * NOT own a socket (that remains with ChatViewModel until the transport seam
 * is moved in a later A1 slice) and it never starts/stops an engine.
 *
 * Events arriving between validation and commit are buffered in the
 * transaction, never discarded. A failed commit hands them back for
 * reconciliation; a successful commit replays them to the committed
 * subscribers with their attribution unchanged.
 */
class ExecutionEventAdapter(
    private val epochOf: (String) -> Long,
    private val maxBufferedEvents: Int = 64,
    private val maxBufferedBytes: Int = 64 * 1024,
    private val newRouter: () -> CrushEventRouter = { CrushEventRouter() },
    private val newConnection: (
        Int, CrushEventRouter, File, (File) -> OkHttpClient?
    ) -> EventConnectionPort = { _, _, _, _ -> error("no transport factory installed") },
    private val attachToRegistry: (
        String, EventConnectionPort, Long, () -> Boolean
    ) -> Unit = { _, _, _, _ -> },
    private val revokeFromRegistry: (String, Long, String) -> Unit = { _, _, _ -> },
    private val isSupersededInRegistry: (String, Long) -> Boolean = { _, _ -> false },
    private val onConfirmedEvent: (String, ExecutionEvent) -> Unit = { _, _ -> },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private class ConnectionEntry(
        val router: CrushEventRouter,
        var conn: EventConnectionPort?,
        var identity: String?,
        val epoch: Long,
        var feedJob: Job? = null,
    )

    private val connections = ConcurrentHashMap<String, ConnectionEntry>()
    private class Provisional(
        val key: String,
        val token: Long,
        val epoch: Long,
    ) {
        val buffer = mutableListOf<ExecutionEvent>()
        var bytes: Int = 0
        var overflowed: Boolean = false
    }

    private val provisional = ConcurrentHashMap<String, Provisional>()
    private val subscribers = ConcurrentHashMap<String, MutableList<Channel<ExecutionEvent>>>()
    private val declaredRuns = ConcurrentHashMap<String, MutableSet<String>>()
    private val tokenSource = AtomicLong(0L)

    /** Starts a provisional attachment; returns its token. */
    fun begin(key: String): Long {
        val token = tokenSource.incrementAndGet()
        provisional[key] = Provisional(key, token, epochOf(key))
        declaredRuns.putIfAbsent(key, mutableSetOf())
        return token
    }

    fun declare(key: String, runId: String) {
        declaredRuns.computeIfAbsent(key) { mutableSetOf() }.add(runId)
    }

    /**
     * Delivers an event. Returns false when the provisional buffer is full;
     * the event is dropped and the transaction is marked INCOMPLETE via
     * [overflowed], never silently accepted.
     */
    fun onEvent(key: String, event: ExecutionEvent): Boolean {
        val pending = provisional[key]
        if (pending != null) {
            val size = (event.detail?.length ?: 0) + 64
            if (pending.buffer.size >= maxBufferedEvents || pending.bytes + size > maxBufferedBytes) {
                pending.overflowed = true
                return false
            }
            pending.buffer.add(event)
            pending.bytes += size
            return true
        }
        return dispatch(key, event)
    }

    /**
     * Commits a provisional attachment only if the epoch is unchanged. On
     * rejection nothing is published: the caller must roll back.
     */
    fun commit(key: String, token: Long): CommitResult {
        val pending = provisional[key] ?: return CommitResult.Rejected("no provisional attachment")
        if (pending.token != token) return CommitResult.Rejected("stale token")
        if (epochOf(key) != pending.epoch) return CommitResult.Rejected("epoch changed")
        if (pending.overflowed) return CommitResult.Rejected("buffer overflow: INCOMPLETE")

        provisional.remove(key, pending)
        subscribers.computeIfAbsent(key) { mutableListOf() }
        pending.buffer.forEach { dispatch(key, it) }
        return CommitResult.Committed
    }

    /**
     * Rolls back a provisional attachment and returns its buffered events so
     * the controller can feed them into reconciliation. Nothing is published.
     */
    fun rollback(key: String, token: Long): List<ExecutionEvent> {
        val pending = provisional[key] ?: return emptyList()
        if (pending.token != token) return emptyList()
        provisional.remove(key, pending)
        return pending.buffer.toList()
    }

    fun register(key: String, runId: String): Channel<ExecutionEvent> {
        val ch = Channel<ExecutionEvent>(Channel.BUFFERED)
        subscribers.computeIfAbsent(key) { mutableListOf() }.add(ch)
        declaredRuns.computeIfAbsent(key) { mutableSetOf() }.add(runId)
        return ch
    }

    fun unregister(key: String, runId: String, ch: Channel<ExecutionEvent>) {
        declaredRuns[key]?.remove(runId)
        subscribers[key]?.let { list ->
            synchronized(list) { list.remove(ch) }
        }
        ch.close()
    }

    fun isDeclared(key: String, runId: String): Boolean = declaredRuns[key]?.contains(runId) == true

    // ---- real transport ownership -------------------------------------------------

    /** Establish/reuse the process-owned reader for an instance key. */
    fun attach(
        key: String,
        workspaceId: String,
        clientId: String,
        port: Int,
        socketFile: File,
        ownerEpoch: Long,
        runInFlightOnKey: () -> Boolean,
        clientSource: (File) -> OkHttpClient?,
    ) {
        // One effective lifecycle per key: the check, the old teardown, and
        // the new registration are one critical section. Two simultaneous
        // attaches cannot both create a reader.
        synchronized(connections) {
            val identity = socketFile.absolutePath
            val existing = connections[key]
            if (existing != null && existing.identity == identity) {
                existing.conn?.ensureAttached(workspaceId, clientId)
                return
            }
            existing?.conn?.stop()
            existing?.router?.close()
            existing?.feedJob?.cancel()

            val router = newRouter()
            val conn = newConnection(port, router, socketFile, clientSource)
            val entry = ConnectionEntry(router, conn, identity, epochOf(key))
            connections[key] = entry
            val feedChannel = router.registerAll()
            entry.feedJob = scope.launch {
                for (frame in feedChannel) feed(key, frame)
            }
            attachToRegistry(key, conn, ownerEpoch, runInFlightOnKey)
            conn.ensureAttached(workspaceId, clientId)
        }
    }

    fun ensureAttached(key: String, workspaceId: String, clientId: String) {
        connections[key]?.conn?.ensureAttached(workspaceId, clientId)
    }

    fun router(key: String): CrushEventRouter? = connections[key]?.router

    fun connection(key: String): EventConnectionPort? = connections[key]?.conn

    fun connectionState(key: String): StateFlow<CrushConnection.State>? = connections[key]?.conn?.state

    fun isSuperseded(key: String, ownerEpoch: Long): Boolean =
        isSupersededInRegistry(key, ownerEpoch)

    fun revokeIfOwn(key: String, ownerEpoch: Long, reason: String) {
        revokeFromRegistry(key, ownerEpoch, reason)
    }

    fun registerRun(key: String, runId: String): Channel<CrushEvent>? =
        connections[key]?.router?.register(runId)

    fun unregisterRun(key: String, runId: String, ch: Channel<CrushEvent>) {
        connections[key]?.router?.unregister(runId, ch)
    }

    fun completeRun(key: String, runId: String) {
        connections[key]?.router?.completeRun(runId)
    }

    /** Stop the reader but keep the router, matching the provider-switch shape. */
    fun stop(key: String) {
        EngineInvalidationProbe.fire("adapter.stop", key)
        connections[key]?.conn?.stop()
    }

    /** Stops the reader and closes the router, matching identity-change teardown. */
    fun close(key: String) {
        EngineInvalidationProbe.fire("adapter.close", key)
        connections.remove(key)?.let { entry ->
            entry.feedJob?.cancel()
            entry.conn?.stop()
            entry.router.close()
        }
        superCloseObservation(key)
    }

    private fun superCloseObservation(key: String) {
        provisional.remove(key)
        val list = subscribers.remove(key) ?: return
        synchronized(list) {
            list.forEach { it.close() }
            list.clear()
        }
    }

    /**
     * Maps a real routed frame into an A1 event. Attribution is derived from
     * the wire run id only; ordinary frames remain unattributed and can never
     * claim execution ownership. No raw body is persisted or published.
     */
    fun feed(key: String, frame: CrushEvent): Boolean {
        // Stale connection rejection is by instance identity + invalidation
        // epoch, never by session id alone. A frame from an invalidated
        // reader cannot mutate a newer execution.
        val entry = connections[key] ?: return false
        if (entry.epoch != epochOf(key)) return false
        val runId = frame.runIdOrNull()
        val attribution = if (runId != null && isDeclared(key, runId)) {
            Attribution.CONFIRMED
        } else {
            Attribution.UNATTRIBUTED
        }
        // A1 device evidence: proves the process-owned feed is alive and
        // receiving real routed frames. No body, prompt or credential is logged.
        com.crush.mobile.util.AppLog.i(
            "ExecutionEventAdapter",
            "feed key=$key type=${frame.type.name} attribution=$attribution epoch=${entry.epoch}",
        )
        // Only CONFIRMED events may change execution state. The app must call
        // declare(key, runId) before the POST for ordinary frames to qualify.
        val event = ExecutionEvent(
            type = when (frame.type.name) {
                "MESSAGE" -> ExecutionEvent.Type.MESSAGE
                "RUN_COMPLETE" -> ExecutionEvent.Type.RUN_COMPLETE
                "PERMISSION_REQUEST" -> ExecutionEvent.Type.PERMISSION
                "QUESTION_REQUEST" -> ExecutionEvent.Type.QUESTION
                else -> ExecutionEvent.Type.OTHER
            },
            attribution = attribution,
            runId = runId,
            detail = frame.type.name,
        )
        if (attribution == Attribution.CONFIRMED) onConfirmedEvent(key, event)
        return onEvent(key, event)
    }

    private fun dispatch(key: String, event: ExecutionEvent): Boolean {
        val list = subscribers[key] ?: return false
        val targets = synchronized(list) { list.toList() }
        targets.forEach { it.trySend(event) }
        return true
    }
}
