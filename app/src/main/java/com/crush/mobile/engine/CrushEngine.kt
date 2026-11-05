package com.crush.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Owns the embedded Crush engine process.
 *
 * The binary is a statically linked aarch64 Go executable shipped as
 * jniLibs/arm64-v8a/libcrush.so. It must be exec'd from nativeLibraryDir:
 * Android 10+ blocks execve() from the app home directory (SELinux W^X), so
 * extracting it to filesDir instead does not work.
 *
 * Engine state (sessions, messages, config) lives in its own files under
 * [EnginePaths], so a process that dies with the UI costs nothing.
 *
 * Transport (P1, audit F01): the engine binds `-H unix://<socketPath>` - a
 * Unix domain socket in the app-private files directory - instead of a TCP
 * port. Kernel file permissions on that socket file are the whole
 * authentication story; see [EngineSockets] for the client side.
 */
/**
 * Converts a reflective `Process.pid()` result to a pid, or -1.
 *
 * `Process.pid()` returns a **`long`**, so reflection hands back a
 * `java.lang.Long`. The previous code did `as? Int`, which can never succeed on
 * a boxed Long, so it returned -1 on every platform that has `pid()` - and
 * diagnostics then reported "engine ready pid=-1" for a perfectly healthy
 * engine, while diagnostics, fault injection and the chaos scripts all worked
 * from a fabricated value.
 *
 * Top-level rather than a member so it is directly testable without spawning a
 * process.
 */
internal fun pidFromReflective(raw: Any?): Int {
    val n = raw as? Number ?: return -1
    val l = n.toLong()
    return if (l > 0 && l <= Int.MAX_VALUE) l.toInt() else -1
}

/**
 * Port value written into [EngineState.Running] since the UDS transport pivot.
 *
 * There is NO TCP port any more: the engine binds a Unix domain socket and
 * the URL port is meaningless. But every consumer that builds engine URLs
 * (`http://127.0.0.1:<port>/v1/...`) from [EngineState.Running.port] keeps
 * compiling byte-for-byte, because 1 is a syntactically valid port and the
 * OkHttp AF_UNIX socket factory (see [EngineSockets]) ignores host and port
 * entirely and connects to the socket file. Never treat this value as a real
 * port; never compute a TCP port again.
 */
internal const val UDS_PORT_SENTINEL: Int = 1

