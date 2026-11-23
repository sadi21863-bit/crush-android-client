package com.crush.mobile.ui.screens.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Accessibility semantics on the chat surface (V2-0 sign-off §3 carry-over).
 *
 * This is deliberately NOT a TalkBack run: no screen reader, no device. It pins
 * the properties a screen reader CONSUMES so a regression fails here instead of
 * on a phone:
 *
 *  - icon-only controls announce an ACTION ("Open chats list"), never a widget
 *    or a bare glyph;
 *  - the model picker's changing visible label stays out of the spoken name and
 *    its current value rides `stateDescription` instead;
 *  - gated sends stay visible but disabled, and streaming swaps Send for Stop
 *    (a hidden control is worse than a disabled one for assistive tech);
 *  - the reasoning disclosure reports Collapsed/Expanded as state.
 *
 * 48dp touch targets are NOT asserted here: this artifact's ui-test version has
 * no touch-bounds matcher, and layout bounds are not the touch target. The token
 * floor is pinned by `CrushTokensTest` and the component sets by
 * `CrushComponentsTest` (V2-2 record).
 *
 * Device/TalkBack verification remains NOT PERFORMED and is recorded as such in
 * `docs/v2/V2-COMPLETION-LEDGER.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ChatAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(state: ChatViewState) {
        compose.setContent {
            ChatContent(
                state = state,
                onAction = {},
                pendingTrust = null,
                onTrustConfirm = {},
                onTrustDismiss = {},
            )
        }
    }

    @Test
    fun `chrome controls announce actions and the picker reports its value`() {
        setContent(ChatFixtures.view())

        compose.onNodeWithContentDescription("Open chats list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose model")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Current: Model",
                )
            )
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun `a gated send stays present but disabled`() {
        setContent(ChatFixtures.view(draft = "typed", canSend = false))

        compose.onNodeWithContentDescription("Send")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun `streaming swaps send for stop`() {
        setContent(
            ChatFixtures.view(
                draft = "typed",
                streaming = true,
                canType = false,
                canSend = false,
            )
        )

        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
    }

    @Test
    fun `the reasoning disclosure announces collapsed and expanded`() {
        setContent(
            ChatFixtures.view(
                messages = listOf(
                    ChatMessage(
                        id = "a1",
                        role = "assistant",
                        text = "Answer ready.",
                        thinking = "hidden thought",
                    )
                )
            )
        )

        val collapsed = SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            "Collapsed",
        )
        val expanded = SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            "Expanded",
        )

        compose.onNodeWithText("Reasoning").assert(collapsed)
        compose.onNodeWithText("hidden thought").assertDoesNotExist()

        compose.onNodeWithText("Reasoning").performClick()

        compose.onNodeWithText("Reasoning").assert(expanded)
        compose.onNodeWithText("hidden thought").assertIsDisplayed()
    }
}
