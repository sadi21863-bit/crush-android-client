# Perf baseline capture (2026-11-23 10:56)

Device: serial        : bbf8ca69; captured      : 2026-11-23T10:55:25.1509661+05:30; label         : v2-11-baseline-unlocked; android       : 10; sdk           : 29; model         : Redmi Note 7 Pro; abi           : arm64-v8a; build         : QKQ1.190915.002

## Cold start (force-stop before every run)

| run | status | TotalTime ms | WaitTime ms | engine boot ms |
|---|---|---|---|---|
| 1 | ok | 543 | 545 | 2353 |
| 2 | ok | 517 | 518 | 2391 |
| 3 | ok | 469 | 471 | 2637 |

Run 2 logged two boots: legacy `[]` at 2391 ms, project `[pd871eee8]` at
2477 ms. The script records the first matching line; the project series is
2353 / 2477 / 2637 ms (see MANIFEST.md).

## Frames since process start (dumpsys gfxinfo)

- total frames rendered: 14
- janky frames: 14
- 50th / 90th / 95th / 99th percentile: 81 / 300 / 300 / 300 ms

## Memory

- TOTAL PSS: 110133 kB

## What this does NOT claim

- No frame budget or regression threshold: this is a baseline capture, not a
  performance proof. Compare against another capture from the SAME device,
  same settings, same app version class.
- The lock gate and prewarm setting were not driven by this script; they are
  whatever the device had at capture time.
- gfxinfo accumulates from process start, so it covers launch + idle, not a
  scripted scroll.

Raw evidence: `device.txt`, `package.txt`, `start-1..3.txt`,
`engine-boot-1..3.txt`, `gfxinfo.txt`, `meminfo.txt`, `starts.csv`.
