package com.crush.mobile.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crush.mobile.domain.file.DiffLine
import com.crush.mobile.domain.file.FileChangeSet
import com.crush.mobile.domain.file.FileDiff
import com.crush.mobile.domain.model.ReasoningDisplay
import com.crush.mobile.ui.CrushrcTrustDialog
import com.crush.mobile.ui.components.CrushBanner
import com.crush.mobile.ui.components.CrushButton
import com.crush.mobile.ui.components.CrushButtonVariant
import com.crush.mobile.ui.components.CrushCard
import com.crush.mobile.ui.components.CrushEmptyState
import com.crush.mobile.ui.components.CrushLoadingRow
import com.crush.mobile.ui.components.CrushStatusLabel
import com.crush.mobile.ui.components.CrushTone
import com.crush.mobile.ui.theme.CrushDimens
import com.crush.mobile.ui.theme.CrushShape
import com.crush.mobile.ui.theme.CrushSpacing
import com.crush.mobile.ui.theme.CrushType
import com.crush.mobile.ui.theme.LocalCrushColors
import com.crush.mobile.ui.theme.LocalDiffColors

/**
 * Phase 2b.1 (V2 plan §5.6): the Chat surface, rendered only from the
 * projection plus content-local view state.
 *
 * This composable may not read a Context, a ViewModel, the container, an
 * Activity, or a nav controller. Everything crossing the seam is a value or a
 * callback. Sheet visibility and the handoff summary/destination edits stay
 * `remember`-scoped HERE, keyed exactly as before; the host keeps only the
 * domain trust request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatViewState,
    onAction: (ChatAction) -> Unit,
    pendingTrust: PendingTrustUi?,
    onTrustConfirm: () -> Unit,
    onTrustDismiss: () -> Unit,
) {
    var showModels by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val messages = state.transcript.messages

    // Autoscroll, but only when already near the bottom so reading history is
    // not yanked away by an incoming token. (Moved with the transcript in
    // 2b.1; the rule is unchanged.)
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (last >= listState.layoutInfo.totalItemsCount - 3) {
            listState.animateScrollToItem(messages.size.coerceAtLeast(0))
        }
    }

    // Notices the ViewModel raised that the user must actually see.
    //
    // Both of these describe a situation where the app previously said nothing
    // and quietly did the wrong thing: a Stop that may not have stopped the
    // agent, and a delete that may not have deleted the chat. A failure the user
    // cannot see is still a failure.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.notice.deleteError) {
        state.notice.deleteError?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(state.run.stopNotice) {
        state.run.stopNotice?.let { snackbarHostState.showSnackbar(it) }
    }
    // The handoff flow's honest one-liners (P6 Batch C): consent refusals,
    // dispatch failures that leave the row APPROVED and resumable, and the
    // same failure surface a delete or a stop uses.
    LaunchedEffect(state.handoff.notice) {
        state.handoff.notice?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.session.label,
                            style = CrushType.section,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // F-E: derived from the readiness snapshot, never from
                        // the free-text phase alone. The words carry the state;
                        // the dot is decorative, so colour is not the signal.
                        CrushStatusLabel(
                            text = state.binding.headerStatus(state.run),
                            tone = when (state.run.readiness) {
                                EngineReadiness.Ready -> CrushTone.Success
                                EngineReadiness.Recoverable -> CrushTone.Warning
                                EngineReadiness.Failed -> CrushTone.Error
                                EngineReadiness.Starting,
                                EngineReadiness.Switching -> CrushTone.Neutral
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        showSessions = true
                        onAction(ChatAction.OpenSessions)
                    }) {
                        // Describes the ACTION (skill audit 2026-10-31): the old
                        // "Chats" named a widget, not what tapping it does.
                        Icon(Icons.Default.Menu, contentDescription = "Open chats list")
                    }
                },
                actions = {
                    IconButton(onClick = { onAction(ChatAction.NewChat) }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (state.binding.kind == BindingKind.Project) {
                                "New session"
                            } else {
                                "New chat (Crush Chat)"
                            }
                        )
                    }
                    // Model picker: only models the provider actually serves are
                    // listed. The label always has a value - display name, then
                    // id, then the word "Model" - because the old id-only
                    // button rendered as invisible space on a new chat (F-C).
                    val chosenModel = state.model.selected
                    val modelLabel = chosenModel?.displayName?.takeIf { it.isNotBlank() }
                        ?: chosenModel?.id?.removeSuffix("-free")
                        ?: "Model"
                    TextButton(
                        onClick = {
                            showModels = true
                            onAction(ChatAction.OpenModels)
                        },
                        // Stable, spoken target: the visible text is the model
                        // name, which changes and may be long.
                        modifier = Modifier.semantics {
                            contentDescription = "Choose model"
                            stateDescription = "Current: $modelLabel"
                        },
                        contentPadding = PaddingValues(horizontal = CrushSpacing.sm)
                    ) {
                        // Was rendering "freespace-bunny-free" because the badge
                        // and the id had no separator between them.
                        if (chosenModel?.freeMarker == FreeMarker.Shown) {
                            Text(
                                "FREE",
                                style = CrushType.badge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(CrushSpacing.xs))
                        }
                        Text(
                            text = modelLabel,
                            style = CrushType.control,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp)
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Composer(
                value = state.composer.draft,
                // With prewarm off the engine is cold on entry BY DESIGN, so the
                // field must stay typeable - it is the only thing that starts the
                // supervisor, and a disabled field would leave the user with a
                // permanently unusable screen and no way to recover.
                //
                // recoverable (2026-11-03 wedge fix): when a bootstrap failure
                // SETTLES (engine Running, agent not ready), the send preflight
                // is the designed recovery (it re-runs the handshake chain);
                // arm the field and the send button for exactly that state.
                // Mid-retry and starting states stay disabled so a press cannot
                // silently no-op.
                enabled = state.composer.canType,
                // Sending still requires a live port. send() reads the port and
                // returns without doing anything when it is null, so enabling
                // this early would swallow the message with no visible error -
                // hence engineReady, or RECOVERABLE (the settled-failure state
                // where the port exists and the preflight explicitly handles
                // the not-ready agent).
                sendEnabled = state.composer.canSend,
                streaming = state.composer.streaming,
                enterSends = state.composer.enterSends,
                onChange = { text -> onAction(ChatAction.DraftChanged(text)) },
                onSend = { onAction(ChatAction.Send) },
                onStop = { onAction(ChatAction.Stop) }
            )
        }
    ) { pad ->
        if (messages.isEmpty()) {
            // V2-4 D2: state-aware welcome copy. The old single line was only
            // correct for prewarm-off cold start; on a failed or ready engine it
            // either hid the failure or claimed the engine had not started.
            val copy = state.emptyCopy()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(CrushSpacing.xl),
                contentAlignment = Alignment.Center
            ) {
                CrushEmptyState(title = copy.title, body = copy.body)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentPadding = PaddingValues(
                    horizontal = CrushDimens.gutter,
                    vertical = CrushSpacing.lg
                ),
                verticalArrangement = Arrangement.spacedBy(CrushSpacing.md)
            ) {
                // The replay-surviving resume affordance (the D+F drive's UX
                // enhancement): a table-driven bar PINNED at the transcript's
                // top, shown when this chat's newest handoff row is
                // DISPATCHED and the view is not already the target session.
                // It survives every replay because it is built FROM the row,
                // never from the ephemeral dispatch note.
                state.handoff.resume
                    ?.takeIf { it.targetSessionId != state.session.id }
                    ?.let { hint ->
                        stickyHeader(key = "handoff-resume-${hint.handoffId}") {
                            // The bar's F04 host: its trust decision is nearest
                            // the affordance. The pending request itself is
                            // host-owned (2b.1); the dialog is rendered at the
                            // content root so disposing this sticky row cannot
                            // drop a consent prompt.
                            HandoffResumeBar(
                                label = hint.targetLabel,
                                onContinue = { onAction(ChatAction.OpenHandoffInCode) }
                            )
                        }
                    }
                itemsIndexed(messages, key = { _, m -> m.id }) { idx, m ->
                    // D13 RETRY WIRING. The retry prompt is derived at render
                    // time, not stored on the model: the nearest preceding user
                    // row IS the prompt whose turn failed, so re-sending it as
                    // a NEW turn keeps history honest - the failed pair stays
                    // visible and the retry appears as a fresh exchange. The
                    // take(idx) scan only ever runs on failed rows.
                    val retryText = if (m.error != null) {
                        messages.take(idx)
                            .lastOrNull { it.role == "user" && it.text.isNotBlank() }?.text
                    } else {
                        null
                    }
                    Bubble(
                        m = m,
                        reasoningDisplay = state.reasoningDisplay,
                        retryText = retryText,
                        retryEnabled = state.run.engineReady && !state.run.streaming,
                        onRetry = {
                            retryText?.let { t ->
                                // setInput puts the prompt visibly back in the
                                // composer before send() consumes it; a send
                                // clears input only inside streamSend, so this
                                // is the exact sequence the send button itself
                                // follows. Going through the same gates also
                                // means send()'s own isStreaming/empty guards
                                // back this call up.
                                onAction(ChatAction.Retry(t))
                            }
                        },
                        // P6 Batch C entry chip: the tapped bubble's index drives
                        // the sheet's prefill prefix (handoffSheetFor), keeping
                        // Batch B's prefill the only summary wording source.
                        onUsePlan = { onAction(ChatAction.UsePlanInCode(idx)) }
                    )
                }

                // What the agent actually changed, at the END of the turn.
                //
                // Below the reply, because the conversation is the point and the
                // diff is the evidence. It is in the scroll rather than a dialog
                // because the user needs to be able to read it against the reply
                // that explains it.
                state.transcript.fileChanges?.let { changes ->
                    // fileCount, not isEmpty: a set with only unreadable files has
                    // nothing to show a diff for but still has to SAY so, or the
                    // user is left believing the agent changed nothing.
                    if (changes.fileCount > 0 || changes.unreadable.isNotEmpty() ||
                        changes.snapshotUnavailable
                    ) {
                        // The rail shows the LATEST turn; every completed turn's
                        // card (older turns included) and the per-file revert live
                        // on the Review screen - the rail links to it, it does not
                        // duplicate it.
                        item(key = "file-changes") {
                            FileChangesPanel(
                                changes,
                                onOpenReview = { onAction(ChatAction.OpenReview) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Permission gate. In ASK mode the engine BLOCKS the turn until this is
    // answered, so without a dialog here every tool call would hang forever -
    // which is strictly worse than the yolo behaviour it replaces.
    state.gate.permission?.let { req ->
        AlertDialog(
            onDismissRequest = {
                onAction(ChatDispatch.permissionDismiss(req.id))
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Allow this action?") },
            text = {
                Column(
                    // Live region (analysis §4 P2): a screen reader that is
                    // mid-browsing elsewhere gets this dialog read out once,
                    // WITHOUT moving focus or re-announcing on each internal
                    // state change. The column is the atomic unit - tool,
                    // description, path - so a bare title fragment is never
                    // announced alone (skill rule: one meaningful contextual
                    // status, not a competing bare label).
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                ) {
                    Text(
                        req.toolName.ifBlank { "The agent" },
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (req.description.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(req.description)
                    }
                    if (req.path.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            req.path,
                            style = CrushType.code
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The agent can only act inside this app's private storage.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onAction(ChatAction.AllowOnce(req.id)) }) {
                    Text("Allow once")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onAction(ChatAction.AllowForSession(req.id)) }) {
                        Text("Always")
                    }
                    TextButton(onClick = {
                        onAction(ChatDispatch.permissionDismiss(req.id))
                    }) {
                        Text("Deny")
                    }
                }
            }
        )
    }

    // Question gate (analysis §4 P0-3). In ask-flows the engine BLOCKS the run
    // on a question batch until it is answered or cancelled; without a dialog
    // the turn hangs invisibly until the idle timeout cancels it - the same
    // failure class the permission dialog fixed. A parsed batch now answers
    // through AnswerQuestionDialog; an unparsable one keeps the cancel-only
    // fallback, so both paths release the run instead of leaving it blocked.
    // (A12, 2026-11-23.)
    state.gate.question?.let { q ->
        if (q.batch != null) {
            // A12: a parsed batch gets real choices. Dismiss is Cancel, never
            // Submit, so a back gesture cannot send a partial answer.
            AnswerQuestionDialog(
                view = q,
                error = state.gate.questionError,
                onCancel = { onAction(ChatAction.ReleaseQuestion) },
                onSubmit = { answers -> onAction(ChatAction.SubmitQuestion(answers)) },
            )
        } else {
            AlertDialog(
                onDismissRequest = { onAction(ChatAction.ReleaseQuestion) },
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                title = {
                    Text(q.title.ifBlank { "The agent has a question" })
                },
                text = {
                    // Live region, same rule as the permission dialog above: the
                    // question reaches a screen reader that is currently
                    // elsewhere, once, without disturbing focus.
                    Column(
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        }
                    ) {
                        Text(
                            "This question's choices could not be parsed. " +
                                "Cancelling releases the run without an answer."
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onAction(ChatAction.ReleaseQuestion) }) {
                        Text("Deny")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onAction(ChatAction.ReleaseQuestion) }) {
                        Text("Dismiss")
                    }
                }
            )
        }
    }

    if (showSessions) {
        SessionSheet(
            sessions = state.session.list.items,
            activeId = state.session.id,
            loading = state.session.list.loading,
            unavailable = state.session.list.unavailable,
            surfaceLabel = state.session.surface,
            newLabel = if (state.binding.kind == BindingKind.Project) {
                "New session"
            } else {
                "New chat"
            },
            onPick = { id ->
                showSessions = false
                onAction(ChatAction.SelectSession(id))
            },
            onDelete = { onAction(ChatAction.DeleteSession(it)) },
            // Q1(a): "+ New chat" is the CHAT choice at creation - the dedicated
            // conversation instance (deny-all tools); Code starts at Projects.
            onNew = {
                showSessions = false
                onAction(ChatAction.NewChat)
            },
            onReview = {
                showSessions = false
                onAction(ChatAction.OpenReview)
            },
            onDismiss = { showSessions = false }
        )
    }

    if (showModels) {
        ModelSheet(
            models = state.model.catalogue.items,
            selected = state.model.selected,
            providerName = state.model.providerName,
            error = state.model.catalogue.error,
            loading = state.model.catalogue.loading,
            onPick = { id ->
                showModels = false
                onAction(ChatAction.SelectModel(id))
            },
            onReload = { onAction(ChatAction.ReloadModels) },
            onDismiss = { showModels = false }
        )
    }

    // P6 Batch C + Batch D: the Chat->Code handoff consent sheet. The
    // selection and the edited summary are hoisted HERE (the chip/tap
    // lifecycle is view state), keyed on the sheet instance so every fresh
    // open starts from its own prefill and its own no-project default -
    // except RESUME, whose preselect IS the approved destination (the
    // same-target Confirm dispatches on the existing approval). Confirm
    // carries both back as parameters - the ViewModel can only express a
    // Confirm as a fresh decision, which is the composable-contract face
    // of the no-alias rule (plan :416).
    state.handoff.sheet?.let { sheet ->
        var selectedDestination by remember(sheet) {
            mutableStateOf(sheet.approvedRow?.targetProjectId)
        }
        var summaryDraft by remember(sheet) { mutableStateOf(sheet.initialSummary) }
        // The F04 consent decision for the sheet's paths (Batch D rewire):
        // screen-local exactly like the Projects row-tap's dialog - the
        // container's root-level pending flow stays the restore's. Allow
        // RE-CALLS the same entry; the second gate finds Allow (consent was
        // just recorded) and the same code path runs on. Dismiss keeps the
        // sheet exactly as it was.
        //
        // 2b.1: the pending request itself is now HOST state (a domain
        // PendingProjectTrust cannot cross this seam); this composable only
        // reports that a consent prompt was raised, through the action that
        // produced it.
        HandoffConsentSheet(
            sheet = sheet,
            projects = state.handoff.destinations,
            selectedDestination = selectedDestination,
            summary = summaryDraft,
            onSummary = { summaryDraft = it },
            onSelect = { selectedDestination = it },
            onConfirm = {
                onAction(ChatAction.ConfirmHandoff(selectedDestination, summaryDraft))
            },
            onDismiss = { onAction(ChatAction.CloseHandoffSheet) },
            onDiscard = { onAction(ChatAction.DiscardHandoff) },
            onOpenInCode = { onAction(ChatAction.OpenHandoffInCode) },
            // The Batch F drive's UX fix: a refused Confirm (the redaction
            // net's verdict, an in-flight turn) renders INSIDE the sheet -
            // the snackbars fired behind the sheet's scrim, and eleven
            // taps that "did nothing" taught the owner eleven invisible
            // refusals.
            notice = state.handoff.notice,
        )
    }

    // The F04 trust prompt. It is rendered at the content root because its
    // request is host-owned: a second gate can re-raise it after Allow, and
    // closing the sheet or scrolling the resume bar must not drop consent.
    pendingTrust?.let { trust ->
        CrushrcTrustDialog(
            folderLabel = trust.folderLabel,
            reasonNote = trust.reason,
            onConfirm = onTrustConfirm,
            onDismiss = onTrustDismiss,
        )
    }
}

/** Assistant-bubble silhouette, shared by the reply path and the error card. */
private val AssistantBubbleShape = RoundedCornerShape(
    topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp
)

