# A2 slice 1 — post-implementation remediation report (2026-11-27)

> **2026-11-27 — post-commit repository-state correction.** The statements in
> §1 (HEAD `105843e`, "nothing was committed or pushed"), §5 ("not yet
> committed") and the closing sentence of §7 accurately described this
> report's authoring state but are **no longer true of HEAD**. Commit
> `f99668c9292b4f3930ccb45ac7505b1ce5a9f60e` ("fix(v2-16): remediate A2 slice 1
> persistence, recovery and UI evidence", 2026-11-27 20:53:09 +0530) DID commit
> and push this report, the recon record, the production/test changes and all
> archived XML/log artifacts to `origin/v2-16-planning-baseline`. Both local
> HEAD and the remote branch were at `f99668c` when the second remediation pass
> began. `main` was and remains untouched. Historical lines stand as written.
>
> **Archive qualification.** The archived `a2-rem-*-junit.xml` files sum to 74
> tests; the targeted run's reported 85 included 11 adapter tests whose
> run-specific XML was not archived in that pass. Full-gate totals derive from
> Gradle output and are not independently re-derivable from the archive.

## 1. Repository state

- Branch: `v2-16-planning-baseline`.
- HEAD (unmodified during this work): `105843e9886565852e94aa097cbbf5065625ac3e`.
- Remote branch head matched HEAD before work began.
- Worktree at report time: 18 modified production/test files, 5 new
  production/test files, 1 new reconnaissance record and 8 archived evidence
  files; plus the pre-existing untracked `v2-66-phaseF-stuck.png` (untouched).
- Nothing was committed or pushed. A1 remains accepted and was not reopened.

Reconnaissance was completed BEFORE any edit and is recorded in
`A2-SLICE1-REMEDIATION-RECON.md` (finding | source evidence | status |
correction | tests required).

## 2. Finding-by-finding remediation

| ID | Status at HEAD | Remediation | Verification |
|---|---|---|---|
| A1 | `markSent()` checked exceptions but ignored a 0-row return | `ExecutionPersistenceGuard.committed(Result<Int>)` requires exactly 1; 0/throw refuse the POST with a named rejection | 5 guard pins incl. 0/throw → 0 POSTs |
| A2 | Controller advanced before the durable ack; 0/throw swallowed | Store ack writes FIRST; controller advances only on a committed write; otherwise honest ambiguity | guard pins; full gate |
| A3 | Exception checks were already before the POST | retained, now unified under the guard | POST-count pins |
| A4 | Reject/ambiguous durable results discarded | results checked; refusal logged; never silent | guard tests + code |
| A5 | `markRejected` could clobber AMBIGUOUS; `markUnsent` too; `markLost` could rewrite terminal UNKNOWN | predicates tightened; `markUnknown` added | DAO no-op test |
| A6 | No send-path zero/throw tests | pure guard seam used by production + deterministic tests | `ExecutionPersistenceGuardTest` 5/5 |
| B | 404 treated as retryable; new runId+executionId while first row AMBIGUOUS | all post-POST failures are AMBIGUOUS; `ExecutionSendPolicy.mayAutoRetry… = false`; post-POST retry removed; pre-POST reattach preserved | `ExecutionSendPolicyTest` 2/2 |
| C | LOST assigned solely because no attach wrapper; evidence vocabulary unused | `ExecutionEvidencePort` + `ReconciliationResult`; UNAVAILABLE/INCOMPLETE/CONFLICTING/NO_EVIDENCE/probe-failure → UNKNOWN with named reason; LOST only on CONFIRMED absence | reconciler tests (gap variants, probe failure, confirmed terminal vs absence) |
| E | DEFERRED run-side ambiguity dropped every startup and mislabelled C6 | run-side AMBIGUOUS stable via `SEND_AMBIGUOUS`, adopted every cycle; prompt ambiguity (non-null `prompt_ref`) deferred only | 3-restart test; cycle-stable detail |
| F | Binding token used as instance key, path used as workspaceId, no generation captured | `instanceKeyForToken` maps token→real key; workspaceId left unknown (never fabricated from path); generation/lease captured at send; generation-aware evidence returns CONFLICTING/NO_EVIDENCE on mismatch/missing; design's `lease_epoch_ms` aligned via additive v4→v5 migration | mapping + match/mismatch/missing tests; v4→v5 copy test |
| D | Recovered records stopped at the controller map | controller flow added through a second `combine`; host input, projection and display-only list lines; no new actions, no prompt UI | `ChatViewStateTest` projection pin + `ChatListContentTest` render pin |
| G | Migration test never inserted a handoff row; evidence doc overclaimed | real full and NULL-optionals handoff rows inserted pre-migration and asserted field-by-field; project timestamps asserted; table/index/canonical-column checks; doc corrected | migration suite 6/6 |

G3 (entity↔SQL↔DAO column agreement) was **not reproducible** and G4 (migration
registration, no destructive fallback) was already correct; both were left
unchanged.

## 3. Files changed and why

- `execution/ExecutionPersistenceGuard.kt` (new) — one pure committed-write rule.
- `execution/ExecutionSendPolicy.kt` (new) — pure no-auto-retry constant.
- `execution/ExecutionEvidence.kt` (new) — `EngineEvidence` + evidence port; production default is UNAVAILABLE.
- `execution/ExecutionModels.kt` — `SEND_AMBIGUOUS` lifecycle, terminal semantics, `recovered` flag.
- `execution/ExecutionController.kt` — terminal set for the new state; `onRecovered` marks recovered.
- `execution/ExecutionDao.kt` — tightened guards, `markUnknown`, UNKNOWN re-adoption, v5 column.
- `execution/ExecutionEntity.kt`, `ExecutionStore.kt` — canonical lease field, generation capture.
- `execution/ExecutionReconciler.kt` — evidence-based outcomes, stable ambiguity, no invented loss.
- `data/project/ProjectDatabase.kt`, `RoomProjectStore.kt` — additive MIGRATION_4_5, version 5.
- `di/AppContainer.kt` — token→instance-key mapping, full outcome adoption, guarded running write.
- `ui/screens/chat/ChatViewModel.kt` — guarded writes, ack ordering, no post-POST retry, host flow.
- `ui/screens/chat/ChatHostInputs.kt`, `ChatViewState.kt`, `ChatListContent.kt` — display-only recovered outcomes.
- Three new execution test classes and three extended UI/execution test classes.
- `docs/parity/v2-16-phase0/A2-SLICE1-REMEDIATION-RECON.md` and this report.

## 4. Tests and exact results

Targeted run (`… :app:testDebugUnitTest` with four `--tests` filters): **85 tests,
0 failures** — controller 15, adapter 11, guard 5, reconciler 11, migration 6,
policy 2, list content 10, view state 25.

New/primary tests:
- `ExecutionPersistenceGuardTest` — only 1 affected row commits; 0 and throw
  prevent the POST; 0/throw never advance the controller.
- `ExecutionSendPolicyTest` — workspace/session 404 → one POST, one execution,
  ambiguous, no automatic second attempt.
- `ExecutionReconcilerTest` — evidence gap variants, probe failure, confirmed
  terminal vs confirmed absence, 3 consecutive restart cycles keeping an
  ambiguous send visible, generation match/mismatch/missing, DAO guard no-ops.
- `ExecutionRoomRoundtripTest` — v3→current preservation of real project and
  handoff rows, canonical lease column, v4→v5 value copy, stale-write no-ops.
- `ChatViewStateTest` / `ChatListContentTest` — recovered outcome reaches
  presentation; unknown is never rendered as lost; terminal is not active work.

## 5. Full gate and archived artifacts

Command: `gradlew.bat :app:testDebugUnitTest` with `JAVA_HOME` = Android Studio
JBR 25, on branch `v2-16-planning-baseline`, working tree containing these
remediations (not yet committed). Result: `BUILD SUCCESSFUL in 6m 54s`.
**125 suites / 1156 tests / 0 failures / 0 errors / 0 skipped** (floor 1087).

Archived in `docs/parity/v2-16-phase0/`:
`a2-rem-fullgate-run.txt` (NUL-stripped raw log),
`a2-rem-persistence-guard-junit.xml`, `a2-rem-send-policy-junit.xml`,
`a2-rem-reconciler-junit.xml`, `a2-rem-migration-junit.xml`,
`a2-rem-controller-junit.xml`, `a2-rem-viewstate-junit.xml`,
`a2-rem-listcontent-junit.xml`.

## 6. Explicitly unresolved / limited

- **No atomic validate-and-attach (§7/§17.1).** Not implemented; recovered
  executions are never claimed live. No stale-generation *latch* test exists.
- **No device evidence.** All results are JVM/Robolectric. No `am kill` cells,
  no fault injection; **blocked by R37** (not authorized).
- **No CI claim.** No GitHub Actions run exists for this branch.
- **Send-path integration limits.** Zero/throw tests pin the pure guard seam
  that production calls; they do not instantiate `ChatViewModel` and drive a
  real `api.send`. The 3-restart test uses the real DAO plus a fresh controller,
  not a real `AppContainer` boot launch.
- **Production evidence is always UNAVAILABLE.** CONFIRMED terminal/absence
  branches are test-only until an engine-query seam is authorized.
- **v5 schema alignment.** Renaming the lease contract to design §5's
  `lease_epoch_ms` required an additive v4→v5 migration; this exceeds the
  original v3→v4 wording and needs owner review.
- `markRejected` remains in the API but is unreachable in production until an
  engine contract proves pre-dispatch rejection.

## 7. Scope confirmations

C4, C5, C6, D3/X1, A3, D1, D2, D4, D7 final resolution, R37, new libraries,
`main`, PR and merge were **not** touched. `main` remains `adc26c0…`, identical
to `origin/main`. A1 reports were not rewritten.

## 8. Recommendation

**Not ready for merge; ready for owner review.** The seven findings are
remediated at source and JVM level, but the unresolved items in §6 — chiefly
the absent atomic-attach seam, device/R37 evidence, the additive v5 schema
change, and the send-path integration-test limit — require an explicit owner
decision. No commit or push was made.
