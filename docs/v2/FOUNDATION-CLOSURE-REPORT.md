# Foundation hardening — focused closure report (2026-11-28)

Baseline reviewed: `31715d0aa84180be9f54b38ddd3743257eb96893`. Scope: the three
findings from the remote review plus deterministic regression coverage for the
hardening diff. No A3/C4–C6/D3/X1/R37/device/CI work was added.

## 1. Finding disposition

| Finding | Status | Fix | Test |
|---|---|---|---|
| Event buffer lifecycle race (add vs clear/remove) | FIXED-SOURCE + JVM-TESTED | `ConfirmedEventBuffer` now uses ONE per-buffer monitor for tombstone checks, insertion, head removal, peek, clear and pending enumeration; no detached-list lifecycle | `FoundationClosureTest.add racing clear cannot resurrect evidence after invalidation`; `removal is head only and preserves later evidence` |
| Explicit execution ID falls back to run ID | FIXED-SOURCE + JVM-TESTED | `ExecutionController.onConfirmedEvent` rejects an unresolvable explicit ID; run-ID fallback only when no ID is supplied AND the match is unambiguous | `explicit execution id that does not resolve is rejected not run-id fallback` |
| Deferred replay has no reliable retry trigger | FIXED-SOURCE + JVM-TESTED (store contract) | `AppContainer.scheduleReplay` bounded delayed retries (3 × 2 s), re-drive from startup reconciliation, explicit invalidation on exhaustion; evidence never drained before apply | `unavailable persistence keeps evidence pending and applies once on retry`; container scheduler itself remains source-verified only (no injection seam) |

## 2. Implementation changes

- `execution/ConfirmedEventBuffer.kt` — single atomic lifecycle monitor, tombstone, head-only removal, `pendingKeys()`.
- `execution/ExecutionController.kt` — fail-closed identity resolution.
- `di/AppContainer.kt` — scheduled bounded replay, startup re-drive, explicit invalidation reasons (`replay_stale`, `replay_conflict`, `replay_integrity_failure`).
- `app/src/test/.../execution/FoundationClosureTest.kt` — in-memory `ExecutionDao` fake for the unavailable-then-success contract, latch-controlled buffer races, identity mismatch.
- `app/src/test/.../data/crush/ConnectionRegistryTest.kt` — older-epoch attach rejection.
- `app/src/test/.../diagnostics/DiagnosticsRecorderRedactionTest.kt` — synthetic secret absent from crash archive.
- `docs/v2/FOUNDATION-HARDENING-FINAL-REPORT.md` — corrected repository-state claim (commits `b647108`, `31715d0`).

## 3. Persistence guarantees

Event writes still require exact `(execution_id, run_id, engine_generation)` with `ACKED` and a live phase. No DAO guard was weakened. `>1` affected rows remains `IntegrityFailure`. A failed persistence attempt returns `Unavailable` (or propagates `CancellationException`) and leaves the buffered event pending. Replay removes only the head that was successfully classified.

## 4. Race coverage

| Test | Interleaving proved |
|---|---|
| add racing clear | 4 threads add while one clears; no evidence survives, later adds tombstoned |
| head-only removal | removing a non-head event leaves the queue intact and ordered |
| explicit ID mismatch | an unknown explicit ID cannot mutate a matching run ID; ambiguous implicit run ID is rejected |
| unavailable then success | first attempt `Unavailable`, evidence retained, second attempt applies once |
| older-epoch attach | epoch-1 attach after epoch-2 is stopped and does not supersede |
| synthetic-key redaction | exception text never persisted unmasked in the crash archive |

No sleeps are used as concurrency proof; latches and controlled fakes only.

## 5. Test results

- Targeted: `gradlew.bat :app:testDebugUnitTest --tests 'com.crush.mobile.execution.*' --tests 'com.crush.mobile.data.crush.ConnectionRegistryTest' --tests 'com.crush.mobile.ui.screens.diagnostics.*'` — `BUILD SUCCESSFUL in 23m 43s`; 13 suites / 116 tests / 0 failures.
- Full gate: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease` — `BUILD SUCCESSFUL in 40m 15s`; 95 tasks; **130 suites / 1187 tests / 0 failures / 0 errors / 0 skipped**; lint 0 issues. Baseline was 128/1181 (+2 suites, +6 tests).
- A stalled first targeted run was terminated and its KSP caches cleared; the clean rerun produced the result above.

## 6. Migration details

None. No schema or Room version change in this closure pass.

## 7. Remaining limitations

The container's scheduled replay is source-verified, not integration-tested (no injection seam). No device or instrumented evidence, no R37 fault injection, no engine-inclusive CI, no branch workflow run. A3/D3/X1, D1/D2/D4/D5, Track B and Gate 7 remain owner decisions or separate validation tracks. The full-gate count is now fully reconcilable: all 130 JUnit XML files are archived.

## 8. Evidence artifacts

`docs/v2/closure-evidence/` contains all 130 JUnit XML files plus `fullgate-run.txt` (raw Gradle output).

## 9. Git diff summary

5 tracked files modified (3 production, 2 test/report), 2 new test classes, 1 new report, 131 archived artifacts. Net diff visible at report time: +133/−59 lines in tracked files plus the new files.

## 10. Worktree and authorization

Branch `v2-16-planning-baseline`, HEAD still `31715d0…`; remote unchanged. Worktree contains the closure changes and the untouched pre-existing `v2-66-phaseF-stuck.png`. **No commit, push, PR or merge was performed.** A2 slice 1 and the foundation programme remain unaccepted pending owner review.
