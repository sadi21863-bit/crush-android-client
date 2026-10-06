package com.opencode.chat.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the engine alive for the lifetime of the UI.
 *
 * The engine holds no state the app cannot rebuild (sessions live in its own
 * files), so running it while the UI is closed buys nothing and would require
 * a foreground service plus process-death recovery. It is started on demand
 * and torn down with the app.
 *
 * This is a single supervised loop: start the engine, poll its health inline,
 * and only restart when health actually fails. An earlier version used a
 * fire-and-forget health job, which meant the loop immediately "restarted" on
 * every iteration and spawned a new engine roughly every second.
 */
class EngineSupervisor(
    private val engine: CrushEngine,
    private val scope: CoroutineScope,
    /**
     * Runtime tuning, resolved from static auto-tuning plus any user override
     * (see TuningPolicy). Mutable so a settings change takes effect without a
     * process restart.
     */
    @Volatile var tuning: com.opencode.chat.domain.model.TuningPolicy.Resolved =
        com.opencode.chat.domain.model.TuningPolicy.resolve(
            com.opencode.chat.domain.model.DeviceProfile(
                apiLevel = android.os.Build.VERSION.SDK_INT,
                totalRamMb = 0, availableRamMb = 0, cores = 1,
                isLowRamDevice = false, hasStrongBox = false,
                hasSecureLockScreen = false, supportsModernAuthGate = false
            ),
            com.opencode.chat.domain.model.TuningPolicy.Overrides()
        ),
    /**
     * Application context, used only to sample thermal / memory / power state
     * for dynamic tuning. Application-scoped because the supervisor is
     * process-scoped and must never pin an Activity.
     *
     * Nullable so existing construction sites still compile; without it the
     * supervisor simply skips dynamic tuning and keeps the static profile.
     */
    private val appContext: android.content.Context? = null
) {

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var supervisorJob: Job? = null

    /** Guarded by [sync]; distinguishes an explicit stop from a crash restart. */
    private val sync = Any()
    private var wanted = false

    fun start() {
        synchronized(sync) {
            if (supervisorJob?.isActive == true) return
            wanted = true
            supervisorJob = scope.launch { supervise() }
        }
    }

    fun stop() {
        synchronized(sync) {
            wanted = false
            supervisorJob?.cancel()
            supervisorJob = null
        }
        // Cancel the scope job, then tear down the process. Doing this in a
        // coroutine would let a starting engine outlive stop().
        engine.stopBlocking()
        _state.value = EngineState.Stopped
    }

    private suspend fun supervise() {
        var attempt = 0
        while (currentCoroutineContext().isActive && isWanted()) {
            attempt++
            _state.value = EngineState.Starting(attempt)

            when (val result = engine.start()) {
                is EngineState.Running -> {
                    attempt = 0
                    _state.value = result

                    // Poll inline. Returns true to restart, false to exit.
                    val shouldRestart = monitorUntilUnhealthy(result.port)
                    if (!shouldRestart) return

                    Log.i(TAG, "engine unhealthy; restarting")
                    engine.stopBlocking()
                    delay(RESTART_DELAY_MS)
                }

                is EngineState.Failed -> {
                    _state.value = result
                    // A non-retryable failure (missing binary, exec denied) will
                    // never succeed on retry, so surface it and stop.
                    if (!result.willRetry) return
                    delay(backoffMs(attempt))
                }

                EngineState.Stopped, is EngineState.Starting -> Unit
            }
        }
    }

/**
     * Decides whether the engine is genuinely gone.
     *
     * The OS-level liveness of the child process is authoritative. HTTP health
     * is NOT: Crush holds one HTTP connection open for the workspace SSE
     * stream, so while a stream (or an agent run) is active the server can be
     * slow to answer /v1/version. Treating that as "dead" caused the engine to
     * be torn down mid-session, which silently invalidated the workspace id the
     * UI was holding.
     */
    /**
     * Decides whether the engine is genuinely gone OR wedged.
 *
     * Two independent failure modes needing opposite handling:
 *
      1. The process disappeared (OOM kill, native crash, idle self-exit). The OS
         process table is authoritative: restart immediately.
 *
 *  2. The process is alive but not answering HTTP - a real hang. Liveness cannot
 *     detect this, so HTTP is the only available signal. Restart, because nothing
 *     short of killing it recovers a wedged Go server.
 *
 * HTTP is NOT authoritative on its own: Crush holds one HTTP connection open for
 * the workspace SSE stream, so while a stream or agent run is active the server
 * can be slow to answer /v1/version. Treating a single slow probe as "dead" tore
 * down healthy engines mid-session and invalidated the held workspace id. So an
 * alive-but-failing engine must persist for [HANG_GRACE_MS], and [isWorkActive]
 * suspends that judgement entirely.
 */
private suspend fun monitorUntilUnhealthy(port: Int): Boolean {
        var httpFailures = 0
        var firstFailureAt = 0L
        var calmRun = 0
        var pressure = com.opencode.chat.domain.model.PressureLevel.OK
        // Captured so a pressure change can be recomputed from the SAME base
        // rather than compounding on top of an already-shrunk value. Without this,
        // repeated CRITICAL samples would divide the idle timeout down to the
        // floor and it could never recover.
        var baseTuning = tuning
        while (currentCoroutineContext().isActive && isWanted()) {
            delay(tuning.healthIntervalSec * 1000L)

            // DYNAMIC auto-tuning. This is the intended caller of DynamicPolicy -
            // the supervisor's poll is the only place that already runs on a
            // timer with an accurate view of whether the engine is busy.
            //
            // It was written, and unit-tested, with a KDoc claiming it was called
            // from here. It was not, so 20 tests guarded dead code while the
            // phone could be thermally throttling with nothing reacting.
            //
            // The workActive guard is inside applySafely and is the whole point:
            // under pressure we shed the engine's idle linger, but never while a
            // stream is live, because restarting Crush mid-stream is how a reply
            // silently vanished.
            // Bound to a local because a nullable property cannot be smart-cast
            // into the lambda below.
            val ctx = appContext
            if (ctx != null) {
                val signals = runCatching {
                    com.opencode.chat.domain.model.RuntimeSignals.sample(
                        ctx, isForeground = true, workActive = workActive
                    )
                }.getOrNull()
                if (signals != null) {
                    val observed = com.opencode.chat.domain.model.DynamicPolicy.pressureOf(signals)
                    // Hysteresis: escalate at once, relax only after sustained calm.
                    calmRun = if (observed == com.opencode.chat.domain.model.PressureLevel.OK) calmRun + 1 else 0
                    val effective = com.opencode.chat.domain.model.DynamicPolicy.next(
                        pressure, observed, calmRun
                    )
                    if (effective != pressure || tuning != baseTuning) {
                        pressure = effective
                        baseTuning = com.opencode.chat.domain.model.DynamicPolicy.applySafely(
                            baseTuning, signals, effective
                        )
                        // Only AUTO-sourced values move under pressure, so a user
                        // override is never silently rewritten.
                        tuning = baseTuning
                        Log.i(TAG, "dynamic tuning: pressure=$effective idle=${baseTuning.engineIdleTimeoutSec}s")
                    }
                }
            }

            // Something is actively using the engine. A slow server is expected
            // under load and is not a hang.
            if (isWorkActive()) {
                httpFailures = 0
                firstFailureAt = 0L
                continue
            }

            if (probeVersion(port)) {
                httpFailures = 0
                firstFailureAt = 0L
                continue
            }

            httpFailures++
            val now = android.os.SystemClock.elapsedRealtime()
            if (firstFailureAt == 0L) firstFailureAt = now

            if (!engine.isAlive()) {
                Log.w(TAG, "process is dead after $httpFailures failed probes; restarting")
                return true
            }

            val hungFor = now - firstFailureAt
            if (hungFor >= tuning.hangGraceSec * 1000L) {
                Log.w(TAG, "process alive but unresponsive for ${hungFor}ms; restarting")
                return true
            }

            Log.w(TAG, "http probe failed x$httpFailures (alive, ${hungFor}ms); not restarting yet")
        }
        return false
    }

    /** Set while a prompt streams or another long request is in flight. */
    @Volatile
    private var workActive = false

    /**
     * Fault-injection hook. SIGKILLs the engine from inside the owning process,
     * which is the only way to simulate a crash on Android (adb cannot signal
     * app-owned processes). The supervisor loop must notice and respawn it.
     */
    fun killEngineForFaultInjection(): Boolean = engine.killForFaultInjection()

    fun setWorkActive(active: Boolean) {
        workActive = active
    }

    private fun isWorkActive(): Boolean = workActive

    private fun isWanted(): Boolean = synchronized(sync) { wanted }

    /**
     * MUST run off the main thread.
     *
     * The supervisor's scope is the composition scope (Main dispatcher), so a
     * blocking HttpURLConnection here threw NetworkOnMainThreadException on
     * every probe. That was swallowed as "engine unhealthy" and caused the
     * supervisor to tear down a perfectly healthy engine roughly every 100s.
     */
    private suspend fun probeVersion(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("http://127.0.0.1:$port/v1/version").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun backoffMs(attempt: Int): Long =
        (INITIAL_BACKOFF_MS shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val TAG = "EngineSupervisor"
        /**
         * Legacy defaults, kept only as the pre-tuning fallback. Live values
         * come from [tuning]. At 10s per probe a 60s grace is ~6 consecutive
         * failures: long enough that a busy engine is never killed mid-turn,
         * short enough that a real deadlock resolves within a minute.
         */
        const val HANG_GRACE_MS = 60_000L
        const val HEALTH_INTERVAL_MS = 10_000L

        const val RESTART_DELAY_MS = 1_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}