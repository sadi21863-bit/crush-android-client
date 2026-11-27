# A1 acceptance report (V2-16) — 2026-11-27

**Status: A1 ACCEPTED by the owner on 2026-11-27, with scoped limitations.**
This acceptance does **not** authorize A2 and does **not** close D1, D2, D4 or
D7; each still needs its own decision. `docs/v2/V2-16-PLAN.md` and the frozen
risk register were not edited.

Scope of A1 as implemented: a process-scoped `ExecutionController`, a
process-owned `ExecutionEventAdapter`, intent-before-POST ordering, and a
production invalidation listener. A2 (Room persistence) is deliberately absent.

## Gate matrix

The evidence classes are kept separate on purpose. A green JVM run is not a
device run, and a source-wired hook is not an exercised hook.

| # | Gate / claim | Class | Evidence | Verdict |
|---|---|---|---|---|
| G1 | A screen leaving does not stop the live stream | **device** | `a1-device-detach.log`: `A1 onCleared: detach only` 02:50:52.842 → `destroy MainActivity` :844 → **zero** `connection stopped` after → 38 adapter feed lines through `RUN_COMPLETE` at 02:51:12.849 | **proven at this scope**. Caveat: every frame in that run was `UNATTRIBUTED epoch=0` |
| G2 | A real confirmed event can reach the controller on device | **device** | `a1-device-confirmed.log`: real `declare` 06:00:03 → `RUN_COMPLETE attribution=CONFIRMED epoch=1` → `ExecutionController: confirmed …` 06:05:01 | **proven**. Caveat: short turn with a delayed permission grant; not a live-run switch |
| G3 | A production invalidation reaches the controller | **device** | `a1-device-invalidation.log`: binding switch with **no run in flight** → `registry.stop` epoch 1, `supervisor.stop` epoch 2; `ExecutionController: invalidated` logged | **proven for the idle switch**. Not a live-run switch |
| G4 | A binding switch with a run in flight preserves the execution | **JVM, real container** | `AppContainerExecutionInvalidationTest.bindingSwitchWithRunInFlightDoesNotCancelTheExecution` — sets the run-in-flight marker, calls `setActiveChatPool()`, asserts the execution remains `RUNNING` (`a1-integration-junit.xml`) | **proven at real-container JVM scope**; **not performed on device** |
| G5 | Controller state machine, intent/attempt ordering, terminal `UNKNOWN`, retry identity, detach-only observation, concurrent registry access | **JVM** | `ExecutionControllerTest` **14** (incl. 2 deterministic concurrency pins), `ExecutionEventAdapterTest` **11** (provisional buffer/commit/rollback, real router with a fake connection port, stale-epoch rejection) | **proven at JVM scope** |
| G6 | Shutdown and terminal-startup cleanup hooks are wired | **JVM** | `AppContainerShutdownTest` **1**, `EngineTerminalStartupCleanupTest` **1** | **proven at JVM scope** |
| G7 | Registry stop signal + token-protocol barrier | **JVM harness** | `E5InvalidationBarrierTest` **4** (`e5-r6-junit.xml`) | **proven at deterministic harness scope**; production invalidation routes still need end-to-end proof |
| G8 | Full regression gate | **JVM** | `app/build/test-results/testDebugUnitTest` from this run: **121 suites / 1128 tests / 0 failures / 0 errors / 0 skipped**; A1 subset is **35/35** | **green** |

A1 subset arithmetic: 14 controller + 11 adapter + 4 container integration +
1 shutdown + 1 terminal cleanup + 4 E5 barrier = **35**.

### Post-review concurrency hardening (2026-11-27)

Owner review found that `activeByKey` was a `ConcurrentHashMap` whose values
were plain `mutableSetOf()`: `start()` added outside the `records` monitor while
`onInvalidated()` snapshotted and `hasActive()` iterated. Fixed by (a) using
`ConcurrentHashMap.newKeySet()` per instance key, and (b) holding the `records`
monitor for every compound access — the record+set insert in `start()`, the
snapshot+rewrite in `onInvalidated()`, and the keyed scan in `hasActive()`.
Two deterministic pins were added to `ExecutionControllerTest`:
`concurrentStartsAndActiveChecksDoNotRace` (16 starters + 2 readers) and
`invalidationDuringConcurrentStartsMarksEveryRegisteredExecution` (12 starters
+ a concurrent invalidator, settled by a final invalidation). All threads are
released by one `CountDownLatch`; controller suite is now **14/14**.

## Source-wired (exercised only by the JVM probes above, or not at all)

