# V2-15 integration report

Status: **implementation + Phase C validation + post-push L2 correction +
Phase D permission-policy verification + Phase D serialization follow-up +
reattach fast-path follow-up, 2026-11-24.** The initial batch is
committed and pushed as `1990118` on
`v2-15-lifecycle-hardening` (not merged into `main`); the L2 terminal-startup
correction is committed on the same branch as a follow-up to `1990118`. No
merge, PR, issue or tag; no Go-engine or dependency change. Device PSS/stop
census was performed on `bbf8ca69` (1 project child + legacy); a 3-child
sample, instrumented execution and TalkBack remain **NOT PERFORMED**.

This report ties the V2-15 batches together:

| Batch | Finding | State |
|---|---|---|
| L1 | shutdown stopped only the legacy supervisor | implemented; container mapping now tested |
| L2 | a throwing child stop could skip proxy teardown; a restart reused a cancelled scope | implemented; cleanup/restart tested |
| L3 | `registrationKey` retained every connection graph forever | implemented with `(key, ownerEpoch)`; tombstone map deleted |
| L4 | runtime retention was asserted, not measured | JVM/registry + device PSS and post-stop census; 3-child sample NOT PERFORMED |
| L5 | grain can read `RUNNING` after a terminal failure | investigated; **no correction required** (bookkeeping only) |
| L6 | proxy scope never cancelled | fixed by L2 (`EngineProxy.stop()` cancels the per-start scope) |

## 1. Production changes

| File | Change |
|---|---|
| `di/AppContainer.kt` | L1 `ShutdownPlan` + `buildShutdownPlan()`; `shutdown()` now returns the completed stop count (same one-shot behavior; the only caller ignores the value) |
| `engine/CrushEngine.kt` | L2 `stopBlocking()` always runs child and proxy teardown through `runEngineStop`; proxy stop is failure-isolated from child stop. Post-push: test-only `processStarter`/`isEgressProxyRunningForTest` seams and the `start()` ownership KDoc; production default unchanged. |
| `engine/EngineProxy.kt` | L2 one cancellable `CoroutineScope` per start; `stop()` cancels it and clears relays; a restart gets a fresh scope |
| `data/crush/ConnectionRegistry.kt` | L3 `isSuperseded(key, ownerEpoch)` reads only the live `entries[key]`; `registrationKey` deleted; test-only retained-count seam |
| `ui/screens/chat/ChatViewModel.kt` | L3 `registeredConnectionKey` captured at successful attach; stale check and `onCleared` use it, never the mutable `instanceKey` |
| `engine/EngineSupervisor.kt` | L4 test-only `hasActiveSupervisionForTest()` seam; post-push L2 correction calls `stopBlocking()` in the terminal `Failed` branch before publishing the state |
| `data/crush/WorkspaceManager.kt` | Phase D: clear `lastAttachedAtMs` when claiming a new id and flush id/generation/lease on `CancellationException`; follow-ups add a companion per-path `Mutex` around `open()` and move `reattachIfStale()`'s fast path under that lock (calling `openLocked()` to avoid recursion); no request-shape or policy-mapping change |

No other production file changed. Phase C itself added only the `Int` return
from `shutdown()`; the post-push correction adds the terminal `stopBlocking()`
call. L1 and L3 behaviour is unchanged.

## 2. What each finding now guarantees

**L1 — shutdown coverage.** `shutdown()` snapshots the legacy supervisor plus
every registry runtime at first call, stops each in isolation, and is one-shot.
`ShutdownPlanTest` (4) pins plan-level isolation/idempotency;
`AppContainerShutdownTest` (1) pins the real registry→plan mapping: 3 stops
(legacy + two projects), then 0; each supervisor `Stopped`, no loop, no child.

**L2 — teardown isolation.** `EngineStopCleanupTest` (3) proves a throwing child
stop still stops the proxy, a throwing proxy stop does not undo the child stop,
and both are attempted; `CrushEngineLifecycleTest` (1) pins stop-before-start and
repeat stop; `EngineProxyConnectTest` (+2) pins stop/restart and a fresh scope
after cancellation. `EngineProxy.stop()` now cancels the per-start scope, so L6
is closed at the scope level. **Post-push correction:** the supervisor's
terminal `Failed` branch did not call `stopBlocking()`, so an exec failure after
the bridge started (`process == null`) leaked the listener/scope; the branch now
calls it before publishing the terminal state (`EngineSupervisor.kt:467`).
`EngineTerminalStartupCleanupTest` (1) drives the real
`CrushEngine.start()` → `supervise()` → terminal path and fails if that line is
removed.

