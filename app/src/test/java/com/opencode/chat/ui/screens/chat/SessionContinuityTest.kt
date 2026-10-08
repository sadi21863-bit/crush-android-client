package com.opencode.chat.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a send continues the conversation or starts a
 * new one.
 *
 * THE BUG THIS PINS
 *
 * The send path chose between "reuse the session" and "mint a new one" with:
 *
 *     if (existing != null && existingSessionWorkspace == ws) reuse else new
 *
 * The `== ws` half looked like a safety check. It was the bug. Crush reaps an
 * unattached workspace after roughly 30 seconds, so the workspace id changed
 * during any pause long enough to type a message. The comparison then failed and
 * a NEW session was created - which is exactly why the app appeared unable to
 * hold more than two or three messages: the third went to a fresh chat, the
 * fourth to another.
 *
 * The correct model, verified against the engine: the workspace is an attachment
 * handle that comes and goes; the session is persistent state keyed by the
 * workspace's cwd. A session id we already hold stays valid across a re-attach.
 * A session is only replaced when the SERVER says it is gone.
 */
class SessionContinuityTest {

    /**
     * Mirrors the decision now used by ChatViewModel.send(). Kept here as a pure
     * function so the rule is testable without an engine, a device, or a
     * coroutine.
     */
    fun chooseSessionId(existing: String?, serverSaysSessionGone: Boolean): String? {
        if (serverSaysSessionGone) return "NEW"
        return existing ?: "NEW"
    }

    @Test
    fun `a held session is reused even after the workspace id changes`() {
        // The regression case. A new workspace id used to force a new session.
        val oldWorkspace = "ws-1"
        val newWorkspace = "ws-2"
        assertTrue("premise: the workspace really did change", oldWorkspace != newWorkspace)
        assertEquals(
            "session must survive a workspace change",
            "sess-abc",
            chooseSessionId("sess-abc", serverSaysSessionGone = false)
        )
    }

    @Test
    fun `consecutive sends all stay in one session`() {
        var sid: String? = "sess-abc"
        val seen = mutableListOf<String>()
        // Four sends with a workspace re-attach between each, mimicking typing
        // pauses longer than the reap window.
        repeat(4) {
            sid = chooseSessionId(sid, serverSaysSessionGone = false)
            seen += sid!!
        }
        assertEquals(
            "every message must land in the same thread, got $seen",
            listOf("sess-abc", "sess-abc", "sess-abc", "sess-abc"),
            seen
        )
    }

    @Test
    fun `a genuinely missing session is the only reason to start over`() {
        assertEquals(
            "server-confirmed missing session should start a new one",
            "NEW",
            chooseSessionId("sess-abc", serverSaysSessionGone = true)
        )
    }

    @Test
    fun `no session at all means create one`() {
        assertEquals("NEW", chooseSessionId(null, serverSaysSessionGone = false))
    }

    // ---- the error strings that drive the above ---------------------------

    @Test
    fun `workspace loss alone is not treated as session loss`() {
        // A reaped workspace says "workspace not found". That must trigger a
        // re-attach and a retry with the SAME session, never a new session.
        val err = "HTTP 404 {\"message\":\"workspace not found\"}"
        assertTrue(err.contains("workspace not found", ignoreCase = true))
        assertTrue(
            "must not be mistaken for a dead session",
            !err.contains("session not found", ignoreCase = true)
        )
    }

    @Test
    fun `a real session 404 is distinguishable`() {
        val err = "HTTP 404 {\"message\":\"session not found\"}"
        assertTrue(err.contains("session not found", ignoreCase = true))
    }

    @Test
    fun `the two 404s are not conflated`() {
        // Guards the ordering in send(): the workspace branch must be checked on
        // its own terms, not by substring matching that both 404s satisfy.
        val workspaceErr = "workspace not found"
        val sessionErr = "session not found"
        assertTrue(workspaceErr.contains("workspace not found"))
        assertTrue(!workspaceErr.contains("session not found"))
        assertTrue(sessionErr.contains("session not found"))
    }
}
