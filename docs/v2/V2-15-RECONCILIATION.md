# V2-15 Phase A — repository reconciliation

Status: **Phase A complete, 2026-11-23. Read-only.** No source, test, workflow or
committed document was changed by this phase. The only new file is this report.
Implementation has not started; Phase B awaits the owner's go-ahead.

## 1. Repository state

| Item | Value |
|---|---|
| Branch | `main` |
| HEAD | `adc26c0` (`docs(v2): finalize A12 citations; fix stale question comments`) |
| Working tree at start | clean (`git status --short` empty) |
| Commits since `adc26c0` | none |
| Tags | `baseline/2026-10-17` only |
| Remote | `sadi21863-bit/crush-android-client` |
| Latest CI | run `45214583147`, **success**, 3m10s, `android-ci` on `main` |

The brief's starting revision `adc26c0` matches the actual HEAD. The previous
five pushes are green:

| Commit | CI | Result |
|---|---|---|
| `adc26c0` final A12 citations/comments | 45214583147 | success |
| `35f0682` A12 question answering | 45213085240 | success |
| `42a5067` A7 permission fail-closed | 45172545549 | success |
| `6cdd2fc` V2-12 audit fixes | 45146277708 | success |
| `ced7bf6` V2-11 ledger/review/audit | 45134642080 | success |

The workflow still runs the four-task engine-less gate on JDK 21
(`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
:app:lintRelease`). `libcrush.so` remains gitignored, so CI remains a compile +
JVM + lint gate, not a runtime proof.

## 2. Issues and pull requests

| Item | State | Relevance |
|---|---|---|
| Issue #2 | **OPEN** | `x86_64 emulator with ARM native bridge`: engine SIGSEGVs in `runtime.vdsoFindVersion`; the README claim needs qualification. Explicitly deferred by the V2-15 brief, but the documentation mismatch is real. |
| PR #1 | **CLOSED, not merged** | "V2 redesign reference", branch `v2-redesign-reference`, closed 2026-11-12. No code from it is on `main`. |

No open PR exists. No issue other than #2 is open.

## 3. Package status

| Record | Status | Evidence |
|---|---|---|
| V2-0 | complete | V2-A14; `V2-0-signoff-2026-11-22.md` |
| V2-1 | complete (phone scope) | V2-A13; tablet under O3 |
| V2-2 … V2-6 | complete | package records + parity dirs |
| V2-7 | complete (foundation) | V2-A18 |
| V2-8 | closed, **not adopted** | V2-A19; debug lab remains |
| V2-9 | complete (hardening) | V2-A15 |
| V2-11 | complete (ledger/review/audit) | `V2-GAP-AUDIT.md`, `V2-COMPLETION-LEDGER.md` |
| V2-12 | complete (authorized audit subset) | A1/A3/A4/A5/A8/A10/A11/A14 + confirmations; 997/104 gate |
| V2-13 | complete (A7/A7b fail-closed) | 1001/105 gate; owner-authorized |
| V2-14 | complete (A12 question answering) | 1020/108 gate |
| V2-15 | **not started** | this report only |
| Gate 7 | deferred, separate | signing/listing/submission/rollout untouched |

The audit's release-gating code set is empty. A2 (project home), A6 (route/mode
monitor), A13 (runtime disposal) and A14's 3-child PSS measurement remain open
as roadmap/architecture work, not release gates.

## 4. Changes after the previous audit

The previous audit record is the V2-11 batch at `ced7bf6`. Since then:

| Commit | Scope | Test delta |
|---|---|---|
| `6cdd2fc` | V2-12: nav veto (A1), copy (A3/A4/A5), atomic engine slots (A8), settings confirmations/draft (A10/A11/auto-approve), comments (A14) | +16 (981 → 997); suites 104 |
| `42a5067` | V2-13: permission policy fail-closed (A7/A7b) | +4 (997 → 1001); suites 105 |
| `35f0682` | V2-14: question answering (A12) | +19 (1001 → 1020); suites 108 |
| `adc26c0` | docs/citations + two comment corrections | none |

No tests were removed. `AppContainerBindingTest` was reduced 5 → 4 during V2-11
(before this baseline) because its DataStore seed deadlocked under Robolectric;
that is already recorded in the ledger.

## 5. Current gate evidence

Local four-task gate `v2-16-a12-gate-2026-11-23.log`:

