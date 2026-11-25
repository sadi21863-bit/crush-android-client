package com.crush.mobile.ui.screens.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * V2-15 Phase E remediation: deterministic concurrency tests for the exact
 * lifecycle controller ChatViewModel uses for Submit/Cancel.
 *
 * Every gate is a `CompletableDeferred`; no sleeps or timing assumptions.
 * The fake state mimics the three identity-checked callbacks: operation,
 * error, and clear.
 */
class QuestionOperationControllerTest {

    private data class FakeState(
        var current: QuestionRef? = null,
        var operation: QuestionOperation? = null,
        var error: String? = null,
        var clearCount: Int = 0,
    )

    private fun controller(state: FakeState) = QuestionOperationController(
        current = { state.current },
        // Deliberately dumb sinks: the controller must be the identity guard
        // being tested, not these callbacks.
        onOperation = { _, op -> state.operation = op },
        onError = { _, message -> state.error = message },
        onClear = { _ ->
            state.clearCount++
            state.current = null
            state.error = null
            state.operation = null
        },
    )

    private val first = QuestionRef("q1", "batch-1")
    private val second = QuestionRef("q2", "batch-2")

    @Test
    fun `a claim for a non-current identity is rejected`() {
        val state = FakeState(current = second)
        val ops = controller(state)

        assertNull(ops.claim(first, QuestionOperation.SUBMIT))
        assertNull(state.operation)
    }

    @Test
    fun `null and empty batch ids are distinct identity keys`() {
        val nullBatch = QuestionRef("q1", null)
        val emptyBatch = QuestionRef("q1", "")
        val state = FakeState(current = nullBatch)
        val ops = controller(state)

        assertNotNull(ops.claim(nullBatch, QuestionOperation.SUBMIT))
        state.current = emptyBatch
        assertNotNull(ops.claim(emptyBatch, QuestionOperation.SUBMIT))
    }

