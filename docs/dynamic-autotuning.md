# Dynamic Auto-Tuning — Design (NOT YET IMPLEMENTED)

Static auto-tuning is **shipped and working** (`DeviceProfile` + `TuningPolicy`).
This document covers the *dynamic* half, which is deliberately **not built yet**.

## Why it is separate

| | Static (shipped) | Dynamic (this doc) |
|---|---|---|
| Sampled | Once, at first launch | Continuously, while running |
| Examples | API level, total RAM, cores, StrongBox | `MemAvailable`, thermal status, foreground state, active SSE stream |
| Thread | Main thread is fine | Must be sampled **off** the main thread |
| Changes | Effectively never | Every few seconds |
| Failure mode | Wrong choice for a rare device | A bad sample causes a wrong decision *mid-session* |

Keeping them apart matters. A static value that is merely stale is harmless; a
dynamic control loop that reacts to a single bad sample can kill the engine
mid-conversation. That is exactly the failure we already shipped once (see
"Lessons" below).

## Signals worth sampling

| Signal | Source | Use |
|---|---|---|
| `MemAvailable` | `ActivityManager.MemoryInfo` | Shed engine below ~250MB |
| `isLowRamDevice` | `ActivityManager` | Static, but re-check on `onTrimMemory` |
| Thermal status | `PowerManager.getCurrentThermalStatus()` | Back off or disable prewarm at `>= THERMAL_STATUS_SEVERE` |
| Battery saver | `PowerManager.isPowerSaveMode` | Shorten idle timeout, drop prewarm |
| Foreground/background | `ProcessLifecycleOwner` | Stop the engine when backgrounded for > N min |
| Active stream | `EngineSupervisor.workActive` | Never shed or restart while streaming |
| `onTrimMemory` | `ComponentCallbacks2` | Aggressive shed at `TRIM_MEMORY_RUNNING_CRITICAL` |

## Proposed policy

```
if (workActive)                 -> do nothing, ever
if (thermalStatus >= SEVERE)    -> stop prewarm, shorten idle to 60s
if (powerSaveMode)              -> shorten idle to 120s
if (memAvailable < 250MB)       -> stop engine, remember to restart on foreground
if (backgrounded > 5 min)       -> stop engine
on foreground                   -> restart engine if stopped, restore prior tuning
```

## Hard rules

1. **Never restart while `workActive`.** A restart mid-stream drops the SSE
   connection and invalidates the workspace id the UI is holding.
2. **Hysteresis is mandatory.** Any threshold needs a separate, wider threshold
   for the reverse transition, or the engine oscillates on/off forever.
3. **Rate-limit transitions.** No more than one shed/start per 60s, or a
   flapping signal becomes a restart storm.
4. **Clamp every value** into the same ranges `TuningPolicy` already applies, so
   a bad sample cannot produce a 0s timeout or an infinite one.
5. **User overrides always win**, exactly as in static tuning. If the user set
   the idle timeout manually, dynamic tuning may *recommend* a change but must
   not silently apply it.

## Why it is worth doing

Measured on the reference device (Redmi Note 7 Pro, API 29):

- Total RAM **3.7GB**, but `MemAvailable` hovers around **0.7–1.0GB**
- Engine RSS **~59MB** resident
- Crush's own idle shutdown is **60s** (overridable via
  `CRUSH_SERVER_IDLE_TIMEOUT`)

So the engine is ~6–8% of *available* memory on an already-constrained device.
On a 2GB phone that fraction is large enough to cause real OOM pressure, which
is what the static `ENTRY` class already compensates for with a 5-minute idle
timeout. Dynamic tuning is what protects the user who leaves the app open
alongside a game, a video call, or a camera.

## Lessons already learned (do not regress these)

1. **Process liveness is not health.** Crush holds an SSE connection open, so a
   busy engine is slow to answer `/v1/version`. Treating a failed HTTP probe as
   death tore down healthy engines mid-session.
2. **`workActive` gating is load-bearing.** It shipped as dead code once — the
   hang detector fired against a *healthy busy* engine because nothing ever set
   the flag. Any future signal must be wired to a real caller, and a chaos test
   must assert it.
3. **One supervisor per process.** Two polling loops raced and produced three
   simultaneous restarts within one millisecond. The supervisor is a
   process-wide singleton in `AppContainer`.
4. **A green test suite can hide real bugs.** The first full chaos run reported
   10/10 PASS while three defects were live. Assert on *behaviour*
   (restart counts, duplicate log lines), not on "app did not crash".

## Test plan before shipping dynamic tuning

The existing harness (`tools/chaos.ps1`) is the right place. Required new cases:

- **S11 `MemoryThrash`** — repeatedly drop `MemAvailable` (real allocation
  pressure, not a fake value) and assert the engine is shed exactly once and
  restored once, with no oscillation.
- **S12 `ThermalSevere`** — signal thermal status and assert prewarm disables.
- **S13 `TrimMemoryCritical`** — fire `onTrimMemory` mid-stream and assert the
  active stream is *not* dropped.
- **S14 `FlappingSignal`** — toggle the trigger source rapidly for 2 minutes and
  assert the supervisor restart count stays at 1.
- **S15 `UserOverrideWins`** — set a manual idle timeout, then drive the dynamic
  path hard, and assert the manual value survives.
