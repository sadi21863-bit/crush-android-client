package com.opencode.chat.ui.screens.diagnostics

import android.content.Context
import android.os.Build
import com.opencode.chat.OpenCodeChatApp
import com.opencode.chat.engine.EngineState
import com.opencode.chat.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The single thing a friend can copy and send us when the app misbehaves.
 *
 * Deliberately a plain data class with no Compose types and no Context in its
 * fields. The formatter ([buildReport]) is a pure function of this plus a
 * timestamp, so the exact text that lands on the clipboard can be unit tested
 * without a device, a Keystore or an engine - the same reason StartRoute and
 * normalizeApiKey were extracted as pure functions.
 */
data class DiagnosticsReport(
    val appVersionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val abis: List<String>,
    val engineBinaryPath: String,
    val engineBinaryPresent: Boolean,
    /** e.g. "running (pid 4211, port 39001)" or "stopped". */
    val engineStateLabel: String,
    val enginePort: Int?,
    /**
     * null means "not checked", which is a real outcome: the probe only runs
     * when the supervisor reports a port, and a null must not be rendered as a
     * failure. The supervisor's own KDoc records that HTTP is not authoritative
     * while a stream is live, so this is evidence, never a verdict.
     */
    val engineResponding: Boolean?,
    val engineProbeNote: String?,
    val crash: RecordedCrash?,
    val logPath: String?,
    val logTail: List<String>,
    val logTotalLines: Int,
    /** True when [logTail] was cut down from [logTotalLines] for display. */
    val logTruncatedForDisplay: Boolean,
    /** Anything that could not be collected. Shown so a gap is never silent. */
    val notes: List<String>
)

/**
 * An uncaught exception recovered from disk.
 *
 * [source] distinguishes the two places it can come from, because they have
 * different lifetimes and that difference is load-bearing - see
 * [DiagnosticsRecorder] for why the archive is the only one that survives a
 * relaunch.
 */
data class RecordedCrash(
    val source: String,
    val capturedAt: String,
    val text: String
)

/**
 * Loads everything the screen shows. Every read happens on [Dispatchers.IO].
 *
 * Nothing here throws. A diagnostics screen that crashes while diagnosing a
 * crash is worse than useless, and the inputs here are exactly the inputs that
 * fail on a friend's phone: a missing file, a half-written file, an engine that
 * is not running, a package manager that refuses.
 */
suspend fun loadDiagnostics(context: Context): DiagnosticsReport =
    withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()
        val appContext = context.applicationContext

        // ---- version -------------------------------------------------------
        // BuildConfig.VERSION_NAME is NOT used, and cannot be: buildConfig is
        // absent from buildFeatures in app/build.gradle.kts, so no BuildConfig
        // class is generated (confirmed: zero BuildConfig references in the
        // release dex). PackageManager is the source of truth anyway - it is
        // what the launcher and any bug report tool read.
        val pkg = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        if (pkg == null) notes += "Could not read package info from PackageManager"

        // ---- log -----------------------------------------------------------
        // AppLog.path() is used rather than re-deriving File(filesDir,
        // "app.log"), so a rename inside AppLog cannot silently produce an
        // always-empty screen. It returns the literal "(not initialised)"
        // before init; requiring an absolute path rejects that without having
        // to string-match a sentinel that AppLog is free to change.
        val logFile = runCatching { File(AppLog.path()).takeIf { it.isAbsolute } }
            .getOrNull()
        if (logFile == null) notes += "App log path unavailable (AppLog not initialised)"

        val allLines: List<String> = if (logFile == null) {
            emptyList()
        } else {
            // Tolerates the three states that matter on a fresh install and
            // after a hard kill: absent, present-but-empty (AppLog truncates on
            // every launch), and present with a partial final line (AppLog
            // appends unsynchronised, so a concurrent write can be mid-line).
            runCatching { logFile.readLines() }
                .onFailure { notes += "Could not read app log: ${it.javaClass.simpleName}" }
                .getOrDefault(emptyList())
        }

        // ---- crash ---------------------------------------------------------
        // Two sources, newest first. The archive is authoritative because it
        // is the only one that outlives the process.
        val archived = DiagnosticsRecorder.readArchived(appContext)
        val logged = parseUncaughtFromLog(allLines)
        val crash = when {
            archived != null && logged != null ->
                archived.copy(
                    text = archived.text + "\n\n" +
                        "--- also present in this run's app.log ---\n" + logged.text
                )
            archived != null -> archived
            else -> logged
        }

        // ---- engine --------------------------------------------------------
        val engineFile = File(appContext.applicationInfo.nativeLibraryDir, "libcrush.so")
        val engineBinaryPresent = engineFile.exists()

        // The supervisor is the process-wide SINGLETON on AppContainer, so
        // touching this getter returns the same instance Chat uses rather than
        // making a second polling loop. It is NOT started here: constructing it
        // does not begin supervision, and starting an engine from a diagnostics
        // screen would boot 59MB of Go to answer a question.
        val engineState = runCatching {
            (appContext as? OpenCodeChatApp)?.container?.engineSupervisor?.state?.value
        }.getOrNull()

        val port = (engineState as? EngineState.Running)?.port
        val responding = if (port != null) probeEngine(port) else null
        if (port != null && responding == null) {
            notes += "Engine probe did not complete (the port may be busy, not dead)"
        }

        DiagnosticsReport(
            appVersionName = pkg?.versionName?.takeIf { it.isNotBlank() } ?: "unknown",
            versionCode = pkg?.let { runCatching { it.longVersionCode }.getOrNull() } ?: -1L,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            abis = Build.SUPPORTED_ABIS.toList(),
            engineBinaryPath = engineFile.absolutePath,
            engineBinaryPresent = engineBinaryPresent,
            engineStateLabel = describeEngineState(engineState),
            enginePort = port,
            engineResponding = responding,
            engineProbeNote = when {
                port == null -> null
                responding == true -> "GET /v1/version returned 200"
                responding == false -> "GET /v1/version did not answer"
                else -> "no response within the timeout"
            },
            crash = crash,
            logPath = logFile?.absolutePath,
            logTail = allLines.takeLast(LOG_LINES_SHOWN),
            logTotalLines = allLines.size,
            logTruncatedForDisplay = allLines.size > LOG_LINES_SHOWN,
            notes = notes
        )
    }

