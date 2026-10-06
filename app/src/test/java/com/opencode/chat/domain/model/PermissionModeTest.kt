package com.opencode.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission mode is a security control, so the parsing rule that matters is
 * the failure one: anything unrecognised must resolve to ASK.
 *
 * A preferences file that is corrupt, truncated, or hand-edited must never be
 * able to talk the app into approving the agent's file edits on its own.
 */
class PermissionModeTest {

    @Test
    fun `default is ask`() {
        assertEquals(PermissionMode.ASK, PermissionMode.parse(null))
        assertEquals(PermissionMode.ASK, PermissionMode.parse(""))
    }

    @Test
    fun `ask round trips`() {
        assertEquals(PermissionMode.ASK, PermissionMode.parse(PermissionMode.ASK.stored))
        assertEquals("ask", PermissionMode.ASK.stored)
    }

    @Test
    fun `auto approve round trips`() {
        assertEquals(
            PermissionMode.AUTO_APPROVE,
            PermissionMode.parse(PermissionMode.AUTO_APPROVE.stored)
        )
        assertEquals("auto_approve", PermissionMode.AUTO_APPROVE.stored)
    }

    @Test
    fun `garbage fails safe to ask`() {
        for (junk in listOf("yolo", "true", "1", "AUTO_APPROVE", "ask ", "allow")) {
            assertEquals(
                "junk '$junk' must not enable auto-approve",
                PermissionMode.ASK,
                PermissionMode.parse(junk)
            )
        }
    }

    @Test
    fun `auto approve is explicit opt in`() {
        // Proves the safe default is actually reachable and not sticky.
        assertEquals(PermissionMode.ASK, PermissionMode.parse(null))
        assertTrue(PermissionMode.parse("auto_approve") == PermissionMode.AUTO_APPROVE)
        assertFalse(PermissionMode.ASK == PermissionMode.AUTO_APPROVE)
    }
}
