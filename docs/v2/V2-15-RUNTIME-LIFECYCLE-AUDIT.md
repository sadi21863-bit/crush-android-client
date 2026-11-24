# V2-15 Phase B — runtime lifecycle audit

Status: **read-only investigation, 2026-11-23, HEAD `adc26c0`.** No source,
test, dependency, workflow or committed document was changed. This audit answers
the Phase B questions and stops before any lifecycle implementation.

**Verdict up front:** there is **no proven engine leak per event**. A stopped
runtime releases its child process, reader/socket resources and supervisor job.
There are four real lifecycle findings, one of which is event-driven retention
(`ConnectionRegistry.registrationKey`), one incomplete teardown path
(`AppContainer.shutdown`), one rare proxy leak on terminal start failure, and one
bounded per-project object retention that does **not** justify automatic
disposal yet. Recommended fixes are targeted; runtime eviction is deferred until
a stress/PSS measurement exists.

## 1. Ownership map

```
Application (CrushMobileApp)
└── AppContainer (process-scoped; containerScope = SupervisorJob + Main.immediate)
    ├── keySession / lockState / launchRoute / projectStore / projectTrust
    ├── crushEngine (legacy CrushEngine) + engineSupervisor (legacy supervisor)
    ├── EngineInstanceRegistry<EngineRuntime>
    │   └── key -> EngineRuntime(engine: CrushEngine, supervisor: EngineSupervisor)
    ├── ConnectionRegistry
    │   ├── entries: key -> live CrushConnection (at most one per key)
    │   ├── registrationKey: CrushConnection -> key (tombstones, never removed)
    │   └── pending: key -> deferred CrushConnection stops
    ├── reviewHistory (per-turn cards)
    └── settings/tuning collectors on containerScope

ChatViewModel (one per nav back-stack entry; viewModelScope)
├── eventConnection: CrushConnection ──> CrushEventRouter ──> run channels
├── eventRouter: CrushEventRouter
├── workspaces: WorkspaceManager? (path, cached id/generation, permission wire)
├── CrushSession(api, WorkspaceManager)
└── run handles / transcript state

EngineSupervisor (one per runtime; scope = AppContainer.containerScope)
├── supervisorJob (null after stop)
├── _state: StateFlow<EngineState>
├── startListeners: CopyOnWriteArrayList (never removed; one per runtime)
└── engine: CrushEngine

CrushEngine (one per runtime)
├── process: Process? (null after stop / timeout)
├── egressBridge: EngineProxy (loopback-only egress relay)
├── paths: EnginePaths (socket file, state dirs)
└── startLock / maySpawn
```

The legacy pair is outside the registry by construction
(`EngineInstanceRegistry.kt:21-23`, `AppContainer.kt:853-856`). Chat runs on the
legacy key; two non-legacy slots are project engines.

## 2. State and lifecycle

Registry grain (`EngineInstanceRegistry.kt:74`):

```
IDLE --requestStart--> STARTING --recordRunning--> RUNNING
  ^                         |                        |
  |                         | terminal Failed(false) | stop()
  |                         v                        v
  +---------------------- (slot released)        STOPPING -> STOPPED
```

- `requestStart` reserves in one `synchronized(lock)` section and counts other
  keys' live children or `STARTING` reservations
  (`EngineInstanceRegistry.kt:160-177`, `251-265`).
- A terminal `Failed(willRetry=false)` releases the reservation through the
  liveness read; the grain itself is not rewritten
  (`EngineInstanceRegistry.kt:238-262`).
- `stop` is lookup-or-noop, records `STOPPING`, calls the supervisor's
  synchronous stop, records `STOPPED`; the entry stays
  (`EngineInstanceRegistry.kt:196-213`).
- The supervisor cancels its loop, stops the child, and sets `Stopped`
  (`EngineSupervisor.kt:159-170`). `CrushEngine.stopBlocking` nulls `process`,
  destroys/waits/forcibly kills, clears `cachedVersion`, and stops the proxy
  (`CrushEngine.kt:536-550`).
