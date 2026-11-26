package com.crush.mobile.execution

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
}
