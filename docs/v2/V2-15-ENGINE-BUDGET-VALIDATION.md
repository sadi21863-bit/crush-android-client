# V2-15 Phase C — engine budget and lifecycle validation

Status: **investigation + deterministic JVM tests + device stop/PSS capture,
2026-11-24.** No runtime eviction, no Go engine/dependency change. The batch is
committed as `1990118` on `v2-15-lifecycle-hardening` (pushed, not merged); the
post-push L2 terminal-cleanup correction is committed on the same branch. L1/L2/L3 stay as
accepted. Instrumented execution was **NOT PERFORMED**; the
device PSS and post-stop census are recorded in
`V2-15-RUNTIME-RETENTION-MEASUREMENTS.md` §6 (1 project child + legacy; a
3-child sample was not reachable through ordinary navigation).

## 1. What was validated

| Area | Question | Verdict |
|---|---|---|
| A | Is the non-legacy start reservation atomic under concurrent starts? | Yes — one monitor covers count + reserve; two-racer test already existed, a ten-racer barrier test is added. |
| A | Do STARTING (childless) engines count against the budget? | Yes — existing test; the ten-racer test proves the reservation is what blocks the ninth and tenth racers. |
| A | Do terminal startup failures release the slot, and retrying failures keep it? | Yes — existing terminal/retrying tests, unchanged. |
| A | Do repeated starts for one key double-count? | No — new test proves one reservation per key and a second key still gets the other slot. |
| A | Does removing a project during startup release the slot? | Yes — new `stop during STARTING` test. |
| A | Are stop/start races coherent? | Yes at the floor the design promises: the race ends in a reservation or a stop, never an impossible grain, and the budget matches the final grain (new barrier test). |
| A | Is the legacy engine budget-gated? | No, by design — existing test. |
| B (L5) | Can a terminal run failure leave `RUNNING` while liveness is `Failed`? | Yes, and it is **bookkeeping only**: not UI/diagnostic-observable, not a budget input, not a restart/cancel obstacle. No correction made. |
| C | Does a stopped runtime retain a child or supervision loop? | No child, no active loop (new `EngineRuntimeLifecycleTest`); the runtime object itself stays registered, by contract. |
| D | Does the container's `shutdown()` really include the registry's supervisors? | Yes — new `AppContainerShutdownTest` asserts the stop count and each supervisor's terminal state. |

## 2. The shipped budget contract (source of truth)

- Capacity: `maxLiveNonLegacy = 2`; the legacy key `""` and the chat-pool key
  `"chat"` nuance: chat is non-legacy and therefore *is* budgeted. The plan's
  "two project slots" line is enforced in `EngineInstanceRegistry`.
- A slot is held by **either** a live child (`childAlive` or supervisor state
  `Starting`/`Running`) **or** a `Grain.STARTING` reservation whose state is not
  a terminal `Failed(willRetry = false)`.
- `requestStart` performs the count and the `STARTING` write in one
  `synchronized(lock)` section (`EngineInstanceRegistry.kt:160-177`), the same
  monitor `runtimeFor` uses for construction (`:114-122`).
- Refusal is fail-closed and named; the caller surfaces it exactly like an
  `EngineState.Failed` (`AppContainer.requestEngineStart`, `:935-942`).
- The registry NEVER removes a key. A stopped key is restarted, never rebuilt.

### Retry windows are fail-closed

L5 raised the related question of whether a retrying engine can stop counting
and let a third child in. The supervisor sets `Starting` at the top of every
attempt (`EngineSupervisor.kt:233-235`), and after a Running child becomes
unhealthy it calls `stopBlocking()` but leaves the state `Running` during
`RESTART_DELAY_MS` (`:424-425`). Both shapes therefore still count: the first as
`Starting`, the second as stale-but-`Running`. A startup failure with
`willRetry = true` also keeps its `STARTING` grain while it backs off
(`:460-461`). There is no measured inter-attempt window where a retrying key is
invisible to the budget; the only stale-`Running` case is the **terminal**
failure, which must not hold a slot.

### Restart ordering after a terminal failure

`requestStart` paints the key's `STARTING` reservation, but a key whose
supervisor still reports terminal `Failed(willRetry = false)` is deliberately
not counted (the terminal-release rule). The reservation becomes budget-visible
when `supervisor.start()` runs and the loop repaints `EngineState.Starting`
(`EngineSupervisor.kt:233-235`). Production calls `requestEngineStart(...)`
followed immediately by `supervisor.start()` on the main thread
(`ChatViewModel.startEngine`), and `containerScope` is `Main.immediate`, so the
repaint happens before another gate can interleave. The restart test models this
repaint explicitly; without it, the registry correctly treats the
terminal-failure reservation as released, which is why the first test draft
failed. No production change resulted.

## 3. Deterministic evidence added

All tests are JVM/Robolectric; no test depends on wall-clock stress. Barriers
synchronize threads and assertions are on final states.

| Test class | New cases | What they pin |
|---|---:|---|
| `EngineInstanceRegistryTest` | 7 | repeated starts keep one reservation; stop-during-STARTING releases; stop/start race coherence; stale RUNNING after terminal failure burns no slot; terminal failure does not block the key's own restart; ten simultaneous starts admit exactly two; 50 distinct projects stay registered/stopped with fan-out |
| `EngineRuntimeLifecycleTest` | 1 | a constructed-then-stopped supervisor has no active loop and no live child |
| `AppContainerShutdownTest` | 1 | `shutdown()` snapshots legacy + every registered project once (count 3, then 0) and leaves each supervisor `Stopped` with no loop/child |