**L3 — connection retention.** `ConnectionRegistryTest` (25 = 15 retained/adapted
+ 10 adversarial) pins: newer owner supersedes, own registration does not,
same-epoch rebuilds do not, own revoke with nothing newer is not supersession,
a newer-then-revoked owner creates no permanent history, unknown keys return
false, and delayed callbacks carrying only `(key, epoch)` remain deterministic.
`registrationKey` no longer exists; the only remaining textual reference is a
test comment explaining what was removed.

**L4 — retention evidence.** A stopped runtime retains bounded metadata (node +
engine + supervisor) with no child process, no proxy listener/tunnel, no active
supervision job and no connection history. 50 distinct projects are supported at
the registry level. On device, switching away from project `p5ab508c3` left
exactly one `libcrush.so` (the legacy supervisor), removed both project socket
files and left no project listener in `/proc/net/unix`; the app kept running.
Device PSS: app 99–128 MB depending on UI, legacy child 23.9 MB idle / 51.9 MB
with a chat, one project child 40.8–60.5 MB. A 3-child sample was NOT PERFORMED
(see §6).

**L5 — stale `RUNNING`.** Not observable by UI/diagnostics (`inspect()` has one
consumer: the shutdown plan), not a budget input, and not an obstacle to the
key's own restart or to `stop()`. The grain stays `RUNNING` after a terminal
failure, matching its documented "bookkeeping only" role. No production change;
a regression test pins that it cannot burn a slot and that a restart still
works.

## 3. Test inventory (V2-15 additions)

| Class | Tests | Note |
|---|---:|---|
| `ShutdownPlanTest` | 4 | L1 plan behavior |
| `EngineStopCleanupTest` | 3 | L2 teardown isolation |
| `CrushEngineLifecycleTest` | 1 | L2 stop/start lifecycle |
| `EngineProxyConnectTest` | +2 | L2 per-start scope/restart |
| `ConnectionRegistryTest` | 25 | L3; 15 prior cases retained/adapted, 10 adversarial |
| `EngineInstanceRegistryTest` | 26 | 19 prior + 7 Phase C (repeated start, stop-during-STARTING, stop/start race, stale RUNNING, own restart, ten-racer barrier, 50 projects) |
| `EngineRuntimeLifecycleTest` | 1 | Phase C, no active loop/child after stop |
| `EngineTerminalStartupCleanupTest` | 1 | Post-push L2: real exec failure → supervisor terminal → bridge released |
| `WorkspacePermissionPolicyScenariosTest` | 11 | Phase D: transport, cancellation, retry, restart/generation, serialization/concurrency, reattach fast path; credential-order assertions |
| `AppContainerShutdownTest` | 1 | Phase C/D, container mapping + one-shot count |

No test was removed or weakened in this batch.

## 4. Gate evidence

| Run | Result |
|---|---|
| Focused L1/L2 suite (pre-L3) | 46 tests across 6 classes, green |
| Focused L3 registry | 25/25 green, 7m15s (`v2-15-l3-focused.log`) |
| L3 full gate | **BUILD SUCCESSFUL in 30m01s**; **1040 tests / 111 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** (`v2-19-l3-gate.log`) |
| Focused Phase C, first pass | BUILD SUCCESSFUL in 17m18s (`v2-15-phasec-focused.log`) |
| Focused Phase C, registry final | 26/26 green in 2m27s (`v2-15-phasec-focused3.log`) |
| Post-push L2 correction, focused | **BUILD SUCCESSFUL in 8m06s**; **10 tests / 5 classes / 0 failures / 0 errors / 0 skipped** (`v2-22-fix-focused2.log`) |
| Full gate after Phase C | **BUILD SUCCESSFUL in 10m25s** (95 tasks, 1 executed / 94 up-to-date); **1049 tests / 113 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** (`v2-21-phasec-gate-final.log`) |
| Post-push correction full gate | **BUILD SUCCESSFUL in 33m01s**; **1050 tests / 114 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** (`v2-23-fix-gate.log`) |
| Phase D permission-policy focused, pre-serialization | **BUILD SUCCESSFUL in 44s**; **27 tests / 3 classes / 0 failures** (`v2-29-phaseD-focused3.log`) |
| Phase D full gate, pre-serialization | **BUILD SUCCESSFUL in 18m12s**; **1059 tests / 115 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** (`v2-30-phaseD-gate.log`) |
| Phase D serialization focused | **BUILD SUCCESSFUL in 2m57s**; **28 tests / 3 classes / 0 failures** (`v2-33-phaseD-serialization-focused2.log`) |
| Phase D serialization full gate | **BUILD SUCCESSFUL in 17m53s**; **1060 tests / 115 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** (`v2-35-phaseD-serialization-gate.log`) |
| Phase D reattach fast-path focused | **BUILD SUCCESSFUL in 50m43s** (Gradle tooling stall; tests green); **29 tests / 3 classes / 0 failures** (`v2-36-reattach-focused.log`) |
| Phase D reattach fast-path full gate | **BUILD SUCCESSFUL in 16m26s**; **1061 tests / 115 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** (`v2-38-reattach-gate.log`) |

