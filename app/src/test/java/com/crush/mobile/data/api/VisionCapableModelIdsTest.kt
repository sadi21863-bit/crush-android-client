package com.crush.mobile.data.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #11 stage 1: which models the ENGINE says can see.
 *
 * Engine v0.100.0 (catwalk v3.0.1) serializes `capabilities.vision`. Older
 * configs wrote a flat `supports_images`. The reader accepts either, but only
 * an explicit `true` counts: an attachment UI must never send a picture to a
 * text-only model, because the engine drops it silently and the user pays for
 * a turn the model never saw.
 *
 * Pure JVM: JsonObjects built in the test, no client or key.
 */
class VisionCapableModelIdsTest {

    private fun model(id: String, json: String = "{}"): JsonObject {
        val m = JsonParser.parseString(json).asJsonObject
        m.addProperty("id", id)
        return m
    }

    private fun provider(id: String, models: List<JsonObject>?): JsonObject {
        val p = JsonObject()
        p.addProperty("id", id)
        if (models != null) {
            val arr = JsonArray()
            models.forEach { arr.add(it) }
            p.add("models", arr)
        }
        return p
    }

    @Test
    fun `nested capabilities vision is read`() {
        val providers = listOf(
            provider(
                "opencode-go",
                listOf(
                    model("sees-images", """{"capabilities":{"vision":true,"audio":false}}"""),
                    model("text-only", """{"capabilities":{"vision":false,"audio":false}}""")
                )
            )
        )
        assertEquals(
            setOf("sees-images"),
            ZenModelsApi.visionCapableModelIds(providers, "opencode-go")
        )
    }

    @Test
    fun `legacy supports_images is still honored`() {
        val providers = listOf(
            provider(
                "groq",
                listOf(
                    model("old-vision", """{"supports_images":true}"""),
                    model("old-text", """{"supports_images":false}""")
                )
            )
        )
        assertEquals(
            setOf("old-vision"),
            ZenModelsApi.visionCapableModelIds(providers, "groq")
        )
    }

    @Test
    fun `absence is not capability`() {
        val providers = listOf(
            provider(
                "opencode-go",
                listOf(
                    model("no-capabilities"),
                    model("empty-capabilities", """{"capabilities":{}}""")
                )
            )
        )
        assertTrue(
            ZenModelsApi.visionCapableModelIds(providers, "opencode-go").isEmpty()
        )
    }

    @Test
    fun `malformed capabilities cannot throw or invent capability`() {
        val providers = listOf(
            provider(
                "opencode-go",
                listOf(
                    model("capabilities-is-a-string", """{"capabilities":"yes"}"""),
                    model("vision-is-a-string", """{"capabilities":{"vision":"true"}}"""),
                    model("vision-is-null", """{"capabilities":{"vision":null}}""")
                )
            )
        )
        assertTrue(
            ZenModelsApi.visionCapableModelIds(providers, "opencode-go").isEmpty()
        )
    }

    @Test
    fun `missing provider or models block yields nothing`() {
        val providers = listOf(provider("opencode-zen", listOf(model("x"))))
        assertTrue(ZenModelsApi.visionCapableModelIds(providers, "opencode-go").isEmpty())
        assertTrue(
            ZenModelsApi.visionCapableModelIds(
                listOf(provider("opencode-go", null)),
                "opencode-go"
            ).isEmpty()
        )
    }
}
