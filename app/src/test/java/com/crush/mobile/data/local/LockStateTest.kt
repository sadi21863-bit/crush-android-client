package com.crush.mobile.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the temporal semantics of the app-lock gate's process-scoped state.
 *
 * These pins exist because each wrong answer here was once a real device bug:
 * trusting the key cache evaporated a successful authentication into a second
 * prompt, and activity-scoped stamps were forgotten by the activity recreation
 * Android performs around credential-screen closure. All times are injected
 * milliseconds - SystemClock cannot be mocked from a JVM test, and the policy
 * here is pure relative arithmetic over them.
 */
class LockStateTest {

    private var now = 10_000L

    private lateinit var lock: LockState

    @Before
    fun setUp() {
        lock = LockState(clockMillis = { now })
    }

    @Test
    fun `fresh process has nobody verified and no trust age`() {
        assertFalse(lock.ownerVerified)
        assertFalse(lock.isFreshUnlock())
        // No pause observed yet - decide() fails closed on a null age.
        assertNull(lock.trustAgeSeconds())
        assertFalse(lock.ageIfAbsent(graceSeconds = 0))
    }

    @Test
    fun `unlock after an absence is fresh with a restarted trust age`() {
        lock.notePause(nowElapsed = 10_000L)
        now = 20_000L
        lock.noteUnlock()
        assertTrue(lock.isFreshUnlock())
        assertEquals(0L, lock.trustAgeSeconds())
        assertTrue(lock.ownerVerified)
    }

    @Test
    fun `unlock before a pause is not fresh`() {
        lock.noteUnlock(nowElapsed = 10_000L)
        lock.notePause(nowElapsed = 15_000L)
        now = 25_000L
        assertFalse(lock.isFreshUnlock())
        assertEquals(10L, lock.trustAgeSeconds())
    }

    @Test
    fun `aged absence clears verification and asks the caller to drop the key`() {
        lock.noteUnlock(nowElapsed = 10_000L)
        lock.notePause(nowElapsed = 12_000L)
        now = 12_000L + 301_000L
        assertTrue(lock.ageIfAbsent(graceSeconds = 300))
        assertFalse(lock.ownerVerified)
    }

    @Test
    fun `short absence never ages`() {
        lock.noteUnlock(nowElapsed = 10_000L)
        lock.notePause(nowElapsed = 12_000L)
        now = 12_000L + 299_000L
        assertFalse(lock.ageIfAbsent(graceSeconds = 300))
        assertTrue(lock.ownerVerified)
    }

    @Test
    fun `a fresh unlock is never aged no matter how long the absence was`() {
        // THE double-prompt shape: the app has been away for a long time, the
        // user authenticates on the overlay, and the resume that follows must
        // not discard the auth it just completed.
        lock.notePause(nowElapsed = 0L)
        now = 900_000L
        lock.noteUnlock()
        assertFalse(lock.ageIfAbsent(graceSeconds = 300))
        assertTrue(lock.ownerVerified)
        assertEquals(0L, lock.trustAgeSeconds())
    }

    @Test
    fun `REGRESSION activity recreation cannot forget a fresh unlock`() {
        // Device trace 2026-10-20: unlock succeeded -> gate OPEN, then the
        // activity was recreated 3 seconds later (coldStart=true, away=-1 on
        // the fresh instance) and every instance field reset to false/null,
        // re-prompting a just-authenticated user. Stamps and flags live in the
        // process now, so a recreate is invisible to the gate.
        lock.notePause(nowElapsed = 0L)
        now = 475_000L
        lock.noteUnlock()
        // ... new instance takes over; it reads the same container state ...
        assertTrue(lock.isFreshUnlock())
        assertTrue(lock.ownerVerified)
        assertEquals(0L, lock.trustAgeSeconds())
    }

    @Test
    fun `strict mode with zero grace still ages a pre-unlock absence`() {
        // graceSeconds=0 means prompt on every resume. Only a fresh unlock
        // suppresses the age, and this shape has the unlock BEFORE the pause,
        // so the flag is dropped.
        lock.noteUnlock(nowElapsed = 0L)
        lock.notePause(nowElapsed = 1_000L)
        now = 1_000L
        assertTrue(lock.ageIfAbsent(graceSeconds = 0))
        assertFalse(lock.ownerVerified)
    }

    @Test
    fun `reset clears all temporal state`() {
        lock.notePause(nowElapsed = 1_000L)
        lock.noteUnlock(nowElapsed = 2_000L)
        lock.reset()
        assertFalse(lock.ownerVerified)
        assertNull(lock.pausedAtElapsedMs)
        assertNull(lock.lastUnlockElapsedMs)
    }
}
