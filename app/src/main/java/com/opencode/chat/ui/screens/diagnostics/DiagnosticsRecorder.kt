package com.opencode.chat.ui.screens.diagnostics

import android.content.Context
import com.opencode.chat.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Preserves uncaught exceptions somewhere that survives a relaunch.
 *
 * This exists because of a defect in the existing crash path that makes it
 * useless for the exact job this screen is for.
 *
 * `OpenCodeChatApp.installCrashHandler` writes the throwable into `app.log` via
 * AppLog, and then the process dies. On the next launch `AppLog.init` runs
 * `f.writeText("")` - it truncates unconditionally, so each run is one file.
 * The user relaunches the app to tell us it crashed, and by the time the
 * Diagnostics screen could read anything, the evidence has been deleted by the
 * app itself. The crash is only ever visible inside the process that died.
 *
 * This writes to a SEPARATE file that nothing truncates, so the record is still
 * there on the next launch.
 *
 * ## Coverage limit - read this before trusting a blank screen
 *
 * Installation is lazy: the handler is attached the first time
 * [com.opencode.chat.ui.screens.diagnostics.DiagnosticsRoute] composes. A crash
 * that happens before the user has ever opened Diagnostics is still lost with
 * `app.log`. That is a real gap, not a rounding error, and it is not fixable
 * from inside this package - see the note on [install].
 *
 * The fix belongs in `OpenCodeChatApp.onCreate`, which is the only place that
 * runs early enough and is outside the files this change owns:
 *
 * ```
 * DiagnosticsRecorder.install(this)   // BEFORE AppLog.init(this)
 * ```
 *
 * It is ordered before `AppLog.init` on purpose. The archive is independent of
 * AppLog so the order is not strictly required, but installing first means a
 * throwable raised during AppLog's own init is still captured.
 */
object DiagnosticsRecorder {

    private const val TAG = "Diagnostics"
    private const val FILE_NAME = "crash-archive.txt"

    /**
     * Keep the archive small. Each entry is a stack trace plus a wall-clock
     * header, so a crash loop would otherwise fill the user's storage from a
     * file that is invisible to them and impossible to delete without adb.
     * Three crashes is enough to recognise a loop; the newest entries are kept.
     */
    private const val MAX_BYTES = 128 * 1024
    private const val MAX_ENTRIES = 3

    private val installed = AtomicBoolean(false)

    /**
     * Chains onto the existing default handler. Safe to call repeatedly.
     *
     * MUST chain rather than replace. `OpenCodeChatApp` installs a handler that
     * logs to `app.log` and then delegates to whatever was there before; if we
     * simply assigned ours, we would silently disable both the logcat write and
     * the existing file write, and the process would no longer terminate the
     * way the platform expects. Delegating to [previous] keeps that contract.
     *
     * @param previous injectable for testing; null means "whatever is installed
     *        now". Exposed as a parameter rather than read internally so the
     *        chaining behaviour is assertable without a crashing thread.
     */
    fun install(context: Context, previous: Thread.UncaughtExceptionHandler? = null) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val downstream = previous ?: Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Same posture as AppLog: this runs on a dying thread with no
            // guarantee of time or memory, so it must never be the thing that
            // adds a second failure. Synchronous and unsynchronised for the same
            // reason AppLog is - a crash record that lost a race is still a
            // crash record, but a handler that throws is a hang.
            runCatching { append(appContext, thread, throwable) }
                .onFailure { AppLog.w(TAG, "could not archive crash: ${it.javaClass.simpleName}") }
            // The downstream handler terminates the process. Returning early
            // instead would leave a zombie app with a broken UI, which is far
            // harder to diagnose than a clean death.
            downstream?.uncaughtException(thread, throwable)
        }
    }

    private fun append(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, FILE_NAME)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        // Each record carries its own header line and ENDS with a newline
        // (stackTraceToString does), so records are joined with a bare "\n" and
        // land one blank line apart. Splitting on a literal separator string
        // instead would strip the "=== " off every record but the first and
        // silently mangle every crash after the first.
        val entry = buildString {
            appendLine("$HEADER_PREFIX $stamp on '${thread.name}' $HEADER_SUFFIX")
            appendLine(throwable.stackTraceToString())
        }

        // Read-modify-write of a tiny file. Not atomic across a crash loop, but
        // a torn write here costs one record, not the app.
        val existing = runCatching { if (file.exists()) file.readText() else "" }
            .getOrDefault("")
        val kept = (listOf(entry) + splitRecords(existing))
            .filter { it.isNotBlank() }
            .take(MAX_ENTRIES)

        val body = kept.joinToString("\n")
        if (body.length > MAX_BYTES) {
            // Keeps the TAIL, which is the newest crash - the one being
            // reported. Taking the head would keep the oldest and drop the
            // evidence.
            runCatching { file.writeText(body.takeLast(MAX_BYTES)) }
        } else {
            runCatching { file.writeText(body) }
        }
    }

    /**
     * Splits on a line that STARTS a record. Each returned segment keeps its
     * own header, so [readArchived] sees the same shape [append] wrote.
     *
     * Multi-line rather than a literal separator because a stack trace cannot
     * contain a line starting with "=== ", whereas any fixed separator string we
     * pick could in principle occur inside one.
     *
     * A record truncated by a torn write is kept as-is rather than dropped: a
     * partial trace is still the best evidence available.
     */
    // A zero-width match before "=== ", so each returned segment ALREADY starts
    // with its header and no prefix has to be re-added - prepending one would
    // produce "==== 2026-..." and break the next split. The leading empty
    // segment that a match at index 0 produces is dropped by the filter.
    private fun splitRecords(text: String): List<String> =
        RECORD_BOUNDARY.split(text).filter { it.isNotBlank() }

    /**
     * The most recent archived crash, or null.
 *
     * Tolerates a missing file (never crashed, or never had Diagnostics opened),
     * an empty file, and an unreadable one. Never throws: the caller is a
     * screen whose whole purpose is to be reliable when things are broken.
     */
    fun readArchived(context: Context): RecordedCrash? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return null

        // The first record is the newest: append() prepends.
        val first = splitRecords(text).firstOrNull { it.isNotBlank() } ?: return null
        val header = first.lineSequence().firstOrNull().orEmpty()
        val capturedAt = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{4}""")
            .find(header)?.value.orEmpty()

        return RecordedCrash(
            source = "crash archive (survives relaunch)",
            capturedAt = capturedAt.ifBlank { "unknown time" },
            text = first.trim()
        )
    }

    /**
     * Test seam. Production callers never reset this; a second [install] in the
     * same process is a no-op by design, because two handlers writing the same
     * file would interleave.
     */
    internal fun resetForTesting() = installed.set(false)

    private const val HEADER_PREFIX = "==="
    private const val HEADER_SUFFIX = "==="

    /**
     * Splits BEFORE a record header, so a truncated tail leaves a partial record
     * that is still parseable rather than one merged into its predecessor.
     */
    private val RECORD_BOUNDARY = Regex("""(?m)^(?==== )""")
}