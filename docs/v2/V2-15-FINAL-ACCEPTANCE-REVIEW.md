# V2-15 final integration and acceptance review

Date: 2026-11-25. Type: **read-only branch review.** No production, test or
workflow file was changed by this review; the only edits it makes are
corrections to V2-15 documentation where it disagreed with the code or with
Phase F. No merge, PR, tag or issue was created.

Review start: branch `v2-15-lifecycle-hardening` at `789ad09`, **10 commits
ahead of `main` (`adc26c0`), 0 behind.** This review adds one docs-only commit
on top.

Evidence used: the branch diff, the source tree, the stored JUnit XML for the
last gate, the stored instrumentation logs, and the V2-15 records. The test
suites were **not** re-run for this review, and the Go engine was not audited.

## 1. Verdict

**Accept V2-15 on the documented scope.** The implementation matches the
records on every claim inspected; no production defect was found; the
outstanding items are documented limitations and owner decisions, not
undisclosed behaviour.

**Owner decisions (2026-11-25), recorded after this review:**

1. **R12 settled — `v0.100.0` is the engine version of record** for this
   baseline (V2-A20). The `0.98.1` references are historical bundle
   references, kept as history. Qualification: this confirms the declared
   baseline and the binary actually run, not an independent audit of the Go
   engine or of every packaged artifact.
2. **R1, R2, R7, R10 accepted as documented limitations** — not as proof of
   release readiness. E3/E4 stay correctness concerns for future architecture;
   R7 needs a defined release-device matrix before a broad release; R10 is an
   accepted lifecycle trade-off, not guaranteed cleanup.

Three documentation corrections were required and are made in this review
(§7). They were the only disagreements found between docs and code.

## 2. Branch composition

Ten commits, no merge commits, merge-base exactly `adc26c0`:

| Commit | Date | Phase |
|---|---|---|
| `1990118` | 2026-11-24 | V2-15 L1-L5 lifecycle hardening |
| `a1be361` | 2026-11-24 | post-push L2 terminal-startup correction |
| `6379165` | 2026-11-24 | Phase D — fail closed on cancelled policy apply |
| `3a261e6` | 2026-11-24 | Phase D — serialize opens per path |
| `878afa9` | 2026-11-24 | Phase D — reattach fast path under the lock |
| `9af99c1` | 2026-11-24 | Phase E — lifecycle contract tests |
| `289de6f` | 2026-11-25 | Phase E — E1/E2/E5 remediation |
| `1111b37` | 2026-11-25 | Phase E — stale-engine rejection before HTTP |
| `8509c6b` | 2026-11-25 | Phase F — device matrix evidence + test-only wire fix |
| `789ad09` | 2026-11-25 | Phase F — timing correction |

Diff against `main`:

| Area | Files | Size |
|---|---|---|
| Production (`app/src/main`) | **11** | +669 / −191 |
| JVM tests (`app/src/test`) | 14 (9 new, 5 modified) | — |
| Instrumented (`app/src/androidTest`) | 2 modified | — |
| Docs + evidence (`docs/`) | 75 (incl. 44 captures, 11 logs) | — |
| **Total** | **102** | +5630 / −329 |

No file outside `app/` and `docs/` changed. No Gradle file, version catalogue,
workflow, `spec/` file or `.go` file changed.

## 3. Objectives → outcome

| Objective | Outcome | Evidence |
|---|---|---|
| A — repository reconciliation | complete | `V2-15-RECONCILIATION.md`; HEAD matched the brief |
| B — runtime lifecycle audit (L1-L6) | complete | `V2-15-RUNTIME-LIFECYCLE-AUDIT.md` |
| C — lifecycle remediation | complete | `1990118`; `ShutdownPlanTest`, `EngineStopCleanupTest`, `AppContainerShutdownTest` |
| D — permission-policy verification | complete; one defect found and fixed | `V2-15-PERMISSION-POLICY-VERIFICATION.md`; `6379165`/`3a261e6`/`878afa9` |
| E — question-answering verification + remediation | complete; E1/E2/E5 fixed, E3/E4 deferred | `V2-15-QUESTION-ANSWERING-VERIFICATION.md`, `…-REMEDIATION.md`; `289de6f`/`1111b37` |
| F — device matrix | complete on available hardware; gaps explicit | `V2-15-DEVICE-MATRIX-VERIFICATION.md` |
| L4 — retention measured | partial by design | device PSS + post-stop census; 3-child PSS and thread census NOT PERFORMED |
| L5 — `RUNNING` after terminal failure | no correction required | bookkeeping only |
| L6 — proxy scope never cancelled | fixed inside L2 | `EngineProxy.stop()` cancels the per-start scope |
| A7/A7b — permission fail-closed | closed at JVM level only | no effective-mode read-back; no device re-run |
| A12 — question answering | closed; `yes_no` device-verified | Phase E + Phase F |

