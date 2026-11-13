package com.crush.mobile.ui.screens.chat

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped bug this file exists to prevent (audit P1-05):
 *
 * Stop used to read the view model's MUTABLE fields - the stream job, wsId,
 * sessionId and port - at TAP time. During preflight (re-attach plus
 * newSession, two network round-trips) those fields still described the
 * PREVIOUS turn, so a stop in that window cancelled a job that had already
 * finished, asked the engine to cancel the previous session, and then the
 * send path POSTED the new prompt anyway. The user pressed Stop and the agent
 * ran regardless.
 *
 * The fix binds stop identity to a per-turn RunHandle whose decision is made
 * ONCE, from the handle's own state. These tests pin the parts of that
 * decision a regression would break:
 *
 *  1. a stop before the target is frozen must suppress the POST, not fire a
 *     cancel at a run that does not exist;
 *  2. once a target IS frozen, the cancel must name exactly that target, not
 *     whatever the view model points at later;
 *  3. a stop is claimed once - a second Stop must not cancel anything,
 *     including jobs recorded after the first stop.
 */
class RunHandleTest {

    @Test
    fun `a fresh turn may post`() {
        val handle = RunHandle(turnId = "t1")
        assertTrue("a claimed, unstopped turn must be allowed to POST", handle.mayPost())
        assertFalse(handle.isStopped)
        assertEquals("t1", handle.turnId)
    }

    @Test
    fun `stop before the target is frozen forbids the POST and reports nothing posted`() {
        // This is the exact window the bug lived in: the user taps Stop while
        // the preflight is still attaching the workspace. Nothing has reached
        // the engine, so the complete answer is a suppressed POST - the old
        // code instead read the PREVIOUS turn's session id and asked the
        // server to cancel that.
        val handle = RunHandle(turnId = "t1")
        val action = handle.stop()
        assertEquals(StopAction.NothingPosted, action)
        assertFalse("a stopped turn must never POST", handle.mayPost())
    }

    @Test
    fun `stop cancels the preflight and stream jobs it was given`() {
        val handle = RunHandle(turnId = "t1")
        val preflight = Job()
        val stream = Job()
        handle.preflightJob = preflight
        handle.streamJob = stream
        handle.stop()
        assertTrue("preflight must be cancelled by stop", preflight.isCancelled)
        assertTrue("stream must be cancelled by stop", stream.isCancelled)
    }

    @Test
    fun `the cancel action names the frozen target`() {
        // Once the preflight has frozen where this turn will POST, a stop must
        // address exactly that ws/session/port - even if the view model has
        // since re-attached to another workspace or a newer turn has begun.
        val handle = RunHandle(turnId = "t1")
        handle.freezeTarget(ws = "ws-abc", sid = "sess-9", port = 43123)
        assertEquals(
            StopAction.CancelOnServer(ws = "ws-abc", sid = "sess-9", port = 43123),
            handle.stop()
        )
    }

    @Test
    fun `a second stop does nothing - including to jobs recorded after the first stop`() {
        // An impatient double-tap must not fire a second server-side cancel.
        // Two cancel requests for one run are harmless to the engine but the
        // second arrives after a newer run may have started on the same
        // session - exactly the ambiguity the handle exists to remove.
        val handle = RunHandle(turnId = "t1")
        val firstStream = Job()
        handle.streamJob = firstStream
        handle.stop()

        val lateJob = Job()
        handle.preflightJob = lateJob

        assertEquals(StopAction.AlreadyStopped, handle.stop())
        assertFalse("the second stop must not cancel anything", lateJob.isCancelled)
        assertTrue(firstStream.isCancelled)
    }

    @Test
    fun `mergeTools keeps earlier steps and stamps only the first sighting`() {
        // The engine starts a new assistant message per LLM step. The old
        // view-model code replaced the turn's tool list with each frame's, so
        // step 2's first tool erased step 1's rows. Rows now accumulate by
        // call id, in first-seen order.
        val handle = RunHandle(turnId = "t1")
        val bashRunning = ToolActivity(
            id = "call-1", name = "bash", args = "{}", state = ToolState.RUNNING, output = ""
        )
        val first = handle.mergeTools(listOf(bashRunning), nowMs = 1_000L)
        assertEquals(1, first.tools.size)
        assertEquals(1, first.added.size)
        assertEquals(1_000L, first.tools[0].firstSeenAt)
        assertEquals(0L, first.tools[0].finishedAt)

        val bashDone = bashRunning.copy(state = ToolState.COMPLETED, output = "ok")
        val second = handle.mergeTools(listOf(bashDone), nowMs = 2_500L)
        assertEquals("the same call must update, not duplicate", 1, second.tools.size)
        assertEquals(0, second.added.size)
        assertEquals(ToolState.COMPLETED, second.tools[0].state)
        assertEquals(1_000L, second.tools[0].firstSeenAt)
        assertEquals(2_500L, second.tools[0].finishedAt)

        val read = ToolActivity(
            id = "call-2", name = "read", args = "{}", state = ToolState.RUNNING, output = ""
        )
        val third = handle.mergeTools(listOf(read), nowMs = 3_000L)
        assertEquals(listOf("call-1", "call-2"), third.tools.map { it.id })
        assertEquals(2, third.tools.size)
        assertEquals(1, third.added.size)
    }

    @Test
    fun `a late republish cannot downgrade a completed tool row`() {
        val handle = RunHandle(turnId = "t1")
        val done = ToolActivity(
            id = "call-1", name = "bash", args = "{}",
            state = ToolState.COMPLETED, output = "ok"
        )
        handle.mergeTools(listOf(done), nowMs = 1_000L)
        val runningAgain = done.copy(state = ToolState.RUNNING, output = "")
        val merged = handle.mergeTools(listOf(runningAgain), nowMs = 2_000L)
        assertEquals(ToolState.COMPLETED, merged.tools[0].state)
        assertEquals("ok", merged.tools[0].output)
        assertEquals(1_000L, merged.tools[0].finishedAt)
    }

    @Test
    fun `idle extensions start at zero`() {
        assertEquals(0, RunHandle(turnId = "t1").idleExtensions)
    }

    @Test
    fun `a target frozen after the stop cannot resurrect the turn`() {
        // The regression this pins: stop claims the turn during preflight
        // (NothingPosted), the preflight completes anyway and freezes a target
        // late. mayPost() must still be false so the POST is suppressed, and a
        // second stop must not re-open the decision.
        val handle = RunHandle(turnId = "t1")
        assertEquals(StopAction.NothingPosted, handle.stop())
        handle.freezeTarget(ws = "ws-late", sid = "sess-late", port = 1)
        assertFalse(
            "a turn stopped before its POST must stay forbidden from POSTing",
            handle.mayPost()
        )
        assertEquals(StopAction.AlreadyStopped, handle.stop())
    }
}
