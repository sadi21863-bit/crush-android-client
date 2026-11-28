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
import java.util.Collections
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
    /**
     * A declaration carries the EXACT execution identity. It is created before
     * submission, retained after ACK, and retired only on a confirmed terminal
     * observation or explicit invalidation — never at ACK time.
     */
    data class Declaration(
        val key: String,
        val executionId: String,
        val runId: String,
        val generation: Long?,
        val state: State,
    ) {
        enum class State { ACTIVE, SUPERSEDED, RETIRED }
    }

    private val declarations = ConcurrentHashMap<Pair<String, String>, Declaration>()
    /** Serializes replacement/retirement per instance key. */
    private val declarationLock = Any()
    /** Bounded read-only history so a late old-run frame maps to its own identity. */
    private val retiredHistory =
        ConcurrentHashMap<String, MutableList<Declaration>>()
    private val tokenSource = AtomicLong(0L)

    private fun historyOf(key: String): MutableList<Declaration> =
        retiredHistory.computeIfAbsent(key) { Collections.synchronizedList(mutableListOf()) }

    private fun pushHistory(declaration: Declaration) {
        val list = historyOf(declaration.key)
        synchronized(list) {
            list += declaration
            while (list.size > DECLARATION_HISTORY_LIMIT) list.removeAt(0)
        }
    }

    /** Starts a provisional attachment; returns its token. */
    fun begin(key: String): Long {
        val token = tokenSource.incrementAndGet()
        provisional[key] = Provisional(key, token, epochOf(key))
        return token
    }

    /**
     * Declares a run before submission. Any prior ACTIVE declaration for the
     * same instance key is superseded and moved to bounded read-only history;
     * its late frames can resolve only to their own execution, never this one.
     */
    fun declare(
        key: String,
        runId: String,
        generation: Long? = null,
        executionId: String,
    ) {
        synchronized(declarationLock) {
            declarations.entries.toList().forEach { (k, d) ->
                if (k.first == key && d.state == Declaration.State.ACTIVE) {
                    declarations.remove(k)
                    pushHistory(d.copy(state = Declaration.State.SUPERSEDED))
                }
            }
            declarations[key to runId] = Declaration(
                key, executionId, runId, generation, Declaration.State.ACTIVE,
            )
        }
        com.crush.mobile.util.AppLog.i(
            "ExecutionEventAdapter",
            "declare key=$key run=$runId exec=$executionId generation=$generation",
        )
    }

    /** Exact-resolution only: no latest-run or single-run fallback. */
    fun resolveDeclaration(key: String, runId: String?): Declaration? {
        if (runId == null) return null
        declarations[key to runId]?.let { return it }
        val list = historyOf(key)
        return synchronized(list) { list.firstOrNull { it.runId == runId } }
    }

    fun generationFor(runId: String): Long? =
        declarations.entries.firstOrNull { it.value.runId == runId }?.value?.generation

    /** Explicit invalidation; never called merely because ACK became durable. */
    fun forgetRun(key: String, runId: String) {
        synchronized(declarationLock) {
            declarations.remove(key to runId)
            val list = historyOf(key)
            synchronized(list) { list.removeAll { it.runId == runId } }
        }
    }

    /** Terminal observation: retain identity read-only, never mutate from it. */
    fun retireRun(key: String, runId: String) {
        synchronized(declarationLock) {
            val declaration = declarations.remove(key to runId) ?: return
            pushHistory(declaration.copy(state = Declaration.State.RETIRED))
        }
    }

    companion object {
        private const val DECLARATION_HISTORY_LIMIT = 8
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
        // Test-only subscription path: no production caller. A blank execution
        // id means this declaration can never authorize a durable mutation.
        declarations.putIfAbsent(
            key to runId,
            Declaration(key, "", runId, null, Declaration.State.ACTIVE),
        )
        return ch
    }

    fun unregister(key: String, runId: String, ch: Channel<ExecutionEvent>) {
        declarations.remove(key to runId)
        subscribers[key]?.let { list ->
            synchronized(list) { list.remove(ch) }
        }
        ch.close()
    }

    fun isDeclared(key: String, runId: String): Boolean =
        resolveDeclaration(key, runId) != null

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
        // Teardown retires declarations to read-only history; a late frame can
        // still resolve to its own identity but can never authorize a mutation.
        synchronized(declarationLock) {
            declarations.entries.toList().forEach { (k, d) ->
                if (k.first == key) {
                    declarations.remove(k)
                    pushHistory(d.copy(state = Declaration.State.RETIRED))
                }
            }
        }
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
        // Exact resolution only. No latest-run or single-run fallback: an
        // anonymous MESSAGE (including finishError) stays UNATTRIBUTED here.
        val declaration = resolveDeclaration(key, runId)
        val attribution = if (declaration != null) {
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
        val isRunComplete = frame.type.name == "RUN_COMPLETE"
        // The engine's run_complete carries `cancelled`; do not invent a
        // failure/stop event type. A cancelled completion is still terminal,
        // but it must not be rendered as a successful completion.
        val detail = if (isRunComplete) {
            if (frame.cancelledOrNull() == true) "confirmed_cancelled" else "confirmed_completion"
        } else {
            frame.type.name
        }
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
            detail = detail,
            generation = declaration?.generation,
            executionId = declaration?.executionId,
            // Superseded/retired declarations are read-only: the event may be
            // attributed to its own identity, but must never mutate a newer run.
            superseded = declaration?.state != Declaration.State.ACTIVE,
        )
        if (attribution == Attribution.CONFIRMED) onConfirmedEvent(key, event)
        return onEvent(key, event)
    }

    /** Bounded two-hop search; never guesses a field the wire does not carry. */
    private fun CrushEvent.cancelledOrNull(): Boolean? {
        fun child(obj: com.google.gson.JsonObject?, name: String) =
            obj?.takeIf { it.has(name) && it.get(name).isJsonObject }
                ?.getAsJsonObject(name)
        val outer = child(body, "payload")
        return listOfNotNull(body, outer, child(outer, "payload"))
            .firstNotNullOfOrNull { obj ->
                obj.takeIf { it.has("cancelled") && !it.get("cancelled").isJsonNull }
                    ?.get("cancelled")?.asBoolean
            }
    }

    private fun dispatch(key: String, event: ExecutionEvent): Boolean {
        val list = subscribers[key] ?: return false
        val targets = synchronized(list) { list.toList() }
        targets.forEach { it.trySend(event) }
        return true
    }
}
