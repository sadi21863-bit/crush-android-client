package com.crush.mobile.ui.screens.onboarding

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.crush.mobile.domain.provider.Provider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2B5-01 (V2 plan §6.5): the Onboarding content layer renders from a
 * fabricated [OnboardingViewState] and host callbacks only.
 *
 * Per CORR-001, no test asserts key validation: the submit path reports only
 * what the host's write returned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class OnboardingContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val zen = Provider(
        id = "opencode-zen",
        displayName = "OpenCode Zen",
        isKnown = true,
        keyHint = "opencode.ai/zen",
    )

    private val groq = Provider(
        id = "groq",
        displayName = "Groq",
        isKnown = true,
        keyHint = "console.groq.com",
        hasFreeTier = true,
    )

    private fun state(selected: Provider = zen) = OnboardingViewState(
        providers = listOf(zen, groq),
        selectedProvider = selected,
    )

    private fun setContent(
        state: OnboardingViewState = state(),
        onSelectProvider: (String) -> Unit = {},
        onSubmit: (key: String, onResult: (Boolean) -> Unit) -> Unit = { _, _ -> },
        onCompleted: () -> Unit = {},
    ) {
        compose.setContent {
            OnboardingContent(
                state = state,
                onSelectProvider = onSelectProvider,
                onSubmit = onSubmit,
                onCompleted = onCompleted,
            )
        }
    }

    @Test
    fun `the heading promises provider models without a model count`() {
        setContent()

        // A5: no hardcoded catalogue number - the promise is qualified by the
        // provider the user actually connects.
        compose.onNodeWithText(
            "Connect a provider and chat with its available models"
        ).assertIsDisplayed()
        compose.onNodeWithText("70+", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the provider slot names the selected provider`() {
        setContent()

        compose.onNodeWithContentDescription("Provider: OpenCode Zen").assertIsDisplayed()
        compose.onNodeWithText("OpenCode Zen").assertIsDisplayed()
    }

    @Test
    fun `a free-tier provider is labelled in the slot and the menu`() {
        setContent(state(selected = groq))

        compose.onNodeWithContentDescription("Provider: Groq, free tier").assertIsDisplayed()
        compose.onNodeWithText("Groq (free tier)").assertIsDisplayed()

        compose.onNodeWithContentDescription("Provider: Groq, free tier").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("Groq (free tier)").assertCountEquals(2)
    }

    @Test
    fun `choosing a provider dispatches its id`() {
        val chosen = mutableListOf<String>()
        setContent(onSelectProvider = { chosen += it })

        compose.onNodeWithText("OpenCode Zen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Groq (free tier)").performClick()
        compose.waitForIdle()

        assertEquals(listOf(groq.id), chosen)
    }

    @Test
    fun `the key hint and placeholder follow the selected provider`() {
        setContent(state(selected = groq))

        compose.onNodeWithText("Get your key at console.groq.com").assertIsDisplayed()
        // The placeholder only renders while the field has focus (the floating
        // label occupies it otherwise), so focus it before asserting.
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("gsk_...", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a blank key keeps start chatting disabled`() {
        setContent()

        compose.onNodeWithText("Start chatting").assertIsNotEnabled()

        compose.onNode(hasSetTextAction()).performTextInput("   ")
        compose.waitForIdle()

        compose.onNodeWithText("Start chatting").assertIsNotEnabled()
    }

    @Test
    fun `submitting dispatches the trimmed key`() {
        val submitted = mutableListOf<String>()
        setContent(onSubmit = { key, _ -> submitted += key })

        compose.onNode(hasSetTextAction()).performTextInput("  oc_sk_test  ")
        compose.waitForIdle()
        compose.onNodeWithText("Start chatting").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf("oc_sk_test"), submitted)
    }

    @Test
    fun `saving disables the button and says what it is doing`() {
        setContent(onSubmit = { _, _ -> /* the host write is still in flight */ })

        compose.onNode(hasSetTextAction()).performTextInput("oc_sk_test")
        compose.waitForIdle()
        compose.onNodeWithText("Start chatting").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Saving key...").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Start chatting").assertDoesNotExist()
    }

    @Test
    fun `a failed save surfaces the error and restores the button`() {
        var result: ((Boolean) -> Unit)? = null
        setContent(onSubmit = { _, onResult -> result = onResult })

        compose.onNode(hasSetTextAction()).performTextInput("oc_sk_test")
        compose.waitForIdle()
        compose.onNodeWithText("Start chatting").performClick()
        compose.waitForIdle()
        compose.runOnIdle { result?.invoke(false) }

        compose.onNodeWithText("Could not save key - please try again").assertIsDisplayed()
        compose.onNodeWithText("Start chatting").assertIsEnabled()
    }

    @Test
    fun `a successful save advances and restores the button`() {
        var completions = 0
        var result: ((Boolean) -> Unit)? = null
        setContent(
            onSubmit = { _, onResult -> result = onResult },
            onCompleted = { completions += 1 },
        )

        compose.onNode(hasSetTextAction()).performTextInput("oc_sk_test")
        compose.waitForIdle()
        compose.onNodeWithText("Start chatting").performClick()
        compose.waitForIdle()
        compose.runOnIdle { result?.invoke(true) }

        assertEquals(1, completions)
        compose.onNodeWithText("Could not save key - please try again").assertDoesNotExist()
        compose.onNodeWithText("Start chatting").assertIsEnabled()
    }

    @Test
    fun `onboarding says the key is not checked before the first message`() {
        setContent()

        // CORR-001 is still open: saving does not validate. The screen must
        // not imply a check happened (V2-6-D6).
        compose.onNodeWithText(
            "Saved on this device. No check runs until your first message."
        ).assertIsDisplayed()
        compose.onNodeWithText("verified", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the reveal toggle flips the key mask`() {
        setContent()

        compose.onNode(hasSetTextAction()).performTextInput("oc_sk_test")
        compose.onNodeWithContentDescription("Show").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Hide").assertIsDisplayed()
    }
}
