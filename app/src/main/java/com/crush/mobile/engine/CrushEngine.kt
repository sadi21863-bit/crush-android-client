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
    private val paths: EnginePaths,
    /**
     * Application context, needed to resolve the Unix-domain-socket path the
     * engine binds ([EngineSockets.socketPath]). AppContainer passes the
     * application context, so the engine never pins an Activity.
     */
    private val appContext: Context
) {

    /** Engine idle linger, in seconds, from static auto-tuning. */
    constructor(context: Context, idleTimeoutSec: Int = 3600) :
        this(EnginePaths(context, idleTimeoutSec), context)

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
            val socketFile = EngineSockets.socketPath(appContext)

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
                    com.crush.mobile.data.crush.EngineConfigInjector.providerCatalog("groq")
                )
                if (injected.changed) {
                    Log.i(TAG, "provider catalogue override applied: ${injected.ids.size} models")
                }
            }.onFailure { Log.w(TAG, "catalog override skipped: ${it.message}") }

            val cmd = listOf(
                paths.binary.absolutePath,
                "server",
                "-H", "unix://${socketFile.absolutePath}",
                "-D", paths.dataDir.absolutePath
            )
            Log.i(TAG, "exec: ${cmd.joinToString(" ") { redactAppPriv(it) }}")

            val started = System.currentTimeMillis()
            val proc = try {
                ProcessBuilder(cmd).apply {
                    environment().putAll(paths.env())
                    redirectErrorStream(true)
                }.start()
            } catch (e: IOException) {
                Log.e(TAG, "exec failed", e)
                return@withContext EngineState.Failed(
                    reason = "Could not launch engine",
                    detail = "${e.javaClass.simpleName}: ${e.message}",
                    willRetry = false
                )
            }

            process = proc
            drainOutput(proc)

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) {
                    val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                    Log.e(TAG, "engine exited early code=$code")
                    process = null
                    return@withContext EngineState.Failed(
                        reason = "Engine exited during startup (exit $code)",
                        detail = tailLog(),
                        willRetry = false
                    )
                }
                // Readiness probe over the socket. EngineSockets.probe returns
                // null while the socket file does not exist or nothing is
                // listening behind it - the same "engine not up yet" signal
                // the old TCP probe gave - and never throws, so the loop needs
                // no try/catch. Same 250ms cadence and deadline as before.
                if (EngineSockets.probe(socketFile, "/v1/version") != null) {
                    val version = probeVersion(socketFile)
                    if (version != null) {
                        cachedVersion = version
                        val pid = readPid(proc)
                        Log.i(TAG, "engine ready pid=$pid version=$version")
                        return@withContext EngineState.Running(
                            pid = pid,
                            port = UDS_PORT_SENTINEL,
                            version = version,
                            bootMs = System.currentTimeMillis() - started
                        )
                    }
                }
                delay(250)
            }

            proc.destroy()
            process = null
            EngineState.Failed(
                reason = "Engine did not become healthy within ${timeoutMs / 1000}s",
                detail = tailLog(),
                willRetry = false
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