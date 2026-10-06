package com.opencode.chat.util

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
 *   adb exec-out run-as com.opencode.chat cat files/app.log
 *
 * Intentionally synchronous and unsynchronized: this is a crash diagnostic, so
 * correctness matters more than throughput, and losing a line during a hard
 * kill is acceptable.
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val MAX_BYTES = 512 * 1024
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val f = File(context.filesDir, "app.log")
        file = f
        // ROLL, do not erase.
        //
        // This used to `writeText("")` on every launch, which is fatal for the
        // one scenario the log exists to explain: the app crashes, the user
        // relaunches it to see what happened, and the evidence is already gone
        // before anything can read it. That is exactly the failure this project
        // hit when a friend's install died silently.
        //
        // The previous run is moved to app-prev.log, so the current run stays
        // small and readable while the crash immediately before it survives.
        runCatching {
            if (f.exists() && f.length() > 0) {
                val prev = File(context.filesDir, "app-prev.log")
                prev.delete()
                f.renameTo(prev)
            }
            f.createNewFile()
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        // Also to logcat, so existing tooling keeps working.
        val body = buildString {
            append(fmt.format(Date()))
            append(' ').append(level).append('/').append(tag)
            append(": ").append(msg)
            t?.let {
                append('\n').append(it.stackTraceToString())
            }
        }
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            "I" -> Log.i(tag, msg, t)
            else -> Log.d(tag, msg, t)
        }
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText("")
            f.appendText(body + "\n")
        }
    }

    /** Path for the chaos harness / adb. */
    fun path(): String = file?.absolutePath ?: "(not initialised)"
}