# Crush Mobile V2 — Foundation Hardening Final Report (2026-11-28)

## 1. Baseline and scope

- Repository `sadi21863-bit/crush-android-client`, branch `v2-16-planning-baseline`.
- Starting HEAD and reviewed checkpoint: `edac95d0458c684884a7e55e805ea7e5e7017da8`.
- Remote branch matched at start; `main` = `origin/main` = `adc26c0…` and was not touched.
- Worktree at start: clean except the pre-existing untracked `v2-66-phaseF-stuck.png` (preserved).
- Toolchain: Gradle 9.8.0, AGP 9.4.1, Kotlin plugin 2.4.10, compileSdk 37 / targetSdk 36 / minSdk 29, JBR 25. Engine of record v0.100.0 (`libcrush.so` 99,418,406 bytes, SHA-256 `326206A5…7B78E`, AArch64 PIE).
- Five parallel read-only audits covered all workstreams. The complete inventory is in `FOUNDATION-HARDENING-TRACEABILITY.md`; stale-doc corrections are in `FOUNDATION-HARDENING-DOC-ADDENDA.md`.

## 2. Workstreams audited and defects confirmed

| Workstream | Confirmed in-scope defects |
|---|---|
| A/B engine + isolation | run-in-flight marker cleared with mutable key after switch (HIGH); reverse-order connection attach; exec-failure proxy leak; proxy socket leak during CONNECT; lossy instance-key collision no-op |
| C/D execution + clocks | `runCatching` swallowed cancellation; `AlreadyApplied` never reconciled controller; controller ignored explicit executionId; declaration replacement unsynchronised; superseded events reached controller; Stale/Conflict/IntegrityFailure collapsed; buffer non-head removal, unsynchronised clear, one-shot replay snapshot; per-turn failure handled only `Applied`; live bubble showed cancellation as success; epoch bump read-modify-write; controller records never pruned |
| E networking | send loop had no `attempt++` (unbounded if policy flag flips); OkHttp transport-level replay of POST on connection failure |
| F security | logcat path passed original throwable; crash archive and on-screen diagnostics stored/rendering unmasked stack traces |
| G/H/I persistence + UI | relative-time labels frozen at composition; stale evidence/doc claims |
| J/K binary + CI + release | no local re-derivation of extracted binary hash; engine-less CI with no branch run; Track B0–B6 not performed; R35–R40 proposal-only; stale README/plan/report statements |

Accepted V2-0…V2-9, V2-12…V2-15 and A1 contracts were preserved. No accepted test or security control was weakened.

## 3. Corrections implemented

- **Execution (`ExecutionController`, `ExecutionStore`, `ExecutionDao`, `ExecutionEventAdapter`, `ConfirmedEventBuffer`, `AppContainer`)**: cancellation now propagates via `runCatchingNonCancellation`; controller resolves by explicit `executionId` and refuses superseded events; `AlreadyApplied` reconciles the in-memory terminal view; declaration replacement/retirement serialized and bounded; typed replay reasons distinguished; buffer head-only removal with synchronized clear/tombstone and re-peek loop; atomic epoch bump; bounded terminal-record pruning (128) that never evicts active runs.
- **Engine/isolation**: `RunHandle` freezes `instanceKey` and cleanup (marker, supervisor `workActive`, cancel API socket) uses that frozen key; `ConnectionRegistry.attach` refuses an older-epoch late attach; exec-failure path stops the egress bridge; proxy sockets registered at creation so `stop()` closes mid-handshake sockets; same-key/different-project-id treated as a real switch.
- **Networking**: POST loop bounded by `for (attempt in 0 until 2)`; `retryOnConnectionFailure(false)` on the REST/POST client only.
- **Security**: logcat and crash-archive/on-screen paths now mask throwable text through `AppLog.Masking` / `AppLog.w`.
- **UI**: relative-time labels use a bounded 60-second ticking clock; live bubble marks `rc.cancelled` as `Cancelled`.
- **Docs**: dated addenda for binary hash, CI, Track B, stale counts, plan approval, A2 self-description, D7/emulator, measurement contradictions, chaos harness, attack-surface model, key normalization, Keystore gating.

