# V2-15 post-push audit correction — L2 terminal-startup cleanup

Status: **correction implemented and tested, 2026-11-24.** Scope is the
confirmed L2 terminal-startup cleanup gap only. No L1/L3 change, no runtime
eviction, no Go engine or dependency change, no merge/PR/issue. The initial
V2-15 batch is committed and pushed as `1990118` on
`v2-15-lifecycle-hardening`; this correction is committed on the same branch
as a follow-up to `1990118` (see §7).

## 1. Confirmed gap and exact trace

The egress bridge is started before the child is spawned, by design, so the
child can inherit `HTTPS_PROXY`. The failure path that follows an exec error
returned a terminal state without releasing it.

| Step | Code | Behaviour before the fix |
|---|---|---|
| 1 | `CrushEngine.start()` — `egressBridge.start()` (`CrushEngine.kt:364`) | Listener + per-start scope are live. |
| 2 | `CrushEngine.start()` — spawn (`CrushEngine.kt:414`, `processStarter`) | `IOException` is caught at `:436`. |
| 3 | `CrushEngine.start()` — catch | Returns `Failed(reason = "Could not launch engine", willRetry = false)` with `process == null`. The bridge stays up. |
| 4 | `EngineSupervisor.supervise()` — `Failed` branch (`:444`) | `startRetryGivesUp(attempt, false)` is immediately true; logs, publishes the terminal state and returns. **No `engine.stopBlocking()`.** |
| 5 | Watchdog `GiveUp` (`:266`) and wedge give-up (`:412`) | Already call `engine.stopBlocking()` before their terminal state. |

The same step-4 branch is also the terminal exit for **retryable** failures
whose `MAX_START_ATTEMPTS` budget runs out. Retryable failures intentionally
leave the bridge up so the next attempt can reuse it (`EngineProxy.start()` is
idempotent); when the budget ends, the bridge must be released. Before the fix
it was not, so a terminal startup failure could leave a loopback listener and
its `SupervisorJob` alive until the instance was stopped or the app shut down.

Impact: a bounded but real resource leak (one listener/scope per failed
instance), not a child-process leak. It was missed because
`EngineStopCleanupTest` proves the teardown helper, and the device stop path
calls `stopBlocking()` directly; neither exercises the supervisor's terminal
branch after a failed start.

## 2. Correction

| File | Change |
|---|---|
| `engine/EngineSupervisor.kt` | In the `Failed` terminal branch, `engine.stopBlocking()` now runs **before** the terminal state is published. Null-safe, so it releases the bridge even when no child was assigned; it also cleans the bridge left by retryable attempts whose budget just expired. |
| `engine/CrushEngine.kt` | Added two test-only seams: `processStarter` (default `ProcessBuilder.start()`) and `isEgressProxyRunningForTest`. Production behaviour is unchanged. The `start()` KDoc now states the ownership rule: the bridge survives retryable failures; a terminal owner must call `stopBlocking()`. |

Not changed: retry/backoff decisions, `MAX_START_ATTEMPTS`, the watchdog and
wedge paths, `stop()` ordering, L1's shutdown plan and L3's connection
predicate.

## 3. Regression test

`EngineTerminalStartupCleanupTest` (1 test) drives the real chain:

1. Robolectric app + a fake `libcrush.so` that passes the binary gate.
2. A real `CrushEngine` starts its real `EngineProxy`; `processStarter` records
   that the bridge is listening and throws `IOException` at the exact spawn
   point.
3. A real `EngineSupervisor.start()` runs the real `supervise()` loop; the
   failure is terminal on the first attempt.
4. Asserts: the bridge was up as a precondition, the terminal reason is the
   exec failure, `engine.isAlive()` is false, the bridge is **not** running,
   and the supervision loop has exited.

The test does not call `stopBlocking()` itself, so removing the supervisor
line makes it fail. That distinguishes it from `EngineStopCleanupTest`, which
only proves the teardown helper.

## 4. `AppContainer.containerScope` review

`containerScope` (`AppContainer.kt:85-87`) is a process-scoped
`SupervisorJob + Dispatchers.Main.immediate`. **Cancelling it in `shutdown()`
is intentionally deferred, not a missing correction.**

