package com.crush.mobile.di

import android.content.Context
import android.util.Log
import com.crush.mobile.data.crush.WorkspaceManager
import com.crush.mobile.data.local.KeySession
import com.crush.mobile.data.local.LockState
import com.crush.mobile.data.local.SettingsDataStore
import com.crush.mobile.domain.file.ReviewHistory
import com.crush.mobile.data.project.PendingProjectTrust
import com.crush.mobile.data.project.ProjectTrustService
import com.crush.mobile.data.project.RoomProjectStore
import com.crush.mobile.data.project.TrustVerdict
import com.crush.mobile.data.repository.SettingsRepository
import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.TuningPolicy
import com.crush.mobile.engine.CrushEngine
import com.crush.mobile.engine.EngineInstanceRegistry
import com.crush.mobile.engine.EnginePaths
import com.crush.mobile.engine.EngineState
import com.crush.mobile.engine.EngineSupervisor
import com.crush.mobile.data.crush.ConnectionRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI container.
 *
 * [EngineSupervisor] is a process-wide SINGLETON, not per-ViewModel. An earlier
 * version returned a new supervisor per call, and because each supervisor runs
 * its own polling loop, an Activity recreation produced two live loops. Chaos
 * testing showed the symptom plainly: every EngineSupervisor log line appeared
 * twice, and two loops racing to restart the same engine caused three
 * simultaneous "process is dead" restarts within one millisecond.
 *
 * The engine is genuinely process-scoped, so the supervisor that owns it must be
 * too. The scope is a container-owned one so it outlives any single Activity.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val containerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    /**
     * Process-scoped, like the supervisor. A per-ViewModel or per-Activity key
     * holder would mean a new Cipher read after every rotation, which is exactly
     * the failure mode KeySession exists to remove.
     */
    val keySession: KeySession = KeySession()

    /**
     * Process-scoped temporal state of the app-lock gate. Activity-scoped lock
     * state did not survive activity recreation (Android recreates the activity
     * around credential-screen closure), which is how a fresh authentication
     * could be forgotten within seconds - see [LockState].
     */
    val lockState: LockState = LockState()

    private val settingsDataStore = SettingsDataStore(appContext, keySession)

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }

    val deviceProfile: DeviceProfile by lazy { DeviceProfile.detect(appContext) }

    /**
     * The durable project registry (ADR-0001 P2, accepted 2026-11-03). The
     * Projects surface (P3, this slice) is its first in-app READER: the
     * screen lists/creates rows and triggers the idempotent legacy-workspace
     * migration on entry. Chat binding to per-project engine instances is
     * the ADR's slice-2 work and deliberately NOT here yet.
     */
    val projectStore: RoomProjectStore by lazy { RoomProjectStore(appContext) }

    /**
     * S-02 (2026-11-05): THE pre-bind consent gate for project engine
     * bindings. Every path that may reach `applyBinding(Project(..))` runs
     * its gate first - today that is exactly the Projects row tap (via
     * ProjectsViewModel.requestOpenProject) and [restoreLastBinding]; there
     * are no deep links or external connectors (2026-11-05), and ANY future
     * "open a project" entry point (intent handler, connector, restored
     * documents) must route through [ProjectTrustService.gate] before it
     * binds - the central trust policy, not a per-screen habit.
     */
    val projectTrust: ProjectTrustService by lazy {
        ProjectTrustService(projectStore)
    }

    /**
     * The pending consent decision a BLOCKED restore published. Hosted by
     * the app root (MainActivity) the same way [LockState] is hosted: a
     * process-scoped StateFlow at container level, because the prompt must
     * outlive any screen it happens to appear over. Consumed by
     * [approvePendingProjectTrust] (record + bind, exactly what the restore
     * would have done had consent already stood) or
     * [clearPendingProjectTrust] (the restore stays on the legacy default;
     * the user can open the folder manually through the gate).
     */
    private val _pendingProjectTrust = MutableStateFlow<PendingProjectTrust?>(null)
    val pendingProjectTrust: StateFlow<PendingProjectTrust?> =
        _pendingProjectTrust.asStateFlow()

    /**
     * Batch G's cold-boot one-shot: the DISPATCHED trace's target session id,
     * noticed by [openLastDispatchedHandoff] before the binding lands and
     * claimed by the chat layer's bootstrap when that id appears in the bound
     * workspace's listing (`findSessionToResume`). A plain property is not
     * enough because the container publishes before the ViewModel exists; a
     * StateFlow gives the bootstrap a readable value whichever side lands
     * second. Claiming = get-AND-clear only on a successful listing match,
     * so a bootstrap that cannot see the id (wrong instance's listing) leaves
     * the intent for the right one.
     */
    private val _pendingOpenSession = MutableStateFlow<String?>(null)
    val pendingOpenSession: StateFlow<String?> = _pendingOpenSession.asStateFlow()

    fun clearPendingOpenSession() {
        _pendingOpenSession.value = null
    }

    /**
     * P4 "Review" runtime store (2026-11-07): per-turn measured changes with
     * per-file revert on the latest completed turn. Process-scoped like the
     * lock state, because the Review screen must outlive the chat's
     * back-stack entry it was reached from (every row open replaces that
     * stack); process memory is also its honest CAP, which the screen says
     * out loud. ChatViewModel is the only writer (beginTurn at send,
     * recordTurn in the turn's finally); the Review screen only reads and
     * reverts through [ReviewHistory]'s own gates.
     */
    val reviewHistory: ReviewHistory by lazy { ReviewHistory() }

    val crushEngine: CrushEngine by lazy {
        CrushEngine(
            appContext,
            TuningPolicy.resolve(deviceProfile, TuningPolicy.Overrides()).engineIdleTimeoutSec
        )
    }

    private var supervisorInstance: EngineSupervisor? = null

    val engineSupervisor: EngineSupervisor
        get() = supervisorInstance ?: synchronized(this) {
            supervisorInstance ?: EngineSupervisor(
                    crushEngine, containerScope,
                    tuning = com.crush.mobile.domain.model.TuningPolicy.resolve(
                        deviceProfile, com.crush.mobile.domain.model.TuningPolicy.Overrides()
                    ),
                    appContext = appContext
                ).also { s ->
                supervisorInstance = s
                // P1-11: a workspace id is minted PER ENGINE PROCESS, so the
                // moment a new engine run reaches Running, every id minted by
                // the previous run is dead regardless of how fresh the lease
                // claims it is - the 15s freshness rule alone cannot see an
                // engine restart. This ONE registration on the ONE supervisor
                // singleton (see the two-loop story above: registering
                // anywhere else risks a second loop or a second registration)
                // bumps WorkspaceManager's engine-generation counter; every
                // manager instance then fails closed on any cached id minted
                // by an older generation and re-POSTs the idempotent
                // /workspaces attach.
                s.onEngineStart {
                    WorkspaceManager.onEngineRestarted()
                }
                applyTuning(
                    TuningPolicy.resolve(deviceProfile, TuningPolicy.Overrides())
                )
                // Single source of truth. One collector updates BOTH the
                // supervisor and the engine's idle linger; they previously
                // drifted, so a manual idle-timeout override reached the
                // supervisor but never reached CRUSH_SERVER_IDLE_TIMEOUT.
                containerScope.launch {
                    settingsRepository.settings.collect { st ->
                        applyTuning(TuningPolicy.resolve(deviceProfile, st.tuning))
                    }
                }
            }
        }

    // ----------------------------------------------------------------
    // Per-instance engine runtimes (ADR-0001 accepted shape, P3 slice 2).
    // ----------------------------------------------------------------

    /**
     * One RUNTIME BUNDLE per bound engine instance: the engine child, its own
     * supervisor loop, and the instance key that isolates its socket file
     * (`filesDir/crush-<key>.sock`) and its whole engine state tree — the
     * measured isolation unit ("TwoEngineInstancesPerProjectTest": per-project
     * engine instances hold exactly their own sessions).
     *
     * The legacy instance ("") stays the process-single
     * [crushEngine]/[engineSupervisor] pair: today's behavior, byte-for-byte.
     *
     * The two-loops-for-one-engine hazard documented above does NOT apply
     * here: that guard exists because a supervisor per CALL would make two
     * loops race on the SAME engine. Distinct instances own distinct engine
     * processes and distinct loops - which is the design, the same way two
     * real crush desktops run two engines.
     *
     * Restart-pressure wiring is per instance: the P1-11 flush bumps
     * WorkspaceManager's generation counter, which deliberately
     * over-invalidates ACROSS instances - re-attach is path-derived and
     * idempotent, so an over-invalidated id costs one extra POST per bound
     * instance and never an isolation breach.
     */
    data class EngineRuntime(
        val instanceKey: String,
        val engine: CrushEngine,
        val supervisor: EngineSupervisor,
    )

    /**
     * §1 of the Gate-7 design memo (2026-11-05): the single
     * `projectRuntime: EngineRuntime?` slot it replaces was a silent
     * eviction hazard - a projects->chat round trip could leave
     * `runtimeFor(oldKey)` constructing a whole fresh engine pair just to
     * stop it. The registry holds ONE runtime object per non-legacy key,
     * forever: idempotent construction, never-evict, stop-only (registry
     * stop is lookup-or-noop), and the non-legacy liveness budget gate (at
     * most two keys with live children - chat + one project).
     *
     * The generic here is deliberately [EngineRuntime]: the registry file
     * stays engine-layer-pure, and the wiring (liveness reads are
     * volatility-only; stop is the supervisor's synchronous, bounded stop)
     * is pinned by lambdas so no engine-package import of di exists.
     */
    private val engineRegistry = EngineInstanceRegistry<EngineRuntime>(
        legacyKey = "",
        livenessOf = { rt ->
            EngineInstanceRegistry.Liveness(rt.engine.isAlive(), rt.supervisor.state.value)
        },
        stopRuntime = { rt -> rt.supervisor.stop() },
    )

    /**
     * §3 of the Gate-7 design memo (2026-11-05): owner-of-record for the
     * long-lived workspace event connections, keyed by instanceKey. The chat
     * ViewModel registers every connection it builds (with a process-unique
     * observer epoch), the binding setters below revoke the OUTGOING key's
     * registration with the same guard the engine stop applies, and a stale
     * VM consults [ConnectionRegistry.isSuperseded] before its reader would
     * re-enter the reconnect ladder.
     */
    val connectionRegistry = ConnectionRegistry()

    /**
     * S3: WHICH surface the chat layer is bound to. The derived
     * [activeInstanceKey] stays the single engine selector ("" legacy /
     * [EnginePaths.CHAT_INSTANCE_KEY] chat pool / "p<uuid8>" project row);
     * the binding itself carries the SEMANTICS (a ChatPool session is
     * conversation-only and has no project identity), which the chat
     * ViewModel replays honestly in its phase text and routing.
     */
    sealed interface EngineBinding {
        data object Legacy : EngineBinding
        data object ChatPool : EngineBinding
        data class Project(val ref: com.crush.mobile.project.ProjectRef) : EngineBinding
    }

    private val _activeBinding =
        MutableStateFlow<EngineBinding>(EngineBinding.Legacy)

    /** The binding surface drives compose observers (S4 UI). */
    val activeBinding: StateFlow<EngineBinding> = _activeBinding.asStateFlow()

    /**
     * The engine instance the chat layer is bound to: "" = the legacy
     * workspace, [EnginePaths.CHAT_INSTANCE_KEY] = the chat pool,
     * "p<uuid8>" = a project row. Written ONLY through the binding setters,
     * never by the chat ViewModel.
     */
    private val _activeInstanceKey = MutableStateFlow("")
    val activeInstanceKey: StateFlow<String> = _activeInstanceKey.asStateFlow()

    @Volatile
    var activeProjectRef: com.crush.mobile.project.ProjectRef? = null
        private set

    private fun keyFor(binding: EngineBinding): String = when (binding) {
        is EngineBinding.Legacy -> ""
        is EngineBinding.ChatPool -> EnginePaths.CHAT_INSTANCE_KEY
        is EngineBinding.Project -> instanceKeyFor(binding.ref.id)
    }

    /** Stable id -> instance-key derivation; "" stays reserved for legacy. */
    fun instanceKeyFor(id: java.util.UUID): String = "p" + id.toString().substring(0, 8)

    /**
     * Whether an AGENT RUN is in flight on some engine instance, and on
     * which one. Reported by the chat ViewModel around every turn (send()
     * start, abandonRun on the exit paths) because ONLY the ViewModel can
     * see `activeRun` - the container cannot. The chat ViewModel is scoped
     * to the nav back-stack entry; a projects->chat round trip can recreate
     * it (measured on device, 2026-11-04 09:27-09:30), so this signal must
     * live in something that outlives every entry: this container.
     */
    @Volatile
    var engineRunInFlightFor: String? = null
        private set

    /** ViewModel seam for the in-flight marker; clear is key-guarded so a
     *  mid-run instance switch leaves the OLD key flagged (its run rides
     *  its own linger) instead of erasing a different key's signal.
     *
     *  §3 (2026-11-05): the key-guarded clear is ALSO the flush point for a
     *  connection revocation deferred earlier by the stop-on-switch guard -
     *  when that guard skipped because a run was in flight, the registered
     *  connection's stop runs here the moment the turn ends. The key guard
     *  is the SAME expression, so a foreign key's flush can never fire. */
    fun reportRunInFlight(instanceKey: String, inFlight: Boolean) {
        if (inFlight) engineRunInFlightFor = instanceKey
        else if (engineRunInFlightFor == instanceKey) {
            engineRunInFlightFor = null
            connectionRegistry.flushDeferredRevocation(instanceKey)
        }
    }

    /**
     * Projects-surface entry point: null opens the legacy workspace.
     * S-02 CENTRAL TRUST NOTE (see [projectTrust]): reaching this with a
     * non-null ref carries an OBLIGATION upstream - the caller must have
     * run [projectTrust.gate] and either got an Allow verdict, or have
     * recorded fresh consent for exactly what is about to be bound. Today's
     * compliant callers: the Projects row tap
     * (ProjectsViewModel.requestOpenProject / grantTrustAndOpen) and the
     * restore prompt's approval. There are no deep links (2026-11-05); a
     * future one MUST gate first too.
     */
    fun setActiveProject(ref: com.crush.mobile.project.ProjectRef?) {
        applyBinding(
            if (ref == null) EngineBinding.Legacy else EngineBinding.Project(ref)
        )
    }

    /**
     * S3 chat-pool entry (driven by the session surface, S4 UI): binds the
     * chat layer to the DEDICATED conversation engine instance - no project
     * cwd, and its spawn-time policy injection removes every Code tool
     * (see CrushEngine.start()).
     */
    fun setActiveChatPool() = applyBinding(EngineBinding.ChatPool)

    /**
     * The restore prompt's consent button (S-02): records consent for
     * whatever bytes the folder carries RIGHT NOW and only then applies the
     * same binding the restore had wanted - the exact contract the Projects
     * row consent path follows ("Run it and open"). A record write that
     * fails is fail-closed: the request stays pending, NOTHING binds, and
     * the honest pair lands in both log sinks.
     */
    fun approvePendingProjectTrust() {
        val pending = _pendingProjectTrust.value ?: return
        containerScope.launch {
            runCatching { projectTrust.recordConsent(pending.ref) }
                .onSuccess {
                    // The request is consumed so the dialog host stops
                    // showing it in the same breath as the bind.
                    _pendingProjectTrust.value = null
                    applyBinding(EngineBinding.Project(pending.ref))
                }
                .onFailure {
                    val why = it.message ?: it.javaClass.simpleName
                    Log.w("Lifecycle", "consent record failed: $why")
                    com.crush.mobile.util.AppLog.e(
                        "Lifecycle", "consent record failed: $why", it
                    )
                }
        }
    }

    /**
     * Cancel (or any takeover) for a pending restore trust request: the
     * restore stays on the legacy default and the user can open the folder
     * manually through the Projects gate. Also used by the Projects surface
     * when IT takes over a row's trust decision (supersedes the prompt) and
     * when it records consent (the decision is consumed). Never a security
     * boundary - it only removes a request; the gate still guards every
     * open.
     */
    fun clearPendingProjectTrust() {
        _pendingProjectTrust.value = null
    }

    /**
     * A11/A12 (2026-11-05) binding tokens, persisted once per binding change
     * and read once per process at cold start. SELF-DESCRIBING on purpose:
     * the derived instance key for a project is `p<first8-uuid>` - LOSSY - so
     * the stored token carries the FULL row uuid and the restore re-resolves
     * the row from the project store. A token naming a row that was removed
     * restores to the honest default (legacy stays bound) rather than
     * reconstructing an engine underneath a deleted directory.
     */
    companion object {
        const val TOKEN_LEGACY = "legacy"
        const val TOKEN_CHAT = "chat"
        private const val TOKEN_PROJECT_PREFIX = "proj:"

        /** The persisted form of a binding. Never null - legacy has a token. */
        fun bindingToToken(binding: EngineBinding): String = when (binding) {
            is EngineBinding.Legacy -> TOKEN_LEGACY
            is EngineBinding.ChatPool -> TOKEN_CHAT
            is EngineBinding.Project -> "$TOKEN_PROJECT_PREFIX${binding.ref.id}"
        }

        /**
         * The full project uuid inside a project token, or null for legacy /
         * chat / malformed tokens (nulls are the caller's signal that no row
         * lookup is needed - and illegal uuid bytes fail-safe to null via the
         * runCatching, never throwing).
         */
        fun tokenToProjectId(token: String): java.util.UUID? =
            if (token.startsWith(TOKEN_PROJECT_PREFIX))
                runCatching {
                    java.util.UUID.fromString(token.removePrefix(TOKEN_PROJECT_PREFIX))
                }.getOrNull()
            else null
    }

    /**
     * One-shot-per-process restore of the last engine binding (A11/A12,
     * measured gap: an overnight cold boot landed the user back on the LEGACY
     * pool's session even though they had last used the chat pool). Called by
     * (1) the container's own cold-start launch, and (2) awaited again at the
     * top of the chat ViewModel's bootstrap so a boot that races the first
     * call still configures the RESTORED surface.
     *
     * Ordered decisions, each fail-safe:
     * - A user action during the restore window WINS: a binding that is
     *   already off the default is never overwritten by a restore.
     * - "" / "legacy" / unparseable tokens mean the legacy default - the
     *   state the process is already in, nothing to change.
     * - A project token whose row is gone (removed while the app was dead)
     *   logs honestly and stays on the legacy workspace.
     * - S-02 (2026-11-05): a project token resolves through the crushrc
     *   trust gate BEFORE any binding. Required → the restore is REFUSED
     *   (the legacy default stays bound), the honest pair is logged, and a
     *   [PendingProjectTrust] is published for the root-level F04 prompt;
     *   the engine provably does not attach the folder until consent. Allow
     *   → the gate-OBSERVED state is re-recorded via confirmAtGate (a
     *   no-crushrc folder gets the noConfig marker so a later-planted
     *   crushrc re-gates; a matched fingerprint refreshes its stamp) - but
     *   ONLY if the folder still shows exactly that state: a crushrc that
     *   appears, changes, or vanishes inside the gate->record window writes
     *   NOTHING, and the restore is wired exactly like the Required verdict
     *   below. A trust-state READ failure is fail-closed too: legacy
     *   stays bound and the pair is logged - never "trust check failed, bind
     *   anyway".
     */
    private var bindingRestored = false

    suspend fun restoreLastBinding() {
        if (bindingRestored) return
        bindingRestored = true
        if (_activeInstanceKey.value != "") return
        val token = runCatching { settingsRepository.readLastBinding() }.getOrDefault("")
        if (token.isEmpty() || token == TOKEN_LEGACY) return
        if (token == TOKEN_CHAT) {
            com.crush.mobile.util.AppLog.i("Lifecycle", "restoring last binding: chat pool")
            applyBinding(EngineBinding.ChatPool)
            return
        }
        val ref = tokenToProjectId(token)?.let { pid ->
            runCatching { projectStore.byId(pid) }.getOrNull()
        }
        if (ref == null) {
            com.crush.mobile.util.AppLog.w(
                "Lifecycle",
                "restoring last binding: saved row for '$token' is gone; legacy stays bound"
            )
            return
        }
        // S-02: gate BEFORE any bind decision. `return` inside the getOrElse
        // is the fail-closed corner (unreadable trust state = never bind).
        val verdict = runCatching { projectTrust.gate(ref) }
            .getOrElse { failure ->
                val why = failure.message ?: failure.javaClass.simpleName
                Log.w("Lifecycle", "last-binding restore blocked: trust state unreadable ($why)")
                com.crush.mobile.util.AppLog.w(
                    "Lifecycle", "last-binding restore blocked: trust state unreadable ($why)",
                    failure
                )
                return
            }
        when (verdict) {
            is TrustVerdict.Allow -> {
                com.crush.mobile.util.AppLog.i(
                    "Lifecycle", "restoring last binding: project '${ref.label}'"
                )
                // Record BEFORE bind, so the persisted state always says YES
                // for exactly what is being bound - and only for what THIS
                // gate observed: the noConfig marker for a still-absent
                // crushrc, the matched fingerprint's refreshed stamp
                // otherwise. confirmAtGate re-reads ONCE and writes NOTHING
                // when the folder moved inside the gate->record window (a
                // crushrc planted or swapped while the restore ran), so the
                // recorded state can never be a gate-never-saw surprise.
                val outcome = runCatching {
                    projectTrust.confirmAtGate(ref, verdict.observedFingerprint)
                }
                    .onFailure { failure ->
                        val why = failure.message ?: failure.javaClass.simpleName
                        Log.w("Lifecycle", "last-binding restore blocked: consent state unrecordable ($why)")
                        com.crush.mobile.util.AppLog.w(
                            "Lifecycle",
                            "last-binding restore blocked: consent state unrecordable ($why)",
                            failure
                        )
                    }
                    .getOrNull()
                val movedTo = outcome?.movedTo
                if (outcome != null && movedTo == null) {
                    // The observed state was persisted: proceed exactly as
                    // it would have pre-S-02.
                    applyBinding(EngineBinding.Project(ref))
                } else if (movedTo != null) {
                    // The folder moved inside the gate->record window and
                    // NOTHING was recorded: the open is wired EXACTLY like
                    // the Required verdict - nothing binds, the honest pair
                    // lands in both sinks, the F04 prompt takes over.
                    val msg =
                        "last-binding restore blocked: project consent required (${movedTo.text})"
                    Log.i("Lifecycle", msg)
                    com.crush.mobile.util.AppLog.i("Lifecycle", msg)
                    _pendingProjectTrust.value = PendingProjectTrust(ref, movedTo)
                }
            }
            is TrustVerdict.Required -> {
                // The honest pair (S5 instrumentation style): the same line
                // into logcat AND app.log, reason shaped per the verdict.
                val msg =
                    "last-binding restore blocked: project consent required (${verdict.reason})"
                Log.i("Lifecycle", msg)
                com.crush.mobile.util.AppLog.i("Lifecycle", msg)
                // Do NOT bind. The default legacy state stays exactly where
                // it is; the root-level F04 prompt takes over the decision.
                _pendingProjectTrust.value = PendingProjectTrust(ref, verdict.kind)
            }
        }
    }

    private var handoffResumeTried = false

    /**
     * Batch G (plan §9 g2): the cold-boot resume of the newest DISPATCHED
     * handoff trace — a SEPARATE function beside [restoreLastBinding], whose
     * bytes it does not touch. The row is the cross-restart proof: this reads
     * it, trust-gates the target project in the same fail-closed order
     * (Required or a moved-inside-window record publishes
     * [PendingProjectTrust], whose Allow lands the binding), and either rides
     * the binding the restore already applied or applies the trace's own
     * binding when nothing was bound. The trace's target session is
     * remembered in [pendingOpenSession] so the chat bootstrap resumes THAT
     * session by id — deterministic, never "whichever session the workspace
     * last touched".
     *
     * Posture: the extension adopts a trace only when its target is the
     * active binding or nothing is bound yet. A user who has since bound
     * elsewhere is never yanked; the row stays DISPATCHED and surfaceable
     * (its source chat's resume bar, the drawer). A target session missing
     * from the workspace listing at boot is the chat layer's fallback
     * (last-with-content), never a row rewrite — the trace is never
     * auto-deleted.
     */
    suspend fun openLastDispatchedHandoff() {
        if (handoffResumeTried) return
        handoffResumeTried = true
        val dao = runCatching { projectStore.handoffDao() }.getOrElse { failure ->
            com.crush.mobile.util.AppLog.w(
                "Lifecycle",
                "handoff resume skipped: handoff store unavailable (${failure.javaClass.simpleName})"
            )
            return
        }
        val row = runCatching { dao.latestDispatched() }.getOrElse { failure ->
            com.crush.mobile.util.AppLog.w(
                "Lifecycle",
                "handoff resume skipped: trace read failed (${failure.javaClass.simpleName})"
            )
            return
        } ?: return
        val targetSession = row.targetCodeSessionId ?: return
        val ref = runCatching {
            projectStore.byId(java.util.UUID.fromString(row.targetProjectId))
        }.getOrNull()
        if (ref == null) {
            com.crush.mobile.util.AppLog.w(
                "Lifecycle",
                "handoff resume skipped: target row ${row.targetProjectId} is gone; the trace stays surfaceable"
            )
            return
        }
        val activeProject = (_activeBinding.value as? EngineBinding.Project)?.ref
        val alreadyBound = activeProject?.id == ref.id
        if (!alreadyBound && _activeInstanceKey.value != "") {
            com.crush.mobile.util.AppLog.i(
                "Lifecycle",
                "handoff resume deferred: active binding is '${_activeInstanceKey.value}', not '${ref.label}'"
            )
            return
        }
        _pendingOpenSession.value = targetSession
        if (alreadyBound) {
            // The restore already gated + bound this exact row; only the
            // session intent was missing.
            com.crush.mobile.util.AppLog.i(
                "Lifecycle",
                "handoff resume: binding '${ref.label}' already restored; opening trace session $targetSession"
            )
            return
        }
        val verdict = runCatching { projectTrust.gate(ref) }.getOrElse { failure ->
            val why = failure.message ?: failure.javaClass.simpleName
            Log.w("Lifecycle", "handoff resume blocked: trust state unreadable ($why)")
            com.crush.mobile.util.AppLog.w(
                "Lifecycle", "handoff resume blocked: trust state unreadable ($why)", failure
            )
            _pendingOpenSession.value = null
            return
        }
        when (verdict) {
            is TrustVerdict.Allow -> {
                val outcome = runCatching {
                    projectTrust.confirmAtGate(ref, verdict.observedFingerprint)
                }.getOrNull()
                val movedTo = outcome?.movedTo
                if (outcome != null && movedTo == null) {
                    com.crush.mobile.util.AppLog.i(
                        "Lifecycle",
                        "handoff resume: restoring project '${ref.label}' + trace session $targetSession"
                    )
                    applyBinding(EngineBinding.Project(ref))
                } else if (movedTo != null) {
                    val msg = "handoff resume blocked: project consent required (${movedTo.text})"
                    Log.i("Lifecycle", msg)
                    com.crush.mobile.util.AppLog.i("Lifecycle", msg)
                    _pendingProjectTrust.value = PendingProjectTrust(ref, movedTo)
                } else {
                    // Unrecordable consent state: fail closed exactly like the
                    // restore's corner - nothing binds, no session intent
                    // survives a bind that will not happen.
                    _pendingOpenSession.value = null
                }
            }
            is TrustVerdict.Required -> {
                val msg = "handoff resume blocked: project consent required (${verdict.reason})"
                Log.i("Lifecycle", msg)
                com.crush.mobile.util.AppLog.i("Lifecycle", msg)
                // The root-level F04 prompt takes the decision; its Allow
                // (approvePendingProjectTrust) applies the binding and the
                // session intent set above lands with the chat bootstrap.
                _pendingProjectTrust.value = PendingProjectTrust(ref, verdict.kind)
            }
        }
    }

    init {
        // A11/A12: prime the restore ahead of composition. The chat layer's
        // LaunchedEffect(activeInstanceKey) re-drives bindProject on ANY later
        // emission, so a restore landing a frame or two late still rebinds -
        // but bootstraps that read the binding should not have to wait for
        // frame timing.
        containerScope.launch {
            runCatching { restoreLastBinding() }
            // Batch G (plan §9 g2): the DISPATCHED trace's resume runs BESIDE
            // the restore, after it - restoreLastBinding's bytes are untouched,
            // and this only acts when no binding landed (or the restore landed
            // the trace's own target).
            runCatching { openLastDispatchedHandoff() }
        }
    }

    private fun applyBinding(binding: EngineBinding) {
        val oldKey = _activeInstanceKey.value
        activeProjectRef = (binding as? EngineBinding.Project)?.ref
        _activeBinding.value = binding
        val newKey = keyFor(binding)
        if (newKey == oldKey) return
        _activeInstanceKey.value = newKey
        // A11/A12: make the binding change durable the same moment it lands.
        // Fire-and-forget: the launch queue on the container's main-immediate
        // scope serialises behind the user taps that caused it, and DataStore
        // serialises edits behind THAT - so the last persisted token is the
        // last binding, at every realistic tap rate.
        val lastBindingToken = bindingToToken(binding)
        containerScope.launch {
            runCatching { settingsRepository.saveLastBinding(lastBindingToken) }
                .onFailure {
                    com.crush.mobile.util.AppLog.w(
                        "Lifecycle", "last-binding persist failed: ${it.message}"
                    )
                }
        }
        // Stop the OUTGOING non-legacy engine on the switch (ADR-0001
        // lifecycle polish, 2026-11-04). This lives HERE and NOT in the chat
        // ViewModel's bindProject: the ViewModel is scoped to the nav
        // back-stack entry, so a projects->chat round trip can recreate it
        // and the outgoing instance key dies with the old entry (measured
        // on device: the legacy-forward bootstrap ran, and the project
        // engine child was still alive because the fresh VM early-returned
        // on its default "" key). The container outlives every entry.
        //
        // Run-in-flight guard: a turn in flight is never killed mid-work -
        // that instance rides its own idle linger instead. The LEGACY engine
        // ("") is never stopped here: it hosts the app's own history,
        // survives every switch, and pre-dates per-instance engines. The
        // chat pool engine is stop-on-switch like every project child
        // (S3): its linger is governed by the same guard.
        if (oldKey.isNotBlank() && engineRunInFlightFor != oldKey) {
            com.crush.mobile.util.AppLog.i(
                "Lifecycle",
                "stopping project engine for instance '$oldKey' (no run in flight)"
            )
            // §1 registry stop (2026-11-05): lookup-or-stop, never a fresh
            // construction of the outgoing pair (the memo's slot-eviction
            // kill). Same synchronous, bounded supervisor stop; the
            // serialization grain is now the registry's per-key lock.
            engineRegistry.stop(oldKey, "instance switched away")
        }
        // §3 connection reclaim (2026-11-05): the OUTGOING key's registered
        // event connection goes with the switch, guarded by the SAME rules
        // the engine stop applies - legacy never revokes, and a run in
        // flight defers the revocation to reportRunInFlight(key,false) so a
        // turn's stream is not cut. The defer/read chain rides the same
        // engineRunInFlightFor flag the engine stop reads, so the two guards
        // can never disagree about an instance being abandoned.
        if (oldKey.isNotBlank()) {
            connectionRegistry.revoke(oldKey, "instance switched away") {
                engineRunInFlightFor == oldKey
            }
        }
    }

    /**
     * Projects-surface removal support (2026-11-04 import-only decision,
     * user): the engine-side half of dropping a project row. What it means
     * for ENGINES only - row/filesystem deletion lives in the store/VM:
     *
     * - If the row IS the active project's row, this is exactly a switch to
     *   the legacy workspace and reuses [setActiveProject]'s switch contract
     *   unchanged (stop the outgoing engine unless a run is in flight on
     *   it; never stop legacy).
     * - For a background row, its engine instance (if ever booted) is
     *   stopped outright under the same no-run-in-flight rule; a run in
     *   flight simply skips the stop and the instance rides its linger -
     *   the VM refuses the whole removal while that flag is set anyway.
     */
    fun forgetProjectEngine(id: java.util.UUID) {
        val key = instanceKeyFor(id)
        if (activeProjectRef?.id == id) {
            setActiveProject(null)
            return
        }
        if (engineRunInFlightFor != key) {
            com.crush.mobile.util.AppLog.i(
                "Lifecycle",
                "stopping project engine for instance '$key' (project removed)"
            )
            // §1 lookup-or-NOOP (2026-11-05): a row that was never opened
            // has NO registered runtime, and today's runtimeFor(key) built a
            // throwaway engine pair just to stop it - the design memo's
            // flagged corner. The registry's stop is a lookup: unregistered
            // key, nothing to stop, nothing constructed.
            engineRegistry.stop(key, "project removed")
        }
        // §3: the row's event connection reclaim carries the SAME guard -
        // a run in flight defers it to reportRunInFlight(key,false).
        connectionRegistry.revoke(key, "project removed") {
            engineRunInFlightFor == key
        }
    }

    /**
     * The engine state of WHATEVER instance the chat layer is bound to, in one
     * flow. Lazily started so the legacy supervisor's construction (and its
     * tuning collector) keeps today's timing: on first chat render, not at
     * container construction.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val engineStateForActiveInstance: StateFlow<EngineState> =
        _activeInstanceKey.flatMapLatest { key ->
            runtimeFor(key).supervisor.state
        }.stateIn(
            containerScope,
            kotlinx.coroutines.flow.SharingStarted.Lazily,
            EngineState.Stopped
        )

    /** Full runtime for an instance key; "" returns the legacy singleton. */
    fun runtimeFor(instanceKey: String): EngineRuntime {
        if (instanceKey.isBlank()) {
            return EngineRuntime("", crushEngine, engineSupervisor)
        }
        // §1 registry (2026-11-05): per-key idempotency + never-evict live
        // here; construction happens at most once per key per process, under
        // the registry's lock (the same serialization the old
        // `synchronized(runtimeLock)` gave the single slot).
        return engineRegistry.runtimeFor(instanceKey) { buildKeyedRuntime(instanceKey) }
    }

    /**
     * The keyed construction, exactly the old [runtimeFor] body. [build]
     * for the registry runs under its lock, so it must stay synchronous
     * and fast: tuning resolve, engine object, supervisor, flush hook.
     */
    private fun buildKeyedRuntime(instanceKey: String): EngineRuntime {
        val resolvedTuning = TuningPolicy.resolve(deviceProfile, TuningPolicy.Overrides())
        val engine = CrushEngine(appContext, instanceKey, resolvedTuning.engineIdleTimeoutSec)
        val supervisor = EngineSupervisor(
            engine,
            containerScope,
            tuning = resolvedTuning,
            appContext = appContext,
            instanceKey = instanceKey
        ).also { s ->
            // P1-11 flush for THIS instance's restarts - the same
            // registration the legacy singleton carries (see its .also
            // block above for why the whole generation counter exists).
            // §1 (2026-11-05): the registry's RUNNING grain rides the SAME
            // hook - a successful new run marks the key, exactly once per
            // start, without a second observer loop anywhere.
            s.onEngineStart {
                WorkspaceManager.onEngineRestarted()
                engineRegistry.recordRunning(instanceKey)
            }
        }
        return EngineRuntime(instanceKey, engine, supervisor)
    }

    /**
     * §1 liveness-budget gate (2026-11-05): returns the fail-closed refusal
     * reason, or null when the start may proceed. The chat layer consults
     * this BEFORE every supervisor.start() and surfaces a refusal to the
     * user the same shape an EngineState.Failed gets ("engine failed: ..."),
     * because the state flow itself is supervisor-owned and deliberately
     * not writable from container side.
     */
    fun requestEngineStart(instanceKey: String): String? = engineRegistry.requestStart(instanceKey)

    private val _keyAvailable = MutableStateFlow(0)

    /** Bumped every time the app is successfully unlocked.
     *
     * ChatViewModel only re-runs bootstrap when the ENGINE state changes, so an
     * unlock could not reach it any other way. Without this signal a user who
     * unlocked correctly stayed on "no API key" until they killed the engine.
     * A counter rather than a boolean so two unlocks in the same frame cannot
     * be collapsed into one emission.
     */
    val keyAvailable: StateFlow<Int> = _keyAvailable.asStateFlow()

    fun notifyKeyAvailable() {
        _keyAvailable.value = _keyAvailable.value + 1
    }

    /**
     * Pushes resolved tuning to every consumer.
     *
     * Supervisor values are live (read each poll). The engine's idle linger is
     * baked into the process environment at launch, so that one applies on the
     * NEXT engine start - it cannot be changed on a running process.
     */
    private fun applyTuning(t: TuningPolicy.Resolved) {
        supervisorInstance?.tuning = t
        crushEngine.setIdleTimeoutSec(t.engineIdleTimeoutSec)
        // Per-instance runtimes - since §1 (2026-11-05) the fan-out is the
        // REGISTRY's every registered key, not a single slot: same rules,
        // same sources of truth, and a STOPPED-but-registered instance must
        // not lose settings (its next spawn bakes the idle linger, so a
        // missed fan-out there would resurrect an old timeout).
        engineRegistry.forEachRuntime { rt ->
            rt.supervisor.tuning = t
            rt.engine.setIdleTimeoutSec(t.engineIdleTimeoutSec)
        }
    }

    /**
     * Production teardown path (P1-10).
     *
     * Until today ONLY the spike harness (SpikeActivity) called
     * EngineSupervisor.stop(), so a UI-launched engine process outlived the UI
     * and kept binding the app-private socket for nothing. Android does not
     * guarantee Application.onTerminate() will ever be called (the process is
     * usually killed outright with no callback), so this hook ENABLES clean
     * teardown for the termination paths that do call back - it cannot
     * guarantee the OS honours it. That limitation is documented at the call
     * site in CrushMobileApp.onTerminate(); the honest goal of this fix is
     * "teardown exists and is wired", not "teardown is guaranteed".
     *
     * Only touches an ALREADY-MATERIALISED supervisor: constructing it here
     * would lazily build the engine, spawn the settings collector - and for an
     * app being torn down that is pure waste, plus the collector would race
     * stop() on the new instance.
     */
    fun shutdown() {
        val s = synchronized(this) { supervisorInstance }
        s?.stop()
    }
}
