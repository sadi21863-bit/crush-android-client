package com.crush.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1 of the Gate-7 design memo (2026-11-05): the per-instance engine
 * registry's pure bookkeeping rules, pinned in plain JUnit without Android
 * dependencies (the memo's "Unit (pure JVM)" row).
 *
 * The runtime record here is a fake on purpose - the CONTRACT under test is
 * the registry's: idempotent construction, never-evict / only-stop, the
 * non-legacy liveness budget and its named refusal, the grain transitions
 * stop() paints, and the tuning fan-out reaching every registered instance
 * including a STOPPED one.
 */
class EngineInstanceRegistryTest {

    private class FakeRuntime(
        val key: String,
        var childAlive: Boolean = false,
        var state: EngineState = EngineState.Stopped,
        var stopped: Boolean = false,
    )

    /**
     * Shared wiring: liveness derives from plain fields (the volatility-only
     * shape the real wiring has), and the stop callback records which grain
     * the registry was holding WHILE the stop ran - the memo's "records
     * STOPPING while a stop's destroy + waitFor is pending".
     */
    private class TestBed {
        val grainsAtStopCall = mutableListOf<EngineInstanceRegistry.Grain>()

        /**
         * The stop callback needs the registry that IS being constructed here.
         * A direct capture inside the constructor call is a recursive
         * initializer the compiler refuses; a late-wired explicit reference
         * breaks it (Kotlin's suggested "specify the types explicitly" shape).
         */
        var registryRef: EngineInstanceRegistry<FakeRuntime>? = null

        val registry: EngineInstanceRegistry<FakeRuntime> = EngineInstanceRegistry<FakeRuntime>(
            legacyKey = "",
            livenessOf = { rt -> EngineInstanceRegistry.Liveness(rt.childAlive, rt.state) },
            stopRuntime = { rt ->
                rt.stopped = true
                registryRef?.inspect()?.firstOrNull { it.key == rt.key }?.let {
                    grainsAtStopCall.add(it.grain)
                }
            },
        ).also { registryRef = it }
    }

    private fun bed(): TestBed = TestBed()

    private fun liveChild(key: String): FakeRuntime =
        // Same Running shape the budget tests below construct: sentinel pid/port,
        // a short version string, engineReady - only the state class matters here.
        FakeRuntime(key, childAlive = true, state = EngineState.Running(1, 1, "v", 0))

    // ------------------------------------------------------------------
    // runtimeFor: idempotent per key; never evicted, only stopped
    // ------------------------------------------------------------------

    @Test
    fun `runtime construction is idempotent per key`() {
        val t = bed()
        var builds = 0
        val a = t.registry.runtimeFor("p1") { builds++; FakeRuntime("p1") }
        val b = t.registry.runtimeFor("p1") { builds++; FakeRuntime("p1") }
        assertEquals("a second runtimeFor for the same key must be the SAME object", a, b)
        assertEquals("the builder must run exactly once per key per process", 1, builds)
        assertEquals("other keys stay absent", 1, t.registry.inspect().size)
    }

    @Test
    fun `distinct keys construct distinct runtimes that stay resident`() {
        val t = bed()
        t.registry.runtimeFor("p1") { FakeRuntime("p1") }
        t.registry.runtimeFor("p2") { FakeRuntime("p2") }
        assertEquals(2, t.registry.inspect().size)
        assertTrue(
            "both keys stay registered alongside each other - no slot to evict",
            t.registry.inspect().map { it.key }.containsAll(listOf("p1", "p2"))
        )
    }

    @Test
    fun `a stopped key is restarted, never rebuilt`() {
        val t = bed()
        var builds = 0
        val first = t.registry.runtimeFor("p1") { builds++; FakeRuntime("p1") }
        t.registry.stop("p1", "switch away")
        val second = t.registry.runtimeFor("p1") { builds++; FakeRuntime("p1") }
        assertEquals(
            "ONE object per key, forever: restart allowed, rebuild is the old eviction hazard",
            first,
            second
        )
        assertEquals(1, builds)
        assertTrue("the stop must have run exactly once", first.stopped)
    }

    // ------------------------------------------------------------------
    // stop: lookup-or-noop; grain STOPPING while stopping, STOPPED after
    // ------------------------------------------------------------------

    @Test
    fun `stop is lookup-or-noop for a key that was never registered`() {
        val t = bed()
        assertFalse(
            "an unregistered key has nothing to stop (the old code built a throwaway pair here)",
            t.registry.stop("p-never", "project removed")
        )
        assertEquals(
            "the lookup must have constructed nothing",
            0,
            t.registry.inspect().size
        )
    }

    @Test
    fun `stop marks STOPPING while the stop runs and STOPPED after`() {
        val t = bed()
        t.registry.runtimeFor("p1") { FakeRuntime("p1") }
        t.registry.stop("p1", "switch away")
        assertEquals(
            "the stop callback must have observed the STOPPING grain",
            listOf(EngineInstanceRegistry.Grain.STOPPING),
            t.grainsAtStopCall
        )
        assertEquals(
            EngineInstanceRegistry.Grain.STOPPED,
            t.registry.inspect().single { it.key == "p1" }.grain
        )
        assertTrue("the runtime itself was told to stop", t.registry.inspect().single().runtime.stopped)
    }

    @Test
    fun `the legacy key never stops and never carries a budget slot`() {
        val t = bed()
        assertFalse("legacy is permanently resident outside this registry", t.registry.stop("", "r"))
        assertFalse("legacy is out of the registry, blank included", t.registry.stop("", "r"))
        assertEquals("legacy must never appear in the registry", 0, t.registry.inspect().size)
    }

    // ------------------------------------------------------------------
    // liveness budget: boundary 2 + named refusal, fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `budget allows exactly the first two live non-legacy instances`() {
        val t = bed()
        assertTrue("0 others live -> start allowed", t.registry.budgetAllows(0))
        assertTrue("1 other live -> chat + one project still within the plan", t.registry.budgetAllows(1))
        assertFalse(
            "2 others live -> a third child is structurally unreachable (phase-1 interlock owns this too)",
            t.registry.budgetAllows(2)
        )
    }

    @Test
    fun `a key with live child counts - child alive, Starting or Running`() {
        val pure = EngineInstanceRegistry<FakeRuntime>(
            legacyKey = "", livenessOf = { EngineInstanceRegistry.Liveness(false, EngineState.Stopped) },
            stopRuntime = {},
        )
        fun holds(childAlive: Boolean, state: EngineState) =
            pure.keyHasLiveChild(EngineInstanceRegistry.Liveness(childAlive, state))
        assertTrue("a live child is live whatever the state grain says", holds(true, EngineState.Stopped))
        assertTrue("Starting holds a child slot", holds(false, EngineState.Starting(1)))
        assertTrue("Running holds a child slot", holds(false, EngineState.Running(1, 1, "v", 0)))
        assertFalse("a stopped supervisor without a child frees its slot", holds(false, EngineState.Stopped))
        assertFalse(
            "Failed frees the slot the same way (the supervisor already gave up)",
            holds(false, EngineState.Failed("engine readiness failed"))
        )
    }

    @Test
    fun `requestStart refuses the third live instance with a named reason`() {
        val t = bed()
        t.registry.runtimeFor("p1") { liveChild("p1") }
        t.registry.runtimeFor("p2") { liveChild("p2") }
        val refusal = t.registry.requestStart("p3")
        assertTrue(
            "a full budget must fail CLOSED",
            refusal != null
        )
        assertTrue(
            "the refusal names the key",
            refusal!!.contains("'p3'")
        )
        assertTrue(
            "the refusal names the counts so the log line is self-explanatory",
            refusal.contains("2/2")
        )
    }

    @Test
    fun `requestStart reclaims its own slot - the key's own child never counts`() {
        val t = bed()
        t.registry.runtimeFor("p1") { liveChild("p1") }
        t.registry.runtimeFor("p2") { liveChild("p2") }
        assertNull(
            "restarting live key 'p1' reuses its own slot (others=1 < 2)",
            t.registry.requestStart("p1")
        )
    }

    @Test
    fun `requestStart allows within budget and paints STARTING, then the start hook paints RUNNING`() {
        val t = bed()
        t.registry.runtimeFor("p1") { FakeRuntime("p1") }
        assertNull(t.registry.requestStart("p1"))
        assertEquals(EngineInstanceRegistry.Grain.STARTING, t.registry.inspect().single().grain)
        t.registry.recordRunning("p1")
        assertEquals(EngineInstanceRegistry.Grain.RUNNING, t.registry.inspect().single().grain)
    }

    @Test
    fun `a refusal never rewrites the grain`() {
        val t = bed()
        t.registry.runtimeFor("p1") { liveChild("p1") }
        t.registry.runtimeFor("p2") { liveChild("p2") }
        t.registry.requestStart("p3")
        assertEquals(
            "p2's grain stays its own truth (bookkeeping, not budget input)",
            EngineInstanceRegistry.Grain.IDLE,
            t.registry.inspect().single { it.key == "p2" }.grain
        )
    }

    @Test
    fun `legacy start request is never budget-gated`() {
        val t = bed()
        t.registry.runtimeFor("p1") { liveChild("p1") }
        t.registry.runtimeFor("p2") { liveChild("p2") }
        assertNull("legacy is the permanent process-single pair, uncapped", t.registry.requestStart(""))
    }

    @Test
    fun `a budget of one refuses the second live instance`() {
        val tiny = EngineInstanceRegistry<FakeRuntime>(
            maxLiveNonLegacy = 1,
            legacyKey = "",
            livenessOf = { EngineInstanceRegistry.Liveness(false, EngineState.Stopped) },
            stopRuntime = {},
        )
        tiny.runtimeFor("p1") { liveChild("p1") }
        assertTrue(
            "max=1 means exactly one non-legacy child",
            !tiny.budgetAllows(1) && tiny.budgetAllows(0)
        )
        assertTrue(
            "even a custom budget names its refusal",
            tiny.refusalReason("p2", 1).contains("'p2'")
        )
    }

    // ------------------------------------------------------------------
    // tuning fan-out: every registered runtime, stopped ones included
    // ------------------------------------------------------------------

    @Test
    fun `tuning fan-out reaches registered STOPPED runtimes`() {
        val t = bed()
        t.registry.runtimeFor("p1") { FakeRuntime("p1") }
        t.registry.runtimeFor("p2") { FakeRuntime("p2") }
        t.registry.stop("p1", "switch away") // p1 is now STOPPED but still registered
        val visited = mutableListOf<String>()
        var count = 0
        t.registry.forEachRuntime { rt ->
            count++
            visited.add(rt.key)
        }
        assertTrue(
            "a stopped-but-registered runtime must not lose settings (§4 risk 9)",
            visited.contains("p1")
        )
        assertEquals("every registered key is visited exactly once", 2, count)
        assertEquals("visited keys are the registered keys", setOf("p1", "p2"), visited.toSet())
    }
}
