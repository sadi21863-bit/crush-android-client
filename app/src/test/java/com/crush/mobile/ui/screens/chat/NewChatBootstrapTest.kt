package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flow 11 (New chat) regression net for the cold-boot binding race.
 *
 * The old path called `startChatSession()` -> `bindProject()` -> `newThread()`
 * synchronously. `bindProject()` nulls the outgoing `Workspaces` handle, so
 * `newThread()` fell back to `bootstrap()`, which resumed the last session
 * instead of minting. On a project -> chat-pool switch that left a project
 * transcript under a chat-pool subtitle.
 *
 * The fix binds the intent to the instance key and consumes it only on that
 * instance's bootstrap. These tests pin the key-matching half; the device walk
 * pins the minted-session shape.
 */
class NewChatBootstrapTest {

    @Test
    fun `no pending intent never mints`() {
        assertFalse(consumesPendingNewChat(null, "chat"))
    }

    @Test
    fun `the requesting instance consumes its intent`() {
        assertTrue(consumesPendingNewChat("chat", "chat"))
        assertTrue(consumesPendingNewChat("p536fa26e", "p536fa26e"))
    }

    @Test
    fun `an overtaking binding cannot consume another instance's intent`() {
        assertFalse(consumesPendingNewChat("chat", "p536fa26e"))
        assertFalse(consumesPendingNewChat("p536fa26e", "chat"))
    }
}
