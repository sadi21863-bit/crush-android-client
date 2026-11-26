# V2-16 Checkpoint 2 — proposed risk-register amendments

Date: 2026-11-26. **Proposal only.** The approved risk register (Revision 3)
and the approved plan (Revision 3.1) are frozen and have not been edited.
Apply these only if the owner approves a new revision.

Sources: `V2-16-BACKGROUND-MEASUREMENT.md`,
`V2-16-CLOCK-MEASUREMENT.md`, `V2-16-CONCURRENCY-MEASUREMENT.md`,
`V2-16-SECURITY-INCIDENT-2026-11-26.md`.

## 1. Amendments to existing rows (text proposals)

| ID | Current meaning | Proposed amendment |
|---|---|---|
| **R17** | background behaviour “not guaranteed” | Replace with measured API 29 facts: HOME, screen-off (49 s window) and forced Doze (charging) did **not** kill app or engine and the turn completed; process/package death and OEM `GarbageClean` did. Still open for API 30+. |
| **R18** | silent phase risk | Add: bash tools emit no per-line frames; the only app-visible progress is `agentInfo().is_busy`. Silence of 90–240 s is normal for a long shell. |
| **R31** | send budget / snapshot | Add: caps (400 files, 4 MB, 256 KB/file) and the measured device I/O proxy (0.10 s for 400 × 8 KB). Snapshot is not a 30 s send-timeout risk in the measured tree; direct Kotlin timing on device still open. |
| **R11** | PSS/threads unknown | Add the P0-E4 numbers: idle engine 15–24 MB PSS, bootstrapped 50–80 MB, busy 50–66 MB; app 50–79 MB; 3-engine tree ≈ 209 MB, MemAvailable ≈ 0.94 GB. |
| **R22** | concurrency | Add: the 2/2 gate binds only when both non-legacy slots hold live children; ordinary project taps stop the outgoing engine unless a run is in flight. |
| **D1** | background strategy open | Keep **open**. Checkpoint 2 supplies API 29 evidence; API 30+/FGS policy evidence and the owner decision are still required. Do not record a decision. |
| **D2** | concurrency envelope open | Keep **open**. Memory evidence supports the existing 2-engine budget, but the owner must decide whether to accept it. |
| **D4** | long-run contract open | Keep **open**. The 240 s budget was measured cancelling a healthy 260 s bash 19 s early; a per-class policy is evidence-backed, but no clock was changed. |

## 2. Proposed new rows

| ID | Class | Sev | Finding | Track | Proposed status |
|---|---|---|---|---|---|
| **R35** | Risk | High | Engine `/v1/workspaces` returns provider API keys in plaintext; a diagnostic that dumps a workspace object exposed one into the transcript. Select fields explicitly; never log/export config. (Already proposed in the security incident report.) | B (B-G5) + A (diagnostics) | open |
| **R36** | Risk | High | A project switch cancels an in-flight turn (`user cancelled assistant tool calling`) even though the liveness guard keeps that project's engine alive. Engine survival is not execution survival; “running with no observer” is not currently achievable by switching projects. | A (A1/A7) + D7 | open |
| **R37** | Risk | Medium | The documented in-app fault injection is unreachable on the current build: `SpikeActivity` is `exported=false`, shell `am start` gets `Permission Denial`, and `tools/chaos.ps1` swallows the error. A green `S01` cannot be trusted to prove engine-pid recovery. | B (test harness) | open |
| **R38** | Risk | Medium | The 2/2 engine-budget refusal is named in the log but not in the UI; the user sees only “Sessions aren't available”. An honest, named refusal surface is required. | A (A7) | open |
| **R39** | Risk | Medium | This ROM's OEM process manager killed the app with reason `GarbageClean` (adj 805) after the MIUI cleaner interaction; `OneKeyClean` spared the app. Neither is LMK; B8 remains not-performed. | A (A5) | open |
| **R40** | Risk | Low | After app-process death the engine writes an explicit interrupted-tool result (“did not produce a result, you may retry”) and the UI shows Interrupted/Retry. This is good, but it is not automatic recovery and must not be reported as exactly-once or resumed execution. | A (A1) | open |

## 3. Evidence status to carry forward

| Item | Status |
|---|---|
| P0-E1 lost-ack recovery | strongly supported, **not** fault-injected; exactly-once **not established** |
| P0-E1 engine survival after app death | **not producible** on this ROM |
| P0-E1 idle suppression | **not-performed** |
| P0-E2 B1 | passed with caveat (49 s spontaneous wake) |
| P0-E2 B2/B3/B4/B6/B7 | passed, five fields each |
| P0-E2 B5 | **not-performed** (fault harness unreachable) |
| P0-E2 B8 | **not-performed** (no LMK; trim is not LMK) |
| P0-E3 | tool-class matrix + 240 s number + snapshot proxy; no code change |
| P0-E4 | PSS/threads + third-start refusal observed; D2 still open |
| Security incident | key replaced by owner at 10:02; old-key invalidation owner-attested, not independently verified by the agent |
