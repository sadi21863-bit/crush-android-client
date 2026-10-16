package com.opencode.chat.ui.screens.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.opencode.chat.data.api.LiveZenModel
import com.opencode.chat.data.api.ZenModelsApi
import com.opencode.chat.data.crush.AgentMessageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import com.opencode.chat.data.crush.CreateSessionRequest
import com.opencode.chat.data.crush.DeleteOutcome
import com.opencode.chat.data.crush.CrushApi
import com.opencode.chat.data.crush.CrushConnection
import com.opencode.chat.data.crush.CrushEvent
import com.opencode.chat.data.crush.CrushEventRouter
import com.opencode.chat.data.crush.CrushEventStream
import com.opencode.chat.data.crush.CrushSession
import com.opencode.chat.data.crush.PermissionGrant
import com.opencode.chat.data.crush.PermissionRequest
import com.opencode.chat.data.crush.RunComplete
import com.opencode.chat.data.crush.SelectedModel
import com.opencode.chat.data.crush.Session
import com.opencode.chat.domain.file.FileChangeCollector
import com.opencode.chat.domain.provider.Provider
import com.opencode.chat.domain.provider.Providers
import com.opencode.chat.domain.file.FileChangeSet
import com.opencode.chat.domain.file.WorkspaceSnapshotter
import com.opencode.chat.data.crush.SetModelRequest
import com.opencode.chat.data.crush.StreamMessage
import com.opencode.chat.data.crush.WorkspaceLease
import com.opencode.chat.data.crush.WorkspaceManager
import com.opencode.chat.data.local.SecureKeyStore
import com.opencode.chat.di.AppContainer
import com.opencode.chat.domain.model.TuningPolicy
import com.opencode.chat.engine.EnginePaths
import com.opencode.chat.engine.EngineState
import com.opencode.chat.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

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

