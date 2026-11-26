package com.crush.mobile.engine

import com.crush.mobile.util.AppLog

/**
 * §1 of the Gate-7 design memo (`docs/design-runtime-registry-2026-11-05.md`,
 * 2026-11-05): the container-owned map of per-instance engine runtimes,
 * keyed by instanceKey.
 *
 * WHY A REGISTRY, not the previous single `projectRuntime: EngineRuntime?`
 * slot: the slot silently EVICTED. After a projects->chat round trip,
 * `runtimeFor(oldKey)` for the OUTGOING key could construct a whole FRESH
 * engine pair just to stop it - a wasted spawn, a second supervisor loop, and
 * the one place the "two supervisors for one engine" hazard could re-enter.
 * The registry kills that structurally:
 *
 * 1. At most ONE runtime OBJECT per key, forever (per process): [runtimeFor]
 *    is idempotent and the entry is never removed.
 * 2. A stopped key may be RESTARTED, never REBUILT: [stop] is lookup-or-noop
 *    and stops bookkeeping only.
 * 3. The LEGACY key ("") is outside the registry by construction: it stays
 *    the permanent process-single pair the container already owns (never
 *    stopped, uncapped by the budget - decisions-open item 9).
 * 4. LIVENESS BUDGET (the plan's max-active-engines item, §1.4): at most
 *    [maxLiveNonLegacy] non-legacy keys holding a slot - a live engine child
 *    OR a [Grain.STARTING] reservation (released when the supervisor reaches
 *    a terminal failure); further starts are refused
 *    fail-closed with a named reason ([requestStart]). The count and the
 *    reservation happen in ONE critical section, so two near-simultaneous
 *    starts can never share the last slot (A8/A14, 2026-11-23). With the
 *    phase-1 born-or-never interlock in CrushEngine the third child is
 *    structurally unreachable - this gate is the belt to that bracer.
 *
 * WHAT THIS CLASS IS NOT: it is NOT a supervisor. Spawn, readiness, health
 * and state truth stay owned by each key's [EngineSupervisor]; the phase-1
 * interlock owns child BIRTH safety, so nothing here gates start()/stop()
 * interleavings. [Grain] is bookkeeping for the inspector and tests, with
 * ONE exception: [Grain.STARTING] is the start gate's slot reservation, so
 * the budget reads it - at gate time the child does not exist yet, and a
 * liveness-only count admitted two starts on one remaining slot. Every other
 * grain value is ignored: liveness TRUTH stays the cheap volatile [Liveness]
 * read, so a grain lag can only produce a fail-closed false refusal (a
 * reservation that has not cleared), never an over-admission of a live child.
 *
 * SCOPE/HYGIENE: the class is GENERIC over the runtime record. The container
 * instantiates it with its own `EngineRuntime`, which keeps this file free of
 * a di-package import (di -> engine is the allowed direction, never back).
 */
