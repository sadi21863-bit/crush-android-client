# V2-15 Phase C/L4 — runtime retention measurements

Status: **JVM-level analysis + real-device stop/PSS evidence, 2026-11-24.** No
eviction, no `removeStopped(key)`, no Go/dependency change. Device PSS and the
post-stop process/socket census were captured later the same day on `bbf8ca69`;
raw capture in `docs/parity/v2-15-device/V215-DEVICE-EVIDENCE.md`. A 3-child
sample was **NOT PERFORMED** (see §6).

## 1. Question and method

Does the never-evict registry retain *engine resources* after a stop, or only
bounded registry metadata?

Method, in this order:

1. **Source-level retained-graph audit** of every object reachable from a
   stopped registry node (`EngineInstanceRegistry.Node` → `EngineRuntime` →
   `CrushEngine` / `EngineSupervisor`).
2. **Deterministic stop tests**: no live child, no active supervision loop, no
   running proxy after `stop()`.
3. **50-distinct-project stress** on the registry, asserting no eviction, one
   stopped node per key, every fake runtime's stop invoked, and tuning fan-out
   reaching all 50.
4. **Container mapping test** proving the registry's supervisors reach the
   shutdown plan.
5. **Real-device PSS samples** in two reachable binding states (legacy only;
   legacy + one project child), then a **post-stop census** (children, socket
   files, `/proc/net/unix` listeners) after switching away from the project.

## 2. Retained graph after stop (per distinct key)

| Object | Retained after stop | Released / cleared |
|---|---|---|
| `EngineInstanceRegistry.Node` | the node itself (key → node) | grain becomes `STOPPED` |
| `AppContainer.EngineRuntime` | key string, `CrushEngine`, `EngineSupervisor` | — |
| `CrushEngine` | `appContext`, `EnginePaths`, idle-timeout int, bounded `logBuffer`, `startLock` (unlocked), stopped `EngineProxy` | `process = null`; `cachedVersion = ""`; proxy `server = null`, `relays` empty, `scope = null` |
| `EngineSupervisor` | `_state` flow (`Stopped`), tuning value, `appContext`, instance key, start callback | `supervisorJob = null`, `wanted = false` |
| Child process | **no** | destroyed, `waitFor(3s)`, force-destroy fallback (`CrushEngine.stopBlocking`) |
| Egress proxy listener/relays | **no** (closed) | per-start scope cancelled on `stop()` (L2) |
| Registry connection history | **no** | L3 removed the `conn → key` map entirely |

The retained graph is **O(distinct instance keys)** and contains no Activity, no
ViewModel, and no `CrushConnection`. It does contain the application context
(process-scoped by design), the engine's file paths, and each supervisor's
`StateFlow`.

### Why the engine objects themselves stay

`EngineInstanceRegistry.runtimeFor` is intentionally idempotent and never
removes a key: a stopped project is *restarted*, never *rebuilt*. Rebuilding was
the old single-slot eviction hazard (a wasted spawn plus a second supervisor).
The retention is therefore a deliberate metadata bound, not a leak of a child
process.

## 3. What a stop actually releases

| Resource | Mechanism | JVM evidence |
|---|---|---|
| Child process | `process.destroy()` + `waitFor(3s)` + `destroyForcibly()`; `process = null` | `EngineRuntimeLifecycleTest` asserts `engine.isAlive()` false on a constructed-then-stopped runtime; `CrushEngineLifecycleTest` covers repeat/stop-before-start |
| Supervision loop | `wanted = false`, `supervisorJob.cancel()`, `supervisorJob = null`; `EngineState.Stopped` | `EngineRuntimeLifecycleTest` asserts `hasActiveSupervisionForTest()` false; `EngineState.Stopped` |
| Egress proxy listener | `server.close()`, `boundPort = -1` | `EngineProxyConnectTest` asserts `isRunning`/`port` after start, stop and restart |
| Proxy tunnels | every relay socket closed, map cleared | `EngineProxy.stop()` + L2's `EngineStopCleanupTest` |
| Proxy coroutine scope | per-start `SupervisorJob` cancelled | `EngineProxyConnectTest` restart case (fresh scope, no reuse of a cancelled one) |
| Stdout drain thread | daemon thread blocks on the child's pipe; process destruction closes it and the read loop ends | **Code-level**; not re-measured here (needs a real child) |
| Socket file | **no** once the stop completed on device: the engine removed `crush-p*.sock`; a crash leaves a stale node, which the engine removes before it next listens and `EngineSockets.probe` reports "not up" with no listener | device: both project socket files absent after stop; legacy listener only (`V215-DEVICE-EVIDENCE.md`) |

`crush-stdout` is a daemon thread and the stream is owned by the child; it
cannot outlive the closed pipe indefinitely. This is an expectation from the
code, not a measured thread census.

## 4. 50-distinct-project stress

`EngineInstanceRegistryTest.fifty distinct projects stay registered with one
stopped node each` (new, deterministic):

