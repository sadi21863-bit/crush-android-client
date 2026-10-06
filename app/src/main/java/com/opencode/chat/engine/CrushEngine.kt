package com.opencode.chat.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
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
 */
class CrushEngine(private val paths: EnginePaths) {

    /** Engine idle linger, in seconds, from static auto-tuning. */
    constructor(context: Context, idleTimeoutSec: Int = 3600) :
        this(EnginePaths(context, idleTimeoutSec))

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
     * the supervisor racing a UI action) must not each spawn an engine on a
     * different port.
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

    /** Crush takes the port in -H, not --port, so we pick a free one. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Starts `crush server` and suspends until it serves HTTP.
     * Returns [EngineState.Running] or [EngineState.Failed]. Never throws.
     *
     * Re-entrant: if an engine is already running, returns the current state
     * instead of spawning a second one on a different port.
     */
    suspend fun start(timeoutMs: Long = 60_000): EngineState = startLock.withLock {
        withContext(Dispatchers.IO) {
            val existing = process
            if (existing != null && existing.isAlive) {
                val port = currentPort
                if (port != null) {
                    return@withContext EngineState.Running(
                        pid = readPid(existing),
                        port = port,
                        version = cachedVersion,
                        bootMs = 0
                    )
                }
            }

        if (!paths.isBinaryUsable) {
                return@withContext EngineState.Failed(
                    reason = "Engine binary missing or not executable",
                    detail = paths.binary.absolutePath,
                    willRetry = false
                )
            }

            val port = freePort()
            currentPort = port

            val cmd = listOf(
                paths.binary.absolutePath,
                "server",
                "-H", "tcp://127.0.0.1:$port",
                "-D", paths.dataDir.absolutePath
            )
            Log.i(TAG, "exec: ${cmd.joinToString(" ")}")

            val started = System.currentTimeMillis()
            val proc = try {
                ProcessBuilder(cmd).apply {
                    environment().putAll(paths.env())
                    redirectErrorStream(true)
                }.start()
            } catch (e: IOException) {
                Log.e(TAG, "exec failed", e)
                currentPort = null
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
                    currentPort = null
                    return@withContext EngineState.Failed(
                        reason = "Engine exited during startup (exit $code)",
                        detail = tailLog(),
                        willRetry = false
                    )
                }
                val version = probeVersion(port)
                if (version != null) {
                    cachedVersion = version
                    val pid = readPid(proc)
                    Log.i(TAG, "engine ready pid=$pid port=$port version=$version")
                    return@withContext EngineState.Running(
                        pid = pid,
                        port = port,
                        version = version,
                        bootMs = System.currentTimeMillis() - started
                    )
                }
                delay(250)
            }

            proc.destroy()
            process = null
            currentPort = null
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
        currentPort = null
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
    private var currentPort: Int? = null

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
            ?: runCatching { paths.logFile().takeIf { it.exists() }?.readText()?.takeLast(2000) }
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
        val pid = Process::class.java.getMethod("pid").invoke(proc) as? Int
        if (pid != null && pid > 0) pid else -1
    }.getOrDefault(-1)

    /**
     * GET /v1/version. /v1/health returns 200 with an EMPTY body, so it cannot
     * confirm readiness usefully; /v1/version returns real JSON.
     */
    private fun probeVersion(port: Int): String? {
        return try {
            val conn = URL("http://127.0.0.1:$port/v1/version").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 1_500
                conn.readTimeout = 1_500
                conn.requestMethod = "GET"
                if (conn.responseCode != 200) {
                    null
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.isBlank()) null else body.take(400)
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "CrushEngine"
        const val MAX_LOG_CHARS = 8_000
    }
}