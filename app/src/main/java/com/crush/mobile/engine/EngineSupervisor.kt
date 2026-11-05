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
     * Initial runtime tuning, resolved from static auto-tuning plus any user
     * override (see TuningPolicy). This parameter only SEEDS the backing
     * field; every later write must go through the public [tuning] setter,
     * which is what makes a settings change visible to the pressure loop
     * without a process restart.
     */
    tuning: com.crush.mobile.domain.model.TuningPolicy.Resolved =
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
    private val appContext: android.content.Context? = null,
    /**
     * The engine instance key HEALTH PROBES address (S0/F03). The socket path
     * is per instance (`filesDir/crush-<key>.sock`); the shipped health probe
     * derived the single-arg legacy path for EVERY supervisor, so a keyed
     * instance was declared healthy-or-not by whatever happened to serve the
     * legacy socket - including our own stop-on-switch lifecycle.
     */
    private val instanceKey: String = ""
) {

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * The live policy value. The pressure loop writes this field DIRECTLY
     * (never through the [tuning] setter): a derived, pressure-adjusted value
     * must not masquerade as a user settings change - see [tuningRevision].
     */
    @Volatile
    private var tuningValue: com.crush.mobile.domain.model.TuningPolicy.Resolved = tuning

    /**
     * Monotonic revision of USER-supplied settings (audit SUP-01, round two).
     *
     * The previous detection compared the current `tuning` against both the
     * unadjusted base and the last derived value by EQUALITY. That cannot
     * separate three cases: (a) the user changed a value, (b) this loop wrote
     * its own pressure-adjusted derivative, (c) the user changed a value BACK
     * to what it was before pressure modified it - case (c) is invisible to
     * any equality check and was silently dropped.
     *
     * A revision counter has none of those blind spots: the ONLY writer is the
     * [tuning] setter, so every bump means some external caller (AppContainer,
     * from real settings) handed over a new base policy, whatever its value.
     *
     * Race note: the setter records the value first, then bumps the counter.
     * A poll that reads the old counter with the new value simply re-derives
     * from a base one poll older than it should be and corrects on the next
     * iteration; the reverse order (bump first) could read a NEW revision with
     * the OLD value and latch a stale base, so this order is deliberate.
     */
    private val tuningRevision = java.util.concurrent.atomic.AtomicLong()

    /**
     * Runtime tuning as seen by every reader (the poll loop reads the getter
     * for interval/grace values). Assigning from OUTSIDE the supervisor (the
     * user changed a setting) marks the new base as unabsorbed via
     * [tuningRevision]; the pressure loop never assigns here - see
     * [tuningValue].
     */
    var tuning: com.crush.mobile.domain.model.TuningPolicy.Resolved
        get() = tuningValue
        set(value) {
            tuningValue = value
            tuningRevision.incrementAndGet()
        }

    /**
     * Listeners notified once per SUCCESSFUL start of a NEW engine run, right
     * after the run reaches [EngineState.Running].
     *
     * Why this exists (P1-11): Crush mints a workspace id per engine process,
     * so a restart voids every id minted by the previous run. The engine does
     * not know about workspace ids and WorkspaceManager instances are created
     * outside the DI graph, so the supervisor - which knows exactly when a new
     * engine generation begins - exposes the moment and lets the DI wiring
     * (AppContainer) push it into whichever layer owns the ids.
     *
     * CopyOnWriteArrayList: listeners are registered once at DI time and never
     * removed; reads dominate writes. The re-entrant early-return path in
     * [CrushEngine.start] ("process already alive") deliberately does NOT
     * notify - that is the SAME run continuing, not a new engine generation.
     */
    private val startListeners =
        java.util.concurrent.CopyOnWriteArrayList<(EngineState.Running) -> Unit>()

    /** Registers a listener fired at every new engine run's [Running] moment. */
    fun onEngineStart(fn: (EngineState.Running) -> Unit) {
        startListeners.add(fn)
    }

    /** Listener exceptions must never kill the supervision loop. */
    private fun notifyEngineStart(running: EngineState.Running) {
        for (fn in startListeners) runCatching { fn(running) }
    }

    private var supervisorJob: Job? = null

    /** Guarded by [sync]; distinguishes an explicit stop from a crash restart. */
    private val sync = Any()
    private var wanted = false

    fun start() {
        synchronized(sync) {
            com.crush.mobile.util.AppLog.i(TAG, "[$instanceKey] start() activeJob=${supervisorJob?.isActive}")
            if (supervisorJob?.isActive == true) return
            wanted = true
            supervisorJob = scope.launch { supervise() }
        }
    }

    fun stop() {
        synchronized(sync) {
            com.crush.mobile.util.AppLog.i(TAG, "[$instanceKey] stop() requested")
            wanted = false
            supervisorJob?.cancel()
            supervisorJob = null
        }
        // Cancel the scope job, then tear down the process. Doing this in a
        // coroutine would let a starting engine outlive stop().
        engine.stopBlocking()
        _state.value = EngineState.Stopped
    }

    /**
     * A deliberate restart, requested by the app rather than by failed health
     * (decision item 4's restart-boundary contract: settings that need the
     * engine to re-read its config - provider key changes are currently
     * per-request, but a config-level change landing later rides THIS).
     *
     * Sequencing mirrors [stop] exactly - cancel the supervised loop FIRST,
     * then the synchronous stopBlocking, and only then re-launch the loop -
     * precisely so a starting engine can never outlive the stop. The wanted
     * flag stays true, which keeps after `[restart]` everything it was before
     * except a fresh engine process. A restart while the engine is already
     * down is a plain [start].
     */
    fun restart(reason: String) {
        val wasWanted = synchronized(sync) { wanted }
        if (!wasWanted) {
            Log.i(TAG, "restart($reason) ignored: supervisor not running")
            return
        }
        Log.i(TAG, "restart requested: $reason")
        synchronized(sync) {
            supervisorJob?.cancel()
            supervisorJob = null
            wanted = true
        }
        engine.stopBlocking()
        synchronized(sync) {
            supervisorJob = scope.launch { supervise() }
        }
    }

    private suspend fun supervise() {
        var attempt = 0
        // Wedge tracking (P1-25SUP): how many restarts in a row happened
        // WITHOUT the engine ever staying Running for MIN_HEALTHY_RUN_MS.
        // Dying instantly on every boot (bad socket file, hostile file state,
        // native crash at boot) without this cap produces an unbounded
        // restart loop at backoff pace forever.
        var wedgeRestarts = 0
        var runningSince = 0L
        // S5 wedge instrumentation (2026-11-05): the two overnight hangs ended
        // with the UI pinned on "engine stopped" while the CHILD process and
        // its socket were alive and fresh - meaning the loop never reached an
        // attempt or exited long before. System logcat was proven lossy for
        // our process lines, so every transition below ALSO goes to the
        // persistent app.log; the tag prefix keeps the two sinks greppable
        // apart.
        com.crush.mobile.util.AppLog.i(TAG, "[$instanceKey] supervise loop start")
        while (currentCoroutineContext().isActive && isWanted()) {
            attempt++
            _state.value = EngineState.Starting(attempt)
            val attemptStart = System.currentTimeMillis()
            com.crush.mobile.util.AppLog.d(
                TAG, "[$instanceKey] starting attempt $attempt"
            )

            when (val result = engine.start()) {
                is EngineState.Running -> {
                    com.crush.mobile.util.AppLog.i(
                        TAG,
                        "[$instanceKey] engine Running after " +
                            "${System.currentTimeMillis() - attemptStart}ms"
                    )
                    attempt = 0
                    wedgeRestarts = 0
                    runningSince = System.currentTimeMillis()
                    _state.value = result
                    notifyEngineStart(result)

                    // Poll inline. Returns true to restart, false to exit.
                    // Health runs over the same Unix domain socket the engine
                    // binds (see EngineSockets); result.port is a sentinel in
                    // UDS mode and is NOT used here.
                    val shouldRestart = monitorUntilUnhealthy(healthSocketFile())
                    if (!shouldRestart) {
                        com.crush.mobile.util.AppLog.i(
                            TAG, "[$instanceKey] supervision ended (not wanted)"
                        )
                        return
                    }

                    Log.i(TAG, "engine unhealthy; restarting")
                    com.crush.mobile.util.AppLog.w(
                        TAG, "[$instanceKey] engine unhealthy; restarting"
                    )
                    // Wedge cap: a run that never lasted MIN_HEALTHY_RUN_MS is
                    // a boot-phase failure, not a regression under load. After
                    // MAX_WEDGE_RESTARTS of them in a row, give up - the user
                    // gets an honest terminal state instead of a battery-drain
                    // churn cycle. A run that DID last the stretch resets the
                    // count, so one bad boot after weeks of healthy use
                    // restarts with a full budget.
                    val ranMs = System.currentTimeMillis() - runningSince
                    if (ranMs < MIN_HEALTHY_RUN_MS) {
                        wedgeRestarts++
                        if (wedgeBudgetExhausted(wedgeRestarts)) {
                            Log.e(
                                TAG,
                                "engine wedged $wedgeRestarts times in a row without ever " +
                                    "staying Running ${MIN_HEALTHY_RUN_MS / 1000}s; giving up"
                            )
                            com.crush.mobile.util.AppLog.e(TAG,
                                "[$instanceKey] engine wedged $wedgeRestarts times in a row " +
                                    "without ever staying Running " +
                                    "${MIN_HEALTHY_RUN_MS / 1000}s; giving up"
                            )
                            engine.stopBlocking()
                            _state.value = EngineState.Failed(
                                reason = "Engine repeatedly failed to stay running",
                                detail = "$wedgeRestarts rapid restarts in a row, none lasting " +
                                    "${MIN_HEALTHY_RUN_MS / 1000}s",
                                willRetry = false
                            )
                            return
                        }
                    } else {
                        wedgeRestarts = 0
                    }
                    engine.stopBlocking()
                    delay(RESTART_DELAY_MS)
                }

                is EngineState.Failed -> {
                    com.crush.mobile.util.AppLog.w(
                        TAG,
                        "[$instanceKey] start Failed (retryable=${result.willRetry}) after " +
                            "${System.currentTimeMillis() - attemptStart}ms, attempt=$attempt: " +
                            result.reason
                    )
                    // P1-10: a cold boot on a slow device can legitimately
                    // exceed the engine's 60s readiness window, and CrushEngine
                    // now classifies both that timeout and an early exit as
                    // RETRYABLE (stale socket files are the most common cause
                    // of the latter, and the engine removes its own stale
                    // socket before listening). The budget lives HERE so the
                    // retirable classification can never become an infinite
                    // loop: MAX_START_ATTEMPTS consecutive failures then a real
                    // terminal Failed(willRetry=false).
                    if (startRetryGivesUp(attempt, result.willRetry)) {
                        Log.e(
                            TAG,
                            "startup failed permanently after $attempt attempts " +
                                "(budget $MAX_START_ATTEMPTS): ${result.reason}"
                        )
                        com.crush.mobile.util.AppLog.e(TAG,
                            "[$instanceKey] startup failed permanently after $attempt attempts " +
                                "(budget $MAX_START_ATTEMPTS): ${result.reason}"
                        )
                        // Report through the same state the caller would have
                        // seen; only the retry promise flips, so the UI shows
                        // a terminal error instead of an eternal spinner.
                        _state.value = if (result.willRetry) result.copy(willRetry = false) else result
                        return
                    }
                    _state.value = result
                    delay(backoffMs(attempt))
                }

                EngineState.Stopped, is EngineState.Starting -> Unit
            }
        }
        com.crush.mobile.util.AppLog.i(
            TAG, "[$instanceKey] supervise loop exit (wanted=${isWanted()})"
        )
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
 * alive-but-failing engine must persist for the configured hang grace
 * (tuning.hangGraceSec), and [isWorkActive]
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
        // SUP-01, mechanism switched to a REVISION counter: the equality pair
        // `tuning != desiredBase && tuning != lastEffective` could not tell a
        // real user settings change from the loop's own derived write, and
        // missed a user changing a value back to a previously-derived value
        // entirely. Now the public [tuning] setter is the only writer that
        // bumps [tuningRevision]; any counter value newer than the one we last
        // absorbed IS an external settings change, whatever the values say.
        // Seeded at loop entry so user changes that landed while the engine
        // was already healthy and merely entered monitoring are absorbed, not
        // treated as "something to re-derive" over and over.
        var absorbedRevision = tuningRevision.get()
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
                    // A settings change is now detected by the REVISION bump on
                    // [tuning], not by object equality (above). Absorb any
                    // unabsorbed external change into the unadjusted base.
                    val currentRevision = tuningRevision.get()
                    if (currentRevision != absorbedRevision) {
                        absorbedRevision = currentRevision
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
                    if (effective != pressure || derived != tuningValue) {
                        pressure = effective
                        // Direct FIELD write, deliberately not `tuning =`:
                        // routing a derived value through the public setter
                        // would bump the revision counter, and the next poll
                        // would read our own derivative as a "user settings
                        // change" - re-deriving from an already pressure-shrunk
                        // base, which is exactly the original SUP-01 defect.
                        tuningValue = derived
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
        appContext?.let { EngineSockets.socketPath(it, instanceKey) }

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
     * patient, and its escalation is bounded by the configured tuning hang
     * grace (see monitorUntilUnhealthy) anyway.
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

    /**
     * Companion is deliberately NOT private: SupervisorHysteresisTest pins the
     * pure give-up rules (startRetryGivesUp / wedgeBudgetExhausted) through
     * the companion's internal members, and keeping the companion unlocked
     * removes any ambiguity about whether a private companion gates external
     * member access to them.
     */
    companion object {
        private const val TAG = "EngineSupervisor"

        /**
         * Applies current pressure to the UNADJUSTED base policy.
         *
         * Kept as a named function so the "derive, never mutate" rule has one
         * place it lives. Every call must pass the resolved user/auto base, not
         * a previously pressure-adjusted value - that mistake (audit SUP-01)
         * compounded the reduction until it hit the floor and stuck there.
         */
        private fun effectiveTuningFor(
            base: com.crush.mobile.domain.model.TuningPolicy.Resolved,
            signals: com.crush.mobile.domain.model.RuntimeSignals,
            pressure: com.crush.mobile.domain.model.PressureLevel
        ): com.crush.mobile.domain.model.TuningPolicy.Resolved =
            com.crush.mobile.domain.model.DynamicPolicy.applySafely(base, signals, pressure)
        /**
         * The dead legacy constants HANG_GRACE_MS / HEALTH_INTERVAL_MS are DELETED
         * (P1-25SUP): since tuning moved to TuningPolicy, live values come from
         * [tuning].healthIntervalSec / [tuning].hangGraceSec and these copies of the
         * numbers were referenced by nothing - a second thing anyone editing the
         * grace had to remember to update, and a lie waiting to be trusted.
         */
        const val RESTART_DELAY_MS = 1_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        /**
         * Bounded START-failure budget (P1-10): how many consecutive
         * Failed(willRetry=true) results the loop still honours before it
         * reports a terminal failure. attempt counts FAILURES: attempts 1..3
         * retry with the existing backoff; a 4th consecutive failure gives
         * up. Reset to 0 by every successful start.
         */
        const val MAX_START_ATTEMPTS = 3

        /**
         * Wedge-restart cap (P1-25SUP): how many consecutive restarts of a
         * run that never survived [MIN_HEALTHY_RUN_MS] the loop performs
         * before reporting a terminal failure.
         */
        const val MAX_WEDGE_RESTARTS = 5

        /**
         * The minimum Running stretch that counts as a non-wedged life.
         * Aligned with the old 60s hang-grace unit: an engine that served
         * traffic for a minute and THEN died is a separate diagnosis with a
         * full wedge budget again; an engine that cannot boot into a minute
         * of life five times in a row is not going to.
         */
        const val MIN_HEALTHY_RUN_MS = 60_000L

        /**
         * Pure give-up rule for the START budget, split out so SupervisorHysteresisTest
         * can pin the boundary without spawning an engine process. `attempt` is the
         * count of CONSECUTIVE failures (the supervisor zeroes it on every
         * successful start).
         */
        internal fun startRetryGivesUp(attempt: Int, failureWantsRetry: Boolean): Boolean =
            !failureWantsRetry || attempt > MAX_START_ATTEMPTS

        /**
         * Pure wedge rule, same reason: an engine that skipped a full healthy
         * stretch on this many consecutive starts is not going to boot.
         */
        internal fun wedgeBudgetExhausted(consecutiveShortRuns: Int): Boolean =
            consecutiveShortRuns >= MAX_WEDGE_RESTARTS
    }
}
