# A1 evidence manifest (V2-16)

Scope: A1 execution-ownership slice. **No production behavior was approved beyond
A1**; A2, D1, D2, D4 and D7 remain open.

## Device provenance

- Device: `bbf8ca69`, API 29, MIUI, arm64-v8a.
- APK: debug builds assembled from the working tree and installed with
  `adb install -r -d`. **No APK hash was captured**; the device runs happened
  before the corresponding commits (detach run 02:50, commit `f2d3e27` 02:58;
  invalidation run 03:07, commit `2d20d6c` 03:22). Do not describe the device
  artifacts as proof of a specific commit hash.
- Build logs (ignored by `.gitignore`, supplied for provenance only):
  `a1-device-build.log`, `a1-device-fix-build.log`,
  `a1-device-marker-build.log`, `a1-device-final-build.log`,
  `a1-invalidation-build.log`, `a1-probe-tests.log`.

## Artifacts and what they prove

| Artifact | Kind | Proves | Does **not** prove |
|---|---|---|---|
| `a1-device-detach.log` | device `app.log` excerpt | `A1 onCleared: detach only` at 02:50:52.842, `destroy MainActivity` at :844, **zero** `connection stopped` after, 38 adapter feed lines through `RUN_COMPLETE` at 02:51:12.849 | Attribution (`all UNATTRIBUTED epoch=0`), execution state projection, durable recovery |
| `a1-device-reattach.png` | device screenshot | session list after relaunch, 107 msgs; engine history showed exactly one `A1NOSTOP` prompt and final `A1NOSTOP` | execution-view UI (none exists yet) |
| `a1-device-invalidation.log` | device `app.log` excerpt | binding switch with **no run in flight**: `registry.stop` epoch 1, `supervisor.stop` epoch 2 | adapter.stop/close on device, live-run recovery, delayed-frame rejection |
| `a1-junit` XML set | JVM | controller transitions, provisional buffering/commit/rollback, real-router frame delivery with a **fake connection port**, stale epoch rejection, container listener wiring, shutdown and terminal-recovery probe labels | real socket transport, device lifecycle, two-ViewModel device race |
| `e5-r6-junit.xml` | JVM harness | registry stop signal + token-protocol barrier | production controller attachment (A1 is now implemented, but this test still uses its own harness) |

## Honest scope statement

- Real SSE ownership moved to `ExecutionEventAdapter`; device log shows frames
  continuing after screen teardown, but all device frames were `UNATTRIBUTED`.
  `CONFIRMED` attribution is proven only in JVM tests.
- Production now calls `declare`, `subscribe`, `markRunning` and terminal marking
  through the confirmed-event path; the durable/recoverable effects belong to A2.
- No project-switch-with-live-run evidence, no device two-ViewModel stale-event
  evidence, and no device evidence for watchdog/recovery/shutdown/debug-kill
  routes. Those routes are source-wired and JVM-pinned where a seam exists.
- Generated Gradle logs are not structured evidence; the XML reports are.
