package com.opencode.chat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.opencode.chat.di.AppContainer
import com.opencode.chat.util.AppLog

class OpenCodeChatApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Installed BEFORE AppLog.init and before the crash handler so a crash
        // during startup is still archived. AppLog rolls its file on init, so a
        // crash recorded only in app.log would be rotated away on the next
        // launch - which is the moment the user relaunches to report it.
        com.opencode.chat.ui.screens.diagnostics.DiagnosticsRecorder.install(this)
        AppLog.init(this)
        installCrashHandler()
        container = AppContainer(this)
        logNavigation()
        startHeartbeat()
        AppLog.i(TAG, "app started pid=${android.os.Process.myPid()} log=${AppLog.path()}")
    }

    /**
     * Catches anything that escapes to the main thread and writes it to file
     * BEFORE handing off to the default handler.
     *
     * This exists because the "Start chatting" crash produced no FATAL
     * EXCEPTION in logcat at all, so a plain uncaught handler may not fire. It
     * is cheap insurance, not the primary diagnostic: see the heartbeat.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e(TAG, "UNCAUGHT on '${thread.name}'", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * A cheap periodic log line. If the process dies with NO uncaught exception
     * and NO further heartbeat, that proves the process was killed from outside
     * (system/OOM/native) rather than crashing, which is what the
     * "Start chatting" death looked like.
     */
    private fun startHeartbeat() {
        val handler = Handler(Looper.getMainLooper())
        val started = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                AppLog.d(TAG, "alive t=${SystemClock.elapsedRealtime() - started}ms")
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(tick, 2000)
    }

    /** Makes the navigation that precedes a crash visible after the fact. */
    private fun logNavigation() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun stamp(a: Activity, s: String) =
                AppLog.i(TAG, "$s ${a.javaClass.simpleName}@${a.hashCode()}")

            override fun onActivityCreated(a: Activity, b: Bundle?) = stamp(a, "create")
            override fun onActivityStarted(a: Activity) = stamp(a, "start")
            override fun onActivityResumed(a: Activity) = stamp(a, "resume")
            override fun onActivityPaused(a: Activity) = stamp(a, "pause")
            override fun onActivityStopped(a: Activity) = stamp(a, "stop")
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) =
                stamp(a, "saveState")

            override fun onActivityDestroyed(a: Activity) = stamp(a, "destroy")
        })
    }

    private companion object {
        const val TAG = "App"
    }
}