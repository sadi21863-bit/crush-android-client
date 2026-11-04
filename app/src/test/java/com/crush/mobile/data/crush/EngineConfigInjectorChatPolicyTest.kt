package com.crush.mobile.data.crush

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
 * Pins the injector's file semantics the same way [EngineConfigInjectorTest]
 * pins the provider-model merge: idempotent bytes, unknown keys preserved,
 * malformed files never rewritten (a rewrite could delete the engine's
 * stored provider key).
 */
class EngineConfigInjectorChatPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun inject(dir: File): EngineConfigInjector.SyncResult {
        val r = EngineConfigInjector.injectChatToolPolicy(dir)
        return r
    }

    private fun file(dir: File): File = File(dir, "crush.json")

    private fun tools(jsonText: String): List<String> {
        val options = JsonParser.parseString(jsonText).asJsonObject
            .getAsJsonObject("options")
            ?: return emptyList()
        val arr = options.getAsJsonArray("disabled_tools") ?: return emptyList()
        return arr.map { it.asString }
    }

    @Test
    fun `creates the deny list when no config file exists yet`() {
        val dir = tmp.newFolder()
        val r = inject(dir)
        assertTrue("first injection must report changed", r.changed)
        assertTrue(
            "every built-in in the deny list must land",
            tools(file(dir).readText()).containsAll(EngineConfigInjector.CHAT_DISABLED_TOOLS)
        )
    }

    @Test
    fun `second injection is byte-stable and idempotent`() {
        val dir = tmp.newFolder()
        inject(dir)
        val bytes = file(dir).readText()
        val r = inject(dir)
        assertFalse("re-injecting the same policy must not rewrite", r.changed)
        assertEquals(
            "the file must be unchanged by a redundant injection",
            bytes,
            file(dir).readText()
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
        inject(dir)
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

    @Test
    fun `a malformed file is never rewritten`() {
        val dir = tmp.newFolder()
        val broken = "{\"providers\": { \"opencode-go\": {\"api_key\": "
        file(dir).writeText(broken)
        val r = inject(dir)
        assertFalse("malformed input must refuse to write", r.changed)
        assertEquals(
            "the on-disk bytes must stay exactly as they were",
            broken,
            file(dir).readText()
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
        inject(dir)
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