## 4. Production change audit

Every changed production file maps to a documented finding; symbol-level check:

| File | Documented change | Verified in source | Pinned by |
|---|---|---|---|
| `di/AppContainer.kt` | L1 shutdown plan, one-shot, returns stop count | `ShutdownPlan` (`:57`), `shutdown(): Int` (`:997`), `buildShutdownPlan()` (`:1007`) snapshots legacy + registry runtimes | `ShutdownPlanTest` 4, `AppContainerShutdownTest` 1 |
| `engine/CrushEngine.kt` | L2 teardown always runs child+proxy; test seams only afterwards | `stopBlocking()` (`:557`) via `runEngineStop` | `EngineStopCleanupTest` 3, `CrushEngineLifecycleTest` 1, `EngineTerminalStartupCleanupTest` 1 |
| `engine/EngineProxy.kt` | one cancellable scope per start | `scope` (`:65`), new scope per start (`:102`), `scope?.cancel()` (`:134`) | `EngineProxyConnectTest` +2 |
| `engine/EngineSupervisor.kt` | terminal `Failed` calls `stopBlocking()` first | `:467` in the terminal branch; `:171` in `stop()` | `EngineTerminalStartupCleanupTest` |
| `data/crush/ConnectionRegistry.kt` | `isSuperseded(key, ownerEpoch)`; `registrationKey` deleted | `isSuperseded` (`:192`); **zero** `registrationKey` references in `app/src/main` | `ConnectionRegistryTest` 25 |
| `ui/screens/chat/ChatViewModel.kt` | L3 captured key; A12 claim/revalidate | `registeredConnectionKey` (`:960`, used `:1813`/`:1946`); `questionOps` (`:651`); `executeWithReattach` (`:2725`, `:2792`) | `ConnectionRegistryTest`, `QuestionOperationControllerTest` 16 |
| `data/crush/WorkspaceManager.kt` | per-path open mutex; reattach under the same lock; cancellation flush | `openLocks` (`:87`), `open()` takes `openLock(path)` (`:217-224`), `openLocked()` (`:229`), reattach calls `openLocked` (`:398`) | `WorkspacePermissionPolicy*` 15, `WorkspaceLeaseTest` 14 |
| `ui/screens/chat/QuestionLifecycle.kt` | E1/E2 identity-checked controller | `QuestionOperationController` (`:37`), `executeWithReattach` (`:95`) | `QuestionOperationControllerTest` 16 |
| `ui/screens/chat/QuestionBatch.kt` | E5 validator before claim/HTTP | `QuestionAnswerValidator` (`:132`), called at `ChatViewModel.kt:2780` | `QuestionAnswerValidatorTest` 7 |
| `ui/screens/chat/QuestionDialog.kt` | Submit **and** Cancel disabled in flight | `busy = view.operation != null`, both `enabled` gates | `QuestionDialogTest` 9 |
| `ui/screens/chat/ChatViewState.kt` | in-flight operation projected to the dialog | `QuestionView.operation` | `ChatViewStateTest` |

The report's claim that "no other production file changed" was **false for the
branch** and is corrected in §7 (D2): the four Phase E files above were missing
from the table.

## 5. Test evidence audit

### 5.1 JVM gate (last full run)

Re-read from `app/build/test-results/testDebugUnitTest/*.xml`, not from Gradle
stdout:

| Metric | Value |
|---|---|
| Suites | **117** |
| Tests | **1087** |
| Failures / errors / skipped | **0 / 0 / 0** |
| XML newest timestamp | 2026-11-25 02:35 |
| Gate log | `v2-61-phaseE-followup-gate2.log`, BUILD SUCCESSFUL in 21m37s, log mtime 02:58:55 |

