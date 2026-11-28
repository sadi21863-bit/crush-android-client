package com.crush.mobile.execution

import android.content.Context
import com.crush.mobile.data.project.RoomProjectStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Stage 2: durable terminal outcomes through the REAL guarded DAO. */
@RunWith(RobolectricTestRunner::class)
class ExecutionTerminalPersistenceTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val dbName = "terminal-events-test.db"

    @Before
    fun wipeTestDatabase() {
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.let { dir ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(dir, "$dbName$suffix").delete()
            }
        }
    }

    private fun storeAt(clock: Long = 10L) =
        ExecutionStore(RoomProjectStore(context, dbName).executionDao(), clock = { clock })

    private suspend fun seed(
        store: ExecutionStore,
        id: String,
        generation: Long? = 7L,
        phase: String = ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name,
        ack: String = ExecutionAckState.ACKED.name,
    ) {
        store.persistIntent(
            executionId = id,
            runId = "run-$id",
            target = ExecutionTarget("chat", "ws", "s"),
            engineKeyToken = "chat",
            workspacePath = "/workspace",
            generation = generation,
        )
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.markSent(id, 1L)
        if (ack == ExecutionAckState.ACKED.name) dao.markAcked(id, 2L)
        if (phase == ExecutionLifecycle.RUNNING.name) dao.markRunningByRunId("run-$id", 3L)
    }

    private fun event(
        id: String,
        type: ExecutionEvent.Type = ExecutionEvent.Type.RUN_COMPLETE,
        detail: String = "confirmed_completion",
        generation: Long? = 7L,
        superseded: Boolean = false,
    ) = ExecutionEvent(
        type = type,
        attribution = Attribution.CONFIRMED,
        runId = "run-$id",
        detail = detail,
        generation = generation,
        executionId = id,
        superseded = superseded,
    )

    @Test(timeout = 120_000)
    fun `confirmed completion persists COMPLETED and survives reopen`() = runBlocking {
        val store = storeAt()
        seed(store, "done")
        assertEquals(ConfirmedApply.Applied, store.applyConfirmedEvent(event("done")))

        val row = storeAt().byId("done")!!
        assertEquals(ExecutionLifecycle.COMPLETED.name, row.phase)
        assertEquals("confirmed_completion", row.terminalReason)
        assertEquals(10L, row.updatedAtMs)
    }

    @Test(timeout = 120_000)
    fun `confirmed cancellation keeps COMPLETED lifecycle with cancelled reason`() = runBlocking {
        val store = storeAt()
        seed(store, "cancel")
        assertEquals(
            ConfirmedApply.Applied,
            store.applyConfirmedEvent(event("cancel", detail = "confirmed_cancelled")),
        )

        val row = store.byId("cancel")!!
        assertEquals(ExecutionLifecycle.COMPLETED.name, row.phase)
        assertEquals("confirmed_cancelled", row.terminalReason)
    }

    @Test(timeout = 120_000)
    fun `duplicate terminal event is idempotent`() = runBlocking {
        val store = storeAt()
        seed(store, "dup")
        assertEquals(ConfirmedApply.Applied, store.applyConfirmedEvent(event("dup")))
        val first = store.byId("dup")!!

        assertEquals(
            ConfirmedApply.AlreadyApplied,
            store.applyConfirmedEvent(event("dup", detail = "late_reason")),
        )
        assertEquals(first, store.byId("dup"))
    }

    @Test(timeout = 120_000)
    fun `terminal event for an unknown execution changes nothing`() = runBlocking {
        val store = storeAt()
        seed(store, "other")
        assertEquals(
            ConfirmedApply.Conflict,
            store.applyConfirmedEvent(event("missing")),
        )
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name, store.byId("other")!!.phase)
    }

    @Test(timeout = 120_000)
    fun `stale generation and superseded declarations never terminate`() = runBlocking {
        val store = storeAt()
        seed(store, "stale")
        assertEquals(
            ConfirmedApply.Stale,
            store.applyConfirmedEvent(event("stale", generation = 99L)),
        )
        assertEquals(
            ConfirmedApply.Stale,
            store.applyConfirmedEvent(event("stale", superseded = true)),
        )
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name, store.byId("stale")!!.phase)
    }

    @Test(timeout = 120_000)
    fun `a wrong execution id cannot mutate another execution`() = runBlocking {
        val store = storeAt()
        seed(store, "victim")
        val wrong = event("victim").copy(executionId = "attacker")
        assertEquals(ConfirmedApply.Conflict, store.applyConfirmedEvent(wrong))
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name, store.byId("victim")!!.phase)
    }

    @Test(timeout = 120_000)
    fun `progress after terminal is NotApplicable and never regresses`() = runBlocking {
        val store = storeAt()
        seed(store, "prog")
        store.applyConfirmedEvent(event("prog"))
        assertEquals(
            ConfirmedApply.NotApplicable,
            store.applyConfirmedEvent(
                event("prog", type = ExecutionEvent.Type.MESSAGE, detail = "MESSAGE"),
            ),
        )
        assertEquals(ExecutionLifecycle.COMPLETED.name, store.byId("prog")!!.phase)
    }

    @Test(timeout = 120_000)
    fun `healthy progress on a RUNNING row is NotApplicable not buffered evidence`() = runBlocking {
        val store = storeAt()
        seed(store, "healthy", phase = ExecutionLifecycle.RUNNING.name)
        assertEquals(
            ConfirmedApply.NotApplicable,
            store.applyConfirmedEvent(
                event("healthy", type = ExecutionEvent.Type.MESSAGE, detail = "MESSAGE"),
            ),
        )
        assertEquals(ExecutionLifecycle.RUNNING.name, store.byId("healthy")!!.phase)
        assertNull(store.byId("healthy")!!.terminalReason)
    }

    @Test(timeout = 120_000)
    fun `disconnect without terminal evidence fabricates no completion`() = runBlocking {
        val store = storeAt()
        seed(store, "disc", phase = ExecutionLifecycle.RUNNING.name)
        assertEquals(
            ConfirmedApply.NotApplicable,
            store.applyConfirmedEvent(
                event("disc", type = ExecutionEvent.Type.MESSAGE, detail = "MESSAGE"),
            ),
        )
        assertEquals(ExecutionLifecycle.RUNNING.name, store.byId("disc")!!.phase)
        assertNull(store.byId("disc")!!.terminalReason)
    }

    @Test(timeout = 120_000)
    fun `confirmed error is FAILED and generic completion cannot overwrite it`() = runBlocking {
        val store = storeAt()
        seed(store, "err")
        assertEquals(
            ConfirmedApply.Applied,
            store.applyConfirmedFailure("err", "run-err", 7L, "Unauthorized: Invalid API key."),
        )
        assertEquals(ConfirmedApply.AlreadyApplied, store.applyConfirmedEvent(event("err")))
        val row = store.byId("err")!!
        assertEquals(ExecutionLifecycle.FAILED.name, row.phase)
        assertEquals("Unauthorized: Invalid API key.", row.terminalReason)
    }

    @Test(timeout = 120_000)
    fun `late confirmed error corrects a generic COMPLETED but never cancellation`() = runBlocking {
        val a = storeAt()
        seed(a, "generic")
        a.applyConfirmedEvent(event("generic"))
        assertEquals(
            ConfirmedApply.Applied,
            a.applyConfirmedFailure("generic", "run-generic", 7L, "provider error with no message"),
        )
        assertEquals(
            ExecutionLifecycle.FAILED.name,
            storeAt().byId("generic")!!.phase,
        )

        val b = storeAt()
        seed(b, "cancelled")
        b.applyConfirmedEvent(event("cancelled", detail = "confirmed_cancelled"))
        assertEquals(
            ConfirmedApply.Conflict,
            b.applyConfirmedFailure("cancelled", "run-cancelled", 7L, "late error"),
        )
        assertEquals(
            "confirmed_cancelled",
            storeAt().byId("cancelled")!!.terminalReason,
        )
    }
}