- **BUILD SUCCESSFUL in 17m07s**
- **1020 tests / 108 suites / 0 failures / 0 errors / 0 skipped**
- lint **0 errors / 1 pre-existing `NotShrinkingResources` warning**
- tallies re-read from `app/build/test-results/testDebugUnitTest/*.xml`
  (108 files), not from Gradle stdout.

This is JVM/Robolectric + compile + lint evidence. It is **not** a device or
instrumented run. Only `SecureKeyStoreInstrumentedTest` has a stored device run
(V2-9); the remaining instrumented classes are compiled, not executed.

## 6. Documentation reconciliation findings

Severity is about documentation accuracy, not product risk.

| # | Sev | Location | Finding |
|---|---|---|---|
| D1 | **High (docs)** | `docs/v2/V2-COMPLETION-LEDGER.md:3-17` | The header still says the ledger is "at tree `225ce94` plus the uncommitted V2-11 batch" and only mentions V2-12/V2-13. It does not state that HEAD is `adc26c0` or include the V2-14 row in the baseline description. This is the stale baseline the brief calls out. |
| D2 | **Medium** | `docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md:17-19` | The "V2 package record through 2026-11-23" pointer says "V2-0 to V2-9 plus the V2-11 and V2-12 batches"; V2-13 and V2-14 are missing. |
| D3 | **Medium** | `docs/v2/V2-COMPLETION-LEDGER.md:249-264` | The consolidated NOT PERFORMED list names the V2-12 and V2-13 device gaps but not the V2-14 question dialog/free-text/TalkBack gap, nor pending-question process-death persistence. |
| D4 | **Medium** | `README.md:70-77` | The x86_64 emulator claim is unqualified: "cannot run this app". Issue #2 documents an ARM-native-bridge path that launches but SIGSEGVs in the Go runtime. The claim should say it is unsupported/unreliable and cite the crash, without expanding the deferred emulator investigation. |
| D5 | **Low** | `docs/v2/README.md:3-8` | The top status sentence lists V2-0…V2-9 only; the audit batches V2-12/13/14 appear in the tables and Scope bullets. Not a contradiction, but the headline is not a complete current-state pointer. |
| D6 | **Low** | `docs/v2/V2-GAP-AUDIT.md:89` | A13's line references are pre-V2-12: `EngineInstanceRegistry.kt:85` is now `:98` (`runtimes`) and `:163-180` (`stop`) is now `:196-213`. The A13 verdict is unchanged; the citations are stale. A2/A6 citations may have shifted similarly. |
| D7 | **Low, known conflict** | `README.md:21` vs `V2-COMPLETION-LEDGER.md` §6 row 3 | README says engine v0.100.0 is the engine of record; the ledger records the bundled version as 0.98.1 and keeps the conflict. Unresolved by design; no new evidence in Phase A. |
| D8 | **Low, historical** | Ledger V2-12/V2-13 rows and status-doc rows | They still say "A12 open" as at those batches; the V2-14 row supersedes it. The rows are explicitly historical and are not rewritten. No action required beyond D1/D2. |

No documentation claims a device pass for V2-12/13/14. No doc claims the full
gate proves runtime behavior.

## 7. Remaining open findings

| ID | State | Notes |
|---|---|---|
| A2 | open, design | Project home remains a modal sheet; owner design decision. |
| A6 | open, monitor | `chat` route + `codeActive` dual meaning; works as built. |
| A13 | open, architecture | Runtime retention/disposal; Phase B target. |
| A14-PSS | open, measurement | Total process budget including legacy; needs 3-child PSS. |
| Free-text UI | open, coverage | A12 free-text field is not Compose-tested (Robolectric cursor loop); wire mapping is pinned. |
| Device matrix | NOT PERFORMED | API 30+, tablet/wide, extended rail hardware, TalkBack, RTL, 1.3x, clipboard/share path. |
| Gate 7 | deferred | Signing, listing, submission, rollout. |
| Issue #2 | open, deferred | x86_64 ARM-bridge crash and README qualification. |

## 8. New regressions

None observed in Phase A. The tree is clean, HEAD matches the brief, all five
recent CI runs are green, and the local 1020-test gate is green. The known
limitations above are carried forward, not new failures.

## 9. Reconciliation verdict

**The source/tests and the V2-12/13/14 records agree.** The remaining work in
Phase A is documentation reconciliation (D1–D4 especially), which Phase H
should perform after the implementation/verification phases establish new
results. No implementation was started, and no V2 package was reopened.

Awaiting the owner's go-ahead for Phase B (runtime lifecycle investigation).
