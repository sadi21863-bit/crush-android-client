package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the row-scoping decision [liveRowsForOpenSession] makes when a chat's
 * transcript is opened.
 *
 * 2026-11-02 the unscoped call site fed the PREVIOUS session's whole
 * transcript into [mergeReplay] on every switch, which is the reported
 * "when we switch between existing chats the other session's chats get
 * transferred to the chat I am currently in".
 */
class ChatSessionTranscriptGuardTest {

    private val oldSessionRows = listOf(
        ChatMessage(id = "a-1", role = "user", text = "question asked in the OLD chat"),
        ChatMessage(id = "a-2", role = "assistant", text = "answer given in the OLD chat")
    )

    @Test
    fun `switching to a different session carries over nothing of the old chat`() {
        val live = liveRowsForOpenSession(
            currentlyViewedSession = "old-session",
            targetSession = "new-session",
            runInFlight = true,
            onScreen = oldSessionRows
        )
        assertTrue("old rows must not ride into the new transcript", live.isEmpty())
    }

    @Test
    fun `opening a chat with no session previously on screen carries over nothing`() {
        val live = liveRowsForOpenSession(
            currentlyViewedSession = null,
            targetSession = "new-session",
            runInFlight = true,
            onScreen = oldSessionRows
        )
        assertTrue(live.isEmpty())
    }

    @Test
    fun `the same-session reopen during a live run keeps the in-flight rows - the R1 case`() {
        val live = liveRowsForOpenSession(
            currentlyViewedSession = "same-session",
            targetSession = "same-session",
            runInFlight = true,
            onScreen = oldSessionRows
        )
        assertEquals(oldSessionRows, live)
    }

    @Test
    fun `same session with no run in flight contributes nothing - engine history is the truth`() {
        val live = liveRowsForOpenSession(
            currentlyViewedSession = "same-session",
            targetSession = "same-session",
            runInFlight = false,
            onScreen = oldSessionRows
        )
        assertTrue(
            "with no live run there is no row worth carrying; the replay must stand alone",
            live.isEmpty()
        )
    }
}
