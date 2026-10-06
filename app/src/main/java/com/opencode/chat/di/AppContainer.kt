package com.opencode.chat.di

import android.content.Context
import com.opencode.chat.data.local.KeySession
import com.opencode.chat.data.local.SettingsDataStore
import com.opencode.chat.data.repository.SettingsRepository
import com.opencode.chat.domain.model.DeviceProfile
import com.opencode.chat.domain.model.TuningPolicy
import com.opencode.chat.engine.CrushEngine
import com.opencode.chat.engine.EngineSupervisor
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
                    tuning = com.opencode.chat.domain.model.TuningPolicy.resolve(
                        deviceProfile, com.opencode.chat.domain.model.TuningPolicy.Overrides()
                    ),
                    appContext = appContext
                ).also { s ->
                supervisorInstance = s
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
}