/**
 * V2-7: reasoning is model prose, but it is not the reply. It renders as a
 * collapsed disclosure above the answer, in plain text (never Markdown) so a
 * model-authored heading or link cannot impersonate host chrome. Expanded text
 * is bounded for the same reason code blocks are: a runaway chain of thought
 * must not turn one bubble into thousands of layout nodes.
 */
private const val REASONING_MAX_CHARS = 8000

@Composable
private fun ReasoningBlock(messageId: String, thinking: String) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Reasoning",
                style = CrushType.metadata,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.ExpandLess
                } else {
                    Icons.Filled.ExpandMore
                },
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            Text(
                text = if (thinking.length <= REASONING_MAX_CHARS) {
                    thinking
                } else {
                    thinking.take(REASONING_MAX_CHARS) + "\n\n[reasoning truncated]"
                },
                style = CrushType.secondary,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = 12.dp, end = 12.dp, bottom = 10.dp
                )
            )
        }
    }
}

/**
 * V2-7: the visible fallback for an unrendered part type. Plain text on a
 * quiet surface - it is a receipt that something arrived, not a new UI
 * vocabulary invented from model output.
 */
@Composable
private fun UnsupportedContentBlock(text: String) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            "Unsupported content",
            style = CrushType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text,
            style = CrushType.secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Bubble(
    m: ChatMessage,
    // V2-7 (owner, 2026-11-22): host-resolved display mode. Only COLLAPSED
    // draws the block; OFF keeps it out of the transcript entirely.
    reasoningDisplay: ReasoningDisplay = ReasoningDisplay.COLLAPSED,
    // D13: non-null only on a failed assistant row that has a preceding user
    // prompt available to re-send; drives the card's Retry affordance.
    retryText: String? = null,
    retryEnabled: Boolean = false,
    onRetry: () -> Unit = {},
    // P6 Batch C: non-null turns on the "Use this plan in Code" chip in the
    // completed-assistant actions row. The sheet's prefill re-selects THIS
    // bubble's text through Batch B's prefill contract (the tapped index
    // prefixes the transcript), so the chip's gate and Copy's stay one gate.
    onUsePlan: (() -> Unit)? = null
) {
    // S-4 transfer note (decisions-open §10 P5): the app's own honest hint
    // that the capability the turn attempted belongs to a project (Code
    // mode) session. A deliberately SMALL surface - one centered bodySmall
    // onSurfaceVariant line the note text carries - so it reads as the
    // transcript's own margin note, not as an error card or as model speech
    // (the system role also keeps it out of the copy/save and retry paths
    // below). Polite live region per the file's a11y rule: the app DID deny
    // a tool mid-turn, so the plain phrase is announced once wherever the
    // reader is, without moving focus; the transcript's near-bottom-only
    // autoscroll is what keeps the line from yanking the user's scroll.
    // App-side only, ephemeral per-session view state - see [turnDenialNote]
    // for the persistence story and dedupe guarantee.
    if (m.role == "system") {
        Text(
            m.text,
            style = CrushType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        return
    }
    val reportOnly = m.reports.isNotEmpty() && m.text.isBlank() && m.tools.isEmpty()
    val isUser = m.role == "user" && !reportOnly
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // V2-4 D5: role is visible text, not merely fill colour or corner
        // geometry. A report carrier has no speech of its own and is skipped.
        if (!reportOnly) {
            Text(
                text = if (isUser) "You" else "Crush",
                style = CrushType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = CrushSpacing.xs)
            )
        }
        // Detached sub-agent results (engine v0.100.0+) are genuine transcript
        // rows, not model speech: they render as their own card above the
        // bubble that used them, both live and after a reopen.
        if (m.reports.isNotEmpty()) {
            m.reports.forEach { report ->
                SubAgentReportCard(report)
                Spacer(Modifier.height(6.dp))
            }
        }
        // A report carrier has no text part (v0.100.0 writes it as a user
        // message). Do not draw an empty user bubble underneath its card.
        if (!reportOnly) {
            // Tool activity sits ABOVE the reply bubble, and only for the
            // agent.
            //
            // Placement matters: the agent reads files and runs commands, and
            // the user must see that happening before the summary, not buried
            // inside it. Previously this was parsed and discarded, so all of it
            // was invisible.
            if (!isUser && m.tools.isNotEmpty()) {
                ToolActivityList(m.tools)
                Spacer(Modifier.height(6.dp))
            }
            // V2-7: reasoning sits between the work and the answer. Collapsed
            // by default, so a long chain of thought never pushes the reply
            // off-screen; the text is plain, never Markdown, so model-authored
            // formatting cannot masquerade as host UI.
            if (!isUser && reasoningDisplay == ReasoningDisplay.COLLAPSED &&
                m.thinking.isNotBlank()
            ) {
                ReasoningBlock(messageId = m.id, thinking = m.thinking)
                Spacer(Modifier.height(6.dp))
            }
            // V2-7 / acceptance-kit row 6: a part type this build does not
            // know must be visible as text, not silently swallowed. The text is
            // already bounded by StreamMessage.unknownText.
            if (!isUser && m.unknownText.isNotBlank()) {
                UnsupportedContentBlock(m.unknownText)
                Spacer(Modifier.height(6.dp))
            }
            if (!isUser && m.error != null) {
                // D13 RETRY CARD: a failed assistant turn is not a normal
                // bubble with error text bolted on - it is a failed object.
                // Giving it its own surface (errorContainer), its own
                // announcement and its own recovery affordance is what turns a
                // recorded failure into an actionable one.
                FailedBubbleCard(
                    error = m.error,
                    partialText = m.text,
                    retryText = retryText,
                    retryEnabled = retryEnabled,
                    onRetry = onRetry
                )
            } else if (m.text.isNotEmpty() || m.isStreaming) {
                // A tool-only or report-only turn has no speech of its own.
                // Drawing its bubble produced an empty pill under the tool
                // rows; skip the bubble entirely, the activity above is the
                // content.
                Box(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp, topEnd = 16.dp,
                                bottomStart = if (isUser) 16.dp else 4.dp,
                                bottomEnd = if (isUser) 4.dp else 16.dp
                            )
                        )
                        .background(
                            if (isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        if (m.text.isEmpty() && m.isStreaming && m.error == null) {
                            Text(
                                if (m.tools.isEmpty()) "thinking..." else "working...",
                                style = CrushType.body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            MarkdownText(
                                m.text,
                                // User rows wrap their content and stay
                                // right-aligned; assistant prose takes the
                                // available width (capped above for wide
                                // windows). Previously both filled the row.
                                modifier = if (isUser) Modifier else Modifier.fillMaxWidth(),
                                // User bubbles paint primaryContainer, so the
                                // text must be onPrimaryContainer. The old
                                // default (onSurface) measured 2.05:1 on the
                                // light bubble.
                                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                // Completed assistant text gets an always-visible actions row -
                // copy the whole message, save it as a .md via the SAF picker,
                // or hand the plan into a Code project (P6 Batch C). App-side
                // only (see ChatExport.kt): no tool fires, the engine never
                // sees this, S-01's deny-all chat policy is untouched.
                // Hidden while streaming (a save mid-draw is a partial artifact)
                // and on failed rows (that card's affordance is Retry, not
                // save). The gate is ONE expression - handoffEntryVisible,
                // extracted verbatim from this line - so the handoff chip
                // provably rides the Copy/Save gate's conjunction rather than
                // a lookalike.
                if (handoffEntryVisible(m)) {
                    MessageActionsRow(m.text, onUsePlanInCode = onUsePlan)
                }
            }
        }
    }
}

/**
 * One detached sub-agent result (engine v0.100.0+).
 *
 * The report is engine-authored evidence of a separate agent's work - label,
 * model, output, failed flag - not model prose. It gets its own surface so it
 * cannot be mistaken for something the primary agent said, and it is retained
 * through replay (see `replayRow`).
 */
@Composable
private fun SubAgentReportCard(report: com.crush.mobile.data.crush.SubAgentReport) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (report.failed) scheme.errorContainer else scheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (report.failed) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = if (report.failed) "sub-agent failed" else "sub-agent finished",
                    tint = if (report.failed) scheme.error else scheme.onSecondaryContainer,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "Sub-agent ${report.label.ifBlank { "report" }}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (report.failed) scheme.onErrorContainer else scheme.onSecondaryContainer
                )
                if (report.model.isNotBlank()) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = report.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (report.failed) scheme.onErrorContainer else scheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            if (report.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = report.output,
                    style = CrushType.code,
                    color = if (report.failed) scheme.onErrorContainer else scheme.onSecondaryContainer
                )
            }
        }
    }
}