data class ChatUiState(
    val phase: String = "starting engine",
    val engineReady: Boolean = false,
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
class ChatViewModel(private val context: Context, private val container: AppContainer) :
    ViewModel() {

    private val gson = Gson()
    private val secureKeys = SecureKeyStore(context)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var wsId: String? = null
    private var sessionId: String? = null

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
    private var connectionPort: Int? = null
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
                    val resolved = Providers.resolve(persisted).id
                    if (resolved == _state.value.providerId) return@collect
                    if (wsId == null && !engineReadyNow()) return@collect
                    AppLog.i(TAG, "provider changed to $resolved; re-bootstrapping")
                    setProvider(resolved)
                }
        }
    }

    /** True only when a workspace is live, so an early emission is ignored. */
    private fun engineReadyNow(): Boolean = _state.value.engineReady && wsId != null

    fun bindEngine(engineState: EngineState) {
        val p = (engineState as? EngineState.Running)?.port
        if (p == null) {
            _state.value = _state.value.copy(
                phase = when (engineState) {
                    is EngineState.Failed -> "engine failed: ${engineState.reason}"
                    is EngineState.Starting -> "starting engine (attempt ${engineState.attempt})"
                    else -> "engine stopped"
                },
                engineReady = false
            )
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
                _state.value = _state.value.copy(
                    phase = "Could not start the agent. Retrying...",
                    engineReady = false
                )
            } finally {
                bootstrapping = false
            }
        }
    }

    private suspend fun doBootstrap() {
        _state.value = _state.value.copy(phase = "connecting to agent", engineReady = false)
        val p = port ?: return
        val api = CrushApi(p)
        val mgr = WorkspaceManager(api)
        val session = CrushSession(api, mgr)
        val d = File(context.filesDir, "").absolutePath

        // WHICH PROVIDER, resolved once for the whole handshake.
        //
        // Read from PERSISTED settings, not from in-memory state, because Settings
        // is a different screen with its own ViewModel and the user can change the
        // provider there while this one still holds the old value.
        //
        // Seven call sites used to pass the literal "opencode-zen". Resolving it
        // here means the key, the model slots, the price lookup and the agent init
        // can never disagree about which provider they are configuring - which is
        // the failure mode when a provider is changed in only some of them: Zen
        // prices read for one provider while the model is set on another, and
        // nothing complains.
        val providerId = Providers.resolve(currentPersistedProvider()).id

        // Read the persisted permission mode rather than assuming. Getting this
        // wrong in the AUTO_APPROVE direction silently grants the agent
        // unattended file edits, so it fails safe to ASK on any error.
        val permissionMode = runCatching {
            container.settingsRepository.settings.first().permissionMode
        }.getOrDefault(com.opencode.chat.domain.model.PermissionMode.ASK)
        AppLog.i(TAG, "permissionMode=$permissionMode")

        val opened = session.openWorkspace("$d/workspace", "$d/crush/state", permissionMode, providerId)
        // Read the key for the provider we are about to configure, not for whatever
        // was cached last.
        val key = container.keySession.require(secureKeys, providerId)
        if (key.isBlank()) {
            // The old copy here was a lie. read() returns null for BOTH "the
            // user never gave us a key" and "the key is fine, the auth window
            // lapsed", so telling the user to add a key they had already added
            // sent them to Settings for nothing. Now that the app locks at
            // entry, a blank key here almost always means we are locked.
            keyBlocked = secureKeys.hasStoredCiphertext(providerId)
            _state.value = _state.value.copy(
                phase = if (keyBlocked) {
                    "unlocking your saved key..."
                } else {
                    "no API key - add one in Settings"
                },
                engineReady = false
            )
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
        var ready = session.ensureAgentReady(
            key,
            providerId = providerId,
            modelId = preferred ?: FALLBACK_PROBE_MODEL
        )
        if (!ready.isReady) {
            AppLog.w(TAG, "early init: ${ready.notes.firstOrNull()}")
        }

        // STEP 2 - discover a model that is actually callable.
        val zen = ZenModelsApi({ key })

        // Load REAL prices before parsing the catalogue.
        //
        // Without this the shortlist had no free-first ordering and fell back to
        // raw catalogue order, which begins with paid Claude models. Measured on
        // device: the probe gave up after 8 (probed=8 denied=8) while the one
        // model the key CAN call, space-bunny-free, sits at position 78 of 86.
        // So the fix is not a bigger probe budget - it is knowing which models
        // are free, which is exactly what this supplies.
        val prices = runCatching {
            ZenModelsApi.pricesFromProviders(
                api.providers(opened.workspaceId),
                providerId
            )
        }.getOrDefault(emptyMap())
        if (prices.isNotEmpty()) {
            zen.applyPricing(prices)
        }
        AppLog.i(TAG, "catalogue prices loaded for ${prices.size} models")

        val catalogue = runCatching { zen.fetch() }.getOrNull()
        val models = catalogue?.models.orEmpty()
        if (models.isEmpty()) {
            AppLog.w(TAG, "no usable model: ${catalogue?.error}")
            _state.value = _state.value.copy(
                phase = ModelChoice.explainFetchFailure(catalogue?.error),
                models = models,
                modelsError = catalogue?.error,
                engineReady = false
            )
            return
        }

        // PROVE the model works before configuring it. Being listed is not
        // enough: Zen's /models is public and still advertises retired ids, so a
        // listed-but-dead model produced "Model is unavailable" on every turn
        // while the app reported a healthy catalogue. Probe with a 1-token
        // request and take the first id that actually answers.
        val shortlist = ModelChoice.shortlist(models)
        val result = zen.firstWorking(shortlist)
        val chosen = result.model
        if (chosen == null) {
            AppLog.w(
                TAG,
                "no usable model: probed=${result.probed} retired=${result.retired} " +
                    "denied=${result.denied} badKey=${result.badKey}"
            )
            _state.value = _state.value.copy(
                phase = ModelChoice.explainNoUsableModel(models.size, result),
                models = models,
                engineReady = false
            )
            return
        }
        AppLog.i(
            TAG,
            "probed ${result.probed} candidate(s), using $chosen (of ${models.size} listed)"
        )

        // STEP 3 - the workspace may have expired during the probe, so apply the
        // probed model and treat a 404 as "re-open", not as a fatal error.
        var wsNow = opened.workspaceId
        var init = runCatching {
            session.ensureAgentReady(key, providerId = providerId, modelId = chosen)
        }.getOrElse {
            AppLog.w(TAG, "init with probed model threw: ${it.message}")
            session.ensureAgentReady(key, providerId = providerId, modelId = chosen)
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
                    modelId = chosen
                )
                if (init.isReady) AppLog.i(TAG, "recovered on workspace $freshWs")
            }
        }

        if (!init.isReady) {
            // Trim the note list: it holds one string per failed call and the
            // same cause repeats, burying the single useful fact.
            val summary = init.notes.distinct().take(2).joinToString("; ")
            _state.value = _state.value.copy(
                phase = "agent not ready: $summary",
                engineReady = false
            )
            return
        }
        ready = init
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

        _state.value = _state.value.copy(
            phase = "ready",
            engineReady = true,
            selectedModel = chosen,
            models = models
        )
        loadModels()
        // A newly opened session has no measured diff. Null rather than an empty
        // list, so the UI reports "not collected" instead of "no files changed".
        workspaceSnapshot = null
        _state.value = _state.value.copy(fileChanges = null)
        // Attach the long-lived stream LAST, once the agent is configured and
        // the session is settled. Attaching earlier means a workspace that dies
        // during the slow model probe leaves a socket pointed at nothing.
        attachEventStream(freshWsOrCurrent(), mgr.clientId)
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
        if (ws.isNullOrBlank()) return
        val p = port ?: return
        val conn = eventConnection
        if (conn != null && p == connectionPort) {
            conn.ensureAttached(ws, clientId)
            return
        }
        // Port changed means the engine restarted; the old connection is dead.
        runCatching { conn?.stop() }
        runCatching { eventRouter.close() }
        val router = CrushEventRouter()
        val fresh = CrushConnection(p, router, gson, CrushApi(p).streamClient)
        // Assign before attaching so a send racing bootstrap sees a consistent
        // (router, connection) pair.
        eventRouter = router
        eventConnection = fresh
        connectionPort = p
        fresh.ensureAttached(ws, clientId)
        AppLog.i(TAG, "long-lived event stream attached to $ws")
    }

    /** The workspace we should be attached to right now, preferring the newest. */
    private fun freshWsOrCurrent(): String? = wsId

    override fun onCleared() {
        runCatching { eventConnection?.stop() }
        runCatching { eventRouter.close() }
        super.onCleared()
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
        val list = runCatching { CrushApi(p).listSessions(ws) }
            .onFailure { AppLog.w(TAG, "listSessions failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (list.isEmpty()) return null

        // Publish the list so the drawer is populated before we pick.
        _state.value = _state.value.copy(sessions = list, sessionsLoading = false)

        val withMessages = list.filter { it.messageCount > 0 }
        val target = withMessages.maxByOrNull { it.updatedAt }
            ?: list.maxByOrNull { it.updatedAt }
            ?: return null
        AppLog.i(TAG, "resuming session ${target.id} (${target.messageCount} msgs)")
        return target.id
    }

/** Live Zen catalogue, so the picker cannot offer a retired model. */
    fun loadModels() {
        viewModelScope.launch {
            val r = ZenModelsApi({ container.keySession.require(secureKeys, currentProviderId()) }).fetch()
            _state.value = _state.value.copy(
                models = r.models,
                modelsError = r.error
            )
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
    private fun currentPersistedProvider(): String =
        observedProvider.takeIf { it.isNotBlank() } ?: _state.value.providerId

    /** Resolved provider id, for call sites that need the key scoped to it. */
    private fun currentProviderId(): String = Providers.resolve(currentPersistedProvider()).id

    /**
     * Switches provider and re-bootstraps.
     *
     * The whole handshake is redone rather than patched: the key, BOTH model
     * slots, and the agent init are all provider-scoped, so changing only the
     * provider id leaves the previous provider's model in the slots and the turn
     * fails with a message about the wrong provider.
     *
     * A provider change invalidates the model choice - a model id from one
     * provider's catalogue means nothing to another, and passing it through would
     * reproduce "large model not found in provider" on a different route.
     */
    fun setProvider(id: String) {
        val provider = Providers.resolve(id)
        if (provider.id == _state.value.providerId) return
        _state.value = _state.value.copy(
            providerId = provider.id,
            selectedModel = "",
            models = emptyList(),
            modelsError = null,
            fileChanges = null
        )
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
        _state.value = _state.value.copy(selectedModel = id)
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
            if (key == null) {
                _state.value = _state.value.copy(
                    modelsError = "No API key is stored yet. Add one in Settings."
                )
                return@launch
            }
            if (!ZenModelsApi({ key }).probeWorks(id)) {
                AppLog.w(TAG, "rejected model $id (did not answer a probe)")
                _state.value = _state.value.copy(
                    modelsError = "\"$id\" is listed by Zen but cannot be called. Pick another."
                )
                return@launch
            }
            // Both slots: Crush uses the small one for session titles.
            runCatching {
                val api = CrushApi(p)
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
        _state.value = _state.value.copy(sessionsLoading = true)
        viewModelScope.launch {
            val ok = runCatching { mgr.reattachIfStale() }.getOrDefault(false)
            val ws = if (ok) mgr.currentWorkspaceId else null
            val list = if (ws == null) {
                emptyList()
            } else {
                runCatching { CrushApi(p).listSessions(ws) }
                    .onFailure { AppLog.w(TAG, "listSessions failed: ${it.message}") }
                    .getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(
                sessions = SessionList.sort(list),
                sessionsLoading = false
            )
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
        viewModelScope.launch {
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) return@launch
            val ws = mgr.currentWorkspaceId ?: return@launch
            val history = runCatching { CrushApi(p).messages(ws, sessionId) }
                .onFailure { AppLog.w(TAG, "history failed: ${it.message}") }
                .getOrDefault(emptyList())

            val replayed = history.mapNotNull { m ->
                val text = m.text
                // Tool activity is replayed too. A reopened chat that showed the
                // answer but hid the fact that the agent read six files to
                // produce it would misrepresent what happened.
                val tools = m.toolCalls.mapNotNull(ToolActivityParser::from)
                // Skip empty assistant placeholders; they are mid-stream rows,
                // not something the user ever saw. An assistant turn that ONLY
                // used tools is kept - dropping it would erase the trace of the
                // work entirely.
                when {
                    m.role == "user" && text.isNotBlank() ->
                        ChatMessage(id = "h-u-${m.id}", role = "user", text = text)
                    m.role == "assistant" && (text.isNotBlank() || tools.isNotEmpty()) ->
                        ChatMessage(
                            id = "h-a-${m.id}",
                            role = "assistant",
                            text = text,
                            tools = tools
                        )
                    else -> null
                }
            }

            wsId = ws
            // Fully qualified: inside launch{} a bare `this` is the
            // CoroutineScope, and the sessionId PARAMETER shadows the property,
            // so `sessionId = sessionId` cannot resolve either way.
            this@ChatViewModel.sessionId = sessionId
            existingSessionWorkspace = ws
            streamingId = ""
            // Reopening a session is not the session the last diff was measured
            // on. Clearing here prevents the drawer's diff from one chat being
            // displayed under another - which reads as this session having made
            // edits it never made.
            workspaceSnapshot = null
            _state.value = _state.value.copy(
                messages = replayed,
                isStreaming = false,
                activeSessionId = sessionId,
                fileChanges = null
            )
        }
    }

    /** Starts a fresh thread, leaving history intact and reachable. */
    fun newThread() {
        val mgr = workspaces ?: run { bootstrap(); return }
        viewModelScope.launch {
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                bootstrap()
                return@launch
            }
            val ws = mgr.currentWorkspaceId ?: return@launch
            val sid = runCatching { mgr.newSession("chat").id }.getOrNull() ?: return@launch
            wsId = ws
            sessionId = sid
            existingSessionWorkspace = ws
            // A fresh chat must not inherit the previous session's file changes.
            // They describe what the agent did on the OTHER session, and showing
            // them here would claim edits this session never made.
            //
            // The snapshot is dropped too, not just the rendered diff: keeping it
            // would diff the new turn against files captured for the old one.
            workspaceSnapshot = null
            _state.value = _state.value.copy(
                messages = emptyList(),
                isStreaming = false,
                activeSessionId = sid,
                fileChanges = null
            )
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
            _state.value = _state.value.copy(deleteError = null)

            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                AppLog.w(TAG, "deleteSession: could not re-attach")
                _state.value = _state.value.copy(
                    deleteError = "Could not reach the engine. Try again."
                )
                return@launch
            }
            val ws = mgr.currentWorkspaceId
            if (ws == null) {
                _state.value = _state.value.copy(
                    deleteError = "Could not reach the engine. Try again."
                )
                return@launch
            }

            // Re-acquire with force=true on failure. A "fresh" lease can still
            // address a workspace the engine has already dropped, because the
            // lease is wall-clock based and the reap is attachment based.
            var outcome = CrushApi(p).deleteSession(ws, sessionId)
            if (outcome is DeleteOutcome.Failed) {
                AppLog.w(TAG, "delete attempt 1 unknown: ${outcome.reason}")
                val back = runCatching { mgr.reattachIfStale(force = true) }
                    .onFailure { AppLog.w(TAG, "delete re-open failed: ${it.message}") }
                    .getOrDefault(false)
                val ws2 = mgr.currentWorkspaceId
                if (back && ws2 != null) {
                    outcome = CrushApi(p).deleteSession(ws2, sessionId)
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
                    _state.value = _state.value.copy(
                        deleteError = "Could not delete that chat. It may still exist."
                    )
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
                        _state.value = _state.value.copy(
                            messages = emptyList(),
                            activeSessionId = null
                        )
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
     */
    fun resolvePermission(allow: Boolean, allowForSession: Boolean = false) {
        val req = _state.value.pendingPermission ?: return
        val mgr = workspaces ?: return
        val p = port ?: return
        _state.value = _state.value.copy(pendingPermission = null)
        viewModelScope.launch {
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) return@launch
            val ws = mgr.currentWorkspaceId ?: return@launch
            val action = when {
                !allow -> PermissionGrant.DENY
                allowForSession -> PermissionGrant.ALLOW_SESSION
                else -> PermissionGrant.ALLOW
            }
            runCatching {
                CrushApi(p).grant(ws, PermissionGrant(permission = req, action = action))
            }.onFailure { AppLog.w(TAG, "grant failed: ${it.message}") }
        }
    }

    /** True when the agent is waiting on the user, so the UI can show a badge. */
    val awaitingPermission: Boolean get() = _state.value.pendingPermission != null
    fun setInput(v: String) {
        _state.value = _state.value.copy(input = v)
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.isStreaming) return
        // Claim the send IMMEDIATELY. isStreaming was previously only set inside
        // streamSend, which runs after re-attach and newSession - two network
        // round-trips. The composable keeps the Send button enabled for that
        // whole window, so a double-tap started two runs; both wrote to the same
        // streamingId and the replies interleaved into one bubble.
        // Clear any stale stop notice from a previous run.
        _state.value = _state.value.copy(isStreaming = true, stopNotice = null)
        val p = port ?: run { _state.value = _state.value.copy(isStreaming = false); return }
        val mgr = workspaces ?: run {
            _state.value = _state.value.copy(isStreaming = false)
            bootstrap()
            return
        }

        viewModelScope.launch {
            // THE fix for "HTTP 404 /v1/workspaces/... workspace not found".
            //
            // Crush reaps an unattached workspace after ~30s (MEASURED: alive at
            // 15s, gone at 30s). The id captured at bootstrap is therefore
            // normally dead before the user finishes typing, so re-attach first.
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                AppLog.w(TAG, "could not re-attach workspace; re-bootstrapping")
                workspaces = null
                wsId = null
                _state.value = _state.value.copy(isStreaming = false)
                bootstrap()
                return@launch
            }
            val ws = mgr.currentWorkspaceId ?: run {
                _state.value = _state.value.copy(isStreaming = false)
                return@launch
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
                bootstrap()
                return@launch
            }

            wsId = ws
            sessionId = sid
            existingSessionWorkspace = ws
            // The Job has to be captured or Stop cancels nothing. It was declared
            // and read by stopStreaming() but never assigned, so the reply kept
            // streaming after the user pressed Stop.
            streamJob = viewModelScope.launch {
                streamSend(text, ws, sid, mgr, p)
            }
        }
    }

    private suspend fun streamSend(
        text: String,
        ws: String,
        sid: String,
        mgr: WorkspaceManager,
        p: Int
    ) {
        val bubbleId = "streaming-${UUID.randomUUID()}"
        streamingId = bubbleId
        _state.value = _state.value.copy(
            input = "",
            isStreaming = true,
            activeSessionId = sid,
            messages = _state.value.messages + ChatMessage(
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

        // Tell the supervisor a long request is live so hang detection cannot
        // restart the engine underneath the stream.
        container.engineSupervisor.setWorkActive(true)

        try {
            val api = CrushApi(p)
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
            while (attempt < 2 && !accepted) {
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
                        workspaceRoot(),
                        workspaceSnapshot
                    )
                    _state.value = _state.value.copy(fileChanges = null)
                    runCatching { api.send(targetWs, AgentMessageRequest(targetSid, text, runId)) }
                }
                val msg = when {
                    sent == null -> "send timed out"
                    sent.isFailure -> sent.exceptionOrNull()?.message.orEmpty()
                    else -> null
                }

                if (msg == null) {
                    accepted = true
                    failure = null
                    try {
                        awaitStream(events, runId)
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
            if (err != null) {
                updateStreaming {
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
        } catch (e: Exception) {
            updateStreaming {
                it.copy(isStreaming = false, error = "${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            container.engineSupervisor.setWorkActive(false)
            _state.value = _state.value.copy(isStreaming = false)
            // Finalise the live bubble no matter how the turn ended.
            //
            // Only state.isStreaming used to be cleared here. The bubble's own
            // isStreaming flag was cleared by `run_complete` inside awaitStream, so
            // any turn that ended WITHOUT one - the 180s timeout, an SSE that
            // delivered nothing, an exception between the POST and the first frame -
            // left a permanent "thinking..." bubble.
            //
            // Worse, the UI-level flag was clear, so send() accepted the next
            // message: two turns then produced two bubbles streaming at once, which
            // is exactly what a slow free model reproduces. Finalising here means
            // the bubble is honest about having ended, and empty is shown as the
            // timeout it is rather than as a reply that never arrived.
            updateStreaming { m ->
                m.copy(
                    isStreaming = false,
                    // Keep whatever text arrived. An empty bubble is finalised as
                    // empty and marked, so the user is told the turn ended rather
                    // than left watching "thinking..." forever.
                    error = m.error ?: if (m.text.isBlank()) "no reply received" else null
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
        }
    }

    private fun workspaceRoot(): File = EnginePaths(context).workspace

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
            _state.value = _state.value.copy(fileChanges = null)
            return
        }
        val ws = wsId ?: run {
            _state.value = _state.value.copy(fileChanges = null)
            return
        }
        val sid = sessionId ?: run {
            _state.value = _state.value.copy(fileChanges = null)
            return
        }

        val set = runCatching {
            val api = CrushApi(port ?: return@runCatching null)
            val tracked = api.trackedFiles(ws, sid)
            collector.collect(workspaceRoot(), tracked, snap).copy(sessionId = sid)
        }.getOrNull()

        _state.value = _state.value.copy(fileChanges = set)
        if (set == null) {
            AppLog.w(TAG, "file-change collection failed; not reporting changes")
        } else {
            AppLog.i(TAG, "file changes: ${set.stat()}, ${set.unreadable.size} unreadable")
        }
    }

    /**
     * Collects the SSE stream until this run completes.
     *
     * `run_complete` also carries the final text, which is used as a fallback:
     * replayed message frames can arrive before the text part lands, and an
     * empty assistant bubble reads as "no reply" rather than "still loading".
     */
    private suspend fun awaitStream(
        events: kotlinx.coroutines.flow.Flow<CrushEvent>,
        runId: String
    ) {
        withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            events.collect { ev ->
                when (ev.type) {
                    CrushEvent.PayloadType.MESSAGE -> {
                        val m = gson.fromJson(ev.body, StreamMessage::class.java)
                        if (m.role == "assistant") {
                            // Crush republishes the WHOLE message, so replace.
                            // Tools are replaced with it, never appended: each
                            // frame carries the full set, and appending would
                            // stack a duplicate row every time a tool advanced.
                            val tools = m.toolCalls.mapNotNull(ToolActivityParser::from)
                            updateStreaming {
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
                            AppLog.i(TAG, "permission requested: ${req.toolName}")
                            _state.value = _state.value.copy(pendingPermission = req)
                        }
                    }

                    CrushEvent.PayloadType.RUN_COMPLETE -> {
                        val rc = gson.fromJson(ev.body, RunComplete::class.java)
                        if (rc.runId == runId || rc.runId.isEmpty()) {
                            updateStreaming {
                                it.copy(
                                    isStreaming = false,
                                    text = rc.text.ifBlank { it.text }
                                )
                            }
                            throw StopStream()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * The id of the single in-flight assistant bubble.
     *
     * Must be UNIQUE across all messages, because ChatScreen keys its LazyColumn
     * by `id`. A previous version appended a bubble with the literal id
     * "streaming" on every send, so the second send produced two rows with the
     * same key and the app died with:
     *
     *   IllegalArgumentException: Key "streaming" was already used.
     *
     * That crash only appeared once sessions were reused, because before that a
     * failed send left no second bubble to collide with. Fixed by generating a
     * fresh id per turn and tracking it explicitly, rather than using a constant
     * sentinel that every new turn reuses.
     */
    @Volatile
    private var streamingId: String = ""

    /** Updates the live assistant bubble for the current turn. */
    private fun updateStreaming(transform: (ChatMessage) -> ChatMessage) {
        val target = streamingId
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { m ->
                if (target.isNotEmpty() && m.id == target) transform(m) else m
            }
        )
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
     */
    fun stopStreaming() {
        val job = streamJob
        val ws = wsId
        val sid = sessionId
        val p = port

        streamJob = null
        job?.cancel()

        if (ws == null || sid == null || p == null) {
            container.engineSupervisor.setWorkActive(false)
            finalizeStoppedRun(requestConfirmed = true)
            return
        }

        // Keep workActive true until the server acknowledges. A transient
        // failure here must not be reported to the user as "stopped".
        viewModelScope.launch {
            val confirmed = runCatching { CrushApi(p).cancel(ws, sid) }.isSuccess
            if (!confirmed) {
                AppLog.w(TAG, "stop: server cancel not confirmed for $sid")
            }
            container.engineSupervisor.setWorkActive(false)
            finalizeStoppedRun(requestConfirmed = confirmed)
        }
    }

    private fun finalizeStoppedRun(requestConfirmed: Boolean) {
        _state.value = _state.value.copy(
            isStreaming = false,
            messages = _state.value.messages.map {
                if (it.isStreaming) it.copy(isStreaming = false) else it
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

    private fun updateAssistant(id: String, transform: (ChatMessage) -> ChatMessage) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { m -> if (m.id == id) transform(m) else m }
        )
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
        const val STREAM_TIMEOUT_MS = 180_000L
        const val SEND_TIMEOUT_MS = 30_000L

        /**
         * Best-effort model id used ONLY to claim a workspace before discovery
         * has run, and always replaced by a probed id before the app reports
         * ready. Crush reaps an unattached workspace in ~30s, so the bootstrap
         * has to touch the workspace before the slow probe or every later call
         * 404s.
         *
         * Deliberately NOT promoted to the configured model: if discovery fails,
         * the app reports failure rather than silently chatting on a guess.
         */
        const val FALLBACK_PROBE_MODEL = "space-bunny-free"
    }
}

