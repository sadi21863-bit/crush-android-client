package com.crush.mobile.execution

import android.content.Context
import com.crush.mobile.data.project.RoomProjectStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * V2-16 A2 second pass: the PRODUCTION durable-event path
 * (ExecutionStore.applyConfirmedEvent through the real guarded DAO), not a
 * standalone enum mapping helper.
 */
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

    private suspend fun seed(
        store: ExecutionStore,
        id: String,
        runId: String = "run-$id",
        generation: Long? = 7L,
        phase: String = ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name,
        ack: String = ExecutionAckState.ACKED.name,
    ) = store.persistIntent(
        executionId = id,
        runId = runId,
        target = ExecutionTarget("chat", "ws", "s"),
        engineKeyToken = "chat",
        workspacePath = "/workspace",
        generation = generation,
    ).also {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.markSent(id, 1L)
        if (ack == ExecutionAckState.ACKED.name) dao.markAcked(id, 2L)
        if (phase == ExecutionLifecycle.RUNNING.name) dao.markRunningByRunId(runId, 3L)
    }

    private fun confirmedCompletion(runId: String, generation: Long? = 7L) = ExecutionEvent(
        type = ExecutionEvent.Type.RUN_COMPLETE,
        attribution = Attribution.CONFIRMED,
        runId = runId,
        detail = "confirmed_completion",
        generation = generation,
    )

    @Test(timeout = 120_000)
    fun `confirmed completion persists COMPLETED with reason and survives reopen`() = runBlocking {
        val store = ExecutionStore(RoomProjectStore(context, dbName).executionDao(), clock = { 10L })
        seed(store, "done")

        assertEquals(1, store.applyConfirmedEvent("run-done", confirmedCompletion("run-done")))

        val reopened = ExecutionStore(RoomProjectStore(context, dbName).executionDao())
        val row = reopened.byId("done")!!
        assertEquals(ExecutionLifecycle.COMPLETED.name, row.phase)
        assertEquals("confirmed_completion", row.terminalReason)
        assertEquals(10L, row.updatedAtMs)
    }

    @Test(timeout = 120_000)
    fun `a duplicate terminal event cannot change reason or timestamp`() = runBlocking {
        val store = ExecutionStore(RoomProjectStore(context, dbName).executionDao(), clock = { 10L })
        seed(store, "dup")

        assertEquals(1, store.applyConfirmedEvent("run-dup", confirmedCompletion("run-dup")))
        val first = store.byId("dup")!!
        val late = confirmedCompletion("run-dup").copy(detail = "late_reason")
        assertEquals(0, store.applyConfirmedEvent("run-dup", late))
        assertEquals(first, store.byId("dup"))
    }

    @Test(timeout = 120_000)
    fun `terminal event for an unknown run changes nothing`() = runBlocking {
        val store = ExecutionStore(RoomProjectStore(context, dbName).executionDao(), clock = { 10L })
        seed(store, "other")

        assertEquals(0, store.applyConfirmedEvent("run-missing", confirmedCompletion("run-missing")))
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name, store.byId("other")!!.phase)
    }

    @Test(timeout = 120_000)
    fun `terminal event from a stale generation changes nothing`() = runBlocking {
        val store = ExecutionStore(RoomProjectStore(context, dbName).executionDao(), clock = { 10L })
        seed(store, "stale", generation = 7L)

        assertEquals(
            0,
            store.applyConfirmedEvent("run-stale", confirmedCompletion("run-stale", generation = 99L)),
        )
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name, store.byId("stale")!!.phase)
    }

    @Test(timeout = 120_000)
    fun `progress after terminal cannot regress the record`() = runBlocking {
        val store = ExecutionStore(RoomProjectStore(context, dbName).executionDao(), clock = { 10L })
        seed(store, "prog")
        store.applyConfirmedEvent("run-prog", confirmedCompletion("run-prog"))

        val progress = ExecutionEvent(
            type = ExecutionEvent.Type.MESSAGE,
            attribution = Attribution.CONFIRMED,
            runId = "run-prog",
            detail = "MESSAGE",
            generation = 7L,
        )
        assertEquals(0, store.applyConfirmedEvent("run-prog", progress))
        assertEquals(ExecutionLifecycle.COMPLETED.name, store.byId("prog")!!.phase)
    }

    @Test(timeout = 120_000)
    fun `a disconnect without a terminal event fabricates no completion`() = runBlocking {
        val store = ExecutionStore(RoomProjectStore(context, dbName).executionDao(), clock = { 10L })
        seed(store, "disc", phase = ExecutionLifecycle.RUNNING.name)

        assertEquals(
            0,
            store.applyConfirmedEvent(
                "run-disc",
                ExecutionEvent(
                    type = ExecutionEvent.Type.MESSAGE,
                    attribution = Attribution.CONFIRMED,
                    runId = "run-disc",
                    detail = "MESSAGE",
                    generation = 7L,
                ),
            ),
        )
        val row = store.byId("disc")!!
        assertEquals(ExecutionLifecycle.RUNNING.name, row.phase)
        assertEquals(null, row.terminalReason)
        assertNotNull(row)
    }
}