/**
 * The failed-reply card (analysis D13 follow-up).
 *
 * Before this, a failed turn rendered its assistant bubble with a tiny
 * monospace error string appended inside the regular surfaceVariant bubble -
 * the same visual grammar as content, no recovery affordance, and easy to
 * read past. Now the failure gets the scheme's errorContainer surface, a
 * plain-language headline, the raw engine error in
 * monospace beneath it, and - when a preceding user prompt exists for this
 * turn - a Retry button that re-sends that prompt as a NEW turn. History is
 * not rewritten: the failed pair stays in the transcript and the retry is a
 * visible follow-up, so "what did I send and what failed" stays auditable.
 *
 * Any partial text the turn produced before dying is kept above the error
 * line, because deleting real output would lie about what happened.
 */
@Composable
private fun FailedBubbleCard(
    error: String,
    partialText: String,
    retryText: String?,
    retryEnabled: Boolean,
    onRetry: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            // Same silhouette as a normal assistant bubble: the card must read
            // as the same conversational object that failed, aligned left.
            .clip(AssistantBubbleShape)
            .background(scheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Live region scoped to the HEADLINE (skill a11y rule: one
                // meaningful contextual status, not the whole card): a run
                // dying mid-turn is a status the user may reach from anywhere
                // in the list, so the plain phrase announces politely, once,
                // without moving focus - the long partial text and raw error
                // stay at the reader's fingers to explore.
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                }
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = scheme.onErrorContainer
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "This reply failed",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onErrorContainer
                )
            }
            if (partialText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                MarkdownText(partialText, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                error,
                style = CrushType.code,
                color = scheme.onErrorContainer
            )
            if (retryText != null) {
                // The button stays VISIBLE but disabled when the engine cannot
                // take it (no live port, or another run is streaming): a
                // vanished affordance would hide the fact that retry exists
                // and will come back.
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = onRetry,
                    enabled = retryEnabled,
                    // error accent rather than primary: on an errorContainer
                    // surface a primary-colored button would read as a "carry
                    // on as usual" cue, which a retry after failure is not.
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = scheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
    }
}

