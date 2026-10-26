package com.crush.mobile.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal file logger, because logcat is unusable for diagnosis on this device.
 *
 * The Redmi Note 7 Pro's logcat is flooded with telephony/system spam and the
 * buffer wraps within minutes, so a crash loop loses its own evidence. The
 * process also dies without any FATAL EXCEPTION, which is exactly when logcat
 * is most likely to have already rotated the relevant lines away.
 *
 * Writes to app-private storage; pull with:
 *   adb exec-out run-as com.crush.mobile cat files/app.log
 *
 * CORRECTNESS NOTES (all three used to be wrong)
 *
 * 1. ROLL, do not erase. On init the previous run becomes app-prev.log, so the
 *    current run stays readable while the crash immediately before it survives.
 *
 * 2. Rotation keeps the **tail**, not the head. The old cap did
 *    `if (f.length() > MAX) f.writeText("")`, which erased the entire buffer at
 *    exactly the moment a long-running fault finally tripped the cap - so the
 *    entries that caused it were the ones deleted. When a crash is what you are
 *    chasing, the interesting lines are the most recent ones, so they are the
 *    ones worth keeping.
 *
 * 3. Writes are serialised. `appendText` opens, writes and closes on every call,
 *    so two threads could interleave mid-line and produce a corrupt log - and a
 *    log that cannot be parsed is not evidence. The heartbeat and the SSE reader
 *    both log on background threads, so this is not hypothetical.
 *
 * 4. HISTORY IS THE POINT (2026-10-25). The user tests this app standalone -
 *    away from adb - and then asks what happened on those days. Two sinks that
 *    kept overwriting each other made that impossible: this run's roll replaces
 *    `app-prev.log` at init, and a mid-run rotation DELETED that file to write
 *    an evicted head into it, so only the newest two files ever existed (the
 *    Oct-23 standalone evidence was gone from disk before it was audited, and
 *    had to be reconstructed from the engine's session DB). Now evicted heads
 *    and each run's roll append to a dated `app-archive-<day>.log`, and every
 *    archive older than [RETENTION_DAYS] is deleted - along with a total-size
 *    cap so a pathological loop cannot fill storage.
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_ON_ROTATE = MAX_BYTES / 2

    /** How long dated archives stay on disk. The user asked for 7-30 days. */
    internal const val RETENTION_DAYS = 30
    internal val RETENTION_MS = RETENTION_DAYS * 24L * 60 * 60 * 1000

    /**
     * Hard ceiling on total archive bytes. 30 days of worst-case evictions
     * (512KB/day at the current cap) is ~15MB; the cap exists for the runaway
     * case, not the expected one, and prunes oldest-first.
     */
    private const val MAX_ARCHIVE_BYTES = 20L * 1024 * 1024

    private var file: File? = null
    private var sizeBytes = 0L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val writeLock = Any()

    fun init(context: Context) {
        synchronized(writeLock) {
            val dir = context.filesDir
            val f = File(dir, "app.log")
            val prev = File(dir, "app-prev.log")
            file = f
            runCatching {
                // The previous run's content is about to be displaced by the
                // roll below. Before that happens, append it into the dated
                // archive for the day it was written, so history accumulates
                // instead of being overwritten in place.
                if (prev.exists() && prev.length() > 0) {
                    val sink = File(dir, archiveFileName(prev.lastModified()))
                    if (sink.length() > 0) sink.appendText("\n")
                    sink.appendText(prev.readText())
                }
                val deleted = pruneArchives(
                    dir, System.currentTimeMillis(), RETENTION_MS, MAX_ARCHIVE_BYTES
                )
                if (deleted > 0) AppLog.i(TAG, "pruned $deleted log archive(s) past $RETENTION_DAYS days")
            }
            runCatching {
                // ROLL, do not erase.
                //
                // This used to `writeText("")` on every launch, which is fatal
                // for the one scenario the log exists to explain: the app
                // crashes, the user relaunches it to see what happened, and the
                // evidence is already gone before anything can read it.
                if (f.exists() && f.length() > 0) {
                    prev.delete()
                    f.renameTo(prev)
                }
                f.createNewFile()
                sizeBytes = f.length()
            }
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val body = buildString {
            append(fmt.format(Date()))
            append(' ').append(level).append('/').append(tag)
            append(": ").append(msg)
            t?.let { append('\n').append(it.stackTraceToString()) }
        }

        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            "I" -> Log.i(tag, msg, t)
            else -> Log.d(tag, msg, t)
        }

        val f = file ?: return
        // Serialised so concurrent writers cannot interleave a line. The size is
        // cached rather than stat'd on every call, which is what a heartbeat
        // logging every second would otherwise pay each time.
        synchronized(writeLock) {
            runCatching {
                if (sizeBytes + body.length > MAX_BYTES) {
                    // Evicted heads go to the dated archive for today, not to
                    // app-prev.log: that file is this app's last-run snapshot
                    // and is replaced at init. A long-lived supervisor run that
                    // rolls several times must not erase what it archived an
                    // hour ago - and the day file survives the next launch.
                    val sink = File(f.parentFile, archiveFileName(System.currentTimeMillis()))
                    rotateKeepingTail(f, sink, body, KEEP_ON_ROTATE)
                    sizeBytes = f.length()
                    pruneArchives(f.parentFile, System.currentTimeMillis(), RETENTION_MS, MAX_ARCHIVE_BYTES)
                } else {
                    f.appendText(body + "\n")
                    sizeBytes += body.length + 1
                }
            }
        }
    }

    /** Path for the chaos harness / adb. */
    fun path(): String = file?.absolutePath ?: "(not initialised)"
}

