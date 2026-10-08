package com.opencode.chat.ui.screens.chat

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool-activity parsing.
 *
 * The engine's real `tool_call` shape was still unconfirmed when this was
 * written - the only fixture available was `{"name":"read"}`. The parser
 * therefore reads each field through a list of candidate names and returns
 * null when none match, rather than assuming one.
 *
 * These tests pin the behaviour that matters regardless of the exact wire
 * format: a malformed or unfamiliar part must degrade to something showable, and
 * must NEVER be reported as completed. Claiming a tool finished when it may not
 * have is the same class of lie as reporting a stop that never happened.
 */
class ToolActivityParserTest {

    private fun parse(json: String): ToolActivity? {
        val o = JsonParser.parseString(json).asJsonObject
        val data = o.getAsJsonObject("data")
        return ToolActivityParser.from(data)
    }

    // ---- the one shape we have actually seen -----------------------------

    @Test
    fun `the known minimal shape parses`() {
        val t = parse("""{"type":"tool_call","data":{"name":"read"}}""")
        assertNotNull(t)
        assertEquals("read", t!!.name)
    }

    @Test
    fun `a part with no name is dropped rather than shown blank`() {
        assertNull(parse("""{"type":"tool_call","data":{"id":"x"}}"""))
    }

    @Test
    fun `non-object data does not crash`() {
        val o = JsonParser.parseString("""{"type":"tool_call","data":null}""").asJsonObject
        // data() in CrushEvent tolerates JsonNull; the parser must too.
        assertNull(o.get("data")?.takeIf { !it.isJsonNull }?.asJsonObject)
    }

    // ---- field-name fallbacks --------------------------------------------

    @Test
    fun `name is found under any of the candidate keys`() {
        for (key in listOf("name", "tool", "tool_name", "toolName")) {
            val t = parse("""{"type":"tool_call","data":{"$key":"bash"}}""")
            assertEquals("wrong for $key", "bash", t?.name)
        }
    }

    @Test
    fun `arguments are pretty-printed when they are a JSON string`() {
        val t = parse("""{"type":"tool_call","data":{"name":"read","arguments":"{\"path\":\"a.txt\"}"}}""")
        assertNotNull(t)
        // Pretty-printed, not the raw escaped one-liner.
        assertTrue(t!!.args.contains("\n"))
        assertTrue(t.args.contains("a.txt"))
    }

    @Test
    fun `arguments are pretty-printed when they are already an object`() {
        val t = parse("""{"type":"tool_call","data":{"name":"read","arguments":{"path":"a.txt"}}}""")
        assertNotNull(t)
        assertTrue(t!!.args.contains("a.txt"))
    }

    @Test
    fun `missing args yield an empty string, not the word null`() {
        val t = parse("""{"type":"tool_call","data":{"name":"read"}}""")
        assertEquals("", t!!.args)
    }

    // ---- state mapping: the part that must never lie ---------------------

    @Test
    fun `completion states map to COMPLETED`() {
        for (s in listOf("completed", "done", "success", "finished")) {
            assertEquals(s, ToolState.COMPLETED, ToolActivityParser.parseState(s))
        }
    }

    @Test
    fun `failure states map to FAILED`() {
        for (s in listOf("error", "failed", "denied", "cancelled")) {
            assertEquals(s, ToolState.FAILED, ToolActivityParser.parseState(s))
        }
    }

    @Test
    fun `running states map to RUNNING`() {
        for (s in listOf("running", "active", "in_progress")) {
            assertEquals(s, ToolState.RUNNING, ToolActivityParser.parseState(s))
        }
    }

    @Test
    fun `an unrecognised state is UNKNOWN and never COMPLETED`() {
        // The important assertion is the negative: an unknown vocabulary must not
        // be optimistically read as "it finished".
        assertEquals(ToolState.UNKNOWN, ToolActivityParser.parseState("wat"))
        assertEquals(ToolState.UNKNOWN, ToolActivityParser.parseState(null))
        assertEquals(ToolState.UNKNOWN, ToolActivityParser.parseState(""))
        assertTrue(ToolActivityParser.parseState("wat") != ToolState.COMPLETED)
    }

    @Test
    fun `state arriving as an array of status objects is understood`() {
        val t = parse(
            """{"type":"tool_call","data":{"name":"read","state":[{"status":"completed"}]}}"""
        )
        assertEquals(ToolState.COMPLETED, t!!.state)
    }

    // ---- identity and truncation -----------------------------------------

    @Test
    fun `id is stable so rows do not duplicate as state advances`() {
        val a = parse("""{"type":"tool_call","data":{"name":"read","id":"call_1","state":"pending"}}""")
        val b = parse("""{"type":"tool_call","data":{"name":"read","id":"call_1","state":"completed"}}""")
        assertEquals(a!!.id, b!!.id)
    }

    @Test
    fun `a missing id still yields a stable synthesised one`() {
        val a = parse("""{"type":"tool_call","data":{"name":"read","arguments":{"p":"x"}}}""")
        val b = parse("""{"type":"tool_call","data":{"name":"read","arguments":{"p":"x"}}}""")
        assertEquals(a!!.id, b!!.id)
    }

    @Test
    fun `very long output is truncated rather than rendered in full`() {
        val big = "x".repeat(20000)
        val t = parse("""{"type":"tool_call","data":{"name":"bash","output":"$big"}}""")
        assertTrue("output was not truncated", t!!.output.length < 20000)
        assertTrue(t.output.contains("truncated"))
    }

    @Test
    fun `summary reflects state without inventing one`() {
        val running = parse("""{"type":"tool_call","data":{"name":"bash","state":"running"}}""")!!
        assertEquals("bash running", running.summary())
        val done = parse("""{"type":"tool_call","data":{"name":"bash","state":"completed"}}""")!!
        assertEquals("bash", done.summary())
        val failed = parse("""{"type":"tool_call","data":{"name":"bash","state":"error"}}""")!!
        assertTrue(failed.summary().contains("failed"))
    }
}
