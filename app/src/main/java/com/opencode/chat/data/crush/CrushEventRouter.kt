package com.opencode.chat.data.crush

import com.google.gson.Gson
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Routes engine events to whichever run asked for them.
 *
 * WHY A LONG-LIVED CONNECTION INSTEAD OF ONE STREAM PER TURN
 *
 * The engine reaps a workspace that has no attached client after roughly 30
 * seconds. Opening an SSE connection per turn therefore guaranteed the workspace
 * died between turns, which is the root of a long chain of defects in this
 * project:
 *
 *  - sends failing with "workspace not found" after a pause
 *  - delete failing, because it addressed a reaped workspace
 *  - a new session being minted on every re-attach, splitting conversations
 *  - the lease heuristic and force-retry code that all existed to paper over it
 *
 * Holding ONE workspace-scoped connection open for as long as the workspace is
 * wanted keeps it attached and removes that whole class of failure. It also
 * fixes an ordering bug for free: the collector is already connected before a
 * prompt is sent, so an event that arrives immediately cannot be missed. With
 * per-turn streams, `callbackFlow` only starts its reader on collection, so
 * posting before collecting dropped early events.
 *
 * WHY THIS CLASS EXISTS SEPARATELY
 *
 * The routing is the part that can be tested without an engine, a device, or a
 * real socket. The connection itself is plumbing. Keeping the demultiplexing
 * pure means the ordering guarantee is pinned by unit tests rather than by
 * hoping.
 */
class CrushEventRouter {

    private val lock = Any()
    private val byRun = mutableMapOf<String, MutableList<Channel<CrushEvent>>>()
    private val broadcast = mutableListOf<Channel<CrushEvent>>()
    private var closed = false

    /**
     * Channel for a run. Unbounded with a drop-oldest buffer: if a consumer is
     * slow, the OLDEST event is the one to lose, because a stale token is worse
     * than a late one and a stalled consumer must never block the engine reader.
     */
    private fun newChannel(): Channel<CrushEvent> =
        Channel(Channel.UNLIMITED, BufferOverflow.DROP_OLDEST)

    /**
     * Registers interest in [runId] and returns the channel to collect from.
     *
     * Call this BEFORE prompting. The previous design created the flow first but
     * only collected after the POST returned, so a fast engine could emit and
     * finish a run before anyone was listening.
     */
    fun register(runId: String): Channel<CrushEvent> {
        val ch = newChannel()
        synchronized(lock) {
            if (closed) {
                // Hand back a closed channel rather than one that will never
                // receive. The caller sees the run as immediately finished
                // instead of hanging forever waiting for events.
                ch.close()
            } else {
                byRun.getOrPut(runId) { mutableListOf() }.add(ch)
            }
        }
        return ch
    }

    /** Registers interest in everything, for workspace-level events. */
    fun registerAll(): Channel<CrushEvent> {
        val ch = newChannel()
        synchronized(lock) {
            if (closed) ch.close() else broadcast += ch
        }
        return ch
    }

    fun unregister(runId: String, ch: Channel<CrushEvent>) {
        synchronized(lock) {
            byRun[runId]?.let { list ->
                list.remove(ch)
                if (list.isEmpty()) byRun.remove(runId)
            }
            broadcast.remove(ch)
            ch.close()
        }
    }

    /**
     * Delivers one event.
     *
     * Sends to the run named by the event, and to every catch-all subscriber.
     * Delivering to both is deliberate: a workspace-level event (permission
     * request, say) has no run id but must still reach the UI.
     */
    fun dispatch(event: CrushEvent) {
        val targets = mutableListOf<Channel<CrushEvent>>()
        synchronized(lock) {
            if (closed) return
            val runId = event.runIdOrNull()
            if (runId != null) byRun[runId]?.let { targets += it }
            targets += broadcast
        }
        // Outside the lock: a channel send must never hold the router's monitor.
        targets.forEach { it.trySend(event) }
    }

    /** How many channels are waiting on [runId]. Used by tests. */
    fun subscriberCount(runId: String): Int =
        synchronized(lock) { byRun[runId]?.size ?: 0 }

    fun hasSubscribers(): Boolean =
        synchronized(lock) { byRun.isNotEmpty() || broadcast.isNotEmpty() }

    /** Ends the turn for [runId] without tearing down the connection. */
    fun completeRun(runId: String) {
        val targets = synchronized(lock) {
            val list = byRun.remove(runId).orEmpty()
            list.forEach { it.close() }
            list
        }
        targets.forEach { it.close() }
    }

    /** Shuts down the router. Every outstanding channel is closed. */
    fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            val all = byRun.values.flatten() + broadcast
            byRun.clear()
            broadcast.clear()
            all
        }
        toClose.forEach { it.close() }
    }
}

/**
 * Pulls a run id out of an event, if it has one.
 *
 * Events are `{"type":...,"payload":{"type":...,"payload":{...}}}`. Only some
 * carry a run id, and it is not always at the same depth, so this checks the
 * likely places and returns null rather than guessing a field that exists.
 *
 * [CrushEvent.body] is already a parsed [com.google.gson.JsonObject] - the
 * stream already deserialises it - so there is nothing to re-parse here.
 */
internal fun CrushEvent.runIdOrNull(): String? = findRunId(this.body)

private fun findRunId(el: com.google.gson.JsonElement?): String? {
    if (el == null || el.isJsonNull) return null
    if (el.isJsonObject) {
        val o = el.asJsonObject
        for (key in listOf("run_id", "runID", "runId")) {
            o.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        // Descend one level: run_id usually sits on the inner payload.
        for (v in o.entrySet().map { it.value }) {
            findRunId(v)?.let { return it }
        }
    } else if (el.isJsonArray) {
        for (v in el.asJsonArray) {
            findRunId(v)?.let { return it }
        }
    }
    return null
}
