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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.crush.mobile.ui.CrushrcTrustDialog
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
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            if (state.binding.kind == BindingKind.ChatPool)
                                "Chat - no tools - ${state.run.phase}" else state.run.phase,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.run.engineReady) LocalCrushColors.current.success
                            else MaterialTheme.colorScheme.onSurfaceVariant
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
                        Icon(Icons.Default.Add, contentDescription = "New chat (conversation-only)")
                    }
                    // Model picker. Only models Zen actually serves are listed.
                    TextButton(onClick = {
                        showModels = true
                        onAction(ChatAction.OpenModels)
                    }) {
                        // Was rendering "freespace-bunny-free" because the badge
                        // and the id had no separator between them.
                        if (state.model.selected?.freeMarker == FreeMarker.Shown) {
                            Text(
                                "FREE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            state.model.selected?.id?.removeSuffix("-free").orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
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
            // "engine stopped" is ChatViewModel's wording for a supervisor that
            // is not running. With prewarm off that is the user's own choice, not
            // a fault, and showing it unlabelled would read as a broken app.
            val phase = if (!state.composer.prewarm && !state.run.engineReady) {
                "Engine starts when you begin typing"
            } else {
                state.run.phase
            }
            EmptyState(phase, Modifier.padding(pad))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
    // failure class the permission dialog fixed. This build cannot answer with
    // real option choices yet (see resolveQuestion), so both actions release
    // the run by cancelling the batch; the dialog says so instead of implying
    // an answer was sent.
    state.gate.question?.let { q ->
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
                        "This build can show the question but not pick an answer yet. " +
                            "Denying or dismissing cancels the question and lets the " +
                            "run continue without one."
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

    if (showSessions) {
        SessionSheet(
            sessions = state.session.list.items,
            activeId = state.session.id,
            loading = state.session.list.loading,
            unavailable = state.session.list.unavailable,
            surfaceLabel = state.session.surface,
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
            onSettings = {
                showSessions = false
                onAction(ChatAction.OpenSettings)
            },
            onProjects = {
                showSessions = false
                onAction(ChatAction.OpenProjects)
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
            selected = state.model.selected?.id.orEmpty(),
            providerName = state.model.providerName,
            error = state.model.catalogue.error,
            onPick = { id ->
                showModels = false
                onAction(ChatAction.SelectModel(id))
            },
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

@Composable
private fun EmptyState(phase: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            phase,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp)
        )
    }
}

/** Assistant-bubble silhouette, shared by the reply path and the error card. */
private val AssistantBubbleShape = RoundedCornerShape(
    topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp
)

@Composable
private fun Bubble(
    m: ChatMessage,
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
            } else {
                Box(
                    modifier = Modifier
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
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            MarkdownText(
                                m.text,
                                modifier = Modifier.fillMaxWidth(),
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            "${changes.fileCount} file${if (changes.fileCount == 1) "" else "s"} changed " +
                "+${changes.totalAdded} -${changes.totalRemoved}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (changes.snapshotUnavailable) {
            Text(
                "No before-image was captured, so no diff is shown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        changes.diffs.filter { it.hasChanges }.forEach { diff ->
            FileDiffRow(diff)
        }

        if (changes.unreadable.isNotEmpty()) {
            Text(
                "${changes.unreadable.size} file(s) not shown (unreadable, binary, or too large): " +
                    changes.unreadable.take(4).joinToString(", ") +
                    if (changes.unreadable.size > 4) ", ..." else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // P4 (2026-11-07): the rail is the latest turn's summary; the Review
        // surface keeps EVERY completed turn of this process runtime with the
        // per-file revert. Only linked when there is something to review: a
        // link that lands on an empty history is a promise the app cannot keep.
        if (onOpenReview != null && changes.fileCount > 0) {
            TextButton(onClick = onOpenReview) {
                Text("Review all turns")
            }
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
                    maxLines = 1
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
                            Text(
                                "@@ ${hunk.beforeStart},${hunk.lines.size} " +
                                    "+${hunk.afterStart},${hunk.lines.size} @@",
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
                placeholder = { Text("Message") },
                maxLines = 5,
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
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onProjects: () -> Unit,
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onNew, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("New chat")
                }
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Settings")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Full-width second row: the Projects surface (ADR-0001 P3).
                // Kept out of the two-button row so neither shrinks below a
                // comfortable label width on a phone.
                OutlinedButton(onClick = onProjects, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Projects")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Full-width third row: the Review surface (P4 v1). Same
                // reasoning as Projects - a half-row with project-relative paths
                // would truncate legibility, and this is the surface where a
                // mis-tap ends up reverting a file, so the target stays big.
                OutlinedButton(onClick = onReview, modifier = Modifier.weight(1f)) {
                    Text("Review changes")
                }
            }
            Spacer(Modifier.height(8.dp))
            // S4: the sheet is scoped to the CURRENT surface's pool (the engine
            // owns session storage per instance), so say WHICH surface the
            // list below belongs to instead of implying a global inbox.
            Text(
                surfaceLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            if (loading && sessions.isEmpty()) {
                Text(
                    "Loading chats...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else if (unavailable && sessions.isEmpty()) {
                // Finding 11: an empty list here is UNQUERIED, not empty. The
                // engine is stopped (or still booting), so the app must not
                // present "No chats yet" as a confirmed answer. A cached
                // non-empty list skips this branch and stays visible.
                Text(
                    "Chats aren't available",
                    style = CrushType.section,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    "The engine isn't connected. Send a message to start it, " +
                        "then reopen this sheet.",
                    style = CrushType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (sessions.isEmpty()) {
                // Say WHY it is empty. An unexplained empty list reads as data
                // loss, which is the failure mode this project keeps hitting.
                Text(
                    "No chats yet",
                    style = CrushType.section,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    "Start one and it will appear here.",
                    style = CrushType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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
                            color = if (active) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
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
                                        .padding(vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        sess.label,
                                        style = CrushType.body,
                                        maxLines = 1
                                    )
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
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = { onDelete(sess.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete chat"
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
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
    selected: String,
    providerName: String,
    error: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Model",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Text(
            "Live list from $providerName. Free models cost nothing.",
            style = CrushType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            items(models, key = { it.id }) { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(m.id) }
                        // Active state: visible check for sighted users,
                        // speakable state for readers.
                        .let { base ->
                            if (m.id == selected) {
                                base.semantics { stateDescription = "selected" }
                            } else {
                                base
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            m.id,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (m.id == selected) {
                        // Active-state rule (skill audit 2026-10-31): the sheet
                        // received the selected id but never rendered it, so
                        // the list gave no feedback about the current choice
                        // and a reload looked like the selection was lost.
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    if (m.isFree) {
                        // Non-interactive badge (analysis §4 P2): the AssistChip
                        // was focusable and tappable while doing nothing, so a
                        // screen reader announced a fake actionless row. A
                        // labelled Text carries the same information without
                        // inventing a behaviour, and it gets an explicit
                        // contentDescription because "FREE" alone announces
                        // poorly out of context.
                        Text(
                            "FREE",
                            modifier = Modifier.semantics {
                                contentDescription = "Free model"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