- `EngineProxy.stop` closes the listener and all relays
  (`EngineProxy.kt:118-126`).

## 3. Findings

Severity is about resource/lifecycle risk, not UI polish. "Proven" means the
source path is unambiguous; "measured" is reserved for device/heap evidence,
which this phase did not produce.

### L1 — `AppContainer.shutdown()` stops only the legacy engine (Medium)

**Proven.** `AppContainer.shutdown()` (`AppContainer.kt:965-968`) reads
`supervisorInstance` (legacy only) and calls `stop()`. It never iterates
`engineRegistry`, so project supervisors and their engine children are not
stopped on the explicit termination path used by `CrushMobileApp.onTerminate`
(`CrushMobileApp.kt:114-118`).

**Sequence:** two projects running → `onTerminate` → legacy supervisor stops →
project `EngineRuntime`s untouched → app process ends. The child engine is a
separate process; it is not guaranteed to die with the app, and remains until
its idle timeout. Sockets/state dirs stay until then.

**Impact:** bounded by the engine idle timeout, not permanent, and
`onTerminate` is not guaranteed by Android. User-visible only as a short-lived
orphan process after an explicit termination; no in-app behavior change.

**Tests:** none for `shutdown`; no test calls `AppContainer.shutdown`.
**Correction:** iterate `engineRegistry.forEachRuntime { it.supervisor.stop() }`
before/after the legacy stop, and revoke live connections. Add a JVM test with
a registry of fakes or a Robolectric container seam. This is a small, safe
teardown completion.

### L2 — `CrushEngine.stopBlocking()` can leave the egress bridge up (Medium)

**Proven.** `stopBlocking` returns when `process == null`
(`CrushEngine.kt:536-540`) **before** `egressBridge.stop()` (`:549`). The start
timeout path destroys and nulls the process and returns a retryable failure
(`CrushEngine.kt:507-522`). After the supervisor exhausts its start budget and
returns terminal `Failed(willRetry=false)` (`EngineSupervisor.kt:441-455`), a
later `stop()` observes `process == null` and returns without closing the
bridge. The bridge's accept coroutine/`ServerSocket` and its
`CoroutineScope(SupervisorJob + IO)` (`EngineProxy.kt:64, 85-109`) remain until
process death. `EngineProxy.stop` also never cancels its scope.

**Reproduction:** force repeated start timeouts until terminal failure on a
runtime, then call `supervisor.stop()`; the loopback listener remains bound.
**Impact:** one loopback listener + coroutine scope per runtime that ended in a
terminal start failure; rare, but not released by the normal stop path.
**Tests:** `CrushEngineSpawnVetoTest` (4) covers the birth interlock;
`EngineProxyConnectTest` starts/stops a healthy proxy but does not cover the
`process == null` terminal path or scope cancellation.
**Correction:** stop the bridge before the `process == null` early return (or
make `stopBlocking` always call it), and cancel/recreate the proxy scope in
`stop`/`start`. Add tests: a proxy is closed after a start that never assigned a
process; `stop` is idempotent; the port is free after stop.

### L3 — `ConnectionRegistry.registrationKey` retains every connection forever (Medium, event-driven)

**Proven retention.** `registrationKey` is a `ConcurrentHashMap<CrushConnection,
String>` and is **never removed** (`ConnectionRegistry.kt:61-67`). `attach`
adds every connection (`:114`); `revoke`/`revokeIfOwn` remove only from
`entries`, not from `registrationKey` (`:138-163`). The comment calls this
"tombstone memory" and intentionally keeps it for the stale-VM reclaim
predicate (`isSuperseded`, `:202-210`). A stopped `CrushConnection` still holds
its `_state`, transition ledger, a closed `CrushEventRouter`, `clientSource`
lambda, `socketFile`, `gson`, and the unknown-type map
(`CrushConnection.kt:64-114, 196-204`). The reader thread is cancelled and
`thread` nulled, so this is object retention, not a live thread leak.

