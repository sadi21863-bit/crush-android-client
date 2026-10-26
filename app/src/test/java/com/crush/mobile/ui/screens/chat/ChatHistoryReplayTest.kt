package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.crush.StreamMessage
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History-replay row mapping.
 *
 * Evidence for the error case: the 2026-10-25 standalone-usage audit. The
 * engine persisted assistant turns whose ONLY part was a finish with reason
 * "error" (real example: a provider DNS failure - `dial tcp: lookup
 * openrouter.ai ... connection refused`). The old replay dropped any row
 * without text or tools, so every failed turn rendered as NOTHING after a
 * reopen even though the live bubble had shown the failure. A failed turn
 * must survive the reopen; it is the visible answer to "the app sometimes
 * doesn't work".
 */
class ChatHistoryReplayTest {

    private fun msg(id: String, role: String, parts: List<JsonObject>) =
        StreamMessage(id = id, role = role, parts = parts)

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    /** The exact shape the wire delivered for a failed provider call. */
    private fun finishError() = obj(
        """{"type":"finish","data":{"reason":"error","time":1792556741,"message":"Provider Error","details":"retry error: dial tcp: lookup openrouter.ai on [::1]:53: connection refused"}}"""
    )

    private fun textPart(t: String) = obj("""{"type":"text","data":{"text":"$t"}}""")

    @Test
    fun `a finish-error assistant turn is kept and carries the error`() {
        val row = replayRow(msg("m1", "assistant", listOf(finishError())))
        assertNotNull(row)
        assertEquals("assistant", row!!.role)
        assertEquals("h-a-m1", row.id)
        assertEquals("", row.text)
        assertTrue("error text must survive", row.error?.contains("connection refused") == true)
    }

    @Test
    fun `an empty-parts assistant turn is not fabricated into history`() {
        // Wire truth (2026-10-25 audit): the "jello" turn persisted with
        // parts:[] - no error, no text, no tools. Keeping nothing is honest:
        // there is nothing to attribute, and the live path already surfaced
        // "no reply received" for that turn.
        assertNull(replayRow(msg("m2", "assistant", emptyList())))
    }

    @Test
    fun `normal user and assistant rows still replay`() {
        val user = replayRow(msg("u1", "user", listOf(textPart("hello"))))
        assertEquals("hello", user!!.text)
        assertEquals("h-u-u1", user.id)

        val asst = replayRow(msg("a1", "assistant", listOf(textPart("hi"))))
        assertEquals("hi", asst!!.text)
        assertNull(asst.error)
    }

    @Test
    fun `tool-only assistant rows are kept`() {
        // The tool_call envelope confirmed on the wire (D3): streaming frames
        // omit `finished`, so the row parses and stays RUNNING - the trace of
        // the work must survive the reopen either way.
        val tool = obj(
            """{"type":"tool_call","data":{"id":"c1","name":"write",
               "input":"{\"file_path\":\"z.txt\"}","finished":true}}"""
        )
        val row = replayRow(msg("a2", "assistant", listOf(tool)))
        assertNotNull(row)
        assertEquals(1, row!!.tools.size)
        assertEquals("write", row.tools[0].name)
    }

    @Test
    fun `a parts-less user row is not replayed`() {
        assertNull(replayRow(msg("u2", "user", emptyList())))
    }
}
