package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.crush.StreamMessage
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a sub-agent report user row survives replay`() {
        // Exact v0.100.0 shape: the report is written back as a USER message
        // whose only part is `sub_agent_report` - no text part at all. The old
        // replay kept user rows only when text was non-blank, so completed
        // detached agents vanished after a reopen.
        val report = obj(
            """{"type":"sub_agent_report","data":{"label":"probe",
               "output":"PONG","failed":false,"model":"openai/gpt-4o"}}"""
        )
        val row = replayRow(msg("u3", "user", listOf(report)))
        assertNotNull(row)
        assertEquals("user", row!!.role)
        assertEquals("h-u-u3", row.id)
        assertEquals("", row.text)
        assertEquals(1, row.reports.size)
        assertEquals("probe", row.reports[0].label)
        assertEquals("PONG", row.reports[0].output)
        assertTrue(!row.reports[0].failed)
        assertEquals("openai/gpt-4o", row.reports[0].model)
    }

    @Test
    fun `a failed sub-agent report keeps its failed flag and no model`() {
        val report = obj(
            """{"type":"sub_agent_report","data":{"label":"audit","output":"boom","failed":true}}"""
        )
        val row = replayRow(msg("u4", "user", listOf(report)))
        assertNotNull(row)
        assertTrue(row!!.reports[0].failed)
        assertEquals("", row.reports[0].model)
    }

    @Test
    fun `a tool result carries the call id, failure flag and engine duration`() {
        // Exact v0.100.0 shape from the on-device DB (2026-11-14): the result
        // is its own role="tool" message and `metadata` is a JSON-encoded
        // STRING holding the engine's start/end epoch ms.
        val result = obj(
            """{"type":"tool_result","data":{"tool_call_id":"call_1","name":"bash",
               "content":"step-one","metadata":"{\"start_time\":1794620808982,\"end_time\":1794620808983,\"output\":\"step-one\"}",
               "is_error":false}}"""
        )
        val m = msg("t1", "tool", listOf(result))
        assertEquals(1, m.toolResults.size)
        assertEquals("call_1", m.toolResults[0].toolCallId)
        assertFalse(m.toolResults[0].failed)
        assertEquals(1L, m.toolResults[0].durationMs)
    }

    @Test
    fun `malformed or missing tool_result metadata yields no invented duration`() {
        val bad = obj(
            """{"type":"tool_result","data":{"tool_call_id":"call_1","metadata":"not json","is_error":true}}"""
        )
        val badResult = msg("t2", "tool", listOf(bad)).toolResults[0]
        assertTrue(badResult.failed)
        assertEquals(0L, badResult.durationMs)

        val absent = obj("""{"type":"tool_result","data":{"tool_call_id":"call_2"}}""")
        assertEquals(0L, msg("t3", "tool", listOf(absent)).toolResults[0].durationMs)
    }

    @Test
    fun `an inverted start and end time yields no duration`() {
        val inverted = obj(
            """{"type":"tool_result","data":{"tool_call_id":"call_1","metadata":"{\"start_time\":200,\"end_time\":100}"}}"""
        )
        assertEquals(0L, msg("t4", "tool", listOf(inverted)).toolResults[0].durationMs)
    }
}
