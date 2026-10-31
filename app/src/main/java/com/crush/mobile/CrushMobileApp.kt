package com.crush.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.crush.mobile.di.AppContainer
import com.crush.mobile.util.AppLog

class CrushMobileApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Installed BEFORE AppLog.init and before the crash handler so a crash
        // during startup is still archived. AppLog rolls its file on init, so a
        // crash recorded only in app.log would be rotated away on the next
        // launch - which is the moment the user relaunches to report it.
        com.crush.mobile.ui.screens.diagnostics.DiagnosticsRecorder.install(this)
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
     *
     * INTERVAL (analysis §4 P2): this used to fire every 2 seconds - roughly
     * 30 lines a minute, 1800 an hour. With the 512KB log keeping only the
     * newest ~200 lines, the diagnostics tail was mostly "alive" lines, so the
     * actual fault the user came for was routinely folded out of the copy-out.
     * 30 seconds keeps the forensic property the heartbeat exists for - a
     * killed process stops ticking while a crash leaves an UNCAUGHT line, so
     * kill-vs-crash attribution holds at any interval - and the cost is only
     * that the exact moment of an external kill is now known to a 30s window
     * instead of a 2s one, which is acceptable against a tail that must keep
     * real evidence visible.
     */
    private fun startHeartbeat() {
        val handler = Handler(Looper.getMainLooper())
        val started = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                AppLog.d(TAG, "alive t=${SystemClock.elapsedRealtime() - started}ms")
                handler.postDelayed(this, 30_000)
            }
        }
        handler.postDelayed(tick, 30_000)
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

    /**
     * P1-10: production never TORE the engine down - only the spike harness
     * called EngineSupervisor.stop(). This is the wiring that makes teardown
     * exist for the real app: stop() cancels the supervision loop and destroys
     * the child Crush process, so an explicitly terminated app leaves no
     * orphaned engine holding the app-private unix socket.
     *
     * HONEST LIMITATION, deliberately not hidden: Android does not guarantee
     * this callback at all. The normal death of an app process (swipe-away,
     * OOM kill, crash) never reaches onTerminate(); it fires only for explicit
     * termination requests. So this hook ENABLES the previously dead teardown
     * path for the termination requests that do call back; it cannot guarantee
     * the OS invokes it. See AppContainer.shutdown() - the honest fallback
     * route, kept minimal on purpose rather than adding the
     * androidx.lifecycle-process ProcessLifecycleOwner hook, whose dependency
     * is not present in app/build.gradle.kts.
     */
    override fun onTerminate() {
        AppLog.i(TAG, "app terminating; stopping embedded engine")
        runCatching { container.shutdown() }
            .onFailure { AppLog.e(TAG, "engine teardown failed", it) }
        super.onTerminate()
    }

    private companion object {
        const val TAG = "App"
    }
}