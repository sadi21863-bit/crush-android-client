# MANIFEST — perf baseline capture (2026-11-23)

Carry-over row: **V2-4 performance proof** (V2-0 sign-off §3). This set does not
prove a performance target; it records the first repeatable numbers so a later
capture on the same device can be compared.

## Device / build

| Field | Value |
|---|---|
| serial | `bbf8ca69` |
| model | Redmi Note 7 Pro |
| Android | 10 (API 29), arm64-v8a |
| build | `QKQ1.190915.002` |
| app | `com.crush.mobile` 1.0.0, versionCode 1, minSdk 29, targetSdk 36 |
| capture | 2026-11-23 10:55 IST, label `v2-11-baseline-unlocked` |
| script | `tools/perf-baseline.ps1 -Serial bbf8ca69 -Runs 3 -Label "v2-11-baseline-unlocked"` |

## Method

For each run: dismiss any system credential screen (the app is auth-gated, and
the Settings credential activity otherwise stays on top and `am start` reports
`TotalTime 0`), `am force-stop com.crush.mobile`, wait 3 s, clear logcat,
`am start -W -n com.crush.mobile/.MainActivity`, wait 6 s, read the
`EngineSupervisor` lines `engine Running after <n>ms`.

Run 2 logged two boots, 300 ms apart: the legacy key `[]` at 2391 ms and the
project key `[pd871eee8]` at 2477 ms. `starts.csv` keeps the script's first
matching value (2391). The table below uses the **project** series for
comparability; runs 1 and 3 logged only the project line.

## Cold start

| run | status | LaunchState | TotalTime ms | WaitTime ms | project boot ms |
|---|---|---|---|---|---|
| 1 | ok | COLD | 543 | 545 | 2353 |
| 2 | ok | COLD | 517 | 518 | 2477 (legacy 2391) |
| 3 | ok | COLD | 469 | 471 | 2637 |

## Steady state after the last launch

- `dumpsys gfxinfo com.crush.mobile`: **14 frames rendered**, **14 janky**,
  50th/90th/95th/99th = 81 / 300 / 300 / 300 ms.
- `dumpsys meminfo com.crush.mobile`: **TOTAL PSS 110,133 kB** for the app
  process. The engine is a separate child process and is not included.

## What this set supports

- Cold activity start is stable across the three runs (469–543 ms).
- The engine reaches Running in 2.35–2.64 s on this device/build, measured from
  the supervisor log, after the activity is already interactive.

## What it does NOT support

- **No frame budget or regression threshold.** The gfxinfo sample is 14 frames
  from launch + idle; it is not a scroll or a scripted interaction, and it is
  too small to characterize frame pacing. 14/14 janky on a launch is a sample,
  not a verdict.
- The lock gate, prewarm setting and provider/model selection were not driven by
  the script; they are whatever the device had. The phone was unlocked by the
  owner for this capture.
- Single device and API level. API 30+, tablet/wide and low-RAM classes are NOT
  PERFORMED (no hypervisor locally; the only AVD is arm64).
- `TotalTime` is activity launch only; it does not include engine readiness or
  a provider round-trip.

## Validity note

The first script revision passed an empty argument list to adb (a function
parameter named `$Args` shadowed PowerShell's automatic `$args`), so it captured
adb help text and was discarded. The fixed revision is what produced this set;
`starts.csv` and the raw files are untouched outputs of that run.

## Evidence

`device.txt`, `package.txt`, `start-1..3.txt`, `engine-boot-1..3.txt`,
`gfxinfo.txt`, `meminfo.txt`, `starts.csv`, `RESULTS.md`. No credential value is
present in any file.