The full gate runs `:app:testDebugUnitTest :app:assembleDebug
:app:assembleDebugAndroidTest :app:lintRelease`.

Run note: `v2-20-phasec-gate.log` executed the tests and both APK assemblies but
was interrupted during `lintAnalyzeRelease`, so it has no BUILD line. The final
run (`v2-21-phasec-gate-final.log`) reused those up-to-date test/assemble
outputs, re-ran lint to completion and reported the totals above. Test counts
were re-read from the 113 `TEST-*.xml` files; lint counts from
`app/build/reports/lint-results-release.xml` (1 warning, 0 errors).

Correction details, including the `containerScope` review and the start/stop
concurrency analysis, are in `docs/v2/V2-15-POST-PUSH-AUDIT-CORRECTION.md`.

## 5. Device evidence (2026-11-24)

Device: Redmi Note 7 Pro (`violet`), API 29, arm64-v8a, serial `bbf8ca69`;
debug build `com.crush.mobile` 1.0.0, bundled engine `v0.100.0`. Method: three
`dumpsys meminfo <pid>` samples per process/state; raw output and screenshots
are in `docs/parity/v2-15-device/` (`V215-DEVICE-EVIDENCE.md`).

| State | App PSS (kB) | Legacy child (kB) | Project child (kB) |
|---|---:|---:|---:|
| legacy + `pd871eee8` (project session list) | 99,012–99,108 | 23,944 | 40,811 |
| legacy + `p5ab508c3` (chat, 8 messages) | 125,658–125,764 (first 143,297 transient) | 23,594 | 60,460 |
| after stop, legacy only (chat) | 127,877–138,323 | 51,494–51,906 | — |
| after stop, legacy only (projects list) | 126,378–128,574 | 51,158 | — |

The stop was a normal binding switch (project → Crush Chat): `files/app.log`
shows `stopping project engine for instance 'p5ab508c3' (no run in flight)` and
`[p5ab508c3] stop() requested`. Immediately after, `ps` showed exactly one
`libcrush.so` pid, both project socket files were absent, and
`/proc/net/unix` had no listener for either project instance.

Honest limits: child PSS is workload-dependent (23.9 MB idle legacy vs 51.9 MB
with a bootstrapped chat; 40.8 MB project on a list vs 60.5 MB with a chat), and
per-process PSS double-counts shared pages, so deltas are directional. The
process/socket census is the retention proof. A 3-child sample was **NOT
PERFORMED** because ordinary switching stops the outgoing non-legacy engine;
two live project children need the run-in-flight/handoff path or a budget
override.

## 6. Remaining gaps (NOT PERFORMED)

