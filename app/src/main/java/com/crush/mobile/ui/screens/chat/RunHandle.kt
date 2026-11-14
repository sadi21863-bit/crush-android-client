package com.crush.mobile.ui.screens.chat

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

/**
 * One user turn's stop identity (audit P1-05).
 *
 * WHY THIS EXISTS
 *
 * Stop used to read the view model's mutable fields - the stream job, wsId,
 * sessionId and port - at TAP time. During preflight (re-attach plus
 * newSession: two network round-trips) those fields still described the
 * PREVIOUS turn, so Stop cancelled a job that had already finished, asked the
 * engine to cancel the previous session, and then the send path posted the new
 * prompt anyway. The user pressed Stop and the agent ran regardless.
 *
 * A handle is created the instant send() claims the turn (before the claim
 * there is no run to stop), and it is the ONLY thing Stop reads. [stop] then
 * answers once, from this handle's own state, what cancellation needs:
 *
 *  - During preflight no target exists yet, so [StopAction.NothingPosted] comes
 *    back - and [mayPost] (checked on the way to the POST) keeps it that way.
 *    This is the one window where suppressing the POST is the only way to stop
 *    a run whose cancel request would otherwise arrive before the run does.
 *  - Once the preflight freezes a target, [stop] hands out exactly that
 *    ws/session/port to cancel - even if the view model has since moved on to
 *    another workspace attach, another session, or another turn.
 *
 * Cancellation of the local jobs happens inside [stop], so a caller cannot
 * forget it; the caller only decides what to do about the server side.
 */
internal class RunHandle(val turnId: String) {

    private val stoppedFlag = AtomicBoolean(false)

    /**
     * The preflight coroutine (re-attach, session, target capture).
     *
     * Assigned BEFORE the coroutine is started - the caller uses a LAZY start -
     * because a main-dispatcher launch runs eagerly to its first suspension:
     * with a plain launch{} there would be a window where the body is already
     * suspended in a network call but the job is not yet recorded, and Stop in
     * that window would cancel nothing at all.
     */
    @Volatile
    var preflightJob: Job? = null

    /** The streaming coroutine; recorded before start, for the same reason. */
    @Volatile
    var streamJob: Job? = null

    /** Where this turn will POST. Frozen by the preflight, never re-read. */
    @Volatile
    var port: Int? = null

    /** Workspace id this turn posts to. Frozen with [port]. */
    @Volatile
    var ws: String? = null

    /** Session id this turn posts to. Frozen with [port]. */
    @Volatile
    var sid: String? = null

    /**
     * The single assistant bubble this turn owns.
     *
     * A turn finalises ONLY its own bubble. The previous finaliser cleared the
     * streaming flag on every streaming message, so a stop landing late could
     * finish a newer turn's bubble it knew nothing about.
     */
    @Volatile
    var bubbleId: String? = null

    /**
     * Tool rows accumulated across EVERY assistant message of this turn.
     *
     * The engine starts a new assistant message for each LLM step, so the
     * view model's old "replace tools with this frame's list" behaviour erased
     * step 1's rows as soon as step 2 called its first tool. Keyed by the
     * engine's tool-call id and insertion-ordered, so a republish updates the
     * existing row and first-seen order is preserved. Guarded by its own lock:
     * the stream coroutine is the only writer, but the snapshot can be read
     * while finalising.
     */
    private val observedTools = java.util.LinkedHashMap<String, ToolActivity>()

    /** How many extra idle windows a busy engine earned this turn. */
    @Volatile
    var idleExtensions: Int = 0

    /**
     * True when this turn's stream showed a detached sub-agent dispatch
     * (`agent_dispatch` / `agent_send`, engine v0.100.0).
     *
     * The sub-agent runs OUTSIDE this turn's run: its report and the primary
     * agent's wake reply are persisted only AFTER the dispatching run's stream
     * has closed (measured on device 2026-11-14: dispatch at :08, report at
     * :14, parent reply at :38). No run is registered by then, so the live
     * event router has nowhere to deliver those frames. The flag tells the
     * view model a bounded post-turn history refresh is worth scheduling -
     * every other turn would only re-read state that is already on screen.
     */
    @Volatile
    var dispatchedSubAgent: Boolean = false

    val isStopped: Boolean get() = stoppedFlag.get()

