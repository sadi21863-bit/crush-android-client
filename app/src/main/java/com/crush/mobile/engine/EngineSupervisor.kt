package com.crush.mobile.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
    @Volatile var tuning: com.crush.mobile.domain.model.TuningPolicy.Resolved =
        com.crush.mobile.domain.model.TuningPolicy.resolve(
            com.crush.mobile.domain.model.DeviceProfile(
                apiLevel = android.os.Build.VERSION.SDK_INT,
                totalRamMb = 0, availableRamMb = 0, cores = 1,
                isLowRamDevice = false, hasStrongBox = false,
                hasSecureLockScreen = false, supportsModernAuthGate = false
            ),
            com.crush.mobile.domain.model.TuningPolicy.Overrides()
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
                    // Health runs over the same Unix domain socket the engine
                    // binds (see EngineSockets); result.port is a sentinel in
                    // UDS mode and is NOT used here.
                    val shouldRestart = monitorUntilUnhealthy(healthSocketFile())
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
 *     detect this, so HTTP (here: over the Unix domain socket) is the only
 *     available signal. Restart, because nothing
 *     short of killing it recovers a wedged Go server.
 *
 * HTTP is NOT authoritative on its own: Crush holds one HTTP connection open for
 * the workspace SSE stream, so while a stream or agent run is active the server
 * can be slow to answer /v1/version. Treating a single slow probe as "dead" tore
 * down healthy engines mid-session and invalidated the held workspace id. So an
 * alive-but-failing engine must persist for [HANG_GRACE_MS], and [isWorkActive]
 * suspends that judgement entirely.
 *
 * [socketFile] may be null when the supervisor was built without an application
 * context (see [probeVersion]); hang detection then degrades to process
 * liveness only, and is treated as never failing rather than always failing.
 */
private suspend fun monitorUntilUnhealthy(socketFile: File?): Boolean {
        var httpFailures = 0
        var firstFailureAt = 0L
        var calmRun = 0
        var pressure = com.crush.mobile.domain.model.PressureLevel.OK
        // Captured so a pressure change can be recomputed from the SAME base
        // rather than compounding on top of an already-shrunk value. Without this,
        // repeated CRITICAL samples would divide the idle timeout down to the
        // floor and it could never recover.
        // The USER/AUTO-resolved policy, before any pressure adjustment.
        //
        // CORRECTION (audit SUP-01): the comment here already claimed this was
        // captured "so a pressure change can be recomputed from the SAME base",
        // but the code below assigned the pressure-adjusted RESULT back into
        // this variable. Repeated CRITICAL samples therefore divided the idle
        // timeout repeatedly and it could never recover - and a later settings
        // change was compared against, and overwritten by, a stale shrunk base.
        //
        // `desiredBase` is now only ever written when the effective policy
        // changes, so it always holds the unadjusted user resolution.
        var desiredBase = tuning
        // The last value actually derived from desiredBase under pressure. Used
        // only to distinguish "settings changed" from "we adjusted it ourselves".
        var lastEffective = tuning
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
                    com.crush.mobile.domain.model.RuntimeSignals.sample(
                        ctx, isForeground = true, workActive = workActive
                    )
                }.getOrNull()
                if (signals != null) {
                    // A settings change must update the BASE, never be compared
                    // against a previously pressure-shrunk value.
                    if (tuning != desiredBase && tuning != lastEffective) {
                        desiredBase = tuning
                    }
                    val observed = com.crush.mobile.domain.model.DynamicPolicy.pressureOf(signals)
                    // Hysteresis: escalate at once, relax only after sustained calm.
                    // DynamicPolicy.next() compares `calmRun + 1 >= 3`, so it expects the count
                    // from BEFORE this sample. The caller used to increment first
                    // and pass the new value, so the +1 was applied twice and the
                    // policy relaxed after TWO calm samples instead of three -
                    // a device throttling during a short pause would shed its idle
                    // linger a full sample early. The pure unit tests never
                    // caught it because they pass a pre-incremented count
                    // directly and so encode the same contract correctly.
                    val priorCalm = calmRun
                    calmRun = if (observed == com.crush.mobile.domain.model.PressureLevel.OK) calmRun + 1 else 0
                    val effective = com.crush.mobile.domain.model.DynamicPolicy.next(
                        pressure, observed, priorCalm
                    )
                    // Derived fresh from the UNADJUSTED base on every poll, never
                    // accumulated. This is the fix for audit SUP-01: the old code
                    // assigned the pressure-adjusted result back into the base, so
                    // repeated CRITICAL samples divided the idle timeout down to
                    // the floor where it stuck permanently.
                    val derived = effectiveTuningFor(desiredBase, signals, effective)
                    if (effective != pressure || derived != tuning) {
                        pressure = effective
                        tuning = derived
                        lastEffective = derived
                        Log.i(TAG, "dynamic tuning: pressure=$effective idle=${derived.engineIdleTimeoutSec}s")
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

            if (probeVersion(socketFile)) {
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
     * The socket file the health probes run over, or null when the supervisor
     * was built without an application context (see [probeVersion]). Derived
     * from [EngineSockets.socketPath] - the engine binds the UDS at exactly
     * this path, and the old TCP port is gone (EngineState.Running.port is a
     * sentinel now and must never be probed).
     */
    private fun healthSocketFile(): File? =
        appContext?.let { EngineSockets.socketPath(it) }

    /**
     * Health probe over the Unix domain socket (see [EngineSockets]).
     *
     * Returns true when the engine answers /v1/version with 2xx; false counts
     * as a probe failure exactly as the previous TCP
     * `HttpURLConnection` probe did. [EngineSockets.probe] dispatches to the
     * IO dispatcher internally and never throws - connect and timeout
     * failures surface as null - so this is safe to call from the
     * Main-dispatched supervisor loop. The old blocking HttpURLConnection
     * version had to wrap itself in withContext(IO), because on the main
     * thread it threw NetworkOnMainThreadException, which was swallowed as
     * "engine unhealthy" and tore down a perfectly healthy engine roughly
     * every 100s.
     *
     * [socketFile] is null only for construction sites without an application
     * context (none exist today; AppContainer always passes one). There is
     * then NO transport to probe, so report healthy and rely on process
     * liveness alone - treating "cannot probe" as failure would restart a
     * healthy engine on every poll.
     *
     * The 5s connect/read timeouts are preserved from the previous probe
     * rather than EngineSockets' 1.2s defaults: hang detection must stay
     * patient, and its escalation is bounded by [HANG_GRACE_MS] anyway.
     */
    private suspend fun probeVersion(socketFile: File?): Boolean {
        if (socketFile == null) return true
        return EngineSockets.probe(
            socketFile,
            "/v1/version",
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000
        ) != null
    }

    private fun backoffMs(attempt: Int): Long =
        (INITIAL_BACKOFF_MS shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val TAG = "EngineSupervisor"

        /**
         * Applies current pressure to the UNADJUSTED base policy.
         *
         * Kept as a named function so the "derive, never mutate" rule has one
         * place it lives. Every call must pass the resolved user/auto base, not
         * a previously pressure-adjusted value - that mistake (audit SUP-01)
         * compounded the reduction until it hit the floor and stuck there.
         */
        fun effectiveTuningFor(
            base: com.crush.mobile.domain.model.TuningPolicy.Resolved,
            signals: com.crush.mobile.domain.model.RuntimeSignals,
            pressure: com.crush.mobile.domain.model.PressureLevel
        ): com.crush.mobile.domain.model.TuningPolicy.Resolved =
            com.crush.mobile.domain.model.DynamicPolicy.applySafely(base, signals, pressure)
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