**Sequence:** each ChatViewModel attach/rebind creates a `CrushConnection`
(`ChatViewModel.kt:1787-1818`) and registers it; on clear/switch/supersession
the live entry is stopped, but the tombstone stays. Distinct chat entries and
instance rebuilds accumulate one tombstone each for the process lifetime.

**Impact:** unbounded by event count (not by distinct projects); per-entry size
is small but unmeasured. No user-visible behavior. This is the strongest
candidate for a retention fix, and it is independent of engine runtimes.
**Tests:** `ConnectionRegistryTest` covers supersession, revoke-if-own and
deferral; none asserts tombstone count or that released connections become
collectable.
**Correction options:**
1. Replace the strong tombstone map with weak keys (a synchronized
   `WeakHashMap<CrushConnection, String>` or explicit weak references). A stale
   VM/reader that still holds the connection keeps its tombstone; once the VM
   and reader are gone, the entry can be collected. This preserves
   `isSuperseded` for every live stale observer without retaining dead graphs.
2. Or store a lightweight `(key, ownerEpoch, identity)` tombstone and remove the
   full connection reference at revoke/supersede, changing `isSuperseded` to
   take the identity token. This is a small API change but needs careful
   tests because the tombstone is load-bearing: removing it naively makes a
   superseded reader look un-superseded and re-enter the reconnect ladder.

Do **not** simply clear `registrationKey` on revoke; that changes the reclaim
contract.

### L4 — Engine runtimes are retained per distinct project for the process (Low/Medium, bounded)

**Proven retention, not proven harm.** `runtimes` is never removed
(`EngineInstanceRegistry.kt:98, 114-122`); `stop` keeps the entry
(`:196-213`); there is no public removal API. Each retained `EngineRuntime`
holds a `CrushEngine` and `EngineSupervisor`. After a normal stop the engine has
`process=null`, the supervisor job is null, the state flow is `Stopped`, the
proxy listener is closed, and the child is gone. The remaining graph is small
objects, paths, tuning, one start listener, and (for L2 terminal cases) a
possibly-live proxy. Project removal deletes the `crush-<key>` state directory
(`ProjectsViewModel.kt:182`) but leaves the runtime node.

**Bounded by:** the number of distinct project keys ever opened in one process
(plus the chat/legacy pair). It is not driven by turns, messages or frames.
**Impact:** unmeasured; no thread or child after a normal stop. A 100-project
session may retain 100 small graphs; without a heap measurement this is not a
proven leak, and automatic eviction would add stale-reference risk.
**Tests:** `EngineInstanceRegistryTest` (19) pins idempotency, stop and the
budget; **no disposal API or growth stress test**.
**Correction (deferred):** only if a measurement shows material retained heap,
add an explicit `removeStopped(key)` with preconditions: grain `STOPPED`, no
live child, not the active binding, no registered connection, no run in flight;
`runtimeFor` reconstructs. Add concurrent stop/start/remove tests and a
50-project stress test. Do not evict automatically.

### L5 — Grain can read `RUNNING` after a terminal run failure (Low, bookkeeping)

**Proven.** `recordRunning` sets `RUNNING` on start
(`EngineInstanceRegistry.kt:180-182`). If a started engine later dies and the
supervisor returns terminal `Failed(willRetry=false)`
(`EngineSupervisor.kt:441-455`), no registry hook rewrites the grain; `stop`
is not called on that path. `inspect()` then shows `RUNNING` while liveness is
`Failed`. The budget ignores `RUNNING` and uses liveness/`STARTING`
(`:251-265`), so this is not a false refusal or over-admission; it is
bookkeeping truth. Optional hook: `recordTerminalFailure(key)` from the
supervisor terminal path. No urgency.

