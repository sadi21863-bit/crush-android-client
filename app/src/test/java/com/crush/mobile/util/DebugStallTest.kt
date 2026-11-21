package com.crush.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O11 harness: the stall exists only for a debuggable launch extra, never for
 * a release build and never outside the bounded window.
 */
class DebugStallTest {

    @Test
    fun `release builds never stall`() {
        assertEquals(0L, DebugStall.sessionsMs(debuggable = false, extraMs = 8_000L))
    }

    @Test
    fun `a debug launch keeps a positive stall`() {
        assertEquals(8_000L, DebugStall.sessionsMs(debuggable = true, extraMs = 8_000L))
        assertEquals(0L, DebugStall.sessionsMs(debuggable = true, extraMs = 0L))
    }

    @Test
    fun `negative and oversized values clamp`() {
        assertEquals(0L, DebugStall.sessionsMs(debuggable = true, extraMs = -5L))
        assertEquals(
            DebugStall.MAX_MS,
            DebugStall.sessionsMs(debuggable = true, extraMs = 99_999L),
        )
    }
}