- `AppContainer.kt:782-785` — the production listener bumps `executionEpochs`
  **before** calling `executionController.onInvalidated`, so a provisional
  attachment committing after an invalidation rejects on epoch change.
- `ChatViewModel.kt:3970-3977` — `onIntent` and `declare` run **before** the
  HTTP POST; `subscribe` runs before prompting. Outcomes are marked at
  `:4051` (acknowledged), `:4068` (definitive rejection) and `:4070`
  (ambiguous send → `UNKNOWN`).
- `ExecutionEventAdapter.kt:170-243, 298` — process-owned connection
  attach/teardown and the `CONFIRMED`-only attribution gate.
- Invalidation emitters exist in `EngineSupervisor.kt:164, 192, 270, 417, 430,
  474, 671` (stop / restart / watchdog / recovery / debug kill) and
  `EngineInstanceRegistry.kt:209`, `AppContainer.kt:1064, 1071` (shutdown).

## Explicitly not performed

- **Live-run project switch on device.** Repeated attempts were defeated by
  the ASK dialog and by host round-trip latency; AUTO_APPROVE was enabled for
  the attempts and restored to ASK afterwards. The invariant is covered by G4.
- **Two-ViewModel stale-event race on device.**
- **Device evidence** for watchdog, supervisor recovery, deliberate restart,
  shutdown, project removal and the debug `kill_engine` route (R37: no debug
  bridge for reliable injection).
- **Any durable/recovery-across-death claim** — that is A2 scope. There is no
  exactly-once claim; `UNKNOWN` is a first-class terminal and retry always
  mints a new `executionId` and `runId`.
- **Tool-call / tool-result classification at the adapter.** `feed()` maps only
  top-level `CrushEvent.PayloadType` values; `tool_call` and `tool_result` are
  *parts inside MESSAGE frames*, so they arrive as `MESSAGE`/`OTHER`, never as
  `ExecutionEvent.Type.TOOL_CALL`/`TOOL_RESULT`. Tool-class waits and progress
  classification are A4 scope; this report makes no claim of complete
  tool-level progress observability.

## Evidence caveats

- No APK hash was captured for the device runs, and some device artifacts
  predate the commits containing the final code. Do not describe them as
  proof of a specific commit.
- Generated Gradle logs are provenance, not structured evidence; the XML
  reports are.
- Passing tests do not prove device behavior, and a device trace of one route
  does not prove the sibling routes.

## Adjacent UI change (not A1 evidence)

Commit `f2ee836` added a contextual **Start engine** button on the chat list:
it appears whenever the bound instance has no confirmed Running engine and
hides only on Running. It is a UI-action change authorized separately and is
not part of the A1 acceptance case. Its own verification: 342 chat-surface JVM
tests green, plus a device run on `bbf8ca69` (button visible with sessions
hidden → tap starts the engine and hides the button → button reappears after a
later engine stop). The capacity-refusal reason text is JVM-tested only.

## Owner acceptance (2026-11-27)

**Decision:** accept A1 with the device gaps recorded as scoped limitations
rather than blocking defects.

Accepted as the A1 evidence set:

- the real-container JVM proof of the live-run binding-switch invariant (G4);
- device evidence for the three structural routes (G1 detach, G2 confirmed
  event, G3 idle invalidation), with their stated caveats;
- the post-review concurrency hardening (14/14 controller pins);
- the full gate at 121 suites / 1128 tests / 0 failures / 0 errors / 0 skipped.

Scoped limitations carried forward, not silently closed:

- live-run project switch, the two-ViewModel stale-event race, and the device
  fault routes (watchdog, recovery, deliberate restart, shutdown, project
  removal, debug `kill_engine`) remain unperformed on device;
- no APK hash was captured, and some device artifacts predate final commits;
- tool-call / tool-result classification is A4 scope;
- there is no durable recovery — that is A2 scope;
- no GitHub Actions branch-workflow run exists for this branch, so the gate
  evidence is the local Gradle run, not CI.

Explicitly not accepted by this decision: A2 implementation, and final
resolutions for D1, D2, D4 and D7.

## Residual risk after acceptance

The residual risk is concentrated in the untested device fault routes and the
live-run switch. A debug-only fault bridge remains an available future option
if those routes need device proof; it is not a condition of this acceptance.

**A2 still requires a separate explicit authorization. It has not been given.**

## Pointer

`A1-EVIDENCE-MANIFEST.md` holds the raw artifact/provenance matrix; this
report is the decision-facing summary and adds no new raw evidence.
