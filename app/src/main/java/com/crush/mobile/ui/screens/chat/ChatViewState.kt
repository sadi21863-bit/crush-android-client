package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.crush.PermissionRequest
import com.crush.mobile.data.crush.SubAgentReport
import com.crush.mobile.domain.file.FileChangeSet
import com.crush.mobile.project.ProjectRef

/**
 * Phase 2a (V2 plan §5.2): the Chat surface as one immutable, renderable
 * value.
 *
 * This is a projection, never a replacement for [ChatUiState]. It may drop
 * fields (the question payload's raw JSON), but it never adds a fact the state
 * does not carry. It is pure: no Context, no Compose, no flows, no Android
 * APIs, no wall clock.
 */
data class ChatViewState(
    val session: SessionView,
    val binding: BindingView,
    val run: RunView,
    val model: ModelView,
    val transcript: TranscriptView,
    val composer: ComposerView,
    val gate: GateView,
    val handoff: HandoffView,
    val notice: NoticeView,
)

data class SessionView(
    val id: String?,
    val label: String,
    val surface: String,
    val list: SessionListView,
)

data class SessionListView(
    val loading: Boolean,
    val items: List<SessionItem>,
)

data class SessionItem(
    val id: String,
    val label: String,
    val isActive: Boolean,
    val isBusy: Boolean,
    // Phase 2b.1 additive fields: the sheet's second line is derived from
    // these three facts at RENDER time, because the relative-time rule needs a
    // wall clock and the projection is clock-free. Defaults keep every
    // existing construction source-compatible.
    val messageCount: Int = 0,
    val cost: Double = 0.0,
    val updatedAt: Long = 0L,
)

data class BindingView(
    val kind: BindingKind,
    val projectLabel: String?,
)

data class RunView(
    val phase: String,
    val readiness: EngineReadiness,
    val streaming: Boolean,
    val stopNotice: String?,
    /**
     * Phase 2b.1 additive fact: Retry's enabled gate and the empty-state copy
     * need the raw engineReady flag, and `readiness == Ready` is NOT equivalent
     * under a binding/key mismatch. Defaults keep existing constructions
     * source-compatible.
     */
    val engineReady: Boolean = false,
)

data class ModelView(
    val providerId: String,
    val providerName: String,
    val selected: SelectedModelView?,
    val catalogue: ModelCatalogueView,
)

/**
 * The selected model is not required to exist in the live catalogue. An absent
 * model is identified by its id, is not an error, and is never auto-switched.
 */
data class SelectedModelView(
    val id: String,
    val displayName: String,
    val presentInCatalogue: Boolean,
    val freeMarker: FreeMarker,
    /** Null means unknown; false is a real "advertised as text-only" answer. */
    val supportsImages: Boolean?,
)

data class ModelCatalogueView(
    val items: List<ModelItem>,
    val error: String?,
)

data class ModelItem(
    val id: String,
    val displayName: String,
    val isFree: Boolean,
    val supportsImages: Boolean,
)

data class TranscriptView(
    val messages: List<ChatMessage>,
    /**
     * Tool identity by engine call id. The V2 contract keeps identity as a
     * first-class map rather than relying on a row index, so a streaming
     * replacement can never make one call's result appear under another.
     */
    val toolsByCallId: Map<String, ToolActivity>,
    /** Detached sub-agent reports, in message order. */
    val reports: List<SubAgentReport>,
    val fileChanges: FileChangeSet?,
    val fileChangesLabel: String?,
)

data class ComposerView(
    val draft: String,
    val canType: Boolean,
    val canSend: Boolean,
    val canSubmitViaIme: Boolean,
    val streaming: Boolean,
    val enterSends: Boolean,
    /**
     * Phase 2b.1 additive fact: the empty state's cold-start copy ("Engine
     * starts when you begin typing") is `!prewarm && !engineReady`, and
     * `canType` cannot reconstruct it once `recoverable` is in play. Defaults
     * keep existing constructions source-compatible.
     */
    val prewarm: Boolean = false,
)

data class GateView(
    val permission: PermissionRequest?,
    val question: QuestionView?,
)