## 4. Test evidence

Commands (exact):
1. `gradlew.bat :app:testDebugUnitTest --tests 'com.crush.mobile.execution.*' --tests 'com.crush.mobile.engine.*' --tests 'com.crush.mobile.data.crush.*' --tests 'com.crush.mobile.ui.screens.chat.*' --tests 'com.crush.mobile.di.*'` — `BUILD SUCCESSFUL in 35m 26s`; 81 suites / 712 tests / 0 failures.
2. `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease` — `BUILD SUCCESSFUL in 31m 12s`; 95 actionable tasks; **128 suites / 1181 tests / 0 failures / 0 errors / 0 skipped**; `lintRelease` completed with 0 issues in `app/build/reports/lint-results-release.xml`.

Archived: `docs/v2/fh-fullgate-run.txt` and six `docs/v2/fh-*-junit.xml` (68 tests). The archived subset does not independently reconstruct 1181; the full XML set was not duplicated. Test count is unchanged from the reviewed checkpoint — **no new dedicated regression tests were added for the new guards**, and no mutation testing was performed. This is a material evidence gap.

## 5. Gates

- **A architecture correctness**: PARTIAL — corrected ownership/isolation defects; prompt identity (A3/D3/X1), atomic attach and D4 clock policy remain open.
- **B persistence/crash consistency**: PARTIAL — existing migration/send/terminal tests green; no new crash-boundary tests for the new guards.
- **C security**: PARTIAL — synthetic-secret scanner clean; redaction fixes implemented but not covered by new tests; device/Keystore run not repeated.
- **D regression**: PASS at gate level (1181 green, lint clean); FAIL for mutation evidence (not performed).
- **E Android device validation**: NOT-PERFORMED — only API 29 device exists; API 30+/tablet/RTL unavailable.
- **F CI/release readiness**: NOT MET — engine-less CI, no branch workflow run, Track B0–B6 open, Gate 7 unauthorized.
- **G documentation integrity**: PARTIAL — dated addenda added; traceability register complete but evidence classes still distinguish source/JVM only.

## 6. Owner decision docket (consolidated)

1. **D1 background execution** — FGS strategy; no legitimate sustainable option established for autonomous coding. No implementation authorized.
2. **D2 concurrency envelope** — keep two non-legacy engines vs change; evidence supports current cap.
3. **D3/X1 prompt identity** — open; `prompt_ref` remains NULL; no prompt durability implementation authorized.
4. **D4 long-run contract** — per-tool-class waits, total ceiling, snapshot clock separation; open.
5. **D5/X4 storage & distribution** — AFA decision recorded 2026-11-14; Play policy answer unresolved.
6. **D7 execution lifecycle** — accepted V2-A20; no new decision needed.
7. **Device/API matrix (B0/B1)** — hardware/farm acquisition required; NOT-PERFORMED.
8. **R37 debug fault bridge** — proposed, not authorized; device fault injection remains blocked.
9. **R26 transcript cap / 10-hour profile** — cap vs measurement; owner decision.
10. **A2 slice 1 acceptance** — implementation checkpoint only; not accepted.

## 7. Remaining limitations

No device verification, no instrumented run, no CI run, no engine-inclusive CI, no R37 fault injection, no atomic validate-and-attach, no process-death pre-ACK durability, no A3/C4–C6, no mutation evidence, no new regression tests for the guards added in this programme, no performance re-measurement, no LH-1 capability claim. Gate 7 remains deferred by owner.

## 8. Worktree and recommendation

Branch `v2-16-planning-baseline`, HEAD still `edac95d…`; remote unchanged. Worktree contains the source/test changes, the two registers, the addenda, the final report and archived artifacts, plus the untouched pre-existing `v2-66-phaseF-stuck.png`. No commit, push, PR or merge was performed.

**Overall recommendation: NOT READY — BLOCKERS REMAIN.** The confirmed in-scope defects are corrected and the full JVM/assemble/lint gate is green, but the owner docket above, missing new regression/mutation evidence, and all device/CI gaps prevent a foundation-hardening acceptance claim.
