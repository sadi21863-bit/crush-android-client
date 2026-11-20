package com.crush.mobile.ui.screens.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.crush.mobile.data.crush.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2B1-01 (V2 plan §6.3): the transcript half of `ChatContent`.
 *
 * Everything under test is rendered from a fabricated [ChatViewState] - no
 * container, no ViewModel, no Context - which is the whole point of the 2b.1
 * seam. Compose + Robolectric as §6.2 prescribes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ChatContentTranscriptTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        state: ChatViewState,
        actions: MutableList<ChatAction> = mutableListOf(),
    ) {
        compose.setContent {
            ChatContent(
                state = state,
                onAction = { actions += it },
                pendingTrust = null,
                onTrustConfirm = {},
                onTrustDismiss = {},
            )
        }
    }

    @Test
    fun `empty cold-start state explains how the engine starts`() {
        setContent(
            ChatFixtures.view(
                messages = emptyList(),
                prewarm = false,
                engineReady = false,
                phase = "engine stopped",
            )
        )

        // With prewarm off the bare phase wording ("engine stopped") is the
        // user's own choice, so the empty state must explain the way out and
        // must not repeat the scary phase text as its body.
        compose.onNodeWithText("Start a conversation").assertIsDisplayed()
        compose.onNodeWithText("Type below - the agent starts when you begin typing.")
            .assertIsDisplayed()
    }

    @Test
    fun `a failed engine empty state shows the failure, not cold-start copy`() {
        setContent(
            ChatFixtures.view(
                messages = emptyList(),
                phase = "engine failed: no port",
                engineReady = false,
                readiness = EngineReadiness.Failed,
                prewarm = true,
            )
        )

        compose.onNodeWithText("Agent unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Start a conversation").assertCountEquals(0)
    }

    @Test
    fun `the header never shows a stale connecting phase once ready`() {
        // F-E: the old header printed run.phase verbatim. Readiness comes from
        // the same snapshot as the binding, so a ready engine reads "ready".
        setContent(ChatFixtures.view(phase = "connecting to agent", engineReady = true))

        compose.onNodeWithText("Chat - no tools \u00b7 ready").assertIsDisplayed()
        compose.onAllNodesWithText("connecting to agent").assertCountEquals(0)
    }

    @Test
    fun `the model picker is visible on a new chat with no selection`() {
        val actions = mutableListOf<ChatAction>()
        setContent(ChatFixtures.view(selectedModel = null), actions)

        // F-C: the old button rendered an empty id string and was invisible.
        compose.onNodeWithText("Model").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose model").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            "Live list from OpenCode Zen. FREE marks a model listed at no cost."
        ).assertIsDisplayed()
        assertTrue(actions.contains(ChatAction.OpenModels))
    }

    @Test
    fun `populated transcript renders both roles and marks the assistant affordances`() {
        setContent(
            ChatFixtures.view(
                messages = listOf(
                    ChatMessage(id = "u1", role = "user", text = "Assemble the deck."),
                    ChatMessage(id = "a1", role = "assistant", text = "Deck assembled."),
                )
            )
        )

        compose.onNodeWithText("Assemble the deck.").assertIsDisplayed()
        compose.onNodeWithText("Deck assembled.").assertIsDisplayed()

        // V2-4 D5: role is now visible text, not only fill colour/geometry.
        compose.onAllNodesWithText("You").assertCountEquals(1)
        compose.onAllNodesWithText("Crush").assertCountEquals(1)

        // The assistant-only actions row still proves the affordance is on the
        // completed assistant row and nowhere else.
        compose.onAllNodesWithText("Copy message").assertCountEquals(1)
        compose.onNodeWithText("Copy message").assertIsDisplayed()
    }

    @Test
    fun `streaming shows Stop and not Send`() {
        setContent(
            ChatFixtures.view(
                messages = listOf(
                    ChatMessage(id = "a1", role = "assistant", text = "Working", isStreaming = true),
                ),
                streaming = true,
                engineReady = true,
            )
        )

        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Send").assertCountEquals(0)
    }

    @Test
    fun `a finish flag without a result still reads running`() {
        // The projection keeps an unfollowed call RUNNING (T2A-01); content
        // must not invent a completion chip for it either.
        val tool = ToolActivity(
            id = "call-1",
            name = "bash",
            args = "{\"command\":\"ls\"}",
            state = ToolState.RUNNING,
            output = "",
            finishedAt = 0L,
        )
        setContent(
            ChatFixtures.view(
                messages = listOf(
                    ChatMessage(id = "a1", role = "assistant", text = "Checking.", tools = listOf(tool)),
                )
            )
        )

        compose.onNodeWithContentDescription("running").assertExists()
        compose.onAllNodesWithContentDescription("completed").assertCountEquals(0)
    }

    @Test
    fun `retry on a failed reply re-sends the preceding user prompt`() {
        val actions = mutableListOf<ChatAction>()
        setContent(
            ChatFixtures.view(
                messages = listOf(
                    ChatMessage(id = "u1", role = "user", text = "Re-read the plan."),
                    ChatMessage(
                        id = "a1",
                        role = "assistant",
                        text = "partial out",
                        error = "engine exited (code 1)",
                    ),
                ),
                engineReady = true,
            ),
            actions,
        )

        compose.onNodeWithText("This reply failed").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertEquals(listOf(ChatAction.Retry("Re-read the plan.")), actions)
    }

    @Test
    fun `the free marker follows the selected model and is absent otherwise`() {
        setContent(
            ChatFixtures.view(
                selectedModel = SelectedModelView(
                    id = "deepseek-v4.1-flash-free",
                    displayName = "DeepSeek",
                    presentInCatalogue = true,
                    freeMarker = FreeMarker.Shown,
                    supportsImages = null,
                )
            )
        )

        compose.onNodeWithText("FREE").assertIsDisplayed()
        // V2-4 D3: the human display name wins over the raw id.
        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        compose.onAllNodesWithText("deepseek-v4.1-flash").assertCountEquals(0)
    }

    @Test
    fun `the picker falls back to a suffix-stripped id when the name is blank`() {
        setContent(
            ChatFixtures.view(
                selectedModel = SelectedModelView(
                    id = "deepseek-v4.1-flash-free",
                    displayName = "",
                    presentInCatalogue = true,
                    freeMarker = FreeMarker.Shown,
                    supportsImages = null,
                )
            )
        )

        // The suffix is stripped from the title, not duplicated into it.
        compose.onNodeWithText("deepseek-v4.1-flash").assertIsDisplayed()
    }

    @Test
    fun `no free marker is rendered for a paid model`() {
        setContent(
            ChatFixtures.view(
                selectedModel = SelectedModelView(
                    id = "glm-5.2",
                    displayName = "GLM",
                    presentInCatalogue = true,
                    freeMarker = FreeMarker.Hidden,
                    supportsImages = null,
                )
            )
        )

        compose.onAllNodesWithText("FREE").assertCountEquals(0)
        compose.onNodeWithText("GLM").assertIsDisplayed()
    }

    @Test
    fun `the resume bar shows only when the target is not the active session`() {
        val hint = HandoffResumeHint(
            handoffId = "h1",
            targetSessionId = "code-1",
            targetLabel = "notes-app",
        )
        setContent(
            ChatFixtures.view(
                sessionId = "chat-1",
                messages = listOf(ChatMessage(id = "a1", role = "assistant", text = "Plan done.")),
                handoff = HandoffView(
                    sheet = null,
                    destinations = emptyList(),
                    notice = null,
                    resume = hint,
                ),
            )
        )

        compose.onNodeWithText("Continue in Code").assertIsDisplayed()
    }

    @Test
    fun `the resume bar is hidden when the active session is the target`() {
        val hint = HandoffResumeHint(
            handoffId = "h1",
            targetSessionId = "chat-1",
            targetLabel = "notes-app",
        )
        setContent(
            ChatFixtures.view(
                sessionId = "chat-1",
                messages = listOf(ChatMessage(id = "a1", role = "assistant", text = "Plan done.")),
                handoff = HandoffView(
                    sheet = null,
                    destinations = emptyList(),
                    notice = null,
                    resume = hint,
                ),
            )
        )

        compose.onAllNodesWithText("Continue in Code").assertCountEquals(0)
    }
}

