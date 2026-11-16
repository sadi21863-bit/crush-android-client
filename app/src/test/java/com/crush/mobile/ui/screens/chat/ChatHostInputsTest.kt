package com.crush.mobile.ui.screens.chat

import com.crush.mobile.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2A-03 (V2 plan §5.2.1, Appendix C.1): one coherent binding/engine snapshot.
 *
 * The rule under test is not "the UI usually looks right"; it is that `Ready`
 * is unreachable while the binding's instance key and the engine sample's key
 * disagree, including the initial `stateIn` seed.
 */
class ChatHostInputsTest {

    /** Every EngineState value the projection can be handed (Appendix C.1). */
    private val engineStates = listOf(
        EngineState.Stopped,
        EngineState.Starting(attempt = 2),
        EngineState.Running(pid = 1, port = 2, version = "0.100.0", bootMs = 10),
        EngineState.Failed(reason = "boom"),
        EngineState.Failed(reason = "boom", willRetry = false),
    )

    @Test
    fun `the initial host seed is safe and never ready`() {
        val host = ChatHostState.initial()

        assertFalse(host.inputs.snapshotReady)
        assertEquals(BindingKind.Legacy, host.inputs.binding)
        assertEquals("", host.inputs.instanceKey)
        assertEquals("", host.inputs.engineStateKey)
        assertEquals(EngineReadiness.Starting, host.inputs.readiness(false, false))
        assertEquals(EngineReadiness.Starting, host.toViewState().run.readiness)
        assertTrue(host.toViewState().run.readiness != EngineReadiness.Ready)
    }

    /**
     * Flow 23 (flow-23 cold-start race): the seed cannot start the supervisor,
     * even though `AppSettings()` would resolve AUTO to prewarm on this device.
     * Only a coherent snapshot whose persisted setting says On may fire.
     */
    @Test
    fun `prewarm cannot fire before the coherent snapshot lands`() {
        val seed = ChatHostState.initial().inputs
        assertFalse(seed.prewarmAtComposition)
        assertFalse(
            "a default AUTO prewarm on the seed must not start the engine",
            seed.copy(prewarm = true).prewarmAtComposition
        )
        assertFalse(seed.copy(snapshotReady = true, prewarm = false).prewarmAtComposition)
        assertTrue(seed.copy(snapshotReady = true, prewarm = true).prewarmAtComposition)
    }

    @Test
    fun `the initial Legacy binding keeps its surface label`() {
        val view = ChatHostState.initial().toViewState()

        assertEquals("New chat", view.session.label)
        assertEquals(BindingKind.Legacy, view.binding.kind)
        assertEquals("Workspace (legacy)", view.session.surface)
    }

    @Test
    fun `the first coherent Legacy snapshot is starting until the engine is ready`() {
        val coherent = ChatHostInputs(
            binding = BindingKind.Legacy,
            instanceKey = "",
            engineStateKey = "",
            engineState = EngineState.Stopped,
            snapshotReady = true,
        )

        assertEquals(EngineReadiness.Starting, coherent.readiness(false, false))

        val ready = ChatUiState(engineReady = true).toViewState(coherent)
        assertEquals(EngineReadiness.Ready, ready.run.readiness)
    }

    @Test
    fun `a project switch is switching until the new key reports`() {
        engineStates.forEach { state ->
            val switching = ChatHostInputs(
                binding = BindingKind.Project,
                projectLabel = "B",
                instanceKey = "pB",
                engineStateKey = "pA",
                engineState = state,
                snapshotReady = true,
            )

            // A's sample is never shown as B's, whatever A was doing.
            assertEquals("state=$state", EngineReadiness.Switching, switching.readiness(true, true))
            assertEquals(
                "state=$state",
                EngineReadiness.Switching,
                ChatUiState(engineReady = true, recoverable = true).toViewState(switching).run.readiness,
            )
            assertEquals("pB", switching.instanceKey)
            assertEquals(
                "Project - B",
                ChatUiState(engineReady = true).toViewState(switching).session.surface,
            )

            val settled = switching.copy(engineStateKey = "pB")
            assertEquals("state=$state", EngineReadiness.Ready, settled.readiness(true, false))
        }
    }

    @Test
    fun `a same-key binding update does not flap readiness`() {
        val ready = ChatHostInputs(
            binding = BindingKind.Project,
            projectLabel = "Project A",
            instanceKey = "pA",
            engineStateKey = "pA",
            engineState = EngineState.Running(pid = 1, port = 2, version = "0.100.0", bootMs = 10),
            snapshotReady = true,
        )
        assertEquals(EngineReadiness.Ready, ready.readiness(true, false))

        val renamed = ready.copy(projectLabel = "Project A renamed")
        assertEquals(EngineReadiness.Ready, renamed.readiness(true, false))
        assertEquals("Project - Project A renamed", renamed.surfaceLabel)
    }

    @Test
    fun `a mismatched key is switching for every engine state`() {
        engineStates.forEach { state ->
            val inputs = ChatHostInputs(
                binding = BindingKind.Project,
                projectLabel = "B",
                instanceKey = "pB",
                engineStateKey = "pA",
                engineState = state,
                snapshotReady = true,
            )
            assertEquals("state=$state", EngineReadiness.Switching, inputs.readiness(true, true))
            assertEquals(
                "state=$state",
                EngineReadiness.Switching,
                ChatUiState(engineReady = true, recoverable = true).toViewState(inputs).run.readiness,
            )
        }

        // Appendix C.1 transition sequence: 50 coherent updates, each read
        // against the PREVIOUS update's sample. Consecutive bindings always
        // resolve to different keys here, so every read is a mismatch and no
        // read may claim Ready.
        val keys = mapOf(
            BindingKind.Legacy to "",
            BindingKind.ChatPool to "chat",
            BindingKind.Project to "p1",
        )
        val updates = List(50) { i ->
            val kind = BindingKind.entries[i % BindingKind.entries.size]
            kind to keys.getValue(kind)
        }

        updates.forEachIndexed { i, (kind, key) ->
            val staleKey = updates[(i + updates.size - 1) % updates.size].second
            val inputs = ChatHostInputs(
                binding = kind,
                projectLabel = "P",
                instanceKey = key,
                engineStateKey = staleKey,
                engineState = engineStates[i % engineStates.size],
                snapshotReady = true,
            )
            assertEquals(
                "update=$i binding=$kind key=$key stale=$staleKey",
                EngineReadiness.Switching,
                inputs.readiness(engineReady = true, recoverable = true),
            )
        }
    }
}