### L6 — `containerScope` and proxy scopes are process-scoped (Observation)

`containerScope` (`AppContainer.kt:51-53`) is never cancelled; the container is
process-scoped, so that is intentional. `EngineProxy` creates a per-instance
`CoroutineScope(SupervisorJob + IO)` (`EngineProxy.kt:64`) and never cancels it
on stop; the accept loop exits when the listener closes, but the scope object
remains. Fold the cancellation into L2; do not cancel `containerScope` while
the process is alive.

### Non-findings (explicit)

- **No duplicate runtime**: `runtimeFor` double-checks under the lock
  (`EngineInstanceRegistry.kt:114-122`).
- **No reader-thread leak on normal connection stop**: `stop` cancels the
  in-flight call and nulls the thread (`CrushConnection.kt:196-204`).
- **No child/socket after a normal stop**: `stopBlocking` destroys the process,
  clears it, and stops the proxy (`CrushEngine.kt:536-550`).
- **No stale-runtime reuse today**: the registry never replaces a key, so a
  supervisor/engine object is stable for the process; the workspace generation
  counter (`WorkspaceManager.onEngineRestarted`) invalidates ids across
  restarts.
- **No start/stop birth race found**: the born-or-never interlock and
  `CrushEngineSpawnVetoTest` cover child birth; `stop` cancels the loop before
  `stopBlocking` (`EngineSupervisor.kt:159-170`).

## 4. Reference-retention analysis

| Object | Owner | Released? | Evidence |
|---|---|---|---|
| Legacy `CrushEngine`/`EngineSupervisor` | `AppContainer` fields | process death; `shutdown()` stops | `AppContainer.kt:156-202, 965-968` |
| Project `EngineRuntime` | `EngineInstanceRegistry.runtimes` | **never**; stop only | `EngineInstanceRegistry.kt:98, 196-213` |
| Project engine child | `CrushEngine.process` | yes, on stop/timeout | `CrushEngine.kt:536-550` |
| Project supervisor job | `EngineSupervisor.supervisorJob` | yes, on stop | `EngineSupervisor.kt:159-170` |
| Egress bridge listener/scope | `EngineProxy` | normal stop yes; **terminal failure no** | `CrushEngine.kt:536-550`; `EngineProxy.kt:64, 118-126` |
| Live event connection | `ConnectionRegistry.entries` + VM | yes, on clear/switch/remove | `ConnectionRegistry.kt:138-163`; `ChatViewModel.kt:1873-1884` |
| Connection tombstone | `ConnectionRegistry.registrationKey` | **never** | `ConnectionRegistry.kt:61-67, 114, 155-163` |
| Reader thread | `CrushConnection.thread` | yes, stop cancels | `CrushConnection.kt:196-204` |
| Event router/channels | `CrushConnection.router`; VM | router close clears channels | `CrushEventRouter.kt:207-217` |
| `WorkspaceManager`/`CrushSession` | `ChatViewModel` | VM GC after nav clear; no container ref | `ChatViewModel.kt:1221-1222, 1873-1884` |
| Run handles / transcript | `ChatViewModel` | viewModelScope/GC | `ChatViewModel.kt` run paths |
| Settings collector | `containerScope` | process death | `AppContainer.kt:196-200` |
| Review history | `AppContainer.reviewHistory` | process death | `AppContainer.kt:154` |

## 5. The ten Phase B questions

1. **Who owns each runtime?** `AppContainer` owns the legacy pair; the
   container-owned `EngineInstanceRegistry` owns every project runtime for the
   process. `EngineRuntime` bundles `CrushEngine` + `EngineSupervisor`; the
   supervisor owns the loop and state; the engine owns the child/proxy.
2. **Exact lifecycle?** On bind, `runtimeFor(key)` builds idempotently; start
   gates then `supervisor.start()`; the loop spawns, reaches Running, and on
   stop cancels its job then `CrushEngine.stopBlocking`; the registry node stays
   STOPPED and may restart.
