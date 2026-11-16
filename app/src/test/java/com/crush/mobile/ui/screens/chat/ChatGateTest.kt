package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.crush.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2A-02 (V2 plan §5.5.1): the composer gates exactly as today.
 *
 * The two load-bearing rows are the 2026-11-03 recovery path
 * (`engineReady=false, recoverable=true` must still send) and the prewarm-off
 * cold start (typeable, not sendable).
 */
class ChatGateTest {

    private data class Row(
        val engineReady: Boolean,
        val recoverable: Boolean,
        val prewarm: Boolean,
        val streaming: Boolean,
        val draft: String,
        val canType: Boolean,
        val canSend: Boolean,
    )

    private fun view(
        engineReady: Boolean,
        recoverable: Boolean,
        prewarm: Boolean,
        streaming: Boolean,
        draft: String,
        permission: PermissionRequest? = null,
        question: PendingQuestion? = null,
    ): ChatViewState = ChatUiState(
        engineReady = engineReady,
        recoverable = recoverable,
        isStreaming = streaming,
        input = draft,
        pendingPermission = permission,
        pendingQuestion = question,
    ).toViewState(
        ChatHostInputs(prewarm = prewarm, snapshotReady = true),
    )

    @Test
    fun `the eight composer rows are exact`() {
        val rows = listOf(
            Row(true, true, true, false, "", true, false),
            Row(true, true, true, false, "hi", true, true),
            Row(true, false, true, false, "hi", true, true),
            Row(false, true, true, false, "hi", true, true),
            Row(false, false, true, false, "hi", false, false),
            Row(false, false, false, false, "hi", true, false),
            Row(false, false, false, true, "hi", false, false),
            Row(true, true, true, false, "   ", true, false),
        )

        rows.forEach { row ->
            val composer = view(
                engineReady = row.engineReady,
                recoverable = row.recoverable,
                prewarm = row.prewarm,
                streaming = row.streaming,
                draft = row.draft,
            ).composer
            assertEquals("canType for $row", row.canType, composer.canType)
            assertEquals("canSend for $row", row.canSend, composer.canSend)
        }
    }

    @Test
    fun `recoverable keeps sending armed when the engine is not ready`() {
        val composer = view(
            engineReady = false,
            recoverable = true,
            prewarm = true,
            streaming = false,
            draft = "recover",
        ).composer

        assertTrue(composer.canType)
        assertTrue(composer.canSend)
    }

    @Test
    fun `a prewarm-off cold start is typeable but not sendable`() {
        val composer = view(
            engineReady = false,
            recoverable = false,
            prewarm = false,
            streaming = false,
            draft = "cold",
        ).composer

        assertTrue(composer.canType)
        assertFalse(composer.canSend)
        assertFalse(composer.canSubmitViaIme)
    }

    @Test
    fun `streaming disables typing and sending regardless of readiness`() {
        val composer = view(
            engineReady = true,
            recoverable = true,
            prewarm = false,
            streaming = true,
            draft = "still typing",
        ).composer

        assertFalse(composer.canType)
        assertFalse(composer.canSend)
        assertFalse(composer.canSubmitViaIme)
        assertTrue(composer.streaming)
    }

    @Test
    fun `whitespace is not sendable`() {
        val composer = view(
            engineReady = true,
            recoverable = false,
            prewarm = true,
            streaming = false,
            draft = " \n\t ",
        ).composer

        assertFalse(composer.canSend)
    }

    @Test
    fun `IME submit requires both gates`() {
        assertFalse(
            view(false, false, false, false, "x").composer.canSubmitViaIme,
        )
        assertTrue(
            view(true, false, true, false, "x").composer.canSubmitViaIme,
        )
    }

    @Test
    fun `a pending permission or question does not disable the composer`() {
        val composer = view(
            engineReady = true,
            recoverable = false,
            prewarm = true,
            streaming = false,
            draft = "still allowed",
            permission = PermissionRequest(id = "p1"),
            question = PendingQuestion(
                id = "q1",
                raw = com.google.gson.JsonParser.parseString("""{"question":"Pick"}"""),
            ),
        ).composer

        assertTrue(composer.canType)
        assertTrue(composer.canSend)
    }
}
