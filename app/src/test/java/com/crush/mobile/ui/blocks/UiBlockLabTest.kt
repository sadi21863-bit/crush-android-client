package com.crush.mobile.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V2-8: the only normalization the lab performs. It must not become a repair
 * pass - malformed or prose-wrapped output has to reach the validator as-is.
 */
class UiBlockLabTest {

    @Test
    fun `a single json fence is stripped`() {
        val raw = "```json\n{\"v\":\"ui_block/v1\",\"blocks\":[]}\n```"
        assertEquals(
            "{\"v\":\"ui_block/v1\",\"blocks\":[]}",
            normalizeUiBlockPayload(raw)
        )
    }

    @Test
    fun `a bare fence is stripped`() {
        val raw = "```\n{\"v\":\"ui_block/v1\"}\n```"
        assertEquals("{\"v\":\"ui_block/v1\"}", normalizeUiBlockPayload(raw))
    }

    @Test
    fun `raw json is unchanged`() {
        val raw = "{\"v\":\"ui_block/v1\",\"blocks\":[]}"
        assertEquals(raw, normalizeUiBlockPayload(raw))
    }

    @Test
    fun `prose around the fence is not searched`() {
        val raw = "Here you go:\n```json\n{\"v\":\"ui_block/v1\"}\n```"
        assertEquals(raw.trim(), normalizeUiBlockPayload(raw))
    }

    @Test
    fun `an unterminated fence is unchanged`() {
        val raw = "```json\n{\"v\":\"ui_block/v1\"}"
        assertEquals(raw, normalizeUiBlockPayload(raw))
    }
}
