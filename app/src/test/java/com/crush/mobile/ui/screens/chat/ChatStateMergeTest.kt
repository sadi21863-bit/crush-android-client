package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the attach-side race merge (analysis R1, 2026-10-31).
 *
 * The window this guards: [ChatViewModel.openSession] fetches history over
 * the network, and between that fetch and its state write the user can send a
 * turn - send() claims `isStreaming` synchronously, so on a slow network the
 * late history used to REPLACE the live turn's rows wholesale and dead-stop
 * the UI. The invariant under test: replayed rows own the transcript order,
 * and every live row the replay does not contain survives after it.
 */
class ChatStateMergeTest {

    private fun user(id: String, text: String) =
        ChatMessage(id = id, role = "user", text = text)

    private fun assistant(id: String, text: String) =
        ChatMessage(id = id, role = "assistant", text = text)

    @Test
    fun `live rows the replay does not contain survive after it`() {
        val replayed = listOf(user("u1", "hi"), assistant("a1", "hello"))
        // The turn that beat the history: a fresh user row + streaming bubble.
        val live = listOf(user("u2", "second"), assistant("live-bubble", ""))

        val merged = mergeReplay(replayed, live)

        assertEquals(
            listOf("u1", "a1", "u2", "live-bubble"),
            merged.map { it.id }
        )
    }

    @Test
    fun `a replay row is never duplicated by an identical live row`() {
        val replayed = listOf(user("u1", "hi"), assistant("a1", "hello"))
        // Same conversation reopened while the stream re-delivered a frame.
        val live = listOf(user("u1", "hi"))

        val merged = mergeReplay(replayed, live)

        assertEquals(replayed, merged)
    }

    @Test
    fun `an empty replay keeps the live transcript whole`() {
        val live = listOf(
            user("u1", "first turn"),
            assistant("a1", "first reply"),
            user("u2", "second turn"),
            assistant("live-bubble", "partial")
        )

        val merged = mergeReplay(emptyList(), live)

        assertEquals(live, merged)
    }
}
