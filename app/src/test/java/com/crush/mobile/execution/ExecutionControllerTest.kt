package com.crush.mobile.execution

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1 controller tests: the four invariants and the four independent dimensions. */
class ExecutionControllerTest {

    private fun target() = ExecutionTarget("pA", "ws-1", "sess-1")

    @Test
    fun executionRunsWithNoObserver() {
        val c = ExecutionController()
        val e = c.start(target())
        c.markAttempt(e.executionId)
        c.markSubmissionAcknowledged(e.executionId)
        assertTrue(c.markRunning(e.executionId, Attribution.CONFIRMED))

        c.unsubscribe(e.executionId, 1L) // not subscribed; no effect
        assertEquals(ExecutionLifecycle.RUNNING, c.executions.value[e.executionId]?.lifecycle)
    }

    @Test
    fun unsubscribeIsDetachOnlyAndNeverCancels() {
        val epoch = 7L
        val c = ExecutionController(epochOf = { epoch })
        val e = c.start(target())
        c.markAttempt(e.executionId)
        c.markSubmissionAcknowledged(e.executionId)
        c.markRunning(e.executionId, Attribution.CONFIRMED)
        c.subscribe(e.executionId, 42L)
        c.unsubscribe(e.executionId, 42L)

        val view = c.executions.value[e.executionId]!!
        assertEquals(ExecutionLifecycle.RUNNING, view.lifecycle)
        assertEquals(ObservationState.DETACHED, view.observation)
        assertEquals(CancellationState.NOT_REQUESTED, view.cancellation)
    }

