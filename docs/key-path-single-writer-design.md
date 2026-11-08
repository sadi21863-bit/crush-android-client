# `crush.json` single-writer design (decision item 4)

Designed 2026-10-31 per the adopted direction: **one writer, one file** — the
engine becomes the sole writer of `crush.json` at run time, and the app's key
injection survives as a *pre-launch* step only. Chosen before any code, as
decided in `decisions-open.md` item 4.

## 1. The defect, precisely

`EngineConfigInjector` rewrites `crush.json` (engine home config) while the
engine process may be alive. The engine also writes that file for its own
reasons (config normalization, provider mutations driven by its own API path).
Two independent writers, one file, no compare-and-swap: either side's write
can erase the other's (lost update). Today's manifestation: the app injects a
provider key, the engine then persists its own normalized config, and the
injected key field can regress / vanish depending on write ordering.
Engine-side write path: `EngineConfigInjector.kt:134–167` documents the
app-side half; the engine's half is upstream behavior, not ours.

## 2. Invariants the design must hold

- **W1 (single writer at rest)** — after engine start, exactly one process
  owns writes to `crush.json`.
- **W2 (secrets leaves the file eventually)** — the key's plaintext residency
  in `crush.json` (decision item 6, accepted trade) gets *narrower*, not
  wider: fewer write windows where a plaintext key is in flight.
- **W3 (provider switch stays safe for runs)** — nothing here may weaken the
  P1-07 guarantee: switching provider never tears down a running turn's
  floor.
- **W4 (crash-safe)** — a killed engine or app mid-write must not leave a
  truncated config (existing injector already writes-then-rename; keep that).

## 3. Chosen mechanism: restart-boundary injection (A), + upstream ask (B)

### A. App writes ONLY before engine start (the implementable half)

1. `EngineConfigInjector` gains a mode contract: it refuses to write when the
   engine is live (supervisor exposes `isAlive`; the injector takes the
   supervisor as a constructor injection — removing the current
   self-deciding behavior).
2. Late key changes (Settings save, provider switch) do NOT touch
   `crush.json` at run time. Instead they go through
   `EngineSupervisor.restart(reason)` with a reason string; restart follows
   the injector path (write file → launch engine). The engine's own config
   writes happen live, but the app no longer races them because the app's
   write window is only "engine not running".
3. Provider switch replaces the restart's session-restore semantics with the
   already-shipped P1-07 switch policy (the turn-completion deferral stands;
   the supervisor restart queues until the policy allows).
   **Cost measured honestly:** a provider switch now costs a 2–6 s engine
   restart instead of being instantaneous once a key already exists. That is
   the accepted trade; the alternative (live key hand-off) does not exist
   upstream.
4. Failure mode preserved: if the restart is refused for any reason, the
   settings screen surfaces the refusal verbatim (policy already in place
   for reconnect failures).

Exit condition for A being *enough*: after it ships, `crush.json` write
conflicts require a bug to be possible, because the only concurrent writer
left is the engine itself and its write window is guarded by W4.

### B. Upstream feature ask (the long pole, filed after A ships)

The *clean* end-state needs an upstream capability: runtime key/provider
injection over the socket API (e.g. `POST /v1/providers/{id}/key` or a
config-patch endpoint), so no app restart is needed for key changes and the
file's plaintext lifetime shrinks to launch-time only. This is filed as an
upstream issue referencing #4092 (same UDS-embedder framing). Not a growth
area for this repo until upstream answers — see the retained remainder:
even with B, W2's "whenever the file does contain a key" wording stays,
because the engine persists whatever it is handed.

## 4. What is explicitly NOT in scope

- A keyring persistence redesign (decision 7 territory).
- Encrypting `crush.json` itself (upstream owns that format; permission to
  change the format is the upstream ask, same as B).
- The `lastEffective`-era tuning fields: already migrated to
  `tuningRevision`/`absorbedRevision` (`4cd9c19`); the injector skips them.

## 5. Test shapes pinned by the implementation

- `InjectorRefusesWhileAliveTest` — supervisor-alive ⇒ injector throws an
  injected `ConfigWriteRefused`; the DTO carries the intended write payload
  so the restart path re-uses it verbatim.
- `ProviderSwitchRestartsEngineTest` — supervisor restart invoked exactly
  once per switch; the second call coalesces (policy-guarded).
- `ConfigWriteCrashSafetyTest` — the injector's write-rename order keeps a
  torn write from ever being the file the engine reads (existing behavior,
  now pinned).

---

**Implementation status 2026-11-08.** Half A shipped in `5b1b222`, in a
shape §3.A permits but does not name literally: instead of a typed
`ConfigWriteRefused` thrown by a supervisor-injected injector, the injector
takes an explicit `engineRunning` window parameter — an out-of-window
request logs and writes NOTHING (the next pre-exec call applies it) — and
`CrushEngine.start()` pins every injector call inside the pre-spawn region
(`data/crush/EngineConfigInjector.kt:132–149`). The `restart(reason)` seam
exists (`engine/EngineSupervisor.kt:185`). And the key story landed at the
end-state §3.B wanted, without an upstream round-trip: the app never writes
the provider key to `crush.json` at all — keys ride the socket at handshake
(`api.setProviderKey` inside `CrushSession.runChain`,
`data/crush/CrushSession.kt:153`) — so the app-side key writer §1 pinned
(`EngineConfigInjector`, its write-path window
:134–167 as of 2026-10-31; the file has always lived at
`data/crush/EngineConfigInjector.kt`) is gone. The only engine-home writes left in the
app are model-catalogue merges and the chat tool policy, both pre-exec.
W2's residual residency stands where the design says it will: the engine
still persists the runtime key it is handed into `crush.json` (upstream
behavior; the §3.B upstream ask remains the recorded long pole). The §5
test names above are the design's, not the tree's — the shipped contract is
pinned in `EngineConfigInjectorTest` (sibling-field preservation incl. the
key, idempotency, half-parsable-file-don't-truncate, plus the
`engineRunning` refusal window) and the spawn-window ordering by the
`CrushEngine`/supervisor suites; no test named
`InjectorRefusesWhileAliveTest`, `ProviderSwitchRestartsEngineTest` or
`ConfigWriteCrashSafetyTest` exists (provider switch coalescing lives in
`ProviderSwitchPolicy` + `ProviderSwitchPolicyTest`).
