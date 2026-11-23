package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A12: the exact `POST /questions/answer` body. This is the wire contract the
 * engine reads, so it is pinned without Compose or a ViewModel.
 */
class QuestionAnswerBuilderTest {

    @Test
    fun `a single and multi choice become selected ids in prompt order`() {
        val body = QuestionAnswerBuilder.build(
            batchId = "batch-1",
            answers = listOf(
                AskAnswerDraft("q1", selectedIds = listOf("c2")),
                AskAnswerDraft("q2", selectedIds = listOf("t1", "t2")),
            ),
        )

        assertEquals("batch-1", body.batchRequestId)
        assertEquals(listOf("q1", "q2"), body.responses.map { it.questionId })
        assertEquals(listOf("c2"), body.responses[0].selectedIds)
        assertEquals(listOf("t1", "t2"), body.responses[1].selectedIds)
        assertNull(body.responses[0].fillInText)
        assertNull(body.responses[0].yes)
    }

    @Test
    fun `free text is trimmed and a blank answer is omitted`() {
        val body = QuestionAnswerBuilder.build(
            batchId = "b",
            answers = listOf(
                AskAnswerDraft("q1", fillInText = "  because  "),
                AskAnswerDraft("q2", fillInText = "   "),
            ),
        )

        assertEquals("because", body.responses[0].fillInText)
        assertNull(body.responses[1].fillInText)
        assertNull(body.responses[1].selectedIds)
    }

    @Test
    fun `yes no keeps false as a real answer`() {
        val body = QuestionAnswerBuilder.build(
            batchId = "b",
            answers = listOf(
                AskAnswerDraft("q1", yes = true),
                AskAnswerDraft("q2", yes = false),
            ),
        )

        assertEquals(true, body.responses[0].yes)
        assertEquals(false, body.responses[1].yes)
    }

    @Test
    fun `an empty selection list is omitted rather than sent as empty`() {
        val body = QuestionAnswerBuilder.build(
            batchId = "b",
            answers = listOf(AskAnswerDraft("q1", selectedIds = emptyList())),
        )

        assertNull(body.responses.single().selectedIds)
    }
}
