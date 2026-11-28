package com.crush.mobile.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 2: declarations are retained after ACK and read-only when superseded. */
class RunDeclarationLifecycleTest {

    private fun adapter() = ExecutionEventAdapter(
        epochOf = { 0L },
        onConfirmedEvent = { _, _ -> },
    )

    @Test
    fun `declaration is retained and resolves to exact identity`() {
        val a = adapter()
        a.declare("pA", "run-A", 7L, "exec-A")
        val d = a.resolveDeclaration("pA", "run-A")
        assertNotNull(d)
        assertEquals("exec-A", d!!.executionId)
        assertEquals(7L, d.generation)
        assertEquals(ExecutionEventAdapter.Declaration.State.ACTIVE, d.state)
        assertNull("declarations must never resolve across instance keys", a.resolveDeclaration("pB", "run-A"))
    }

    @Test
    fun `a superseding run keeps the old declaration read-only`() {
        val a = adapter()
        a.declare("pA", "run-A", 7L, "exec-A")
        a.declare("pA", "run-B", 8L, "exec-B")

        val old = a.resolveDeclaration("pA", "run-A")!!
        val current = a.resolveDeclaration("pA", "run-B")!!
        assertEquals("exec-A", old.executionId)
        assertEquals(ExecutionEventAdapter.Declaration.State.SUPERSEDED, old.state)
        assertEquals("exec-B", current.executionId)
        assertEquals(ExecutionEventAdapter.Declaration.State.ACTIVE, current.state)
    }

    @Test
    fun `terminal observation retires instead of dropping identity`() {
        val a = adapter()
        a.declare("pA", "run-A", 7L, "exec-A")
        a.retireRun("pA", "run-A")

        val retired = a.resolveDeclaration("pA", "run-A")!!
        assertEquals("exec-A", retired.executionId)
        assertEquals(ExecutionEventAdapter.Declaration.State.RETIRED, retired.state)
    }

    @Test
    fun `an evicted declaration fails closed`() {
        val a = adapter()
        repeat(10) { i -> a.declare("pA", "run-$i", 7L, "exec-$i") }
        assertNull(a.resolveDeclaration("pA", "run-0"))
        assertEquals("exec-9", a.resolveDeclaration("pA", "run-9")!!.executionId)
    }

    @Test
    fun `forgetRun is explicit invalidation not an ACK side effect`() {
        val a = adapter()
        a.declare("pA", "run-A", 7L, "exec-A")
        a.forgetRun("pA", "run-A")
        assertNull(a.resolveDeclaration("pA", "run-A"))
        assertFalse(a.isDeclared("pA", "run-A"))
    }

    @Test
    fun `no latest-run fallback resolves an unknown run id`() {
        val a = adapter()
        a.declare("pA", "run-A", 7L, "exec-A")
        assertNull(a.resolveDeclaration("pA", "run-unknown"))
        assertTrue(a.isDeclared("pA", "run-A"))
    }
}
