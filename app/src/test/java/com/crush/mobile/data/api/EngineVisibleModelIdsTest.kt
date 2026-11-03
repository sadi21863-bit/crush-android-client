package com.crush.mobile.data.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE filter that keeps engine-retired ids out of the picker and the probe
 * shortlist (user-reported 2026-11-03 catalog-drift incident, measured on
 * device). Pull request-shaped contract:
 *
 * - The engine /providers payload is the ONLY truth consulted.
 * - The provider's documented /models list may drift apart from it; ids
 *   absent in the engine-payload must be dropped.
 * - An EMPTY result means "the engine answered without a models block" - the
 *   caller must NOT filter then (a plumbing failure must not masquerade as an
 *   empty catalogue), so the function itself returns empty for that state and
 *   the callers log. This test pins BOTH behaviors.
 *
 * These are pure JVM tests: the function reads JsonObjects known at compile
 * time, no client or key is constructed.
 */
class EngineVisibleModelIdsTest {

    private fun provider(id: String, modelIds: List<String>?): JsonObject {
        val p = JsonObject()
        p.addProperty("id", id)
        if (modelIds != null) {
            val arr = JsonArray()
            modelIds.forEach { mid ->
                val m = JsonObject()
                m.addProperty("id", mid)
                m.addProperty("cost_per_1m_in", 1.5)
                m.addProperty("cost_per_1m_out", 4.0)
                arr.add(m)
            }
            p.add("models", arr)
        }
        return p
    }

    @Test
    fun idsOutsideTheEnginePayloadAreDropped() {
        val providers = listOf(
            provider("other-provider", listOf("unused-id")),
            provider("opencode-go", listOf("deepseek-v4.1-flash", "glm-5.3-flash"))
        )
        val visible = ZenModelsApi.engineVisibleModelIds(providers, "opencode-go")
        assertEquals(
            setOf("deepseek-v4.1-flash", "glm-5.3-flash"),
            visible
        )
    }

    @Test
    fun missingProviderEntryMeansNoVisibleIds() {
        val providers = listOf(provider("opencode-zen", listOf("some-id")))
        assertTrue(
            ZenModelsApi.engineVisibleModelIds(providers, "opencode-go").isEmpty()
        )
    }

    @Test
    fun providerEntryWithoutModelsBlockMeansNoVisibleIds() {
        // Measured incident shape: BOTH setProviderKey and setModel answered
        // 2xx, yet the engine's persisted provider object carried no models
        // block - agent/init then 500s for ANY id. Callers must not filter on
        // this (otherwise a dropped models block would empty the picker).
        val providers = listOf(provider("opencode-go", null))
        assertTrue(
            ZenModelsApi.engineVisibleModelIds(providers, "opencode-go").isEmpty()
        )
    }
}