/**
 * Collapsed-by-default rows, one per tool call.
 *
 * A coding agent runs a lot of tools; expanding all of them by default would
 * bury the conversation. But the count and the names are always visible, because
 * the point is supervision - you should always be able to see that the agent did
 * something, and what, without tapping.
 */
@Composable
private fun ToolActivityList(tools: List<ToolActivity>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tools.forEach { tool ->
            ToolRow(tool)
        }
    }
}

/**
 * The files a turn changed, one collapsible row per file.
 *
 * Collapsed by default. An agent run can touch a dozen files and a wall of red and
 * green pushes the reply itself off-screen, so the row is the summary and the diff
 * is what you get when you ask for it. Expanding is remembered per file, so
 * scrolling away and back does not lose it.
 *
 * Files we could not diff are listed EXPLICITLY rather than omitted. The whole
 * point of this panel is that the user can see what the agent did; a silently
 * dropped file is the same lie as a delete that reported success.
 */
@Composable
private fun FileChangesPanel(changes: FileChangeSet, onOpenReview: (() -> Unit)? = null) {
    CrushCard(modifier = Modifier.fillMaxWidth()) {
        Text("This turn's changes", style = CrushType.section)
        Spacer(Modifier.height(CrushSpacing.xs))
        if (changes.snapshotUnavailable) {
            CrushStatusLabel(
                text = "Changes were not measured for this turn",
                tone = CrushTone.Warning,
            )
        } else {
            Text(
                "${changes.fileCount} file${if (changes.fileCount == 1) "" else "s"} changed " +
                    "+${changes.totalAdded} -${changes.totalRemoved}",
                style = CrushType.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (changes.unreadable.isNotEmpty()) {
            Spacer(Modifier.height(CrushSpacing.xs))
            Text(
                "${changes.unreadable.size} file(s) not shown (unreadable, binary, or too large): " +
                    changes.unreadable.take(4).joinToString(", ") +
                    if (changes.unreadable.size > 4) ", ..." else "",
                style = CrushType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (changes.diffs.any { it.hasChanges }) {
            Spacer(Modifier.height(CrushSpacing.sm))
        }
        changes.diffs.filter { it.hasChanges }.forEach { diff ->
            FileDiffRow(diff)
        }

        // P4 (2026-11-07): the rail is the latest turn's summary; the Review
        // surface keeps EVERY completed turn of this process runtime with the
        // per-file revert. Only linked when there is something to review: a
        // link that lands on an empty history is a promise the app cannot keep.
        // V2-5: a not-measured turn is reviewable too, because Review can say so.
        if (
            onOpenReview != null &&
            (changes.fileCount > 0 || changes.unreadable.isNotEmpty() || changes.snapshotUnavailable)
        ) {
            Spacer(Modifier.height(CrushSpacing.xs))
            CrushButton(
                text = "Review all turns",
                onClick = onOpenReview,
                variant = CrushButtonVariant.TextOnly,
            )
        }
    }
}

// internal: the Review surface (P4 v1) re-renders each diff with this exact
// row, so "what you review" and "what you revert against" are the same bytes.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileDiffRow(diff: FileDiff) {
    var expanded by remember(diff.path) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    // Role.Button + a spoken state (analysis §4 P2): a tap-only
                    // row otherwise reads as plain text to TalkBack, with no
                    // affordance and no hint that tapping reveals the diff.
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics {
                        stateDescription = if (expanded) "expanded" else "collapsed"
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Path first, stat second: WHAT changed is more useful than how much.
                Text(
                    diff.path,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val tint = when {
                    diff.deleted -> MaterialTheme.colorScheme.error
                    diff.created -> LocalCrushColors.current.success
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    when {
                        diff.created -> "new"
                        diff.deleted -> "deleted"
                        diff.tooLarge -> "${diff.stat()} (large)"
                        else -> diff.stat()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
                )
            }

            if (expanded) {
                Column(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 8.dp)
                ) {
                    if (diff.hunks.isEmpty()) {
                        Text(
                            if (diff.tooLarge) "Too large to diff line by line."
                            else "No line-level change.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        diff.hunks.forEach { hunk ->
                            val beforeCount = hunk.lines.count {
                                it.kind != DiffLine.Kind.ADDED
                            }
                            val afterCount = hunk.lines.count {
                                it.kind != DiffLine.Kind.REMOVED
                            }
                            Text(
                                "@@ -${hunk.beforeStart},$beforeCount " +
                                    "+${hunk.afterStart},$afterCount @@",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Monospace, and each line keeps its own number. A diff
                            // without line numbers is decoration.
                            hunk.lines.forEach { line ->
                                val rowBg = when (line.kind) {
                                    DiffLine.Kind.ADDED -> LocalDiffColors.current.addedBg
                                    DiffLine.Kind.REMOVED -> LocalDiffColors.current.removedBg
                                    DiffLine.Kind.CONTEXT -> Color.Transparent
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(rowBg)
                                ) {
                                    Text(
                                        (line.beforeLine ?: line.afterLine)?.toString() ?: "",
                                        style = CrushType.code,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(40.dp)
                                    )
                                    val lineTint = when (line.kind) {
                                        DiffLine.Kind.ADDED -> LocalDiffColors.current.added
                                        DiffLine.Kind.REMOVED -> LocalDiffColors.current.removed
                                        DiffLine.Kind.CONTEXT -> MaterialTheme.colorScheme.onSurface
                                    }
                                    Text(
                                        when (line.kind) {
                                            DiffLine.Kind.ADDED -> "+ ${line.text}"
                                            DiffLine.Kind.REMOVED -> "- ${line.text}"
                                            DiffLine.Kind.CONTEXT -> "  ${line.text}"
                                        },
                                        style = CrushType.code,
                                        color = lineTint
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact app-observed duration: 850ms, 1.2s, 2m 05s. */
private fun formatToolDuration(ms: Long): String = when {
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m ${String.format(java.util.Locale.US, "%02ds", (ms % 60_000) / 1000)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolRow(tool: ToolActivity) {
    // Expand automatically only while the tool is mid-flight, so live progress
    // is visible without the user hunting. A finished tool collapses again.
    var expanded by remember(tool.id) { mutableStateOf(tool.isRunning) }

    val tint = when (tool.state) {
        ToolState.FAILED -> LocalCrushColors.current.toolFailed
        ToolState.COMPLETED -> LocalCrushColors.current.toolSucceeded
        ToolState.RUNNING -> LocalCrushColors.current.toolRunning
        ToolState.PENDING -> LocalCrushColors.current.pending
        ToolState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 2.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Role.Button + spoken state (analysis §4 P2): same rule as
                    // the diff rows - tap targets must not read as flat text.
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics {
                        stateDescription = if (expanded) "expanded" else "collapsed"
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (tool.state) {
                        ToolState.FAILED -> Icons.Default.Warning
                        ToolState.COMPLETED -> Icons.Default.CheckCircle
                        ToolState.RUNNING -> Icons.Default.Refresh
                        else -> Icons.Default.Info
                    },
                    // A human phrase, not implementation vocabulary (analysis
                    // §4 P2): an enum name like "COMPLETED" is read aloud
                    // letter-caps and describes the class, not the status.
                    contentDescription = when (tool.state) {
                        ToolState.FAILED -> "failed"
                        ToolState.COMPLETED -> "completed"
                        ToolState.RUNNING -> "running"
                        ToolState.PENDING -> "pending"
                        ToolState.UNKNOWN -> "status unknown"
                    },
                    tint = tint,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    tool.name,
                    style = CrushType.code,
                    color = tint
                )
                Spacer(Modifier.width(7.dp))
                if (tool.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.5.dp
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    tool.args.take(70).replace('\n', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // ENGINE-reported execution time, when the call's tool_result
                // carried start/end metadata. The wire's `finished` flag is
                // part-final, so the old first-frame-to-finish chip measured
                // frame latency, not execution (device finding 2026-11-14:
                // 274 ms displayed for a call gated 2m20s). No result -> no
                // chip; never invent one.
                val elapsedMs = tool.durationMs
                if (elapsedMs > 0L) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatToolDuration(elapsedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (expanded && (tool.args.isNotBlank() || tool.output.isNotBlank() || tool.error != null)) {
                Column(Modifier.padding(start = 32.dp, end = 10.dp, bottom = 6.dp)) {
                    if (tool.args.isNotBlank()) {
                        Text(
                            tool.args,
                            style = CrushType.code,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (tool.output.isNotBlank() || tool.error != null) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    tool.error?.let {
                        Text(
                            it,
                            style = CrushType.code,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (tool.output.isNotBlank()) {
                        Text(
                            tool.output,
                            style = CrushType.code,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
value: String,
    enabled: Boolean,
    /**
     * Split from [enabled] because the two conditions genuinely differ: typing
     * only needs a usable field, while sending needs the engine actually Running
     * so there is a port for send() to read.
     */
    sendEnabled: Boolean,
    streaming: Boolean,
    enterSends: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message", style = CrushType.body) },
                maxLines = CrushDimens.composerMaxLines,
                enabled = enabled && !streaming,
                // ENTER-SEND SETTING (analysis §4 P2): the preference existed
                // end-to-end in the model, DataStore and ViewModel with no
                // consumer and no UI. In "send" mode the IME action becomes
                // the send arrow and the action fires onSend under the same
                // guards the send button uses; in the "newline" default the
                // action stays Default, so the enter key keeps inserting a
                // newline exactly as before. Soft newlines in send mode are a
                // mode-switch away; Settings says so beside the toggle.
                keyboardOptions = KeyboardOptions(
                    imeAction = if (enterSends) ImeAction.Send else ImeAction.Default
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (enabled && !streaming && sendEnabled && value.isNotBlank()) {
                            onSend()
                        }
                    }
                )
            )
            Spacer(Modifier.width(8.dp))
            if (streaming) {
                FilledIconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = sendEnabled && value.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * Model picker fed from Zen's LIVE /models, not Crush's embedded catalogue.
 * Crush still lists `deepseek-v4-flash-free`, which Zen has retired, so a picker
 * built from Crush would hand the user a model that fails every turn.
 */
/**
 * Chat list.
 *
 * Sessions are per-workspace, exactly as Claude Code scopes them ("a session is a
 * saved conversation tied to a project directory"), so this deliberately does NOT
 * offer a global all-chats list. That would need a query per workspace and would
 * misrepresent what the engine actually holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    sessions: List<SessionItem>,
    activeId: String?,
    loading: Boolean,
    unavailable: Boolean,
    surfaceLabel: String,
    newLabel: String,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val pendingLabel = pendingDelete?.let { id ->
        sessions.firstOrNull { it.id == id }?.label
    }

    // Destructive and irreversible, so it is always confirmed. A single-tap
    // delete in a list where rows are one tap from opening a conversation is
    // exactly how a history gets destroyed by accident.
    //
    // 2b.1 DEVIATION (pre-existing, recorded not fixed): this dialog is dead
    // code. The row's trash button dispatches DeleteSession directly and never
    // sets pendingDelete, so the confirmation below cannot be reached. Baseline
    // flow 13 captures the immediate delete. Phase 2b.1 preserves today's
    // behaviour byte-for-byte; the fix is deliberately out of scope.
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete chat?") },
            text = {
                Text(
                    "\"$pendingLabel\" and its messages will be removed. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(pendingDelete!!)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(newLabel)
            }
            Spacer(Modifier.height(8.dp))
            // Review stays in the sheet: it is contextual to the conversation,
            // not a primary destination, and the shell's bar cannot reach a
            // pushed route. Settings/Projects moved to the shell bar, so this
            // sheet is now only about the chats list and turn review.
            OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                Text("Review changes")
            }
            Spacer(Modifier.height(8.dp))
            // S4: the sheet is scoped to the CURRENT surface's pool (the engine
            // owns session storage per instance), so say WHICH surface the
            // list below belongs to instead of implying a global inbox.
            Text(
                surfaceLabel,
                style = CrushType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = CrushSpacing.xs)
            )

            if (loading && sessions.isEmpty()) {
                CrushLoadingRow(
                    text = "Loading chats...",
                    modifier = Modifier.padding(vertical = CrushSpacing.lg)
                )
            } else if (unavailable && sessions.isEmpty()) {
                // Finding 11: an empty list here is UNQUERIED, not empty. The
                // engine is stopped (or still booting), so the app must not
                // present "No chats yet" as a confirmed answer. A cached
                // non-empty list skips this branch and stays visible below.
                CrushEmptyState(
                    title = "Chats aren't available",
                    body = "The engine isn't connected. Send a message to start it, " +
                        "then reopen this sheet.",
                    modifier = Modifier.padding(vertical = CrushSpacing.sm)
                )
            } else if (sessions.isEmpty()) {
                // Say WHY it is empty. An unexplained empty list reads as data
                // loss, which is the failure mode this project keeps hitting.
                CrushEmptyState(
                    title = "No chats yet",
                    body = "Start one and it will appear here.",
                    modifier = Modifier.padding(vertical = CrushSpacing.sm)
                )
            } else {
                if (unavailable) {
                    // A cached list is still useful, but it is not live. Say so
                    // instead of letting an old list masquerade as current.
                    CrushBanner(
                        text = "Showing the last known list. The engine isn't connected.",
                        tone = CrushTone.Warning,
                        modifier = Modifier.padding(bottom = CrushSpacing.sm)
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(sessions, key = { it.id }) { sess ->
                        val active = sess.id == activeId
                        // Non-clickable Surface (analysis §4 P2): this used to
                        // be Surface(onClick = {}) - a focusable row that did
                        // NOTHING except present a second, dead tap target next
                        // to the row's real clickable region and its delete
                        // button. The color-carrying wrapper has no behaviour
                        // of its own, so it must not pretend otherwise.
                        Surface(
                            color = if (active) LocalCrushColors.current.accentSoft
                            else MaterialTheme.colorScheme.surface,
                            shape = CrushShape.standard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Tap target for OPENING the chat. The delete
                                // button used to sit inside this same clickable
                                // Surface, so one tap fired both handlers and
                                // deleting a chat navigated to a new one
                                // instead. Splitting the row into an explicit
                                // clickable region plus a separate button is what
                                // makes the two actions independent.
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { onPick(sess.id) }
                                        // V2-4 D4: the current chat is spoken,
                                        // not just tinted. The fill, check and
                                        // word below are the visual half.
                                        .semantics {
                                            if (active) stateDescription = "Current chat"
                                        }
                                        .padding(
                                            horizontal = CrushSpacing.md,
                                            vertical = CrushSpacing.md
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (active) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = LocalCrushColors.current.accent,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(CrushSpacing.xs))
                                        }
                                        Text(
                                            sess.label,
                                            style = CrushType.body,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (active) {
                                            Spacer(Modifier.width(CrushSpacing.sm))
                                            Text(
                                                "Current",
                                                style = CrushType.metadata,
                                                color = LocalCrushColors.current.muted
                                            )
                                        }
                                    }
                                    val detail = SessionList.detail(
                                        messageCount = sess.messageCount,
                                        cost = sess.cost,
                                        isBusy = sess.isBusy,
                                        updatedAt = sess.updatedAt,
                                        nowMs = now,
                                    )
                                    if (detail.isNotEmpty()) {
                                        Text(
                                            detail,
                                            style = CrushType.metadata,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = { onDelete(sess.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        // Name the target: a list of identical
                                        // "Delete chat" buttons is ambiguous to
                                        // a screen-reader user.
                                        contentDescription = "Delete chat: ${sess.label}"
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(CrushSpacing.sm))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * The live model catalogue sheet.
 *
 * [providerName] is the ACTIVE provider's display name: the subtitle names the
 * provider the list came from. It hardcoded "OpenCode Zen" until the first
 * hyper key stored in the vault made the line lie ("Live list from OpenCode
 * Zen" under an OpenCode Go catalogue) — the same copy-defect class the
 * no-model verdict had.
 */
private fun ModelSheet(
    models: List<ModelItem>,
    selected: SelectedModelView?,
    providerName: String,
    error: String?,
    loading: Boolean,
    onPick: (String) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedId = selected?.id
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CrushSpacing.xl)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Model",
                    style = CrushType.section,
                    modifier = Modifier.weight(1f)
                )
                // V2-4 D3: the reload action existed in the ViewModel but had
                // no emitter, so a failed or empty fetch was a dead end.
                TextButton(onClick = onReload, enabled = !loading) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(CrushSpacing.xs))
                    Text("Reload", style = CrushType.control)
                }
            }
            Text(
                text = if (providerName.isBlank()) {
                    "Live model list. FREE marks a model listed at no cost."
                } else {
                    "Live list from $providerName. FREE marks a model listed at no cost."
                },
                style = CrushType.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Only claim absence when the list actually loaded. During a
            // failed fetch the catalogue is empty but the model's absence is
            // unverified, and the error banner below already tells that story.
            if (!loading && error == null && selected != null && !selected.presentInCatalogue) {
                Spacer(Modifier.height(CrushSpacing.sm))
                CrushBanner(
                    text = "${selected.id} isn't in the current list. It stays selected " +
                        "until you choose another model.",
                    tone = CrushTone.Warning,
                    actionLabel = "Reload",
                    onAction = onReload
                )
            }

            error?.let {
                Spacer(Modifier.height(CrushSpacing.sm))
                CrushBanner(text = it, tone = CrushTone.Error)
            }

            if (loading) {
                Spacer(Modifier.height(CrushSpacing.sm))
                CrushLoadingRow(
                    text = if (models.isEmpty()) "Loading models..." else "Refreshing models...",
                    modifier = Modifier.padding(vertical = CrushSpacing.sm)
                )
            } else if (models.isEmpty() && error == null) {
                Spacer(Modifier.height(CrushSpacing.sm))
                CrushEmptyState(
                    title = "No models listed",
                    body = "The provider returned an empty list. Reload to try again."
                )
            }

            Spacer(Modifier.height(CrushSpacing.sm))
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                items(models, key = { it.id }) { m ->
                    val isSelected = m.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) LocalCrushColors.current.accentSoft
                                else Color.Transparent
                            )
                            .clickable { onPick(m.id) }
                            // Active state: visible check for sighted users,
                            // speakable state for readers.
                            .let { base ->
                                if (isSelected) {
                                    base.semantics { stateDescription = "selected" }
                                } else {
                                    base
                                }
                            }
                            .padding(
                                horizontal = CrushSpacing.md,
                                vertical = CrushSpacing.md
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.displayName,
                                style = CrushType.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                m.id,
                                style = CrushType.metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (m.supportsImages) {
                            Text(
                                "Images",
                                modifier = Modifier.semantics {
                                    contentDescription = "Supports images"
                                },
                                style = CrushType.metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(CrushSpacing.sm))
                        }
                        if (m.isFree) {
                            // Non-interactive badge (analysis §4 P2): the
                            // AssistChip was focusable and tappable while doing
                            // nothing, so a screen reader announced a fake
                            // actionless row. A labelled Text carries the same
                            // information without inventing a behaviour, and it
                            // gets an explicit contentDescription because
                            // "FREE" alone announces poorly out of context.
                            Text(
                                "FREE",
                                modifier = Modifier.semantics {
                                    contentDescription = "Free model"
                                },
                                style = CrushType.metadata,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(CrushSpacing.sm))
                        }
                        if (isSelected) {
                            // Active-state rule (skill audit 2026-10-31): the
                            // sheet received the selected id but never rendered
                            // it, so the list gave no feedback about the current
                            // choice and a reload looked like the selection was
                            // lost.
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = LocalCrushColors.current.accent
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(CrushSpacing.xl))
        }
    }
}