| Gap | Status | Why |
|---|---|---|
| `registeredConnectionKey` unit test | not added | no JVM `ChatViewModel` harness; construction pulls Keystore/DataStore and the capture is a single field. Source-inspected: set at attach, used by the only stale check and `onCleared`; no mutable-binding use. |
| L2 real spawned-engine timeout after proxy startup | not added | JVM cannot execute the bundled arm64 engine; cleanup mechanics are pinned by the stop helper, the proxy tests and the injected-spawn terminal test. |
| L2 terminal cleanup with a real exec failure | covered by injection | `EngineTerminalStartupCleanupTest` injects the `IOException` at the production `processStarter` point after the real bridge starts; a real arm64 exec failure remains device-only. |
| Device PSS + post-stop census | **performed** | `bbf8ca69`, debug build, 1 legacy + 1 project child; see §5 and `docs/parity/v2-15-device/`. |
| 3-child PSS | NOT PERFORMED | ordinary switching stops the outgoing non-legacy engine; needs the run-in-flight/handoff path or a budget override (separate experiment). |
| Thread census after stop | NOT PERFORMED | child/socket census performed; thread count per process was not sampled. |
| Instrumented / TalkBack | NOT PERFORMED | no instrumented run or TalkBack session in this phase. |
| Effective permission-mode read-back | **does not exist** | the spec exposes `yolo`/`skip` booleans, not the v0.100.0 mode enum; a 2xx apply remains the only proof (Phase D §5). |
| Phase D device validation | NOT PERFORMED | the Phase D scenario matrix is JVM/MockWebServer; no device re-run of `/permissions/mode` or engine restart. |

## 7. Phase D — permission-policy verification (2026-11-24)

The A7/A7b fail-closed implementation was re-verified across all ten scenarios
in `docs/v2/V2-15-PERMISSION-POLICY-VERIFICATION.md`.

| Contract | Verdict |
|---|---|
| Rejected `permissions/mode` / `permissions/skip` → no `agent/init` | holds |
| Rejection/transport failure → no provider-key request | holds |
| Transport failure during apply → fail closed | holds |
| Cancellation → no unconfirmed attachment | **one defect found and fixed** |
| Engine restart / generation change → stale id not reused | holds |
| Failed reattach → reported false, retry recovers | holds |
| Effective-mode read-back | **no trustworthy endpoint; limitation retained** |
| Concurrent opens/reattaches | **serialized per workspace path; later intent wins** |
| Fresh-lease reattach during an in-flight open | **waits on the path lock; cannot accept the old lease** |

The defect: a cancelled re-open could leave the old fresh
`lastAttachedAtMs` while the new id was already claimed, so a later non-forced
`reattachIfStale()` could return true without re-applying the new policy.
`WorkspaceManager.open()` now clears the lease when it claims the id and flushes
id/generation/lease on `CancellationException`. No L1/L2/L3 file was touched.

**Follow-up — different-mode concurrency.** The race is reachable: managers are
created per bootstrap, `ChatViewModel`'s `bootstrapping` guard is per instance,
and a cleared ViewModel's blocking OkHttp policy call can outlive it while a
replacement reads a changed mode. `open()` now acquires a companion `Mutex`
keyed by workspace path and holds it through create/policy/init; the later
call's mode is the final apply. The out-of-order ASK/YOLO regression
(`out-of-order ASK and YOLO apply the later intent last`) fails if the lock is
removed (mutation run `v2-34-phaseD-serialization-mutation.log`) and passes with
it.

**Follow-up — reattach fast path.** `reattachIfStale()` evaluated its fresh-lease
short-circuit before taking the path lock, so it could return true while an open
was still inside `createWorkspace` and the old lease had not been cleared. The
fast path is now inside the same lock and the reopen calls `openLocked()` (not
`open()`), avoiding recursive mutex acquisition. Scenario 10c holds a YOLO
create before the lease is cleared and asserts the concurrent reattach is still
suspended; the mutation with the fast path outside the lock fails at
`reattach must not accept the old lease while the YOLO open is in progress`
(`v2-37-reattach-mutation.log`).

Tests: `WorkspacePermissionPolicyScenariosTest` (11 new), the existing
`WorkspacePermissionPolicyTest` (4, now also asserting no provider-key request),
and `WorkspaceLeaseTest` (14). The full gate result is in §4.

## 8. Restrictions honored

- No runtime eviction, `removeStopped(key)` or LRU added.
- No Go engine, dependency, workflow or unrelated UI change.
- The initial V2-15 batch was committed and pushed to
  `v2-15-lifecycle-hardening` as `1990118` on owner instruction; it is not
  merged into `main`, and no PR or issue was created. The post-push correction
  is committed on the same branch as a follow-up. Phase D is committed on the
  same branch; no PR or issue was created for it. The serialization follow-up
  is committed as `3a261e6`. The reattach fast-path follow-up is committed on
  the same branch.
- All V2-15 reports, test evidence and implementation changes are preserved.
- No historical record was rewritten; the Phase C and correction reports state
  what was and was not executed.
- No device claim is made from JVM tests.
