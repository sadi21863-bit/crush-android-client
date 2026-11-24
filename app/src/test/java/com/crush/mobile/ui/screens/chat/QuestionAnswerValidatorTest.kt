package com.crush.mobile.ui.screens.chat

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * V2-15 Phase E remediation (E5): the ViewModel-boundary answer validator.
 * A malformed draft must be rejected before an operation is claimed or HTTP is
 * issued; `yes = false` remains a valid yes/no answer.
 */
class QuestionAnswerValidatorTest {

    private fun batch() = AskBatch(
        batchId = "batch-1",
        prompts = listOf(
            AskPrompt("q1", AskKind.YES_NO, "Continue?"),
            AskPrompt(
                "q2", AskKind.SINGLE_CHOICE, "Pick one",
                choices = listOf(AskChoice("c1", "A"), AskChoice("c2", "B")),
            ),
            AskPrompt(
                "q3", AskKind.MULTI_CHOICE, "Pick many",
                choices = listOf(AskChoice("m1", "M1"), AskChoice("m2", "M2")),
            ),
            AskPrompt("q4", AskKind.FREE_TEXT, "Why?"),
        ),
    )

    private fun validAnswers() = listOf(
        AskAnswerDraft("q1", yes = false),
        AskAnswerDraft("q2", selectedIds = listOf("c2")),
        AskAnswerDraft("q3", selectedIds = listOf("m1", "m2")),
        AskAnswerDraft("q4", fillInText = "  because  "),
    )

    @Test
    fun `a complete well-shaped batch is valid and false yes is preserved`() {
        assertNull(QuestionAnswerValidator.validate(batch(), validAnswers()))
    }

    @Test
    fun `missing or duplicate prompt ids are rejected`() {
        val missing = validAnswers().dropLast(1)
        assertNotNull(QuestionAnswerValidator.validate(batch(), missing))

        val duplicate = validAnswers().toMutableList().apply {
            this[1] = this[1].copy(promptId = "q1")
        }
        assertNotNull(QuestionAnswerValidator.validate(batch(), duplicate))
    }

    @Test
    fun `an unexpected prompt id is rejected`() {
        val unexpected = validAnswers().toMutableList().apply {
            this[3] = this[3].copy(promptId = "q-extra")
        }
        assertNotNull(QuestionAnswerValidator.validate(batch(), unexpected))
    }

    @Test
    fun `a choice id outside its prompt is rejected`() {
        val wrong = validAnswers().toMutableList().apply {
            this[1] = this[1].copy(selectedIds = listOf("m1"))
        }
        assertNotNull(QuestionAnswerValidator.validate(batch(), wrong))
    }

    @Test
    fun `answer shape must match the prompt kind`() {
        val yesNoWithChoice = validAnswers().toMutableList().apply {
            this[0] = this[0].copy(selectedIds = listOf("c1"))
        }
        assertNotNull(QuestionAnswerValidator.validate(batch(), yesNoWithChoice))

        val singleWithTwo = validAnswers().toMutableList().apply {
            this[1] = this[1].copy(selectedIds = listOf("c1", "c2"))
        }
        assertNotNull(QuestionAnswerValidator.validate(batch(), singleWithTwo))

        val emptyMulti = validAnswers().toMutableList().apply {
            this[2] = this[2].copy(selectedIds = emptyList())
        }
        assertNotNull(QuestionAnswerValidator.validate(batch(), emptyMulti))

        val blankFreeText = validAnswers().toMutableList().apply {
            this[3] = this[3].copy(fillInText = "   ")
        }
        assertNotNull(QuestionAnswerValidator.validate(batch(), blankFreeText))
    }

    @Test
    fun `duplicate selected ids in a multi choice are rejected`() {
        val duplicate = validAnswers().toMutableList().apply {
            this[2] = this[2].copy(selectedIds = listOf("m1", "m1"))
        }
        assertNotNull(QuestionAnswerValidator.validate(batch(), duplicate))
    }

    @Test
    fun `an unknown prompt cannot be answered`() {
        val unknown = AskBatch(
            batchId = "batch-1",
            prompts = listOf(AskPrompt("q1", AskKind.UNKNOWN, "Unsupported")),
        )
        assertNotNull(
            QuestionAnswerValidator.validate(
                unknown,
                listOf(AskAnswerDraft("q1", fillInText = "guess")),
            ),
        )
    }
}