    @Test
    fun `submit replaced during reattach sends no answer`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val lease = ops.claim(first, QuestionOperation.SUBMIT)!!
        val reattachGate = CompletableDeferred<Unit>()
        var posts = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.executeWithReattach(
                lease = lease,
                reattach = { reattachGate.await(); null },
                post = { posts++; null },
            )
        }

        state.current = second
        state.operation = null
        state.error = null
        reattachGate.complete(Unit)
        job.join()

        assertEquals("obsolete submit must not reach the API", 0, posts)
        assertSame(second, state.current)
        assertEquals(0, state.clearCount)
        assertNull(state.error)
        assertNull(state.operation)
    }

    @Test
    fun `cancel replaced during reattach sends no cancel`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val lease = ops.claim(first, QuestionOperation.CANCEL)!!
        val reattachGate = CompletableDeferred<Unit>()
        var posts = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.executeWithReattach(
                lease = lease,
                reattach = { reattachGate.await(); null },
                post = { posts++; null },
            )
        }

        state.current = second
        state.operation = null
        state.error = null
        reattachGate.complete(Unit)
        job.join()

        assertEquals("obsolete cancel must not reach the API", 0, posts)
        assertSame(second, state.current)
        assertEquals(0, state.clearCount)
        assertNull(state.error)
        assertNull(state.operation)
    }

    @Test
    fun `old submit success cannot clear a newer batch`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val lease = ops.claim(first, QuestionOperation.SUBMIT)!!
        val gate = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.execute(lease) { gate.await(); null }
        }

        state.current = second
        state.operation = null
        state.error = null
        gate.complete(Unit)
        job.join()

        assertSame(second, state.current)
        assertEquals(0, state.clearCount)
        assertNull(state.error)
        assertNull(state.operation)
    }

    @Test
    fun `old cancel success cannot clear a newer batch`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val lease = ops.claim(first, QuestionOperation.CANCEL)!!
        val gate = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.execute(lease) { gate.await(); null }
        }

        state.current = second
        state.operation = null
        state.error = null
        gate.complete(Unit)
        job.join()

        assertSame(second, state.current)
        assertEquals(0, state.clearCount)
        assertNull(state.error)
        assertNull(state.operation)
    }

    @Test
    fun `old failure cannot overwrite a newer batch error or operation`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val lease = ops.claim(first, QuestionOperation.SUBMIT)!!
        val gate = CompletableDeferred<String?>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.execute(lease) { gate.await() }
        }

        state.current = second
        state.operation = QuestionOperation.CANCEL
        state.error = "newer error"
        gate.complete("stale error")
        job.join()

        assertSame(second, state.current)
        assertEquals("newer error", state.error)
        assertEquals(QuestionOperation.CANCEL, state.operation)
        assertEquals(0, state.clearCount)
    }

    @Test
    fun `two submits for one batch admit exactly one`() {
        val state = FakeState(current = first)
        val ops = controller(state)

        assertNotNull(ops.claim(first, QuestionOperation.SUBMIT))
        assertNull(ops.claim(first, QuestionOperation.SUBMIT))
    }

    @Test
    fun `two cancels for one batch admit exactly one`() {
        val state = FakeState(current = first)
        val ops = controller(state)

        assertNotNull(ops.claim(first, QuestionOperation.CANCEL))
        assertNull(ops.claim(first, QuestionOperation.CANCEL))
    }

    @Test
    fun `submit and cancel are mutually exclusive in both orders`() {
        val submitFirst = FakeState(current = first)
        val opsSubmit = controller(submitFirst)
        assertNotNull(opsSubmit.claim(first, QuestionOperation.SUBMIT))
        assertNull(opsSubmit.claim(first, QuestionOperation.CANCEL))

        val cancelFirst = FakeState(current = first)
        val opsCancel = controller(cancelFirst)
        assertNotNull(opsCancel.claim(first, QuestionOperation.CANCEL))
        assertNull(opsCancel.claim(first, QuestionOperation.SUBMIT))
    }

    @Test
    fun `failed submit releases the guard and allows retry`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val lease = ops.claim(first, QuestionOperation.SUBMIT)!!

        ops.execute(lease) { "submit failed" }

        assertEquals("submit failed", state.error)
        assertNull(state.operation)
        assertNotNull(ops.claim(first, QuestionOperation.SUBMIT))
    }

    @Test
    fun `failed cancel releases the guard and allows retry`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val lease = ops.claim(first, QuestionOperation.CANCEL)!!

        ops.execute(lease) { "cancel failed" }

        assertEquals("cancel failed", state.error)
        assertNull(state.operation)
        assertNotNull(ops.claim(first, QuestionOperation.CANCEL))
    }

    @Test
    fun `cancellation releases the guard`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val lease = ops.claim(first, QuestionOperation.SUBMIT)!!
        val gate = CompletableDeferred<String?>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.execute(lease) { gate.await() }
        }

        job.cancelAndJoin()

        assertNull(state.operation)
        assertNull(state.error)
        assertNotNull(ops.claim(first, QuestionOperation.SUBMIT))
    }

    @Test
    fun `new batch is usable while an older request is unwinding`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val oldLease = ops.claim(first, QuestionOperation.SUBMIT)!!
        val oldGate = CompletableDeferred<Unit>()
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.execute(oldLease) { oldGate.await(); null }
        }

        state.current = second
        state.operation = null
        val newLease = ops.claim(second, QuestionOperation.SUBMIT)
        assertNotNull("new batch must not inherit the old guard", newLease)
        ops.execute(newLease!!) { null }
        assertEquals(1, state.clearCount)

        oldGate.complete(Unit)
        oldJob.join()
        assertEquals("old completion must not clear the new batch again", 1, state.clearCount)
        assertNull(state.current)
    }

    @Test
    fun `old completion cannot release a newer batch guard`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val oldLease = ops.claim(first, QuestionOperation.SUBMIT)!!
        val oldGate = CompletableDeferred<Unit>()
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.execute(oldLease) { oldGate.await(); null }
        }

        state.current = second
        state.operation = null
        val newLease = ops.claim(second, QuestionOperation.SUBMIT)!!
        val newGate = CompletableDeferred<Unit>()
        val newJob = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.execute(newLease) { newGate.await(); null }
        }

        oldGate.complete(Unit)
        oldJob.join()
        assertNull("old completion must not free the new guard", ops.claim(second, QuestionOperation.SUBMIT))

        newGate.complete(Unit)
        newJob.join()
        state.current = second
        assertNotNull("new guard releases only on its own completion", ops.claim(second, QuestionOperation.SUBMIT))
    }

    @Test
    fun `newer operation state survives an older completion`() = runBlocking {
        val state = FakeState(current = first)
        val ops = controller(state)
        val oldLease = ops.claim(first, QuestionOperation.SUBMIT)!!
        val oldGate = CompletableDeferred<Unit>()
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
            ops.execute(oldLease) { oldGate.await(); null }
        }

        state.current = second
        state.operation = null
        assertNotNull(ops.claim(second, QuestionOperation.CANCEL))
        assertEquals(QuestionOperation.CANCEL, state.operation)

        oldGate.complete(Unit)
        oldJob.join()

        assertSame(second, state.current)
        assertEquals(QuestionOperation.CANCEL, state.operation)
    }
}
