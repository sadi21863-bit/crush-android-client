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

    @Test
    fun `unknown provider gets no override`() {
        assertTrue(EngineConfigInjector.providerCatalog("does-not-exist").isEmpty())
    }
}
