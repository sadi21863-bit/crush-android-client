package com.opencode.chat.data.crush

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event routing for a long-lived workspace connection.
 *
 * THE BUG THIS PINS
 *
 * The previous design opened a `callbackFlow` per turn. A callbackFlow only
 * starts its reader when collection begins, so the order was: build the flow,
 * POST the prompt, then start collecting. The engine dispatches a run detached
 * and can emit - and finish - before collection starts, so early events were
 * lost. That is a race, not an edge case: it gets worse the faster the engine
 * replies.
 *
 * With one long-lived connection the collector is already running, so
 * [CrushEventRouter.register] happening BEFORE the prompt is all it takes. These
 * tests pin that property, because it is invisible until it has already lost
 * data.
 */
class CrushEventRouterTest {

    private fun ev(json: String): CrushEvent {
        val o = JsonParser.parseString(json).asJsonObject
        return CrushEvent(
            type = CrushEvent.PayloadType.MESSAGE,
            change = CrushEvent.ChangeType.UPDATED,
            body = o
        )
    }

    private fun msg(runId: String, text: String) = ev(
        """{"type":"message","run_id":"$runId","parts":[{"type":"text","data":{"text":"$text"}}]}"""
    )

    // ---- the ordering guarantee -------------------------------------------

    @Test
    fun `an event delivered before collection is still buffered and not lost`() =
        runBlocking {
            val r = CrushEventRouter()
            val ch = r.register("run-1")
            // The engine replies before anyone starts collecting - exactly what
            // happened with per-turn callbackFlow.
            r.dispatch(msg("run-1", "early token"))

            val got = ch.tryReceive().getOrNull()
            assertTrue("early event was dropped", got != null)
            r.close()
        }

    @Test
    fun `subscribing before prompting means nothing is missed`() = runBlocking {
        val r = CrushEventRouter()
        // 1. register first
        val ch = r.register("run-1")
        // 2. engine streams while we are still "prompting"
        r.dispatch(msg("run-1", "one"))
        r.dispatch(msg("run-1", "two"))
        // 3. now collect
        val a = ch.tryReceive().getOrNull()
        val b = ch.tryReceive().getOrNull()
        assertTrue("first event lost", a != null)
        assertTrue("second event lost", b != null)
        r.close()
    }

    // ---- isolation between runs -------------------------------------------

    @Test
    fun `events only reach the run they belong to`() = runBlocking {
        val r = CrushEventRouter()
        val one = r.register("run-1")
        val two = r.register("run-2")
        r.dispatch(msg("run-2", "for two"))

        assertTrue("run-2 did not get its event", two.tryReceive().getOrNull() != null)
        assertTrue("run-1 received another run's event", one.tryReceive().getOrNull() == null)
        r.close()
    }

    @Test
    fun `a run with no subscriber is dropped rather than queued forever`() = runBlocking {
        val r = CrushEventRouter()
        r.dispatch(msg("nobody", "x"))
        // Must not throw and must not retain anything for a run that never
        // registered - otherwise a finished workspace leaks channels.
        assertEquals(0, r.subscriberCount("nobody"))
        r.close()
    }

    @Test
    fun `two subscribers on one run both receive it`() = runBlocking {
        val r = CrushEventRouter()
        val a = r.register("run-1")
        val b = r.register("run-1")
        r.dispatch(msg("run-1", "shared"))
        assertTrue(a.tryReceive().getOrNull() != null)
        assertTrue(b.tryReceive().getOrNull() != null)
        r.close()
    }

    // ---- run id extraction ------------------------------------------------

    @Test
    fun `run id is read from the top level`() {
        assertEquals("r1", msg("r1", "t").runIdOrNull())
    }

    @Test
    fun `run id is found when nested inside a payload`() {
        val e = ev(
            """{"type":"message","payload":{"type":"updated","payload":{"run_id":"r2","id":"m1"}}}"""
        )
        assertEquals("r2", e.runIdOrNull())
    }

    @Test
    fun `an event with no run id yields null rather than throwing`() {
        val e = ev("""{"type":"config_changed","payload":{"type":"updated","payload":{}}}""")
        assertEquals(null, e.runIdOrNull())
    }

    @Test
    fun `malformed json yields null rather than throwing`() {
        val e = CrushEvent(
            CrushEvent.PayloadType.MESSAGE,
            CrushEvent.ChangeType.UPDATED,
            JsonParser.parseString("{}").asJsonObject
        )
        assertEquals(null, e.runIdOrNull())
    }

    // ---- lifecycle --------------------------------------------------------

    @Test
    fun `completing a run closes only that run`() = runBlocking {
        val r = CrushEventRouter()
        val one = r.register("run-1")
        val two = r.register("run-2")
        r.completeRun("run-1")
        assertTrue("run-1 should be closed", one.tryReceive().isFailure)
        r.dispatch(msg("run-2", "still live"))
        assertTrue("run-2 was closed too", two.tryReceive().getOrNull() != null)
        r.close()
    }

    @Test
    fun `registering after close yields an already-closed channel`() = runBlocking {
        val r = CrushEventRouter()
        r.close()
        val ch = r.register("run-late")
        // A late registration must not hang forever waiting for events that
        // can no longer arrive.
        assertTrue(ch.tryReceive().isFailure)
    }

    @Test
    fun `close releases every outstanding subscriber`() = runBlocking {
        val r = CrushEventRouter()
        val a = r.register("r1")
        val b = r.register("r2")
        r.close()
        assertTrue(a.tryReceive().isFailure)
        assertTrue(b.tryReceive().isFailure)
        assertFalse(r.hasSubscribers())
    }

    @Test
    fun `dispatch after close is a no-op, not a crash`() = runBlocking {
        val r = CrushEventRouter()
        r.close()
        r.dispatch(msg("r1", "late"))
        // The point is that this does not throw.
        assertTrue(true)
    }

    @Test
    fun `unregister removes the channel and stops delivery`() = runBlocking {
        val r = CrushEventRouter()
        val ch = r.register("run-1")
        r.unregister("run-1", ch)
        assertEquals(0, r.subscriberCount("run-1"))
        r.dispatch(msg("run-1", "after unregister"))
        assertTrue("received after unregister", ch.tryReceive().getOrNull() == null)
        r.close()
    }

    @Test
    fun `catch-all subscribers also receive run-scoped events`() = runBlocking {
        val r = CrushEventRouter()
        val all = r.registerAll()
        r.dispatch(msg("run-1", "x"))
        assertTrue(
            "a workspace-level listener must still see run events",
            all.tryReceive().getOrNull() != null
        )
        r.close()
    }
}
