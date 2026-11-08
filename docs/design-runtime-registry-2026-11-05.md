# Gate-7 design memo — Engine instance registry, wedge recovery, connection reclaim

Produced 2026-11-05 as a design read (no code). Evidence base: `AppContainer.kt`,
`EngineSupervisor.kt`, `CrushEngine.kt`, `EnginePaths.kt`, `EngineSockets.kt`,
`CrushConnection.kt`, `WorkspaceManager.kt`, `ChatViewModel.kt`
(bind/bootstrap/attach/`onCleared`), `ChatScreen.kt` composition effects,
`EngineState.kt`, `NavGraph.kt`; docs: `capabilities.md` S3/S4 + S5 drive,
`chat-code-modes-plan-2026-11-04.md` §7 (Gate-7, F06), `decisions-open.md`
items 10/11. Test seams: `SupervisorHysteresisTest` (pure give-up rules),
`TwoEngineInstancesPerProjectTest` (device).

UPGRADE while drafting, from the same-evening live drive (see
capabilities.md S5 drive, "wedge shape 2"): the Starting→Running wedge was
caught live while AWAKE with the S5 instrumentation — supervisor logged
`engine Running after 3952ms` and kept running, yet NO VM collector received
the state for ~12 minutes; a later re-bind's fresh subscription served the
STILL-CACHED `Running` and fully recovered in ~3 s. So the wedge now has two
measured shapes, and the registry/reclaim sections below must cover both:

- **Shape 1 (overnight, twice):** stop-vs-in-flight-start race → supervise
  loop dies with the child alive; nothing probes; UI pinned on "engine
  stopped" forever (force-stop cures).
- **Shape 2 (live 2026-11-05 18:58→19:10, awake):** loop ALIVE + child ALIVE
  + child-serving socket; the state WRITE happened but no subscriber saw it —
  the collector fleet went silent (nav-covered entries' collectors cancelled,
  and the composed-but-wedged surface never re-subscribed). Any supervisor-
  internal watchdog CANNOT shape-2 (the supervisor is healthy); only the
  container-level registry + collector-reclaim (§3) or a UI-rebinding change
  covers it.

## §0 Root-cause pin (file:line)

**Implementation status 2026-11-08 — the memo shipped in both phases; the
design text and this §0 pin stay as written, as the record of the
2026-11-05 tree.** Evidence of record: `1d229de` (phase 1: born-or-never
interlock + readiness reorder + starting watchdog) and `d818a9c` (phase 2:
engine instance registry + connection supersession/reclaim). The file:line
anchors below are dated evidence of that tree — the refactor they
prescribed moved many of them — so the current-state refresh is listed
here once, not rewritten inline:

- **§1 registry:** `engine/EngineInstanceRegistry.kt` is the container-owned
  per-key map, wired at `AppContainer.kt:220`; the single
  `projectRuntime: EngineRuntime?` slot (cited at `:158`) is GONE, and the
  §4-flagged "throwaway runtime just to stop a background key" corner is
  the registry's lookup-or-noop `stop`. The liveness budget is
  `maxLiveNonLegacy = 2` with the fail-closed named refusal; the VM's
  start path consults it (`AppContainer.requestEngineStart` `:685` →
  `requestStart`). Laws pinned in `EngineInstanceRegistryTest`
  (third-live-instance refusal, own-slot reclaim) and
  `ConnectionRegistryTest`.
- **§1 interlock:** the spawn veto shares the `stopBlocking()` monitor
  (`CrushEngine.kt:345–370` for the synchronized birth section, veto seam
  `spawnVetoed` at `:602–619`; pinned by `CrushEngineSpawnVetoTest`) — so
  the §0 race pin reads as historical root-cause evidence: that orphan
  interleave is no longer constructible.
- **§2 starting watchdog:** `STARTING_WATCHDOG_MS = 90_000` with
  probe-before-teardown and one bounded restart → the honest terminal
  `Failed(willRetry=false)`, re-using the wedge budget constants exactly as
  §4 requires (`EngineSupervisor.kt:242–295`, `MAX_WEDGE_RESTARTS = 5`
  `:754`, `MIN_HEALTHY_RUN_MS = 60_000` `:763`; pinned in
  `SupervisorHysteresisTest`).
