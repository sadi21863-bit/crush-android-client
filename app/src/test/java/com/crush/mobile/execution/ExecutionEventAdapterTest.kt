package com.crush.mobile.execution

import com.crush.mobile.data.crush.CrushConnection
import com.crush.mobile.data.crush.CrushEvent
import com.crush.mobile.data.crush.CrushEventRouter
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1 adapter tests: provisional ownership, buffering, replay and rollback. */
class ExecutionEventAdapterTest {

    private fun event(
        type: ExecutionEvent.Type = ExecutionEvent.Type.MESSAGE,
        attribution: Attribution = Attribution.CONFIRMED,
        detail: String = "x",
    ) = ExecutionEvent(type = type, attribution = attribution, detail = detail)

    @Test
    fun commitReplaysBufferedEventsWithAttributionUnchanged() {
        var epoch = 1L
        val adapter = ExecutionEventAdapter(epochOf = { epoch })
        val token = adapter.begin("pA")
        adapter.declare("pA", "run-1", 7L, "e-1")
        adapter.onEvent("pA", event(attribution = Attribution.CONFIRMED, detail = "confirmed"))
        adapter.onEvent("pA", event(attribution = Attribution.PLAUSIBLE, detail = "plausible"))
        adapter.onEvent("pA", event(attribution = Attribution.UNATTRIBUTED, detail = "none"))

        val ch = adapter.register("pA", "run-1")
        val result = adapter.commit("pA", token)

        assertTrue(result is CommitResult.Committed)
        val received = listOf(ch.tryReceive().getOrNull(), ch.tryReceive().getOrNull(), ch.tryReceive().getOrNull())
        assertEquals(listOf("confirmed", "plausible", "none"), received.map { it?.detail })
        assertEquals(
            listOf(Attribution.CONFIRMED, Attribution.PLAUSIBLE, Attribution.UNATTRIBUTED),
            received.map { it!!.attribution },
        )
    }

    @Test
    fun invalidatedEpochRejectsCommitAndRollbackReturnsBuffer() {
        var epoch = 1L
        val adapter = ExecutionEventAdapter(epochOf = { epoch })
        val token = adapter.begin("pA")
        adapter.onEvent("pA", event(detail = "only-terminal-evidence"))
        epoch = 2L // invalidation

        assertTrue(adapter.commit("pA", token) is CommitResult.Rejected)
        val rolledBack = adapter.rollback("pA", token)
        assertEquals(listOf("only-terminal-evidence"), rolledBack.map { it.detail })
        assertFalse(adapter.commit("pA", token) is CommitResult.Committed)
    }

    @Test
    fun staleTokenCannotCommitOrRollbackAnotherTransaction() {
        var epoch = 1L
        val adapter = ExecutionEventAdapter(epochOf = { epoch })
        val first = adapter.begin("pA")
        val second = adapter.begin("pA")
        adapter.onEvent("pA", event(detail = "second"))

        assertTrue(adapter.commit("pA", first) is CommitResult.Rejected)
        assertTrue(adapter.rollback("pA", first).isEmpty())
        assertTrue(adapter.commit("pA", second) is CommitResult.Committed)
    }

    @Test
    fun bufferOverflowRejectsCommitAndDoesNotPublish() {
        var epoch = 1L
        val adapter = ExecutionEventAdapter(epochOf = { epoch }, maxBufferedEvents = 2)
        val token = adapter.begin("pA")
        val ch = adapter.register("pA", "run-1")
        adapter.onEvent("pA", event(detail = "a"))
        adapter.onEvent("pA", event(detail = "b"))
        assertFalse(adapter.onEvent("pA", event(detail = "c")))

        assertTrue(adapter.commit("pA", token) is CommitResult.Rejected)
        assertFalse(ch.tryReceive().getOrNull() != null)
    }

    @Test
    fun eventsAfterCommitGoStraightToSubscribers() {
        var epoch = 1L
        val adapter = ExecutionEventAdapter(epochOf = { epoch })
        val token = adapter.begin("pA")
        val ch = adapter.register("pA", "run-1")
        assertTrue(adapter.commit("pA", token) is CommitResult.Committed)

        adapter.onEvent("pA", event(detail = "live"))
        assertEquals("live", ch.tryReceive().getOrNull()?.detail)
    }

    private class FakePort : EventConnectionPort {
        override val state: StateFlow<CrushConnection.State> =
            MutableStateFlow(CrushConnection.State.CONNECTED)
        var attached = 0
            private set
        var stopped = false
            private set

        override fun ensureAttached(workspaceId: String, clientId: String) { attached++ }
        override fun stop() { stopped = true }
    }

