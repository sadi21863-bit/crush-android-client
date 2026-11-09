package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the S-4 "smart transfer" denial note (decisions-open §10 P5).
 *
 * The turn under test fakes the app-side auto-deny path: a chat
 * (conversation-only) run whose PERMISSION_REQUEST resolves into
 * [turnDenialNote] + [appendDenialNote] at the deny site in ChatViewModel.
 * The invariants: exactly one hint per turn (a second deny in the same turn
 * collapses onto the row already on screen), no hint when the turn raised no
 * denial, and one single surviving hint across a session replay merge -
 * since [mergeReplay] dedupes by id and [replayRow] can never emit a note
 * (the engine transcript does not hold it), the row renders once no matter
 * how often the transcript is re-fetched while the run is live.
 */
class ChatDenialNoteTest {

    private fun userMsg(id: String, text: String) =
        ChatMessage(id = id, role = "user", text = text)

    private fun assistantMsg(id: String, text: String) =
        ChatMessage(id = id, role = "assistant", text = text)

    @Test
    fun `an auto-deny yields exactly one hint per turn`() {
        val transcript = listOf(userMsg("u1", "hi"), assistantMsg("b1", ""))

        // The model retried after the first deny: a SECOND deny inside the
        // same turn (distinct tool request) must land on the note that is
        // already there, not stack a second line.
        val once = appendDenialNote(transcript, turnDenialNote("turn-1", "bash"))
        val twice = appendDenialNote(once, turnDenialNote("turn-1", "write"))

        assertEquals(once, twice)
        assertEquals(1, twice.count { it.role == "system" })
        // And the id is turn-scoped: the dedupe is per turn, not per attempt.
        assertEquals("s4-deny-turn-1", twice.single { it.role == "system" }.id)
    }

    @Test
    fun `a turn without an auto-deny adds no hint`() {
        val transcript = listOf(userMsg("u1", "hi"), assistantMsg("b1", "hello"))

        // No PERMISSION_REQUEST fired for this turn: the deny site passes
        // null and the transcript is returned untouched.
        assertEquals(transcript, appendDenialNote(transcript, null))
    }

    @Test
    fun `the hint is a system row that names the attempted tool`() {
        val note = turnDenialNote("turn-1", "bash")

        // role "system" is the render flag: the small centered line, never
        // assistant content - no copy/save bar and no retry scan can touch it.
        assertEquals("system", note.role)
        assertTrue(note.text.contains("bash"))
        assertTrue(note.text.contains("Code mode"))
    }

    @Test
    fun `a blank tool name falls back to the generic wording`() {
        val note = turnDenialNote("turn-1", "")

        assertEquals("s4-deny-turn-1", note.id)
        assertTrue(note.text.contains("Crush tools are off in Chat"))
    }

    @Test
    fun `replay and re-render never duplicate the hint`() {
        // Reopen-during-run (R1): the engine history replays first, and the
        // note rides in the carried-over live rows - the engine transcript
        // never held it.
        val replayed = listOf(userMsg("h-u-1", "hi"), assistantMsg("h-a-1", "earlier"))
        val live = listOf(
            userMsg("u2", "go"),
            assistantMsg("b2", ""),
            turnDenialNote("turn-2", "write")
        )

        val merged = mergeReplay(replayed, live)
        assertEquals(1, merged.count { it.role == "system" })

        // Re-render shape: a second history fetch for the same view merges
        // the transcript already on screen - the note still survives once,
        // and the merged transcript is idempotent.
        val remerged = mergeReplay(replayed, merged)
        assertEquals(merged, remerged)
        assertEquals(1, remerged.count { it.role == "system" })
    }
}
