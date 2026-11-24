package com.crush.mobile.di

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V2-15 L1: the shutdown plan is the one place that owns explicit termination.
 * It must stop every snapshot member once, isolate a throwing stop, and be
 * idempotent. These are plain-JVM tests; no container/Android is constructed.
 */
class ShutdownPlanTest {

    @Test
    fun `every project and the legacy supervisor stop once in order`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val plan = ShutdownPlan(
            legacy = { calls += "legacy" },
            projects = listOf(
                { calls += "p1" },
                { calls += "p2" },
            ),
            onFailure = { what, _ -> failures += what },
        )

        assertEquals(3, plan.run())
        assertEquals(listOf("p1", "p2", "legacy"), calls)
        assertEquals(emptyList<String>(), failures)

        assertEquals("second shutdown is a no-op", 0, plan.run())
        assertEquals("no stop may run twice", listOf("p1", "p2", "legacy"), calls)
    }

    @Test
    fun `a throwing project stop does not prevent the others or legacy`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val plan = ShutdownPlan(
            legacy = { calls += "legacy" },
            projects = listOf(
                { calls += "p1" },
                { throw IllegalStateException("boom") },
                { calls += "p3" },
            ),
            onFailure = { what, _ -> failures += what },
        )

        assertEquals(3, plan.run())
        assertEquals(listOf("p1", "p3", "legacy"), calls)
        assertEquals(listOf("project[1]"), failures)
    }

    @Test
    fun `a throwing legacy stop still reports and leaves projects stopped`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val plan = ShutdownPlan(
            legacy = { throw IllegalStateException("legacy boom") },
            projects = listOf({ calls += "p1" }),
            onFailure = { what, _ -> failures += what },
        )

        assertEquals(1, plan.run())
        assertEquals(listOf("p1"), calls)
        assertEquals(listOf("legacy"), failures)
    }

    @Test
    fun `an empty partial-init plan is safe and idempotent`() {
        val plan = ShutdownPlan(legacy = null, projects = emptyList(), onFailure = { _, _ -> })
        assertEquals(0, plan.run())
        assertEquals(0, plan.run())
    }
}