    @Test
    fun realRouterFramesReachAdapterWithNoScreenObserver() = runTest {
        val epoch = 1L
        val router = CrushEventRouter()
        val port = FakePort()
        val adapter = ExecutionEventAdapter(
            epochOf = { epoch },
            newRouter = { router },
            newConnection = { _, _, _, _ -> port },
            scope = this,
        )
        adapter.declare("pA", "run-1", 7L, "e-1")
        val stream = adapter.register("pA", "run-1")
        adapter.attach(
            key = "pA",
            workspaceId = "ws-1",
            clientId = "cid",
            port = 1,
            socketFile = java.io.File("crush-pA.sock"),
            ownerEpoch = 1L,
            runInFlightOnKey = { false },
            clientSource = { null },
        )

        val body = JsonObject().apply { addProperty("run_id", "run-1") }
        router.dispatch(
            CrushEvent(
                type = CrushEvent.PayloadType.RUN_COMPLETE,
                change = CrushEvent.ChangeType.UPDATED,
                body = body,
            )
        )

        val received = kotlinx.coroutines.withTimeout(1_000L) { stream.receive() }
        assertEquals(Attribution.CONFIRMED, received.attribution)
        assertEquals(ExecutionEvent.Type.RUN_COMPLETE, received.type)
        assertEquals(1, port.attached)

        adapter.close("pA")
        assertTrue(port.stopped)
        assertTrue(stream.isClosedForReceive)
    }

    @Test
    fun repeatedAttachIsIdempotentForTheSameSocketIdentity() = runTest {
        val creations = AtomicInteger()
        val port = FakePort()
        val adapter = ExecutionEventAdapter(
            epochOf = { 1L },
            newConnection = { _, _, _, _ -> creations.incrementAndGet(); port },
            scope = this,
        )
        fun attach() = adapter.attach(
            key = "pA", workspaceId = "ws", clientId = "cid", port = 1,
            socketFile = java.io.File("crush-pA.sock"), ownerEpoch = 1L,
            runInFlightOnKey = { false }, clientSource = { null },
        )
        attach(); attach(); attach()
        assertEquals(1, creations.get())
        assertEquals(3, port.attached)
        adapter.close("pA")
    }

    @Test
    fun concurrentAttachCreatesExactlyOneReader() = runTest {
        val creations = AtomicInteger()
        val adapter = ExecutionEventAdapter(
            epochOf = { 1L },
            newConnection = { _, _, _, _ -> creations.incrementAndGet(); FakePort() },
            scope = this,
        )
        val gate = CountDownLatch(1)
        val threads = (1..4).map {
            Thread {
                gate.await()
                adapter.attach(
                    key = "pA", workspaceId = "ws", clientId = "cid", port = 1,
                    socketFile = java.io.File("crush-pA.sock"), ownerEpoch = 1L,
                    runInFlightOnKey = { false }, clientSource = { null },
                )
            }
        }
        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(1, creations.get())
        adapter.close("pA")
    }

    @Test
    fun delayedFrameFromInvalidatedInstanceIsRejected() = runTest {
        var epoch = 1L
        val router = CrushEventRouter()
        val adapter = ExecutionEventAdapter(
            epochOf = { epoch },
            newRouter = { router },
            newConnection = { _, _, _, _ -> FakePort() },
            scope = this,
        )
        adapter.declare("pA", "run-1", 7L, "e-1")
        val stream = adapter.register("pA", "run-1")
        adapter.attach(
            key = "pA", workspaceId = "ws", clientId = "cid", port = 1,
            socketFile = java.io.File("crush-pA.sock"), ownerEpoch = 1L,
            runInFlightOnKey = { false }, clientSource = { null },
        )
        epoch = 2L // invalidation: old reader epoch is stale

        val body = JsonObject().apply { addProperty("run_id", "run-1") }
        router.dispatch(
            CrushEvent(CrushEvent.PayloadType.RUN_COMPLETE, CrushEvent.ChangeType.UPDATED, body)
        )
        kotlinx.coroutines.delay(50L)
        assertFalse(stream.tryReceive().getOrNull() != null)
        adapter.close("pA")
    }

    @Test
    fun closeStopsDeliveryWhileFramesAreArriving() = runTest {
        val router = CrushEventRouter()
        val adapter = ExecutionEventAdapter(
            epochOf = { 1L },
            newRouter = { router },
            newConnection = { _, _, _, _ -> FakePort() },
            scope = this,
        )
        adapter.declare("pA", "run-1", 7L, "e-1")
        val stream = adapter.register("pA", "run-1")
        adapter.attach(
            key = "pA", workspaceId = "ws", clientId = "cid", port = 1,
            socketFile = java.io.File("crush-pA.sock"), ownerEpoch = 1L,
            runInFlightOnKey = { false }, clientSource = { null },
        )
        adapter.close("pA")

        val body = JsonObject().apply { addProperty("run_id", "run-1") }
        router.dispatch(
            CrushEvent(CrushEvent.PayloadType.RUN_COMPLETE, CrushEvent.ChangeType.UPDATED, body)
        )
        kotlinx.coroutines.delay(50L)
        assertFalse(stream.tryReceive().getOrNull() != null)
    }

    @Test
    fun closeReleasesSubscribers() {
        var epoch = 1L
        val adapter = ExecutionEventAdapter(epochOf = { epoch })
        val ch: Channel<ExecutionEvent> = adapter.register("pA", "run-1")
        adapter.close("pA")
        assertTrue(ch.isClosedForReceive)
    }
}
