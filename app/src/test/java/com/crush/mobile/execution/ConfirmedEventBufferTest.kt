package com.crush.mobile.execution

import android.content.Context
import com.crush.mobile.data.project.RoomProjectStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Deterministic interleavings for the event-before-ack race. */
@RunWith(RobolectricTestRunner::class)
class ConfirmedEventBufferTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val dbName = "event-buffer-test.db"

    @Before
    fun wipeTestDatabase() {
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.let { dir ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(dir, "$dbName$suffix").delete()
            }
        }
    }

    private fun event(
        type: ExecutionEvent.Type = ExecutionEvent.Type.RUN_COMPLETE,
        detail: String = "confirmed_completion",
        generation: Long? = 7L,
    ) = ExecutionEvent(
        type = type,
        attribution = Attribution.CONFIRMED,
        runId = "run-1",
        detail = detail,
        generation = generation,
    )

    private fun storeAt(clock: Long = 10L) =
        ExecutionStore(RoomProjectStore(context, dbName).executionDao(), clock = { clock })

    private suspend fun sentButNotAcked(store: ExecutionStore) {
        store.persistIntent(
            executionId = "e1",
            runId = "run-1",
            target = ExecutionTarget("chat", "ws", "s"),
            engineKeyToken = "chat",
            workspacePath = "/workspace",
            generation = 7L,
        )
        store.markSent("e1")
    }

    @Test(timeout = 120_000)
    fun `an event before ack is buffered then applied after ack`() = runBlocking {
        val store = storeAt()
        val buffer = ConfirmedEventBuffer()
        sentButNotAcked(store)

        val early = event()
        // The DAO guard refuses it because ACKED is not durable yet.
        assertEquals(0, store.applyConfirmedEvent("run-1", early))
        assertTrue(buffer.add("chat", "run-1", early))

        assertEquals(1, store.markAcked("e1"))
        val replayed = buffer.drain("chat", "run-1")
        assertEquals(1, replayed.size)
        assertEquals(1, store.applyConfirmedEvent("run-1", replayed.first()))
        assertEquals(ExecutionLifecycle.COMPLETED.name, store.byId("e1")!!.phase)
        assertEquals("confirmed_completion", store.byId("e1")!!.terminalReason)
    }

    @Test(timeout = 120_000)
    fun `a failed ack never manufactures an acknowledged durable state`() = runBlocking {
        val store = storeAt()
        val buffer = ConfirmedEventBuffer()
        sentButNotAcked(store)

        assertTrue(buffer.add("chat", "run-1", event()))
        // No ack was written, so a guarded durable event cannot apply.
        assertEquals(0, store.applyConfirmedEvent("run-1", event()))
        // The honest ambiguity write records the missing acknowledgement.
        assertEquals(1, store.markAmbiguous("e1"))

        val row = store.byId("e1")!!
        assertEquals(ExecutionLifecycle.SEND_ATTEMPTED.name, row.phase)
        assertEquals(ExecutionAckState.AMBIGUOUS.name, row.ackState)
        // No replay: ACK was never durable, so the buffered event stays unapplied.
        assertEquals(1, buffer.pendingCount("chat", "run-1"))
        assertEquals(0, store.applyConfirmedEvent("run-1", event()))
    }

    @Test(timeout = 120_000)
    fun `a stale generation event is never applied on replay`() = runBlocking {
        val store = storeAt()
        val buffer = ConfirmedEventBuffer()
        sentButNotAcked(store)
        assertTrue(buffer.add("chat", "run-1", event(generation = 99L)))
        assertEquals(1, store.markAcked("e1"))

        assertEquals(1, buffer.drain("chat", "run-1").size)
        assertEquals(0, store.applyConfirmedEvent("run-1", event(generation = 99L)))
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name, store.byId("e1")!!.phase)
    }

    @Test(timeout = 120_000)
    fun `overflow is explicit and duplicates do not multiply evidence`() {
        val buffer = ConfirmedEventBuffer(maxEvents = 2)
        assertTrue(buffer.add("chat", "run-1", event()))
        assertTrue(buffer.add("chat", "run-1", event()))
        assertEquals(1, buffer.pendingCount("chat", "run-1"))

        assertTrue(buffer.add("chat", "run-1", event(detail = "MESSAGE", type = ExecutionEvent.Type.MESSAGE)))
        assertFalse(buffer.add("chat", "run-1", event(detail = "PERMISSION", type = ExecutionEvent.Type.PERMISSION)))
        assertTrue(buffer.isIncomplete("chat", "run-1"))
        assertTrue(buffer.drain("chat", "run-1").isEmpty())
    }

    @Test(timeout = 120_000)
    fun `a terminal event before ack is not lost and not claimed complete`() = runBlocking {
        val store = storeAt()
        val buffer = ConfirmedEventBuffer()
        sentButNotAcked(store)
        assertTrue(buffer.add("chat", "run-1", event()))

        assertEquals(ExecutionLifecycle.SEND_ATTEMPTED.name, store.byId("e1")!!.phase)
        assertEquals(1, store.markAcked("e1"))
        assertEquals(1, store.applyConfirmedEvent("run-1", buffer.drain("chat", "run-1").first()))
        assertEquals(ExecutionLifecycle.COMPLETED.name, store.byId("e1")!!.phase)
    }
}
