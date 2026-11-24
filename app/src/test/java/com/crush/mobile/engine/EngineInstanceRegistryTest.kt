package com.crush.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier

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
     * One racer's start-gate outcome. A holder, not a nullable String:
     * ConcurrentHashMap refuses null values and "accepted" IS a null result.
     */
    private class GateResult(val accepted: Boolean, val refusal: String?)

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
    fun `a STARTING reservation holds its slot before any child exists`() {
        val t = bed()
        t.registry.runtimeFor("p1") { liveChild("p1") } // occupies 1 of 2 slots
        t.registry.runtimeFor("p2") { FakeRuntime("p2") } // childless: reservation only
        t.registry.runtimeFor("p3") { FakeRuntime("p3") }
        assertNull("the last slot is free before anyone reserves it", t.registry.requestStart("p2"))
        assertEquals(
            "the accepted start is reserved, not yet live",
            EngineInstanceRegistry.Grain.STARTING,
            t.registry.inspect().single { it.key == "p2" }.grain
        )
        val refusal = t.registry.requestStart("p3")
        assertTrue(
            "a childless STARTING key still holds its slot - otherwise check-then-reserve races",
            refusal != null
        )
        assertTrue("the reservation is counted in the refusal", refusal!!.contains("2/2"))
    }

    @Test
    fun `a terminal start failure releases the reservation`() {
        val t = bed()
        val p1 = t.registry.runtimeFor("p1") { FakeRuntime("p1") } // reservation holder
        t.registry.runtimeFor("p2") { liveChild("p2") } // occupies the other slot
        t.registry.runtimeFor("p3") { FakeRuntime("p3") }
        assertNull(t.registry.requestStart("p1"))
        // The supervisor gives up: Failed(willRetry = false) and RETURNS, so
        // neither recordRunning nor stop ever touches this key's grain.
        p1.state = EngineState.Failed("x", "y", willRetry = false)
        assertNull(
            "a terminal failure must release the slot, not burn it for the whole process",
            t.registry.requestStart("p3")
        )
        assertEquals(
            "the grain itself is NOT rewritten - the release is a liveness read",
            EngineInstanceRegistry.Grain.STARTING,
            t.registry.inspect().single { it.key == "p1" }.grain
        )
    }

    @Test
    fun `a retrying start failure keeps the reservation`() {
        val t = bed()
        val p1 = t.registry.runtimeFor("p1") { FakeRuntime("p1") }
        t.registry.runtimeFor("p2") { liveChild("p2") }
        t.registry.runtimeFor("p3") { FakeRuntime("p3") }
        assertNull(t.registry.requestStart("p1"))
        // Still trying: the supervisor will hold a child on the next attempt,
        // so the slot stays reserved (a premature release would over-admit).
        p1.state = EngineState.Failed("x", "y", willRetry = true)
        val refusal = t.registry.requestStart("p3")
        assertTrue("a retrying reservation still holds its slot", refusal != null)
        assertTrue("the refusal names the counts", refusal!!.contains("2/2"))
    }

    @Test
    fun `simultaneous starts never both take the last non-legacy slot`() {
        // A8/A14: budget 2, one key already live -> EXACTLY one free slot; two
        // keys enter the gate together. Before the lock covered check+reserve,
        // both read "one free" and both returned null (the defect).
        val t = bed()
        t.registry.runtimeFor("existing") { liveChild("existing") }
        t.registry.runtimeFor("a") { FakeRuntime("a") }
        t.registry.runtimeFor("b") { FakeRuntime("b") }
        t.registry.runtimeFor("c") { FakeRuntime("c") }

        val gate = CyclicBarrier(2)
        val results = ConcurrentHashMap<String, GateResult>()
        fun racer(key: String) = Thread({
            gate.await() // both threads enter requestStart as close to together as possible
            val refusal = t.registry.requestStart(key)
            results[key] = GateResult(refusal == null, refusal)
        }, "start-gate-$key")

        val threads = listOf(racer("a"), racer("b"))
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertTrue(
            "both racers must finish; a hung gate would hide the race",
            threads.none { it.isAlive }
        )
        assertEquals("both racers must report an outcome", 2, results.size)

        val accepted = results.filterValues { it.accepted }.keys
        val refused = results.filterValues { !it.accepted }.keys
        assertEquals("exactly ONE start may claim the last slot", 1, accepted.size)
        assertEquals("the other must be refused - never both null", 1, refused.size)

        val winner = accepted.single()
        val loser = refused.single()
        assertEquals(
            "the accepted key holds the reservation before any child exists",
            EngineInstanceRegistry.Grain.STARTING,
            t.registry.inspect().single { it.key == winner }.grain
        )
        val refusal = results.getValue(loser).refusal!!
        assertTrue("the refusal names the losing key", refusal.contains("'$loser'"))
        assertTrue(
            "the refusal counts the winner's reservation as a held slot",
            refusal.contains("2/2")
        )
        assertTrue(
            "a third key is refused while the winner's reservation stands",
            t.registry.requestStart("c") != null
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

    // ------------------------------------------------------------------
    // Phase C: budget/lifecycle transitions
    // ------------------------------------------------------------------

    @Test
    fun `repeated start requests for one key keep one reservation`() {
        val t = bed()
        t.registry.runtimeFor("p1") { FakeRuntime("p1") }
        t.registry.runtimeFor("p2") { FakeRuntime("p2") }
        t.registry.runtimeFor("p3") { FakeRuntime("p3") }

        assertNull(t.registry.requestStart("p1"))
        assertNull("a repeated request reuses its own slot", t.registry.requestStart("p1"))
        assertEquals(
            EngineInstanceRegistry.Grain.STARTING,
            t.registry.inspect().single { it.key == "p1" }.grain
        )
        assertNull(
            "the second key can still use the remaining slot",
            t.registry.requestStart("p2")
        )
        val refusal = t.registry.requestStart("p3")
        assertTrue("the repeated request must not double-count p1", refusal != null)
        assertTrue(refusal!!.contains("2/2"))
    }

    @Test
    fun `stop during STARTING releases the reservation`() {
        val t = bed()
        t.registry.runtimeFor("p1") { FakeRuntime("p1") }
        t.registry.runtimeFor("p2") { FakeRuntime("p2") }
        t.registry.runtimeFor("p3") { FakeRuntime("p3") }

        assertNull(t.registry.requestStart("p1"))
        assertNull(t.registry.requestStart("p2"))
        assertTrue("both reservations fill the budget", t.registry.requestStart("p3") != null)

        assertTrue(t.registry.stop("p1", "project removed during startup"))
        assertEquals(
            EngineInstanceRegistry.Grain.STOPPED,
            t.registry.inspect().single { it.key == "p1" }.grain
        )
        assertNull(
            "the released slot is usable again",
            t.registry.requestStart("p3")
        )
    }

    @Test
    fun `stop racing requestStart for one key leaves a coherent budget`() {
        val reg = EngineInstanceRegistry<FakeRuntime>(
            maxLiveNonLegacy = 1,
            legacyKey = "",
            livenessOf = { rt -> EngineInstanceRegistry.Liveness(rt.childAlive, rt.state) },
            stopRuntime = { it.stopped = true },
        )
        reg.runtimeFor("p1") { FakeRuntime("p1") }
        reg.runtimeFor("p2") { FakeRuntime("p2") }

        val gate = CyclicBarrier(2)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val start = Thread({
            runCatching {
                gate.await()
                reg.requestStart("p1")
            }.onFailure { errors += it }
        }, "phase-c-start")
        val stop = Thread({
            runCatching {
                gate.await()
                reg.stop("p1", "phase-c stop race")
            }.onFailure { errors += it }
        }, "phase-c-stop")
        start.start()
        stop.start()
        start.join(5_000)
        stop.join(5_000)

        assertTrue("no thread threw: $errors", errors.isEmpty())
        assertFalse("both finished", start.isAlive || stop.isAlive)
        val grain = reg.inspect().single { it.key == "p1" }.grain
        assertTrue(
            "the race may leave a reservation or a stop, never an impossible grain: $grain",
            grain == EngineInstanceRegistry.Grain.STARTING ||
                grain == EngineInstanceRegistry.Grain.STOPPED
        )
        if (grain == EngineInstanceRegistry.Grain.STARTING) {
            assertTrue(
                "a final reservation must still hold the only slot",
                reg.requestStart("p2") != null
            )
        } else {
            assertNull(
                "a final stop must free the only slot",
                reg.requestStart("p2")
            )
        }
    }

    @Test
    fun `a stale RUNNING grain after terminal failure does not consume a slot`() {
        val reg = EngineInstanceRegistry<FakeRuntime>(
            maxLiveNonLegacy = 1,
            legacyKey = "",
            livenessOf = { rt -> EngineInstanceRegistry.Liveness(rt.childAlive, rt.state) },
            stopRuntime = {},
        )
        val p1 = reg.runtimeFor("p1") { FakeRuntime("p1") }
        reg.recordRunning("p1")
        assertEquals(EngineInstanceRegistry.Grain.RUNNING, reg.inspect().single().grain)

        // The supervisor gives up after a start: no live child, terminal state.
        p1.state = EngineState.Failed("engine died", "terminal", willRetry = false)

        val p2 = reg.runtimeFor("p2") { FakeRuntime("p2") }
        assertNull(
            "only STARTING/live children count; a stale RUNNING grain must not burn the slot",
            reg.requestStart("p2")
        )
        assertEquals(
            "the grain is bookkeeping, not liveness",
            EngineInstanceRegistry.Grain.RUNNING,
            reg.inspect().single { it.key == "p1" }.grain
        )
        assertEquals(2, reg.inspect().size)
    }

    @Test
    fun `a terminal failure does not block the key's own restart`() {
        val reg = EngineInstanceRegistry<FakeRuntime>(
            maxLiveNonLegacy = 2,
            legacyKey = "",
            livenessOf = { rt -> EngineInstanceRegistry.Liveness(rt.childAlive, rt.state) },
            stopRuntime = {},
        )
        val p1 = reg.runtimeFor("p1") { FakeRuntime("p1") }
        assertNull(reg.requestStart("p1"))
        reg.recordRunning("p1")
        p1.state = EngineState.Failed("died after running", null, willRetry = false)

        reg.runtimeFor("p2") { FakeRuntime("p2") }
        assertNull("the freed slot is available to another key", reg.requestStart("p2"))
        // A real restart calls requestEngineStart and then supervisor.start();
        // start() repaints Starting (EngineSupervisor.kt:233-235) before it
        // spawns, which is what makes the reservation count again.
        p1.state = EngineState.Starting(1)
        assertNull(
            "the failed key may restart itself: its own dead child is not counted against it",
            reg.requestStart("p1")
        )
        assertEquals(
            "a restart repaints the owner's reservation",
            EngineInstanceRegistry.Grain.STARTING,
            reg.inspect().single { it.key == "p1" }.grain
        )
        assertTrue(
            "two reservations fill a budget of two",
            reg.requestStart("p3") != null
        )
    }

    @Test
    fun `ten simultaneous starts for ten keys admit exactly the two slots`() {
        val reg = EngineInstanceRegistry<FakeRuntime>(
            maxLiveNonLegacy = 2,
            legacyKey = "",
            livenessOf = { rt -> EngineInstanceRegistry.Liveness(rt.childAlive, rt.state) },
            stopRuntime = { it.stopped = true },
        )
        val keys = (1..10).map { "p$it" }
        keys.forEach { key -> reg.runtimeFor(key) { FakeRuntime(key) } }

        val gate = CyclicBarrier(keys.size)
        val results = ConcurrentHashMap<String, GateResult>()
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = keys.map { key ->
            Thread({
                runCatching {
                    gate.await()
                    val refusal = reg.requestStart(key)
                    results[key] = GateResult(refusal == null, refusal)
                }.onFailure { errors += it }
            }, "phase-c-many-$key")
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertTrue("no racer threw: $errors", errors.isEmpty())
        assertFalse("every racer finished", threads.any { it.isAlive })
        assertEquals("every racer recorded an outcome", keys.size, results.size)
        val accepted = results.filterValues { it.accepted }.keys
        assertEquals("exactly the two slots may be reserved", 2, accepted.size)
        assertEquals(
            "the two accepted keys are the two STARTING grains",
            2,
            reg.inspect().count { it.grain == EngineInstanceRegistry.Grain.STARTING }
        )
        results.filterValues { !it.accepted }.values.forEach { result ->
            assertTrue(
                "a refusal names the budget: ${result.refusal}",
                result.refusal!!.contains("2/2")
            )
        }
    }

    @Test
    fun `fifty distinct projects stay registered with one stopped node each`() {
        val t = bed()
        val keys = (1..50).map { "p$it" }
        keys.forEach { key -> t.registry.runtimeFor(key) { FakeRuntime(key) } }

        keys.forEach { key -> assertTrue(t.registry.stop(key, "phase-c stress")) }

        val snapshots = t.registry.inspect()
        assertEquals("no eviction: one node per distinct project", 50, snapshots.size)
        assertTrue(
            "every node is stopped bookkeeping, not a live child",
            snapshots.all { it.grain == EngineInstanceRegistry.Grain.STOPPED }
        )
        assertTrue(
            "the stop reached every fake runtime",
            snapshots.all { it.runtime.stopped }
        )
        var fannedOut = 0
        t.registry.forEachRuntime { fannedOut++ }
        assertEquals("tuning still reaches all 50 stopped nodes", 50, fannedOut)
    }
}
