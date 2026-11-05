package com.crush.mobile.engine

import com.crush.mobile.data.crush.EngineConfigInjector
import com.crush.mobile.data.crush.EngineConfigInjector.ChatPolicyReadback
import com.crush.mobile.data.crush.EngineConfigInjector.ChatPolicyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The chat pool's tool-policy spawn gate (S-01/P0-2, 2026-11-05): the pure
 * pre-exec verdict over [com.crush.mobile.data.crush.EngineConfigInjector.injectChatToolPolicy]'s
 * typed outcome, mirroring [CrushEngineSpawnVetoTest]'s shape for the
 * MAY-SPAWN birth interlock ([spawnVetoed]).
 *
 * WHAT IS AND IS NOT UNIT-COVERED - read before assuming more:
 *
 * - Covered here: the pure gate decision ([chatPolicyGate]) for every policy
 *   outcome - both readback-verified outcomes proceed, a
 *   [ChatPolicyResult.PolicyFailure] fails fast with the reason carried
 *   VERBATIM (it reaches EngineState.Failed and the instance-key-prefixed
 *   AppLog line) - plus one WIRED pair through the real injector against real
 *   files (malformed -> PolicyFailure -> FailFast with the bytes untouched;
 *   missing file -> PolicyInstalled -> Proceed with the on-disk readback
 *   Verified).
 *
 * - NOT unit-covered, honestly: the code inside [CrushEngine.start] that ACTS
 *   on the verdict - the actual ProcessBuilder skip, the egress-bridge
 *   cleanup, the Log/AppLog failure pair and the
 *   EngineState.Failed(willRetry=true) return. Building a CrushEngine needs an
 *   Android Context and java.lang.Process cannot be faked on a plain JVM (the
 *   same reasons EngineSocketsHostnameTest documents for staying
 *   Robolectric-free). Those paths are device-drive territory; the pure
 *   decision is pinned here, the injector's typed outcomes in
 *   EngineConfigInjectorChatPolicyTest.
 */
class ChatPolicySpawnGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun failFast(verdict: ChatPolicyDecision): ChatPolicyDecision.FailFast {
        assertTrue("expected FailFast, got $verdict", verdict is ChatPolicyDecision.FailFast)
        return verdict as ChatPolicyDecision.FailFast
    }

    // (spec f) Pure mapping: both verified outcomes let the chat pool exec.
    @Test
    fun `an installed policy lets the chat pool proceed`() {
        assertTrue(
            chatPolicyGate(ChatPolicyResult.PolicyInstalled(true, listOf("bash")))
                is ChatPolicyDecision.Proceed
        )
    }

    @Test
    fun `an already-verified policy lets the chat pool proceed`() {
        assertTrue(
            chatPolicyGate(
                ChatPolicyResult.AlreadyVerified(EngineConfigInjector.CHAT_DISABLED_TOOLS)
            ) is ChatPolicyDecision.Proceed
        )
    }

    // (spec f) A failure blocks the spawn and the reason surfaces verbatim:
    // it is what the honest EngineState.Failed and the AppLog line carry.
    @Test
    fun `a policy failure blocks the spawn and surfaces the reason verbatim`() {
        val reason = "readback after write disagrees: 1 of 22 deny ids missing"
        val fast = failFast(chatPolicyGate(ChatPolicyResult.PolicyFailure(reason)))
        assertEquals(
            "the reason must travel verbatim into the refusal",
            reason,
            fast.reason
        )
    }

    /**
     * (spec a, wired) A REAL malformed file through the REAL injector ends in
     * a FailFast verdict, and the S1 constraint survives the handoff: the
     * half-parsed file's bytes are exactly as they were.
     */
    @Test
    fun `a real malformed file flows to a fail-fast spawn block`() {
        val dir = tmp.newFolder()
        val file = File(dir, "crush.json")
        val broken = "{\"options\":{\"disabled_tools\":"
        file.writeText(broken)
        val fast = failFast(chatPolicyGate(EngineConfigInjector.injectChatToolPolicy(dir)))
        assertTrue(
            "the surfaced reason must be honest AND content-free",
            fast.reason.isNotBlank() && fast.reason.length < 300
        )
        assertEquals(
            "the malformed file must stay untouched through the gate's verdict",
            broken,
            file.readText()
        )
    }

    /**
     * (spec b, wired) A missing file through the REAL injector: PolicyInstalled
     * -> Proceed, and the readback seam confirms the policy out of the bytes
     * actually on disk (no harder claim than coverage of every required id).
     */
    @Test
    fun `a real missing-file install flows to proceed with the readback verified`() {
        val dir = tmp.newFolder()
        val verdict = chatPolicyGate(EngineConfigInjector.injectChatToolPolicy(dir))
        assertTrue(
            "an installed policy must proceed, got $verdict",
            verdict is ChatPolicyDecision.Proceed
        )
        assertEquals(
            "the installed policy must verify from disk",
            ChatPolicyReadback.Verified,
            EngineConfigInjector.verifyChatPolicyOnDisk(
                File(dir, "crush.json").readText(),
                EngineConfigInjector.CHAT_DISABLED_TOOLS
            )
        )
    }
}
