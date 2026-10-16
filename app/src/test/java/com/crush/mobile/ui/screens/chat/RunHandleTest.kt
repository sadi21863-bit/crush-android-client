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
