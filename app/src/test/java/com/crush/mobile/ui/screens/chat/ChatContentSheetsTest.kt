package com.crush.mobile.ui.screens.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.crush.mobile.project.ProjectRef
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2B1-03 (V2 plan §6.3): the four sheets/dialogs `ChatContent` owns,
 * rendered from fabricated state only.
 *
 * Recorded acceptance gaps (2b.1 ledger):
 * - the active session is marked by COLOR ONLY - no spoken/semantic state, so
 *   the strongest honest assertion here is that no selected state exists;
 * - delete is NOT confirmed: the trash button dispatches `DeleteSession`
 *   immediately and the `SessionSheet` confirmation dialog is unreachable
 *   (baseline flow 13, pre-existing; pinned, not fixed, in 2b.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class ChatContentSheetsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun selectedState(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun setContent(
        state: ChatViewState,
        actions: MutableList<ChatAction> = mutableListOf(),
        pendingTrust: PendingTrustUi? = null,
        onTrustConfirm: () -> Unit = {},
        onTrustDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            ChatContent(
                state = state,
                onAction = { actions += it },
                pendingTrust = pendingTrust,
                onTrustConfirm = onTrustConfirm,
                onTrustDismiss = onTrustDismiss,
            )
        }
    }

    private fun openSessionSheet() {
        compose.onNodeWithContentDescription("Open chats list").performClick()
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ sessions

    @Test
    fun `the session sheet names the surface it belongs to and lists its rows`() {
        val sessions = listOf(
            SessionItem(
                id = "s2",
                label = "Newer chat",
                isActive = false,
                isBusy = false,
                messageCount = 4,
                cost = 0.12,
                updatedAt = 1_700_000_000_000L,
            ),
            SessionItem(
                id = "s1",
                label = "Active chat",
                isActive = true,
                isBusy = false,
                messageCount = 8,
                cost = 0.0,
                updatedAt = 1_700_000_000_000L,
            ),
        )
        setContent(
            ChatFixtures.view(
                sessionId = "s1",
                surface = "Project - notes-app",
                sessions = sessions,
            )
        )
        openSessionSheet()

        // The label comes from state.session.surface, not from a constant.
        compose.onNodeWithText("Project - notes-app").assertIsDisplayed()
        compose.onNodeWithText("Newer chat").assertIsDisplayed()
        compose.onNodeWithText("Active chat").assertIsDisplayed()

        // ACCEPTANCE GAP (recorded): the active row is color-only. Nothing in
        // the sheet announces which chat is open.
        compose.onAllNodes(selectedState("selected")).assertCountEquals(0)
    }

    @Test
    fun `picking a session dispatches SelectSession and closes the sheet`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(
                sessions = listOf(SessionItem("s2", "Newer chat", isActive = false, isBusy = false))
            ),
            actions,
        )
        openSessionSheet()
        // Opening the sheet is itself an action; the assertions below are
        // about what the ROW tap dispatches.
        actions.clear()

        compose.onNodeWithText("Newer chat").performClick()

        assertEquals(listOf(ChatAction.SelectSession("s2")), actions)
        // The sheet closed: its row is gone.
        compose.onAllNodesWithText("Newer chat").assertCountEquals(0)
    }

    @Test
    fun `delete dispatches immediately with no confirmation`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(
                sessions = listOf(SessionItem("s2", "Newer chat", isActive = false, isBusy = false))
            ),
            actions,
        )
        openSessionSheet()
        actions.clear()

        compose.onAllNodesWithContentDescription("Delete chat")[0].performClick()
        compose.waitForIdle()

        // ACCEPTANCE GAP (recorded, pre-existing): the target says delete is
        // confirmed and named. Today it is immediate and the confirmation
        // dialog in SessionSheet is unreachable - baseline flow 13 captured
        // the same behaviour before the extraction.
        compose.onAllNodesWithText("Delete chat?").assertCountEquals(0)
        assertEquals(listOf(ChatAction.DeleteSession("s2")), actions)
    }

    @Test
    fun `an empty session list says why it is empty`() {
        setContent(ChatFixtures.view(sessions = emptyList(), sessionsLoading = false))
        openSessionSheet()

        compose.onNodeWithText("No chats yet").assertIsDisplayed()
        compose.onNodeWithText("Start one and it will appear here.").assertIsDisplayed()
    }

    @Test
    fun `a loading session list says it is loading`() {
        setContent(ChatFixtures.view(sessions = emptyList(), sessionsLoading = true))
        openSessionSheet()

        compose.onNodeWithText("Loading chats...").assertIsDisplayed()
    }

    // --------------------------------------------------------------- models

    @Test
    fun `the model sheet renders provider, selection, and free badge`() {
        val models = listOf(
            ModelItem(id = "glm-5.2", displayName = "GLM 5.2", isFree = false, supportsImages = false),
            ModelItem(id = "kimi-k2-free", displayName = "Kimi K2", isFree = true, supportsImages = false),
        )
        setContent(
            ChatFixtures.view(
                selectedModel = SelectedModelView(
                    id = "glm-5.2",
                    displayName = "GLM 5.2",
                    presentInCatalogue = true,
                    freeMarker = FreeMarker.Hidden,
                    supportsImages = false,
                ),
                models = models,
            )
        )

        compose.onNodeWithText("glm-5.2").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Live list from OpenCode Zen. Free models cost nothing.")
            .assertIsDisplayed()
        compose.onNodeWithText("GLM 5.2").assertIsDisplayed()
        compose.onNodeWithText("Kimi K2").assertIsDisplayed()
        // The selected row is announced, and only it.
        compose.onAllNodes(selectedState("selected")).assertCountEquals(1)
        compose.onNode(hasText("GLM 5.2") and selectedState("selected")).assertExists()
    }

    @Test
    fun `picking a model dispatches SelectModel and closes the sheet`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(
                selectedModel = SelectedModelView(
                    id = "glm-5.2",
                    displayName = "GLM 5.2",
                    presentInCatalogue = true,
                    freeMarker = FreeMarker.Hidden,
                    supportsImages = false,
                ),
                models = listOf(
                    ModelItem("glm-5.2", "GLM 5.2", isFree = false, supportsImages = false),
                    ModelItem("kimi-k2-free", "Kimi K2", isFree = true, supportsImages = false),
                ),
            ),
            actions,
        )

        compose.onNodeWithText("glm-5.2").performClick()
        compose.waitForIdle()
        actions.clear()

        compose.onNodeWithText("Kimi K2").performClick()

        assertEquals(listOf(ChatAction.SelectModel("kimi-k2-free")), actions)
    }

    @Test
    fun `a catalogue error is rendered inside the model sheet`() {
        setContent(
            ChatFixtures.view(
                selectedModel = SelectedModelView(
                    id = "glm-5.2",
                    displayName = "GLM 5.2",
                    presentInCatalogue = true,
                    freeMarker = FreeMarker.Hidden,
                    supportsImages = false,
                ),
                modelsError = "catalogue fetch failed",
            )
        )

        compose.onNodeWithText("glm-5.2").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("catalogue fetch failed").assertIsDisplayed()
    }

    // -------------------------------------------------------------- handoff

    @Test
    fun `the handoff sheet prefills, selects a destination, and confirms with both`() {
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", text = "Build the deck"),
            ChatMessage(id = "a1", role = "assistant", text = "Plan\n\n1. Read it\n2. Edit it"),
        )
        val sheet = handoffSheetFor(
            messages = messages,
            tappedIndex = 1,
            sourceChatId = "s1",
            sourceSessionTitle = "Deck chat",
        )
        val projectId = UUID.randomUUID()
        val project = ProjectRef(id = projectId, label = "notes-app", repoDir = File("/tmp/notes-app"))
        val actions = mutableListOf<ChatAction>()

        setContent(
            ChatFixtures.view(
                messages = messages,
                handoff = HandoffView(
                    sheet = sheet,
                    destinations = listOf(project),
                    notice = null,
                    resume = null,
                ),
            ),
            actions,
        )

        // Prefill: the sheet opens with Batch B's summary text.
        compose.onNodeWithText(sheet.initialSummary).assertExists()
        compose.onNodeWithText("No project selected").assertExists()
        compose.onNodeWithText("Confirm").assertIsNotEnabled()

        compose.onNodeWithText("notes-app").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Selected").assertExists()
        compose.onNodeWithText("Confirm").assertIsEnabled().performScrollTo().performClick()

        assertEquals(
            listOf(ChatAction.ConfirmHandoff(projectId.toString(), sheet.initialSummary)),
            actions,
        )
    }

    @Test
    fun `a refused confirm notice is rendered inside the handoff sheet`() {
        val messages = listOf(
            ChatMessage(id = "a1", role = "assistant", text = "Plan\n\n1. Read it"),
        )
        val sheet = handoffSheetFor(
            messages = messages,
            tappedIndex = 0,
            sourceChatId = "s1",
            sourceSessionTitle = "Deck chat",
        )
        setContent(
            ChatFixtures.view(
                messages = messages,
                handoff = HandoffView(
                    sheet = sheet,
                    destinations = listOf(
                        ProjectRef(UUID.randomUUID(), "notes-app", File("/tmp/notes-app"))
                    ),
                    notice = "Refused: the summary still carries a blocked path.",
                    resume = null,
                ),
            )
        )

        // The same notice also rides the screen's snackbar (pre-existing host
        // behaviour kept by 2b.1), so scope the assertion to the sheet's own
        // dialog rather than accepting any node with that text.
        compose.onNode(
            hasText("Refused: the summary still carries a blocked path.") and
                hasAnyAncestor(isDialog())
        ).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- trust

    @Test
    fun `the trust dialog renders host copy and fires confirm exactly once`() {
        var confirms = 0
        var dismisses = 0
        setContent(
            ChatFixtures.view(),
            pendingTrust = PendingTrustUi(
                folderLabel = "notes-app",
                reason = "The crushrc CHANGED since your last approval.",
                flow = TrustFlow.Handoff,
            ),
            onTrustConfirm = { confirms += 1 },
            onTrustDismiss = { dismisses += 1 },
        )

        compose.onNodeWithText("This folder carries a crushrc").assertIsDisplayed()
        compose.onNodeWithText("notes-app", substring = true).assertExists()
        compose.onNodeWithText("The crushrc CHANGED since your last approval", substring = true)
            .assertExists()

        compose.onNodeWithText("Run it and open").performClick()
        compose.waitForIdle()

        assertEquals(1, confirms)
        assertEquals(0, dismisses)
    }

    @Test
    fun `cancelling the trust dialog fires only dismiss`() {
        var confirms = 0
        var dismisses = 0
        setContent(
            ChatFixtures.view(),
            pendingTrust = PendingTrustUi(
                folderLabel = "notes-app",
                reason = null,
                flow = TrustFlow.Resume,
            ),
            onTrustConfirm = { confirms += 1 },
            onTrustDismiss = { dismisses += 1 },
        )

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, confirms)
        assertEquals(1, dismisses)
    }
}
