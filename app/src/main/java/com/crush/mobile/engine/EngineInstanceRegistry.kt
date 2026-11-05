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
 *    [maxLiveNonLegacy] non-legacy keys with a live engine child; further
 *    starts are refused fail-closed with a named reason ([requestStart]).
 *    With the phase-1 born-or-never interlock in CrushEngine the third child
 *    is structurally unreachable - this gate is the belt to that bracer.
 *
 * WHAT THIS CLASS IS NOT: it is NOT a supervisor. Spawn, readiness, health
 * and state truth stay owned by each key's [EngineSupervisor]; the phase-1
 * interlock owns child BIRTH safety, so nothing here gates start()/stop()
 * interleavings. [Grain] is bookkeeping for the inspector and tests; the
 * BUDGET never reads it - it derives from the cheap volatile [Liveness]
 * signal, so a stale grain can never lie about liveness (any registry-level
 * bookkeeping writer can legitimately skip a beat, unlike the state flow).
 *
 * SCOPE/HYGIENE: the class is GENERIC over the runtime record. The container
 * instantiates it with its own `EngineRuntime`, which keeps this file free of
 * a di-package import (di -> engine is the allowed direction, never back).
 */
class EngineInstanceRegistry<R>(
    /**
     * The liveness budget. 2 is LOAD-BEARING - it is the plan's
     * "at most two non-legacy keys with live children (chat + one project)"
     * line; changing it changes how many engine children one device holds.
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
     * ([stop]). The bookkeeping deliberately stays INDEPENDENT of the budget,
     * which derives truth from [Liveness] - see the class KDoc.
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

    /** Serializes the check-then-build; the map itself is concurrent. */
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
     * Fail-closed means BOTH: a refusal is an honest named reason (logged
     * here, surfaced by the caller in the same shape an
     * [EngineState.Failed] gets), and the predicate is "any OTHER
     * non-legacy key holds a live child" from the [Liveness] read - never
     * from bookkeeping. The key's OWN child never counts against its own
     * start (a re-start reclaims its own slot).
     *
     * No start()/stop() interleaving is gated here on purpose: the phase-1
     * born-or-never interlock owns child birth safety and each supervisor
     * serializes its own loop; a STOPPING grain does not veto the next
     * start, because stop is synchronous-fast and the supervisor-local
     * Mutex already defers that key's next start behind the stop.
     */
    fun requestStart(key: String): String? {
        if (key.isBlank() || key == legacyKey) return null
        var liveOthers = 0
        for ((otherKey, node) in runtimes) {
            if (otherKey == key) continue
            if (keyHasLiveChild(livenessOf(node.runtime))) liveOthers++
        }
        if (!budgetAllows(liveOthers)) {
            // The refusal itself is the diagnostic (P1-15): a named reason,
            // once per refusal, key included - and the caller surfaces it to
            // the UI exactly as an EngineState.Failed would read.
            AppLog.w("EngineRegistry", refusalReason(key, liveOthers))
            return refusalReason(key, liveOthers)
        }
        runtimes[key]?.grain = Grain.STARTING
        return null
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

    /** PURE: the budget predicate. [liveOthers] excludes the starting key. */
    internal fun budgetAllows(liveOthers: Int): Boolean = liveOthers < maxLiveNonLegacy

    /**
     * PURE: the named refusal for a budget-tripped start. The key and both
     * counts must be present: the user-visible surface is a real
     * "engine failed: ..." string, so vagueness here is what the user reads.
     */
    internal fun refusalReason(key: String, liveOthers: Int, budget: Int = maxLiveNonLegacy): String =
        "engine instance budget: $liveOthers/$budget non-legacy instances hold live children; " +
            "start of '$key' refused until one stops"

    /**
     * PURE: 'has live child' - the only budget-input signal per key. OR of
     * the child's real liveness and the supervisor's exposed Starting/Running
     * (the memo's tri-state collapsed to the two cheap reads; the deferred
     * socket probe of the inspector stays with the S5 instrumentation, since
     * a supervisor state read cannot false-negative a budget refusal the way
     * it can a UI badge).
     */
    internal fun keyHasLiveChild(l: Liveness): Boolean =
        l.childAlive || l.state is EngineState.Starting || l.state is EngineState.Running
}
