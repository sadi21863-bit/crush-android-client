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
    fun `C2 C3 with no evidence are unknown not lost`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("c2", ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED, ExecutionAckState.ACKED))
        dao.insert(row("c3", ExecutionLifecycle.RUNNING, ExecutionAckState.ACKED))

        val summary = ExecutionReconciler(dao, clock = { 9_000L }).reconcile()
        assertEquals(2, summary.unknown)
        assertEquals(0, summary.lost)
        listOf("c2", "c3").forEach { id ->
            val stored = dao.byId(id)!!
            assertEquals(ExecutionLifecycle.UNKNOWN.name, stored.phase)
            assertEquals("recovery:evidence_unavailable", stored.terminalReason)
        }
    }

    @Test(timeout = 120_000)
    fun `prompt ambiguity is deferred untouched and reconciliation is idempotent`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(
            row("c6", ExecutionLifecycle.SEND_ATTEMPTED, ExecutionAckState.AMBIGUOUS)
                .copy(promptRef = "prompt-ref-reserved-for-A3")
        )

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
            val summary = ExecutionReconciler(
                dao,
                FixedEvidence(ReconciliationResult.CONFIRMED),
                clock = { 9_000L },
            ).reconcile()

            val controller = ExecutionController()
            summary.recovered.forEach { recovered ->
                val entity = recovered.entity
                val lifecycle = when (recovered.outcome) {
                    ExecutionReconciler.Outcome.UNSENT -> ExecutionLifecycle.UNSENT
                    ExecutionReconciler.Outcome.AMBIGUOUS -> ExecutionLifecycle.SEND_AMBIGUOUS
                    ExecutionReconciler.Outcome.UNKNOWN -> ExecutionLifecycle.UNKNOWN
                    ExecutionReconciler.Outcome.LOST -> ExecutionLifecycle.LOST
                    ExecutionReconciler.Outcome.COMPLETED -> ExecutionLifecycle.COMPLETED
                    ExecutionReconciler.Outcome.FAILED -> ExecutionLifecycle.FAILED
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
            assertEquals("recovery:confirmed_absence", view.reasonCode)
            assertEquals(ObservationState.DETACHED, view.observation)
        }

    private class FixedEvidence(
        private val result: ReconciliationResult,
        private val terminal: ExecutionLifecycle? = null,
        private val detail: String = "",
        private val fail: Boolean = false,
        private val currentGeneration: Long? = null,
        private val generationAware: Boolean = false,
    ) : ExecutionEvidencePort {
        override suspend fun probe(
            engineKeyToken: String,
            workspacePath: String,
            sessionId: String,
            generation: Long?,
        ): EngineEvidence {
            if (fail) throw IllegalStateException("probe failed")
            if (generationAware) {
                val state = when {
                    generation == null -> ReconciliationResult.NO_EVIDENCE
                    generation != currentGeneration -> ReconciliationResult.CONFLICTING
                    else -> ReconciliationResult.CONFIRMED
                }
                return EngineEvidence(state, detail = "generation probe: $state")
            }
            return EngineEvidence(result, terminal, detail)
        }
    }

    @Test(timeout = 120_000)
    fun `an evidence gap is unknown not lost`() = runBlocking {
        listOf(
            ReconciliationResult.UNAVAILABLE to "recovery:evidence_unavailable",
            ReconciliationResult.INCOMPLETE to "recovery:evidence_incomplete",
            ReconciliationResult.CONFLICTING to "recovery:evidence_conflicting",
            ReconciliationResult.NO_EVIDENCE to "recovery:evidence_no_evidence",
        ).forEachIndexed { i, (result, reason) ->
            val dao = RoomProjectStore(context, dbName).executionDao()
            dao.insert(row("gap$i", ExecutionLifecycle.RUNNING, ExecutionAckState.ACKED))
            val summary = ExecutionReconciler(dao, FixedEvidence(result), clock = { 9_000L }).reconcile()

            val recovered = summary.recovered.first { it.entity.executionId == "gap$i" }
            assertEquals("$result", ExecutionReconciler.Outcome.UNKNOWN, recovered.outcome)
            assertEquals("$result", reason, recovered.detail)
            val stored = dao.byId("gap$i")!!
            assertEquals(ExecutionLifecycle.UNKNOWN.name, stored.phase)
            assertEquals(reason, stored.terminalReason)
            assertNotNull(stored)
        }
    }

    @Test(timeout = 120_000)
    fun `a failed probe is an evidence gap not a loss`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("fail", ExecutionLifecycle.RUNNING, ExecutionAckState.ACKED))

        val summary = ExecutionReconciler(
            dao,
            FixedEvidence(ReconciliationResult.CONFIRMED, fail = true),
            clock = { 9_000L },
        ).reconcile()

        assertEquals(1, summary.unknown)
        assertEquals(0, summary.lost)
        assertEquals(
            "recovery:evidence_unavailable",
            dao.byId("fail")!!.terminalReason,
        )
    }

    @Test(timeout = 120_000)
    fun `confirmed terminal history and confirmed absence are distinguished`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("done", ExecutionLifecycle.RUNNING, ExecutionAckState.ACKED))

        val done = ExecutionReconciler(
            dao,
            FixedEvidence(
                ReconciliationResult.CONFIRMED,
                ExecutionLifecycle.COMPLETED,
                "history:completed",
            ),
            clock = { 9_000L },
        ).reconcile()
        assertEquals(
            ExecutionReconciler.Outcome.COMPLETED,
            done.recovered.first { it.entity.executionId == "done" }.outcome,
        )

        dao.insert(row("absent", ExecutionLifecycle.RUNNING, ExecutionAckState.ACKED))
        val absent = ExecutionReconciler(
            dao,
            FixedEvidence(ReconciliationResult.CONFIRMED),
            clock = { 10_000L },
        ).reconcile()
        assertEquals(
            ExecutionReconciler.Outcome.LOST,
            absent.recovered.first { it.entity.executionId == "absent" }.outcome,
        )
        assertEquals("recovery:confirmed_absence", dao.byId("absent")!!.terminalReason)
    }

    @Test(timeout = 120_000)
    fun `an ambiguous send stays visible through three restart cycles`() = runBlocking {
        val dao = RoomProjectStore(context, dbName).executionDao()
        dao.insert(row("amb3", ExecutionLifecycle.SEND_ATTEMPTED, ExecutionAckState.SENT_UNCONFIRMED))

        repeat(3) { cycle ->
            val summary = ExecutionReconciler(dao, clock = { 9_000L + cycle }).reconcile()
            assertEquals("cycle $cycle", 1, summary.ambiguous)
            assertEquals("cycle $cycle", 0, summary.lost)
            assertEquals("cycle $cycle", 0, summary.deferred)

            val controller = ExecutionController()
            summary.recovered.forEach { r ->
                controller.onRecovered(
                    executionId = r.entity.executionId,
                    runId = r.entity.runId,
                    target = ExecutionTarget("", "", r.entity.sessionId),
                    lifecycle = ExecutionLifecycle.SEND_AMBIGUOUS,
                    reasonCode = r.detail,
                )
            }
            val view = controller.executions.value["amb3"]!!
            assertEquals("cycle $cycle", ExecutionLifecycle.SEND_AMBIGUOUS, view.lifecycle)
            assertEquals("cycle $cycle", "recovery:send_ambiguous", view.reasonCode)
            assertTrue("cycle $cycle", view.recovered)
            assertEquals("cycle $cycle", ObservationState.DETACHED, view.observation)
        }
        assertEquals(ExecutionAckState.AMBIGUOUS.name, dao.byId("amb3")!!.ackState)
    }

    @Test(timeout = 120_000)
    fun `generation mismatch and missing generation never adopt into a replacement engine`() = runBlocking {
        listOf(
            "match" to 7L,
            "mismatch" to 99L,
            "missing" to null,
        ).forEachIndexed { i, (_, currentGeneration) ->
            val dao = RoomProjectStore(context, dbName).executionDao()
            dao.insert(
                row("gen$i", ExecutionLifecycle.RUNNING, ExecutionAckState.ACKED).copy(
                    engineGeneration = 7L,
                )
            )
            val summary = ExecutionReconciler(
                dao,
                FixedEvidence(
                    ReconciliationResult.CONFIRMED,
                    currentGeneration = currentGeneration,
                    generationAware = true,
                ),
                clock = { 9_000L },
            ).reconcile()
            val outcome = summary.recovered
                .first { it.entity.executionId == "gen$i" }.outcome
            if (i == 0) {
                assertEquals(ExecutionReconciler.Outcome.LOST, outcome)
            } else {
                assertEquals(ExecutionReconciler.Outcome.UNKNOWN, outcome)
                assertEquals(ExecutionLifecycle.UNKNOWN.name, dao.byId("gen$i")!!.phase)
            }
        }
    }
}