Delta from `main`'s recorded gate at `adc26c0` (1020 / 108): **+67 tests, +9
suites**. Spot-checked class counts match the records: `QuestionOperationControllerTest`
16, `QuestionAnswerValidatorTest` 7, `QuestionBatchParserTest` 8,
`QuestionDialogTest` 9, `QuestionAnswerBuilderTest` 4, `ConnectionRegistryTest`
25, `WorkspaceLeaseTest` 14, `WorkspacePermissionPolicyScenariosTest` 11,
`WorkspacePermissionPolicyTest` 4, `ShutdownPlanTest` 4,
`EngineInstanceRegistryTest` 26, `EngineStopCleanupTest` 3 — all 0 failures.

Mutation evidence exists for the three claims that matter (each run fails when
the protection is removed): `v2-34` (out-of-order ASK/YOLO apply), `v2-37`
(reattach fast path), `v2-47`/`v2-48` (question identity/guard), `v2-59`
(stale-engine revalidation).

### 5.2 Instrumented (Phase F, `bbf8ca69`, API 29)

14 test methods passed, 1 blocked: `SecureKeyStore` 3, Room 2, smoke 2,
two-project 1, two-engine 1, lock-flow 5 of 6. The blocked one is
`recreationKeepsGateDecisionAndLockStateObject` (test design — the system
credential activity prevents a pre-auth `recreate()`).

### 5.3 Evidence labels

- **JVM/Robolectric:** all 1087 tests, including every lifecycle, permission and
  question-controller claim.
- **Device:** the 14 instrumented tests, the A12 `yes_no` dialog, TalkBack focus
  traversal, 1.3x, copy/share, simulated wide rail.
- **Neither:** API 30+, tablet hardware, RTL, TalkBack speech, the other three
  question kinds, 3-child PSS, thread census.

## 6. Where documentation and implementation now agree

- Finding semantics (L1-L6), production files, test names and counts match the
  code and the XML.
- Phase F's corrected timing (`1432.201 s`) matches the log and wall clock.
- The engine-isolation tests are labelled test-only and do not claim to cover
  production version negotiation.
- The last full gate is labelled a Phase E result, not a Phase F one.

## 7. Corrections made by this review

| # | Correction | Why |
|---|---|---|
| D1 | Integration report header no longer says "instrumented execution and TalkBack remain NOT PERFORMED"; it states Phase F performed them and names what still remains (API 30+, tablet, RTL, speech, three question kinds). | The header was false after Phase F. |
| D2 | Integration report §1 gained the four Phase E production rows (`QuestionLifecycle`, `QuestionBatch`, `QuestionDialog`, `ChatViewState`) and the "no other production file changed" sentence was scoped to the L/D batch. | 11 production files changed; §1 listed 7. |
| D3 | §6 rows for instrumented/TalkBack and question device coverage, and the §8 closing bullet, now say "partly performed in Phase F" / "device run added" instead of NOT PERFORMED. | Stale after Phase F; they contradicted §10. |

Known-but-deliberate staleness, **not** changed here:

- The ledger's `V2-15` package row is a snapshot of its batch (by the ledger's
  own convention, later records supersede without rewriting the row). Its
  "no instrumented run" note is superseded by the Phase F record.
- `docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md` is pinned at code HEAD `af178f8`
  and lists the V2 package record only through V2-12 (D2 in Phase A). It is
  historical by design; updating it is a housekeeping task, not evidence.
- `README.md`'s status headline still lists V2-0…V2-9 (Phase A D5).

## 8. Outstanding-risk register

Severity is about release/engineering risk, not documentation tidiness.
"No" in **Blocks merge** means the item is a documented limitation the owner can
accept.

