package com.crush.mobile.di

import android.app.Application
import com.crush.mobile.engine.EngineInvalidationProbe
import com.crush.mobile.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * V2-15 Phase C/D: the container-level half of L1 that the plan-level
 * [ShutdownPlanTest] could not prove — that `buildShutdownPlan()` really walks
 * the registry and includes the legacy supervisor.
 *
 * No engine is started here (JVM cannot execute the bundled arm64 binary). The
 * assertion is the plan's observable result: the stop count and each
 * supervisor's terminal state. `shutdown()` returns the number of completed
 * stop actions, which is exactly one per snapshotted supervisor.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: the manifest's CrushMobileApp would build a second
// container and its DataStore read can deadlock a paused Robolectric looper
// (same reason as AppContainerBindingTest).
@Config(application = Application::class)
class AppContainerShutdownTest {

    @Test
    fun `shutdown snapshots the legacy supervisor and every registered project once`() {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        // A1: observe the container's real listener without replacing its
        // behavior; the probe is NOT a no-op once an AppContainer exists.
        val installed = EngineInvalidationProbe.listener
        val labels = mutableListOf<Pair<String, String>>()
        EngineInvalidationProbe.listener = { source, key ->
            labels += source to key
            installed?.invoke(source, key)
        }
        val legacy = container.engineSupervisor // force the lazy legacy singleton
        val p1 = container.runtimeFor("p1")
        val p2 = container.runtimeFor("p2")
        // A repeated lookup must not add a second stop for the same key.
        assertSame("runtimeFor is idempotent per key", p1, container.runtimeFor("p1"))

        assertEquals("legacy + p1 + p2", 3, container.shutdown())

        assertEquals(EngineState.Stopped, legacy.state.value)
        assertEquals(EngineState.Stopped, p1.supervisor.state.value)
        assertEquals(EngineState.Stopped, p2.supervisor.state.value)
        assertFalse("legacy supervision loop is gone", legacy.hasActiveSupervisionForTest())
        assertFalse("p1 supervision loop is gone", p1.supervisor.hasActiveSupervisionForTest())
        assertFalse("p2 supervision loop is gone", p2.supervisor.hasActiveSupervisionForTest())
        assertFalse("p1 holds no child", p1.engine.isAlive())
        assertFalse("p2 holds no child", p2.engine.isAlive())

        assertEquals("the shutdown plan is one-shot", 0, container.shutdown())

        assertEquals(
            "shutdown must fire for each project key",
            true,
            labels.any { it.first == "shutdown" && it.second == "p1" } &&
                labels.any { it.first == "shutdown" && it.second == "p2" },
        )
        assertEquals(
            "shutdown must fire for the legacy key",
            true,
            labels.any { it.first == "shutdown" && it.second == "" },
        )
        assertEquals(
            "each shutdown also reaches supervisor.stop",
            true,
            labels.count { it.first == "supervisor.stop" } >= 3,
        )
        EngineInvalidationProbe.listener = installed
    }
}