    /**
     * Merges one message frame's tool rows into this turn's accumulated list.
     *
     * Returns the full ordered list plus only the ids seen for the first time,
     * which is what the per-call rail log uses (a shape log is not a call log:
     * a second `bash` call must still be visible).
     */
    @Synchronized
    fun mergeTools(
        incoming: List<ToolActivity>,
        nowMs: Long = System.currentTimeMillis()
    ): ToolMerge {
        val added = mutableListOf<ToolActivity>()
        incoming.forEach { fresh ->
            val prev = observedTools[fresh.id]
            val merged = if (prev == null) {
                added += fresh
                fresh.copy(
                    firstSeenAt = nowMs,
                    // A live tool-call frame is PART-final at best (class
                    // doc: `finished` lands before execution). Execution is
                    // proven only by a later tool_result, so a fresh row
                    // starts RUNNING unless the frame carries a failure.
                    state = if (fresh.state == ToolState.FAILED) {
                        ToolState.FAILED
                    } else {
                        ToolState.RUNNING
                    },
                    finishedAt = 0L,
                    durationMs = 0L
                )
            } else if (prev.state != ToolState.RUNNING && prev.state != ToolState.PENDING) {
                // A row completed by completeTool (or settled at run end)
                // must never be downgraded by a late/duplicate republish.
                prev.copy(output = prev.output.ifBlank { fresh.output })
            } else {
                fresh.copy(
                    firstSeenAt = prev.firstSeenAt,
                    state = if (fresh.state == ToolState.FAILED) {
                        ToolState.FAILED
                    } else {
                        ToolState.RUNNING
                    },
                    finishedAt = prev.finishedAt,
                    durationMs = prev.durationMs
                )
            }
            observedTools[fresh.id] = merged
        }
        return ToolMerge(observedTools.values.toList(), added)
    }

    /**
     * Marks a call complete from its engine `tool_result` message.
     *
     * This is the only live completion signal that means the tool actually
     * ran: the assistant message's `finished` flag is PART-final (measured
     * 2026-11-14 - a `sleep 8` call "finished" in 274 ms, then executed
     * 2m20s later). [durationMs] is the engine's own start/end metadata and
     * is shown only when it is present. Returns false when the call id is
     * unknown (the result raced ahead of the call frame).
     */
    @Synchronized
    fun completeTool(
        callId: String,
        failed: Boolean,
        durationMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prev = observedTools[callId] ?: return false
        observedTools[callId] = prev.copy(
            state = if (failed) ToolState.FAILED else ToolState.COMPLETED,
            finishedAt = if (prev.finishedAt > 0L) prev.finishedAt else nowMs,
            durationMs = if (durationMs > 0L) durationMs else prev.durationMs
        )
        return true
    }

    /** The turn's rows in first-seen order, for publishing to the bubble. */
    @Synchronized
    fun snapshotTools(): List<ToolActivity> = observedTools.values.toList()

    /**
     * True while this turn is still allowed to reach the engine.
     *
     * Checked before the POST. A turn stopped during preflight must never reach
     * the server: asking the engine to cancel a session whose run was never
     * posted accomplishes nothing, so the POST is what has to be prevented.
     */
    fun mayPost(): Boolean = !stoppedFlag.get()

    /** Records where this turn resolved to, immediately before its POST. */
    fun freezeTarget(ws: String, sid: String, port: Int) {
        this.ws = ws
        this.sid = sid
        this.port = port
    }

    /**
     * Claims the stop exactly once and answers what cancellation needs.
     *
     * First caller wins: an impatient double-tap gets [StopAction.AlreadyStopped]
     * and must not fire a second server cancel (two cancel requests for one run
     * are harmless to the engine but the second would arrive after a newer run
     * started on the same session - which is exactly the ambiguity this whole
     * class exists to remove).
     *
     * The jobs are cancelled here, before the action is returned, so both the
     * preflight window (cancel the preflight - it has not launched the stream
     * yet) and the streaming window (cancel the stream) are covered by the same
     * call.
     */
    fun stop(): StopAction {
        if (!stoppedFlag.compareAndSet(false, true)) return StopAction.AlreadyStopped
        preflightJob?.cancel()
        streamJob?.cancel()
        // Snapshot at STOP time. If the preflight had not frozen a target yet,
        // nothing reached the server - and mayPost() now guarantees nothing
        // will, so local cancellation is the complete answer.
        val p = port ?: return StopAction.NothingPosted
        val w = ws ?: return StopAction.NothingPosted
        val s = sid ?: return StopAction.NothingPosted
        return StopAction.CancelOnServer(w, s, p)
    }
}

/** Result of [RunHandle.mergeTools]: the full turn list plus newly seen rows. */
internal data class ToolMerge(
    val tools: List<ToolActivity>,
    val added: List<ToolActivity>
)

/** What [RunHandle.stop] says the caller must do next. */
internal sealed class StopAction {

    /**
     * The run never reached the engine; local cancellation is already complete.
     * This is the preflight window: no bubble exists and no POST was sent.
     */
    object NothingPosted : StopAction()

    /**
     * Ask the engine to cancel this exact run, then release workActive. The
     * target came from the handle, so a stop issued while the view model is
     * already preparing a later turn still cancels the run the user saw.
     */
    data class CancelOnServer(val ws: String, val sid: String, val port: Int) : StopAction()

    /** This turn was already stopped; do nothing. */
    object AlreadyStopped : StopAction()
}