| ID | Risk | Sev | State | Blocks merge |
|---|---|---|---|---|
| R1 | **E3** — a pending question carries no `session_id`/`run_id`; the answer is posted to the engine's current session. One project chat at a time makes this benign; concurrent asks could be answered against the wrong session. | Med | **accepted 2026-11-25** as a documented limitation; stays a correctness concern for future architecture | No |
| R2 | **E4** — a pending question has no engine-generation identity; after an engine restart the stale dialog survives until an answer/cancel fails or the user cancels. | Med | **accepted 2026-11-25** as a documented limitation; stays a correctness concern for future architecture | No |
| R3 | No process-death persistence for a pending question; the batch is lost with the process and the blocked run is abandoned. | Low | documented | No |
| R4 | No answer read-back; a 2xx on `/questions/answer` is the only proof of acceptance. | Low | documented | No |
| R5 | Free-text question UI has no Compose test (Robolectric cursor loop); only the wire mapping is pinned. | Low | documented | No |
| R6 | `recreationKeepsGateDecisionAndLockStateObject` cannot run on a device with the system credential prompt; JVM `LockStateTest` (9) is the coverage. | Low | blocked, test-only | No |
| R7 | Device matrix gaps: API 30+, tablet/foldable, RTL, TalkBack speech, `single_choice`/`multi_choice`/`free_text` dialogs, real extended-rail hardware. | Med | **accepted for the V2-15 scope 2026-11-25; not cleared for a broad production release** — a release-device matrix is still required | No for merge; release gate |
| R8 | **A2** — project home is still a modal sheet; owner design decision. | Low | open by design | No |
| R9 | **A6** — `chat` route + `codeActive` carry two meanings; works as built, monitor only. | Low | open, monitor | No |
| R10 | **A13** — runtime disposal: stop/restart paths are verified, but `onTerminate()` is not guaranteed by Android, so process death relies on the engine's idle timeout for cleanup. Documented at the call site; shutdown-on-process-termination is NOT PERFORMED. | Med | **accepted 2026-11-25** as an Android lifecycle trade-off, not guaranteed cleanup | No |
| R11 | 3-child PSS and per-process thread census were never measured; the budget claim rests on a 2-child sample. | Low | NOT PERFORMED | No |
| R12 | **Engine-version record conflict:** `README.md` names v0.100.0 as the engine of record, the ledger still records the bundled version as 0.98.1 and "keeps the conflict"; the device reports `v0.100.0`. A future fetch could pick the wrong binary. | Med | **resolved 2026-11-25 (V2-A20):** `v0.100.0` is the version of record; `0.98.1` is classified historical. Qualification: declared baseline, not an independent engine audit | No |
| R13 | Issue #2: x86_64 emulator with ARM native bridge SIGSEGVs in the Go runtime; README qualification deferred. | Low | open, deferred | No |
| R14 | CI is engine-less by design and the branch has **no CI run**; a green CI would prove compile + JVM + lint only. | Med | structural | No (state it in the PR) |
| R15 | Gate 7 (signing, listing, submission, rollout) is untouched and needs its own authorization. | — | deferred | No (out of V2) |
| R16 | Device-run fragility: MIUI defers instrumentation starts for minutes and `OneKeyClean` can kill a run; wall times are not test times. | Low | operational | No |

Device-state note (not a risk): during Phase F the credential prefs grew from
664 to 840 bytes as the `opencode-go` slot appeared; the live slots
(`hyper`, `openrouter`, `opencode-go`) are intact, no plaintext exists, and this
phase wrote no key.

## 9. Acceptance recommendation

1. **Accept V2-15** as an implementation + verification package on the scope the
   records define, with the limitations in §8 carried explicitly.
2. **Decision status:** R12 is settled by V2-A20 (`v0.100.0` of record);
   R1, R2, R7 and R10 are accepted as documented limitations (2026-11-25).
   V2-15 acceptance is **not** a production-release approval.
3. **If a PR is opened:** state that CI is engine-less, that the branch has no CI
   run today, and that the 1087/117 gate is Phase E's. Link
   `V2-15-INTEGRATION-REPORT.md` §10 and
   `V2-15-DEVICE-MATRIX-VERIFICATION.md`.
4. **Do not** describe the device matrix as complete: API 30+, tablet, RTL,
   TalkBack speech and three question kinds remain unverified. A release-device
   matrix is a prerequisite for a broad production release, not for this
   acceptance.

## 10. What this review does not claim

- No test suite was re-run; the gate result is re-read from the stored XML of the
  Phase E run, not re-measured.
- No line-by-line security audit of the 669 added production lines, and no
  review of the Go engine, network protocol or Android Keystore internals.
- No CI confirmation exists for any commit on the branch.
- Absence of a finding here is not proof of absence of defects; it means the
  documented behaviour and the inspected source agree.
