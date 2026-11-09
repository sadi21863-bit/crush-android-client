package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 Batch C entry-chip and dispatch-note proofs, plain JVM (the
 * ChatDenialNoteTest / HandoffPackageDraftTest style - no Room, no
 * Robolectric, no engine).
 *
 * The load-bearing pin: the handoff entry chip's visibility predicate is
 * the Copy-gate's conjunction EXACTLY - the one expression
 * `handoffEntryVisible` the bubble's actions row composes - so the third
 * chip ("Use this plan in Code", plan §Batch C (c1)) provably carries the
 * same guard the Copy/Save chips render under, with no lookalike drift.
 */
class HandoffEntryChipTest {

    private fun assistantMsg(
        id: String,
        text: String,
        isStreaming: Boolean = false,
        error: String? = null,
    ) = ChatMessage(id = id, role = "assistant", text = text, isStreaming = isStreaming, error = error)

    private fun userMsg(id: String, text: String) =
        ChatMessage(id = id, role = "user", text = text)

    // ------------------------------------------------- the Copy-gate's gate

    @Test
    fun `the entry chip's gate is the Copy-gate's conjunction on a completed assistant row`() {
        val chip = handoffEntryVisible(assistantMsg("a1", "Here is the plan:\n1. split the ledger"))
        assertTrue(chip)
    }

    @Test
    fun `streaming, failed and empty rows are never eligible - the completed-only gate`() {
        // Mid-stream: the plan's own reason stands - a handoff drafted from
        // half-drawn content is a partial artifact.
        assertFalse(handoffEntryVisible(assistantMsg("a1", "half of a", isStreaming = true)))
        // A failed turn IS an error row on screen; the recovery affordance
        // there is Retry, not save and not hand off.
        assertFalse(handoffEntryVisible(assistantMsg("a2", "partial before the error", error = "dial refused")))
        // Nothing to hand over: the Copy chip has no text to copy either.
        assertFalse(handoffEntryVisible(assistantMsg("a3", "")))
        // User rows never show the actions row; the conjunction carries !isUser.
        assertFalse(handoffEntryVisible(userMsg("u1", "my own plan text")))
    }

    @Test
    fun `a system note row matches the conjunction - and stays structurally unreachable`() {
        // The conjunction is the ACTIONS ROW's guard verbatim; the Bubble renders
        // role=="system" rows as note lines and early-returns BEFORE the row, so
        // the chip is unreachable for them structurally. The predicate keeps the
        // conjunction exact rather than re-deriving a different condition.
        val note = ChatMessage(id = "s4-deny-t", role = "system", text = "Crush tools are off in Chat.")
        assertTrue("the conjunction's own value: a note row is not a user row", handoffEntryVisible(note))
    }

    // --------------------------------------------------- the dispatch note

    @Test
    fun `the dispatch note is the transcript's own system line naming the project`() {
        val note = handoffDispatchNote("handoff-9", "Ledger project")
        assertEquals(
            "the note line's text is the plan's honesty wording",
            "Handed off to Ledger project.",
            note.text,
        )
        // The S-4 note grammar: role "system" renders as a centered line, never
        // model speech, never the copy/save or retry affordances.
        assertEquals("system", note.role)
        // The id derives from the handoff - one dedupe story per handoff (the
        // turnDenialNote precedent runs on the turn id for the same reason).
        assertEquals("p6-handoff-handoff-9", note.id)
        // A richer label rides through untouched - no re-styling inside the
        // note, the wording sentence stays one sentence.
        assertEquals("Handed off to Project Two.", handoffDispatchNote("h2", "Project Two").text)
    }

    // ------------------------------------------------ dispatch title shapes

    @Test
    fun `the dispatch session title is the plan's Handoff HHmmss shape`() {
        assertEquals(
            "Handoff 142208",
            handoffSessionTitle(java.time.LocalTime.of(14, 22, 8)),
        )
        assertEquals(
            "Handoff 000105",
            handoffSessionTitle(java.time.LocalTime.of(0, 1, 5)),
        )
        // The whole title - the target session list reads it, not some
        // downstream subtitle - so the exact shape is worth pinning.
        assertTrue(handoffSessionTitle(java.time.LocalTime.NOON).startsWith("Handoff "))
    }

    // ----------------------------------------------- the provider stamp slot

    @Test
    fun `the provider stamp prefers the model the chat ran on and falls back to the provider`() {
        assertEquals("glm-5.3-flash", handoffProviderStamp("opencode-go", "glm-5.3-flash"))
        // No live selection: the provider id is all the app knows today - the
        // declared value, not a fabricated model. (§12 owner fact 2 is the
        // value's exact origin; this stamp is the best-available read.)
        assertEquals("opencode-go", handoffProviderStamp("opencode-go", ""))
        // Whitespace is not a selection either - trim guards the stamp.
        assertEquals("opencode-go", handoffProviderStamp("opencode-go", "   "))
    }

    // ------------------------------------------------- the provenance stamp

    @Test
    fun `the provenance stamp is dated-paper shape and refuses an absent clock`() {
        // The session list's epoch-seconds clock: <= 0 means the app knows
        // nothing, so null - and the assembled first turn renders the plan's
        // own "(not recorded)" (FirstTurnTest pins that render).
        assertNull(handoffSourceStamp(0L))
        assertNull(handoffSourceStamp(-5L))
        val stamp = handoffSourceStamp(1_762_000_000L)!!
        assertTrue(
            "the stamp is yyyy-MM-dd HH:mm (device zone), got: $stamp",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(stamp),
        )
    }
}