3. **When is an engine stopped?** On binding switch to another key (unless a run
   is in flight), project removal (VM refuses while a run is in flight), the
   legacy `shutdown()` path, and supervisory terminal/idle paths internal to
   the supervisor. Not on background/foreground.
4. **When are sockets/connections disposed?** The engine unlinks its socket on
   close; `CrushEngine.stopBlocking` destroys the child and closes the proxy.
   Event connections are stopped on VM clear, binding switch, supersession or
   project removal; the **tombstone** remains (L3).
5. **What remains after stopping?** The registry node and its small engine/
   supervisor/proxy graph; paths/tuning/state flow/listener; a possibly-open
   proxy on the terminal-failure path (L2); connection tombstones (L3).
6. **Can a runtime be reconstructed?** Yes: `runtimeFor` builds a new pair under
   the same key; the socket path and state dir are key-derived, and workspace
   ids are re-acquired by path. But a **disposed** runtime must not be evicted
   while a stale VM, run or connection can still touch it; supersession and the
   gen counter are the guards.
7. **Can disposal race with an in-flight request?** Yes, if implemented without
   a run/observer guard. Today `engineRunInFlightFor` guards engine stop and
   connection revocation (`AppContainer.kt:755-778`); disposal would need the
   same guard plus a connection/epoch check. No current disposal code races
   because none exists.
8. **Can a retry use an obsolete runtime?** Not today: the registry never
   replaces a key, so the supervisor/engine object is the same one across
   retries; workspace ids are generation-flushed. Introducing eviction would
   require a generation/epoch on the runtime, or a retry could address a removed
   node.
9. **Project removed or rebound?** Removal stops the engine (no-run guard),
   revokes the connection (deferred if a run is in flight; remove refuses then),
   deletes `crush-<key>` state, and leaves the runtime node (L4). Rebind stops
   the outgoing key unless a run is in flight; its runtime stays STOPPED.
10. **Process recreated?** All in-memory state is gone; `AppContainer` rebuilds;
    project runtimes are constructed on demand; workspace ids are invalid and
    reattached by path; old child processes are not addressable by the new
    process and rely on their idle timeout if the old process died uncleanly.

## 6. Concurrency and cancellation

- **Birth safety:** the `CrushEngine` birth-or-never interlock and supervisor
  `maySpawn` are the authority; `CrushEngineSpawnVetoTest` (4) and
  `SupervisorHysteresisTest` (17) cover the decision/backoff paths.
- **Registry reservation:** `requestStart` is atomic; the barrier test and
  terminal/retry tests are green (V2-12/13).
- **Stop vs start:** the supervisor cancels its job first, then synchronously
  stops the child; the engine re-checks liveness under its start lock. No new
  race found. No test drives `registry.stop` concurrently with
  `registry.requestStart`; that is a Phase C test gap, not a proven defect.
- **Connection supersession:** the epoch map and deferred revocation are
  pinned by `ConnectionRegistryTest` (15). The tombstone map is the retention
  cost of that correctness (L3).
- **Cancellation:** `runCatchingNonCancellation` preserves cancellation in the
  workspace paths; supervisor job cancellation is intentional. A cancelled
  start does not assign a process without the interlock.

## 7. Tests: existing and missing

Existing relevant coverage:

- `EngineInstanceRegistryTest` 19: idempotency, stop, budget, STARTING
  reservation, terminal release, retrying failure, barrier race.
- `ConnectionRegistryTest` 15: supersede, revoke-if-own, deferred flush,
  `isSuperseded`.
- `SupervisorHysteresisTest` 17: start budget, hard failures, wedge, watchdog,
  pressure.
- `CrushEngineSpawnVetoTest` 4: birth veto/lazy evaluation.
- `EngineProxyConnectTest` 2: proxy start/stop happy path.
- `TwoProjectAttachTest` (instrumented, not executed here): isolation, not
  lifecycle.

