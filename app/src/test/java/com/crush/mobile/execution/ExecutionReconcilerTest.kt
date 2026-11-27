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
 * V2-16 A2 slice 1: C1–C3 and the four send-path crash boundaries, verified
 * against the REAL guarded DAO (not an in-memory fake).
 */
@RunWith(RobolectricTestRunner::class)
class ExecutionReconcilerTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val dbName = "executions-reconcile-test.db"

    @Before
    fun wipeTestDatabase() {
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.let { dir ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(dir, "$dbName$suffix").delete()
            }
        }
    }

    private fun row(
        id: String,
        phase: ExecutionLifecycle,
        ack: ExecutionAckState,
    ) = ExecutionEntity(
        executionId = id,
        runId = "run-$id",
        sessionId = "sess-1",
        engineKeyToken = "proj:11111111-1111-1111-1111-111111111111",
        workspacePath = "/storage/emulated/0/Test project folder 2",
        phase = phase.name,
        createdAtMs = 1L,
        updatedAtMs = 1L,
        ackState = ack.name,
    )

    @Test(timeout = 120_000)
    fun `C1 idle has no record and mints no execution`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        val summary = ExecutionReconciler(dao, clock = { 9_000L }).reconcile()

        assertTrue(summary.recovered.isEmpty())
        assertEquals(0, summary.unsent + summary.ambiguous + summary.lost + summary.deferred)
        assertTrue(dao.recoverableRows().isEmpty())
    }

    @Test(timeout = 120_000)
    fun `boundary one intent before send recovers as visibly unsent`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("b1", ExecutionLifecycle.INTENT_RECORDED, ExecutionAckState.INTENT_ONLY))

        val summary = ExecutionReconciler(dao, clock = { 9_000L }).reconcile()
        assertEquals(1, summary.unsent)
        val stored = dao.byId("b1")!!
        assertEquals(ExecutionLifecycle.UNSENT.name, stored.phase)
        assertEquals("recovery:send_not_attempted", stored.terminalReason)
        assertEquals(9_000L, stored.updatedAtMs)
    }

    @Test(timeout = 120_000)
    fun `boundary two lost acknowledgement becomes ambiguous with no auto resend`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("b2", ExecutionLifecycle.SEND_ATTEMPTED, ExecutionAckState.SENT_UNCONFIRMED))

        val summary = ExecutionReconciler(dao, clock = { 9_000L }).reconcile()
        assertEquals(1, summary.ambiguous)
        val stored = dao.byId("b2")!!
        assertEquals(ExecutionAckState.AMBIGUOUS.name, stored.ackState)
        assertEquals(ExecutionLifecycle.SEND_ATTEMPTED.name, stored.phase)
    }

    @Test(timeout = 120_000)
    fun `C2 C3 with an ack but no atomic attach are visibly lost`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("c2", ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED, ExecutionAckState.ACKED))
        dao.insert(row("c3", ExecutionLifecycle.RUNNING, ExecutionAckState.ACKED))

        val summary = ExecutionReconciler(dao, clock = { 9_000L }).reconcile()
        assertEquals(2, summary.lost)
        listOf("c2", "c3").forEach { id ->
            val stored = dao.byId(id)!!
            assertEquals(ExecutionLifecycle.LOST.name, stored.phase)
            assertEquals("recovery:no_atomic_attach", stored.terminalReason)
        }
        assertTrue(dao.recoverableRows().isEmpty())
    }

    @Test(timeout = 120_000)
    fun `C6 ambiguity is deferred untouched and reconciliation is idempotent`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("c6", ExecutionLifecycle.SEND_ATTEMPTED, ExecutionAckState.AMBIGUOUS))

        val first = ExecutionReconciler(dao, clock = { 9_000L }).reconcile()
        assertEquals(1, first.deferred)
        val unchanged = dao.byId("c6")!!
        assertEquals(ExecutionAckState.AMBIGUOUS.name, unchanged.ackState)
        assertEquals(1L, unchanged.updatedAtMs)

        val second = ExecutionReconciler(dao, clock = { 10_000L }).reconcile()
        assertEquals(1, second.deferred)
        assertEquals(unchanged, dao.byId("c6"))
    }

    @Test(timeout = 120_000)
    fun `recovered outcomes can be adopted by the controller without claiming a live engine`() =
        runBlocking {
            val dao = RoomProjectStore(context, dbName).executionDao()
            dao.insert(row("c2", ExecutionLifecycle.RUNNING, ExecutionAckState.ACKED))
            val summary = ExecutionReconciler(dao, clock = { 9_000L }).reconcile()

            val controller = ExecutionController()
            summary.recovered.forEach { recovered ->
                val entity = recovered.entity
                val lifecycle = when (recovered.outcome) {
                    ExecutionReconciler.Outcome.UNSENT -> ExecutionLifecycle.UNSENT
                    ExecutionReconciler.Outcome.AMBIGUOUS -> ExecutionLifecycle.SEND_ATTEMPTED
                    ExecutionReconciler.Outcome.LOST -> ExecutionLifecycle.LOST
                    ExecutionReconciler.Outcome.DEFERRED -> return@forEach
                }
                controller.onRecovered(
                    executionId = entity.executionId,
                    runId = entity.runId,
                    target = ExecutionTarget(
                        entity.engineKeyToken,
                        entity.workspacePath,
                        entity.sessionId,
                    ),
                    lifecycle = lifecycle,
                    reasonCode = recovered.detail,
                )
            }

            val view = controller.executions.value["c2"]
            assertNotNull(view)
            assertEquals(ExecutionLifecycle.LOST, view!!.lifecycle)
            assertEquals("recovery:no_atomic_attach", view.reasonCode)
            assertEquals(ObservationState.DETACHED, view.observation)
        }
}
