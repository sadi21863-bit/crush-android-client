package com.opencode.chat.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencode.chat.OpenCodeChatApp
import com.opencode.chat.data.crush.Session
import com.opencode.chat.di.AppContainer
import com.opencode.chat.util.AppLog
import com.opencode.chat.engine.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val container: AppContainer = (context.applicationContext as OpenCodeChatApp).container
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(context, container))
    val s by vm.state.collectAsState()
val engineState by container.engineSupervisor.state.collectAsStateWithLifecycle()
    val keyAvailable by container.keyAvailable.collectAsStateWithLifecycle()
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
        initialValue = com.opencode.chat.domain.model.AppSettings()
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
    DisposableEffect(prewarm) {
        if (prewarm) {
            AppLog.i("Chat", "starting supervisor (prewarm on)")
            container.engineSupervisor.start()
        } else {
            AppLog.i("Chat", "prewarm off; supervisor deferred to first input")
        }
        onDispose { }
    }

    LaunchedEffect(engineState) { vm.bindEngine(engineState) }
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

    Scaffold(
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
                            s.phase,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.engineReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showSessions = true; vm.loadSessions() }) {
                        Icon(Icons.Default.Menu, contentDescription = "Chats")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newThread() }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
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
                enabled = s.engineReady || !prewarm,
                // Sending still requires a live port. send() reads the port and
                // returns without doing anything when it is null, so enabling
                // this early would swallow the message with no visible error.
                sendEnabled = s.engineReady,
                streaming = s.isStreaming,
                onChange = { text ->
                    // The on-demand start that prewarm=off defers to, so the
                    // engine is already booting by the time the user finishes
                    // typing. start() is idempotent (it returns early on a live
                    // supervisor job), so this is safe on every keystroke and
                    // doubles as a retry after a failed boot.
                    if (!prewarm) container.engineSupervisor.start()
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
                items(s.messages, key = { it.id }) { m -> Bubble(m) }
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
                Column {
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

    if (showSessions) {
        SessionSheet(
            sessions = s.sessions,
            activeId = s.activeSessionId,
            loading = s.sessionsLoading,
            onPick = { vm.openSession(it); showSessions = false },
            onDelete = { vm.deleteSession(it) },
            onNew = { vm.newThread(); showSessions = false },
            onSettings = { showSessions = false; onOpenSettings() },
            onDismiss = { showSessions = false }
        )
    }

    if (showModels) {
        ModelSheet(
            models = s.models,
            selected = s.selectedModel,
            error = s.modelsError,
            onPick = { vm.setModel(it); showModels = false },
            onDismiss = { showModels = false }
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

@Composable
private fun Bubble(m: ChatMessage) {
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
                    Text(m.text, style = MaterialTheme.typography.bodyMedium)
                }
                m.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace
                    )
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
                    .clickable { expanded = !expanded }
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
                    contentDescription = tool.state.name,
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
                enabled = enabled && !streaming
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
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
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
            Spacer(Modifier.height(12.dp))

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
                        Surface(
                            onClick = { },
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
private fun ModelSheet(
    models: List<com.opencode.chat.data.api.LiveZenModel>,
    selected: String,
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
            "Live list from OpenCode Zen. Free models cost nothing.",
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
                    if (m.isFree) {
                        AssistChip(onClick = { }, label = { Text("FREE") })
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}


