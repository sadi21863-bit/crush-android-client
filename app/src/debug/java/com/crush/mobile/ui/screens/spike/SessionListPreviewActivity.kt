package com.crush.mobile.ui.screens.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.crush.mobile.ui.screens.chat.BindingKind
import com.crush.mobile.ui.screens.chat.BindingView
import com.crush.mobile.ui.screens.chat.ChatListContent
import com.crush.mobile.ui.screens.chat.ChatViewState
import com.crush.mobile.ui.screens.chat.ComposerView
import com.crush.mobile.ui.screens.chat.EngineReadiness
import com.crush.mobile.ui.screens.chat.GateView
import com.crush.mobile.ui.screens.chat.HandoffView
import com.crush.mobile.ui.screens.chat.ModelCatalogueView
import com.crush.mobile.ui.screens.chat.ModelView
import com.crush.mobile.ui.screens.chat.NoticeView
import com.crush.mobile.ui.screens.chat.RunView
import com.crush.mobile.ui.screens.chat.SessionListView
import com.crush.mobile.ui.screens.chat.SessionView
import com.crush.mobile.ui.screens.chat.TranscriptView
import com.crush.mobile.ui.theme.CrushMobileTheme

/**
 * O11 (2026-11-22): a debug-only preview of the session-list loading row.
 *
 * The row is normally visible only inside the sub-second window between
 * `sessionsLoading = true` and the fetch returning. The injected
 * [com.crush.mobile.util.DebugStall] proves the host state is entered on
 * device; this activity renders the SAME product composable in that state so
 * the rendered row can be captured from the real device without an
 * instrumentation activity launch (API 29 blocks background activity starts
 * from the test process on this ROM) and without injected taps (which this ROM
 * offsets inside scrollable content).
 *
 * Debug source set only - nothing here is merged into a release build. The
 * manifest entry is exported only because this ROM's shell cannot start a
 * non-exported activity; the screen holds no data and takes no input:
 *
 *     adb shell am start -n com.crush.mobile/.ui.screens.spike.SessionListPreviewActivity
 *     adb shell am start -n com.crush.mobile/.ui.screens.spike.SessionListPreviewActivity --es surface chat
 */
class SessionListPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "chat" shows the Crush Chat surface (the legacy pool); anything else
        // shows a project's Code sessions. This pins both copies in one harness.
        val chatSurface = intent.getStringExtra("surface") == "chat"
        setContent {
            CrushMobileTheme(themeMode = "system") {
                ChatListContent(
                    state = if (chatSurface) loadingChatState() else loadingProjectState(),
                    onAction = {},
                )
            }
        }
    }

    /** The list-first Code surface with an empty, loading session list. */
    private fun loadingProjectState(): ChatViewState = loadingList(
        binding = BindingView(kind = BindingKind.Project, projectLabel = "TestProject3"),
        surface = "Code sessions",
    )

    /** The list-first Crush Chat surface with an empty, loading chat list. */
    private fun loadingChatState(): ChatViewState = loadingList(
        binding = BindingView(kind = BindingKind.Legacy, projectLabel = null),
        surface = "Crush Chat",
    )

    private fun loadingList(binding: BindingView, surface: String): ChatViewState = ChatViewState(
        session = SessionView(
            id = null,
            label = "New session",
            surface = surface,
            list = SessionListView(
                loading = true,
                items = emptyList(),
                unavailable = false,
            ),
        ),
        binding = binding,
        run = RunView(
            phase = "ready",
            readiness = EngineReadiness.Ready,
            streaming = false,
            stopNotice = null,
            engineReady = true,
        ),
        model = ModelView(
            providerId = "opencode-go",
            providerName = "OpenCode Go",
            selected = null,
            catalogue = ModelCatalogueView(items = emptyList(), error = null),
        ),
        transcript = TranscriptView(
            messages = emptyList(),
            toolsByCallId = emptyMap(),
            reports = emptyList(),
            fileChanges = null,
            fileChangesLabel = null,
        ),
        composer = ComposerView(
            draft = "",
            canType = true,
            canSend = false,
            canSubmitViaIme = false,
            streaming = false,
            enterSends = true,
            prewarm = false,
        ),
        gate = GateView(permission = null, question = null),
        handoff = HandoffView(
            sheet = null,
            destinations = emptyList(),
            notice = null,
            resume = null,
        ),
        notice = NoticeView(deleteError = null),
    )
}
