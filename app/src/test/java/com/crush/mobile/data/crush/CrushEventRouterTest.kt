package com.crush.mobile.data.crush

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

    // ---- P1-5: the search is BOUNDED and must not over-match ---------------

    @Test
    fun `a foreign run id in tool params or two plain levels deep does not bind`() {
        // A run id inside a tool call's params is tool ARGUMENTS, not event
        // routing. `params` is a plain child of the body - not an envelope
        // `payload` hop - so the search must stop before it. The recursive
        // version bound such frames to a run whose id merely appeared in the
        // arguments, delivering e.g. a permission ask to the WRONG run
        // mid-turn.
        val params = ev(
            """{"id":"p1","session_id":"s1","tool_name":"write","params":{"run_id":"foreign"}}"""
        )
        assertEquals(null, params.runIdOrNull())

        // Same for a run id reached via two PLAIN object levels. Only the
        // wire's `payload` hops are followed, so `outer.inner.run_id` is out
        // of reach on purpose - see the doc comment on findRunId.
        val twoLevels = ev("""{"outer":{"inner":{"run_id":"foreign"}}}""")
        assertEquals(null, twoLevels.runIdOrNull())
    }

    @Test
    fun `a run id inside an array element does not bind`() {
        // Parts/spans hold per-entry structures with ids of their own; the
        // recursive version descended arrays and re-introduced the foreign
        // bind through them. The reachable places are body top level and the
        // `payload` chain - arrays are never on that path.
        val e = ev("""{"role":"assistant","parts":[{"type":"text","run_id":"foreign"}]}""")
        assertEquals(null, e.runIdOrNull())

        // An array inside the envelope is equally out of reach.
        val nested = ev(
            """{"payload":{"type":"updated","payload":{"parts":[{"run_id":"foreign"}]}}}"""
        )
        assertEquals(null, nested.runIdOrNull())
    }

    @Test
    fun `a frame whose only run id hides in tool params fans out anonymously`() = runBlocking {
        // The routing consequence of the bounded search: an event whose ONLY
        // run_id sits inside tool params cannot be bound to that run, so it
        // routes as anonymous - every live turn sees it and each collector
        // filters by its own turn downstream (same rules as D12 fan-out). If
        // the run id WERE bound, run-1 would receive nothing at all.
        val r = CrushEventRouter()
        val one = r.register("run-1")
        val decoy = r.register("run-foreign")
        r.dispatch(ev("""{"session_id":"s1","tool_name":"write","params":{"run_id":"run-foreign"}}"""))
        // The discriminator is run-1: fan-out delivers to every live run, so
        // it gets the frame ONLY if routing treated the event as anonymous.
        // An over-match would deliver to run-foreign alone and starve run-1.
        assertTrue("run-1 lost an anonymous frame", one.tryReceive().getOrNull() != null)
        assertTrue("fan-out must have reached run-foreign too", decoy.tryReceive().getOrNull() != null)
        r.close()
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

    // ---- D12: anonymous (run-id-less) events must reach live runs ---------

    @Test
    fun `a run-id-less permission request reaches the in-flight turn`() = runBlocking {
        val r = CrushEventRouter()
        val ch = r.register("run-1")
        // Real permission frame shape: no run_id anywhere in the body, and the
        // engine sends it DURING the registered run (nothing else registered).
        val e = CrushEvent(
            CrushEvent.PayloadType.PERMISSION_REQUEST,
            CrushEvent.ChangeType.CREATED,
            JsonParser.parseString(
                """{"id":"perm-1","session_id":"s1","tool_call_id":"tc1","tool_name":"write","action":"write","params":{}}"""
            ).asJsonObject
        )
        r.dispatch(e)
        assertTrue("ask card could never render (D12)", ch.tryReceive().getOrNull() != null)
        r.close()
    }

    @Test
    fun `a run-id-less message frame reaches the in-flight turn`() = runBlocking {
        val r = CrushEventRouter()
        val ch = r.register("run-1")
        // proto.Message carries only a session id - every streaming frame is
        // anonymous, which is why streaming text stayed "thinking" until the
        // final run_complete.
        r.dispatch(ev("""{"id":"m1","role":"assistant","session_id":"s1","parts":[]}"""))
        assertTrue("streaming text was dropped (D12)", ch.tryReceive().getOrNull() != null)
        r.close()
    }

    @Test
    fun `anonymous events reach every live run, scoped ones stay scoped`() = runBlocking {
        val r = CrushEventRouter()
        val one = r.register("run-1")
        val two = r.register("run-2")
        // Anonymous frame: both live turns see it (a collector that is not the
        // event's turn ignores it downstream).
        r.dispatch(ev("""{"id":"m1","role":"assistant","session_id":"s1","parts":[]}"""))
        assertTrue(one.tryReceive().getOrNull() != null)
        assertTrue(two.tryReceive().getOrNull() != null)
        // Scoped frame (run_complete echoes the caller's run id): unchanged.
        r.dispatch(ev("""{"run_id":"run-2","text":"done"}"""))
        assertTrue(two.tryReceive().getOrNull()?.body?.optString("text") == "done")
        assertTrue("run-scoped event crossed runs", one.tryReceive().getOrNull() == null)
        r.close()
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
