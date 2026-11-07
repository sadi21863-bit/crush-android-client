package com.crush.mobile.ui.screens.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.crush.mobile.data.api.LiveZenModel
import com.crush.mobile.data.api.ZenModelsApi
import com.crush.mobile.data.crush.AgentMessageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import com.crush.mobile.data.crush.CreateSessionRequest
import com.crush.mobile.data.crush.DeleteOutcome
import com.crush.mobile.data.crush.CrushApi
import com.crush.mobile.data.crush.CrushConnection
import com.crush.mobile.data.crush.CrushEvent
import com.crush.mobile.data.crush.CrushEventRouter
import com.crush.mobile.data.crush.CrushEventStream
import com.crush.mobile.data.crush.CrushSession
import com.crush.mobile.data.crush.PermissionGrant
import com.crush.mobile.data.crush.PermissionRequest
import com.crush.mobile.data.crush.RunComplete
import com.crush.mobile.data.crush.SelectedModel
import com.crush.mobile.data.crush.Session
import com.crush.mobile.domain.file.FileChangeCollector
import com.crush.mobile.domain.provider.Provider
import com.crush.mobile.domain.provider.Providers
import com.crush.mobile.domain.file.FileChangeSet
import com.crush.mobile.domain.file.WorkspaceSnapshotter
import com.crush.mobile.data.crush.SetModelRequest
import com.crush.mobile.data.crush.StreamMessage
import com.crush.mobile.data.crush.WorkspaceLease
import com.crush.mobile.data.crush.WorkspaceManager
import com.crush.mobile.data.local.SecureKeyStore
import com.crush.mobile.di.AppContainer
import com.crush.mobile.domain.model.TuningPolicy
import com.crush.mobile.engine.EnginePaths
import com.crush.mobile.engine.EngineSockets
import com.crush.mobile.engine.EngineState
import com.crush.mobile.util.AppLog
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Converts one persisted engine message into its history-replay row, or null.
 *
 * The standalone-day audit (2026-10-25) exposed the cost of dropping rows too
 * eagerly: provider-failure turns persist as an assistant message whose only
 * part is a `finish` with reason "error" (real example: `dial tcp: lookup
 * openrouter.ai ... connection refused` after a DNS outage). The live bubble
 * showed the failure, but the replay dropped the row, so every failed turn
 * rendered as NOTHING after a reopen - the user's "the app sometimes doesn't
 * work". Failed turns must stay visible: rows carrying a finish-error are kept
 * with the error text even when they have no other content.
 *
 * Assistant messages with NO parts at all stay dropped. Wire truth: those are
 * mid-stream snapshots that an abrupt stop/loss persisted (`"parts":[]`, from
 * the same audit); they carry no error and no content to attribute. The live
 * path already surfaces "no reply received" for such turns, and fabricating a
 * row from an empty message would invent history. Tool activity is replayed
 * too: a reopened chat that showed the answer but hid the fact the agent read
 * six files would misrepresent what happened.
 */
internal fun replayRow(m: StreamMessage): ChatMessage? {
    val text = m.text
    val tools = m.toolCalls.mapNotNull(ToolActivityParser::from)
    val error = m.finishError
    return when {
        m.role == "user" && text.isNotBlank() ->
            ChatMessage(id = "h-u-${m.id}", role = "user", text = text)
        m.role == "assistant" && (text.isNotBlank() || tools.isNotEmpty() || error != null) ->
            ChatMessage(
                id = "h-a-${m.id}",
                role = "assistant",
                text = text,
                tools = tools,
                error = error
            )
        else -> null
    }
}

/**
 * Merges a freshly fetched transcript with any live turn rows a concurrent
 * send has already appended (analysis R1).
 *
 * This is a race SEAM, not a cosmetic dedupe: [openSession] fetches history
 * over the network, and between that fetch landing and its state write the
 * user can finish sending a turn - send() claims `isStreaming` synchronously,
 * so the window is real. The merge keeps replayed order first (it defines the
 * transcript), and appends any live rows the replay does not contain: the
 * user row and the streaming bubble of the turn that beat the history.
 * Pure so the shape stays pinned by a test.
 */
internal fun mergeReplay(
    replayed: List<ChatMessage>,
    live: List<ChatMessage>
): List<ChatMessage> {
    val replayedIds = replayed.map { it.id }.toSet()
    return replayed + live.filter { it.id !in replayedIds }
}

/**
 * Decides which of the on-screen rows may ride along when a session's
 * transcript is (re)opened.
 *
 * Pure and pinned because 2026-11-02 the call site fed the PREVIOUS session's
 * whole transcript into [mergeReplay] on every switch, which is the reported
 * "the other session's chats get transferred to the chat I am currently in".
 * The only legitimate carry-over is a live turn's rows in the R1
 * reopen-during-run case: the target session was already on screen AND a run
 * is still in flight in it. Everything else keeps a clean transcript; a
 * completed turn reappears through the engine's own history replay.
 */
internal fun liveRowsForOpenSession(
    currentlyViewedSession: String?,
    targetSession: String,
    runInFlight: Boolean,
    onScreen: List<ChatMessage>
): List<ChatMessage> =
    if (currentlyViewedSession == targetSession && runInFlight) onScreen else emptyList()

/**
 * [Immutable] is an ASSERTION, not a derivation (analysis §4 P0-1): without it
 * Compose sees a data class carrying a List and treats every row as
 * possible-mutating, so a role/stability meta cannot mark bubble rows skippable
 * and each streaming frame recomposes every visible cell. Text and tool lists
 * are replaced wholesale here - never mutated in place - so the claim is
 * sound.
 */
@Immutable
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val thinking: String = "",
    val isStreaming: Boolean = false,
    val error: String? = null,
    /**
     * Tool invocations made by the agent, in the order it made them.
     *
     * Populated from the engine's `tool_call` parts. The engine already emitted
     * these and the client already parsed them into `StreamMessage.toolCalls`;
     * they were then dropped on the floor, so the agent read files and ran
     * commands with nothing on screen to show for it.
     *
     * The engine republishes WHOLE messages, so this is replaced wholesale on
     * every frame rather than appended to - appending would duplicate rows
     * every time a tool's state advanced.
     */
    val tools: List<ToolActivity> = emptyList()
)

/**
 * One question batch raised by `question_batch_request` (analysis §4 P0-3).
 *
 * The wire shape per the engine is `{"id","session_id","tool_call_id",
 * "questions":[...]}` (the ids that CrushApi.answerQuestions wants back are a
 * top-level batch id and per-question request ids). Only the batch id is
 * extracted; the RAW payload is kept verbatim so a real answer-choosing UI -
 * which needs the option lists of every question - is built later without
 * having ever thrown that information away mid-stream.
 */
@Immutable
data class PendingQuestion(
    /** Engine batch id (top-level `id`); empty when the engine sent none. */
    val id: String,
    /** The whole batch object as the engine published it. */
    val raw: JsonElement
)

/** Where a human-readable ask MOST LIKELY lives, ordered by confidence. */
private val QUESTION_TEXT_KEYS =
    listOf("question", "title", "text", "message", "prompt", "label", "description")

/** Candidate names for the question batch id, per the engine's known wire shape. */
private val QUESTION_ID_KEYS = listOf("id", "request_id", "batch_id")