- `shutdown()` stops the legacy supervisor and every registered project
  supervisor. Each `EngineSupervisor.stop()` cancels its own supervision job,
  then `stopBlocking()` destroys the child and closes the proxy/scope. No
  engine resource is owned by `containerScope` after the plan runs.
- The remaining `containerScope` jobs are settings/tuning collection, binding
  restore, last-binding persistence and similar process-scoped launches
  (`AppContainer.kt:230, 431, 740, 767`). None holds an engine listener, child
  or socket.
- `shutdown()` is terminal: on the only production callback
  (`CrushMobileApp.onTerminate`) the process is about to die, and Android does
  not guarantee that callback at all. Cancelling the container scope would
  change the container's lifetime contract (and any test/emulator caller that
  keeps using it) without releasing an engine resource.
- If a future cleanup path needs to reuse the container after `shutdown()`,
  that is a separate lifecycle decision; it is not required to close this L2
  gap.

No code change was made for this item.

## 5. `EngineSupervisor` start/stop concurrency — existing vs V2-15

| Mechanism | Existing before V2-15 | V2-15 effect |
|---|---|---|
| `start()` early-return while the job is active | Yes (`EngineSupervisor.kt:153-160`) | unchanged |
| `stop()` sets `wanted=false`, cancels and nulls the job under `sync`, then calls `stopBlocking()` outside the lock | Yes (`:162-173`) | unchanged |
| `maySpawn` interlock + `CrushEngine`'s birth/stop monitor | Yes (pre-dates V2-15) | unchanged |
| `stopBlocking()` always releases the proxy even when `process == null` | **No** — child and proxy were separate best-effort calls | L2 fix; closes the leak |
| One cancellable proxy scope per start | **No** — a cancelled scope could be reused | L2 fix; restart tests pin it |
| Supervisor production code | — | unchanged except the test seam, until this correction |

Race shapes:

- `stop()` releases the monitor before `stopBlocking()`, so a new `start()`
  can launch a new loop while the old teardown runs. `maySpawn` and the shared
  `synchronized(this)` birth section mean either the veto prevents the birth
  or the old teardown reaches the assigned child; the new loop sees the early
  exit and uses the existing bounded retry ladder. This is pre-existing
  behaviour, not introduced by V2-15.
- The new terminal `stopBlocking()` runs before `supervise()` returns, while
  the supervisor job is still active. A concurrent `start()` therefore
  early-returns instead of launching a fresh loop mid-cleanup; once the loop
  returns, a later `start()` is a normal fresh attempt.
- A concurrent explicit `stop()` and the terminal branch may both call
  `stopBlocking()`; it is idempotent and synchronized. The final `_state`
  remains last-writer-wins, the same shape the watchdog and wedge terminal
  paths already had.

Verdict: no new start/stop race was introduced by V2-15; the terminal cleanup
is the missing resource release, not a concurrency change.

## 6. Test evidence

| Run | Result |
|---|---|
| Focused L2/supervisor (`v2-22-fix-focused2.log`) | **BUILD SUCCESSFUL in 8m06s**; **10 tests / 5 classes / 0 failures / 0 errors / 0 skipped**: `EngineTerminalStartupCleanupTest` 1, `EngineStopCleanupTest` 3, `CrushEngineLifecycleTest` 1, `EngineProxyConnectTest` 4 (includes the existing restart/scope cases), `EngineRuntimeLifecycleTest` 1. |
| Full JVM + compile + lint gate (`v2-23-fix-gate.log`) | **BUILD SUCCESSFUL in 33m01s**; **1050 tests / 114 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning**; `assembleDebug` and `assembleDebugAndroidTest` completed. |

The first focused attempt (`v2-22-fix-focused.log`) hung in `kspDebugKotlin`
with no log/CPU progress after 11 minutes; it was killed and rerun in a fresh
daemon. That was a local Gradle-daemon failure, not a test failure — the
successful run above is the evidence.

## 7. Committed-status wording

The initial V2-15 batch (L1/L2/L3, Phase C tests, reports, device evidence) is
commit `1990118` on `v2-15-lifecycle-hardening`, pushed to `origin`, **not
merged into `main`**. This post-push correction is a follow-up commit on the
same branch; `main` remains at `adc26c0`.