/**
 * The single text block the user copies.
 *
 * Header first, on purpose: it is the line that makes a WhatsApp message
 * recognisable in a chat full of forwards.
 *
 * [redactSecrets] is applied to the whole block, log included. AppLog is
 * written by hand and never logs the key today, but this text is designed to be
 * pasted into a third-party app, and `files/crush.json` holds the provider key
 * in plaintext by Crush's own design. Anything key-shaped is therefore removed
 * on the way out rather than trusted not to be there.
 */
fun buildReport(report: DiagnosticsReport, capturedAt: String): String = buildString {
    appendLine("=== OpenCode Chat ${report.appVersionName} (${report.versionCode}) ===")
    appendLine("captured: $capturedAt")
    appendLine()
    appendLine("--- device ---")
    appendLine("app version   : ${report.appVersionName} (versionCode ${report.versionCode})")
    appendLine("android       : ${report.androidRelease} (API ${report.sdkInt})")
    appendLine("device        : ${report.manufacturer} ${report.model}")
    appendLine("abis          : ${report.abis.joinToString(", ").ifBlank { "none reported" }}")
    appendLine()
    appendLine("--- engine ---")
    appendLine("binary        : ${if (report.engineBinaryPresent) "present" else "MISSING"}")
    appendLine("  path        : ${report.engineBinaryPath}")
    appendLine("state         : ${report.engineStateLabel}")
    appendLine("port          : ${report.enginePort ?: "none"}")
    appendLine(
        "responding    : " + when (report.engineResponding) {
            null -> "not checked"
            true -> "yes"
            false -> "no"
        } + (report.engineProbeNote?.let { " ($it)" } ?: "")
    )
    appendLine()
    appendLine("--- crash ---")
    if (report.crash == null) {
        appendLine("none recorded")
    } else {
        appendLine("source    : ${report.crash.source}")
        appendLine("captured  : ${report.crash.capturedAt}")
        appendLine()
        appendLine(report.crash.text)
    }
    appendLine()
    appendLine("--- app log (${report.logTail.size} of ${report.logTotalLines} lines) ---")
    appendLine("path      : ${report.logPath ?: "(no log)"}")
    report.notes.forEach { appendLine("note      : $it") }
    appendLine()
    if (report.logTail.isEmpty()) {
        appendLine("(nothing recorded yet)")
    } else {
        if (report.logTruncatedForDisplay) {
            appendLine("(earlier lines omitted)")
        }
        report.logTail.forEach { appendLine(it) }
    }
}.let(::redactSecrets)

