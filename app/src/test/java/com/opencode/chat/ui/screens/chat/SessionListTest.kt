package com.opencode.chat.ui.screens.chat

import com.opencode.chat.data.crush.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the chat-list presentation rules.
 *
 * The busy-first ordering is the load-bearing one: a session with a live run must
 * never be buried, because the user watching tokens stream into it would see it
 * appear to vanish.
 */
class SessionListTest {

    private fun s(
        id: String = "x",
        title: String = "",
        messages: Int = 0,
        cost: Double = 0.0,
        updated: Long = 0,
        busy: Boolean = false,
        parent: String = "",
        promptTok: Int = 0,
        completionTok: Int = 0
    ) = Session(
        id = id, title = title, messageCount = messages, cost = cost,
        updatedAt = updated, isBusy = busy, parentSessionId = parent,
        promptTokens = promptTok, completionTokens = completionTok
    )

    @Test
    fun `newest first`() {
        val out = SessionList.sort(
            listOf(s(id = "old", updated = 100), s(id = "new", updated = 300), s(id = "mid", updated = 200))
        )
        assertEquals(listOf("new", "mid", "old"), out.map { it.id })
    }

    @Test
    fun `busy session pins to top even when oldest`() {
        val out = SessionList.sort(
            listOf(
                s(id = "fresh", updated = 300),
                s(id = "running", updated = 100, busy = true)
            )
        )
        assertEquals("running", out.first().id)
    }

    @Test
    fun `empty list stays empty`() {
        assertTrue(SessionList.sort(emptyList()).isEmpty())
    }

    @Test
    fun `auto title is used when present`() {
        assertEquals("Simple Greeting Request", SessionList.label(s(title = "Simple Greeting Request")))
    }

    @Test
    fun `blank title falls back to message count`() {
        assertEquals("Chat (4 messages)", SessionList.label(s(title = "   ", messages = 4)))
    }

    @Test
    fun `untitled empty session is not blank`() {
        // A blank row reads as a rendering bug, so it must always say something.
        assertEquals("New chat", SessionList.label(s()))
    }

    @Test
    fun `free session omits zero cost`() {
        // space-bunny-free reports cost exactly 0.0; showing "$0.00" is noise.
        val d = SessionList.detail(s(messages = 2, cost = 0.0, updated = 0), nowMs = 0)
        assertTrue("should not show cost: $d", !d.contains("$"))
        assertTrue(d.contains("2 msg"))
    }

    @Test
    fun `paid session shows cost`() {
        val d = SessionList.detail(s(messages = 2, cost = 1.239, updated = 0), nowMs = 0)
        assertTrue("cost missing: $d", d.contains("1.24"))
    }

    @Test
    fun `busy session is labelled running`() {
        assertTrue(SessionList.detail(s(busy = true), nowMs = 0).contains("running"))
    }

    @Test
    fun `untouched session has empty detail`() {
        // Nothing to say, so the row should not render the line at all.
        assertEquals("", SessionList.detail(s(), nowMs = 1_000_000))
    }

    @Test
    fun `relative time buckets`() {
        val now = 1_700_000_000_000L
        fun ago(sec: Long) = SessionList.relativeTime(now / 1000 - sec, now)
        assertEquals("now", ago(5))
        assertEquals("5m", ago(5 * 60))
        assertEquals("2h", ago(2 * 3600))
        assertEquals("3d", ago(3 * 86_400))
        assertEquals("2w", ago(14 * 86_400))
    }

    @Test
    fun `clock skew does not produce negative ages`() {
        val now = 1_700_000_000_000L
        assertEquals("now", SessionList.relativeTime(now / 1000 + 500, now))
    }

    @Test
    fun `zero timestamp is omitted not rendered as epoch`() {
        assertEquals("", SessionList.relativeTime(0, 1_700_000_000_000L))
    }

    @Test
    fun `tokens sum both directions`() {
        assertEquals(150, SessionList.tokens(s(promptTok = 100, completionTok = 50)))
    }

    @Test
    fun `forks group under their parent`() {
        val all = listOf(
            s(id = "root", parent = ""),
            s(id = "forkA", parent = "root"),
            s(id = "forkB", parent = "root"),
            s(id = "other", parent = "zzz")
        )
        assertEquals(listOf("forkA", "forkB"), SessionList.forksOf(all, "root").map { it.id })
    }

    @Test
    fun `top level sessions are not forks`() {
        val all = listOf(s(id = "root", parent = ""), s(id = "fork", parent = "root"))
        // parent_session_id is "" for a top-level session, which is NOT a
        // parent id, so forksOf("") must return nothing rather than every
        // top-level session.
        assertTrue(SessionList.forksOf(all, "").isEmpty())
    }
}