class EngineInstanceRegistry<R>(
    /**
     * The liveness budget. 2 is LOAD-BEARING - it is the plan's "at most two
     * non-legacy keys with live children" line; changing it changes how many
     * engine children one device holds. The two slots are two non-legacy
     * projects; chat runs on the legacy key, which is outside this budget.
     */
    private val maxLiveNonLegacy: Int = 2,
    /** Instance key of the permanent process-single pair; never stored here. */
    private val legacyKey: String,
    /** Runtime -> cheap volatility-only liveness reads (no I/O, no locks). */
    private val livenessOf: (R) -> Liveness,
    /** Synchronous, bounded stop of the runtime's supervisor (<= ~3s waitFor). */
    private val stopRuntime: (R) -> Unit,
) {

    /**
     * Coarse lifecycle bookkeeping per key, written only at registry-owned
     * moments: construction ([runtimeFor]), the start gate ([requestStart]),
     * the per-runtime successful-start hook ([recordRunning]) and stop
     * ([stop]). [Grain.STARTING] is the ONE grain the budget reads: it is the
     * start gate's slot reservation, so check-and-reserve are a single critical
     * section (A8/A14). The other grains stay out of the budget, which derives
     * liveness truth from [Liveness] - see the class KDoc.
     */
    enum class Grain { IDLE, STARTING, RUNNING, STOPPING, STOPPED }

    /**
     * Cheap liveness signal for one runtime. Both reads are volatile or
     * lock-free: [childAlive] is the engine object's `process?.isAlive
     * == true`, [state] is the supervisor's exposed StateFlow value.
     */
    class Liveness(val childAlive: Boolean, val state: EngineState)

    /** Immutable per-key snapshot for the inspector and tests. */
    class Snapshot<R>(
        val key: String,
        val grain: Grain,
        val runtime: R,
        val liveness: Liveness,
    )

    private class Node<R>(val runtime: R, @Volatile var grain: Grain)

    /**
     * Serializes the check-then-build ([runtimeFor]) AND the check-then-reserve
     * ([requestStart]); the map itself is concurrent.
     */
    private val lock = Any()
    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, Node<R>>()

    /**
     * Full runtime for an instance key; idempotent per key, FOREVER.
     *
     * Idempotency is the whole point: the old code held ONE slot, so a key's
     * return after another key's sojourn evicted-or-replaced pairs that other
     * keys were still riding. Here construction happens exactly once per key
     * per process, under the registry lock (the same serialization the old
     * `synchronized(runtimeLock)` gave the single slot), and the entry is
     * NEVER removed - only stopped by [stop].
     *
     * [build] runs inside the lock, exactly like the old construction, and
     * must therefore stay synchronous and fast (resolve tuning, build the
     * engine and its supervisor, register the P1-11 flush hook).
     */
    fun runtimeFor(key: String, build: () -> R): R {
        runtimes[key]?.let { return it.runtime }
        synchronized(lock) {
            runtimes[key]?.let { return it.runtime }
            val runtime = build()
            runtimes[key] = Node(runtime, Grain.IDLE)
            return runtime
        }
    }

    /**
     * The start gate. Returns the refusal reason and refuse-fails-closed, or
     * null to allow the caller's start to proceed.
     *
     * ATOMIC CHECK-AND-RESERVE (A8/A14, 2026-11-23): counting live children
     * and painting the [Grain.STARTING] reservation happen in ONE
     * `synchronized(lock)` section - the same monitor [runtimeFor] uses for
     * construction. Before that, the count and the reservation were two
     * separate steps and the reservation was not counted at all, so two
     * near-simultaneous starts for DIFFERENT keys both read "one slot free"
     * and both passed. Now the winner's [Grain.STARTING] is visible to the
     * loser's count, because a start that has not spawned yet still OWNS its
     * slot. A plain monitor on purpose: this class is synchronous end to end,
     * and the section does no [stopRuntime], no [runtimeFor] build and no
     * waiting - it is a map walk plus one volatile write, and on the refusal
     * path the one [AppLog.w] line the diagnostic requires.
     *
     * Fail-closed means BOTH: a refusal is an honest named reason (logged
     * here once, surfaced by the caller in the same shape an
     * [EngineState.Failed] gets), and the slot predicate is "any OTHER
     * non-legacy key holds a live child OR a [Grain.STARTING] reservation"
     * (see [othersHoldingSlots]). The key's OWN child never counts against
     * its own start (a re-start reclaims its own slot).
     *
     * The caller registers the key first ([runtimeFor]) - the app's path is
     * bindEngine -> runtimeFor -> startEngine - so the node exists and the
     * reservation sticks. A key with no node is still BUDGET-CHECKED and can
     * be refused; it just has nowhere to record the reservation (nothing to
     * start either, since the supervisor comes from the same node).
     *
     * No start()/stop() interleaving is gated here on purpose: the phase-1
     * born-or-never interlock owns child birth safety and each supervisor
     * serializes its own loop; a STOPPING grain does not veto the next
     * start, because stop is synchronous-fast and the supervisor-local
     * Mutex already defers that key's next start behind the stop.
     */
    fun requestStart(key: String): String? {
        if (key.isBlank() || key == legacyKey) return null
        synchronized(lock) {
            val liveOthers = othersHoldingSlots(key)
            if (!budgetAllows(liveOthers)) {
                // The refusal itself is the diagnostic (P1-15): a named reason,
                // once per refusal, key included - and the caller surfaces it to
                // the UI exactly as an EngineState.Failed would read.
                val refusal = refusalReason(key, liveOthers)
                AppLog.w("EngineRegistry", refusal)
                return refusal
            }
            // The reservation: from here the slot is OURS even before a child
            // exists, so a concurrent gate cannot hand it to another key.
            runtimes[key]?.grain = Grain.STARTING
            return null
        }
    }

    /** Records a successful start's grain; rides the P1-11 onEngineStart hook. */
    fun recordRunning(key: String) {
        runtimes[key]?.grain = Grain.RUNNING
    }

    /**
     * Stops a key's runtime - lookup-or-NOOP. A key never registered is worth
     * nothing here: `forgetProjectEngine` used to build a throwaway runtime
     * just to stop a background key; this lookup refuses that waste. The
     * entry STAYS (restart allowed, never rebuild).
     *
     * STOPPING is recorded before the stop runs ("while a stop's destroy +
     * waitFor is pending", §1.5) and STOPPED after, whatever the outcome of
     * [stopRuntime] (whose exceptions propagate exactly as today's caller
     * allowed; grain is bookkeeping, not truth). The supervisor's own stop is
     * idempotent, so an already-stopped key re-stopping is harmless.
     */
    fun stop(key: String, reason: String): Boolean {
        if (key.isBlank() || key == legacyKey) return false
        val node = runtimes[key]
        if (node == null) {
            AppLog.i(
                "EngineRegistry",
                "stop requested but no instance runtime is registered for '$key'; skip ($reason)"
            )
            return false
        }
        // E5-R6 test seam: no-op in production (listener is null). Makes the
        // binding-switch / project-removal invalidation observable to the
        // deterministic barrier test before any engine teardown begins.
        EngineInvalidationProbe.fire("registry.stop", key)
        node.grain = Grain.STOPPING
        try {
            stopRuntime(node.runtime)
        } finally {
            node.grain = Grain.STOPPED
        }
        return true
    }

    /**
     * Fan-out over every registered runtime - the per-instance half of a
     * tuning/settings apply. A STOPPED-but-registered runtime must not lose
     * settings: on its next start the supervisor's tuning read and the
     * engine's idle-linger bake at spawn time (§4 migration risk 9).
     */
    fun forEachRuntime(action: (R) -> Unit) {
        for (node in runtimes.values) action(node.runtime)
    }

    /** Inspector snapshot (the design memo's `activeEngineSnap()`), tests included. */
    fun inspect(): List<Snapshot<R>> =
        runtimes.map { (key, node) ->
            Snapshot(key, node.grain, node.runtime, livenessOf(node.runtime))
        }

    /**
     * §A8/A14: how many OTHER non-legacy keys hold a budget slot right now -
     * a live child ([keyHasLiveChild]) OR a [Grain.STARTING] reservation from
     * a gate that has not produced a child yet. The reservation half is what
     * makes the gate atomic: without it the childless window between
     * [requestStart] and the spawn was invisible to a concurrent count.
     *
     * A reservation is RELEASED by a TERMINAL failure (audit A8 follow-up): a
     * supervisor that gives up ends in `EngineState.Failed(willRetry = false)`
     * and returns, so neither [recordRunning] nor [stop] ever rewrites the
     * grain - counting that [Grain.STARTING] unconditionally would burn a slot
     * for the rest of the process. A RETRYING failure (`willRetry = true`)
     * KEEPS the reservation: the supervisor is still trying and will hold a
     * child on success. Liveness is read once per key here, and a live child
     * always wins over the state class (a Failed-with-child still holds one).
     *
     * Caller MUST hold [lock] (both halves are stable then: [runtimeFor]
     * builds under the same monitor, and the grain's only gate writer is
     * [requestStart] itself).
     */
    private fun othersHoldingSlots(exceptKey: String): Int {
        var slots = 0
        for ((otherKey, node) in runtimes) {
            if (otherKey == exceptKey || otherKey == legacyKey) continue
            val liveness = livenessOf(node.runtime)
            if (keyHasLiveChild(liveness)) {
                slots++
                continue
            }
            if (node.grain != Grain.STARTING) continue
            val terminalFailure = (liveness.state as? EngineState.Failed)?.willRetry == false
            if (!terminalFailure) slots++
        }
        return slots
    }

    /**
     * PURE: the budget predicate. [liveOthers] excludes the starting key and,
     * at the [requestStart] call site, counts BOTH keys with a live child and
     * keys holding a [Grain.STARTING] reservation ([othersHoldingSlots]) - a
     * reserved start is a slot about to hold a child, so it must never read
     * as free.
     */
    internal fun budgetAllows(liveOthers: Int): Boolean = liveOthers < maxLiveNonLegacy

    /**
     * PURE: the named refusal for a budget-tripped start. The key and both
     * counts must be present: the user-visible surface is a real
     * "engine failed: ..." string, so vagueness here is what the user reads.
     *
     * [liveOthers] may include a pending [Grain.STARTING] reservation as well
     * as live children (see [othersHoldingSlots]); the message keeps the
     * user-facing "hold live children" phrasing because a reservation is a
     * slot about to hold one.
     */
    internal fun refusalReason(key: String, liveOthers: Int, budget: Int = maxLiveNonLegacy): String =
        "engine instance budget: $liveOthers/$budget non-legacy instances hold live children; " +
            "start of '$key' refused until one stops"

    /**
     * PURE: 'has live child' - the only LIVENESS budget-input signal per key.
     * OR of the child's real liveness and the supervisor's exposed
     * Starting/Running (the memo's tri-state collapsed to the two cheap
     * reads; the deferred socket probe of the inspector stays with the S5
     * instrumentation, since a supervisor state read cannot false-negative a
     * budget refusal the way it can a UI badge).
     *
     * This is HALF the gate's input: [othersHoldingSlots] ORs it with the
     * [Grain.STARTING] reservation, because a start reserves its slot before
     * any child exists (A8/A14). Keep it liveness-only - tests and the
     * inspector read it as liveness truth.
     */
    internal fun keyHasLiveChild(l: Liveness): Boolean =
        l.childAlive || l.state is EngineState.Starting || l.state is EngineState.Running
}
