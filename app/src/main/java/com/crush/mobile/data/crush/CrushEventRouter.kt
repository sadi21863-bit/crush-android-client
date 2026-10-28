package com.crush.mobile.data.crush

import com.crush.mobile.util.AppLog
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
     * Channel for a run.
     *
     * BOUNDED, because `Channel.UNLIMITED` is not an overflow strategy - it is
     * the absence of one. With DROP_OLDEST on an unbounded channel nothing is
     * ever dropped, because nothing is ever full, so a consumer that is slow or
     * absent accumulates every token frame for the life of the process. On a
     * device with 0.7-1.0 GB free that is an OOM waiting for a long answer.
     *
     * 512 is roughly a thousand tokens of headroom, which is far more than the
     * collector needs: it republishes WHOLE messages, so it coalesces as it
     * goes and never falls behind by more than a frame or two. If it somehow
     * does, dropping the OLDEST frame is correct - a stale token is worse than a
     * late one, and a stalled consumer must never block the engine reader.
     */
    private fun newChannel(): Channel<CrushEvent> =
        Channel(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)

    companion object {
        /** Bounded on purpose - see [newChannel]. */
        const val QUEUE_CAPACITY = 512

        private const val TAG = "CrushEventRouter"
    }

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
     * Delivery rules:
     *
     *  - an event carrying a run id goes to that run's channels, AND to every
     *    catch-all subscriber;
     *  - an event WITHOUT a run id is fanned out to every registered run AND
     *    to the catch-alls.
     *
     * The second rule is D12. Only `run_complete` (and `agent_event`) echo the
     * caller-supplied run id; the wire shapes of `permission_request`,
     * `question_batch_request`, and EVERY `message` frame have no run id at all,
     * because the engine's `proto.Message`/`proto.PermissionRequest` DTOs carry
     * only a session id. Routing those to `broadcast` alone dropped them
     * whenever no workspace-level listener existed — and none ever does: the
     * turn collector registers per-run and nothing in the app calls
     * [registerAll]. The consequence was the whole D12 class of symptoms: the
     * engine published the ask and wrote it to the socket (instrumented-engine
     * breadcrumbs `PW permissionsvc publish` → `PW sse recv` all fired, session
     * filter passing), yet the collector for the in-flight turn never saw it,
     * so the ask card never rendered and the turn blocked until the 3-minute
     * stream timeout. Streaming text was the same casualty: message frames
     * without a run id never reached the per-run channel, so the assistant
     * bubble stayed "thinking" for the whole turn and only the final
     * `run_complete` text ever painted.
     *
     * Fanning anonymous events to every registered run is safe:
     *  - a registered run IS an in-flight turn (register happens before the
     *    prompt, completeRun/unregister ends it), so at most one live turn "owns"
     *    the stream window the engine is describing; two can overlap only in the
     *    one-retry window, and the bounded channels already tolerate surplus
     *    frames with drop-oldest;
     *  - the engine already scopes what it emits to the client's just-reported
     *    current session (deliverToClient), so the anonymous frames arriving on
     *    this socket belong to the session the registered runs are about;
     *  - a collector that is not the event's turn merely ignores it: the
     *    MESSAGE branch repaints only its OWN bubble id, and
     *    PERMISSION_REQUEST/QUESTION branches are idempotent state writes.
     */
    fun dispatch(event: CrushEvent) {
        val targets = mutableListOf<Channel<CrushEvent>>()
        synchronized(lock) {
            if (closed) return
            val runId = event.runIdOrNull()
            if (runId != null) {
                byRun[runId]?.let { targets += it }
            } else {
                // Anonymous frame: no run anchor to filter by, so every live
                // turn sees it (D12 — see the doc comment above).
                byRun.values.forEach { targets += it }
            }
            targets += broadcast
        }
        // Outside the lock: a channel send must never hold the router's monitor.
        targets.forEach {
            val res = it.trySend(event)
            // P1-15: trySend results used to vanish. Only FAILURES are logged,
            // never successes - this runs per frame per target. A failure here
            // is almost always the close-vs-send race (completeRun ended the
            // run between target selection above and this send), which is not
            // a hard signal: a DROP_OLDEST channel cannot fail from fullness,
            // so failure means the channel closed underneath us. That USED to
            // be an invisible frame loss; now it leaves one honest line.
            if (res.isFailure) {
                AppLog.w(
                    TAG,
                    "dispatch: dropped ${event.type.wire} frame - channel closed " +
                        "(${res.exceptionOrNull()?.message ?: "closed channel"})"
                )
            }
        }
    }

    /** How many channels are waiting on [runId]. Used by tests. */
    fun subscriberCount(runId: String): Int =
        synchronized(lock) { byRun[runId]?.size ?: 0 }

    fun hasSubscribers(): Boolean =
        synchronized(lock) { byRun.isNotEmpty() || broadcast.isNotEmpty() }

    /** Ends the turn for [runId] without tearing down the connection. */
    fun completeRun(runId: String) {
        // Snapshot INSIDE the lock, close OUTSIDE it - the same rule dispatch
        // obeys: channel lifecycle calls must not hold the router's monitor.
        // The old body closed the same channels TWICE (once inside, once
        // outside): harmless per se, since close() is idempotent, but it armed
        // the close-vs-send race for no reason and made it ambiguous which
        // close a failure log referred to.
        val targets = synchronized(lock) {
            byRun.remove(runId).orEmpty()
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

/**
 * The only member keys that name a routing run id, in tolerance order: the
 * wire is snake_case; the camelCase forms cover hand-built events.
 */
private val RUN_ID_KEYS = listOf("run_id", "runID", "runId")

/**
 * Bounded run-id search (P1-5 - the previous version RECURSED through the
 * entire body: arrays, tool params, everything, contradicting its own
 * "one level" comment and occasionally binding a frame to a run that had
 * nothing to do with it).
 *
 * Candidate objects, exactly and only:
 *
 *  (a) the body object's own keys, and
 *  (b) the wire envelope hops: the direct child named `payload`, and ITS
 *      child named `payload`. That is the whole range a run id legitimately
 *      occupies: production decode strips both wraps, so an engine-echoed
 *      run id (run_complete, agent_event) lands at (a); tests and
 *      hand-built events may carry the full envelope, in which case it sits
 *      on the `payload` chain at (b). Two wraps is the cap because a
 *      well-formed frame has exactly that much envelope.
 *
 * NOTHING else is visited, deliberately:
 *
 *  - A `run_id` inside a tool call's params is tool ARGUMENTS, not event
 *    routing. `params` is a plain child of the body (any other name than
 *    `payload`), so the search stops before ever looking inside it. The
 *    recursive version bound those frames to whatever run id happened to
 *    appear in the arguments - e.g. a permission request for a tool whose
 *    params echoed an older run's id would be delivered to THAT run,
 *    crossing turns.
 *  - Array elements (parts, spans, message lists) are per-entry structures
 *    with ids of their own; descending into them re-introduced the same
 *    foreign bind. Legit run ids live on objects in the two places above,
 *    which is why arrays were simply never on the search path.
 *  - A run_id reached through two PLAIN object levels (a.b.run_id) is not
 *    an envelope hop and is equally unreached; if the engine ever grows a
 *    shape that legitimately nests there, name the key explicitly here
 *    rather than recursing again.
 */
private fun findRunId(el: com.google.gson.JsonElement?): String? {
    if (el == null || el.isJsonNull || !el.isJsonObject) return null
    val body = el.asJsonObject
    val inner = body.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
    val innermost = inner?.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
    for (candidate in listOfNotNull(body, inner, innermost)) {
        for (key in RUN_ID_KEYS) {
            candidate.get(key)
                ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
    }
    return null
}