/**
 * Dated archive filename for a moment in time, in the device's local day - the
 * user thinks in the days they were testing, not in UTC.
 */
internal fun archiveFileName(dayMs: Long): String {
    val name = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayMs))
    return "app-archive-$name.log"
}

/**
 * Deletes dated log archives once they are past [retentionMs] old, then
 * evicts the OLDEST remaining archives while the directory's total archive
 * size exceeds [capBytes]. Only files named `app-archive-*` are candidates;
 */
internal fun pruneArchives(
    dir: File,
    nowMs: Long,
    retentionMs: Long,
    capBytes: Long
): Int {
    if (!dir.isDirectory) return 0
    val archives = dir.listFiles { f -> f.isFile && f.name.startsWith("app-archive-") }
        ?.sortedBy { it.lastModified() }
        ?: return 0

    var deleted = 0
    var total = 0L
    val survivors = ArrayList<File>()
    for (f in archives) {
        val age = nowMs - f.lastModified()
        if (age > retentionMs && f.delete()) deleted++
        else survivors.add(f)
    }
    for (f in survivors) total += f.length()
    // Oldest first: recent evidence outranks old evidence by construction.
    val oldestFirst = survivors.sortedBy { it.lastModified() }.iterator()
    while (total > capBytes && oldestFirst.hasNext()) {
        val f = oldestFirst.next()
        val n = f.length()
        if (f.delete()) {
            deleted++
            total -= n
        }
    }
    return deleted
}

/**
 * Keeps the most recent half of [current] and appends [newest].
 *
 * Returns the content that was written. Extracted as a pure function so the
 * policy can be tested without a filesystem or Android Context; the property
 * that matters is "the newest entry survives even when the cap trips", which
 * the previous `writeText("")` violated.
 *
 * The evicted head is APPENDED to [archived], never replacing its content:
 * [archived] is a dated day-file that accumulates every rotation of that day
 * (and, with the same key semantics, each roll at init). The first cut of this
 * feature deleted the archive file first - correct when the sink was a
 * single-file scratch buffer, destructive against a day file that other
 * rotations had already contributed to.
 */
internal fun rotateKeepingTail(
    current: File,
    archived: File?,
    newest: String,
    budget: Int
): String {
    val lines = runCatching {
        if (current.exists()) current.readLines() else emptyList()
    }.getOrDefault(emptyList())

    // Walk backwards until the tail fills the keep budget. The head is dropped:
    // a long fault's early lines are history, its latest ones are the evidence.
    val kept = ArrayList<String>()
    var bytes = 0
    for (line in lines.asReversed()) {
        val n = line.length + 1
        if (bytes + n > budget && kept.isNotEmpty()) break
        kept.add(line)
        bytes += n
    }
    kept.reverse()

    // Only the evicted head goes to the archive. The first implementation
    // renamed the whole file there, so the "archive" held everything including
    // the tail that was also written back to the live log - the two files
    // duplicated each other and "the archive has the head" was satisfied while
    // "the live log kept the tail" was not.
    val droppedCount = lines.size - kept.size
    val dropped = lines.subList(0, droppedCount)
    val text = if (kept.isEmpty()) newest + "\n" else kept.joinToString("\n") + "\n" + newest + "\n"

    runCatching {
        if (archived != null && droppedCount > 0) {
            dropped.forEachIndexed { i, line ->
                if (i == 0 && !archived.exists()) archived.writeText(line + "\n")
                else archived.appendText(line + "\n")
            }
        }
        current.writeText(text)
    }
    return text
}