The pre-existing budget cases (two-racer atomicity, STARTING counts, terminal
release, retry keeps, legacy exemption, budget=1 boundary, grain transitions,
tuning fan-out) are unchanged and still in the same class.

`EngineInstanceRegistryTest` grows 19 → 26. No existing case was removed or
weakened.

## 4. L5 — stale `RUNNING` grain after a terminal failure

### Reproduction

1. `requestStart(k)` paints `STARTING`; a successful start calls
   `recordRunning(k)` (`EngineInstanceRegistry.kt:180-182`), grain `RUNNING`.
2. The child dies and the supervisor gives up with
   `EngineState.Failed(willRetry = false)` and returns
   (`EngineSupervisor.kt:444-458`); the runtime terminal paths at `:305-311`,
   `:413-419` do the same.
3. No registry hook rewrites the grain. `inspect()` shows
   `RUNNING` while `liveness.state` is `Failed`.

### State table

| Supervisor state (authoritative) | Registry grain | Budget input | UI |
|---|---|---|---|
| `Starting(attempt)` | `STARTING` (gate reservation) | yes | starting |
| `Running(...)` | `RUNNING` | yes (live child) | running |
| `Failed(willRetry = true)` during a startup backoff | `STARTING` (kept) | yes | retrying |
| `Failed(willRetry = false)` after a run | `RUNNING` (stale) | **no** | terminal error |
| `Stopped` after `stop()` | `STOPPED` | no | stopped |

### Answers

| Question | Answer |
|---|---|
| Observable by UI or diagnostics? | **No.** `inspect()` has exactly one consumer, `AppContainer.buildShutdownPlan()` (`AppContainer.kt:1005`). It is not read by the Diagnostics screen, a report builder, or any UI state. |
| Can it affect budget accounting? | **No.** `othersHoldingSlots` only counts a live child/`Starting`/`Running` liveness and a non-terminal `STARTING` reservation (`EngineInstanceRegistry.kt:251-265`). `RUNNING` grain is not an input. The new test proves a second key starts after a terminal failure with budget 1. |
| Can it affect restart, cancellation, or future dispatch? | **No.** `requestStart` repaints the owner's own reservation regardless of the stale grain (new restart test); `stop` repaints `STOPPING → STOPPED`; `revoke`/dispatch do not read grains. |
| Which component owns authoritative run state? | **`EngineSupervisor.state`** (the `StateFlow` observed by the container/UI). `Grain` is registry bookkeeping; `STARTING` is the only grain with a semantic role (the slot reservation). |
| Should it transition to `FAILED`, `STOPPED`, or another state? | **Not necessary for the current contract.** No consumer would read a new `FAILED` grain, and adding a supervisor→registry terminal hook creates a second writer of grain during a teardown the registry does not own. The class KDoc already states grains are bookkeeping except `STARTING`. |
| Correction implemented? | **No.** The state contract does not require it. A regression test now pins the harmless behaviour so a future change cannot accidentally make `RUNNING` a budget input. |

### Conditions that would revisit L5

If an inspector/diagnostics surface starts rendering `Grain` as lifecycle
truth, derive the displayed grain from `Liveness` at read time, or add an
explicit `recordTerminalFailure(key)` called only from the supervisor's terminal
return paths. That is a display decision, not a budget fix, and is not part of
V2-15.

## 5. Validation evidence

| Run | Result |
|---|---|
| Focused Phase C, first pass (registry 24 + runtime lifecycle + connection registry) | **BUILD SUCCESSFUL in 17m18s** (`v2-15-phasec-focused.log`) |
| Focused Phase C, second pass (all four classes) | one new registry test failed on its own model (it reserved a restart while the supervisor still reported terminal `Failed`); the container, lifecycle and registry suites otherwise passed. The test was corrected to model the supervisor's `Starting` repaint; no production defect. |
| Focused Phase C, registry final | **26/26 green in 2m27s** (`v2-15-phasec-focused3.log`) |
| Full gate (JVM, compile, lint) | **BUILD SUCCESSFUL in 10m25s**; **1049 tests / 113 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** (`v2-21-phasec-gate-final.log`). `v2-20-phasec-gate.log` executed tests+assemble but was interrupted mid-lint; the final run reused those up-to-date results and completed lint. |
| Post-push correction full gate | **BUILD SUCCESSFUL in 33m01s**; **1050 tests / 114 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** (`v2-23-fix-gate.log`). |

## 6. Remaining limits

- The budget is proven at the **registry contract** level. A real spawned
  child, real socket, and device memory pressure are not exercised in JVM.
- On device (2026-11-24), switching away from a project stopped its real child
  and removed its socket/file listener; that is the binding-switch stop path,
  not `shutdown()` on process termination, and a third concurrent instance was
  not exercised.
- `AppContainerShutdownTest` proves the mapping and stop count, but starts no
  engine; it cannot prove a real child dies on process termination.
- Ten-racer and stop/start tests prove outcome invariants, not every scheduler
  interleaving; the set of winners is intentionally not asserted.
- L5 is closed as **no change required**, not as "fixed"; the observable state
  remains the supervisor's `StateFlow`.
