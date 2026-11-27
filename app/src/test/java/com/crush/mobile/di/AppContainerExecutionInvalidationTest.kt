package com.crush.mobile.di

import android.app.Application
import android.os.Looper
import com.crush.mobile.execution.Attribution
import com.crush.mobile.execution.ExecutionLifecycle
import com.crush.mobile.execution.ExecutionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A1.3: the REAL container's invalidation listener, with the real controller
 * and adapter. No engine is started (the bundled arm64 binary cannot run on a
 * JVM). This proves the production wiring from adapter teardown to execution
 * state, not just the probe vocabulary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppContainerExecutionInvalidationTest {

    private fun container(): AppContainer {
        val c = AppContainer(RuntimeEnvironment.getApplication())
        shadowOf(Looper.getMainLooper()).idle()
        return c
    }

    private fun runningExecution(container: AppContainer, key: String): String {
        val view = container.executionController.start(
            ExecutionTarget(key, "ws-1", "sess-1"),
            "run-1",
        )
        container.executionController.markAttempt(view.executionId)
        container.executionController.markSubmissionAcknowledged(view.executionId)
        container.executionController.markRunning(view.executionId, Attribution.CONFIRMED)
        return view.executionId
    }

    @Test
    fun adapterStopMarksTheExecutionUnknownThroughTheContainerListener() {
        val container = container()
        val id = runningExecution(container, "p-stop")

        container.executionEventAdapter.stop("p-stop")

        val view = container.executionController.executions.value[id]!!
        assertEquals(ExecutionLifecycle.UNKNOWN, view.lifecycle)
        assertEquals("invalidated:adapter.stop", view.reasonCode)
    }

    @Test
    fun adapterCloseMarksTheExecutionUnknownThroughTheContainerListener() {
        val container = container()
        val id = runningExecution(container, "p-close")

        container.executionEventAdapter.close("p-close")

        val view = container.executionController.executions.value[id]!!
        assertEquals(ExecutionLifecycle.UNKNOWN, view.lifecycle)
        assertEquals("invalidated:adapter.close", view.reasonCode)
    }

    @Test
    fun bindingSwitchWithRunInFlightDoesNotCancelTheExecution() {
        val container = container()
        val key = "p-switch"
        val id = runningExecution(container, key)
        // The real ChatViewModel sets this marker at send; the binding guard
        // reads it before stopping an outgoing engine.
        container.reportRunInFlight(key, true)

        container.setActiveChatPool()

        val view = container.executionController.executions.value[id]!!
        assertEquals(ExecutionLifecycle.RUNNING, view.lifecycle)
        assertFalse(view.isTerminal)
    }

    @Test
    fun invalidationBumpsThePerInstanceEpoch() {
        val container = container()
        val id = runningExecution(container, "p-epoch")
        val before = container.executionController.executions.value[id]!!.epoch

        container.executionEventAdapter.stop("p-epoch")

        val after = container.executionController.executions.value[id]!!.epoch
        assertEquals("epoch must advance on invalidation", before + 1, after)
    }
}
