package com.crush.mobile.engine

import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.TuningPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * V2-15 Phase C/L4: a constructed-then-stopped runtime must hold no live child
 * and no active supervision loop. This is the JVM-checkable half of the
 * retained-resource claim; a real started child and its PSS remain device work.
 */
@RunWith(RobolectricTestRunner::class)
class EngineRuntimeLifecycleTest {

    @Test
    fun `a stopped supervisor has no active loop or child`() {
        val context = RuntimeEnvironment.getApplication()
        val engine = CrushEngine(context, "p-v2-15-runtime", idleTimeoutSec = 60)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val supervisor = EngineSupervisor(
            engine = engine,
            scope = scope,
            tuning = TuningPolicy.resolve(
                DeviceProfile(
                    apiLevel = 29,
                    totalRamMb = 4096,
                    availableRamMb = 2048,
                    cores = 8,
                    isLowRamDevice = false,
                    hasStrongBox = false,
                    hasSecureLockScreen = false,
                    supportsModernAuthGate = false,
                ),
                TuningPolicy.Overrides(),
            ),
            appContext = context,
            instanceKey = "p-v2-15-runtime",
        )

        assertFalse("constructed, never started: no loop", supervisor.hasActiveSupervisionForTest())
        assertFalse("constructed, never started: no child", engine.isAlive())

        supervisor.stop()

        assertEquals(EngineState.Stopped, supervisor.state.value)
        assertFalse("stop leaves no active supervision loop", supervisor.hasActiveSupervisionForTest())
        assertFalse("stop leaves no child", engine.isAlive())
    }
}