Missing:

- `shutdown()` stops every registered supervisor and connection (L1).
- Terminal start failure leaves no proxy listener/scope (L2).
- Connection tombstone is bounded/collectable while `isSuperseded` still works
  for a live stale VM (L3).
- N-project runtime growth and heap/PSS; no stress test (L4).
- Grain vs supervisor state after terminal run failure (L5).
- Registry `stop` racing `requestStart` (Phase C).

## 8. Resource costs

- **Child process:** gone on normal stop; not retained by the registry.
- **Reader thread:** one per live `CrushConnection`; cancelled and nulled on
  stop. Tombstones do not keep a thread alive.
- **Loopback listener:** one per live runtime's egress bridge; closed on normal
  stop; can remain on the terminal-failure path (L2).
- **Files:** `crush-<key>` state dirs and sockets. Project removal deletes the
  dir; chat/legacy dirs persist. Socket files are unlinked by the engine.
- **Heap:** no measurement this phase. Runtime/connection graphs are small per
  unit, but L3 grows with events and L4 with distinct projects; a heap dump or
  PSS/1-vs-2-engine capture is required before calling either material.

## 9. Conclusions on disposal

1. **Automatic runtime disposal is not justified yet.** Normal stops release
   the child, socket, reader and supervisor job; the retained graph is small and
   bounded by distinct projects. Eviction adds stale-reference/cross-generation
   risk and would need a contract for runs, observers and retries.
2. **The event-driven retention is `ConnectionRegistry.registrationKey`, not
   the runtime registry.** Fix that first, preferably with weak tombstones or a
   lightweight token, preserving `isSuperseded` for live stale VMs. This is the
   smallest change with the clearest unbounded-growth property.
3. **Complete the teardown paths:** `AppContainer.shutdown` should stop every
   registered supervisor/connection; `CrushEngine.stopBlocking` should always
   close the egress bridge, including when `process == null`. These are
   correctness/hygiene fixes, not eviction.
4. **Measure before evicting:** a 50-project stress test plus a device PSS
   capture for app + 1/2/3 engine children. If retained heap is material, add
   explicit `removeStopped(key)` under the preconditions in L4; never evict a
   runtime automatically.
5. **No source change was made.** Implementation of L1/L2/L3 awaits explicit
   approval, and L4 awaits the measurement.

## 10. Evidence index

| Path | Role |
|---|---|
| `engine/EngineInstanceRegistry.kt` | registry, grain, budget, stop, retention |
| `di/AppContainer.kt` | container ownership, runtime builder, shutdown, tuning |
| `engine/EngineSupervisor.kt` | loop, stop/restart, terminal failure, listeners |
| `engine/CrushEngine.kt` | process/proxy lifecycle, timeout, stopBlocking |
| `engine/EngineProxy.kt` | loopback bridge, scope, start/stop |
| `data/crush/ConnectionRegistry.kt` | live entries, tombstones, deferred revokes |
| `data/crush/CrushConnection.kt` | reader thread, stop, retained fields |
| `data/crush/CrushEventRouter.kt` | channel ownership/close |
| `ui/screens/chat/ChatViewModel.kt` | VM attach, onCleared, run/workspace refs |
| `ui/screens/projects/ProjectsViewModel.kt` | project removal and state-dir delete |
| `app/src/test/.../EngineInstanceRegistryTest.kt` | registry coverage |
| `app/src/test/.../ConnectionRegistryTest.kt` | supersession coverage |
| `app/src/test/.../SupervisorHysteresisTest.kt` | supervisor coverage |
| `app/src/test/.../CrushEngineSpawnVetoTest.kt` | birth interlock |
| `app/src/test/.../EngineProxyConnectTest.kt` | proxy happy path |
| `docs/parity/2026-11-23-v2-baseline-perf/MANIFEST.md` | app PSS/cold start baseline (no per-engine PSS) |
