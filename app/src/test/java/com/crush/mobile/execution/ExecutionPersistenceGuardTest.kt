package com.crush.mobile.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic pins for the guarded durable-write decision on the send path. */
class ExecutionPersistenceGuardTest {

    @Test
    fun `only one affected row counts as a committed write`() {
        assertTrue(ExecutionPersistenceGuard.committed(Result.success(1)))
        assertFalse(ExecutionPersistenceGuard.committed(Result.success(0)))
        assertFalse(ExecutionPersistenceGuard.committed(Result.success(2)))
        assertFalse(
            ExecutionPersistenceGuard.committed(Result.failure(IllegalStateException("db"))),
        )
    }

    @Test
    fun `markSent returning zero prevents the POST`() {
        var posts = 0
        val marked = Result.success(0)
        if (ExecutionPersistenceGuard.committed(marked)) posts++
        assertEquals(0, posts)
    }

    @Test
    fun `markSent throwing prevents the POST`() {
        var posts = 0
        val marked: Result<Int> = runCatching { throw IllegalStateException("db closed") }
        if (ExecutionPersistenceGuard.committed(marked)) posts++
        assertEquals(0, posts)
    }

    @Test
    fun `a committed markSent permits the POST`() {
        var posts = 0
        val marked = Result.success(1)
        if (ExecutionPersistenceGuard.committed(marked)) posts++
        assertEquals(1, posts)
    }

    @Test
    fun `markAcked zero or throw does not advance the controller state`() {
        var acknowledged = false
        listOf(Result.success(0), Result.failure<Int>(IllegalStateException("db"))).forEach { r ->
            if (ExecutionPersistenceGuard.committed(r)) acknowledged = true
            assertFalse(acknowledged)
        }
    }
}
