package com.crush.mobile.data.crush

import com.crush.mobile.data.crush.EngineConfigInjector.ChatPolicyReadback
import com.crush.mobile.data.crush.EngineConfigInjector.ChatPolicyResult
import com.crush.mobile.engine.ChatPolicyDecision
import com.crush.mobile.engine.chatPolicyGate
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The chat pool's spawn-time tool policy (S3, mechanism measured by the
 * 2026-11-05 S1 probe: the pinned v0.99.0 hides every entry of
 * `options.disabled_tools` from the agent).
 *
 * S-01/P0-2 (2026-11-05): the policy is now FAIL-CLOSED. The outcomes are
 * typed ([ChatPolicyResult]) - a malformed file, an unusable state dir, a
 * failed write or a readback disagreement is a
 * [ChatPolicyResult.PolicyFailure] the spawn gate refuses to exec on, NOT the
 * old silently-ignored "unchanged, carry on". Success is claimed only
 * VERIFIED-BY-READBACK: the file is re-read from disk
 * ([EngineConfigInjector.verifyChatPolicyOnDisk]) and every required deny id
 * must be present in it. Malformed files are never REWRITTEN either way - a
 * rewrite could delete the engine's stored provider key.
 *
 * Each failure test below also pins the gate handoff
 * ([chatPolicyGate] -> [ChatPolicyDecision.FailFast]) so the injector verdict
 * and the spawn decision cannot drift apart; the pure gate mapping lives in
 * ChatPolicySpawnGateTest (engine package, mirrors the spawn-veto tests).
 */
class EngineConfigInjectorChatPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun inject(dir: File): ChatPolicyResult =
        EngineConfigInjector.injectChatToolPolicy(dir)

    private fun installed(r: ChatPolicyResult): ChatPolicyResult.PolicyInstalled {
        assertTrue("expected PolicyInstalled, got $r", r is ChatPolicyResult.PolicyInstalled)
        return r as ChatPolicyResult.PolicyInstalled
    }

    private fun failure(r: ChatPolicyResult): ChatPolicyResult.PolicyFailure {
        assertTrue("expected PolicyFailure, got $r", r is ChatPolicyResult.PolicyFailure)
        return r as ChatPolicyResult.PolicyFailure
    }

    private fun unparsable(back: ChatPolicyReadback): ChatPolicyReadback.Unparsable {
        assertTrue("expected Unparsable, got $back", back is ChatPolicyReadback.Unparsable)
        return back as ChatPolicyReadback.Unparsable
    }

    private fun verifyOnDiskVerifies(dir: File) {
        assertEquals(
            "the policy must verify against the bytes actually on disk",
            ChatPolicyReadback.Verified,
            EngineConfigInjector.verifyChatPolicyOnDisk(
                file(dir).readText(),
                EngineConfigInjector.CHAT_DISABLED_TOOLS
            )
        )
    }

    private fun file(dir: File): File = File(dir, "crush.json")

    private fun tools(jsonText: String): List<String> {
        val options = JsonParser.parseString(jsonText).asJsonObject
            .getAsJsonObject("options")
            ?: return emptyList()
        val arr = options.getAsJsonArray("disabled_tools") ?: return emptyList()
        return arr.map { it.asString }
    }

    // (spec b) A missing file is fine: the policy installs, AND the outcome is
    // readback-verified from the bytes actually on disk.
    @Test
    fun `creates the deny list when no config file exists yet`() {
        val dir = tmp.newFolder()
        val r = installed(inject(dir))
        assertTrue("first injection must report a real write", r.changed)
        assertTrue(
            "every built-in in the deny list must land",
            r.tools.containsAll(EngineConfigInjector.CHAT_DISABLED_TOOLS)
        )
        verifyOnDiskVerifies(dir)
    }

    // (spec c) A byte-stable re-injection reports AlreadyVerified and churns
    // no bytes: second-call idempotence is a named outcome now, not a flag.
    @Test
    fun `second injection is byte-stable and reports the policy AlreadyVerified`() {
        val dir = tmp.newFolder()
        inject(dir)
        val bytes = file(dir).readBytes()
        val r = inject(dir)
        assertTrue(
            "an already-correct policy must be reported as AlreadyVerified, got $r",
            r is ChatPolicyResult.AlreadyVerified
        )
        assertTrue(
            "the verified report must still carry the full deny list",
            (r as ChatPolicyResult.AlreadyVerified).tools
                .containsAll(EngineConfigInjector.CHAT_DISABLED_TOOLS)
        )
        assertTrue(
            "the file must be unchanged by a redundant injection",
            bytes.contentEquals(file(dir).readBytes())
        )
    }

    @Test
    fun `provider keys are preserved when the policy lands`() {
        val dir = tmp.newFolder()
        file(dir).writeText(
            """
            {
              "models": {"large": {"provider": "opencode-go"}},
              "providers": {"opencode-go": {"api_key": "sk-not-a-real-key"}}
            }
            """.trimIndent()
        )
        assertTrue(inject(dir) is ChatPolicyResult.PolicyInstalled)
        val parsed = JsonParser.parseString(file(dir).readText()).asJsonObject
        assertTrue(
            "options must land beside the existing keys",
            parsed.getAsJsonObject("options").has("disabled_tools")
        )
        assertEquals(
            "the engine's stored provider entry must survive the policy merge",
            "sk-not-a-real-key",
            parsed.getAsJsonObject("providers").getAsJsonObject("opencode-go")
                .get("api_key").asString
        )
        assertTrue(
            "models must survive the policy merge",
            parsed.getAsJsonObject("models").has("large")
        )
    }

    // (spec a) A malformed file is a PolicyFailure, its bytes stay untouched,
    // and the spawn gate fails fast on it.
    @Test
    fun `a malformed file is a policy failure and is never rewritten`() {
        val dir = tmp.newFolder()
        val broken = "{\"providers\": { \"opencode-go\": {\"api_key\": "
        file(dir).writeText(broken)
        val r = failure(inject(dir))
        assertTrue(
            "the honest reason must say the file was not rewritten",
            r.reason.contains("never rewritten")
        )
        assertEquals(
            "the on-disk bytes must stay exactly as they were",
            broken,
            file(dir).readText()
        )
        assertTrue(
            "a malformed file must block the chat pool's spawn",
            chatPolicyGate(r) is ChatPolicyDecision.FailFast
        )
    }

    // A wrong-shaped EXISTING member (here: a bare-null "options") throws out
    // of the tree walk exactly like a half-parsed file - it must fail closed,
    // never pass and never be "repaired" away.
    @Test
    fun `an existing member of the wrong shape fails closed too`() {
        val dir = tmp.newFolder()
        val shape = "{\"options\": null, \"providers\": {}}"
        file(dir).writeText(shape)
        val r = failure(inject(dir))
        assertEquals("the untouched rule covers wrong shapes too", shape, file(dir).readText())
        assertTrue(
            "a wrong-shaped member must block the chat pool's spawn",
            chatPolicyGate(r) is ChatPolicyDecision.FailFast
        )
    }

    // (spec d) An unusable state dir is a PolicyFailure -> FailFast.
    //
    // Driven deterministically by a REGULAR FILE occupying the dir's path:
    // that is the SAME mkdirs-failure branch a truly unwritable dir hits, and
    // an OS read-only directory is not portable on the JVM here (NTFS ignores
    // the read-only flag for child-file writes). The real unwritable-dir case
    // is device-drive territory - recorded, not hidden.
    @Test
    fun `an unusable state dir is a policy failure, never a silent pass`() {
        val dir = tmp.newFolder()
        val occupied = File(dir, "state-dir-blocked")
        occupied.writeText("occupied")
        val r = failure(inject(occupied))
        assertTrue(
            "the honest reason must name the unavailable state dir",
            r.reason.contains("state dir")
        )
        assertFalse("the blocker must not be destroyed or repaired away", occupied.isDirectory)
        assertFalse("no config may exist under it", File(occupied, "crush.json").exists())
        assertTrue(
            "an unusable state dir must block the chat pool's spawn",
            chatPolicyGate(r) is ChatPolicyDecision.FailFast
        )
    }

    // The write-failure branch, driven portably by occupying the tmp sink with
    // a DIRECTORY: every write path throws deterministically on any OS. The
    // tmp-rename fallback itself is device territory (honest gap).
    @Test
    fun `a write that cannot land is a policy failure`() {
        val dir = tmp.newFolder()
        File(dir, "crush.json.tmp").mkdirs()
        val r = failure(inject(dir))
        assertFalse("no policy file may be claimed after a failed write", file(dir).exists())
        assertTrue(
            "the occupier must survive untouched (no auto-repair)",
            File(dir, "crush.json.tmp").isDirectory
        )
        assertTrue(
            "a failed write must block the chat pool's spawn",
            chatPolicyGate(r) is ChatPolicyDecision.FailFast
        )
    }

    @Test
    fun `existing disabled entries are unioned, not clobbered`() {
        val dir = tmp.newFolder()
        file(dir).writeText(
            """
            {"options": {"disabled_tools": ["sourcegraph"]}}
            """.trimIndent()
        )
        assertTrue(inject(dir) is ChatPolicyResult.PolicyInstalled)
        val after = tools(file(dir).readText())
        assertTrue("pre-existing denies must be kept", after.contains("sourcegraph"))
        assertTrue(
            "the policy list must still land",
            after.containsAll(EngineConfigInjector.CHAT_DISABLED_TOOLS)
        )
        assertEquals(
            "double entries must not appear",
            after.size,
            after.toSet().size
        )
    }

    // The v0.100.0 built-in catalogue SNAPSHOT (2026-11-09 ladder on branch
    // engine-v0100-ladder): the 29 ids the v0.99.0 parity pass enumerated
    // from upstream allToolNames() and strings-confirmed over the shipped
    // v0.99.0 libcrush.so, PLUS the four sub-agent tools the v0.100.0
    // release notes document (dispatch commits 965d1a7 + 938fef2).
    //
    // STRINGS-CONFIRMED 2026-11-09: the official
    // crush_0.100.0_Android_arm64.tar.gz (sha256 cdb15f23393c7e334b059a37d
    // 2c41a12fcb5f4d02d6e940795b7707e4b0afeaa, byte-verified against the
    // release's own checksums.txt) was unpacked out-of-tree and scanned -
    // ALL 33 ids above are present in the binary, the four agent tools
    // included, and no ADDITIONAL built-in tool id surfaced beyond them
    // (the probe id "agent_task" is absent). At fetch time the SAME
    // verification reruns over the in-tree libcrush.so the script writes;
    // any then-unforeseen new ids land in BOTH this snapshot and
    // CHAT_DISABLED_TOOLS before the device gate. ("task"/"diagnostics"
    // are inert extra ids the deny list also carries; they are not
    // engine-raised built-ins and stay out of this snapshot.)
    private val ENGINE_V0100_BUILTIN_TOOLS = listOf(
        "agent", "bash", "crush_info", "crush_logs", "job_output", "job_kill",
        "download", "edit", "multiedit", "lsp_call_hierarchy", "lsp_definition",
        "lsp_diagnostics", "lsp_references", "lsp_rename", "lsp_replace_symbol",
        "lsp_restart", "lsp_symbols", "fetch", "agentic_fetch", "glob", "grep",
        "ls", "question", "sourcegraph", "todos", "view", "write",
        "list_mcp_resources", "read_mcp_resource",
        // v0.100.0 sub-agent additions (release notes; strings-confirm DONE
        // 2026-11-09 - see the STRINGS-CONFIRMED note above).
        "agent_dispatch", "agent_send", "agent_status", "agent_stop",
    )

    // RATCHET: when tools/fetch-crush.ps1 moves the engine to a newer
    // version, FIRST re-diff that version's upstream allToolNames(), extend
    // BOTH this frozen list and CHAT_DISABLED_TOOLS, THEN bump the version.
    // This test fails whenever the Chat policy stops covering every id the
    // pinned engine documents - the S-01 contract is catalogue parity, not
    // "whatever the last drive happened to catch".
    //
    // 2026-11-09 LADDER STATE (engine-v0100-ladder): the version knob is
    // ALREADY at 0.100.0 (ladder step 1) while this ratchet's strings steps
    // are still pending - the fresh libcrush.so re-diff runs at fetch time
    // and MUST pass (with any additional new ids merged into BOTH lists)
    // BEFORE the device gate. Nothing runs on a device from this branch
    // until that close.
    @Test
    fun `the pinned v0100 upstream catalogue is fully denied`() {
        val missing = ENGINE_V0100_BUILTIN_TOOLS
            .filterNot(EngineConfigInjector.CHAT_DISABLED_TOOLS::contains)
        assertTrue(
            "CHAT_DISABLED_TOOLS is missing ids the pinned v0.100.0 engine " +
                "documents: $missing",
            missing.isEmpty()
        )
    }

    // (spec e) The readback verifier driven DIRECTLY over text - the seam that
    // is faked without a Context. The tamper shape is the pack-05 one: a
    // writer that strips ids out of the list.
    @Test
    fun `the readback verifier confirms a complete list and catches a stripped one`() {
        val full = EngineConfigInjector.CHAT_DISABLED_TOOLS
        val ok = JsonObject().apply {
            add("options", JsonObject().apply {
                add("disabled_tools", JsonArray().apply { full.forEach { add(it) } })
            })
        }
        assertEquals(
            ChatPolicyReadback.Verified,
            EngineConfigInjector.verifyChatPolicyOnDisk(ok.toString(), full)
        )
        val stripped = JsonObject().apply {
            // The tamper shape: a writer that drops ids out of the list (built
            // fresh rather than mutated, keeping the test off mutable
            // JsonArray APIs that vary across Gson generations).
            add("options", JsonObject().apply {
                add(
                    "disabled_tools",
                    JsonArray().apply { full.drop(1).forEach { add(it) } }
                )
            })
        }
        assertEquals(
            "a writer that strips ids must fail the readback, by count",
            ChatPolicyReadback.ShortList(missing = 1),
            EngineConfigInjector.verifyChatPolicyOnDisk(stripped.toString(), full)
        )
    }

    // (spec e) Every absent/broken shape fails closed - absence of coverage is
    // a failure, never an implicit pass.
    @Test
    fun `the readback verifier fails closed on absence and junk`() {
        val full = EngineConfigInjector.CHAT_DISABLED_TOOLS
        assertEquals(
            "no options object is a countable failure",
            ChatPolicyReadback.ShortList(missing = full.size),
            EngineConfigInjector.verifyChatPolicyOnDisk("{\"providers\":{}}", full)
        )
        assertEquals(
            "non-string entries are not tool ids and do not count as coverage",
            ChatPolicyReadback.ShortList(missing = full.size),
            EngineConfigInjector.verifyChatPolicyOnDisk(
                "{\"options\":{\"disabled_tools\":[5,null]}}", full
            )
        )
        assertTrue(
            "half-parsed text is unparsable, never verified",
            EngineConfigInjector.verifyChatPolicyOnDisk("{ nope", full)
                is ChatPolicyReadback.Unparsable
        )
        assertTrue(
            "a non-object root is unparsable, never verified",
            EngineConfigInjector.verifyChatPolicyOnDisk("[]", full)
                is ChatPolicyReadback.Unparsable
        )
        assertTrue(
            "a bare-null member is unparsable, never verified",
            EngineConfigInjector.verifyChatPolicyOnDisk("{\"options\":null}", full)
                is ChatPolicyReadback.Unparsable
        )
    }

    /**
     * (spec g) KEY-PROTECTION CONTRACT PIN - read the honest note first.
     *
     * The full rule is "this object never prints file content anywhere": it
     * is enforced BY CONSTRUCTION (the object contains zero log calls - the
     * engine layer logs outcomes, reasons only) and by the class KDoc staying
     * honest. Loggability itself cannot be asserted here: AppLog is a static
     * object with a masking hook, not a capture seam, and this object has no
     * logging dependency to stub. What IS pinned behaviorally is the contract
     * the log rule depends on: every outcome datum the injector can emit with
     * a keyed file in play must be content-free - never echoing the
     * (fake, sibling-test-style) key-shaped marker, never echoing the file,
     * and bounded in length so a reason line could not smuggle file content
     * by length.
     */
    @Test
    fun `failure reasons never echo file content that could carry a key`() {
        val dir = tmp.newFolder()
        val marker = "sk-not-a-real-key"
        // Parses far enough to place the marker, then cuts off mid-token.
        val broken = "{\"providers\": {\"zen\": {\"api_key\": \"$marker\"}"
        file(dir).writeText(broken)

        val r = failure(inject(dir))
        assertFalse("an injector reason must never carry the marker", r.reason.contains(marker))
        assertFalse("an injector reason must never echo the file", r.reason.contains(broken))
        assertTrue(
            "a reason must stay a bounded line, not a dump",
            r.reason.length < 200
        )

        val back = EngineConfigInjector.verifyChatPolicyOnDisk(
            broken, EngineConfigInjector.CHAT_DISABLED_TOOLS
        )
        val unparsable = unparsable(back)
        assertFalse("a readback cause must never carry the marker", unparsable.cause.contains(marker))
        assertTrue("a cause must stay a bare class name", unparsable.cause.length < 80)
    }

    @Test
    fun `the deny list covers the tools the agent actually tried to hide`() {
        // From the 2026-11-05 S1 probe: the model attempted bash and the
        // engine refused it. These names are the minimum the Chat pool must
        // hide for MODE-01-grade denies.
        val required = setOf("bash", "edit", "write", "fetch", "agentic_fetch", "view")
        assertTrue(
            "required tools missing from the deny list: " +
                (required - EngineConfigInjector.CHAT_DISABLED_TOOLS.toSet()),
            EngineConfigInjector.CHAT_DISABLED_TOOLS.containsAll(required)
        )
    }
}
