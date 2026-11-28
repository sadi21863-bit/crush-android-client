package com.crush.mobile.execution

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused closure pass: regression pins for the buffer lifecycle, explicit
 * execution identity and persistence-unavailable replay contract.
 */
class FoundationClosureTest {

    private fun event(
        executionId: String?,
        runId: String = "run-1",
        detail: String = "confirmed_completion",
    ) = ExecutionEvent(
        type = ExecutionEvent.Type.RUN_COMPLETE,
        attribution = Attribution.CONFIRMED,
        runId = runId,
        detail = detail,
        generation = 7L,
        executionId = executionId,
    )

    @Test
    fun `explicit execution id that does not resolve is rejected not run-id fallback`() {
        val c = ExecutionController()
        val first = c.start(ExecutionTarget("pA", "ws", "s"), "shared-run")
        c.markSubmissionAcknowledged(first.executionId)
        val before = c.executions.value[first.executionId]!!

        c.onConfirmedEvent("pA", event(executionId = "unknown", runId = "shared-run"))
        assertEquals(before, c.executions.value[first.executionId])

        // Without an explicit id, an ambiguous run id is also rejected.
        val second = c.start(ExecutionTarget("pA", "ws", "s"), "shared-run")
        c.markSubmissionAcknowledged(second.executionId)
        val beforeSecond = c.executions.value[second.executionId]!!
        c.onConfirmedEvent("pA", event(executionId = null, runId = "shared-run"))
        assertEquals(beforeSecond, c.executions.value[second.executionId])
    }

    @Test
    fun `add racing clear cannot resurrect evidence after invalidation`() {
        val buffer = ConfirmedEventBuffer()
        val threads = 4
        val pool = Executors.newFixedThreadPool(threads + 1)
        val gate = CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        val futures = (0 until threads).map {
            pool.submit {
                gate.await()
                if (buffer.add("pA", "run-1", event("e1"))) accepted.incrementAndGet()
            }
        }
        val clearer = pool.submit {
            gate.await()
            buffer.clear("pA", "run-1")
        }
        gate.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        clearer.get(10, TimeUnit.SECONDS)
        pool.shutdownNow()

        // Whether an add won the race or was tombstoned, no event survives.
        assertEquals(0, buffer.pendingCount("pA", "run-1"))
        assertFalse(buffer.add("pA", "run-1", event("late")))
    }

    @Test
    fun `removal is head only and preserves later evidence`() {
        val buffer = ConfirmedEventBuffer()
        val first = event("e1", detail = "first")
        val second = event("e1", detail = "second", ).copy(
            type = ExecutionEvent.Type.MESSAGE,
        )
        assertTrue(buffer.add("pA", "run-1", first))
        assertTrue(buffer.add("pA", "run-1", second))
        buffer.remove("pA", "run-1", second)
        assertEquals(2, buffer.pendingCount("pA", "run-1"))
        buffer.remove("pA", "run-1", first)
        assertEquals(listOf(second), buffer.peek("pA", "run-1"))
    }

    @Test
    fun `unavailable persistence keeps evidence pending and applies once on retry`() = runBlocking {
        val dao = FakeDao()
        val store = ExecutionStore(dao, clock = { 10L })
        dao.rows["e1"] = ExecutionEntity(
            executionId = "e1",
            runId = "run-1",
            sessionId = "s",
            engineKeyToken = "chat",
            workspacePath = "/w",
            engineGeneration = 7L,
            phase = ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name,
            createdAtMs = 1L,
            updatedAtMs = 1L,
            ackState = ExecutionAckState.ACKED.name,
        )
        val buffer = ConfirmedEventBuffer()
        val ev = event("e1")

        dao.failExact = true
        assertEquals(ConfirmedApply.Unavailable::class, store.applyConfirmedEvent(ev)::class)
        assertTrue(buffer.add("pA", "run-1", ev))
        assertEquals(1, buffer.pendingCount("pA", "run-1"))

        dao.failExact = false
        assertEquals(ConfirmedApply.Applied, store.applyConfirmedEvent(ev))
        buffer.remove("pA", "run-1", ev)
        assertEquals(0, buffer.pendingCount("pA", "run-1"))
        assertEquals(ExecutionLifecycle.COMPLETED.name, dao.rows["e1"]!!.phase)
    }

    /** Minimal in-memory DAO: only exact-identity paths need real behaviour. */
    private class FakeDao : ExecutionDao {
        val rows = mutableMapOf<String, ExecutionEntity>()
        var failExact = false

        override suspend fun insert(row: ExecutionEntity) { rows[row.executionId] = row }
        override suspend fun byId(id: String) = rows[id]
        override suspend fun recoverableRows() = rows.values.toList()
        override suspend fun markSent(id: String, at: Long) = 0
        override suspend fun markAcked(id: String, at: Long) = 0
        override suspend fun markAmbiguous(id: String, at: Long) = 0
        override suspend fun markRejected(id: String, reason: String, at: Long) = 0
        override suspend fun markRunning(id: String, at: Long) = 0
        override suspend fun markRunningByRunId(runId: String, at: Long) = 0
        override suspend fun byRunId(runId: String) = rows.values.firstOrNull { it.runId == runId }
        override suspend fun byExecutionAndRun(executionId: String, runId: String) =
            rows[executionId]?.takeIf { it.runId == runId }
        override suspend fun markRunningExact(
            executionId: String, runId: String, generation: Long, at: Long,
        ): Int {
            if (failExact) throw IllegalStateException("db unavailable")
            val row = rows[executionId] ?: return 0
            if (row.runId != runId || row.phase != ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name) return 0
            rows[executionId] = row.copy(phase = ExecutionLifecycle.RUNNING.name, updatedAtMs = at)
            return 1
        }
        override suspend fun markTerminalExact(
            executionId: String, runId: String, generation: Long,
            phase: String, reason: String, at: Long,
        ): Int {
            if (failExact) throw IllegalStateException("db unavailable")
            val row = rows[executionId] ?: return 0
            if (row.runId != runId ||
                row.phase !in setOf(
                    ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED.name,
                    ExecutionLifecycle.RUNNING.name,
                )
            ) return 0
            rows[executionId] = row.copy(phase = phase, terminalReason = reason, updatedAtMs = at)
            return 1
        }
        override suspend fun correctGenericCompletionToFailed(
            executionId: String, runId: String, generation: Long, reason: String, at: Long,
        ) = 0
        override suspend fun markFailedExact(
            executionId: String, runId: String, generation: Long, reason: String, at: Long,
        ) = 0
        override suspend fun markTerminalByRunId(
            runId: String, phase: String, reason: String, at: Long,
        ) = 0
        override suspend fun markTerminal(
            id: String, phase: String, reason: String?, at: Long,
        ) = 0
        override suspend fun markUnsent(id: String, reason: String, at: Long) = 0
        override suspend fun markUnknown(id: String, reason: String, at: Long) = 0
        override suspend fun markLost(id: String, reason: String, at: Long) = 0
        override suspend fun observeProgress(id: String, at: Long) = 0
    }
}
