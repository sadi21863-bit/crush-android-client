package com.crush.mobile.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.crush.mobile.domain.file.DiffLine
import com.crush.mobile.ui.theme.LocalDiffColors
import com.crush.mobile.domain.file.FileChangeSet
import com.crush.mobile.domain.file.FileDiff
import com.crush.mobile.domain.provider.Providers
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crush.mobile.CrushMobileApp
import com.crush.mobile.data.crush.Session
import com.crush.mobile.data.project.PendingProjectTrust
import com.crush.mobile.di.AppContainer
import com.crush.mobile.ui.CrushrcTrustDialog
import com.crush.mobile.ui.crushrcTrustNote
import com.crush.mobile.util.AppLog
import com.crush.mobile.engine.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenReview: () -> Unit,
    isLocked: Boolean = false
) {
    val context = LocalContext.current
    val container: AppContainer = (context.applicationContext as CrushMobileApp).container
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(context, container))
    val s by vm.state.collectAsState()
    // The engine state of WHATEVER instance the chat layer is bound to ("" =
    // legacy engine on the process-single supervisor; "p<uuid8>" = a project's
    // own engine child). The container flips this flow when the Projects
    // surface opens a project.
    val engineState by container.engineStateForActiveInstance.collectAsStateWithLifecycle()
    val activeInstanceKey by container.activeInstanceKey.collectAsStateWithLifecycle()
    val keyAvailable by container.keyAvailable.collectAsStateWithLifecycle()

    // Tell the ViewModel the gate is closed. It cannot infer this: the lock state
    // lives in MainActivity, and composing this screen behind an overlay was
    // exactly how the engine booted and the key decrypted while locked.
    LaunchedEffect(isLocked) { vm.onLockStateChanged(isLocked) }

    val listState = rememberLazyListState()
    var showModels by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }

    // Prewarm is resolved from the SAME TuningPolicy result the rest of the app
    // uses - AppContainer.applyTuning pushes the identical value into the
    // supervisor - so this reads the existing single source of truth rather than
    // adding a second tuning path. Collected instead of read off the
    // supervisor's volatile field so a change made in Settings is picked up on
    // the way back into chat.
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
        // Required by this overload. AppSettings() defaults prewarmEngine to
        // AUTO resolution, which is the same value TuningPolicy.resolve would
        // produce, so there is no behavioural difference on the first frame.
        initialValue = com.crush.mobile.domain.model.AppSettings()
    )
    val prewarm = remember(settings) {
        container.settingsRepository.resolvedTuning(settings).prewarmEngine
    }

    // The engine was never started on this path: only ChatViewModel touched the
    // supervisor for setWorkActive, so the chat screen sat on "engine stopped"
    // forever. Start it here, and deliberately do NOT stop it on dispose so
    // navigating away cannot invalidate an in-flight session.
    //
    // This now honours the prewarm setting, which this screen used to write and
    // then ignore - the row was a dead control for as long as it existed. With
    // prewarm OFF the boot is deferred to the first keystroke (see the composer's
    // onChange below) instead of happening at composition.
    //
    // The deferral is NOT free and the cost is deliberate. Nothing else in the
    // app calls start(), and send() returns silently while the port is still
    // null, so a cold engine cannot be recovered from by the send path itself -
    // which means the composer has to stay typeable or the screen is simply
    // dead with no way out. The price is a first message that waits for the
    // ~3s boot instead of meeting a warm engine, paid for ~59MB of RAM not being
    // held on a device that may never chat. This must NOT be "simplified" back
    // into an unconditional start().
    // START the ACTIVE engine instance when composing (prewarm on), and again
    // every time the ACTIVE INSTANCE changes (ADR-0001 slice 2, 2026-11-03).
    // bindProject alone cannot start the new instance's engine, and nothing
    // else in the app calls start() except the composer's first keystroke -
    // without this the project chat sat on "engine stopped" with no way out
    // (measured 21:39 on device, test-project). With prewarm OFF the boot
    // defers to the first keystroke instead, which targets the bound instance
    // too (vm.startEngine()).
    LaunchedEffect(activeInstanceKey) {
        vm.bindProject()
        if (prewarm) {
            AppLog.i("Chat", "starting supervisor for instance '$activeInstanceKey' (prewarm on)")
            vm.startEngine()
        } else {
            AppLog.i("Chat", "prewarm off; supervisor deferred to first input for $activeInstanceKey")
        }
    }

    LaunchedEffect(engineState) { vm.bindEngine(engineState) }    // Provider is chosen on the Settings screen, which has its own ViewModel.
    LaunchedEffect(Unit) { vm.observeProviderChanges() }
    // An unlock is the only event that can make a blocked bootstrap retryable;
    // engine state does not change when the user authenticates.
    LaunchedEffect(keyAvailable) { vm.onKeyAvailable() }
    // Autoscroll, but only when already near the bottom so reading history is
    // not yanked away by an incoming token.
    LaunchedEffect(s.messages.size, s.messages.lastOrNull()?.text) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (last >= listState.layoutInfo.totalItemsCount - 3) {
            listState.animateScrollToItem(s.messages.size.coerceAtLeast(0))
        }
    }

    // Shows which thread is open. A permanent "Chat" label hid the fact that
    // reopening a session swapped the entire conversation underneath the user.
    val activeSession = s.sessions.firstOrNull { it.id == s.activeSessionId }
    val threadTitle = if (activeSession != null) {
        SessionList.label(activeSession)
    } else if (s.messages.isEmpty()) {
        "New chat"
    } else {
        "Chat"
    }

    // Notices the ViewModel raised that the user must actually see.
    //
    // Both of these describe a situation where the app previously said nothing
    // and quietly did the wrong thing: a Stop that may not have stopped the
    // agent, and a delete that may not have deleted the chat. A failure the user
    // cannot see is still a failure.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(s.deleteError) {
        s.deleteError?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(s.stopNotice) {
        s.stopNotice?.let { snackbarHostState.showSnackbar(it) }
    }
    // The handoff flow's honest one-liners (P6 Batch C): consent refusals,
    // dispatch failures that leave the row APPROVED and resumable, and the
    // same failure surface a delete or a stop uses.
    LaunchedEffect(s.handoffNotice) {
        s.handoffNotice?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            threadTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            if (activeInstanceKey == com.crush.mobile.engine.EnginePaths.CHAT_INSTANCE_KEY)
                                "Chat - no tools - ${s.phase}" else s.phase,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.engineReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showSessions = true; vm.loadSessions() }) {
                        // Describes the ACTION (skill audit 2026-10-31): the old
                        // "Chats" named a widget, not what tapping it does.
                        Icon(Icons.Default.Menu, contentDescription = "Open chats list")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.startChatSession() }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat (conversation-only)")
                    }
                    // Model picker. Only models Zen actually serves are listed.
                    TextButton(onClick = { showModels = true; vm.loadModels() }) {
                        // Was rendering "freespace-bunny-free" because the badge
                        // and the id had no separator between them.
                        if (s.selectedModel.endsWith("-free")) {
                            Text(
                                "FREE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            s.selectedModel.removeSuffix("-free"),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            )
        },
bottomBar = {
            Composer(
                value = s.input,
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
                enabled = s.engineReady || !prewarm || s.recoverable,
                // Sending still requires a live port. send() reads the port and
                // returns without doing anything when it is null, so enabling
                // this early would swallow the message with no visible error -
                // hence engineReady, or RECOVERABLE (the settled-failure state
                // where the port exists and the preflight explicitly handles
                // the not-ready agent).
                sendEnabled = s.engineReady || s.recoverable,
                streaming = s.isStreaming,
                enterSends = settings.enterSendMode == "send",
                onChange = { text ->
                    // The on-demand start that prewarm=off defers to, so the
                    // engine is already booting by the time the user finishes
                    // typing. start() is idempotent (it returns early on a live
                    // supervisor job), so this is safe on every keystroke and
                    // doubles as a retry after a failed boot. Routes through
                    // the ACTIVE instance (legacy or per-project engine; see
                    // the LaunchedEffect above and vm.bindProject).
                    if (!prewarm) vm.startEngine()
                    vm.setInput(text)
                },
                onSend = vm::send,
                onStop = vm::stopStreaming
            )
        }
    ) { pad ->
        if (s.messages.isEmpty()) {
            // "engine stopped" is ChatViewModel's wording for a supervisor that
            // is not running. With prewarm off that is the user's own choice, not
            // a fault, and showing it unlabelled would read as a broken app.
            val phase = if (!prewarm && !s.engineReady) {
                "Engine starts when you begin typing"
            } else {
                s.phase
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
                s.handoffResumeHint?.takeIf { it.targetSessionId != s.activeSessionId }?.let { hint ->
                    stickyHeader(key = "handoff-resume-${hint.handoffId}") {
                        // The bar's own F04 host: its trust decision is
                        // nearest the affordance (hoisted in this sticky
                        // row's composition - state lives and dies with the
                        // bar itself).
                        var pendingResumeTrust by remember {
                            mutableStateOf<PendingProjectTrust?>(null)
                        }
                        HandoffResumeBar(
                            label = hint.targetLabel,
                            onContinue = { vm.openHandoffInCode { pendingResumeTrust = it } }
                        )
                        pendingResumeTrust?.let { pending ->
                            CrushrcTrustDialog(
                                folderLabel = pending.ref.label,
                                reasonNote = crushrcTrustNote(pending.kind),
                                onConfirm = {
                                    pendingResumeTrust = null
                                    vm.openHandoffInCode { pendingResumeTrust = it }
                                },
                                onDismiss = { pendingResumeTrust = null },
                            )
                        }
                    }
                }
                itemsIndexed(s.messages, key = { _, m -> m.id }) { idx, m ->
                    // D13 RETRY WIRING. The retry prompt is derived at render
                    // time, not stored on the model: the nearest preceding user
                    // row IS the prompt whose turn failed, so re-sending it as
                    // a NEW turn keeps history honest - the failed pair stays
                    // visible and the retry appears as a fresh exchange. The
                    // take(idx) scan only ever runs on failed rows.
                    val retryText = if (m.error != null) {
                        s.messages.take(idx)
                            .lastOrNull { it.role == "user" && it.text.isNotBlank() }?.text
                    } else {
                        null
                    }
                    Bubble(
                        m = m,
                        retryText = retryText,
                        retryEnabled = s.engineReady && !s.isStreaming,
                        onRetry = {
                            retryText?.let { t ->
                                // setInput puts the prompt visibly back in the
                                // composer before send() consumes it; a send
                                // clears input only inside streamSend, so this
                                // is the exact sequence the send button itself
                                // follows. Going through the same gates also
                                // means send()'s own isStreaming/empty guards
                                // back this call up.
                                vm.setInput(t)
                                vm.send()
                            }
                        },
                        // P6 Batch C entry chip: the tapped bubble's index drives
                        // the sheet's prefill prefix (handoffSheetFor), keeping
                        // Batch B's prefill the only summary wording source.
                        onUsePlan = { vm.openHandoffSheet(idx) }
                    )
                }

                // What the agent actually changed, at the END of the turn.
                //
                // Below the reply, because the conversation is the point and the
                // diff is the evidence. It is in the scroll rather than a dialog
                // because the user needs to be able to read it against the reply
                // that explains it.
                s.fileChanges?.let { changes ->
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
                            FileChangesPanel(changes, onOpenReview = onOpenReview)
                        }
                    }
                }
            }
        }
    }

    // Permission gate. In ASK mode the engine BLOCKS the turn until this is
    // answered, so without a dialog here every tool call would hang forever -
    // which is strictly worse than the yolo behaviour it replaces.
    s.pendingPermission?.let { req ->
        AlertDialog(
            onDismissRequest = { vm.resolvePermission(allow = false) },
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
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
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
                TextButton(onClick = { vm.resolvePermission(allow = true) }) {
                    Text("Allow once")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.resolvePermission(allow = true, allowForSession = true) }) {
                        Text("Always")
                    }
                    TextButton(onClick = { vm.resolvePermission(allow = false) }) {
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
    s.pendingQuestion?.let { q ->
        AlertDialog(
            onDismissRequest = { vm.resolveQuestion() },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = {
                Text(q.displayTitle().ifBlank { "The agent has a question" })
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
                TextButton(onClick = { vm.resolveQuestion() }) {
                    Text("Deny")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resolveQuestion() }) {
                    Text("Dismiss")
                }
            }
        )
    }

    if (showSessions) {
        SessionSheet(
            sessions = s.sessions,
            activeId = s.activeSessionId,
            loading = s.sessionsLoading,
            surfaceLabel = vm.surfaceLabel.collectAsState().value,
            onPick = { vm.openSession(it); showSessions = false },
            onDelete = { vm.deleteSession(it) },
            // Q1(a): "+ New chat" is the CHAT choice at creation - the dedicated
            // conversation instance (deny-all tools); Code starts at Projects.
            onNew = { vm.startChatSession(); showSessions = false },
            onSettings = { showSessions = false; onOpenSettings() },
            onProjects = { showSessions = false; onOpenProjects() },
            onReview = { showSessions = false; onOpenReview() },
            onDismiss = { showSessions = false }
        )
    }

    if (showModels) {
        ModelSheet(
            models = s.models,
            selected = s.selectedModel,
            providerName = Providers.byId(s.providerId)?.displayName ?: s.providerId,
            error = s.modelsError,
            onPick = { vm.setModel(it); showModels = false },
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
    s.handoffSheet?.let { sheet ->
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
        var pendingHandoffTrust by remember(sheet) {
            mutableStateOf<PendingProjectTrust?>(null)
        }
        HandoffConsentSheet(
            sheet = sheet,
            projects = s.handoffProjects,
            selectedDestination = selectedDestination,
            summary = summaryDraft,
            onSummary = { summaryDraft = it },
            onSelect = { selectedDestination = it },
            onConfirm = { vm.confirmHandoff(summaryDraft, selectedDestination) { pendingHandoffTrust = it } },
            onDismiss = { vm.closeHandoffSheet() },
            onDiscard = { vm.discardHandoff() },
            onOpenInCode = { vm.openHandoffInCode { pendingHandoffTrust = it } },
            // The Batch F drive's UX fix: a refused Confirm (the redaction
            // net's verdict, an in-flight turn) renders INSIDE the sheet -
            // the snackbars fired behind the sheet's scrim, and eleven
            // taps that "did nothing" taught the owner eleven invisible
            // refusals.
            notice = s.handoffNotice,
        )
        pendingHandoffTrust?.let { pending ->
            val mode = handoffSheetMode(sheet)
            CrushrcTrustDialog(
                folderLabel = pending.ref.label,
                reasonNote = crushrcTrustNote(pending.kind),
                onConfirm = {
                    pendingHandoffTrust = null
                    // THE CONSENT STEP (the 2026-11-11 P5 drive's bug): this
                    // callback used to re-call WITHOUT recording anything on
                    // the chat side, so the second gate returned Required
                    // again and the dialog re-opened forever (27 cycles
                    // logged, zero DB writes). Record consent for the
                    // pending ref's CURRENT bytes first (the service
                    // re-hashes at record time), then re-call - the second
                    // gate finds Allow and the flow continues. Whatever
                    // moved AGAIN inside the re-call's gate re-opens the
                    // dialog.
                    vm.grantHandoffTrust(pending.ref) {
                        if (mode == HandoffSheetMode.DISPATCHED) {
                            vm.openHandoffInCode { pendingHandoffTrust = it }
                        } else {
                            vm.confirmHandoff(summaryDraft, selectedDestination) { pendingHandoffTrust = it }
                        }
                    }
                },
                onDismiss = { pendingHandoffTrust = null },
            )
        }
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        return
    }
    val isUser = m.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Tool activity sits ABOVE the reply bubble, and only for the agent.
        //
        // Placement matters: the agent reads files and runs commands, and the
        // user must see that happening before the summary, not buried inside
        // it. Previously this was parsed and discarded, so all of it was
        // invisible.
        if (!isUser && m.tools.isNotEmpty()) {
            ToolActivityList(m.tools)
            Spacer(Modifier.height(6.dp))
        }
        if (!isUser && m.error != null) {
            // D13 RETRY CARD: a failed assistant turn is not a normal bubble
            // with error text bolted on - it is a failed object. Giving it its
            // own surface (errorContainer), its own announcement and its own
            // recovery affordance is what turns a recorded failure into an
            // actionable one.
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
                        MarkdownText(m.text, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            // Completed assistant text gets an always-visible actions row -
            // copy the whole message, save it as a .md via the SAF picker,
            // or hand the plan into a Code project (P6 Batch C). App-side
            // only (see ChatExport.kt): no tool fires, the engine never
            // sees this, S-01's deny-all chat policy is untouched.
            // Hidden while streaming (a save mid-draw is a partial artifact)
            // and on failed rows (that card's affordance is Retry, not save).
            // The gate is ONE expression - handoffEntryVisible, extracted
            // verbatim from this line - so the handoff chip provably rides
            // the Copy/Save gate's conjunction rather than a lookalike.
            if (handoffEntryVisible(m)) {
                MessageActionsRow(m.text, onUsePlanInCode = onUsePlan)
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
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onErrorContainer,
                fontFamily = FontFamily.Monospace
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
                    diff.created -> MaterialTheme.colorScheme.primary
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
                                Row {
                                    Text(
                                        (line.beforeLine ?: line.afterLine)?.toString() ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(34.dp)
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
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolRow(tool: ToolActivity) {
    // Expand automatically only while the tool is mid-flight, so live progress
    // is visible without the user hunting. A finished tool collapses again.
    var expanded by remember(tool.id) { mutableStateOf(tool.isRunning) }

    val tint = when (tool.state) {
        ToolState.FAILED -> MaterialTheme.colorScheme.error
        ToolState.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        ToolState.RUNNING, ToolState.PENDING -> MaterialTheme.colorScheme.primary
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
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
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
            }

            if (expanded && (tool.args.isNotBlank() || tool.output.isNotBlank() || tool.error != null)) {
                Column(Modifier.padding(start = 32.dp, end = 10.dp, bottom = 6.dp)) {
                    if (tool.args.isNotBlank()) {
                        Text(
                            tool.args,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (tool.output.isNotBlank() || tool.error != null) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    tool.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (tool.output.isNotBlank()) {
                        Text(
                            tool.output,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
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
    sessions: List<Session>,
    activeId: String?,
    loading: Boolean,
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
        sessions.firstOrNull { it.id == id }?.let { SessionList.label(it) }
    }

    // Destructive and irreversible, so it is always confirmed. A single-tap
    // delete in a list where rows are one tap from opening a conversation is
    // exactly how a history gets destroyed by accident.
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
            } else if (sessions.isEmpty()) {
                // Say WHY it is empty. An unexplained empty list reads as data
                // loss, which is the failure mode this project keeps hitting.
                Text(
                    "No chats yet",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    "Start one and it will appear here.",
                    style = MaterialTheme.typography.bodySmall,
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
                                        SessionList.label(sess),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1
                                    )
                                    val detail = SessionList.detail(sess, now)
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
    models: List<com.crush.mobile.data.api.LiveZenModel>,
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
            style = MaterialTheme.typography.bodySmall,
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
                        Text(m.name, style = MaterialTheme.typography.bodyMedium)
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