/**
 * Fabricated projections for the 2b.1 content tests. Deliberately NOT a
 * production helper: every default here is a test-only choice.
 */
internal object ChatFixtures {

    fun view(
        sessionId: String? = "s1",
        label: String = "Retry the deck",
        surface: String = "Chats - conversation only",
        messages: List<ChatMessage> = emptyList(),
        phase: String = "ready",
        engineReady: Boolean = true,
        readiness: EngineReadiness? = null,
        streaming: Boolean = false,
        canType: Boolean = true,
        canSend: Boolean = true,
        draft: String = "",
        prewarm: Boolean = true,
        enterSends: Boolean = false,
        selectedModel: SelectedModelView? = null,
        models: List<ModelItem> = emptyList(),
        modelsError: String? = null,
        modelsLoading: Boolean = false,
        permission: PermissionRequest? = null,
        question: QuestionView? = null,
        handoff: HandoffView = HandoffView(null, emptyList(), null, null),
        sessions: List<SessionItem> = emptyList(),
        sessionsLoading: Boolean = false,
        sessionsUnavailable: Boolean = false,
        deleteError: String? = null,
    ): ChatViewState = ChatViewState(
        session = SessionView(
            id = sessionId,
            label = label,
            surface = surface,
            list = SessionListView(
                loading = sessionsLoading,
                items = sessions,
                unavailable = sessionsUnavailable,
            ),
        ),
        binding = BindingView(kind = BindingKind.ChatPool, projectLabel = null),
        run = RunView(
            phase = phase,
            readiness = readiness
                ?: if (engineReady) EngineReadiness.Ready else EngineReadiness.Starting,
            streaming = streaming,
            stopNotice = null,
            engineReady = engineReady,
        ),
        model = ModelView(
            providerId = "opencode",
            providerName = "OpenCode Zen",
            selected = selectedModel,
            catalogue = ModelCatalogueView(
                items = models,
                error = modelsError,
                loading = modelsLoading,
            ),
        ),
        transcript = TranscriptView(
            messages = messages,
            toolsByCallId = messages.flatMap { it.tools }.associateBy { it.id },
            reports = messages.flatMap { it.reports },
            fileChanges = null,
            fileChangesLabel = null,
        ),
        composer = ComposerView(
            draft = draft,
            canType = canType,
            canSend = canSend,
            canSubmitViaIme = canType && canSend,
            streaming = streaming,
            enterSends = enterSends,
            prewarm = prewarm,
        ),
        gate = GateView(permission = permission, question = question),
        handoff = handoff,
        notice = NoticeView(deleteError = deleteError),
    )
}
