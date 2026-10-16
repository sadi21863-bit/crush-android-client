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
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_ON_ROTATE = MAX_BYTES / 2

    private var file: File? = null
    private var prevFile: File? = null
    private var sizeBytes = 0L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val writeLock = Any()

    fun init(context: Context) {
        synchronized(writeLock) {
            val dir = context.filesDir
            val f = File(dir, "app.log")
            val prev = File(dir, "app-prev.log")
            file = f
            prevFile = prev
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
                    rotateKeepingTail(f, prevFile, body, KEEP_ON_ROTATE)
                    sizeBytes = f.length()
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
 * Keeps the most recent half of [current] and appends [newest].
 *
 * Returns the content that was written. Extracted as a pure function so the
 * policy can be tested without a filesystem or Android Context; the property
 * that matters is "the newest entry survives even when the cap trips", which
 * the previous `writeText("")` violated.
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
            archived.delete()
            dropped.forEachIndexed { i, line ->
                if (i == 0) archived.writeText(line + "\n") else archived.appendText(line + "\n")
            }
        }
        current.writeText(text)
    }
    return text
}