- **§3 reclaim:** `data/crush/ConnectionRegistry.kt` (per-key supersession
  incl. legacy `""`, revoke-only-if-own, stop deferred while a run is in
  flight on the key) is wired per-VM in the chat layer: the owner-epoch is
  taken at VM construction (`ChatViewModel.kt:549`), supersession is
  checked at the rebuild point (`:1280`), registration happens at
  `:1342`, and `revokeIfOwn` runs from the entry's teardown (`:1407`);
  pinned by `ConnectionRegistryTest`. §3's `ChatViewModel.kt` block
  anchors (and §2's) describe that file as it stood on 2026-11-05.

§4's must-not-regress list was honored as designed — its named constants
(`MIN_HEALTHY_RUN_MS`, `MAX_WEDGE_RESTARTS`, `MAX_START_ATTEMPTS`) are the
shipped ones, and the remaining "device acceptance (1 each)" rows in the
test matrix stay the outstanding drive work.

### Where the chat child survives its `stopping project engine` line (F06, shape 1)

1. `AppContainer.kt:364–370` — `applyBinding` logs
   `stopping project engine for instance 'X' (no run in flight)` and calls
   `runtimeFor(oldKey).supervisor.stop()`. The log line precedes any stop
   guarantee.
2. `EngineSupervisor.kt:158–169` — `stop()`: `wanted=false`,
   `supervisorJob?.cancel()`, then **synchronously** `engine.stopBlocking()`,
   then `_state.value = Stopped`. The KDoc's promise covers only the OUTER
   race.
3. `CrushEngine.kt:255–287` vs `:363–378` — the spawn path writes `process`
   at `:287`, but the child is already alive at `:277`
   (`ProcessBuilder(cmd).start()`). `stopBlocking()` is `@Synchronized` but
   **snapshots first** (`val proc = process`, `:365`), and the
   spawn-and-assign pair is not atomic against that snapshot (spawn runs
   under `startLock`, deliberately not shared with `stopBlocking` — comment
   at `:357–362`).

The decisive mechanics: the in-flight `supervise()` coroutine sits suspended
inside `engine.start()`'s readiness loop (`:290–333`). When `stop()` cancels
the job, cancellation is honored at the NEXT suspension point — `delay(250)`
at `:332` — so everything after `:243` (bridge start, config injection,
`ProcessBuilder.start()`, `drainOutput`) still executes after the stop
decision. The interleave that produced the measured F06:

```
main : stop() → wanted=false, cancel, job=null
main : stopBlocking() → snapshot process==null → RETURN (kills nothing)  ← :365–368
IO   : (cancelled-but-starting attempt) spawns child at :277,
       sets process = :287
IO   : delay(250) at :332 → CancellationException → unwinds WITHOUT
       destroying the child
```

Result: `EngineState.Stopped` written by `:168`, supervisor job gone, child
alive — exactly "the chat child survived its own stop line". The morning's
three children = legacy (permanent by design) + this chat orphan + the
newly bound keyed child.

### What keeps state at "engine stopped" (shape 1, and why ONLY force-stop cured it)

Every `EngineState` write is owned by the `supervise()` loop
(`EngineSupervisor.kt:221, 237, 325`) plus `stop()`'s unconditional `:168`.
The only socket readers during boot: the readiness loop inside the now-
cancelled start coroutine (`CrushEngine.kt:318`), and
`monitorUntilUnhealthy`'s inline health poll — which exists only while the
supervise job LIVES. When the race kills the loop, no app-side probe can
observe "a live child is bound to MY socket". `force-stop` cures because a
rebooted process builds a FRESH `CrushEngine` (null `process`), the
re-entrant early-return at `:172–180` (process already alive) can't see the
orphan, and — decisive for the child — the engine REMOVES a stale socket
file before listening (`:192–196`), so the respawn steals the path; the
orphan keeps serving its unlinked inode until the engine's own idle ties it
off.

The UI then locks itself in: `ChatScreen.kt:120–131` is event-driven only —
`LaunchedEffect(activeInstanceKey)` fires on key CHANGE, `LaunchedEffect
(engineState)` on a state EMISSION. With the loop dead, no emission ever
comes; `bindEngine(Stopped)` paints "engine stopped" and nothing re-drives
(user staying bound gets the full ~10-min hang; the 07:11 shape wedged the
same way).

### The ~55–60s spawn latency (cousin)

Only honest blockers between bind and the first `exec:` line
(`CrushEngine.kt:252`): `startLock` acquisition, injector file work
(`:206–216`, measured ~5s under write pressure), `egressBridge.start()`
(`:243`) — none reach 56 s. Live candidates: (a) attempt-1 failure + the
backoff/readiness ladder reordering the second spawn's line past the
measurement window; (b) **device doze freezing the process between bind and
spawn** — the readiness deadline uses `System.currentTimeMillis()` (wall
clock, `:290–291`), so a counted freeze turns the first post-freeze
iteration into an instant deadline bust even though the child then binds in
1 s and probes healthy. Disambiguation: log a wall-vs-monotonic
(`SystemClock.elapsedRealtime()`) pair around bind→exec in the attempt
lines; and reorder probe-before-deadline in the loop (§2.3), converting the
freeze case into either success or a legitimate retry.

## §1 Instance-registry design (the Gate-7 map)

**Shape.** Replace the single `projectRuntime: EngineRuntime?` slot
(`AppContainer.kt:158`) with a container-owned map keyed by instanceKey:

```kotlin
class EngineInstanceRegistry {
    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, EngineRuntime>()
    // per-key grain: IDLE / STARTING / RUNNING / STOPPED / STOPPING
    fun runtimeFor(key: String): EngineRuntime   // idempotent per key
    fun stop(key: String, reason: String)        // interlocked, below
    fun activeEngineSnap(): List<Liveness>       // inspect + budget enforcement
}
```

Construction stays exactly `runtimeFor`'s body (`AppContainer.kt:423–442`),
including the per-runtime `onEngineStart { WorkspaceManager
.onEngineRestarted() }` registration (`:437`) — it must land on every
registry-created runtime, not per-slot. Difference: a key's runtime is
**never evicted, only stopped**. The legacy key stays the permanent
process-single pair (`:419–421`), reading `crushEngine`/`engineSupervisor`
unchanged (never stopped, decisions-open item 9).

**Ownership table (per instanceKey):**

| Action | Owner | Seam (today) |
|---|---|---|
| bind/switch (`applyBinding`) | Container only | `AppContainer.kt:328–371`; `_activeInstanceKey` written ONLY by binding setters |
| stop-on-switch, `forgetProjectEngine` | Container only, guarded by `engineRunInFlightFor` (`:219–222`) — flag lives container-side because the VM dies with its nav entry (measured 2026-11-04) | `:364–370`, `:387–400` |
| start (prewarm/keystroke) | VM invokes; supervisor owns | `ChatViewModel.kt:391–393` → `EngineSupervisor.start()` (`:149–156`) |
| spawn, readiness, health | That key's supervisor | `EngineSupervisor.kt:202–335`, `CrushEngine.kt:170–352` |
| state truth (badge) | Supervisor StateFlow, aggregated per key | `AppContainer.kt:408–416` |
| inspect (liveness) | Registry — supervisor state PLUS child liveness (`CrushEngine.isAlive()`, `:114`) PLUS socket probe (`EngineSockets.probe`, the same 2xx predicate the spawn loop trusts) | `EngineSupervisor.kt:532–568` |
| workspace/session/connections | VM, construct-per-bootstrap (`WorkspaceManager` per bootstrap, `ChatViewModel.kt:816`) — unchanged except §3 registration | — |

**Liveness contract.**

1. At most ONE runtime object per key, forever (per process); a stopped key
   may be re-started, never rebuilt. This kills the silent slot-eviction
   where `runtimeFor(oldKey)` AFTER a switch constructs a fresh engine pair
   just to stop it.
2. A runtime's child exists only if `wanted` is true and the attempt
   produced a live process. Every spawn is **born-or-never under the engine
   object's monitor** (fix below), so `stop()` can always deliver "all
   children this runtime spawned die."
3. Liveness is tri-state (stateFlow, child liveness, socket probe) so
   diagnosis and budget enforcement never depend on the UI-observed state.
4. Budget (the plan's max-active-engines item): at most TWO non-legacy keys
   with live children (chat + one project); honest fail-closed refusal at
   `start()` with a named reason. Defense-in-depth: with the interlock, the
   third child is structurally unreachable.
5. Runs in flight pin their child: existing `engineRunInFlightFor`
   semantics preserved; the registry reads the same container flag.

**The interlock that closes the orphan race — per-key, not global.**

- **Veto-at-spawn, atomic birth (the load-bearing half).** In
  `CrushEngine.start()`, the final pre-spawn decision moves INSIDE the
  engine object's monitor — the same one `stopBlocking()` synchronizes on:

  ```kotlin
  synchronized(this@CrushEngine) {
      if (!maySpawn()) { runCatching { egressBridge.stop() }
                         ; return@withContext EngineState.Stopped }
      proc = ProcessBuilder(cmd).start()
      process = proc
  }
  ```

  with `maySpawn()` supplied by the supervisor as `{ isWanted() }` (the
  thread-safe read; `wanted` flips under `sync` BEFORE `stopBlocking` runs,
  `:160–167` — order preserved). `stopBlocking()`'s snapshot now shares one
  critical section with birth. Case analysis: stop's snapshot happens
  before the spawn block → `wanted` false → veto fires, NO child is born;
  the block ran first → `process` is assigned before the snapshot → child
  destroyed. The `:277→:287` window and the cancel-then-spawn window both
  disappear. This also fixes the same-shaped hole in `restart()`
  (`:184–200`).
- **Per-key stop-state grain.** The registry records `STOPPING` while a
  stop's `destroy()+waitFor` is pending and gates that key's OWN next
  `start()` behind it (supervisor-local `Mutex`, never cross-key). Stop
  stays synchronous-fast (≤ ~3s bounded `waitFor`, `CrushEngine.kt:369–374`)
  so main-immediate taps stay serial and bounded; only the same key's next
  start defers. Each supervise loop runs inside the registry's structured
  `Job` for that key, so cancel is structured, never spray-on.

**Why the measured race becomes unconstructible:** with birth-or-veto
atomic under the monitor, `stop()` can never observe a null `process` while
the child is otherwise reachable — either no child was born, or it is in
`process` at snapshot time and destroyed in the same section. And the
"supervision gone while child lives" state — which made the UI wedge
irreversible for shape 1 — stops being constructible.

## §2 Starting→Running bounded-restart fix

**Detection predicate (using the S5 lines).** Wedge signature:

> for key X, log shows `supervise loop start` + `starting attempt N`, with
> NO `engine Running after` and NO `Failed` for > `STARTING_WATCHDOG_MS`
> (90s — above the bounded 60s readiness window + probe grace), WHILE a
> probe of X's own socket (`EngineSockets.probe(healthSocketFile(),
> "/v1/version") == 200`) reports the child bound and serving.

The probe is the same signal the spawn path trusts; per-instance since
`349bad3`; file-absent means "not up yet" (`EngineSockets`), so the
predicate fails closed — a bound-but-frozen `Starting` for >90 s is a wedge,
a missing socket is normal starting.

**Second detectable shape (shape-1 leftovers):** state `Stopped` while X's
socket probes 200 and no live child = the historical orphan signature.
Post-§1 this is unreachable for NEW generations; the inspector logs it once
(evidence-grade). NEVER adopt a foreign child (no owned handle, stop could
not be honored) — the honest treatment is spawn-under-me + probe, yielding
real ownership or real failure.

**The fix, mechanically:**

1. **Reorder probe-before-deadline in the readiness loop**
   (`CrushEngine.kt:291–333`): `proc.isAlive` → probe → THEN the
   wall-clock deadline. After a doze freeze the first resumed iteration
   honestly reports a child that bound during the freeze as `Running`.
   Diagnostic guard: log wall + monotonic elapsed around bind→exec so the
   55–60s gap gets classified (doze vs ladder) instead of guessed.
2. **In-loop starting watchdog** (suspend-safe, per-key): on the predicate,
   `wedgeRestarts++` (reuse the existing start/stop-of-record budget
   constants, unit-pinned in `SupervisorHysteresisTest`), perform ONE
   bounded restart (`stopBlocking()` → `delay(RESTART_DELAY_MS)` → next
   attempt); a SECOND wedged episode in a row writes
   `Failed(reason = "engine bound its socket but the app never confirmed
   readiness", willRetry = false)` and RETURNS — the terminal shape the
   start budget already yields.
3. **Honest UI failure:** `Failed(willRetry=false)` flows through
   `engineStateForActiveInstance` → `bindEngine` → "engine failed:…",
   `engineReady=false` — already implemented, no UI work.

**Why each stop cannot loop:** (a) give-up rules are pure and pinned; the
watchdog counts within the same budget, never resets `attempt`;
`startRetryGivesUp` remains the only give-up law for start FAILURES (the
watchdog governs only the bound-child wedge shape). (b) terminal
`Failed(willRetry=false)` writes once per episode and the loop returns —
leaving it requires an explicit user-driven `start()`/rebind (documented
contract). (c) the watchdog is a branch of the loop itself; `stop()`'s
`job.cancel()` pre-empts it. (d) the §1 interlock removes the only known
way "stopped" could shadow a live child.

**Shape-2 honesty:** the supervisor is healthy in shape 2, so an in-
supervisor watchdog cannot detect it. §3's registry + supersede/reclaim
covers it: a UI that re-subscribes (fresh VM/change of composition) always
gets the flow's current value — the live case recovered exactly this way —
so §3's guarantee is "a subscriber that composes gets the truth"; plus the
registry's inspector can emit one evidence line per surface if it sees
UI-stopped+probe-ok for a key WITHOUT a registered live collector (needs
one cheap kind of collector census: registry tracks which keys have active
streamers).

## §3 Stale-VM connection reclaim

**Defect today:** a chat back-stack entry survives being covered
(`NavGraph.kt:50–68`; a `launchSingleTop` return creates a NEW chat entry
above the old), so the stale `ChatViewModel` keeps its `CrushConnection`
(`:522`) — reader thread retries the backed-off ladder (250 ms→10s cap,
`:296–297`) against a socket whose engine may be stopped, until `onCleared`
(`:1337–1340`). A stale VM is never visited again.

**Design: container-owned `ConnectionRegistry` keyed by instanceKey,
modeled on WorkspaceManager's flush-by-read pattern (P1-11, `:50–74,
:96–125`).**

```kotlin
class ConnectionRegistry {
    private val conns = ConcurrentHashMap<String, CrushConnection>()
    private val connectionGeneration = AtomicLong()
    fun attach(key: String, conn: CrushConnection, inFlight: () -> Boolean, ownerEpoch: Long)
    fun revoke(key: String, reason: String)        // synchronous conn.stop() + router close
    fun isSuperseded(ownerEpoch: Long, conn: CrushConnection): Boolean
}
```

- **Register** at `attachEventStream`'s rebuild point
  (`ChatViewModel.kt:1278–1284`): the VM passes its monotonic
  `ownerEpoch` (the same generation-token idiom already used twice in the
  file — `sessionViewEpoch :458`, `modelSelection :566`). Registering a new
  connection for key K supersedes/revokes the previous one for K — legacy
  `""` included (without supersession a round-trip leaves two live readers
  fed by the same live engine — measured family of the reused-socket
  false-banner defect).
- **Revoke on abandon** where the engine stops on switch: `applyBinding`
  (`:364–370`) + `forgetProjectEngine` (`:387–400`) call
  `registry.revoke(oldKey, …)`, DEFERRED while `engineRunInFlightFor ==
  oldKey` (a turn's stream is not cut; the revocation runs off
  `reportRunInFlight(key,false)` — mirrors the engine-stop guard
  byte-for-byte so the two always agree).
- **Reclaim on read:** the stale VM's next touch checks
  `isSuperseded(...)` (cheap volatile + reference compare) and, if
  superseded, STOPS its reader and logs once instead of entering the retry
  ladder — WorkspaceManager's trick relocated; retries "until reclaim"
  become a bounded single unit.
- **`onCleared`** (`:1337–1340`) additionally unregisters (revoke key ONLY
  if the registry still maps it to this VM's epoch — a newer VM's
  connection must not be killed by an old entry's cleanup).
- The registry never constructs connections; construction stays VM-owned.

**Shape-2 tie-in:** with supersession + a fresh-subscribe-always-served
contract (StateFlow semantics), any newly composed surface gets the
truth-instantly — as measured live. The remaining shape-2 exposure would
only be a composed-but-non-subscribed surface, so §1's `activeEngineSnap`
gains a one-line inspector guard (log evidence only).

**Constraints honored:** revoke is a synchronous `CrushConnection.stop()`
(already main-thread-safe: cancels the in-flight call, `:171–181`); no
global lock; reader unwind needs no coroutine plumbing (plain thread +
`running` flag, as today); a superseded VM's one retry is logged honestly
and ends there.

## §4 Migration risks + test matrix

**Must not regress:**

1. **P1-10 start budget (`EngineSupervisor.kt:609–640`):** `MAX_START_ATTEMPTS=3`, ladder (`:604–606`), terminal honest failure. The watchdog counts within this philosophy — never resets `attempt`; `startRetryGivesUp` stays the sole give-up law for start failures.
2. **P1-11 engine-generation flush (`AppContainer.kt:97–110, 433–437`; `WorkspaceManager.kt:108–125`):** the per-runtime `onEngineStart { WorkspaceManager.onEngineRestarted() }` must land on every registry-created runtime. Over-invalidation across instances is intentional and safe (re-attach is path-derived + idempotent). The re-entrant "process already alive" start path deliberately does NOT bump the generation (`EngineSupervisor.kt:127–128`).
3. **Port-sentinel blind spot:** `bindEngine`'s `p != port` never reacts to engine replacement (port is always the UDS sentinel, `CrushEngine.kt:61`; `ChatViewModel.kt:657–664`) — workspace-id safety rides entirely on P1-11's generation flush. Preserve the companion-level bump reaching managers built outside DI.
4. **F11/349bad3 fail-closed transport:** no plain-TCP fallback; registry/§3 code must not add reconnects that bypass the per-attempt transport resolution (`CrushConnection.kt:207–214`, `EngineSockets.kt:68–77`).
5. **Single-writer pre-spawn window (`CrushEngine.kt:206–216`):** the veto-at-spawn refactor keeps the injector inside the pre-spawn region (veto sits after it, under the monitor).
6. **A11/A12 restore (`AppContainer.kt:275–326`; adoption `ChatViewModel.kt:803–813`):** one-shot latch, user-action-wins, gone-row → legacy. NO second binding path.
7. **Container-owned stop + run-in-flight guard:** stop-on-switch stays in the registry's `stop(key)`; `reportRunInFlight`'s key-guarded clear survives as the flag source.
8. **Main-immediate serialization:** no global locks; per-key grain only; legacy engine never stopped.
9. **Settings→idle-linger path (`AppContainer.kt:111–122, 461–476`):** tuning fans out per runtime in the registry (a stopped-but-registered runtime must not lose settings).
10. **Wedge budget constants (`MIN_HEALTHY_RUN_MS=60_000`, `MAX_WEDGE_RESTARTS=5`)** are load-bearing; reuse, never duplicate.

**Test matrix:**

| § | Unit (pure JVM) | Robolectric/JVM | Device acceptance (1 each) |
|---|---|---|---|
| §1 Registry | idempotency per key; never-evict/only-stop; budget predicate (≤2 non-legacy); stop-before-start ordering | stub-binary `CrushEngine` (sleep-script via injected `EnginePaths.binary`), race `stop()` vs in-flight `start()` N× on a real JVM process — zero surviving children, single Running/Stopped terminal | 349bad3 rebind protocol (A→B→A×3 + chat): `ps` shows ≤2 children at every instant; each stop line paired with child destruction; no `$key stop() requested` without its `stopping project engine` line |
| §2 Wedge recovery | watchdog predicate math: 90s + probe-true ⇢ 1 restart; second episode ⇢ `Failed(willRetry=false)` (extend `SupervisorHysteresisTest`) | fake-engine supervisor (start-forever + probe seam): exactly one restart then terminal Failed; a healthy 58s boot is NEVER false-restarted | doze/overnight drive: recover by instrumented lines — `Running after` ≤90 s or honest failure, never a >10-min pin; the 55–60s spawn-gap now classifies wall-vs-monotonic |
| §3 Reclaim | supersede-by-epoch; revoke-deferred-while-run-in-flight; revoke-only-if-own | extend `CrushConnectionSocketIdentityTest`: revoked conn unwinds, ONE log line, no ladder restarts after supersession | Projects↔chat round trip: exactly one reader per bound key; zero-attributable stale-entry retry lines; legacy `""` superseded cleanly |

**Corner flagged:** `forgetProjectEngine` currently builds a throwaway
runtime just to stop a background key (`AppContainer.kt:387–400`) — the
registry makes that a lookup-or-noop. The VM's removal-refusal-while-
run-in-flight rule (decisions-open item 9) keeps visiting the registry
through the same flag, and the guard order (refuse → stop → drop row →
delete trees) must be preserved in `ProjectsViewModel.removeProject.`
F08 access-state rechecks at project-open stay at the Projects surface, as
the S5 drive landed them.