/**
 * Strips anything key-shaped from text bound for a third-party app.
 *
 * Deliberately blunt. A diagnostics report that silently mangled a stack trace
 * would be worse than one that dropped a rare legitimate token, and the only
 * strings that must never leave the device are the ones matching this.
 */
private fun redactSecrets(text: String): String = text
    .replace(SECRET_OC_SK, "[redacted-key]")
    .replace(SECRET_SK, "[redacted-key]")

/**
 * Pulls the most recent uncaught exception out of the app log.
 *
 * AppLog writes `<time> E/App: UNCAUGHT on '<thread>'` followed by the raw
 * stack trace on its own lines, because a Throwable is appended with a newline
 * rather than being re-prefixed. So the trace is every following line until the
 * next timestamped entry - hence the terminator regex, which mirrors AppLog's
 * own "HH:mm:ss.SSS L/TAG: " format.
 *
 * Returns null when the log has no crash, which on a fresh install is the
 * normal case and must not be presented as a failure.
 */
internal fun parseUncaughtFromLog(lines: List<String>): RecordedCrash? {
    val index = lines.indexOfLast { it.contains(UNCAUGHT_MARKER) }
    if (index < 0) return null
    var end = index + 1
    while (end < lines.size && !ENTRY_PREFIX.matches(lines[end])) end++
    val trace = lines.subList(index, end).joinToString("\n").trimEnd()
    // The header alone with no trace means the process died mid-write. Still
    // worth showing: "it crashed here" beats an empty section.
    val thread = Regex("UNCAUGHT on '([^']*)'").find(lines[index])
        ?.groupValues?.getOrNull(1).orEmpty()
    return RecordedCrash(
        source = "app.log (this run only - AppLog truncates on every launch)",
        capturedAt = lines[index].take(TIMESTAMP_LEN).ifBlank { "unknown time" },
        text = if (thread.isBlank()) trace else "thread: $thread\n$trace"
    )
}

private fun describeEngineState(state: EngineState?): String = when (state) {
    null -> "unknown (supervisor unavailable)"
    EngineState.Stopped -> "stopped"
    is EngineState.Starting -> "starting (attempt ${state.attempt})"
    is EngineState.Running ->
        "running (pid ${state.pid}, port ${state.port}, boot ${state.bootMs}ms)"
    // The detail carries an engine log tail, which belongs in the log section,
    // not inline here - inlining it would bury the reason. willRetry is the
    // useful bit: it distinguishes "will keep retrying" from "permanently dead".
    is EngineState.Failed ->
        "failed: ${state.reason} (will retry: ${if (state.willRetry) "yes" else "no"})"
}

/**
 * One GET against loopback with a short timeout.
 *
 * Kept because it is genuinely cheap and the answer is worth having: a friend
 * reporting "it says starting forever" is a different bug from one reporting
 * "no response", and nothing else on this screen distinguishes them.
 *
 * /v1/health is useless here - it returns 200 with an EMPTY body (recorded in
 * CrushEngine), so /v1/version is used for the same reason the engine uses it.
 * Timeouts are 1.2s rather than the supervisor's 5s: this is a UI refresh, and
 * a user staring at a spinner for five seconds is a worse experience than an
 * inconclusive answer.
 */
private fun probeEngine(port: Int): Boolean? = try {
    val conn = URL("http://127.0.0.1:$port/v1/version").openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = PROBE_TIMEOUT_MS
        conn.readTimeout = PROBE_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.responseCode == 200
    } finally {
        conn.disconnect()
    }
} catch (_: Exception) {
    // Includes the timeout. Distinguished from false via engineProbeNote rather
    // than a second boolean, because "slow" and "dead" have different causes
    // and the supervisor's KDoc explains why a busy engine looks dead here.
    null
}

/** Shown on screen and copied. 200 is the brief's cap and also a sane paste size. */
private const val LOG_LINES_SHOWN = 200
private const val PROBE_TIMEOUT_MS = 1_200
private const val UNCAUGHT_MARKER = "UNCAUGHT on '"
private const val TIMESTAMP_LEN = 12

/** Matches AppLog's `HH:mm:ss.SSS L/TAG: ` prefix, used as a record terminator. */
private val ENTRY_PREFIX = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} [DIWE]/\S+: """)

// oc_sk_ is OpenCode Zen's own prefix and must be caught first; the sk- pattern
// would otherwise match its tail.
private val SECRET_OC_SK = Regex("""oc_sk_[A-Za-z0-9_\-]{6,}""")
private val SECRET_SK = Regex("""(?<![A-Za-z0-9_])sk-[A-Za-z0-9_\-]{8,}""")