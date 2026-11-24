package com.crush.mobile.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A12: the answered question dialog. These tests drive the real Compose
 * surface and assert the exact [ChatAction.SubmitQuestion] draft, so a UI
 * change cannot silently send a different choice id than the one shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class QuestionDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private fun batch(vararg prompts: AskPrompt) = AskBatch(
        batchId = "batch-1",
        prompts = prompts.toList(),
    )

    private fun setContent(question: QuestionView, actions: MutableList<ChatAction>) {
        compose.setContent {
            ChatContent(
                state = ChatFixtures.view(question = question),
                onAction = { actions += it },
                pendingTrust = null,
                onTrustConfirm = {},
                onTrustDismiss = {},
            )
        }
    }

    private fun single() = AskPrompt(
        id = "q1",
        kind = AskKind.SINGLE_CHOICE,
        text = "Which file?",
        choices = listOf(
            AskChoice("c1", "A.kt"),
            AskChoice("c2", "B.kt"),
        ),
    )

    @Test
    fun `single choice submits the selected id`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            QuestionView("batch-1", "Which file?", batch(single())),
            actions,
        )

        compose.onNodeWithText("B.kt").performClick()
        compose.onNodeWithTag("question-submit").assertIsEnabled().performClick()

        assertEquals(
            listOf(
                ChatAction.SubmitQuestion(
                    listOf(AskAnswerDraft("q1", selectedIds = listOf("c2")))
                )
            ),
            actions,
        )
    }

    @Test
    fun `multi choice submits every selected id`() {
        val actions = mutableListOf<ChatAction>()
        val multi = AskPrompt(
            id = "q1",
            kind = AskKind.MULTI_CHOICE,
            text = "Which tests?",
            choices = listOf(
                AskChoice("t1", "Unit"),
                AskChoice("t2", "UI"),
            ),
        )
        setContent(QuestionView("batch-1", "Which tests?", batch(multi)), actions)

        compose.onNodeWithTag("question-choice-t1").performClick()
        compose.onNodeWithTag("question-choice-t2").performClick()
        compose.onNodeWithTag("question-submit").performClick()

        val submitted = actions.single() as ChatAction.SubmitQuestion
        assertEquals(listOf("q1"), submitted.answers.map { it.promptId })
        assertEquals(setOf("t1", "t2"), submitted.answers.single().selectedIds.toSet())
    }

    @Test
    fun `yes no submits the boolean`() {
        val actions = mutableListOf<ChatAction>()
        val yesNo = AskPrompt(id = "q1", kind = AskKind.YES_NO, text = "Continue?")
        setContent(QuestionView("batch-1", "Continue?", batch(yesNo)), actions)

        compose.onNodeWithTag("question-yes-q1").performClick()
        compose.onNodeWithTag("question-submit").performClick()

        assertEquals(
            listOf(
                ChatAction.SubmitQuestion(
                    listOf(AskAnswerDraft("q1", yes = true))
                )
            ),
            actions,
        )
    }

    @Test
    fun `submit stays disabled until every prompt is answered`() {
        val actions = mutableListOf<ChatAction>()
        val second = AskPrompt(
            id = "q2",
            kind = AskKind.SINGLE_CHOICE,
            text = "And which test?",
            choices = listOf(AskChoice("d1", "D.kt"), AskChoice("d2", "E.kt")),
        )
        setContent(QuestionView("batch-1", "Two questions", batch(single(), second)), actions)

        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.onNodeWithText("A.kt").performClick()
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.onNodeWithText("D.kt").performClick()
        compose.onNodeWithTag("question-submit").assertIsEnabled()
    }

    @Test
    fun `cancel releases and never submits`() {
        val actions = mutableListOf<ChatAction>()
        setContent(QuestionView("batch-1", "Which file?", batch(single())), actions)

        compose.onNodeWithTag("question-cancel").performClick()

        assertEquals(listOf(ChatAction.ReleaseQuestion), actions)
    }

    @Test
    fun `an unanswerable prompt disables submit but cancel remains`() {
        val actions = mutableListOf<ChatAction>()
        val unknown = AskPrompt(
            id = "q1",
            kind = AskKind.UNKNOWN,
            text = "Order these",
        )
        setContent(QuestionView("batch-1", "Order these", batch(unknown)), actions)

        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.onNodeWithTag("question-cancel").assertIsEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `a replacement batch starts with no selection from the old batch`() {
        val actions = mutableListOf<ChatAction>()
        var question by mutableStateOf(
            QuestionView("batch-1", "First", batch(single()))
        )
        compose.setContent {
            ChatContent(
                state = ChatFixtures.view(question = question),
                onAction = { actions += it },
                pendingTrust = null,
                onTrustConfirm = {},
                onTrustDismiss = {},
            )
        }

        compose.onNodeWithText("A.kt").performClick()
        compose.onNodeWithTag("question-submit").assertIsEnabled()

        compose.runOnIdle {
            question = QuestionView(
                "batch-2",
                "Second",
                AskBatch(
                    batchId = "batch-2",
                    prompts = listOf(
                        AskPrompt(
                            id = "q1",
                            kind = AskKind.SINGLE_CHOICE,
                            text = "Second?",
                            choices = listOf(
                                AskChoice("c3", "C.kt"),
                                AskChoice("c4", "D.kt"),
                            ),
                        )
                    ),
                ),
            )
        }
        compose.waitForIdle()

        // The old A.kt selection must not satisfy the new batch's q1.
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `operations in flight disable submit and cancel`() {
        val actions = mutableListOf<ChatAction>()
        var question by mutableStateOf(
            QuestionView("batch-1", "First", batch(single()))
        )
        compose.setContent {
            ChatContent(
                state = ChatFixtures.view(question = question),
                onAction = { actions += it },
                pendingTrust = null,
                onTrustConfirm = {},
                onTrustDismiss = {},
            )
        }

        compose.onNodeWithText("A.kt").performClick()
        compose.onNodeWithTag("question-submit").assertIsEnabled()

        compose.runOnIdle {
            question = question.copy(operation = QuestionOperation.SUBMIT)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.onNodeWithTag("question-cancel").assertIsNotEnabled()

        compose.runOnIdle {
            question = question.copy(operation = QuestionOperation.CANCEL)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.onNodeWithTag("question-cancel").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `a new batch does not inherit an in-flight operation`() {
        val actions = mutableListOf<ChatAction>()
        var question by mutableStateOf(
            QuestionView(
                "batch-1",
                "First",
                batch(single()),
                operation = QuestionOperation.SUBMIT,
            )
        )
        compose.setContent {
            ChatContent(
                state = ChatFixtures.view(question = question),
                onAction = { actions += it },
                pendingTrust = null,
                onTrustConfirm = {},
                onTrustDismiss = {},
            )
        }

        compose.runOnIdle {
            question = QuestionView(
                "batch-2",
                "Second",
                AskBatch(
                    batchId = "batch-2",
                    prompts = listOf(
                        AskPrompt(
                            id = "q1",
                            kind = AskKind.SINGLE_CHOICE,
                            text = "Second?",
                            choices = listOf(
                                AskChoice("c3", "C.kt"),
                                AskChoice("c4", "D.kt"),
                            ),
                        )
                    ),
                ),
                operation = null,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("C.kt").performClick()
        compose.onNodeWithTag("question-submit").assertIsEnabled()
        compose.onNodeWithTag("question-cancel").assertIsEnabled()
        assertTrue(actions.isEmpty())
    }
}