class CrushEngine(
    /** Engine-owned paths; internal so the chat layer reads (never rebuilds) them. */
    internal val paths: EnginePaths,
    /**
     * Application context, needed to resolve the Unix-domain-socket path the
     * engine binds ([EngineSockets.socketPath]). AppContainer passes the
     * application context, so the engine never pins an Activity.
     */
    private val appContext: Context,
    /**
     * Per-instance key (ADR-0001 amendment, 2026-11-03): "" is the
     * production engine (socket at filesDir/crush.sock, legacy engine state
     * under the filesDir/crush tree). A non-empty key isolates THIS child's
     * socket (crush-<key>.sock) so each project can host its own engine
     * process with its own session pool. Callers must pair it with an
     * [EnginePaths] built with the same key.
     */
    private val instanceKey: String = ""
) {

    /** Engine idle linger, in seconds, from static auto-tuning. */
    constructor(context: Context, idleTimeoutSec: Int = 3600) :
        this(EnginePaths(context, idleTimeoutSec), context)

    /** Per-instance variant (see the primary constructor's key doc). */
    constructor(context: Context, instanceKey: String, idleTimeoutSec: Int = 3600) :
        this(EnginePaths(context, idleTimeoutSec, instanceKey), context, instanceKey)

    /**
     * Updates the idle linger. Takes effect on the NEXT engine start because
     * the value is baked into the process environment at launch.
     */
    fun setIdleTimeoutSec(sec: Int) {
        paths.idleTimeoutSec = sec
    }

    @Volatile
    private var process: Process? = null

    /**
     * Serialises [start] against itself. Concurrent callers (a double tap, or
     * the supervisor racing a UI action) must not each spawn an engine.
     */
    private val startLock = Mutex()

    /**
     * The supervisor-owned MAY-SPAWN checkbox (design memo §1 interlock,
     * 2026-11-05). [start]'s birth section consults it as the FINAL pre-spawn
     * decision, inside the same monitor `stopBlocking()` synchronizes on, so a
     * stop that races an in-flight start is honoured before any child exists
     * instead of racing its birth.
     *
     * The default is literally `true`: until EngineSupervisor installs
     * `{ isWanted() }` in its supervise() setup (it does, before its first
     * start attempt), this changes NOTHING - every existing start call keeps
     * its current shape. Only the supervisor ever writes this field; a
     * veto is fail-closed (see [spawnVetoed]) and reported as
     * [EngineState.Stopped] so the supervise loop exits on its own
     * not-wanted condition.
     */
    @Volatile
    internal var maySpawn: () -> Boolean = { true }

    private val logBuffer = StringBuilder()

    val binaryPath: String get() = paths.binary.absolutePath
    val isBinaryUsable: Boolean get() = paths.isBinaryUsable

    /** True if we already own a live process. Guards against double-start. */
    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * Fault-injection hook for the chaos harness.
     *
     * The engine is a direct child of this app, and Android refuses SIGKILL from
     * an adb shell even via `run-as` (SELinux blocks it; verified on API 29).
     * So the only way to simulate an engine crash from outside is to have the
     * owning process kill its own child, which `Process.destroy()` does.
     *
     * @return true if a process was signalled.
     */
    fun killForFaultInjection(): Boolean {
        val p = process ?: return false
        return runCatching {
            p.destroy()
            p.waitFor(5, TimeUnit.SECONDS)
            true
        }.getOrDefault(false)
    }

    /**
     * Loopback egress bridge for the engine child (see [EngineProxy]).
     *
     * Wires the child's HTTPS_PROXY/HTTP_PROXY to an app-local CONNECT relay
     * so the engine NEVER resolves DNS itself: a statically linked Go binary
     * on Android has no /etc/resolv.conf and its resolver falls back to
     * loopback :53, which nothing serves here - measured via live device
     * traces 2026-11-02 ("works only while plugged into the computer"/tether
     * windows; dial refused stands alone). Started before spawn, stopped at
     * true engine shutdown.
     */
    private val egressBridge = EngineProxy()

    /**
     * Log hygiene. The engine binds app-private paths (the socket file, the
     * state dir); they are not secrets, but abbreviating the device-absolute
     * prefix keeps the exec line short, portable between devices, and free of
     * paths that change per install.
     */
    private fun redactAppPriv(arg: String): String =
        if (arg.contains("/data/user/0/")) arg.replace("/data/user/0/", "<datadir>/") else arg

    /**
     * Starts `crush server` and suspends until it serves HTTP over the Unix
     * domain socket. Returns [EngineState.Running] or [EngineState.Failed].
     * Never throws.
     *
     * Re-entrant: if an engine is already running, returns the current state
     * instead of spawning a second engine.
     *
     * Spawn veto (design memo §1 interlock): the final pre-spawn decision
     * consults [maySpawn] inside the same monitor `stopBlocking()` takes, so
     * the veto verdict, the child's birth and the `process` assignment are one
     * atomic step; a stop racing an in-flight start can no longer observe
     * "no process" while a child is otherwise reachable. A vetoed call returns
     * [EngineState.Stopped] and spawns nothing.
     *
     * Transport: the spawn uses `-H unix://<socketPath>`; there is NO TCP port
     * and none is ever computed. [EngineState.Running.port] carries
     * [UDS_PORT_SENTINEL] so URL-building consumers keep compiling while the
     * socket factory (see [EngineSockets]) ignores the URL port entirely.
     */
    suspend fun start(timeoutMs: Long = 60_000): EngineState = startLock.withLock {
        withContext(Dispatchers.IO) {
            val existing = process
            if (existing != null && existing.isAlive) {
                return@withContext EngineState.Running(
                    pid = readPid(existing),
                    port = UDS_PORT_SENTINEL,
                    version = cachedVersion,
                    bootMs = 0
                )
            }

        if (!paths.isBinaryUsable) {
                return@withContext EngineState.Failed(
                    reason = "Engine binary missing or not executable",
                    detail = paths.binary.absolutePath,
                    willRetry = false
                )
            }

            // The bind is a Unix domain socket in the app-private files dir,
            // not a TCP port. A stale socket file left over from a crashed
            // previous run is removed by the engine itself (net_other.go)
            // before it listens, and EngineSockets.probe reports "not up" for
            // a file with no listener behind it.
            val socketFile = EngineSockets.socketPath(appContext, instanceKey)

            // LAST-MINUTE CATALOG OVERRIDE.
            //
            // The engine parses its config ONCE per process, so any provider
            // catalogue repair has to land in the file BEFORE this spawn. The
            // engine's Catwalk cache is authoritative for most providers but
            // measured stale for groq (see EngineConfigInjector for the
            // numbers), so the override list is merged in here, idempotently.
            // Nothing must be logged with the file around - it holds the raw
            // provider key.
            runCatching {
                val injected = com.crush.mobile.data.crush.EngineConfigInjector.syncProviderModels(
                    paths.globalData,
                    "groq",
                    com.crush.mobile.data.crush.EngineConfigInjector.providerCatalog("groq"),
                    // Single-writer contract (decision item 4): this call site
                    // IS the pre-spawn window - no engine process exists yet -
                    // so the parameter is passed explicitly to pin the
                    // ordering against future call-site drift.
                    engineRunning = false
                )
                if (injected.changed) {
                    Log.i(TAG, "provider catalogue override applied: ${injected.ids.size} models")
                }
            }.onFailure { Log.w(TAG, "catalog override skipped: ${it.message}") }

            // S3: THE CHAT POOL'S TOOL POLICY (S1-measured mechanism). The chat
            // pool is conversation-only, so its process starts with every
            // built-in tool hidden via options.disabled_tools. Same pre-spawn
            // window as the catalogue override above - the engine parses its
            // config ONCE per process, so anything later cannot reach it.
            if (instanceKey == EnginePaths.CHAT_INSTANCE_KEY) {
                runCatching {
                    com.crush.mobile.data.crush.EngineConfigInjector.injectChatToolPolicy(
                        paths.globalData
                    )
                }.onFailure { Log.w(TAG, "chat tool policy injection skipped: ${it.message}") }
            }

            // START THE EGRESS BRIDGE BEFORE SPAWNING: the child env below
            // aims HTTPS_PROXY/HTTP_PROXY at this port. Without it the
            // engine's Go resolver has no usable server on Android (no
            // /etc/resolv.conf; fallback ::1:53, measured dead) and every
            // provider call dies whenever the phone is not tethering - the
            // "works only while plugged into the computer" defect,
            // root-caused 2026-11-02. Idempotent across the supervisor's
            // bounded retry ladder.
            val bridgeUp = egressBridge.start()
            Log.i(TAG, "egress bridge ${if (bridgeUp) "up port=${egressBridge.port}" else "UNAVAILABLE"}")

            val cmd = listOf(
                paths.binary.absolutePath,
                "server",
                "-H", "unix://${socketFile.absolutePath}",
                "-D", paths.dataDir.absolutePath
            )
            // Wall/monotonic pair for the S5 §2.1 classification (2026-11-05):
            // the measured 55-60s spawn gap is either a device doze-freeze
            // (wall clock jumps, monotonic does not) or the attempt ladder;
            // the success line below prints BOTH so the gap gets classified
            // instead of guessed. Captured at attempt start, just ahead of the
            // birth section.
            val started = System.currentTimeMillis()
            val startedMono = android.os.SystemClock.elapsedRealtime()

            // BIRTH-OR-NEVER interlock (design memo §1, 2026-11-05). The MAY-
            // SPAWN verdict, the child's birth and the `process` assignment
            // share ONE critical section with stopBlocking()'s snapshot - the
            // same monitor, deliberately NOT shared with [startLock] (UI-tap
            // stop stays deadlock-free; only this brief section blocks it). A
            // racing stop can therefore only observe: (a) no child yet -> the
            // veto keeps it that way, or (b) process already assigned -> the
            // same section's snapshot reaches it and kills it. The measured
            // F06 window (child alive between ProcessBuilder.start() and the
            // assignment while stopBlocking snapshots null) is closed by
            // construction. The injector/policy single-writer pre-spawn work
            // above is untouched; the veto sits AFTER it, under the monitor.
            val proc: Process = synchronized(this) {
                // MAY-SPAWN: the supervisor's checkbox, evaluated at the last
                // moment (default true = current behaviour until the
                // supervisor installs `{ isWanted() }`). On a veto there is no
                // child to destroy and nothing else to undo except the bridge
                // started above - mirroring exactly what the cancel path
                // already cleans up - and the honest outcome is the state the
                // supervise loop exits on by itself when not wanted.
                if (spawnVetoed(maySpawn)) {
                    Log.w(TAG, "spawn vetoed by the supervisor: no engine child will be born")
                    com.crush.mobile.util.AppLog.w(
                        TAG,
                        "[$instanceKey] spawn vetoed before birth (stop superseded the " +
                            "pending start); no engine child will be born"
                    )
                    runCatching { egressBridge.stop() }
                    return@withContext EngineState.Stopped
                }
                Log.i(TAG, "exec: ${cmd.joinToString(" ") { redactAppPriv(it) }}")
                try {
                    ProcessBuilder(cmd).apply {
                        environment().putAll(paths.env())
                        // Route ALL engine egress through the loopback bridge: the
                        // engine's Go http honours these vars (stdlib
                        // ProxyFromEnvironment), so it never resolves a hostname
                        // itself and never depends on the broken Android DNS
                        // fallback. NO_PROXY keeps loopback targets out of the
                        // bridge so nothing can loop into itself. The bridge only
                        // relays: TLS stays end-to-end engine<->provider.
                        if (egressBridge.port > 0) {
                            val proxyUrl = "http://127.0.0.1:${egressBridge.port}"
                            with(environment()) {
                                this["HTTPS_PROXY"] = proxyUrl
                                this["https_proxy"] = proxyUrl
                                this["HTTP_PROXY"] = proxyUrl
                                this["http_proxy"] = proxyUrl
                                this["NO_PROXY"] = "127.0.0.1,localhost,::1"
                                this["no_proxy"] = "127.0.0.1,localhost,::1"
                            }
                        }
                        redirectErrorStream(true)
                    }.start().also { process = it }
                } catch (e: IOException) {
                    Log.e(TAG, "exec failed", e)
                    return@withContext EngineState.Failed(
                        reason = "Could not launch engine",
                        detail = "${e.javaClass.simpleName}: ${e.message}",
                        willRetry = false
                    )
                }
            }
            drainOutput(proc)

            val deadline = System.currentTimeMillis() + timeoutMs
            // PROBE-BEFORE-DEADLINE (design memo §2, doze-vs-ladder fix,
            // 2026-11-05). The old order checked the wall-clock deadline
            // FIRST, so a counted doze-freeze turned the first post-freeze
            // iteration into an instant deadline bust: a child that bound
            // its socket DURING the freeze was destroyed as "not healthy
            // within 60s" instead of reported Running. Each iteration now
            // runs isAlive -> probe -> deadline, so the freeze case converts
            // into either an honest success or a legitimate retry, and a
            // child that is genuinely neither alive nor bound still falls
            // through to exactly the same timeout outcome below.
            while (true) {
                if (!proc.isAlive) {
                    val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                    Log.e(TAG, "engine exited early code=$code")
                    process = null
                    return@withContext EngineState.Failed(
                        reason = "Engine exited during startup (exit $code)",
                        detail = tailLog(),
                        // P1-10: the most common non-config cause of an
                        // early exit is a bind failure on the socket file -
                        // usually a stale socket file left by a crashed
                        // previous engine run. That is NOT a hard error: the
                        // engine itself removes a stale socket before
                        // listening (net_other.go, see the bind comment
                        // above), so a retry has a real chance of clearing
                        // it. The START budget in EngineSupervisor
                        // (MAX_START_ATTEMPTS) bounds the retry loop; a
                        // genuinely hard failure (missing binary, exec
                        // denied) never reaches this branch.
                        willRetry = true
                    )
                }
                // Readiness probe over the socket. EngineSockets.probe returns
                // null while the socket file does not exist or nothing is
                // listening behind it - the same "engine not up yet" signal
                // the old TCP probe gave - and never throws, so the loop needs
                // no try/catch. Same 250ms cadence and deadline as before; the
                // probe answers BEFORE the deadline check now, which only
                // widens the outcome set by "bound in time despite a wall-
                // clock jump" (the doze case) and by at most one probe's
                // worth of trailing slack past the deadline.
                if (EngineSockets.probe(socketFile, "/v1/version") != null) {
                    val version = probeVersion(socketFile)
                    if (version != null) {
                        cachedVersion = version
                        val pid = readPid(proc)
                        // S5 instrumentation (memo §2.1): wall AND monotonic
                        // elapsed since attempt start. wall >> monotonic at
                        // bind = the device doze-froze between spawn and bind;
                        // both stretched together = a genuinely long boot.
                        Log.i(
                            TAG,
                            "engine socket bound and 2xx after " +
                                "${System.currentTimeMillis() - started}ms " +
                                "(monotonic ${android.os.SystemClock.elapsedRealtime() - startedMono}ms)"
                        )
                        Log.i(TAG, "engine ready pid=$pid version=$version")
                        return@withContext EngineState.Running(
                            pid = pid,
                            port = UDS_PORT_SENTINEL,
                            version = version,
                            bootMs = System.currentTimeMillis() - started
                        )
                    }
                }
                // Deadline honored AFTER the probe (see the loop comment).
                if (System.currentTimeMillis() >= deadline) {
                    break
                }
                delay(250)
            }

            proc.destroy()
            process = null
            EngineState.Failed(
                reason = "Engine did not become healthy within ${timeoutMs / 1000}s",
                detail = tailLog(),
                // P1-10: exactly ONE slow cold boot at 60s used to leave the
                // user stranded in Failed(willRetry=false) forever - a slow
                // first boot (cold disk, catalog population under writeMu
                // measured ~5s alone, memory pressure) is transient, so it
                // must retry rather than strand. The retry budget is bounded
                // by EngineSupervisor's MAX_START_ATTEMPTS backoff ladder, so
                // this cannot become an infinite loop; keep willRetry=false
                // for the genuinely hard failures (missing binary, exec
                // failure) above.
                willRetry = true
            )
        }
    }

    /**
     * Graceful stop, escalating to SIGKILL if the engine ignores it.
     *
     * Deliberately not guarded by [startLock]: it is called from the UI thread
     * (via the supervisor's stop()) where blocking on the same mutex a
     * concurrent start() holds would deadlock. A concurrent start() re-checks
     * liveness under the lock and returns the existing engine instead of
     * spawning a second one.
     */
    @Synchronized
    fun stopBlocking() {
        val proc = process
        process = null
        cachedVersion = ""
        if (proc == null) return
        runCatching {
            proc.destroy()
            if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }.onFailure { Log.w(TAG, "stop failed", it) }
        // The child is gone, so the bridge has no more peers; a later
        // start() brings it back up (idempotent).
        egressBridge.stop()
    }

    @Volatile
    private var cachedVersion: String = ""

    /**
     * The engine writes nothing to a tty on Android, so we must drain the pipe.
     * If this buffer fills, the child blocks forever and boot silently hangs.
     */
    private fun drainOutput(proc: Process) {
        Thread({
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "[crush] $line")
                    synchronized(logBuffer) {
                        if (logBuffer.length < MAX_LOG_CHARS) logBuffer.append(line).append('\n')
                    }
                }
            }
        }, "crush-stdout").apply { isDaemon = true }.start()
    }

    private fun tailLog(): String? =
        synchronized(logBuffer) { logBuffer.toString() }
            .takeIf { it.isNotBlank() }
            ?.takeLast(2000)
            ?: runCatching { paths.logFile()?.readText()?.takeLast(2000) }
                .getOrNull()

