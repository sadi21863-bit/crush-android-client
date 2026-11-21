package com.crush.mobile.util

/**
 * O11 harness (2026-11-21): a debug-only stall before the session-list fetch.
 *
 * The loading row is real, but it lives in a sub-second window, so two manual
 * parity passes could not photograph it (V2-0 O11). A debuggable build may arm
 * the stall from its launch intent:
 *
 * ```
 * adb shell am start -n com.crush.mobile/.MainActivity \
 *     --el crush.debug.stall_sessions_ms 8000
 * ```
 *
 * Release builds are non-debuggable, so [sessionsMs] returns 0 and the delay is
 * never taken. No product behavior depends on this value.
 */
object DebugStall {
    const val SESSIONS_EXTRA = "crush.debug.stall_sessions_ms"

    /** Far below a hang detector's grace and enough to capture by hand. */
    const val MAX_MS = 15_000L

    fun sessionsMs(debuggable: Boolean, extraMs: Long): Long =
        if (!debuggable) 0L else extraMs.coerceIn(0L, MAX_MS)
}
