package com.opencode.chat.ui.screens.chat

import com.opencode.chat.data.api.LiveZenModel
import com.opencode.chat.data.api.ModelPrice
import com.google.gson.JsonParser
import com.opencode.chat.data.api.ZenModelsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped bug this file exists to prevent:
 *
 * Zen's GET /models is PUBLIC - it answers HTTP 200 with all 86 models and no
 * API key at all - and it still lists retired ids. Measured with one key:
 *
 *   deepseek-v4-flash-free -> HTTP 400 "Model is unavailable"
 *   space-bunny-free       -> HTTP 200
 *
 * The old picker took the first id ending in "-free", which is exactly the dead
 * one, so the app booted "healthy" and then failed every turn. These tests pin
 * the rule that fixed it: never trust membership or naming, only measurement.
 */
class ModelChoiceTest {

    private fun m(id: String, free: Boolean) = LiveZenModel(id, id, free)

    @Test
    fun `shortlist prefers zero-cost models but does not decide`() {
        val s = ModelChoice.shortlist(
            listOf(m("paid-a", false), m("free-a", true), m("paid-b", false))
        )
        assertEquals(listOf("free-a", "paid-a", "paid-b"), s)
    }

    @Test
    fun `shortlist is empty when catalogue is empty`() {
        assertTrue(ModelChoice.shortlist(emptyList()).isEmpty())
        assertNull(ModelChoice.pick(emptyList()))
    }

    @Test
    fun `shortlist drops blank ids and de-duplicates`() {
        val s = ModelChoice.shortlist(listOf(m("a", true), m("a", true), m("", true), m("  ", false)))
        assertEquals(listOf("a"), s)
    }

    @Test
    fun `an all-dead shortlist is still returned so it can be probed`() {
        // The point: shortlist must not silently collapse to null just because
        // nothing is flagged free. Measurement happens later, in firstWorking.
        val s = ModelChoice.shortlist(listOf(m("dead-1", false), m("dead-2", false)))
        assertEquals(listOf("dead-1", "dead-2"), s)
    }

    @Test
    fun `pick is only a fallback and returns first listed`() {
        assertEquals("x", ModelChoice.pick(listOf(m("x", false))))
    }

    // ---- price extraction: the real source of "free" ------------------

    private val providersJson = """
        [
          {"id":"opencode-zen","models":[
            {"id":"space-bunny-free","cost_per_1m_in":0,"cost_per_1m_out":0},
            {"id":"some-paid","cost_per_1m_in":1.25,"cost_per_1m_out":5.0}
          ]},
          {"id":"other","models":[{"id":"nope","cost_per_1m_in":0,"cost_per_1m_out":0}]}
        ]
    """.trimIndent()

    @Test
    fun `prices come from the named provider only`() {
        val list = JsonParser.parseString(providersJson).asJsonArray
            .map { it.asJsonObject }
        val prices = ZenModelsApi.pricesFromProviders(list, "opencode-zen")
        assertEquals(setOf("space-bunny-free", "some-paid"), prices.keys)
        assertNull(prices["nope"])
    }

    @Test
    fun `a zero-cost model is free and a priced one is not`() {
        val list = JsonParser.parseString(providersJson).asJsonArray
            .map { it.asJsonObject }
        val prices = ZenModelsApi.pricesFromProviders(list, "opencode-zen")
        assertTrue(prices.getValue("space-bunny-free").isKnownZero)
        assertTrue(!prices.getValue("some-paid").isKnownZero)
    }

    // ---- MODEL-02: unknown price is NOT zero ---------------------------
    //
    // These used to coerce a missing price to 0.0, which made "we have no data"
    // indistinguishable from "this model is free" and let free-first ordering
    // prefer models whose cost was simply undocumented.

    private val missingPriceJson = """
        [
          {"id":"opencode-zen","models":[
            {"id":"no-price-at-all"},
            {"id":"null-prices","cost_per_1m_in":null,"cost_per_1m_out":null},
            {"id":"half-known","cost_per_1m_in":0}
          ]}
        ]
    """.trimIndent()

    private fun pricesOf(json: String): Map<String, ModelPrice> =
        ZenModelsApi.pricesFromProviders(
            JsonParser.parseString(json).asJsonArray.map { it.asJsonObject },
            "opencode-zen"
        )

    @Test
    fun `a model with no price fields is unknown, not free`() {
        val p = pricesOf(missingPriceJson).getValue("no-price-at-all")
        assertNull("absent price must stay null", p.costIn)
        assertNull(p.costOut)
        assertTrue("undocumented cost must not read as free", !p.isKnownZero)
    }

    @Test
    fun `explicit null prices are unknown, not free`() {
        val p = pricesOf(missingPriceJson).getValue("null-prices")
        assertTrue(!p.isKnownZero)
    }

    @Test
    fun `a zero in-cost with unknown out-cost is not known-free`() {
        // Free to send, unknown to receive. Claiming "free" here could route a
        // paid response through a model we believe costs nothing.
        val p = pricesOf(missingPriceJson).getValue("half-known")
        assertEquals(0.0, p.costIn!!, 0.0001)
        assertNull(p.costOut)
        assertTrue(!p.isKnownZero)
    }

    @Test
    fun `both sides explicitly zero is free`() {
        val list = JsonParser.parseString(providersJson).asJsonArray
            .map { it.asJsonObject }
        val p = ZenModelsApi.pricesFromProviders(list, "opencode-zen")
            .getValue("space-bunny-free")
        assertTrue(p.isKnownZero)
    }

    @Test
    fun `describe separates unknown from free`() {
        assertEquals("price unknown", ModelPrice(null, null).describe())
        assertEquals("free", ModelPrice(0.0, 0.0).describe())
        assertTrue(ModelPrice(1.25, 5.0).describe().contains("1.25"))
        // Half-known must not be described as free either.
        assertTrue(!ModelPrice(0.0, null).describe().contains("free"))
    }

    @Test
    fun `missing provider yields no prices rather than guessing`() {
        val list = JsonParser.parseString(providersJson).asJsonArray
            .map { it.asJsonObject }
        assertTrue(ZenModelsApi.pricesFromProviders(list, "absent").isEmpty())
    }

    @Test
    fun `unknown provider is not treated as free`() {
        val list = JsonParser.parseString(providersJson).asJsonArray
            .map { it.asJsonObject }
        assertTrue(
            !ZenModelsApi.pricesFromProviders(list, "absent")
                .getOrDefault("anything", ModelPrice(1.0, 1.0)).isKnownZero
        )
    }

    // ---- error wording: credential vs connectivity must stay distinct ---

    @Test
    fun `a 401 is reported as a key problem not a network problem`() {
        assertTrue(ModelChoice.isCredentialProblem("HTTP 401"))
        assertTrue(
            ModelChoice.explainFetchFailure("HTTP 401")
                .contains("rejected this API key", ignoreCase = true)
        )
    }

    @Test
    fun `a network failure does not accuse the key`() {
        // A connectivity failure and a rejected key look identical to the user
        // otherwise, so the wording must not slide between them - otherwise
        // people go re-typing a perfectly good key because the wifi dropped.
        val msg = ModelChoice.explainFetchFailure("timeout")
        assertTrue("got: $msg", msg.contains("connection", ignoreCase = true))
        assertTrue("must not blame the key, got: $msg", !msg.contains("API key", ignoreCase = true))
    }
}