/** First present, non-blank STRING member among [keys], else null. */
private fun JsonObject.firstText(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { k ->
        val e = get(k) ?: return@firstNotNullOfOrNull null
        val v = (e as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString
        v?.takeIf { it.isNotBlank() }
    }

/**
 * The question text the dialog gets as its title.
 *
 * Tries the top-level object first, then the first element of `questions[]`.
 * The option field names are engine-versioned, so this reads defensively: any
 * miss degrades to an empty string and the dialog falls back to a generic
 * title rather than crashing on a shape it did not anticipate.
 */
internal fun PendingQuestion.displayTitle(): String {
    val obj = raw as? JsonObject ?: return ""
    val top = obj.firstText(QUESTION_TEXT_KEYS)
    if (!top.isNullOrBlank()) return top.take(240)
    val questions = obj.get("questions") as? JsonArray ?: return ""
    val first = questions.firstOrNull() as? JsonObject ?: return ""
    val text = first.firstText(QUESTION_TEXT_KEYS)?.take(240) ?: ""
    return if (questions.size() > 1) "$text (+${questions.size() - 1} more)" else text
}

data class ChatUiState(
    val phase: String = "starting engine",
    val engineReady: Boolean = false,
    /**
     * A SETTLED bootstrap failure the send-preflight can recover from.
     *
     * 2026-11-03 wedge (user-measured): the beam-free catalog-drift incident
     * left the phase reading "agent not ready: ..." while the engine itself
     * stayed Running - and with prewarm on, the composer was HARD-DISABLED
     * whenever !engineReady, so the designed recovery path (send → preflight
     * re-config → ensureAgentReady with a healthy model) was unreachable and
     * the screen wedged until a provider switch. Recoverable=true is set
     * exactly at that settle point; it enables TYPING and the send button so
     * the preflight can re-drive the handshake. Transient states (starting,
     * the auto-retry loop) stay false: pressing send mid-retry would be a
     * silent no-op, which is the exact anti-pattern the sendEnabled guard
     * exists to prevent.
     */
    val recoverable: Boolean = false,
    val isStreaming: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val models: List<LiveZenModel> = emptyList(),
    val modelsError: String? = null,
    /**
     * Non-null when a stop was requested but the server did not confirm the run
     * ended. Null means either nothing was stopped, or the stop was confirmed.
     *
     * Distinguishing these matters: the agent can keep generating and running
     * tools after the UI stops listening, and saying "stopped" when it did not
     * is a false claim about work still happening.
     */
    val stopNotice: String? = null,
    /**
     * Non-null when a delete could not be confirmed. The session may still
     * exist, and the chat is deliberately left bound rather than optimistically
     * removed - telling a user a conversation is gone when it is not is worse
     * than showing them the failure.
     */
    val deleteError: String? = null,
    /**
 * No default model id.
 *
 * It used to be "space-bunny-free", hardcoded in two places and written to both
 * the large and small slots before agent/init. Crush validates the id against
 * the provider's per-credential catalogue and rejects the entire handshake when
 * it is not there:
 *
 *   HTTP 500 /agent/init {"message":"large model not found in provider"}
 *
 * which is what a friend saw on a fresh install whose key could not see that
 * model. A hardcoded id is a guess, and this engine treats a guess as fatal.
 * The model now comes from a live catalogue fetch - see ModelChoice.
 */
val selectedModel: String = "",
    val input: String = "",
    /** A tool call awaiting the user's decision. See PermissionMode.ASK. */
    val pendingPermission: PermissionRequest? = null,
    /**
     * A question batch the agent is blocked on (analysis §4 P0-3).
     *
     * `question_batch_request` is the permission-ask's sibling for multiple
     * questions. This build can SHOW the batch but cannot answer it with real
     * option choices, so the state carries the raw payload for the dialog and
     * [ChatViewModel.resolveQuestion] releases the run by cancelling the
     * batch. Whole-object replace, never mutated in place.
     */
    val pendingQuestion: PendingQuestion? = null,
    /** Sessions in the current workspace, newest first. See SessionList. */
    val sessions: List<Session> = emptyList(),
    val sessionsLoading: Boolean = false,
    /** The session this chat is bound to. Survives re-attach via the session map. */
    val activeSessionId: String? = null,
    /**
     * Files this session changed.
     *
     * Null means "not collected", which is NOT the same as "nothing changed". The
     * UI must not render an empty list as proof the agent edited nothing: a missing
     * pre-turn snapshot, or a file we could not read, leaves this null or reports
     * the path in [FileChangeSet.unreadable].
     */
    val fileChanges: FileChangeSet? = null,
    /**
     * Crush provider id for this chat.
     *
     * Resolved through [Provider] rather than a literal at each call site. The
     * id is the engine's vocabulary - `opencode` does not exist and 500s - so it
     * is never assembled from a provider's public name.
     */
    val providerId: String = Providers.DEFAULT.id
) {
    val fileChangesLabel: String? get() {
        val s = fileChanges ?: return null
        if (s.isEmpty) return null
        return buildString {
            append("${s.fileCount} file(s) +${s.totalAdded} -${s.totalRemoved}")
            if (s.unreadable.isNotEmpty()) append(", ${s.unreadable.size} unreadable")
            if (s.snapshotUnavailable) append(" (no before-image)")
        }
    }
}

/**
 * Phase 4 chat: real sessions, real streaming, a live model picker.
 *
 * The backend contract this relies on was all proven by the Phase 3 end-to-end
 * test, so this layer is deliberately thin - it owns UI state and nothing else.
 */
class ChatViewModel(
    // LINT StaticFieldLeak waived 2026-10-31: the ONLY construction site
    // (the factory, ~line 2410) passes context.applicationContext, so this
    // field holds the app context and outlives no Activity - the generic
    // Context parameter type is what trips the detector.
    @Suppress("StaticFieldLeak") private val context: Context,
    private val container: AppContainer
) : ViewModel() {

    private val gson = Gson()
    private val secureKeys = SecureKeyStore(context)

    /**
     * Engine client bound to the UDS socket path **at call time** (P1 pivot).
     *
     * Every engine call goes through here so each client sees the socket file
     * as it exists right now: building once at construction time would pin a
     * plain-TCP client whenever the engine had not yet created the file.
     * The URL host/port are shape-only - CrushApi routes them through
     * junixsocket's factory onto the socket file.
     */
    private fun crushApi(port: Int): CrushApi =
        CrushApi(port, socketPath = EngineSockets.socketPath(context, instanceKey))

    /**
     * The engine INSTANCE this session binds to (ADR-0001 accepted shape,
     * slice 2 2026-11-03): "" = the legacy workspace on the process-single
     * engine, "p<uuid8>" = a project on ITS OWN engine child (own socket +
     * own state dir - the measured isolation unit). Written ONLY through
     * [bindProject], which is what the Projects surface drives via the
     * composition; [container.activeProjectRef] stays the single source of
     * truth for WHICH project (the ViewModel mirrors it to survive process
     * death naturally: repoDir is re-read from the container, not stored as
     * ViewModel state).
     */
    private var instanceKey: String = ""

    /** The workspace cwd handed to the engine for THIS instance. */
    private fun workspacePathFor(): String {
        val ref = container.activeProjectRef
        return ref?.repoDir?.absolutePath ?: File(context.filesDir, "workspace").absolutePath
    }

    /** The engine state dir for THIS instance (isolated per project). */
    private fun engineStateDirFor(): String =
        container.runtimeFor(instanceKey).engine.paths.dataDir.absolutePath

    /** The supervisor of the engine instance THIS session is bound to. */
    private val engineSupervisorFor: com.crush.mobile.engine.EngineSupervisor
        get() = container.runtimeFor(instanceKey).supervisor

    /**
     * Idempotent start of the ACTIVE instance's supervisor (legacy singleton
     * or per-project engine child). The composition's start path calls this,
     * and the composer's first keystroke defers here under prewarm=off - both
     * must target the bound instance, never hard-code the legacy singleton.
     */
    fun startEngine() {
        // Gate-7 §1 liveness budget (2026-11-05): at most TWO non-legacy
        // instances with live children (chat + one project). The refusal is
        // fail-closed with a named reason; surface it EXACTLY in the shape
        // bindEngine gives an EngineState.Failed ("engine failed: ...",
        // engineReady=false) - the state flow itself lives inside each
        // key's supervisor and is deliberately not writable from here.
        container.requestEngineStart(instanceKey)?.let { refusal ->
            AppLog.w(TAG, "engine start refused for instance '$instanceKey': $refusal")
            _state.update {
                it.copy(phase = "engine failed: $refusal", engineReady = false)
            }
            return
        }
        engineSupervisorFor.start()
    }

    /**
     * What the ENGINE still knows for [engineVisibleProviderId] (the
     * agent/init-validatable id set, measured from the engine's /providers
     * payload at bootstrap). The picker filters through this so a
     * retired-upstream id can never be offered again after any successful
     * bootstrap. Empty set = engine returned no models (or boot failed before
     * the fetch) - callers keep the raw list in that state.
     */
    @Volatile private var engineVisibleIdsCache: Set<String> = emptySet()
    @Volatile private var engineVisibleProviderId: String = ""

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /**
     * S4: the sheet's surface label - what pool the CURRENT binding hosts
     * ("Chats - conversation only" / "Project - <label>" / "Workspace
     * (legacy)"). Derived from the container's binding, never stored
     * separately: the binding is the single authority (S3).
     */
    val surfaceLabel: StateFlow<String> = container.activeBinding.map { binding ->
        when (binding) {
            is AppContainer.EngineBinding.ChatPool -> "Chats - conversation only"
            is AppContainer.EngineBinding.Project -> "Project - ${binding.ref.label}"
            is AppContainer.EngineBinding.Legacy -> "Workspace (legacy)"
        }
    }.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.Eagerly,
        "Workspace (legacy)"
    )

    /**
     * The turn currently claimed by send(), or null when none is in flight.
     *
     * This is Stop's ONLY input (audit P1-05). Stop used to read the mutable
     * streamJob/wsId/sessionId/port at tap time; through the whole preflight -
     * re-attach plus newSession, two network round-trips - those still
     * described the PREVIOUS turn, so Stop cancelled a job that had already
     * finished and the new prompt was posted anyway. The handle is created in
     * send() before any work starts and is immutable identity for that turn.
     */
    @Volatile
    private var activeRun: RunHandle? = null

    private var wsId: String? = null
    private var sessionId: String? = null

    /**
     * Monotonic id of the chat VIEW. Every session switch bumps it; history
     * fetches and turn preflights snapshot it and DROP their effects when it
     * has moved on.
     *
     * 2026-11-02 measured defect (user report, device traces): rapid switches
     * between existing chats let the compartment with the slower network call
     * win - either a stale history fetch writing the OLD session's replayed
     * transcript over the CURRENT chat, or a turn's rows being inserted into
     * whatever transcript the user later switched to - which read as exactly
     * "the other session's chats get transferred to the chat I am currently
     * in". One epoch guards the three writers: [openSession], [newThread] and
     * the send preflight/stream-insert seam.
     */
    @Volatile
    private var sessionViewEpoch = 0

    /** Drops [handle] as the active run if it still is one; safe either way. */
    private fun abandonRun(handle: RunHandle) {
        if (activeRun === handle) {
            activeRun = null
            container.reportRunInFlight(instanceKey, false)
        }
    }

    /**
     * A provider switch that was chosen in Settings while a run was in flight.
     *
     * Applied at run exit instead of under a streaming turn (audit P1-07):
     * applying it mid-run dropped the workspace id and cached key underneath
     * the stream and stopped the SSE connection feeding it, so the in-flight
     * reply died silently. The persisted setting already says "new", so a
     * relaunch lands on the new provider regardless of this field.
     */
    @Volatile
    private var pendingProviderId: String? = null

    /**
     * Applies a deferred provider switch when nothing is running.
     *
     * Returns true when it applied, so callers that would otherwise bootstrap
     * themselves can ask for it INSTEAD of bootstrapping and forking two
     * workspaces in a row.
     */
    private fun applyPendingProviderIfIdle(): Boolean {
        val pending = pendingProviderId ?: return false
        if (activeRun != null) return false
        pendingProviderId = null
        setProvider(pending)
        return true
    }

    /**
     * The workspace as it looked before the prompt in flight.
     *
     * Reused across turns rather than rebuilt: reading every file on every prompt
     * would be a real cost, and WorkspaceSnapshotter skips files whose size and
     * mtime are unchanged. Null when no turn is in flight.
     */
    private var workspaceSnapshot: WorkspaceSnapshotter.Snapshot? = null

    private val collector: FileChangeCollector by lazy { FileChangeCollector() }

    /**
     * Long-lived workspace event connection, plus the router that feeds it.
     *
     * Crush reaps a workspace with no attached client after ~30s, so the app
     * used to open a stream per turn and the workspace died between every pair
     * of turns. Holding one connection open keeps the workspace attached and
     * removes the reap, which in turn removes the re-attach-and-retry paths
     * that existed only to cope with it.
     *
     * The per-turn [CrushEventStream] is kept as a fallback: if this connection
     * is not healthy the app must still be able to run a turn rather than hang
     * waiting on a socket that will never arrive.
     */
    @Volatile
    private var eventRouter = CrushEventRouter()
    @Volatile
    private var eventConnection: CrushConnection? = null

    /** Engine port the current connection is bound to; a change means a restart. */
    @Volatile
    private var connectionIdentity: String? = null

    /**
     * Process-unique observer epoch of THIS VM (Gate-7 §3, 2026-11-05).
     * Handed out ONCE from the container's connection registry. Unlike the
     * per-view epochs above ([sessionViewEpoch], [modelSelection]) it must be
     * unique ACROSS ViewModels, because the registry compares it against
     * other VMs' registrations for the same instance key: same epoch means
     * "the same reader", a different epoch means "a newer VM owns the key".
     */
    private val connectionOwnerEpoch: Long = container.connectionRegistry.nextOwnerEpoch()

    /**
     * Set once when the registry says this VM's connection was superseded for
     * its key (Gate-7 §3 reclaim). A stale VM logs ONE line and gives up
     * observing permanently - its back-stack entry is gone and it must never
     * disturb (or resurrect a ladder against) the live reader.
     */
    @Volatile
    private var reclaimedBySupersession: Boolean = false
    private var workspaces: WorkspaceManager? = null
    private var port: Int? = null

    /**
     * True when bootstrap stopped because the key exists but is locked. Kept
     * because bootstrap() only runs when engine state CHANGES, so without an
     * explicit retry unlocking the app was not enough to recover - the user had
     * to kill the engine. That is the actual cause of B2, not the missing prompt.
     */
    private var keyBlocked: Boolean = false

    /**
     * Which workspace [sessionId] belongs to.
     *
     * Sessions die with their workspace, so a cached session id is only usable
     * while it is paired with the workspace that created it. Without this the
     * send path would reuse a session against a re-attached (different) workspace
     * and 404 on the session instead of the workspace.
     *
     * OBSOLETE, and kept only so the assignments still compile.
     *
     * That comment stated the wrong model. Sessions do NOT die with their
     * workspace: they are persistent engine state keyed by the workspace's cwd,
     * and the workspace is an attachment handle that the ~30s reap takes away and
     * hands back. The send path used to require `existingSessionWorkspace == ws`
     * before reusing a session, so every re-attach failed that check and started
     * a new chat - the "only 2-3 messages per session" bug. The path now reuses
     * whatever session id it holds and detects a genuinely missing session from
     * the server's own "session not found" response. Delete this field and its
     * assignments once nothing references it.
     */
    private var existingSessionWorkspace: String? = null

    /**
     * Generation token for model picks (analysis R3). Incremented on every
     * tap; only the coroutine whose token still equals the field after each
     * await may settle, so a slow verify from an earlier tap can never
     * overwrite a later decision.
     */
    private var modelSelection = 0

    /**
     * A rejected pick restores [model] (the pre-tap selection) and shows
     * [error] - but ONLY while the same tap still owns the selection; a newer
     * tap meanwhile has already written its own optimistic value, and an old
     * rejection must not undo it.
     */
    private fun revertModelSelection(model: String, error: String) {
        _state.update { it.copy(selectedModel = model, modelsError = error) }
    }

    /** Guards against two concurrent bootstraps racing to create two workspaces. */
    private var bootstrapping: Boolean = false

    /** Latest persisted provider, fed by observeProviderChanges. */
    @Volatile
    private var observedProvider: String = ""

    /**
     * Called after a successful unlock. Retries bootstrap only when the key was
     * the thing blocking it, so this is cheap to call on every unlock.
     */
    fun onKeyAvailable() {
        if (!keyBlocked || wsId != null) return
        bootstrap()
    }

    /**
     * Watches the persisted provider and re-bootstraps when it changes.
     *
     * Necessary because Settings is a different screen with a different
     * ViewModel: it persists the choice and this screen never hears about it.
     * Without this, switching provider in Settings appears to do nothing until
     * the app is killed - and until then the chat keeps the OLD provider's model
     * in the slots while the NEW provider key is injected, which fails with a
     * message about the wrong provider.
     *
     * Guarded on the previous value rather than on any emission, because the
     * settings flow re-emits on every other preference change too and rebuilding
     * the workspace for a theme tweak would be absurd.
     */
    fun observeProviderChanges() {
        viewModelScope.launch {
            container.settingsRepository.settings
                .map { it.providerId }
                .distinctUntilChanged()
                .collect { persisted ->
                    observedProvider = persisted
                    // Not ready: applying here would tear down a bootstrap
                    // already in flight (the guard below predates the switch
                    // policy and exists for this emission noise alone).
                    if (wsId == null && !engineReadyNow()) return@collect
                    // setProvider is the single reducer (audit P1-07): it
                    // decides no-op, revert-clears-pending, apply-now and
                    // defer-while-a-turn-runs in one place.
                    setProvider(Providers.resolve(persisted).id)
                }
        }
    }

    /** True only when a workspace is live, so an early emission is ignored. */
    private fun engineReadyNow(): Boolean = _state.value.engineReady && wsId != null

    fun bindEngine(engineState: EngineState) {
        // S5 wedge instrumentation (2026-11-05): the two overnight hangs showed
        // a HEALTHY child with a UI pinned on "engine stopped", and system
        // logcat was proven lossy for our process lines - so every bind goes
        // to the persistent app.log too, making the supervisor<->UI state
        // pair reconstructible after a sleep.
        com.crush.mobile.util.AppLog.d(
            TAG, "bindEngine state=$engineState instance=$instanceKey"
        )
        val p = (engineState as? EngineState.Running)?.port
        if (p == null) {
            // Flow.update, not read-modify-write (analysis §4 P2 on races):
            // every `_state.value = _state.value.copy(...)` re-reads the state
            // in between and two writers can interleave, silently dropping one
            // side's fields. _state.update is a single atomic CAS loop.
            _state.update {
                it.copy(
                    phase = when (engineState) {
                        is EngineState.Failed -> "engine failed: ${engineState.reason}"
                        is EngineState.Starting -> "starting engine (attempt ${engineState.attempt})"
                        else -> "engine stopped"
                    },
                    engineReady = false
                )
            }
            return
        }
        // A port change means the engine was replaced: workspace id is dead and
        // must be rebuilt. This is the single most important piece of state.
        if (p != port) {
            port = p
            wsId = null
            sessionId = null
            workspaces = null
        }
        if (wsId == null) bootstrap()
    }

    /**
     * Projects-surface entry (ADR-0001 slice 2, 2026-11-03): binds this
     * session to a project's OWN engine instance, or to the legacy workspace
     * for null. The container's [com.crush.mobile.di.AppContainer.activeProjectRef]
     * is the single source of truth for WHICH project (the Projects surface
     * writes it) - this method reads it and re-targets everything
     * engine-derived.
     *
     * A no-op when the key did not change, so composition-time callbacks can
     * fire it freely.
     */
    fun bindProject() {
        // S3: the container's ENGINE BINDING decides the instance. The
        // method name predates modes; it re-targets everything engine-derived
        // for whatever surface the user is on (legacy / chat pool / project).
        val derivation = when (val binding = container.activeBinding.value) {
            is AppContainer.EngineBinding.Project ->
                container.instanceKeyFor(binding.ref.id) to "switching to ${binding.ref.label}..."
            is AppContainer.EngineBinding.ChatPool ->
                EnginePaths.CHAT_INSTANCE_KEY to "switching to the chat pool..."
            is AppContainer.EngineBinding.Legacy ->
                "" to "switching to the workspace..."
        }
        val (key, phaseText) = derivation
        if (key == instanceKey) return
        instanceKey = key
        // An instance switch invalidates every engine-derived handle: the
        // new engine owns its own workspace/session id space (the same reset
        // bindEngine does on an engine replacement - here the switch IS the
        // replacement).
        port = null
        wsId = null
        sessionId = null
        workspaces = null
        // Run-in-flight reporting moved WITH the stop to the container
        // (reportRunInFlight + setActiveProject, 2026-11-04): the ViewModel
        // is scoped to the nav back-stack entry, so its knowledge of the
        // outgoing key cannot be trusted to survive a projects<->chat round
        // trip - measured on device: the stop branch never fired from here,
        // and the project engine outlived the switch. This method keeps the
        // re-targeting only.
        _state.update {
            it.copy(
                phase = phaseText,
                engineReady = false
            )
        }
        // Re-drive the target instance immediately: if it is already Running
        // (a previously-opened project, or the kept-warm legacy engine), no
        // later state TRANSITION will announce it.
        bindEngine(container.runtimeFor(key).supervisor.state.value)
    }

/**
     * True while the lock gate demands authentication.
     *
     * The overlay used to be purely visual: NavGraph composes regardless of gate
     * state, so this ViewModel booted the engine and decrypted the key BEHIND the
     * lock screen. Cancelling the prompt therefore appeared to do nothing, because
     * the privileged work had already started.
     */
    @Volatile
    private var locked: Boolean = false

    /**
     * Called by the composition whenever the lock gate changes.
     *
     * On lock: no NEW work starts. This cannot retract work already in flight - an
     * agent mid-turn is inside the engine and cancelling it is a separate problem
     * - but it prevents the far worse case of the key being decrypted and the
     * engine configured while the user has not authenticated.
     *
     * On unlock: resumes. The workspace was deliberately never built while locked,
     * so it cannot already exist and nothing needs tearing down.
     */
    fun onLockStateChanged(isLocked: Boolean) {
        if (locked == isLocked) return
        locked = isLocked
        if (isLocked) {
            AppLog.i(TAG, "lock engaged; refusing to bootstrap")
            return
        }
        if (port != null) {
            AppLog.i(TAG, "lock released; resuming")
            bootstrap()
        }
    }

    private fun bootstrap() {
        if (bootstrapping) return
        // THE EXECUTION BOUNDARY. Checked before anything with a side effect: the
        // whole point is that a locked app performs no privileged work at all.
        if (locked) {
            AppLog.i(TAG, "bootstrap refused: locked")
            return
        }
        bootstrapping = true
        viewModelScope.launch {
            try {
                doBootstrap()
            } catch (t: Throwable) {
                // MUST NOT let this escape. viewModelScope has no
                // CoroutineExceptionHandler, so an escaping exception reaches
                // Android's default handler and kills the process. doBootstrap
                // makes unguarded network calls (createWorkspace, newSession),
                // and the engine can be mid-restart at any moment - which is
                // routine, not exceptional.
                AppLog.e(TAG, "bootstrap failed: ${t.javaClass.simpleName}: ${t.message}")
                keyBlocked = false
                _state.update {
                    it.copy(
                        phase = "Could not start the agent. Retrying...",
                        engineReady = false
                    )
                }
            } finally {
                bootstrapping = false
            }
        }
    }

    private suspend fun doBootstrap() {
        _state.update { it.copy(phase = "connecting to agent", engineReady = false, recoverable = false) }
        // S5/A11+A12 (2026-11-05): resolve the RESTORED binding before any
        // instance-derived path (workspacePathFor, engineStateDirFor, the
        // supervisor handle) is read. The container's own cold-start launch
        // usually finished long before; this await closes the race where the
        // VM's first boot starts faster than that DataStore read. Also the
        // boot's own profile-of-truth sync (same principle as the persisted
        // provider read below): configure the binding the CONTAINER holds,
        // never a stale ViewModel key. The command style mirrors
        // bindEngine's replacement reset - handles nulled, engine_state
        // observed once - because bindProject()'s normal path deliberately
        // parks on port=null and waits for the next state emission, which
        // would end THIS boot without opening anything.
        runCatching { container.restoreLastBinding() }
        val boundKey = container.activeInstanceKey.value
        if (boundKey != instanceKey) {
            AppLog.i(TAG, "bootstrap adopting the container's binding '$boundKey' (was '$instanceKey')")
            instanceKey = boundKey
            port = null
            wsId = null
            sessionId = null
            workspaces = null
            bindEngine(container.runtimeFor(boundKey).supervisor.state.value)
        }
        val p = port ?: return
        val api = crushApi(p)
        val mgr = WorkspaceManager(api)
        val session = CrushSession(api, mgr)
        val d = File(context.filesDir, "").absolutePath

        // Other things below still use $d for paths unrelated to the engine
        // state; keep the meaning intact here.
        AppLog.d(TAG, "bootstrap instance=$instanceKey workspacePath=${workspacePathFor()}")

        // WHICH PROVIDER, resolved ONCE, from the PERSISTED settings we await
        // right below - not from any in-memory mirror.
        //
        // Read from PERSISTED settings, not from in-memory state, because Settings
        // is a different screen with its own ViewModel and the user can change the
        // provider there while this one still holds the old value.
        //
        // The bug this order kills (device 2026-10-20 15:40): the old code read
        // `currentPersistedProvider()`, which is the OBSERVED MIRROR of the
        // settings flow - `observedProvider` or the ChatUiState default. A
        // freshly recreated ViewModel races DataStore's first emission: the
        // mirror is still blank, so the handshake configured the DEFAULT
        // provider (`opencode-zen`), found no ciphertext under that id, and the
        // app showed "no API key - add one in Settings" to a user whose intact
        // openrouter key was sitting in secure_api_keys.xml the whole time. The
        // branch also logged nothing and never retried, so one loss latched the
        // banner for the life of the process. Reading the provider from the SAME
        // emission the permission mode comes from makes resolution deterministic:
        // await persisted settings, then use them.
        //
        // Seven call sites used to pass the literal "opencode-zen". Resolving it
        // here means the key, the model slots, the price lookup and the agent init
        // can never disagree about which provider they are configuring - which is
        // the failure mode when a provider is changed in only some of them: Zen
        // prices read for one provider while the model is set on another, and
        // nothing complains.
        //
        // Fails safe to ASK/AUTO-approve defaults on read errors; the provider
        // read failing is rarer but the same principle - resolve from the last
        // KNOWN persisted value rather than a blank mirror. If the flow had not
        // emitted at all we cannot configure safely: fall back to the mirror
        // (pre-race behaviour, warmed by the ViewModel's own collector) and log
        // loudly, because a wrong provider here undoes the whole handshake.
        val persisted = runCatching {
            container.settingsRepository.settings.first()
        }
        val permissionMode = persisted.getOrNull()?.permissionMode
            ?: com.crush.mobile.domain.model.PermissionMode.ASK
        // S3 belt-and-braces: the chat pool is conversation-only, so yolo can
        // never be armed for it even if a later global auto-approve setting
        // ships - the engine-side tool hiding is the boundary, this is the
        // second lock on the same door.
        val effectivePermissionMode =
            if (instanceKey == EnginePaths.CHAT_INSTANCE_KEY)
                com.crush.mobile.domain.model.PermissionMode.ASK
            else permissionMode
        val providerId = Providers.resolve(
            persisted.getOrNull()?.providerId ?: currentPersistedProvider()
        ).id
        if (persisted.isFailure) {
            AppLog.w(TAG, "settings read failed; configuring provider from mirror: $providerId")
        }
        AppLog.i(TAG, "permissionMode=$effectivePermissionMode providerId=$providerId")

        // SYNC THE VIEW with the provider this boot actually configures
        // (2026-11-02, user-audit request "check all places OpenCode Zen is
        // hardcoded"): state.providerId used to stay at the DEFAULT
        // ("opencode-zen") until a manual setProvider(), because bootstrap
        // only ever wrote it there. The visible cost was measured live: cold
        // boot on OpenCode Go, the model sheet's subtitle interpolated the
        // stale state and read "Live list from OpenCode Zen" under a Go
        // catalogue - and every other consumer of _state.value.providerId
        // (send preflight re-config) risks disagreeing with the engine the
        // same way. The resolution above is the boot's truth; publish it
        // before any decision composes off it. Guard: a switch chosen during
        // this window (pendingProviderId, P1-07) must win over this write.
        _state.update {
            if (pendingProviderId == null && it.providerId != providerId) {
                AppLog.d(TAG, "state.providerId synced to the boot's provider: ${it.providerId} -> $providerId")
                it.copy(providerId = providerId)
            } else {
                it
            }
        }

        val opened = session.openWorkspace(
            workspacePathFor(),
            engineStateDirFor(),
            effectivePermissionMode,
            providerId
        )
        // Read the key for the provider we are about to configure, not for whatever
        // was cached last.
        val key = container.keySession.require(secureKeys, providerId)
        if (key.isBlank()) {
            // The old copy here was a lie. read() returns null for BOTH "the
            // user never gave us a key" and "the key is fine, the auth window
            // lapsed", so telling the user to add a key they had already added
            // sent them to Settings for nothing. Now that the app locks at
            // entry, a blank key here almost always means we are locked.
            //
            // This branch MUST say what happened before giving up: device log
            // 2026-10-20 15:40 spent this path SILENTLY - an app.log window with
            // nothing in it - while the user saw "no API key" for a key that was
            // provably intact on disk. providerId and the ciphertext state are
            // ids, not secrets; the key itself is never logged anywhere.
            val stored = secureKeys.hasStoredCiphertext(providerId)
            AppLog.i(
                TAG,
                "bootstrap key empty: provider=$providerId storedCiphertext=$stored " +
                    "providersWithKeys=${secureKeys.providersWithKeys().joinToString(",")}"
            )
            keyBlocked = stored
            _state.update {
                it.copy(
                    phase = if (stored) {
                        "unlocking your saved key..."
                    } else {
                        "no API key - add one in Settings"
                    },
                    engineReady = false
                )
            }
            return
        }
        keyBlocked = false

        // STEP 1 - claim the workspace immediately.
        //
        // ORDER IS THE WHOLE FIX HERE. Crush reaps an unattached workspace after
        // roughly 30 seconds. Model discovery (provider prices, the Zen
        // catalogue, then up to 24 one-token probes to find one that actually
        // answers) routinely takes longer than that. Discovering the model FIRST
        // therefore let the workspace die mid-bootstrap, and every subsequent
        // agent call 404'd with "workspace not found" - a wall of errors that
        // reads like a broken install rather than an expired workspace.
        //
        // So: handshake and discovery both need the workspace, so do the cheap
        // one first and re-verify after the slow one.
        val preferred = _state.value.selectedModel.ifBlank { null }
        // The workspace claim itself happened above, in openWorkspace. Early
        // agent init happens ONLY when the user already has a model chosen:
        // with none selected, the old code fed a HARDCODED RETIRED id
        // ("space-bunny-free") into the provider's model slots, and Crush
        // treats a hardcoded guess as fatal - the exact "large model not found
        // in provider" a friend hit on a fresh install (CrushSession refused
        // to carry a default for this reason). STEP 2/3 below run the live
        // catalogue, probe, and init with the probed id, and the key reaches
        // the engine there too.
        if (preferred != null) {
            val ready = session.ensureAgentReady(
                key,
                providerId = providerId,
                modelId = preferred,
                mainAgent = Providers.byId(providerId)?.mainAgent?.takeIf { it.isNotBlank() }
            )
            if (!ready.isReady) {
                AppLog.w(TAG, "early init: ${ready.notes.firstOrNull()}")
            }
        }

        // STEP 2 - discover a model that is actually callable.
        //
        // The catalogue comes from the SELECTED provider's own /models, never
        // from Zen by default: constructing the client with Zen's base for a
        // non-Zen provider would probe opencode.ai with that provider's key and
        // fail with an opaque 401 that described nothing.
        // 2026-11-01 (promoted from the v099 lane): the OpenCode/Console
        // family serves the list on a DIFFERENT, plan-dependent base than
        // chat - Provider.modelsListBase covers that split.
        val provider = Providers.byId(providerId) ?: return
        val modelsBase = provider.modelsBase ?: provider.modelsListBase
        if (modelsBase == null) {
            AppLog.w(TAG, "no compat base for provider '$providerId'; cannot fetch a catalogue")
            _state.update {
                it.copy(
                    phase = "This provider has no live model list in this build yet.",
                    models = emptyList(),
                    modelsError = "provider \"$providerId\" has no OpenAI-compatible /models base configured",
                    engineReady = false
                )
            }
            return
        }
        val zen = ZenModelsApi(
            keyProvider = { key },
            baseUrl = modelsBase,
            listBaseOverride = provider.modelsListBase ?: provider.modelsBase,
            chatBaseCandidates = provider.chatBases
        )

        // Load REAL prices + the ENGINE'S VISIBLE model set before parsing the catalogue.
        //
        // Without this the shortlist had no free-first ordering and fell back to
        // raw catalogue order, which begins with paid Claude models. Measured on
        // device: the probe gave up after 8 (probed=8 denied=8) while the one
        // model the key CAN call, space-bunny-free, sits at position 78 of 86.
        // So the fix is not a bigger probe budget - it is knowing which models
        // are free, which is exactly what this supplies.
        //
        // 2026-11-03 (user-reported catalog drift): the engine's /providers
        // payload is ALSO the ground truth of what agent/init will accept. The
        // app's own list endpoint still advertised `beam-free` and the gateway
        // even answered its probe with 200, but the engine's refreshed catalog
        // had dropped it, and init died with "large model not found in provider
        // config" for exactly the id the app then configured. The visible set
        // below is what keeps retired-upstream ids out of the picker and out of
        // the probe shortlist.
        val engineProviders = runCatching {
            api.providers(opened.workspaceId)
        }.getOrDefault(emptyList())
        val visibleEngineIds = ZenModelsApi.engineVisibleModelIds(engineProviders, providerId)
        // Cache for the picker path (loadModels re-fetching through the cache
        // when the workspace-attach already proved what the engine accepts).
        engineVisibleIdsCache = visibleEngineIds
        engineVisibleProviderId = providerId
        val prices = runCatching {
            ZenModelsApi.pricesFromProviders(engineProviders, providerId)
        }.getOrDefault(emptyMap())
        if (prices.isNotEmpty()) {
            zen.applyPricing(prices)
        }
        AppLog.i(TAG, "catalogue prices loaded for ${prices.size} models")

        val catalogue = runCatching { zen.fetch() }.getOrNull()
        // ONE bounded retry of the whole catalogue read (measured on device
        // 2026-11-02, traces 02:15 + 12:44): the same fetch succeeded seconds
        // later on both occasions, and a transient 200-empty/dial flake used to
        // strand the chat in a dead "no usable model" state that only a manual
        // provider re-tap healed. ZenModelsApi returns labelled errors for
        // everything short of a hard throw, so "unusable" == null or empty
        // models with any error state.
        val usable = if (catalogue?.models.orEmpty().isEmpty()) {
            AppLog.w(TAG, "catalogue fetch unusable (${catalogue?.error}); retrying once")
            delay(1_200)
            val retried = runCatching { zen.fetch() }.getOrNull()
            if (retried?.models.isNullOrEmpty()) catalogue else retried
        } else catalogue
        val models = usable?.models.orEmpty().let { listed ->
            if (visibleEngineIds.isEmpty()) {
                if (listed.isNotEmpty()) {
                    AppLog.w(
                        TAG,
                        "engine /providers returned no models for '$providerId'; " +
                            "keeping the raw list (filter would hide everything)"
                    )
                }
                listed
            } else {
                val dropped = listed.filter { it.id !in visibleEngineIds }.map { it.id }
                if (dropped.isNotEmpty()) {
                    AppLog.w(
                        TAG,
                        "hiding ${dropped.size} model(s) the engine no longer knows: " +
                            dropped.joinToString(",")
                    )
                }
                // Preserve catalogue order probe-shortlist assumptions.
                listed.filter { it.id in visibleEngineIds }
            }
        }
        if (models.isEmpty()) {
            AppLog.w(TAG, "no usable model: ${usable?.error}")
            _state.update {
                it.copy(
                    phase = ModelChoice.explainFetchFailure(
                        usable?.error,
                        Providers.byId(providerId)?.displayName ?: providerId
                    ),
                    models = models,
                    modelsError = usable?.error,
                    engineReady = false
                )
            }
            return
        }

        // PROVE the model works before configuring it. Being listed is not
        // enough: Zen's /models is public and still advertises retired ids, so a
        // listed-but-dead model produced "Model is unavailable" on every turn
        // while the app reported a healthy catalogue. Probe with a 1-token
        // request and take the first id that actually answers. The provider's
        // verified ids (if any) are probed first - see Provider.preferredModelIds.
        val shortlist = ModelChoice.shortlist(models, Providers.byId(providerId)?.preferredModelIds.orEmpty())
        val result = zen.firstWorking(shortlist)
        val chosen = result.model
        if (chosen == null) {
            AppLog.w(
                TAG,
                "no usable model: probed=${result.probed} retired=${result.retired} " +
                    "denied=${result.denied} badKey=${result.badKey}"
            )
            _state.update {
                it.copy(
                    phase = ModelChoice.explainNoUsableModel(
                        // Name the ACTIVE provider: the message is the model
                        // access verdict for THIS key, and every registry row
                        // (hyper measured 2026-11-01) must be described by its
                        // own display name, never by the Zen-era constant.
                        providerName = Providers.byId(providerId)?.displayName ?: providerId,
                        listed = models.size,
                        result = result
                    ),
                    models = models,
                    engineReady = false
                )
            }
            return
        }
        AppLog.i(
            TAG,
            "probed ${result.probed} candidate(s), using $chosen (of ${models.size} listed)"
        )

        // STEP 3 - the workspace may have expired during the probe, so apply the
        // probed model and treat a 404 as "re-open", not as a fatal error.
        var wsNow = opened.workspaceId
        // The provider's main-agent choice rides on every init, because a
        // restarted engine recreates the coordinator with its own default
        // (the build agent), which would silently undo the seating.
        val mainAgent = Providers.byId(providerId)?.mainAgent?.takeIf { it.isNotBlank() }
        var init = runCatching {
            session.ensureAgentReady(key, providerId = providerId, modelId = chosen, mainAgent = mainAgent)
        }.getOrElse {
            AppLog.w(TAG, "init with probed model threw: ${it.message}")
            session.ensureAgentReady(key, providerId = providerId, modelId = chosen, mainAgent = mainAgent)
        }

        if (!init.isReady && init.notes.any { it.contains("workspace not found", true) }) {
            AppLog.w(TAG, "workspace reaped during probe; forcing re-open")
            // force=true so the freshness shortcut cannot short-circuit this.
            // Without it the lease may still look fresh and we would "retry"
            // against the very id that just 404'd.
            val back = runCatching { mgr.reattachIfStale(force = true) }
                .onFailure { AppLog.w(TAG, "re-open failed: ${it.message}") }
                .getOrDefault(false)
            val freshWs = mgr.currentWorkspaceId
            if (back && freshWs != null) {
                wsNow = freshWs
                init = session.ensureAgentReady(
                    key,
                    providerId = providerId,
                    modelId = chosen,
                    mainAgent = mainAgent
                )
                if (init.isReady) AppLog.i(TAG, "recovered on workspace $freshWs")
            }
        }

        if (!init.isReady) {
            // Trim the note list: it holds one string per failed call and the
            // same cause repeats, burying the single useful fact.
            val summary = init.notes.distinct().take(2).joinToString("; ")
            _state.update {
                it.copy(
                    phase = "agent not ready: $summary",
                    engineReady = false,
                    // Settled failure: hand recovery back to the user via the
                    // send preflight (recoverable=true arms the composer and
                    // the send button - the 2026-11-03 wedge fix). The engine
                    // itself is Running, so the preflight's re-config chain is
                    // live; a healthy model resolves it on the next send.
                    recoverable = true
                )
            }
            return
        }
        wsId = wsNow
        workspaces = mgr

        // STEP 4 - resume the last real conversation instead of minting another
        // empty chat. Do this LAST so the workspace is already claimed and
        // settled; an earlier attempt at this point was what allowed the reap.
        val wsForResume = wsId
        val resumable: String? = if (wsForResume == null) {
            null
        } else {
            runCatching { findSessionToResume(wsForResume) }
                .onFailure { AppLog.w(TAG, "session resume lookup failed: ${it.message}") }
                .getOrNull()
        }

        sessionId = resumable ?: run {
            AppLog.i(TAG, "no prior session with content; creating one")
            runCatching { mgr.newSession("chat").id }.getOrNull()
        }

        // Bootstrap also retires any prompt from the session it left behind:
        // a permission or question addressed to a pre-restart run can never be
        // validly answered after the workspace was rebuilt (analysis §4 P1-7).
        _state.update {
            it.copy(
                phase = "ready",
                engineReady = true,
                recoverable = false,
                selectedModel = chosen,
                models = models,
                pendingPermission = null,
                pendingQuestion = null
            )
        }
        loadModels()
        // A newly opened session has no measured diff. Null rather than an empty
        // list, so the UI reports "not collected" instead of "no files changed".
        workspaceSnapshot = null
        _state.update { it.copy(fileChanges = null) }
        // Attach the long-lived stream LAST, once the agent is configured and
        // the session is settled. Attaching earlier means a workspace that dies
        // during the slow model probe leaves a socket pointed at nothing.
        attachEventStream(freshWsOrCurrent(), mgr.clientId)
        // The stream speaks the client id the prompt scoping is keyed on from
        // this moment, so the session report belongs right after it. The
        // resumable path re-reports inside openSession; this covers the
        // freshly minted session (no resume), where nothing else would.
        reportCurrentSession(sessionId)
        resumable?.let { openSession(it) }
    }

    /**
     * Keeps [CrushConnection] alive for [ws], reconnecting when the workspace id
     * changes.
     *
     * Safe to call repeatedly: [CrushConnection.ensureAttached] is a no-op when
     * already attached to the same workspace, and replaces the socket when the
     * id differs.
     */
    private fun attachEventStream(ws: String?, clientId: String) {
        // Gate-7 §3 reclaim (2026-11-05): the stale-VM check FIRST, before
        // anything else this method would do. If this VM's registration has
        // lost its key (round trips through the Projects surface create
        // overlapping chat entries), this reader must NOT re-enter the
        // reconnect ladder - the container's revoke or a newer VM's
        // attach already owns the key. Stop, log once, observe never again.
        if (reclaimedBySupersession) return
        if (container.connectionRegistry.isSuperseded(connectionOwnerEpoch, eventConnection)) {
            reclaimedBySupersession = true
            AppLog.i(
                TAG,
                "event connection superseded for instance '$instanceKey'; stale observer retires, " +
                    "no reconnect retries"
            )
            runCatching { eventConnection?.stop() }
            runCatching { eventRouter.close() }
            return
        }
        if (ws.isNullOrBlank()) return
        val p = port ?: return
        // THE IDENTITY (S0/F03): a connection belongs to a SOCKET FILE, not to a
        // port. Every engine reports the sentinel port 1, so the old
        // `p == connectionPort` guard was always true and an instance switch
        // within one ViewModel lifetime REUSED the previous instance's
        // connection. Two measured failure shapes follow: (a) the reused socket
        // serves the WRONG engine, whose workspace router 404s every keyed id;
        // (b) after stop-on-switch the old engine's socket file was unlinked,
        // so the pinned factory connected into ENOENT forever while call-time
        // REST kept succeeding - the turn completed, the banner lied. Rebuild
        // whenever the CURRENTLY BOUND instance's socket file differs.
        val socketFile = EngineSockets.socketPath(context, instanceKey)
        val identity = socketFile.absolutePath
        val conn = eventConnection
        if (conn != null && identity == connectionIdentity) {
            conn.ensureAttached(ws, clientId)
            return
        }
        AppLog.i(
            TAG,
            "event transport identity change -> ${CrushConnection.scrubbedPath(identity)}; rebuilding connection"
        )
        // Socket identity changed; the old connection may hold a reader on the
        // previous file. Stop it and close its router before replacing both.
        runCatching { conn?.stop() }
        runCatching { eventRouter.close() }
        val router = CrushEventRouter()
        // SSE timeouts live on the base client (readTimeout 0 = no idle limit,
        // the same shape CrushApi's stream client used); the supplier lets the
        // transport resolve the socket file PER ATTEMPT (S0/F03+F11).
        val sseBase = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val fresh = CrushConnection(p, router, gson, socketFile) { EngineSockets.okClient(it, sseBase) }
        // Assign before attaching so a send racing bootstrap sees a consistent
        // (router, connection) pair.
        eventRouter = router
        eventConnection = fresh
        connectionIdentity = identity
        // Gate-7 §3 registration (2026-11-05): this VM owns the live reader
        // for its instance key from this moment. Registering supersedes any
        // OTHER VM's registration for the same key - the legacy "" key
        // included - the same deferral rule the container's stop-on-switch
        // guard applies: while a run is in flight on the key, the superseded
        // reader's stop pins and completes off reportRunInFlight(key,false).
        // Register BEFORE ensureAttached so a reader that starts breathing
        // here can never be the unregistered one.
        container.connectionRegistry.attach(
            instanceKey,
            fresh,
            connectionOwnerEpoch,
            runInFlightOnKey = { container.engineRunInFlightFor == instanceKey }
        )
        fresh.ensureAttached(ws, clientId)
        AppLog.i(TAG, "long-lived event stream attached to $ws")
    }

    /** The workspace we should be attached to right now, preferring the newest. */
    private fun freshWsOrCurrent(): String? = wsId

    /**
     * Tells the engine which session this client is viewing (POST
     * current-session, engine v0.98.2).
     *
     * This is half of the v0.98.2 prompt-scoping contract; the other half lives
     * in [attachEventStream] and [CrushApi.currentSession]: the SSE stream must
     * be attached with the client id sent here, because the engine keys prompt
     * delivery on that identity pair. On v0.98.1 the route does not exist, the
     * POST logs a failure, and everything degrades to the old open-broadcast.
     *
     * Fire-and-forget by design: a failed report must never block opening a
     * conversation or starting a run. [null] clears (landing state).
     */
    private fun reportCurrentSession(sid: String?) {
        val mgr = workspaces ?: return
        val p = port ?: return
        val ws = wsId ?: mgr.currentWorkspaceId ?: return
        viewModelScope.launch {
            // The engine requires the SSE attach (same client id) to be KNOWN
            // before current-session, and on a FRESH engine the attach
            // registration lands slower than this POST - measured 22:18
            // (test-project's first ever boot: HTTP 409 "client not attached"
            // on the very first call, repeated on the next boot). The fix is a
            // tiny bounded retry, firing-and-forgetting as before (the caller
            // was never blocked), and only for THAT race: a genuinely
            // different failure (missing route on old engines) fails fast the
            // way it always has.
            val attempts = 4
            var lastFailure: String? = null
            for (n in 1..attempts) {
                val outcome = runCatching { crushApi(p).currentSession(ws, mgr.clientId, sid.orEmpty()) }
                if (outcome.isSuccess) {
                    AppLog.i(TAG, "current-session reported: ${sid?.take(8) ?: "(cleared)"}")
                    return@launch
                }
                lastFailure = outcome.exceptionOrNull()?.message
                if (lastFailure?.contains("client not attached") != true) break
                if (n < attempts) delay(500)
            }
            AppLog.w(
                TAG,
                "current-session report failed after $attempts attempts: $lastFailure"
            )
        }
    }

    override fun onCleared() {
        // Gate-7 §3 (2026-11-05): drop this VM's registry record FIRST, and
        // ONLY if it is still ours - a newer VM's registration for the same
        // key must not be killed by an old entry's cleanup (revoke-only-if-
        // own). The direct stop below remains this VM's own teardown and
        // runs regardless of what the registry holds.
        container.connectionRegistry.revokeIfOwn(
            instanceKey, connectionOwnerEpoch, "chat entry cleared"
        )
        runCatching { eventConnection?.stop() }
        runCatching { eventRouter.close() }
    }

    /**
     * Finds the newest session that actually has content, so a relaunch resumes
     * the user's last real conversation instead of a blank one.
     *
     * Prefers sessions WITH messages over merely the newest. The empty sessions
     * this bug generated are all equally recent, so ordering by recency alone
     * would resume a blank thread - and look like nothing had been fixed.
     *
     * @return the session id to resume, or null when nothing is worth resuming.
     */
    private suspend fun findSessionToResume(ws: String): String? {
        val p = port ?: return null
        val list = runCatching { crushApi(p).listSessions(ws) }
            .onFailure { AppLog.w(TAG, "listSessions failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (list.isEmpty()) return null

        // Publish the list so the drawer is populated before we pick.
        _state.update { it.copy(sessions = list, sessionsLoading = false) }

        val withMessages = list.filter { it.messageCount > 0 }
        val target = withMessages.maxByOrNull { it.updatedAt }
            ?: list.maxByOrNull { it.updatedAt }
            ?: return null
        AppLog.i(TAG, "resuming session ${target.id} (${target.messageCount} msgs)")
        return target.id
    }

/** Live catalogue for the current provider, so the picker cannot offer a retired model. */
    fun loadModels() {
        viewModelScope.launch {
            // Resolve the provider ONCE, here in the suspend context, per the
            // same race that broke bootstrap (see currentPersistedProvider):
            // the key factory lambda below is `() -> String`, so it cannot
            // suspend, and a mirror read inside it can still be cold.
            val providerId = currentProviderId()
            // 2026-11-01 (promoted from the v099 lane): OpenCode/Console
            // families serve the list on a plan-dependent base - prefer
            // Provider.modelsListBase, then the plain compat base. resolve()
            // guarantees a known id, so the ?: exit is unreachable in
            // practice but keeps the checker honest without !!.
            val providerRow = Providers.byId(providerId) ?: return@launch
            val base = providerRow.modelsBase ?: providerRow.modelsListBase
            if (base == null) {
                _state.update {
                    it.copy(
                        models = emptyList(),
                        modelsError =
                            "provider \"$providerId\" has no OpenAI-compatible /models base configured"
                    )
                }
                return@launch
            }
            val r = ZenModelsApi(
                { container.keySession.require(secureKeys, providerId) },
                baseUrl = base,
                chatBaseCandidates = providerRow.chatBases
            ).fetch()
            // Engine-aware filtering (2026-11-03): drop ids the engine's
            // config no longer knows, using the cache bootstrap recorded for
            // THIS provider. Without the cache (failed boot) the raw list
            // stands - the picker must not lie about availability it cannot
            // verify yet.
            val visible = engineVisibleIdsCache
                .takeIf { engineVisibleProviderId == providerId && it.isNotEmpty() }
            val models = if (visible == null) {
                r.models
            } else {
                val dropped = r.models.filter { it.id !in visible }.map { it.id }
                if (dropped.isNotEmpty()) {
                    AppLog.w(
                        TAG,
                        "picker: hiding ${dropped.size} model(s) the engine no longer knows: " +
                            dropped.joinToString(",")
                    )
                }
                r.models.filter { it.id in visible }
            }
            _state.update {
                it.copy(
                    models = models,
                    modelsError = r.error
                )
            }
        }
    }

    /**
     * The provider id as PERSISTED, read synchronously.
     *
     * Settings is a separate screen with a separate ViewModel, so a change there
     * is invisible to the in-memory ChatUiState until something rebuilds it. The
     * provider decides the key, both model slots and the agent init, so reading a
     * stale value here would configure the old provider with the new provider key.
     *
     * Falls back to the in-memory value if the flow has not emitted yet, and to
     * the default if there is nothing at all - never throws, because failing to
     * read a preference must not stop the app from starting.
     */
    private suspend fun currentPersistedProvider(): String {
        // THE persisted value, awaited. The in-memory mirror (observedProvider)
        // is fed by a separate collector, so a freshly recreated ViewModel can
        // reach a send or a bootstrap BEFORE the mirror has warmed; trusting the
        // mirror there is what configured "opencode-zen" against an intact
        // openrouter key and showed "no API key" for a key that was on disk the
        // whole time (device log 2026-10-20 15:40). Await the real DataStore
        // emission - first() answers immediately because DataStore serves the
        // in-process cache after the first read - and keep the mirror only as
        // the fallback if DataStore itself errors.
        val persisted = runCatching {
            container.settingsRepository.settings.first().providerId
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return persisted
            ?: observedProvider.takeIf { it.isNotBlank() }
            ?: _state.value.providerId
    }

    /** Resolved provider id, for call sites that need the key scoped to it. */
    private suspend fun currentProviderId(): String =
        Providers.resolve(currentPersistedProvider()).id

    /**
     * (retired 2026-11-01, 0.99.0 promotion) — replaced by provider-aware
     * construction of [ZenModelsApi] at its three call sites: the
     * OpenCode/Console family needs list-vs-chat split bases
     * ([Provider.modelsListBase], [Provider.chatBases]) which a single-base
     * read cannot express.
     */

    /**
     * Switches provider and re-bootstraps - the single switch reducer.
     *
     * The whole handshake is redone rather than patched: the key, BOTH model
     * slots, and the agent init are all provider-scoped, so changing only the
     * provider id leaves the previous provider's model in the slots and the turn
     * fails with a message about the wrong provider.
     *
     * A provider change invalidates the model choice - a model id from one
     * provider's catalogue means nothing to another, and passing it through would
     * reproduce "large model not found in provider" on a different route.
     *
     * When to apply comes from [ProviderSwitchPolicy] (audit P1-07): a switch
     * during a run is DEFERRED, because applying it the old way dropped the
     * workspace id, the cached key and the shared SSE connection underneath a
     * streaming turn, and the reply died while the UI kept its bubble. The
     * deferred id is applied at run exit via [applyPendingProviderIfIdle], and
     * reverting to the already-active provider cancels the deferral instead of
     * honouring it.
     */
    fun setProvider(id: String) {
        val provider = Providers.resolve(id)
        when (ProviderSwitchPolicy.resolve(
            current = _state.value.providerId,
            requested = provider.id,
            runInFlight = activeRun != null,
            pending = pendingProviderId
        )) {
            ProviderSwitchPolicy.Decision.NoOp -> return
            ProviderSwitchPolicy.Decision.ClearPending -> {
                AppLog.i(TAG, "provider reverted to ${provider.id}; deferred switch cancelled")
                pendingProviderId = null
                return
            }
            ProviderSwitchPolicy.Decision.Defer -> {
                if (pendingProviderId != provider.id) {
                    AppLog.i(TAG, "provider switch to ${provider.id} deferred; a turn is in flight")
                }
                pendingProviderId = provider.id
                return
            }
            ProviderSwitchPolicy.Decision.ApplyNow -> pendingProviderId = null
        }
        // The engine-visible cache is provider-scoped: a new provider must not
        // inherit the old one's accepted-id set.
        engineVisibleIdsCache = emptySet()
        engineVisibleProviderId = ""
        AppLog.i(TAG, "provider switched to ${provider.id}; re-bootstrapping")
        _state.update {
            it.copy(
                providerId = provider.id,
                selectedModel = "",
                models = emptyList(),
                modelsError = null,
                fileChanges = null
            )
        }
        // Drop the cached decrypted key with the workspace. It belongs to the
        // provider being left, and a stale key in memory is a usable credential
        // for a provider the user is no longer looking at: switching back would
        // serve it without a fresh unlock. Re-bootstrap reads the new provider's
        // key from storage, and if it has none, the chat says so.
        container.keySession.clear()
        // Force a clean re-bootstrap: drop the workspace and any connection so
        // nothing carries over from the previous provider.
        wsId = null
        sessionId = null
        workspaces = null
        workspaceSnapshot = null
        eventConnection?.stop()
        bootstrap()
    }

    fun setModel(id: String) {
        // STATEFLOW RACE R3 (analysis 2026-10-31): the tap optimistically
        // wrote `selectedModel = id` and the VERIFY below could reject - but
        // nothing reverted the optimistic write, so the TopBar showed a model
        // the engine slots had actually refused. Two writers (tap vs verified
        // slots) disagreed, and the tap always won on screen.
        //
        // Fix: capture the pre-tap value, keep, and (a) revert to it when this
        // tap's verification rejects, (b) ignore the result entirely when a
        // NEWER tap replaced this one - the generation token makes the latest
        // tap the only writer that may settle. Both writers now agree.
        val previousModel = _state.value.selectedModel
        _state.update { it.copy(selectedModel = id) }
        val myTap = ++modelSelection
        val ws = wsId ?: return
        val p = port ?: return
        val providerId = Providers.resolve(_state.value.providerId).id
        viewModelScope.launch {
            // Verify before writing to the slots. The picker is fed the public
            // /models list, which contains retired ids, so a tap could otherwise
            // configure a model that fails every later turn with
            // "Model is unavailable" - the same class of failure as the original
            // hardcoded default, just user-triggered.
            val key = runCatching { container.keySession.require(secureKeys, currentProviderId()) }
                .getOrNull()
            if (myTap != modelSelection) return@launch
            if (key == null) {
                // Superseded taps never own the error or the revert: the latest
                // tap decides what the picker shows.
                revertModelSelection(previousModel, "No API key is stored yet. Add one in Settings.")
                return@launch
            }
            val providerName = Providers.byId(providerId)?.displayName ?: providerId
            // 2026-11-01 (promoted from the v099 lane): resolve() guarantees a
            // known id, so a missing row cannot happen; the ?: exit keeps the
            // nullability checker honest without !!.
            val providerRow = Providers.byId(providerId) ?: return@launch
            val base = providerRow.modelsBase ?: providerRow.modelsListBase
            val probe = if (base == null) null else ZenModelsApi(
                keyProvider = { key },
                baseUrl = base,
                listBaseOverride = providerRow.modelsListBase ?: providerRow.modelsBase,
                chatBaseCandidates = providerRow.chatBases
            ).probe(id)
            // The plan-walk turns 404s into NEXT-base attempts, so what remains
            // after the walk is a real gateway verdict on the right base; the
            // message now explains WHY rather than saying "did not answer".
            AppLog.w(TAG, "probe $id -> ${probe?.verdict} detail=${probe?.detail}")
            val rejection = when {
                base == null ->
                    "Cannot verify \"$id\": provider \"$providerName\" has no live " +
                        "model list in this build, so taps cannot be trusted."
                probe == null -> "Verification did not run (no reachable base)."
                probe.verdict == com.crush.mobile.data.api.ProbeVerdict.WORKS -> null
                // 2026-11-01, officially explained: the OpenCode gateway bills
                // the WORKSPACE the key was minted in ("each key works in one
                // workspace, chosen when it is created"). A Go-entitled model
                // 402ing "Insufficient account funds" on the Go base means this
                // key's workspace is not the subscribed one - a fresh key
                // minted there fixes it, not a top-up.
                probe.verdict == com.crush.mobile.data.api.ProbeVerdict.PAYMENT_REQUIRED &&
                    providerRow.chatBases.isNotEmpty() ->
                    "\"$id\" returned \"Insufficient account funds\" on the Go " +
                        "gateway: this key was minted in a workspace without the " +
                        "Go subscription. Create a new personal API key inside the " +
                        "Go-subscribed workspace (Console > Account > Personal API " +
                        "keys > choose the Workspace), then paste that key here."
                probe.verdict == com.crush.mobile.data.api.ProbeVerdict.RETIRED ->
                    "\"$id\" is not callable on your plan's gateway right now " +
                        "(\"Model is unavailable\"). Pick another."
                else -> "\"$id\" answered ${probe.verdict}: ${probe.detail ?: "no detail"}"
            }
            if (rejection != null) {
                if (myTap != modelSelection) return@launch
                revertModelSelection(previousModel, rejection)
                return@launch
            }
            // Superseded taps do not write the slots either: they lost the race
            // and their verification belongs to a selection nobody made.
            if (myTap != modelSelection) return@launch
            // Both slots: Crush uses the small one for session titles.
            runCatching {
                val api = crushApi(p)
                for (slot in listOf(SetModelRequest.LARGE, SetModelRequest.SMALL)) {
                    api.setModel(ws, slot, SelectedModel(model = id, provider = providerId))
                }
            }.onFailure { Log.w(TAG, "setModel failed: ${it.message}") }
        }
    }

    /**
     * Loads the chat list for the current workspace.
     *
     * Re-attaches FIRST, for the same reason send() does: an unattached
     * workspace is reaped after ~30s, so listing sessions on a workspace that
     * has been idle since the last turn returns 404 and the drawer silently
     * shows nothing. A silent empty list is indistinguishable from "no history",
     * which is the exact lie this project keeps hitting.
     */
    fun loadSessions() {
        val mgr = workspaces ?: return
        val p = port ?: return
        _state.update { it.copy(sessionsLoading = true) }
        viewModelScope.launch {
            val ok = runCatching { mgr.reattachIfStale() }.getOrDefault(false)
            val ws = if (ok) mgr.currentWorkspaceId else null
            val list = if (ws == null) {
                emptyList()
            } else {
                runCatching { crushApi(p).listSessions(ws) }
                    .onFailure { AppLog.w(TAG, "listSessions failed: ${it.message}") }
                    .getOrDefault(emptyList())
            }
            _state.update {
                it.copy(
                    sessions = SessionList.sort(list),
                    sessionsLoading = false
                )
            }
        }
    }

    /**
     * Binds the chat to an existing session and replays its history.
     *
     * This is what makes a conversation resumable rather than a single turn:
     * without it every send was its own throwaway thread with its own
     * auto-generated title and nothing could ever be reopened.
     */
    fun openSession(sessionId: String) {
        val mgr = workspaces ?: return
        val p = port ?: return
        val epoch = ++sessionViewEpoch
        viewModelScope.launch {
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) return@launch
            val ws = mgr.currentWorkspaceId ?: return@launch
            val history = runCatching { crushApi(p).messages(ws, sessionId) }
                .onFailure { AppLog.w(TAG, "history failed: ${it.message}") }
                .getOrDefault(emptyList())

            // Stale-switch guard (2026-11-02): while this history fetch was in
            // flight the user may have switched to another session (or a new
            // thread). Without this check the last fetch to land won - writing
            // the OLD session's replayed transcript, wsId and sessionId over
            // the user's CURRENT chat. Only the newest switch may write.
            if (epoch != sessionViewEpoch) {
                AppLog.w(TAG, "openSession($sessionId) dropped: the view moved on while the history loaded")
                return@launch
            }

            // Replay through the one seam that decides what a row is worth
            // keeping: tool activity is replayed too, finish-error rows are
            // preserved (the audit's invisible failed turns - see [replayRow]),
            // and mid-stream empty snapshots stay dropped.
            val replayed = history.mapNotNull { m -> replayRow(m) }

            wsId = ws
            // Fully qualified: inside launch{} a bare `this` is the
            // CoroutineScope, and the sessionId PARAMETER shadows the property,
            // so `sessionId = sessionId` cannot resolve either way.
            this@ChatViewModel.sessionId = sessionId
            existingSessionWorkspace = ws
            // First act after the session becomes the viewed one: report it,
            // so a permission prompt raised by THIS session can reach us. The
            // prompt can only arrive while the stream is attached and the
            // report is in - ordering this before any replayed UI settles
            // costs nothing and cannot race a turn the user sends later.
            reportCurrentSession(sessionId)
            // No streaming-id reset needed: a turn's bubble is identified by
            // its own RunHandle, and the replayed message list contains none
            // of the previous turn's rows, so a still-running old turn cannot
            // touch it.
            //
            // Reopening a session is not the session the last diff was measured
            // on. Clearing here prevents the drawer's diff from one chat being
            // displayed under another - which reads as this session having made
            // edits it never made.
            workspaceSnapshot = null
            // Switching chats also retires a prompt the OLD session raised:
            // an unanswered permission or question belongs to the run stream
            // that was being watched, and answering it from a different chat
            // forwards the wrong session's context (analysis §4 P1-7).
            // STATEFLOW RACE R1 (analysis 2026-10-31): between the history
            // fetch above and this write, the user can finish sending a turn -
            // send() claims isStreaming synchronously, so the window is real on
            // a slow network. The old unconditional `messages = replayed,
            // isStreaming = false` erased that turn's user row + streaming
            // bubble and dead-stopped the UI while the run was live.
            //
            // Fix, inside the CAS loop so the freshest state is what wins:
            // MERGE - replayed rows first (they define the transcript order),
            // then any live rows the replay does not contain (the user row and
            // the streaming bubble of the turn that beat the history) - and
            // only drop isStreaming when no live run exists. [mergeReplay]
            // is the extracted, unit-pinned shape.
            _state.update {
                // MERGE ONLY WHAT BELONGS TO THIS TRANSCRIPT (2026-11-02). At
                // this moment it.messages still holds the PREVIOUS session's
                // rows, and mergeReplay appends every live row the fetched
                // history does not contain - so switching A->B rendered A's
                // whole transcript under B's. Live rows only ever belong here
                // in the R1 reopen-during-run case: the target session was
                // ALREADY on screen when the write lands AND a turn is still
                // streaming in it. Switching away drops the old rows from
                // view; switching back replays the engine's own history,
                // which by then includes the completed turn.
                val liveRows = liveRowsForOpenSession(
                    currentlyViewedSession = it.activeSessionId,
                    targetSession = sessionId,
                    runInFlight = activeRun != null,
                    onScreen = it.messages
                )
                it.copy(
                    messages = mergeReplay(replayed, liveRows),
                    isStreaming = if (activeRun != null) it.isStreaming else false,
                    activeSessionId = sessionId,
                    fileChanges = null,
                    pendingPermission = null,
                    pendingQuestion = null
                )
            }
        }
    }

    /**
     * S4: the session surface's "+ New chat" button = a CHAT-pool session
     * (Q1(a) choose-at-creation; the chat choice is the Chat default, Q3).
     * Binds the dedicated conversation instance first - its switch contract
     * stops the outgoing engine unless a run is in flight - then mints a
     * fresh session there. newThread falls back to bootstrap when the pool's
     * WorkspaceManager is not handed out yet, so either path lands a real
     * chat-pool session.
     */
    fun startChatSession() {
        container.setActiveChatPool()
        bindProject()
        newThread()
    }

    /** Starts a fresh thread, leaving history intact and reachable. */
    fun newThread() {
        val mgr = workspaces ?: run { bootstrap(); return }
        val epoch = ++sessionViewEpoch
        viewModelScope.launch {
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                bootstrap()
                return@launch
            }
            val ws = mgr.currentWorkspaceId ?: return@launch
            val sid = runCatching { mgr.newSession("chat").id }.getOrNull() ?: return@launch
            // Same stale-switch contract as openSession: creating the engine
            // session across the network must not let a slower write of a
            // PREVIOUS switch overwrite the view the user is actually on.
            if (epoch != sessionViewEpoch) {
                AppLog.w(TAG, "newThread dropped: the view moved on while the session was created")
                return@launch
            }
            wsId = ws
            sessionId = sid
            existingSessionWorkspace = ws
            // An empty-but-live session is a real viewing target: the first
            // turn can raise a permission prompt before there is any history
            // to replay, so report immediately.
            reportCurrentSession(sid)
            // A fresh chat must not inherit the previous session's file changes.
            // They describe what the agent did on the OTHER session, and showing
            // them here would claim edits this session never made.
            //
            // The snapshot is dropped too, not just the rendered diff: keeping it
            // would diff the new turn against files captured for the old one.
            workspaceSnapshot = null
            // Same lifecycle rule as openSession: a fresh thread must not
            // inherit a prompt addressed to the previous session's run.
            _state.update {
                it.copy(
                    messages = emptyList(),
                    isStreaming = false,
                    activeSessionId = sid,
                    fileChanges = null,
                    pendingPermission = null,
                    pendingQuestion = null
                )
            }
            loadSessions()
        }
    }

    /**
     * Deletes a session and repairs whatever local state pointed at it.
     *
     * Deleting the session the chat is currently bound to is the interesting
     * case: the cached session id would then address something the engine no
     * longer has, producing a 404 on the next send. So the binding is cleared
     * and a fresh thread started, rather than leaving a dangling id.
     */
    fun deleteSession(sessionId: String) {
        val mgr = workspaces ?: return
        val p = port ?: return
        viewModelScope.launch {
            // Clear any previous failure banner at the start of a new attempt.
            _state.update { it.copy(deleteError = null) }

            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                AppLog.w(TAG, "deleteSession: could not re-attach")
                _state.update {
                    it.copy(deleteError = "Could not reach the engine. Try again.")
                }
                return@launch
            }
            val ws = mgr.currentWorkspaceId
            if (ws == null) {
                _state.update {
                    it.copy(deleteError = "Could not reach the engine. Try again.")
                }
                return@launch
            }

            // Re-acquire with force=true on failure. A "fresh" lease can still
            // address a workspace the engine has already dropped, because the
            // lease is wall-clock based and the reap is attachment based.
            var outcome = crushApi(p).deleteSession(ws, sessionId)
            if (outcome is DeleteOutcome.Failed) {
                AppLog.w(TAG, "delete attempt 1 unknown: ${outcome.reason}")
                val back = runCatching { mgr.reattachIfStale(force = true) }
                    .onFailure { AppLog.w(TAG, "delete re-open failed: ${it.message}") }
                    .getOrDefault(false)
                val ws2 = mgr.currentWorkspaceId
                if (back && ws2 != null) {
                    outcome = crushApi(p).deleteSession(ws2, sessionId)
                    if (outcome !is DeleteOutcome.Failed) wsId = ws2
                }
            }

            when (outcome) {
                is DeleteOutcome.Failed -> {
                    // The session may well still exist. Say so, and DO NOT touch
                    // local state - clearing the bound session here is what
                    // produced a conversation that reappeared after the app had
                    // already told the user it was deleted.
                    AppLog.w(TAG, "deleteSession unknown outcome: ${outcome.reason}")
                    _state.update {
                        it.copy(deleteError = "Could not delete that chat. It may still exist.")
                    }
                    loadSessions()
                }
                DeleteOutcome.Deleted, DeleteOutcome.AlreadyGone -> {
                    // Confirmed gone, either by a successful delete or by a 404
                    // on a workspace we know is live.
                    AppLog.i(TAG, "deleteSession confirmed ($outcome) for $sessionId")
                    if (sessionId == this@ChatViewModel.sessionId ||
                        sessionId == _state.value.activeSessionId
                    ) {
                        this@ChatViewModel.sessionId = null
                        existingSessionWorkspace = null
                        // The deleted session's prompt dies with it (P1-7).
                        _state.update {
                            it.copy(
                                messages = emptyList(),
                                activeSessionId = null,
                                pendingPermission = null,
                                pendingQuestion = null
                            )
                        }
                        newThread()
                    }
                    loadSessions()
                }
            }
        }
    }

    /**
     * Answers a permission request the agent is blocked on.
     *
     * Only reachable in PermissionMode.ASK. In AUTO_APPROVE the engine never asks
     * and this is never called, which is exactly why ASK is the default.
     *
     * The whole request object is echoed back, not just its id - Crush matches on
     * tool_call_id, so sending only the id silently leaves the agent blocked.
     *
     * LIFECYCLE (analysis §4 P1-7): the dialog previously closed BEFORE the
     * grant POST, on failure paths re-attach/window included - so a grant that
     * never landed closed the dialog AND left the run blocked, invisible. It
     * now stays open until the grant POST itself resolves; only a successful
     * (2xx) grant clears [ChatUiState.pendingPermission]. grant()'s
     * PermissionGrantResponse.resolved flag is logged when false - some engine
     * versions return it false on grants they did accept - but a 2xx is the
     * confirmation available, so the dialog clears on it.
     */
    fun resolvePermission(allow: Boolean, allowForSession: Boolean = false) {
        val req = _state.value.pendingPermission ?: return
        val mgr = workspaces ?: return
        val p = port ?: return
        viewModelScope.launch {
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                AppLog.w(TAG, "grant aborted: could not re-attach; dialog kept open")
                return@launch
            }
            val ws = mgr.currentWorkspaceId ?: run {
                AppLog.w(TAG, "grant aborted: no workspace attached; dialog kept open")
                return@launch
            }
            val action = when {
                !allow -> PermissionGrant.DENY
                allowForSession -> PermissionGrant.ALLOW_SESSION
                else -> PermissionGrant.ALLOW
            }
            val granted = runCatching {
                crushApi(p).grant(ws, PermissionGrant(permission = req, action = action))
            }
            granted.onFailure {
                AppLog.w(TAG, "grant failed: ${it.message}; dialog kept open")
            }
            granted.getOrNull()?.let { resp ->
                if (!resp.resolved) {
                    AppLog.w(TAG, "grant 200 but engine reported resolved=false; " +
                        "request id ${req.id.take(12)}")
                }
            }
            // Cleared ONLY after the POST resolved: a failed grant keeps the
            // dialog open so the user can retry instead of a silently blocked
            // turn (P1-7).
            if (granted.isSuccess) {
                // SOAK DIAGNOSTIC (2026-11-01): the idle guard fired once with
                // a card standing; log this clear so the transition timeline
                // is first-hand next run.
                AppLog.w(TAG, "prompt CLOSED (grant ok; id=${req.id.take(8)}); pendingPermission cleared")
                _state.update { it.copy(pendingPermission = null) }
            }
        }
    }

    /** True when the agent is waiting on the user, so the UI can show a badge. */
    val awaitingPermission: Boolean get() = _state.value.pendingPermission != null

    /**
     * Releases an agent blocked on a question batch (analysis §4 P0-3).
     *
     * HONEST SCOPE: this build cannot answer with real option choices yet.
     * [CrushApi.answerQuestions] exists on the wire and is the intended
     * endpoint, but building QuestionAnswer.selected_ids requires an
     * option-choice UI this screen does not have - shipping guessed answer
     * bodies would tell the engine a user chose something they never saw.
     * What IS verified on the wire (and used by CrushStreamProbe to stop a
     * probe turn from hanging) is POST .../questions/cancel, which releases
     * the blocked run without an answer. Both dialog actions land here so the
     * turn visibly resumes instead of hanging on an invisible ask.
     */
    fun resolveQuestion() {
        val q = _state.value.pendingQuestion ?: return
        val mgr = workspaces ?: return
        val p = port ?: return
        viewModelScope.launch {
            // Mirror resolvePermission: the dialog stays open until the POST
            // resolves, so a failed release cannot silently strand the run.
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                AppLog.w(TAG, "question release aborted: could not re-attach; dialog kept open")
                return@launch
            }
            val ws = mgr.currentWorkspaceId ?: run {
                AppLog.w(TAG, "question release aborted: no workspace attached; dialog kept open")
                return@launch
            }
            val confirmed = runCatching { crushApi(p).cancelQuestions(ws) }
                .onFailure {
                    AppLog.w(TAG, "question cancel failed: ${it.message}; dialog kept open")
                }
                .isSuccess
            if (!confirmed) return@launch
            AppLog.i(
                TAG,
                "question batch ${q.id.take(12)} cancelled; agent released without an answer"
            )
            _state.update { it.copy(pendingQuestion = null) }
        }
    }

    fun setInput(v: String) {
        _state.update { it.copy(input = v) }
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.isStreaming) return
        // View epoch snapshot (2026-11-02): if the user switches chats while
        // the send's preflight (two network round-trips) is in flight, the
        // turn no longer belongs to the chat on screen - it is dropped BEFORE
        // any prompt is posted, the same policy the app already applies to a
        // permission prompt raised by the chat that was switched away from
        // (analysis §4 P1-7).
        val sendEpoch = sessionViewEpoch
        // Claim the send IMMEDIATELY. isStreaming was previously only set inside
        // streamSend, which runs after re-attach and newSession - two network
        // round-trips. The composable keeps the Send button enabled for that
        // whole window, so a double-tap started two runs; both wrote to the same
        // streamingId and the replies interleaved into one bubble.
        // Clear any stale stop notice from a previous run.
        _state.update { it.copy(isStreaming = true, stopNotice = null) }
        val p = port ?: run { _state.update { it.copy(isStreaming = false) }; return }
        val mgr = workspaces ?: run {
            _state.update { it.copy(isStreaming = false) }
            bootstrap()
            return
        }

        // The stop identity for this turn, created before any work starts so a
        // Stop can never race a half-initialised run (audit P1-05).
        val handle = RunHandle(turnId = UUID.randomUUID().toString())
        activeRun = handle
        // Give the container the in-flight signal while THIS instance runs
        // (the run-in-flight guard for the container-owned stop on switch).
        container.reportRunInFlight(instanceKey, true)

        // LAZY + record-then-start: a main-dispatcher launch{} runs its body
        // eagerly up to the first suspension, so a Stop landing during the
        // preflight would find a coroutine already suspended in a network call
        // with no job recorded to cancel. With LAZY the job exists on the
        // handle before any body runs.
        val preflight = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // THE fix for "HTTP 404 /v1/workspaces/... workspace not found".
            //
            // Crush reaps an unattached workspace after ~30s (MEASURED: alive at
            // 15s, gone at 30s). The id captured at bootstrap is therefore
            // normally dead before the user finishes typing, so re-attach first.
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                AppLog.w(TAG, "could not re-attach workspace; re-bootstrapping")
                workspaces = null
                wsId = null
                abandonRun(handle)
                _state.update { it.copy(isStreaming = false) }
                // A deferred provider switch (P1-07) applies here instead of
                // the old-provider bootstrap - not in addition to it.
                if (!applyPendingProviderIfIdle()) bootstrap()
                return@launch
            }
            val ws = mgr.currentWorkspaceId ?: run {
                abandonRun(handle)
                _state.update { it.copy(isStreaming = false) }
                applyPendingProviderIfIdle()
                return@launch
            }

            // RECOVERY BEFORE SEND - the re-attach above may have reported
            // success while its agent/init FAILED: the engine rejects init
            // with "large model provider not configured" whenever the model
            // slots still name a provider the CURRENT config does not know
            // (device 2026-10-27: yesterday's opencode-zen slots outlived the
            // provider in crush.json, and every send died at init with no
            // visible trace). Instead of letting the prompt fail, re-seat the
            // key + both model slots + coordinator right here, using the same
            // probe-verified model bootstrap chose.
            if (_state.value.selectedModel.isNotBlank()) {
                val precheck = runCatching { crushApi(p).agentInfo(ws) }.getOrNull()
                if (precheck?.isReady != true) {
                    AppLog.w(TAG, "send preflight: agent not ready, re-configuring")
                    val key = runCatching {
                        container.keySession.require(secureKeys, currentProviderId())
                    }.getOrNull()
                    val providerId = Providers.resolve(_state.value.providerId).id
                    if (key != null) {
                        val healed = runCatching {
                            CrushSession(crushApi(p), mgr).ensureAgentReady(
                                key,
                                providerId = providerId,
                                modelId = _state.value.selectedModel,
                                mainAgent = Providers.byId(providerId)?.mainAgent?.takeIf { it.isNotBlank() }
                            )
                        }.getOrNull()
                        if (healed?.isReady != true) {
                            AppLog.w(
                                TAG,
                                "send preflight: re-configure did not produce a ready " +
                                    "agent: ${healed?.notes?.distinct()?.take(2)?.joinToString("; ")}"
                            )
                        }
                    } else {
                        AppLog.w(TAG, "send preflight: no key in session; cannot re-configure")
                    }
                }
            }

            // REUSE the session so the conversation is a thread. Creating a
            // throwaway session per send was why there was never anything to
            // resume, list, or show in a history drawer - each turn was an
            // isolated conversation with its own auto-generated title.
            //
            // The guard used to be `existingSessionWorkspace == ws`, which looked
            // defensive but caused the exact bug it was meant to prevent: every
            // re-attach produces a NEW workspace id, so the comparison failed
            // after roughly 30s of inactivity and a new session was minted. That
            // is why the app appeared to hold only 2-3 messages - the 3rd landed
            // in a fresh chat, the 4th in another.
            //
            // The workspace is an ATTACHMENT HANDLE that comes and goes with the
            // reap; the session is persistent engine state. A session id we
            // already hold stays valid across a re-attach, so the only question
            // is whether we have one. If the server has genuinely lost it, the
            // send path detects "session not found" and starts a new one there.
            val existing = sessionId
            val sid = existing ?: runCatching { mgr.newSession("chat").id }.getOrNull()
            if (sid == null) {
                AppLog.w(TAG, "could not obtain a session; re-bootstrapping")
                abandonRun(handle)
                if (!applyPendingProviderIfIdle()) bootstrap()
                return@launch
            }

            // Stale-view drop (2026-11-02): nothing has been posted yet, and
            // everything below - binding writes, the current-session report
            // and the prompt POST itself - belongs to the session the user
            // was on at [send()] entry. If they switched while the re-attach
            // and (possibly) session creation ran, the honest move is to stop
            // here: the prompt was for a chat the user navigated away from.
            if (sendEpoch != sessionViewEpoch) {
                AppLog.w(TAG, "send dropped: the chat view switched while the prompt was being prepared; nothing was posted")
                abandonRun(handle)
                _state.update { it.copy(isStreaming = false) }
                return@launch
            }

            wsId = ws
            sessionId = sid
            existingSessionWorkspace = ws
            // Same scoping contract as openSession: the run about to start in
            // [sid] may hit a permission ask before the stream shows anything,
            // and the ask only finds this client if the report landed first.
            reportCurrentSession(sid)
            // Freeze the target on the handle BEFORE the stream can start, so a
            // Stop from this instant on cancels exactly this ws/session - not
            // whatever the view model points at when the tap arrives.
            handle.freezeTarget(ws = ws, sid = sid, port = p)
            val stream = viewModelScope.launch(start = CoroutineStart.LAZY) {
                streamSend(text, ws, sid, mgr, p, handle, sendEpoch)
            }
            handle.streamJob = stream
            stream.start()
        }
        handle.preflightJob = preflight
        preflight.start()
    }

    private suspend fun streamSend(
        text: String,
        ws: String,
        sid: String,
        mgr: WorkspaceManager,
        p: Int,
        handle: RunHandle,
        sendEpoch: Int
    ) {
        // FREEZE THE CAPTURE ROOT (S0/F02): everything this turn diffs -
        // before-image and after-image alike - belongs to the directory the
        // run's engine was bound to at start, not to whatever the Projects
        // surface has active when the turn ENDS.
        runFileRoot = workspaceRoot()
        // Review history (P4 v1): mark the fly flag and retire every older
        // card NOW, so the Review surface can refuse mid-run file surgery and
        // only ever offers the latest completed turn. The flag falls in this
        // turn's finally, after collectFileChanges saw the workspace settled.
        turnStartedAtMs = System.currentTimeMillis()
        container.reviewHistory.beginTurn()
        // Fresh id per turn, owned by the handle. ChatScreen keys its
        // LazyColumn by message id, and a constant sentinel produced
        // `IllegalArgumentException: Key "streaming" was already used` once
        // sessions were reused; the handle additionally guarantees that only
        // THIS turn can update this bubble.
        val bubbleId = "streaming-${UUID.randomUUID()}"
        handle.bubbleId = bubbleId
        _state.update { st ->
            // The transcript write is VIEW-SCOPED (2026-11-02): if the user
            // switched chats while the preflight ran, this turn's rows must
            // not be appended to the chat now on screen; isStreaming must
            // stay honest and the other chat's composer must keep its text.
            // The preflight epoch guard normally drops the turn before this
            // point - this is the second fastener for any future asynchrony
            // added above it.
            if (sendEpoch != sessionViewEpoch) {
                AppLog.w(TAG, "run rows withheld: the chat view moved on; turn continues for session ${sid.take(8)}")
                st
            } else st.copy(
                input = "",
                isStreaming = true,
                activeSessionId = sid,
                messages = st.messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "user",
                    text = text
                ) + ChatMessage(
                    id = bubbleId,
                    role = "assistant",
                    text = "",
                    isStreaming = true
                )
            )
        }

        // Tell the supervisor a long request is live so hang detection cannot
        // restart the engine underneath the stream.
        engineSupervisorFor.setWorkActive(true)

        try {
            val api = crushApi(p)
            var targetWs = ws
            var targetSid = sid
            var attempt = 0
            var failure: String? = null
            var accepted = false
            // A retried turn must not keep consuming the first attempt's
            // subscription, or it would read a run id that was never posted.
            var routerUsedHere = false

            // One retry, because a reaped workspace is routine rather than a
            // fault and the user's message must survive it. Two attempts max:
            // retrying more could duplicate a run that actually landed.
            //
            // mayPost() is re-checked per attempt: a turn stopped during
            // preflight must never reach the engine, and a stop that lands
            // between attempts must not retry (audit P1-05).
            while (attempt < 2 && !accepted && handle.mayPost()) {
                val runId = UUID.randomUUID().toString()
                // Subscribe BEFORE prompting, on the long-lived connection when
                // it is healthy.
                //
                // The engine dispatches a run detached, so an event can arrive
                // before the POST even returns. The old per-turn callbackFlow
                // only started reading on collection, i.e. AFTER the POST, which
                // lost early frames - a race that worsens the faster the engine
                // replies. Here registration happens first, so nothing is missed.
                //
                // Falls back to a dedicated per-turn stream if the shared
                // connection is not up: waiting on a socket that will never
                // arrive would hang the turn, and a degraded stream beats a
                // frozen app.
                val conn = eventConnection
                val useShared = conn != null &&
                    conn.state.value == CrushConnection.State.CONNECTED &&
                    !routerUsedHere
                var sharedChannel: kotlinx.coroutines.channels.Channel<CrushEvent>? = null
                val events: kotlinx.coroutines.flow.Flow<CrushEvent> = if (useShared) {
                    val ch = eventRouter.register(runId)
                    sharedChannel = ch
                    routerUsedHere = true
                    ch.receiveAsFlow()
                } else {
                    CrushEventStream(api, gson).events(targetWs, mgr.clientId)
                }

                // MUST NOT use launch{} here. A launch creates an independent
                // coroutine, so an exception thrown by api.send() escapes the
                // surrounding try/catch and kills the process. That is exactly
                // how a 404 closed the app with no visible error.
                val sent = withTimeoutOrNull(SEND_TIMEOUT_MS) {
                    // CAPTURE THE WORKSPACE JUST BEFORE THE PROMPT.
                    //
                    // The engine's filetracker names the files a session touched but
                    // carries no before-image, and the workspace is our own
                    // filesDir/workspace. So the before side has to be read now: this
                    // is the only moment the file exists in its pre-edit state.
                    //
                    // Snapshotting AFTER the turn would capture the after-image twice
                    // and every touched file would render as "+N -0", telling the user
                    // the agent rewrote a file when it changed one line. Clearing any
                    // stale rows first means a failed turn leaves no phantom diff.
                    workspaceSnapshot = collector.captureBefore(
                        runFileRoot ?: workspaceRoot(),
                        workspaceSnapshot
                    )
                    _state.update { it.copy(fileChanges = null) }
                    runCatching { api.send(targetWs, AgentMessageRequest(targetSid, text, runId)) }
                }
                val msg = when {
                    sent == null -> "send timed out"
                    sent.isFailure -> sent.exceptionOrNull()?.message.orEmpty()
                    else -> null
                }

                if (!handle.mayPost()) {
                    // Stop claimed this turn while the POST was in flight. The
                    // result no longer matters: the engine is being asked to
                    // cancel this run and this coroutine is cancelled.
                    // Interpreting the resulting cancellation exception (the
                    // runCatching around api.send() catches it like any other
                    // failure) as a send error would rename the user's own
                    // action into a model failure.
                    break
                }

                if (msg == null) {
                    accepted = true
                    failure = null
                    try {
                        awaitStream(events, runId, handle)
                    } finally {
                        // Release the subscription either way. Completing the run
                        // closes the channel; an abandoned one must be
                        // unregistered or it leaks for the process lifetime.
                        sharedChannel?.let { eventRouter.completeRun(runId) }
                    }
                } else {
                    failure = msg
                    // A failed attempt must not leave a subscription behind.
                    sharedChannel?.let { eventRouter.unregister(runId, it) }
                    val workspaceGone = msg.contains("workspace not found", ignoreCase = true)
                    if (workspaceGone && attempt == 0) {
                        AppLog.w(TAG, "workspace reaped mid-send; re-attaching and retrying once")
                        if (runCatching { mgr.reattachIfStale(force = true) }.getOrDefault(false)) {
                            val ws2 = mgr.currentWorkspaceId
                            if (ws2 != null) {
                                // KEEP THE SAME SESSION.
                                //
                                // This used to mint a new one, with the comment
                                // "a fresh session is required: the old one
                                // belonged to the reaped workspace". That premise
                                // is wrong and it silently destroyed conversation
                                // continuity: sessions are persistent engine state
                                // keyed by the workspace's cwd, while the workspace
                                // is only an attachment handle that comes and goes
                                // with the ~30s reap. So the old session id is still
                                // valid after re-attaching, and replacing it meant a
                                // user's 3rd message landed in a brand-new chat,
                                // then their 4th in another - which looked exactly
                                // like the app could only hold 2-3 messages.
                                //
                                // Only the WORKSPACE id changes here.
                                targetWs = ws2
                                wsId = ws2
                                existingSessionWorkspace = ws2
                                // Re-point the long-lived event connection at the
                                // fresh workspace (analysis §4 P1-6). This turn's
                                // retry subscribes with its own per-turn stream on
                                // the next loop pass (routerUsedHere is already
                                // true), so the re-point matters for every LATER
                                // turn: without it the shared socket stays attached
                                // to the workspace the engine just reaped and
                                // publishes nothing we are addressed by.
                                // eventConnection == null means no shared attach
                                // exists at all, in which case silently skipping
                                // is right - the per-turn fallback already governs
                                // this and later turns.
                                runCatching { eventConnection?.ensureAttached(ws2, mgr.clientId) }
                                    .onFailure {
                                        AppLog.w(
                                            TAG,
                                            "event re-attach after reap failed: ${it.message}"
                                        )
                                    }
                                attempt++
                                continue
                            }
                        }
                    }
                    // A 404 that names the SESSION (not the workspace) means the
                    // session itself is genuinely gone, which is the one case
                    // where starting over is correct.
                    if (msg.contains("session not found", ignoreCase = true) && attempt == 0) {
                        AppLog.w(TAG, "session gone; starting a new one")
                        val sid2 = runCatching { mgr.newSession("chat").id }.getOrNull()
                        if (sid2 != null) {
                            targetSid = sid2
                            sessionId = sid2
                            attempt++
                            continue
                        }
                    }
                    break
                }
            }

            val err = failure
            if (err != null && !handle.isStopped) {
                updateStreaming(handle) {
                    it.copy(
                        isStreaming = false,
                        error = WorkspaceLease.explainReattach(err).take(200)
                    )
                }
                if (err.contains("workspace not found", ignoreCase = true)) {
                    AppLog.w(TAG, "workspace gone; re-bootstrapping")
                    wsId = null
                    workspaces = null
                    sessionId = null
                    existingSessionWorkspace = null
                    keyBlocked = false
                    bootstrap()
                }
            }
        } catch (_: StopStream) {
            // expected: normal end of turn
        } catch (e: CancellationException) {
            // Stop, or the view model going away - not a failure. The finally
            // below finalises this turn's bubble; without this catch the
            // generic handler would paint the cancellation as a red error
            // ("JobCancellationException...") the moment the user pressed Stop.
            throw e
        } catch (e: Exception) {
            updateStreaming(handle) {
                it.copy(isStreaming = false, error = "${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            // Release the hang detector only when this turn was NOT stopped. A
            // stopped turn keeps workActive true until stopStreaming's server
            // cancel has actually completed (audit CANCEL-01); releasing it
            // here - the moment the local job unwinds - would let hang
            // detection judge the engine idle while it is still working on the
            // run we just asked it to stop.
            if (!handle.isStopped) engineSupervisorFor.setWorkActive(false)
            abandonRun(handle)
            // The turn is over in every sense: any permission or question this
            // run raised is addressed to a run that no longer exists, and once
            // the turn ends there is no one left to answer it. Clearing here
            // (P1-7) completes the prompt lifecycle; live prompts raised by a
            // STILL-RUNNING turn are re-raised by its own frames.
            _state.update {
                it.copy(
                    isStreaming = false,
                    pendingPermission = null,
                    pendingQuestion = null
                )
            }
            // Finalise the live bubble no matter how the turn ended.
            //
            // Only state.isStreaming used to be cleared here. The bubble's own
            // isStreaming flag was cleared by `run_complete` inside awaitStream, so
            // any turn that ended WITHOUT one - the prompt-wait ceiling, an SSE that
            // delivered nothing, an exception between the POST and the first frame -
            // left a permanent "thinking..." bubble.
            //
            // Worse, the UI-level flag was clear, so send() accepted the next
            // message: two turns then produced two bubbles streaming at once, which
            // is exactly what a slow free model reproduces. Finalising here means
            // the bubble is honest about having ended, and empty is shown as the
            // timeout it is rather than as a reply that never arrived.
            updateStreaming(handle) { m ->
                m.copy(
                    isStreaming = false,
                    // Keep whatever text arrived. An empty bubble is finalised as
                    // empty and marked, so the user is told the turn ended rather
                    // than left watching "thinking..." forever.
                    //
                    // A stopped turn is not a failed turn: "no reply received"
                    // would tell the user their model broke when they pressed
                    // Stop, so the marker is reserved for turns that ended on
                    // their own.
                    error = m.error
                        ?: if (m.text.isBlank() && !handle.isStopped) "no reply received" else null
                )
            }
            // Collect the file changes AFTER the turn, however it ended - success,
            // failure or Stop. Doing it in the success path only would mean a turn
            // that errored halfway left the user with no record of what the agent had
            // already written, which is exactly when they most want to see it.
            //
            // Runtime, not persisted: this is what the agent did on THIS turn, so a
            // new session starts empty rather than inheriting a stale diff.
            collectFileChanges()
            // The Review surface's fly flag is honest about the whole turn:
            // raised at send, lowered only now, after collect saw the
            // workspace settle. Revert becomes available with the card in
            // the same instant.
            container.reviewHistory.runSettled()
            // The capture root belongs to THIS run alone; drop it so the next
            // send re-freezes against the instance it starts on.
            runFileRoot = null

            // A provider switch that waited for this turn (P1-07) applies now:
            // the turn's events are done being consumed, so rebuilding the
            // workspace underneath it can no longer kill the stream mid-reply or
            // lose the measurement above. The run has already been abandoned,
            // so setProvider's own "is a turn in flight" check passes.
            applyPendingProviderIfIdle()
        }
    }

    /**
     * The diff-capture root for the BOUND instance (S0/F02): the ACTIVE
     * project's repo directory, or the legacy workspace when no project is
     * bound. This is the same source [workspacePathFor] feeds the engine cwd
     * from, so what the engine edits and what we snapshot ALWAYS meet in one
     * directory. The shipped version returned the legacy workspace
     * unconditionally, so an imported-folder turn was captured against the
     * wrong tree and every engine-made file rendered as
     * "(unreadable, binary, or too large)" while the true folder held them.
     */
    private fun workspaceRoot(): File =
        container.activeProjectRef?.repoDir ?: EnginePaths(context).workspace

    /**
     * Run-frozen capture root (S0/F02 acceptance): resolved ONCE at
     * [streamSend] entry. captureBefore and collectFileChanges must measure
     * THE SAME root even if the user navigates between projects while a turn
     * is in flight - the engine's tracked-file list belongs to the cwd the
     * run started on. Cleared in the turn's finally.
     */
    @Volatile
    private var runFileRoot: File? = null

    /**
     * When the CURRENT turn's prompt was sent - the Review card's label and
     * the name of the window its revert covers. Owned by the same lifecycle
     * as [runFileRoot]: set at streamSend entry, read at collect time.
     */
    @Volatile
    private var turnStartedAtMs: Long = 0L

    /**
     * Reads the engine's tracked-file list and diffs it against the pre-turn
     * snapshot.
     *
     * Every failure path leaves [ChatUiState.fileChanges] NULL rather than
     * fabricating a diff, because a one-sided read would claim the agent wrote
     * whole files. It also means the UI cannot distinguish "no changes" from "not
     * measured" - which is why the UI says "not collected" instead of "no files
     * changed" whenever this is null.
     */
    private suspend fun collectFileChanges() {
        val snap = workspaceSnapshot ?: run {
            AppLog.w(TAG, "no pre-turn snapshot; file changes not measured")
            _state.update { it.copy(fileChanges = null) }
            return
        }
        val ws = wsId ?: run {
            _state.update { it.copy(fileChanges = null) }
            return
        }
        val sid = sessionId ?: run {
            _state.update { it.copy(fileChanges = null) }
            return
        }

        val result = runCatching {
            val api = crushApi(port ?: return@runCatching null)
            val tracked = api.trackedFiles(ws, sid)
            collector.collectFull(runFileRoot ?: workspaceRoot(), tracked, snap)
        }.getOrNull()

        // The rail keeps its semantics: null is "not measured", an empty set
        // is "measured, nothing diffable".
        val set = result?.changes?.copy(sessionId = sid)
        _state.update { it.copy(fileChanges = set) }
        if (set == null) {
            AppLog.w(TAG, "file-change collection failed; not reporting changes")
        } else {
            AppLog.i(TAG, "file changes: ${set.stat()}, ${set.unreadable.size} unreadable")
        }

        // Retain the turn for the Review surface (P4 v1). BEFORE re-reading
        // would give the card images a second, later instant; both maps are
        // keyed per rendered diff path and the card copies them now, ahead of
        // the NEXT capture that replaces the workspaceSnapshot slot.
        if (result != null && set != null) {
            val root = runFileRoot ?: workspaceRoot()
            container.reviewHistory.recordTurn(
                sessionId = sid,
                startedAtMs = turnStartedAtMs,
                rootPath = root.absolutePath,
                changes = set,
                beforeTexts = result.changes.diffs.associate { it.path to snap.before(it.path) },
                afterTexts = result.afterTexts
            )
        }
    }

    /**
     * Collects the SSE stream until this run completes.
     *
     * `run_complete` also carries the final text, which is used as a fallback:
     * replayed message frames can arrive before the text part lands, and an
     * empty assistant bubble reads as "no reply" rather than "still loading".
     *
     * TIMEOUTS (analysis §4 P0-2) - layered, all ending in a SERVER-SIDE
     * cancel:
     *
     *  - STREAM_IDLE_MS per-frame idle: the previously single
     *    withTimeoutOrNull(collect) treated the whole turn as one winner-takes-
     *    all gap, so a tool call that thought for 20 minutes (or a model that
     *    streams one token every slow minute) died at exactly 180s even though
     *    the agent was demonstrably alive. Frames now reset the idle clock via
     *    a per-receive race, so only a genuinely SILENT agent expires early.
     *  - PROMPT_WAIT_MS per parked prompt: while an ask/question dialog
     *    stands, the engine is legitimately silent - it parks ON the user's
     *    decision - so the idle race is suspended there (device 2026-10-29:
     *    the z4 ask was cancelled by its own 20s idle race exactly 20s after
     *    the dialog opened, with the turn healthy). The STREAM_TIMEOUT_MS
     *    TOTAL cap was then only able to kill the other healthy cases: a long
     *    multi-tool turn streamed past 180s, and a user reading the ask
     *    parked past it (both proven live, z6/z7); with real silence covered
     *    by idle and parked silence covered by PROMPT_WAIT_MS, the total cap
     *    had no job left and is removed. A never-answered dialog still ends
     *    the turn via the prompt ceiling - awaited server cancel first.
     *
     * Expiry is not the old silent give-up: ask the engine to cancel the run
     * first and AWAIT that POST (via the handle's frozen target, exactly like
     * StopAction.CancelOnServer in stopStreaming), and only then return.
     * streamSend's finally releases workActive, so awaiting the cancel here is
     * what keeps the hang detector honest until the engine acknowledged it
     * (CANCEL-01). On StopStream and CancellationException nothing changed.
     */
    private suspend fun awaitStream(
        events: kotlinx.coroutines.flow.Flow<CrushEvent>,
        runId: String,
        handle: RunHandle
    ) {
        coroutineScope {
                // The two event sources have different shapes: the shared
                // router hands over a Channel, the per-turn fallback is a
                // callbackFlow. Funneling both into ONE local channel gives the
                // frame loop a plain receiveCatching() that CAN be raced
                // against the idle deadline - receiveAsFlow() itself has no
                // "wait N seconds for the next frame" primitive, and
                // transformWhile-style tricks drop the frame raced on.
                val frames = Channel<CrushEvent>(Channel.BUFFERED)
                // Catch-all, not rethrow: when the consumer exits (stop, idled
                // out, thrown), the still-collecting feeder sees a closed or
                // cancelled channel and that is NORMAL teardown, not an error
                // to paint. The finally closes the channel so the feeder can
                // never block forever.
                val feeder = launch {
                    try {
                        events.collect { frames.send(it) }
                    } catch (e: Exception) {
                        // Teardown vs failure are distinguished: a cancelled or
                        // already-closed channel is normal shutdown (stop,
                        // consumer exit), but the flow itself failing (e.g. the
                        // SSE socket dropped mid-turn) must stay an HONEST
                        // error. Closing the channel with the cause delivers it
                        // to the consumer, which rethrows it so it is painted
                        // on the bubble exactly as the old events.collect
                        // propagated it.
                        if (!frames.isClosedForSend) frames.close(e)
                    } finally {
                        frames.close()
                    }
                }
                // While an ask/question dialog stands open the engine is
                // PARKED, not silent, so the 20s idle race must not fire
                // (z4, 2026-10-29). Parked receives race the remaining
                // PROMPT_WAIT ceiling instead, so a prompt nobody answers
                // still ends the turn with the same awaited server cancel
                // the other expiries use; answering the dialog (or any
                // stray frame) hands control back to the frame loop's
                // normal idle regime.
                suspend fun receiveWhilePromptOpen(): kotlinx.coroutines.channels.ChannelResult<CrushEvent> {
                    // SOAK DIAGNOSTIC (2026-11-01): the backgrounding soak
                    // measured the idle branch firing while a card should
                    // have held this loop OPEN. Log entry/exit here so the
                    // next run states the transition timeline directly.
                    AppLog.w(TAG, "prompt-wait: entering (run ${handle.turnId})")
                    var promptStartMs = 0L
                    while (true) {
                        val nowMs = System.currentTimeMillis()
                        if (promptStartMs == 0L) promptStartMs = nowMs
                        val remaining = PROMPT_WAIT_MS - (nowMs - promptStartMs)
                        if (remaining <= 0L) {
                            if (!handle.isStopped) {
                                AppLog.w(
                                    TAG,
                                    "prompt unanswered > ${PROMPT_WAIT_MS / 60000}min " +
                                        "for run ${handle.turnId}; requesting engine cancel"
                                )
                                awaitServerCancel(handle)
                            }
                            throw StopStream()
                        }
                        val waiting = withTimeoutOrNull(remaining) { frames.receiveCatching() }
                        if (waiting != null) {
                            AppLog.w(TAG, "prompt-wait: frame received; loop resumes (run ${handle.turnId})")
                            return waiting
                        }
                    }
                }
                try {
                    var foreignRunCompletes = 0
                    while (true) {
                        // A pending permission/question is NOT engine silence:
                        // in ASK mode the run parks ON the user's decision, so
                        // racing the idle deadline cancels a healthy turn
                        // exactly 20s after the dialog opens - the z4 probe
                        // died this way on 2026-10-29 ("stream idle > 20s" 20s
                        // after "permission requested") while its own dialog
                        // stood open. Parked prompts wait in
                        // [receiveWhilePromptOpen], which races the prompt
                        // ceiling instead; the frame loop relies on the
                        // per-frame idle race for everything else.
                        val waitingOnPrompt =
                            _state.value.pendingPermission != null ||
                                _state.value.pendingQuestion != null
                        val got =
                            if (waitingOnPrompt) receiveWhilePromptOpen()
                            else withTimeoutOrNull(STREAM_IDLE_MS) { frames.receiveCatching() }
                        if (got == null) {
                            // Idle deadline expired: the engine went silent on
                            // this run. A stopped turn already handles its own
                            // cancel; anything else gets the same
                            // cancel-then-end treatment as the total deadline.
                            if (!handle.isStopped) {
                                AppLog.w(
                                    TAG,
                                    "stream idle > ${STREAM_IDLE_MS / 1000}s for run " +
                                        "${handle.turnId}; requesting engine cancel"
                                )
                                awaitServerCancel(handle)
                            }
                            throw StopStream()
                        }
                        // Source flow ENDED without a run_complete (per-turn
                        // stream closed): a quiet end of turn exactly like the
                        // old events.collect returning, UNLESS the flow FAILED
                        // - in which case the close carried the failure and it
                        // is rethrown so the bubble still tells the truth.
                        val failure = got.exceptionOrNull()
                        val ev = got.getOrNull()
                        if (ev == null) {
                            if (failure != null) throw failure
                            break
                        }
                        when (ev.type) {
                            CrushEvent.PayloadType.MESSAGE -> {
                                val m = gson.fromJson(ev.body, StreamMessage::class.java)
                                if (m.role == "assistant") {
                                    // Crush republishes the WHOLE message, so replace.
                                    // Tools are replaced with it, never appended: each
                                    // frame carries the full set, and appending would
                                    // stack a duplicate row every time a tool advanced.
                                    val tools = m.toolCalls.mapNotNull(ToolActivityParser::from)
                                    updateStreaming(handle) {
                                        it.copy(
                                            text = m.text.ifBlank { it.text },
                                            thinking = m.thinking.ifBlank { it.thinking },
                                            tools = tools.ifEmpty { it.tools },
                                            error = m.finishError ?: it.error
                                        )
                                    }
                                }
                            }

                            // In ASK mode the agent BLOCKS here until answered, so the
                            // request must reach the UI or the turn hangs silently. In
                            // AUTO_APPROVE the engine never emits this.
                            CrushEvent.PayloadType.PERMISSION_REQUEST -> {
                                val req = gson.fromJson(ev.body, PermissionRequest::class.java)
                                if (req.id.isNotBlank()) {
                                    if (instanceKey == EnginePaths.CHAT_INSTANCE_KEY) {
                                        // S4 DEFENSE LAYER (Q2): Chat is
                                        // conversation-only. The spawn-time
                                        // disabled_tools list hides the built-ins
                                        // (S1-verified), so a grant request here can
                                        // only come from a surface that list cannot
                                        // see (e.g. an MCP server name). Auto-DENY -
                                        // we NEVER park an approval dialog on a
                                        // chat session - and say WHY in the
                                        // transcript: the take-this-to-Code
                                        // affordance the user asked for at
                                        // decision time.
                                        AppLog.i(
                                            TAG,
                                            "chat deny-all: ${req.toolName} auto-denied on the pool"
                                        )
                                        _state.update { st ->
                                            st.copy(
                                                pendingPermission = req,
                                                messages = st.messages + ChatMessage(
                                                    id = UUID.randomUUID().toString(),
                                                    role = "assistant",
                                                    text = "Chat is conversation-only, so \"" +
                                                        req.toolName + "\" was blocked. " +
                                                        "Open a Code project and run this there."
                                                )
                                            )
                                        }
                                        resolvePermission(allow = false)
                                    } else {
                                        AppLog.i(
                                            TAG,
                                            "permission requested: ${req.toolName} (id=${req.id.take(8)}); pendingPermission SET for run $runId"
                                        )
                                        _state.update { it.copy(pendingPermission = req) }
                                    }
                                } else {
                                    AppLog.w(TAG, "permission request with BLANK id dropped; pendingPermission NOT set")
                                }
                            }

                            CrushEvent.PayloadType.QUESTION_REQUEST -> {
                                // The agent is blocked on an ask-the-user question
                                // batch (analysis §4 P0-3). The option vocabulary is
                                // engine-versioned, so the batch is kept RAW: the
                                // dialog renders its title and one release path -
                                // see [resolveQuestion] - until a real option-choice
                                // UI exists. Never swapped out: like permissions, a
                                // question must reach this client or the run blocks
                                // invisibly until the idle timeout cancels it.
                                val q = PendingQuestion(
                                    id = ev.body.firstText(QUESTION_ID_KEYS).orEmpty(),
                                    raw = ev.body
                                )
                                AppLog.i(TAG, "question batch requested (raw ${ev.body})")
                                _state.update { it.copy(pendingQuestion = q) }
                            }

                            CrushEvent.PayloadType.RUN_COMPLETE -> {
                                val rc = gson.fromJson(ev.body, RunComplete::class.java)
                                if (rc.runId == runId) {
                                    // STRICT MATCH (analysis §4 P2): the old
                                    // `|| rc.runId.isEmpty()` condition let an
                                    // empty/foreign run_complete finalise THIS
                                    // turn, but bubble ids are always minted as
                                    // UUIDs, so an empty id can only belong to a
                                    // run this client never posted. Count them
                                    // (bounded, not spammed) and keep waiting.
                                    // A finished run also implies its tools
                                    // finished (§4 P2 live rows): flip only
                                    // THIS bubble's RUNNING rows to COMPLETED,
                                    // never on timeout/stop, where the run did
                                    // not complete.
                                    updateStreaming(handle) {
                                        it.copy(
                                            isStreaming = false,
                                            text = rc.text.ifBlank { it.text },
                                            tools = it.tools.map { t ->
                                                if (t.isRunning) {
                                                    t.copy(state = ToolState.COMPLETED)
                                                } else {
                                                    t
                                                }
                                            }
                                        )
                                    }
                                    throw StopStream()
                                } else {
                                    foreignRunCompletes++
                                    if (foreignRunCompletes <= 3) {
                                        AppLog.w(
                                            TAG,
                                            "run_complete ignored: runId='${rc.runId}' " +
                                                "while waiting for '$runId' (turn ${handle.turnId}, " +
                                                "foreign #$foreignRunCompletes)"
                                        )
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }
                } finally {
                    frames.close()
                    feeder.cancel()
                }
        }
    }

    /**
     * Asks the engine to cancel this turn's run and waits for the POST.
     *
     * Target comes from the handle's frozen snapshot (same rule as
     * stopStreaming), or does nothing at all when no target was frozen. The
     * isStopped re-check narrows (eliminates on the happy path) the race where
     * an expiry-driven cancel would fire at a NEWER run on the same session -
     * the documented reason StopAction exists at all. Cancel POSTs are
     * idempotent enough per RunHandle's contract, so an unlucky interleave is
     * an accepted cost rather than a redesign.
     */
    private suspend fun awaitServerCancel(handle: RunHandle) {
        val p = handle.port ?: return
        val w = handle.ws ?: return
        val s = handle.sid ?: return
        if (handle.isStopped) return
        runCatching { crushApi(p).cancel(w, s) }
            .onFailure { AppLog.w(TAG, "expiry cancel not confirmed for $s: ${it.message}") }
    }

    /**
     * Updates the live assistant bubble owned by [handle]'s turn.
     *
     * The bubble id lives on the handle, so a turn can only ever touch its own
     * row. The previous single mutable `streamingId` meant the sole way to
     * identify "the" in-flight bubble was global mutable state, which is how a
     * stop finishing late could finalise a bubble it never owned (audit P1-05).
     */
    private fun updateStreaming(handle: RunHandle, transform: (ChatMessage) -> ChatMessage) {
        val target = handle.bubbleId
        _state.update { st ->
            st.copy(
                messages = st.messages.map { m ->
                    if (target != null && m.id == target) transform(m) else m
                }
            )
        }
    }

    /** Stop button: cancels the stream without tearing the engine down. */
    /**
     * Stops the in-flight run.
     *
     * CANCELLATION IS SERVER-SIDE FIRST (audit CANCEL-01).
     *
     * This previously cancelled only the local collection job. The engine kept
     * generating tokens, could keep executing tools, and kept mutating the
     * persisted session - so the UI said "stopped" while the agent was still
     * working. It also cleared workActive immediately, which let the hang
     * detector judge the engine idle and restart it mid-run.
     *
     * Now: ask the server to cancel, and only release workActive once that
     * request has actually completed. If the cancel cannot be confirmed we
     * still stop the UI, but say "stop requested" rather than claiming the
     * agent has stopped.
     *
     * The cancel addresses this turn's frozen target from its RunHandle, and a
     * stop during preflight suppresses the POST instead of firing a cancel at
     * a run that does not exist yet (audit P1-05).
     */
    fun stopStreaming() {
        // Everything Stop needs comes from the handle for THIS turn (audit
        // P1-05): the jobs to cancel, and the exact ws/session/port to tell the
        // engine about. Reading the view model's fields here is what made Stop
        // cancel the wrong run during preflight.
        val handle = activeRun ?: return

        when (val action = handle.stop()) {
            StopAction.AlreadyStopped -> return

            StopAction.NothingPosted -> {
                // Preflight window: nothing was posted, no bubble exists, and
                // mayPost() keeps it that way. Local cancellation is complete.
                abandonRun(handle)
                engineSupervisorFor.setWorkActive(false)
                // A prompt raised during preflight belongs to a run that was
                // never posted and can never be answered; retire it (P1-7).
                _state.update {
                    it.copy(pendingPermission = null, pendingQuestion = null)
                }
                finalizeStoppedRun(handle, requestConfirmed = true)
            }

            is StopAction.CancelOnServer -> {
                abandonRun(handle)
                // The answer to this prompt can no longer be consumed by the
                // stream being stopped here; retire it now (P1-7) rather than
                // leaving a dialog pointed at a run about to die. The cancel
                // MAY fail, which is a stop-confirmation problem - it does not
                // make the prompt answerable again.
                _state.update {
                    it.copy(pendingPermission = null, pendingQuestion = null)
                }
                // Keep workActive true until the server acknowledges. A
                // transient failure here must not be reported to the user as
                // "stopped".
                viewModelScope.launch {
                    val confirmed = runCatching {
                        crushApi(action.port).cancel(action.ws, action.sid)
                    }.isSuccess
                    if (!confirmed) {
                        AppLog.w(TAG, "stop: server cancel not confirmed for ${action.sid}")
                    }
                    engineSupervisorFor.setWorkActive(false)
                    finalizeStoppedRun(handle, requestConfirmed = confirmed)
                }
            }
        }
    }

    private fun finalizeStoppedRun(handle: RunHandle, requestConfirmed: Boolean) {
        val bubble = handle.bubbleId
        _state.update { st ->
            st.copy(
                isStreaming = false,
                // Only the bubble THIS run owned. The old finaliser cleared the
                // streaming flag on every streaming row, so a stop finishing late
                // could flip a newer turn's bubble to "done" mid-stream.
                messages = st.messages.map { m ->
                    if (bubble != null && m.id == bubble && m.isStreaming) {
                        m.copy(isStreaming = false)
                    } else {
                        m
                    }
                },
                // Do not assert the agent has stopped when we could not tell the
                // server to. The user is told the truth either way.
                stopNotice = if (requestConfirmed) {
                    null
                } else {
                    "Stop requested. The agent may still be finishing."
                }
            )
        }
    }

    private fun updateAssistant(id: String, transform: (ChatMessage) -> ChatMessage) {
        _state.update { st ->
            st.copy(messages = st.messages.map { m -> if (m.id == id) transform(m) else m })
        }
    }

    private class StopStream : RuntimeException(null, null, false, false)

    class Factory(
        private val context: Context,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(context.applicationContext, container) as T
    }

    private companion object {
        const val TAG = "ChatViewModel"
        const val SEND_TIMEOUT_MS = 30_000L

        /**
         * Per-frame idle deadline (analysis §4 P0-2). A single
         * withTimeoutOrNull(collect) around the whole collect treated ANY hung
         * moment as the whole budget: a turn that streamed steadily died at
         * exactly 180s, and a silent-but-alive agent wasted the window. Idle
         * resets on every received frame, so only a genuinely SILENT agent
         * expires early. 20s is under the workspace's ~30s reap window and
         * comfortably above a normal frame gap.
         */
        const val STREAM_IDLE_MS = 20_000L

        /**
         * Ceiling for ONE parked prompt - the ask/question dialog the user is
         * still reading. Replaces the old STREAM_TIMEOUT_MS total (180s),
         * which device evidence (z4/z6/z7, 2026-10-29) showed can only ever
         * kill the healthy cases: parked-past-it ask reading, and a fast-
         * but-long multi-tool turn. Real silence is the idle deadline's job;
         * parked silence is this ceiling's job, so the total cap is gone. 10
         * minutes is a product default, recorded as decision item 8 in
         * docs/decisions-open.md.
         */
        const val PROMPT_WAIT_MS = 600_000L
    }
}