/**
 * java.lang.Process.pid() is absent from Android's API surface, so read the
 * pid reflectively.
 *
 * Deliberately NO /proc fallback: on Android, listing /proc from an
 * untrusted_app triggers a burst of SELinux `avc: denied` audit events
 * (proc_stat, proc_keys, proc_fb, ...). The pid is cosmetic, so a reflection
 * miss simply yields -1 rather than generating thousands of denials.
 */
private fun readPid(proc: Process): Int = runCatching {
    pidFromReflective(Process::class.java.getMethod("pid").invoke(proc))
}.getOrDefault(-1)

    /**
     * GET /v1/version over the UDS socket. /v1/health returns 200 with an
     * EMPTY body, so it cannot confirm readiness usefully; /v1/version
     * returns real JSON.
     *
     * [EngineSockets.probe] answers the status question for the readiness
     * loop; this performs the equivalent request through
     * [EngineSockets.okClient] so the version STRING - the raw body, as the
     * previous HttpURLConnection probe produced it - can be cached. Returns
     * null when the socket file is still missing, the request fails, or the
     * body is blank, exactly like the old probe.
     */
    private fun probeVersion(socketFile: File): String? {
        val client = EngineSockets.okClient(socketFile) ?: return null
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://${EngineSockets.LOCAL_HOSTNAME}/v1/version")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else body.take(400)
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "CrushEngine"
        const val MAX_LOG_CHARS = 8_000
    }
}

/**
 * The MAY-SPAWN birth-gate decision (design memo §1 interlock, 2026-11-05),
 * evaluated inside [CrushEngine.start]'s synchronized birth section as the
 * FINAL pre-spawn check.
 *
 * Pure, top-level and IDEMPOTENTLY fail-closed, like [pidFromReflective]
 * living outside the class for the same reason: a checkbox implementation that
 * throws must veto the birth, never crash the start call and never let an
 * orphan child past a half-checked gate ("a child survives its own stop"). The
 * lambda must involve no suspension and no lock other than the ones it uses by
 * contract (the supervisor's `{ isWanted() }` reads its `wanted` flag under
 * the supervisor's own sync, which no path takes while holding this monitor in
 * reverse order).
 */
internal fun spawnVetoed(maySpawn: () -> Boolean): Boolean =
    // fail-CLOSED means: evaluation either says Allowed or the gate VETOES -
    // a throwing checkbox must veto (read the KDoc above: never let a child
    // be born on an unknown verdict), a wanted-vote allows the birth.
    runCatching { maySpawn() }.getOrDefault(false).not()