/** The question's display title only; the raw payload is deliberately dropped. */
data class QuestionView(
    val id: String,
    val title: String,
)

data class HandoffView(
    val sheet: HandoffSheetState?,
    val destinations: List<ProjectRef>,
    val notice: String?,
    val resume: HandoffResumeHint?,
)

data class NoticeView(
    val deleteError: String?,
)

/**
 * The pure projection (V2 plan §5.2.2).
 *
 * Deterministic, total and side-effect free. The host resolves everything that
 * needs Android or domain services before calling this function.
 */
fun ChatUiState.toViewState(inputs: ChatHostInputs): ChatViewState {
    val selected = selectedModel.takeIf { it.isNotBlank() }?.let { id ->
        val model = models.firstOrNull { it.id == id }
        SelectedModelView(
            id = id,
            displayName = model?.name?.takeIf { it.isNotBlank() } ?: id,
            presentInCatalogue = model != null,
            // T2A-04a: the legacy rule is intentionally preserved here. Phase 6
            // replaces this branch with the catalogue `isFree` rule (T6-01).
            freeMarker = if (id.endsWith("-free")) FreeMarker.Shown else FreeMarker.Hidden,
            supportsImages = model?.supportsImages,
        )
    }

    val activeSession = sessions.firstOrNull { it.id == activeSessionId }
    val sessionLabel = when {
        activeSession != null -> SessionList.label(activeSession)
        messages.isEmpty() -> "New chat"
        else -> "Chat"
    }

    val readiness = inputs.readiness(engineReady = engineReady, recoverable = recoverable)
    val canType = !isStreaming && (engineReady || !inputs.prewarm || recoverable)
    val canSend = !isStreaming && (engineReady || recoverable) && input.isNotBlank()

    return ChatViewState(
        session = SessionView(
            id = activeSessionId,
            label = sessionLabel,
            surface = inputs.surfaceLabel,
            list = SessionListView(
                loading = sessionsLoading,
                items = sessions.map { session ->
                    SessionItem(
                        id = session.id,
                        label = SessionList.label(session),
                        isActive = session.id == activeSessionId,
                        isBusy = session.isBusy,
                        messageCount = session.messageCount,
                        cost = session.cost,
                        updatedAt = session.updatedAt,
                    )
                },
            ),
        ),
        binding = BindingView(kind = inputs.binding, projectLabel = inputs.projectLabel),
        run = RunView(
            phase = phase,
            readiness = readiness,
            streaming = isStreaming,
            stopNotice = stopNotice,
            engineReady = engineReady,
        ),
        model = ModelView(
            providerId = providerId,
            providerName = inputs.providerDisplayName.ifBlank { providerId },
            selected = selected,
            catalogue = ModelCatalogueView(
                items = models.map { model ->
                    ModelItem(
                        id = model.id,
                        displayName = model.name.takeIf { it.isNotBlank() } ?: model.id,
                        isFree = model.isFree,
                        supportsImages = model.supportsImages,
                    )
                },
                error = modelsError,
            ),
        ),
        transcript = TranscriptView(
            messages = messages,
            toolsByCallId = messages.flatMap { it.tools }.associateBy { it.id },
            reports = messages.flatMap { it.reports },
            fileChanges = fileChanges,
            fileChangesLabel = fileChangesLabel,
        ),
        composer = ComposerView(
            draft = input,
            canType = canType,
            canSend = canSend,
            canSubmitViaIme = canType && canSend,
            streaming = isStreaming,
            enterSends = inputs.enterSendMode == EnterSendMode.Send,
            prewarm = inputs.prewarm,
        ),
        gate = GateView(
            permission = pendingPermission,
            question = pendingQuestion?.let { question ->
                QuestionView(
                    id = question.id,
                    title = question.displayTitle(),
                )
            },
        ),
        handoff = HandoffView(
            sheet = handoffSheet,
            destinations = handoffProjects,
            notice = handoffNotice,
            resume = handoffResumeHint,
        ),
        notice = NoticeView(deleteError = deleteError),
    )
}
