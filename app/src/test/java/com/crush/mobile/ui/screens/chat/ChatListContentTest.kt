package com.crush.mobile.ui.screens.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V2-5 IA: the list-first surface used by Crush Chat and by a project's Code
 * sessions. It renders from the same ChatViewState/action seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class ChatListContentTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        state: ChatViewState,
        actions: MutableList<ChatAction> = mutableListOf(),
    ) {
        compose.setContent {
            ChatListContent(state = state, onAction = { actions += it })
        }
    }

    @Test
    fun `Crush Chat lists legacy chats and starts with a New chat action`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Legacy,
                sessions = listOf(
                    SessionItem(
                        id = "s2",
                        label = "Plan the week",
                        isActive = false,
                        isBusy = false,
                        messageCount = 4,
                        updatedAt = 1_700_000_000_000L,
                    ),
                    SessionItem(
                        id = "s1",
                        label = "Earlier chat",
                        isActive = true,
                        isBusy = false,
                        messageCount = 8,
                        updatedAt = 1_700_000_100_000L,
                    ),
                ),
            ),
            actions,
        )

        compose.onNodeWithText("Crush Chat").assertIsDisplayed()
        compose.onNodeWithText("Chats").assertIsDisplayed()
        compose.onNodeWithText("Plan the week").assertIsDisplayed()
        compose.onNodeWithText("Earlier chat").assertIsDisplayed()
        compose.onNodeWithText("Current").assertExists()

        compose.onNodeWithText("Plan the week").performClick()
        compose.onNodeWithText("New chat").performClick()

        assertEquals(
            listOf(ChatAction.SelectSession("s2"), ChatAction.NewChat),
            actions,
        )
    }

    @Test
    fun `a project lists its own sessions with Review and New session`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Project,
                projectLabel = "notes-app",
                sessions = listOf(
                    SessionItem("p1", "Fix the parser", isActive = true, isBusy = false),
                ),
            ),
            actions,
        )

        compose.onNodeWithText("notes-app").assertIsDisplayed()
        compose.onNodeWithText("Code sessions").assertIsDisplayed()
        compose.onNodeWithText("Fix the parser").assertIsDisplayed()
        compose.onNodeWithText("New session").assertExists()

        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("New session").performClick()
        compose.onNodeWithText("Fix the parser").performClick()

        assertEquals(
            listOf(
                ChatAction.OpenReview,
                ChatAction.NewChat,
                ChatAction.SelectSession("p1"),
            ),
            actions,
        )
    }

    @Test
    fun `a loading project list says sessions`() {
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Project,
                projectLabel = "notes-app",
                sessionsLoading = true,
            )
        )

        compose.onNodeWithText("Loading sessions...").assertIsDisplayed()
        compose.onNodeWithText("Loading chats...").assertDoesNotExist()
    }

    @Test
    fun `a loading chat list says chats`() {
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Legacy,
                sessionsLoading = true,
            )
        )

        compose.onNodeWithText("Loading chats...").assertIsDisplayed()
        compose.onNodeWithText("Loading sessions...").assertDoesNotExist()
    }

    @Test
    fun `an unavailable list does not claim the chat pool is empty`() {
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Legacy,
                sessions = emptyList(),
                sessionsUnavailable = true,
            )
        )

        compose.onNodeWithText("Chats aren't available", substring = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText("No chats yet").assertCountEquals(0)
    }

    @Test
    fun `a stopped engine does not claim the list is empty`() {
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Legacy,
                sessions = emptyList(),
                engineReady = false,
                prewarm = false,
            )
        )

        compose.onNodeWithText("Chats aren't available", substring = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText("No chats yet").assertCountEquals(0)
    }

    @Test
    fun `a stopped project engine offers a Start engine action`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Project,
                projectLabel = "notes-app",
                sessions = emptyList(),
                sessionsUnavailable = true,
                engineReady = false,
                engineStartable = true,
            ),
            actions,
        )

        compose.onNodeWithText("Start engine").assertIsDisplayed().performClick()
        assertEquals(listOf(ChatAction.StartEngine), actions)
    }

    @Test
    fun `a live engine has no Start engine button`() {
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Project,
                projectLabel = "notes-app",
                sessions = listOf(SessionItem("p1", "Fix the parser", isActive = true, isBusy = false)),
            )
        )

        compose.onAllNodesWithText("Start engine").assertCountEquals(0)
    }

    @Test
    fun `a refused start keeps the button and names the reason`() {
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Project,
                projectLabel = "notes-app",
                sessions = emptyList(),
                sessionsUnavailable = true,
                engineReady = false,
                engineStartable = true,
                engineStartError = "at most 2 live projects",
            )
        )

        compose.onNodeWithText("Start engine").assertIsDisplayed()
        compose.onNodeWithText("at most 2 live projects").assertIsDisplayed()
    }

    @Test
    fun `a recovered ambiguous run is visible as unresolved and detached`() {
        setContent(
            ChatFixtures.view(
                bindingKind = BindingKind.Project,
                projectLabel = "notes-app",
                recovered = listOf(
                    RecoveredOutcomeView(
                        id = "e1",
                        statusLabel = "send unclear",
                        detail = "recovery:send_ambiguous",
                        detached = true,
                        requiresUserAction = true,
                        active = false,
                    ),
                ),
            )
        )

        compose.onNodeWithText(
            "Recovered run: send unclear - detached from current engine - " +
                "your decision needed - not active work (recovery:send_ambiguous)",
        ).assertIsDisplayed()
    }
}
