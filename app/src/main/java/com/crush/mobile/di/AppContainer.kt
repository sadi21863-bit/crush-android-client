package com.crush.mobile.di

import android.content.Context
import com.crush.mobile.data.crush.WorkspaceManager
import com.crush.mobile.data.local.KeySession
import com.crush.mobile.data.local.LockState
import com.crush.mobile.data.local.SettingsDataStore
import com.crush.mobile.data.project.RoomProjectStore
import com.crush.mobile.data.repository.SettingsRepository
import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.TuningPolicy
import com.crush.mobile.engine.CrushEngine
import com.crush.mobile.engine.EngineState
import com.crush.mobile.engine.EngineSupervisor
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

    private var projectRuntime: EngineRuntime? = null
    private val runtimeLock = Any()

    /**
     * The engine instance the chat layer is bound to: "" = the legacy
     * workspace, "p<uuid8>" = a project row (set ONLY by the Projects surface
     * through [setActiveProject]; the chat ViewModel reads it, never writes).
     * Explanation of the project's workspace path: [activeProjectRef].
     */
    private val _activeInstanceKey = MutableStateFlow("")
    val activeInstanceKey: StateFlow<String> = _activeInstanceKey.asStateFlow()

    @Volatile
    var activeProjectRef: com.crush.mobile.project.ProjectRef? = null
        private set

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
     *  its own linger) instead of erasing a different key's signal. */
    fun reportRunInFlight(instanceKey: String, inFlight: Boolean) {
        if (inFlight) engineRunInFlightFor = instanceKey
        else if (engineRunInFlightFor == instanceKey) engineRunInFlightFor = null
    }

    /** Projects-surface entry point: null opens the legacy workspace. */
    fun setActiveProject(ref: com.crush.mobile.project.ProjectRef?) {
        val oldKey = _activeInstanceKey.value
        val newKey = if (ref == null) "" else instanceKeyFor(ref.id)
        activeProjectRef = ref
        if (newKey == oldKey) return
        _activeInstanceKey.value = newKey
        // Stop the OUTGOING PROJECT engine on the switch (ADR-0001 lifecycle
        // polish, 2026-11-04). This lives HERE and NOT in the chat
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
        // survives every switch, and pre-dates per-instance engines.
        if (oldKey.isNotBlank() && engineRunInFlightFor != oldKey) {
            com.crush.mobile.util.AppLog.i(
                "Lifecycle",
                "stopping project engine for instance '$oldKey' (no run in flight)"
            )
            synchronized(runtimeLock) { runtimeFor(oldKey).supervisor.stop() }
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
        synchronized(runtimeLock) {
            projectRuntime?.takeIf { it.instanceKey == instanceKey }?.let { return it }
            val resolvedTuning = TuningPolicy.resolve(deviceProfile, TuningPolicy.Overrides())
            val engine = CrushEngine(appContext, instanceKey, resolvedTuning.engineIdleTimeoutSec)
            val supervisor = EngineSupervisor(
                engine,
                containerScope,
                tuning = resolvedTuning,
                appContext = appContext
            ).also { s ->
                // P1-11 flush for THIS instance's restarts - the same
                // registration the legacy singleton carries (see its .also
                // block above for why the whole generation counter exists).
                s.onEngineStart { WorkspaceManager.onEngineRestarted() }
            }
            val runtime = EngineRuntime(instanceKey, engine, supervisor)
            projectRuntime = runtime
            return runtime
        }
    }

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
        // Per-project runtimes (slice 2): same rules, same sources of truth.
        projectRuntime?.let { rt ->
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
