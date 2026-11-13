package com.crush.mobile.data.crush

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The bug class this prevents, measured live on device:
 *
 * The engine's catalogue for `groq` - both the embedded snapshot and the live
 * Catwalk server on 2026-10-17 - carried only `moonshotai/kimi-k2-instruct-0905`
 * and `qwen/qwen3-32b`, both of which the real Groq API answers 404 for. The
 * app probed a live model fine and the engine then refused it with
 * "large model not found in provider". The engine merges `providers.<id>.models`
 * from its config file over its catalogue, so this injector is the bridge.
 *
 * Hard constraints those tests pin:
 * - the file may also contain the raw API key - a rewrite MUST preserve
 *   every sibling field (an earlier draft would have dropped it silently);
 * - the write must be idempotent, because it runs on every engine start;
 * - a half-parsable file must be LEFT ALONE rather than truncated.
 */
class EngineConfigInjectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val groq = EngineConfigInjector.providerCatalog("groq")

    private fun sync(dir: java.io.File) =
        EngineConfigInjector.syncProviderModels(dir, "groq", groq)

    @Test
    fun `creates the file with the full ordered list when none exists`() {
        val dir = tmp.newFolder("fresh")
        val r = sync(dir)
        assertTrue(r.changed)
        assertEquals(listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b", "allam-2-7b"), r.ids)
        val json = JsonParser.parseString(java.io.File(dir, "crush.json").readText())
        val models = json.asJsonObject.getAsJsonObject("providers")
            .getAsJsonObject("groq").getAsJsonArray("models")
        assertEquals(4, models.size())
        assertEquals("openai/gpt-oss-120b", models[0].asJsonObject.get("id").asString)
    }

    @Test
    fun `preserves the provider key and unrelated providers when merging`() {
        val dir = tmp.newFolder("keep")
        java.io.File(dir, "crush.json").writeText(
            """
            {"providers":{"groq":{"api_key":"RAW-KEY-IN-FILE"},"zen":{"api_key":"other"}},
             "models":{"large":{"model":"x"}}}
            """.trimIndent()
        )
        sync(dir)
        val json = JsonParser.parseString(java.io.File(dir, "crush.json").readText()).asJsonObject
        val groqObj = json.getAsJsonObject("providers").getAsJsonObject("groq")
        assertEquals("RAW-KEY-IN-FILE", groqObj.get("api_key").asString)
        val zen = json.getAsJsonObject("providers").getAsJsonObject("zen")
        assertEquals("other", zen.get("api_key").asString)
        assertTrue(json.getAsJsonObject("models").has("large"))
    }

    @Test
    fun `second identical call changes nothing`() {
        val dir = tmp.newFolder("idem")
        val first = sync(dir)
        assertTrue(first.changed)
        val bytes = java.io.File(dir, "crush.json").readBytes()
        val second = sync(dir)
        assertFalse("re-running on an already-synced file must be a no-op", second.changed)
        assertTrue(bytes.contentEquals(java.io.File(dir, "crush.json").readBytes()))
    }

    @Test
    fun `a write during the engine's live window is refused and the file is untouched`() {
        // Decision item 4's single-writer contract (docs/key-path-single-writer-design.md):
        // the ONLY legal write window for crush.json is "engine down". A caller
        // that arrives out of window must DEFER, not race - the engine holds the
        // file open in its own write cycles and a lost update silently deletes
        // keys or models.
        val dir = tmp.newFolder("alive")
        assertTrue(sync(dir).changed)
        val file = java.io.File(dir, "crush.json")
        val before = file.readBytes()
        val refused = EngineConfigInjector.syncProviderModels(dir, "groq", groq, engineRunning = true)
        assertFalse("an out-of-window write must not claim success", refused.changed)
        assertTrue("out-of-window write must not state ids", refused.ids.isEmpty())
        assertTrue("file must survive the refusal byte-for-byte", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `a malformed file is left alone, not truncated`() {
        val dir = tmp.newFolder("bad")
        val broken = "{providers: sintaxe"
        java.io.File(dir, "crush.json").writeText(broken)
        val r = sync(dir)
        assertFalse("must not claim success over a file it could not parse", r.changed)
        assertEquals(
            "the broken file must survive byte-for-byte",
            broken,
            java.io.File(dir, "crush.json").readText()
        )
    }

    @Test
    fun `empty override list touches nothing`() {
        val dir = tmp.newFolder("none")
        val r = EngineConfigInjector.syncProviderModels(dir, "groq", emptyList())
        assertFalse(r.changed)
        assertFalse(java.io.File(dir, "crush.json").exists())
    }

    @Test
    fun `allam's 4096 completion cap is encoded, not the 8192 default`() {
        val allam = groq.first { it.id == "allam-2-7b" }
        assertEquals(4096, allam.defaultMaxTokens)
        val oss = groq.first { it.id == "openai/gpt-oss-120b" }
        assertEquals(8192, oss.defaultMaxTokens)
        assertEquals(131072, oss.contextWindow)
        assertTrue(oss.canReason)
        assertEquals(listOf("low", "medium", "high"), oss.reasoningEfforts)
    }

    /**
     * #11 stage 1. Engine v0.100.0 reads catwalk v3's nested schema:
     * `capabilities.vision` and `reasoning.thinking` / `effort_levels` /
     * `default_effort_level`. The flat keys this injector used to write are
     * ignored by that engine, so an injected reasoning model lost its levels
     * and a future vision model would have been silently text-only.
     */
    @Test
    fun `injected specs use the engine v0_100 nested capabilities and reasoning schema`() {
        val dir = tmp.newFolder("catwalk-v3")
        val spec = EngineConfigInjector.ModelSpec(
            id = "vision/model",
            displayName = "Vision Model",
            contextWindow = 32_000,
            defaultMaxTokens = 4096,
            canReason = true,
            reasoningEfforts = listOf("low", "high"),
            defaultReasoningEffort = "low",
            supportsVision = true
        )
        EngineConfigInjector.syncProviderModels(dir, "test-provider", listOf(spec))
        val model = JsonParser.parseString(java.io.File(dir, "crush.json").readText())
            .asJsonObject.getAsJsonObject("providers")
            .getAsJsonObject("test-provider").getAsJsonArray("models")[0].asJsonObject

        val caps = model.getAsJsonObject("capabilities")
        assertTrue("v0.100.0 must see capabilities.vision", caps.get("vision").asBoolean)
        assertFalse(caps.get("audio").asBoolean)

        val reasoning = model.getAsJsonObject("reasoning")
        assertEquals("toggleable", reasoning.get("thinking").asString)
        assertEquals("low", reasoning.get("default_effort_level").asString)
        val levels = reasoning.getAsJsonArray("effort_levels")
        assertEquals(2, levels.size())
        assertEquals("low", levels[0].asJsonObject.get("value").asString)
        assertEquals("Low", levels[0].asJsonObject.get("display").asString)
        assertEquals("high", levels[1].asJsonObject.get("value").asString)

        // Legacy v0.99 keys carry the SAME facts, so a downgrade cannot
        // disagree about vision or reasoning.
        assertTrue(model.get("supports_images").asBoolean)
        assertTrue(model.get("can_reason").asBoolean)
        assertEquals("low", model.get("default_reasoning_effort").asString)
        assertEquals(
            listOf("low", "high"),
            model.getAsJsonArray("reasoning_levels").map { it.asString }
        )
    }

    /**
     * The display label is part of the config the engine unmarshals, so it
     * must match catwalk v3.0.1's `effortLevelDisplay` exactly: uppercase the
     * first letter, with `xhigh` spelled `X-High` upstream. A naive
     * title-case shows `Xhigh`, which the engine would render verbatim.
     */
    @Test
    fun `effort level display labels match catwalk v3 derivation`() {
        val dir = tmp.newFolder("catwalk-v3-display")
        EngineConfigInjector.syncProviderModels(
            dir,
            "test-provider",
            listOf(
                EngineConfigInjector.ModelSpec(
                    id = "reasoning/model",
                    displayName = "Reasoning Model",
                    contextWindow = 32_000,
                    defaultMaxTokens = 4096,
                    canReason = true,
                    reasoningEfforts = listOf("minimal", "high", "xhigh"),
                    defaultReasoningEffort = "xhigh"
                )
            )
        )
        val reasoning = JsonParser.parseString(java.io.File(dir, "crush.json").readText())
            .asJsonObject.getAsJsonObject("providers")
            .getAsJsonObject("test-provider").getAsJsonArray("models")[0].asJsonObject
            .getAsJsonObject("reasoning")
        assertEquals(
            listOf("Minimal", "High", "X-High"),
            reasoning.getAsJsonArray("effort_levels").map {
                it.asJsonObject.get("display").asString
            }
        )
        assertEquals("xhigh", reasoning.get("default_effort_level").asString)
    }

    @Test
    fun `a non-reasoning injected model says so explicitly in both schemas`() {
        val dir = tmp.newFolder("catwalk-v3-noreason")
        EngineConfigInjector.syncProviderModels(
            dir,
            "test-provider",
            listOf(
                EngineConfigInjector.ModelSpec(
                    id = "plain/model",
                    displayName = "Plain",
                    contextWindow = 8_000,
                    defaultMaxTokens = 2048
                )
            )
        )
        val model = JsonParser.parseString(java.io.File(dir, "crush.json").readText())
            .asJsonObject.getAsJsonObject("providers")
            .getAsJsonObject("test-provider").getAsJsonArray("models")[0].asJsonObject
        assertEquals("never", model.getAsJsonObject("reasoning").get("thinking").asString)
        assertFalse(model.has("can_reason"))
        assertFalse(model.getAsJsonObject("capabilities").get("vision").asBoolean)
        assertFalse(model.get("supports_images").asBoolean)
    }

    /**
     * P2-21: the frozen 2026-10-17 table used to write cost_per_1m_* = 0,
     * which ZenModelsApi then read as "known free" - a paid model labelled
     * free by a stale spreadsheet. The fix is ABSENCE, not a corrected
     * number: absence can be filled by live catalogue discovery, an explicit
     * 0 has to be actively overwritten. Pin that no cost key ever comes back.
     */
    @Test
    fun `injected models claim NO cost - zero must never be written as a free claim`() {
        val dir = tmp.newFolder("no-cost")
        sync(dir)
        val models = JsonParser.parseString(java.io.File(dir, "crush.json").readText())
            .asJsonObject.getAsJsonObject("providers")
            .getAsJsonObject("groq").getAsJsonArray("models")
        for (m in models) {
            val o = m.asJsonObject
            for (key in listOf(
                "cost_per_1m_input_tokens",
                "cost_per_1m_output_tokens",
                "cost_per_1m_cached_input_tokens"
            )) {
                assertFalse(
                    "$key must be absent: a frozen-table 0 reads as a known-free claim",
                    o.has(key)
                )
            }
        }
    }

    @Test
    fun `unknown provider gets no override`() {
        assertTrue(EngineConfigInjector.providerCatalog("does-not-exist").isEmpty())
    }
}
