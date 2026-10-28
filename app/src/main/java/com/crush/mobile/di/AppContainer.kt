package com.crush.mobile.di

import android.content.Context
import com.crush.mobile.data.crush.WorkspaceManager
import com.crush.mobile.data.local.KeySession
import com.crush.mobile.data.local.LockState
import com.crush.mobile.data.local.SettingsDataStore
import com.crush.mobile.data.repository.SettingsRepository
import com.crush.mobile.domain.model.DeviceProfile
import com.crush.mobile.domain.model.TuningPolicy
import com.crush.mobile.engine.CrushEngine
import com.crush.mobile.engine.EngineSupervisor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _keyAvailable = MutableStateFlow(0)

    /**
     * Bumped every time the app is successfully unlocked.
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