    @Test
    fun plausibleActivityCannotAdvancePhaseOrClearNoProgress() {
        val c = ExecutionController()
        val e = c.start(target())
        c.markAttempt(e.executionId)
        c.markSubmissionAcknowledged(e.executionId)

        assertFalse(c.markRunning(e.executionId, Attribution.PLAUSIBLE))
        assertTrue(c.setNoObservableProgress(e.executionId))
        assertTrue(c.observeProgress(e.executionId, Attribution.PLAUSIBLE, at = 99L))

        val view = c.executions.value[e.executionId]!!
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED, view.lifecycle)
        assertEquals(ObservationState.NO_OBSERVABLE_PROGRESS, view.observation)
        assertEquals(99L, view.lastObservedProgressAt)
    }

    @Test
    fun ambiguousPromptCannotOwnExecutionAndResolvedPromptDoesNotResume() {
        val c = ExecutionController()
        val e = c.start(target())
        c.markAttempt(e.executionId)
        c.markSubmissionAcknowledged(e.executionId)

        assertFalse(c.setInteraction(e.executionId, InteractionState.QUESTION_PENDING, Attribution.PLAUSIBLE))
        assertTrue(c.setInteraction(e.executionId, InteractionState.QUESTION_PENDING, Attribution.CONFIRMED))
        assertTrue(c.resolvePrompt(e.executionId))

        val view = c.executions.value[e.executionId]!!
        assertEquals(InteractionState.NONE, view.interaction)
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED, view.lifecycle)
    }

    @Test
    fun cancelResolvedIsNotAnExecutionTerminal() {
        val c = ExecutionController()
        val e = c.start(target())
        c.markAttempt(e.executionId)
        c.markSubmissionAcknowledged(e.executionId)
        c.markRunning(e.executionId, Attribution.CONFIRMED)

        assertTrue(c.requestCancel(e.executionId, "user_stop"))
        assertTrue(c.markCancelResolved(e.executionId))
        val view = c.executions.value[e.executionId]!!
        assertEquals(ExecutionLifecycle.CANCELLING, view.lifecycle)
        assertEquals(CancellationState.REQUEST_RESOLVED, view.cancellation)
        assertFalse(view.isTerminal)
    }

    @Test
    fun retryCreatesNewIdentityAndKeepsOldRecord() {
        val c = ExecutionController()
        val first = c.start(target(), runId = "run-1")
        c.markAttempt(first.executionId)
        c.markSubmissionAcknowledged(first.executionId)
        c.markRunning(first.executionId, Attribution.CONFIRMED)
        c.markUnknown(first.executionId, "ambiguous_send")

        val fresh = c.retry(first.executionId)!!
        assertNotEquals(first.executionId, fresh.executionId)
        assertNotEquals(first.runId, fresh.runId)
        assertEquals(first.target, fresh.target)
        assertEquals(first.executionId, fresh.retrySourceExecutionId)
        assertEquals(ExecutionLifecycle.UNKNOWN, c.executions.value[first.executionId]?.lifecycle)
    }

    @Test
    fun confirmedRunCompleteIsTheOnlyTerminalEngineEvidence() {
        val c = ExecutionController()
        val e = c.onIntent(target(), "run-1")
        c.markSubmissionAcknowledged(e.executionId)

        val unconfirmed = ExecutionEvent(
            ExecutionEvent.Type.RUN_COMPLETE, Attribution.UNATTRIBUTED, runId = "run-1"
        )
        c.onConfirmedEvent("pA", unconfirmed)
        assertFalse(c.executions.value[e.executionId]!!.isTerminal)

        val confirmed = ExecutionEvent(
            ExecutionEvent.Type.RUN_COMPLETE, Attribution.CONFIRMED, runId = "run-1"
        )
        c.onConfirmedEvent("pA", confirmed)
        val view = c.executions.value[e.executionId]!!
        assertEquals(ExecutionLifecycle.COMPLETED, view.lifecycle)
        assertEquals("confirmed_completion", view.reasonCode)
    }

    @Test
    fun intentIsRegisteredBeforePostAndAmbiguousOutcomeStaysUnknown() {
        val c = ExecutionController()
        val e = c.onIntent(target(), "run-1")

        // Before any HTTP call the controller already owns an attempt record.
        assertEquals(ExecutionLifecycle.SEND_ATTEMPTED, c.executions.value[e.executionId]?.lifecycle)

        c.markAmbiguousSend(e.executionId, "send timed out")
        val view = c.executions.value[e.executionId]!!
        assertEquals(ExecutionLifecycle.UNKNOWN, view.lifecycle)
        assertEquals("send timed out", view.reasonCode)
        assertEquals(1, c.executions.value.size) // no auto-created duplicate
    }

    @Test
    fun definitiveRejectionIsFailedAndAcknowledgementIsNotRunning() {
        val c = ExecutionController()
        val rejected = c.onIntent(target(), "run-1")
        c.markSubmitRejected(rejected.executionId, "HTTP 400")
        assertEquals(ExecutionLifecycle.FAILED, c.executions.value[rejected.executionId]?.lifecycle)

        val accepted = c.onIntent(target(), "run-2")
        c.markSubmissionAcknowledged(accepted.executionId)
        val view = c.executions.value[accepted.executionId]!!
        assertEquals(ExecutionLifecycle.SUBMISSION_ACKNOWLEDGED, view.lifecycle)
        assertFalse(view.lifecycle == ExecutionLifecycle.RUNNING)
    }

    @Test
    fun invalidationMarksActiveExecutionsUnknownWithReason() {
        var epoch = 1L
        val c = ExecutionController(epochOf = { epoch })
        val e = c.start(target())
        c.markAttempt(e.executionId)
        c.markSubmissionAcknowledged(e.executionId)
        c.markRunning(e.executionId, Attribution.CONFIRMED)
        epoch = 2L

        c.onInvalidated("pA", "registry.stop")
        val view = c.executions.value[e.executionId]!!
        assertEquals(ExecutionLifecycle.UNKNOWN, view.lifecycle)
        assertEquals(2L, view.epoch)
        assertEquals("invalidated:registry.stop", view.reasonCode)
    }

    @Test
    fun hasActiveIsKeyedAndReleasesOnTerminal() {
        val c = ExecutionController()
        val e = c.start(target())
        assertTrue(c.hasActive("pA"))
        assertFalse(c.hasActive("pB"))
        c.markUnknown(e.executionId, "done")
        assertFalse(c.hasActive("pA"))
    }

    @Test
    fun staleObserverCannotDetachAnotherObserver() {
        val c = ExecutionController()
        val e = c.start(target())
        c.subscribe(e.executionId, 1L)
        c.subscribe(e.executionId, 2L)
        c.unsubscribe(e.executionId, 1L)

        assertEquals(ObservationState.OBSERVING, c.executions.value[e.executionId]?.observation)
        assertNull(c.executions.value["missing"])
    }

    @Test
    fun concurrentStartsAndActiveChecksDoNotRace() {
        val c = ExecutionController()
        val n = 16
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n + 2)
        val ids = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val starters = (0 until n).map {
            pool.submit {
                try {
                    ready.await()
                    ids += c.start(target()).executionId
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        val readers = (0 until 2).map {
            pool.submit {
                try {
                    ready.await()
                    repeat(200) { c.hasActive("pA") }
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        ready.countDown()
        (starters + readers).forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(n, ids.toSet().size)
        assertEquals(n, c.executions.value.size)
        assertTrue(c.hasActive("pA"))
    }

    @Test
    fun invalidationDuringConcurrentStartsMarksEveryRegisteredExecution() {
        val c = ExecutionController()
        val n = 12
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n + 1)
        val ids = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val starters = (0 until n).map {
            pool.submit {
                try {
                    ready.await()
                    ids += c.start(target()).executionId
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        val invalidator = pool.submit {
            try {
                ready.await()
                repeat(50) { c.onInvalidated("pA", "registry.stop") }
            } catch (t: Throwable) {
                errors += t
            }
        }
        ready.countDown()
        (starters + invalidator).forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()
        // Settle the ones that could only start after the last invalidation.
        c.onInvalidated("pA", "registry.stop")

        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(n, ids.toSet().size)
        ids.forEach { id ->
            val view = c.executions.value[id]!!
            assertEquals(ExecutionLifecycle.UNKNOWN, view.lifecycle)
            assertEquals("invalidated:registry.stop", view.reasonCode)
        }
        assertFalse(c.hasActive("pA"))
    }
}
