package com.crush.mobile.engine

import android.content.Context
import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.TuningPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

/**
 * V2-15 post-push audit regression: a terminal start failure must release the
 * egress bridge and its per-start scope even when no child process was ever
 * assigned (`process == null`).
 *
 * This drives the REAL chain: [CrushEngine.start] starts its [EngineProxy],
 * the injected spawn failure returns `Failed(willRetry = false)`, and
 * [EngineSupervisor.supervise]'s terminal branch must call
 * [CrushEngine.stopBlocking]. It deliberately does not call `stopBlocking`
 * itself, unlike [EngineStopCleanupTest], which only proves the helper.
 */
@RunWith(RobolectricTestRunner::class)
class EngineTerminalStartupCleanupTest {

    @Test
    fun `terminal exec failure after proxy startup releases the bridge`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val key = "p-v2-15-terminal"
        installFakeBinary(context)

        val engine = CrushEngine(context, key, idleTimeoutSec = 60)
        assertTrue(
            "fixture: the fake binary must pass CrushEngine's binary gate",
            engine.isBinaryUsable,
        )

        var bridgeWasUp = false
        engine.processStarter = {
            // The bridge is started before exec in CrushEngine.start(); record
            // that it was really listening at the injected failure point.
            bridgeWasUp = engine.isEgressProxyRunningForTest
            throw IOException("injected exec failure")
        }

        val supervisor = EngineSupervisor(
            engine = engine,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            tuning = tuning(),
            appContext = context,
            instanceKey = key,
        )

        supervisor.start()

        val terminal = withTimeout(10_000) {
            supervisor.state.first { it is EngineState.Failed && !it.willRetry }
        } as EngineState.Failed

        assertTrue(
            "precondition: the egress bridge was listening before exec failed",
            bridgeWasUp,
        )
        assertTrue(
            "terminal state must report the exec failure: ${terminal.reason}",
            terminal.reason.contains("Could not launch engine"),
        )
        assertFalse("no child process was assigned", engine.isAlive())
        assertFalse(
            "terminal failure must release the proxy listener and its scope",
            engine.isEgressProxyRunningForTest,
        )

        // The terminal branch publishes state after cleanup, then the loop
        // returns; wait for the job to settle before the test ends.
        withTimeout(5_000) {
            while (supervisor.hasActiveSupervisionForTest()) delay(10)
        }
    }

    /**
     * Robolectric has no extracted jniLib; point the engine at a fake file that
     * passes `exists && canExecute`. The spawn itself is injected, so the file
     * content is irrelevant.
     */
    private fun installFakeBinary(context: Context) {
        val nativeDir = File(context.cacheDir, "v2-15-terminal-native").apply { mkdirs() }
        val binary = File(nativeDir, "libcrush.so").apply {
            writeText("stub: the test injects the spawn result\n")
            setExecutable(true)
        }
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        assertTrue("fixture: fake engine binary is executable", binary.canExecute())
    }

    private fun tuning(): TuningPolicy.Resolved = TuningPolicy.resolve(
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
    )
}
