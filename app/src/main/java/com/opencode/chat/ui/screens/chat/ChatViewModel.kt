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
import com.opencode.chat.data.crush.CreateSessionRequest
import com.opencode.chat.data.crush.CrushApi
import com.opencode.chat.data.crush.CrushEvent
import com.opencode.chat.data.crush.CrushEventStream
import com.opencode.chat.data.crush.CrushSession
import com.opencode.chat.data.crush.PermissionGrant
import com.opencode.chat.data.crush.PermissionRequest
import com.opencode.chat.data.crush.RunComplete
import com.opencode.chat.data.crush.SelectedModel
import com.opencode.chat.data.crush.Session
import com.opencode.chat.data.crush.SetModelRequest
import com.opencode.chat.data.crush.StreamMessage
import com.opencode.chat.data.crush.WorkspaceLease
import com.opencode.chat.data.crush.WorkspaceManager
import com.opencode.chat.data.local.SecureKeyStore
import com.opencode.chat.di.AppContainer
import com.opencode.chat.domain.model.TuningPolicy
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
    val error: String? = null
)

data class ChatUiState(
    val phase: String = "starting engine",
    val engineReady: Boolean = false,
    val isStreaming: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val models: List<LiveZenModel> = emptyList(),
    val modelsError: String? = null,
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
    val activeSessionId: String? = null
)

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
     */
    private var existingSessionWorkspace: String? = null

    /** Guards against two concurrent bootstraps racing to create two workspaces. */
    private var bootstrapping: Boolean = false

    /**
     * Called after a successful unlock. Retries bootstrap only when the key was
     * the thing blocking it, so this is cheap to call on every unlock.
     */
    fun onKeyAvailable() {
        if (!keyBlocked || wsId != null) return
        bootstrap()
    }

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

    private fun bootstrap() {
        if (bootstrapping) return
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

        // Read the persisted permission mode rather than assuming. Getting this
        // wrong in the AUTO_APPROVE direction silently grants the agent
        // unattended file edits, so it fails safe to ASK on any error.
        val permissionMode = runCatching {
            container.settingsRepository.settings.first().permissionMode
        }.getOrDefault(com.opencode.chat.domain.model.PermissionMode.ASK)
        AppLog.i(TAG, "permissionMode=$permissionMode")

        val opened = session.openWorkspace("$d/workspace", "$d/crush/state", permissionMode)
        val key = container.keySession.require(secureKeys)
        if (key.isBlank()) {
            // The old copy here was a lie. read() returns null for BOTH "the
            // user never gave us a key" and "the key is fine, the auth window
            // lapsed", so telling the user to add a key they had already added
            // sent them to Settings for nothing. Now that the app locks at
            // entry, a blank key here almost always means we are locked.
            keyBlocked = secureKeys.hasStoredCiphertext()
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
            providerId = "opencode-zen",
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
                "opencode-zen"
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
            session.ensureAgentReady(key, providerId = "opencode-zen", modelId = chosen)
        }.getOrElse {
            AppLog.w(TAG, "init with probed model threw: ${it.message}")
            session.ensureAgentReady(key, providerId = "opencode-zen", modelId = chosen)
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
                    providerId = "opencode-zen",
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
        resumable?.let { openSession(it) }
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
            val r = ZenModelsApi({ container.keySession.require(secureKeys) }).fetch()
            _state.value = _state.value.copy(
                models = r.models,
                modelsError = r.error
            )
        }
    }

    fun setModel(id: String) {
        _state.value = _state.value.copy(selectedModel = id)
        val ws = wsId ?: return
        val p = port ?: return
        viewModelScope.launch {
            // Verify before writing to the slots. The picker is fed the public
            // /models list, which contains retired ids, so a tap could otherwise
            // configure a model that fails every later turn with
            // "Model is unavailable" - the same class of failure as the original
            // hardcoded default, just user-triggered.
            val key = runCatching { container.keySession.require(secureKeys) }
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
                    api.setModel(ws, slot, SelectedModel(model = id, provider = "opencode-zen"))
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
                // Skip empty assistant placeholders; they are mid-stream rows,
                // not something the user ever saw.
                if (m.role == "user" && text.isNotBlank()) {
                    ChatMessage(id = "h-u-${m.id}", role = "user", text = text)
                } else if (m.role == "assistant" && text.isNotBlank()) {
                    ChatMessage(id = "h-a-${m.id}", role = "assistant", text = text)
                } else {
                    null
                }
            }

            wsId = ws
            // Fully qualified: inside launch{} a bare `this` is the
            // CoroutineScope, and the sessionId PARAMETER shadows the property,
            // so `sessionId = sessionId` cannot resolve either way.
            this@ChatViewModel.sessionId = sessionId
            existingSessionWorkspace = ws
            streamingId = ""
            _state.value = _state.value.copy(
                messages = replayed,
                isStreaming = false,
                activeSessionId = sessionId
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
            _state.value = _state.value.copy(
                messages = emptyList(),
                isStreaming = false,
                activeSessionId = sid
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
            if (!runCatching { mgr.reattachIfStale() }.getOrDefault(false)) {
                AppLog.w(TAG, "deleteSession: could not re-attach")
                return@launch
            }
            val ws = mgr.currentWorkspaceId ?: return@launch
            // Delete may be attempted minutes after the last send, by which point
            // Crush has reaped the unattached workspace. Re-acquire it with
            // force=true rather than trusting the lease: a "fresh" lease can
            // still address a workspace the engine has already dropped, and the
            // result was a silent no-op that left the chat in the list.
            var deleted = runCatching { CrushApi(p).deleteSession(ws, sessionId) }
                .onFailure {
                    AppLog.w(
                        TAG,
                        "deleteSession attempt 1 failed: ${it::class.java.simpleName} " +
                            "msg=${it.message}"
                    )
                }
                .isSuccess

            if (!deleted) {
                val back = runCatching { mgr.reattachIfStale(force = true) }
                    .onFailure { AppLog.w(TAG, "delete re-open failed: ${it.message}") }
                    .getOrDefault(false)
                val ws2 = mgr.currentWorkspaceId
                if (back && ws2 != null) {
                    deleted = runCatching { CrushApi(p).deleteSession(ws2, sessionId) }
                        .onFailure {
                            AppLog.w(
                                TAG,
                                "deleteSession attempt 2 failed: ${it::class.java.simpleName}"
                            )
                        }
                        .isSuccess
                    if (deleted) wsId = ws2
                }
            }

            if (!deleted) {
                AppLog.w(TAG, "deleteSession: engine refused for $sessionId")
            }

            // Deleting the session this chat is bound to would leave a cached id
            // the engine no longer has, so the next send would 404. Start clean.
            if (sessionId == this@ChatViewModel.sessionId || sessionId == _state.value.activeSessionId) {
                this@ChatViewModel.sessionId = null
                existingSessionWorkspace = null
                _state.value = _state.value.copy(messages = emptyList(), activeSessionId = null)
                newThread()
            }
            loadSessions()
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
        _state.value = _state.value.copy(isStreaming = true)
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
            val existing = sessionId
            val sid = if (existing != null && existingSessionWorkspace == ws) {
                existing
            } else {
                runCatching { mgr.newSession("chat").id }.getOrNull()
            }
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

            // One retry, because a reaped workspace is routine rather than a
            // fault and the user's message must survive it. Two attempts max:
            // retrying more could duplicate a run that actually landed.
            while (attempt < 2 && !accepted) {
                val runId = UUID.randomUUID().toString()
                // Subscribe BEFORE prompting: Crush dispatches the run detached,
                // so events can arrive before the POST even returns.
                val events = CrushEventStream(api, gson).events(targetWs, mgr.clientId)

                // MUST NOT use launch{} here. A launch creates an independent
                // coroutine, so an exception thrown by api.send() escapes the
                // surrounding try/catch and kills the process. That is exactly
                // how a 404 closed the app with no visible error.
                val sent = withTimeoutOrNull(SEND_TIMEOUT_MS) {
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
                    awaitStream(events, runId)
                } else {
                    failure = msg
                    val workspaceGone = msg.contains("workspace not found", ignoreCase = true)
                    if (workspaceGone && attempt == 0) {
                        AppLog.w(TAG, "workspace reaped mid-send; re-attaching and retrying once")
                        if (runCatching { mgr.reattachIfStale(force = true) }.getOrDefault(false)) {
                            val ws2 = mgr.currentWorkspaceId
                            // A fresh session is required: the old one belonged to
                            // the reaped workspace and will 404 identically.
                            val sid2 = runCatching { mgr.newSession("chat").id }.getOrNull()
                            if (ws2 != null && sid2 != null) {
                                targetWs = ws2
                                targetSid = sid2
                                wsId = ws2
                                sessionId = sid2
                                existingSessionWorkspace = ws2
                                attempt++
                                continue
                            }
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
                            updateStreaming {
                                it.copy(
                                    text = m.text.ifBlank { it.text },
                                    thinking = m.thinking.ifBlank { it.thinking },
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
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        container.engineSupervisor.setWorkActive(false)
        _state.value = _state.value.copy(
            isStreaming = false,
            messages = _state.value.messages.map {
                if (it.isStreaming) it.copy(isStreaming = false) else it
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