- registers `p1 … p50` through `runtimeFor` (one fake runtime per key);
- stops every key and asserts `stop` returned true for each;
- asserts `inspect()` has **50** entries, all grain `STOPPED`;
- asserts every fake runtime's `stopped` flag was set;
- asserts tuning fan-out still visits all 50.

The test proves bounded registry metadata and complete stop fan-out. It does
**not** prove 50 real children ever existed, because spawning the arm64 engine
is impossible in JVM.

## 5. Container-level evidence

`AppContainerShutdownTest` (new, Robolectric):

- creates the real container, the legacy supervisor, and project runtimes
  `p1`/`p2`;
- `shutdown()` returns **3** (legacy + two projects), a second call returns
  **0** (one-shot plan);
- every supervisor ends `Stopped`, with no active loop and no live child;
- a repeated `runtimeFor("p1")` returns the same object (one stop, not two).

This closes the L1 "plan-level only" gap at the container mapping level. It
still starts no real engine, so it cannot prove process death on app
termination.

## 6. Device measurements (2026-11-24)

Device: Redmi Note 7 Pro, API 29, arm64-v8a, serial `bbf8ca69`; app
`com.crush.mobile` 1.0.0 debug, engine `v0.100.0` (build `2my2izixtou8`).
Method: three `adb shell dumpsys meminfo <pid>` samples per process per state
(the `TOTAL:` PSS line, kB), same UI state within a state, no other app in the
foreground. Raw capture: `docs/parity/v2-15-device/V215-DEVICE-EVIDENCE.md`.

| State (UI) | App PSS (kB) | Legacy child PSS (kB) | Project child PSS (kB) |
|---|---|---|---|
| legacy + project `pd871eee8` (project session list) | 99,012 / 99,108 / 99,012 | 23,944 | 40,811 |
| legacy + project `p5ab508c3` (chat, 8-msg session) | 143,297 (transient) / 125,764 / 125,658 | 23,594 | 60,460 |
| after stop: legacy only (legacy chat) | 138,323 / 130,411 / 127,877 | 51,906 / 51,906 / 51,494 | — |
| after stop: legacy only (projects list) | 128,574 / 126,378 | 51,158 | — |

Post-stop census at the same moment as the third row:

- `ps -A -o PID,NAME | grep libcrush` → exactly `14968 libcrush.so` (legacy);
  the project PIDs 14972 / 18718 are gone.
- `/proc/net/unix | grep crush` → only legacy `crush.sock` (one `01` listener
  plus peers); no `crush-pd871eee8.sock` or `crush-p5ab508c3.sock` entry.
- `run-as ... ls -l files/crush-pd871eee8.sock files/crush-p5ab508c3.sock` →
  `No such file or directory` for both; the engine removed the socket files.

Caveats:

- Engine child PSS is **workload-dependent**: the legacy child measures 23.9 MB
  idle and 51.9 MB after a chat bootstrap; a project child measures 40.8 MB on
  a session list and 60.5 MB with a chat open. 23.9/60.5 are not fixed costs.
- App-process PSS includes Compose UI and content caches (99 MB on an idle
  project list → ~128 MB after chat interactions). Per-process PSS values also
  share pages, so summing them is an upper bound. PSS deltas are directional;
  the process/socket census is the retention proof.
- **3-child sample NOT PERFORMED.** A normal binding switch stops the outgoing
  non-legacy engine when no run is in flight (`AppContainer.kt:775-800`), so
  ordinary navigation reaches legacy + one project at most. Two live project
  children need the run-in-flight deferral/handoff path or a budget override;
  that is a separate experiment.

### Reproduction procedure

1. Install the debug build; record `getprop ro.product.model`,
   `ro.build.version.sdk`, and the package `versionName`.
2. Open a project and wait for `engine Running` in `files/app.log`; sample the
   app pid and each `libcrush.so` pid with `dumpsys meminfo <pid>` three times.
3. Switch to **Crush Chat** (legacy binding); confirm
   `Lifecycle: stopping project engine ...` in `files/app.log`, then re-sample.
4. Confirm the project pid is gone, its socket file is absent, and
   `/proc/net/unix` has no listener for it.

## 7. Conclusion

- A stopped runtime retains **bounded registry metadata**: one node, one engine
  object, one supervisor object and their small fields per distinct key.
- It retains **no child process**, **no proxy listener/tunnel**, **no active
  supervision job**, and (L3) **no connection history**.
- 50 distinct projects are structurally supported; 50 real engines are not, and
  the production budget is 2 non-legacy + the legacy singleton.
- On device, the project child, its socket file and its listener are gone after
  the stop path, with the app still running and responsive; the only engine
  left is the legacy supervisor. That is the runtime half of `A13` the JVM
  tests could not reach.
- No measurement justifies runtime eviction. The `removeStopped(key)` proposal
  stays deferred.
