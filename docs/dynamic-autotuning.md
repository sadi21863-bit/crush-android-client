# Dynamic auto-tuning

Thermal, memory and power-aware reduction of the engine's idle linger.

> **Status: implemented and wired into `EngineSupervisor`.** An earlier revision
> of this document described the dynamic half as "deliberately not built yet"
> while `DynamicPolicy` was already written, unit-tested, and never called. That
> gap — 20 tests guarding dead code — is now closed.

## The problem

A throttling phone is a slow phone. Crush holding a workspace open costs CPU and
battery, and a thermally constrained device has less headroom precisely when the
user is doing something demanding.

The engine's idle timeout is the main dial: how long Crush lingers after the last
turn before exiting.

## Policy

| Signal | Source | Meaning |
|---|---|---|
| Thermal status | `PowerManager.getCurrentThermalStatus()` | device is throttling |
| Low memory | `ActivityManager.MemoryInfo.lowMemory` | system under pressure |
| Power saver | `PowerManager.isPowerSaveMode` | user asked to save battery |

`RuntimeSignals.sample()` reads all three. `DynamicPolicy.pressureOf()` collapses
them into one level, worst-wins:

```
CRITICAL  thermal >= SEVERE  ||  lowMemory
ELEVATED  thermal >= MODERATE ||  powerSaver
OK        otherwise
```

`DynamicPolicy.next()` applies hysteresis: escalation is immediate, relaxation
requires `calmRun >= 3` consecutive calm samples. This prevents flapping when a
device sits on the threshold.

`DynamicPolicy.applySafely()` maps a level onto concrete timeouts, and refuses to
act while `workActive` is true — shedding the idle linger mid-stream is how a
reply silently vanishes.

## The bug that made it dangerous

`EngineSupervisor` captured the resolved policy in `baseTuning` and then assigned
the **pressure-adjusted result back into that same variable**:

```kotlin
baseTuning = DynamicPolicy.applySafely(baseTuning, signals, effective)
tuning = baseTuning
```

The comment above it already claimed this was captured "so a pressure change can
be recomputed from the SAME base rather than compounding on top of an
already-shrunk value". The code did the opposite. Consequences:

- Repeated `CRITICAL` samples divided the idle timeout repeatedly, driving it to
  the floor where it could not recover even once the device cooled.
- A later settings change was compared against — and overwritten by — that stale
  shrunk value, so a user override could be silently discarded.

The fix separates the two states. `desiredBase` holds the unadjusted
user/auto-resolved policy and is only written when settings change;
`effectiveTuningFor()` derives the applied value from it on every poll and never
writes the result back. `lastEffective` distinguishes "settings changed" from
"we adjusted it ourselves".

## Known integration defect

`DynamicPolicy.next()` checks `calmRun + 1 >= 3`, but the caller increments
`calmRun` **before** calling it, so the already-incremented count is passed in.
The caller therefore relaxes after **two** observed calm samples rather than
three.

The pure function's 20 unit tests all pass — they test `next()` directly with a
pre-incremented count, so they encode the same off-by-one the caller has. Fixing
it needs the contract defined once ("calmRun before or including this sample?")
plus an integration test over a signal sequence. Not yet done.

## Device verification

Thermal throttling cannot be induced on demand from adb. What *is* observable:

- `dynamic tuning: pressure=… idle=…s` appears in logcat on every transition.
- Under load, the engine's memory and CPU behaviour can be compared via
  `dumpsys`.
- The `CRITICAL`-then-recovery path is exercised by S07 in `tools/chaos.ps1` only
  partially — the script reads meminfo but does not induce pressure.

## Files

| File | Role |
|---|---|
| `domain/model/DynamicPolicy.kt` | Pure policy: signals → pressure → tuning |
| `domain/model/TuningPolicy.kt` | Resolution, clamping, and the USER > AUTO > default precedence |
| `engine/EngineSupervisor.kt` | Samples signals on its health poll and applies the result |
| `tools/chaos.ps1` | S07 memory-pressure scenario (read-only) |
