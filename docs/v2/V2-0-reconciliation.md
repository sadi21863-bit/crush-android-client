# V2-0 - Rev 3.1 brief reconciliation (2026-11-19)

**Status: task 4 COMPLETE; owner sign-off pending.** Planning record only.
No source, test or build file changed.

Sources:

- Brief: `crush-mobile-v2-design-brief-state-contract-test-plan-2026-11-14.md`
  (Revision 3.1), 1,319 lines / 74,654 bytes, LF endings, Appendix C
  included. Fully read for this reconciliation.
- Tree: docs HEAD `4329d26`; app code `1c902eb` (2026-11-18). Everything
  above `1c902eb` is CI workflow, docs or parity PNGs.
- Tests: counted in the tree (850 `@Test` methods / 87 files; the gate
  reports 91 suites).

Corrections already applied to the ledger (not to the brief):

- The brief **is** 1,319 lines; the earlier 996 figure was the non-blank
  count (`Measure-Object -Line`). O13 is closed in the status doc and the
  ADR register.
- The app code baseline is `1c902eb`, not `1679f25` (the latter is docs).

The other three Revision 4.0 review corrections in §5.10 still stand:
2b.4 device parity was performed; O11 needs an injected stall or
instrumentation; the trust wording is "gate-observed", not TOCTOU-free.

Status labels: CODE, TEST, PARTIAL, NOT DONE (absent from tree/records),
STALE (pre-2b anchors), SUPERSEDED, NOT VERIFIED, OWNER-GATE.

---

## 1. Bottom line

Phase 2a and 2b.1-2b.5 are shipped; V2 visual work has not started. The
brief's 2b clauses are largely satisfied at the content/host seam. The
unbuilt half is exactly where V2-1 onward expects to work: no navigation
shell, no token system, no chat redesign, dead `Screen.Models` still
declared, no automated screenshot suite, no `T3-01`/`T6-01`/`T6-02`.

Three gaps are visible in shipped surfaces and are not just "V2 polish":

1. There is no bottom bar, rail or drawer. Chat/Projects/Settings are
   reached from controls inside Chat, not from a primary destination shell.
2. Projects has no "Current" marker bound to `activeBinding`, although the
   brief requires it and the state object does not carry the field.
3. Diagnostics and Settings/About do not show the engine's pinned version
   or contract level; Diagnostics uses `/v1/version` only as a reachability
   probe.

## 2. Design brief (section 4)

| Clause | Status | Evidence |
|---|---|---|
| 4.1 quiet workspace, transcript is the product | direction only | no code claim |
| 4.2 P1 permanent chrome <=56dp | PARTIAL | M3 `TopAppBar` in `ChatContent.kt`; no bottom bar exists |
| 4.2 P2 real state or nothing | CODE | `ChatViewState.kt` projection |
| 4.4/4.5 palette, spacing, shape, type, motion tokens | NOT DONE | `ui/theme/` has raw colors + type only; no token layer |
| 4.6 three primary destinations | PARTIAL | 3 destinations exist; **no shell component** (`NavigationBar`/`Rail`/`Suite` absent from source) |
| 4.6 Review/Diagnostics pushed, Lock overlay | CODE | `NavGraph.kt`; `LockOverlay.kt` |
| 4.6 project row -> Chat replace + singleTop | CODE + DEVICE | `NavGraph.kt:96-105`; flow 26 PASS in `after-2b1` |
| 4.6 Onboarding inclusive pop | CODE | `NavGraph.kt:56-63` |
| 4.7.1 content tree | CODE + TEST | `ChatContent.kt:59`; `ChatScreen.kt:198-241`; T2B1-01/02/03 |
| 4.7.1 top bar (truthful phase, no project chip) | CODE | `ChatContent.kt:106-118` |
| 4.7.1 transcript rules + near-bottom guard | CODE | `ChatContent.kt:73-78,229-263` |
| 4.7.1 tool rows, 3 decisions, dismiss-deny, composer gates | CODE + TEST | `ChatContent.kt`; `ChatGateTest`; T2A-02 |
| 4.7.2 import-only, legacy openable, named Remove | CODE + TEST (+ device trust gate in `after-2b2`) | `ProjectsContent.kt`; `ProjectsContentTest` 11 |
| 4.7.2 "Current" reflects `activeBinding` | NOT DONE | no current/active marker in `ProjectsContent.kt` or `ProjectsViewState.kt`; `activeBinding` not read |
| 4.7.3 Review newest-first, cap, per-file revert, byte-exact export | CODE + TEST | `ReviewHistory.kt`, `ReviewContent.kt`; `ReviewHistoryTest` 14, `ReviewContentTest` 10 |
| 4.7.4 Settings rows, no dead switches | CODE + TEST | `SettingsContent.kt`; `SettingsContentTest` 10 |
| 4.7.4 About: version, build, engine pinned, contract | PARTIAL | About shows "Version 1.0.0" only (`SettingsContent.kt:381`) |
| 4.7.5 Models sheet: name/id/free/selection/loading/error | PARTIAL | sheet CODE; `modelsError` exists, **no loading state**; free marker uses catalogue `isFree` in the sheet |
| 4.7.5 "not in current list" marker | NOT DONE | `presentInCatalogue` is declared/projected but never rendered |
| 4.7.5 delete dead `Screen.Models` | NOT DONE | `NavGraph.kt:16`; 7 routes declared, 6 composables |
| 4.7.6 Diagnostics groups + redaction | PARTIAL | `DiagnosticsScreen.kt`, `DiagnosticsReport.kt`; no pinned version/contract; no report-level redaction test |
| 4.7.7 onboarding saves without implying validation | CODE + TEST | `OnboardingScreen.kt:56-60`; `OnboardingContentTest` 10 |
| 4.7.8 lock policy, chat refuses bootstrap | CODE + TEST | `AppLockPolicy.kt`; `ChatViewModel` "bootstrap refused: locked"; `AppLockPolicyTest` 16, `LockStateTest` 9 |
| 4.8 accessibility | PARTIAL | semantics and 48dp assertions exist; no 1.3x/2.0x or focus-audit evidence found |
| 4.9 performance rule + timing | NOT VERIFIED | no frame-timing record; `ChatScreen.kt:107` rebuilds the view per host emission (`remember(host)`) |

## 3. UI state contract (section 5)

| Clause | Status | Evidence |
|---|---|---|
| 5.1 as-built inventory + line anchors | STALE | files split/grew: `ChatScreen.kt` 288, `ChatContent.kt` 1519, `ChatViewModel.kt` 4834; `ChatUiState` fields moved on |
| 5.2 target `ChatViewState` shape | PARTIAL | shape exists; additive fields beyond the brief (`engineReady`, `prewarm`, session metadata, `unavailable`) are justified in records but not in the contract |
| 5.2.1 Option A atomic `BindingSnapshot` | CODE | `AppContainer.kt:305-308,707-717`; `engineStateForKey` |
| 5.2.1 projection pins readiness/seed | TEST | `ChatHostInputsTest` 7, incl. all `EngineState` values |
| 5.2.1 container-level atomicity test | NOT VERIFIED | no test references `bindingSnapshot`; `SettingsLastBindingTest` covers persistence, not snapshot ordering |
| 5.2.2 `toViewState` pure, Android-free | CODE + TEST | `ChatViewStateTest` purity test, plain JUnit |
| 5.3 field mapping, drop raw question | CODE | `ChatViewState.kt` |
| 5.3 `notice.stopNoticeHandled` | STALE/deviation | `NoticeView` carries `deleteError` only |
| 5.3.1 selected-model identity; absent is not an error | CODE + TEST | `ChatViewState.kt`; `ChatModelMetadataTest` |
| 5.3.1 legacy suffix badge preserved | CODE + TEST | `ChatViewState.kt:177-179`; `ChatModelMetadataTest`; `ChatContent.kt` |
| 5.3.1 catalogue `isFree` badge (T6-01/CORR-003) | NOT DONE | still suffix-based in the transcript; Phase 6 |
| 5.3.1 no price accessor / no synthesized cost | CODE + TEST | reflection pin in `ChatModelMetadataTest`; CORR-002 deferred |
| 5.4 sealed `ChatAction` set | CODE | `ChatAction.kt` |
| 5.4.1 complete interaction ledger | TEST | `ChatDispatchTest` asserts 37 rows |
| 5.4.2 `draft` freshness gate | CODE + TEST | `ChatDispatch.kt:22-33`; `ChatScreen.kt` `rememberUpdatedState`; `ChatHostCallbackTest` 4 |
| 5.4.3 trust dialog host-owned | CODE | `ChatScreen.kt`; `ChatContent.kt` intent only |
| 5.5 #11 one collection per screen | PARTIAL | `ChatScreen.kt:38-45` also collects engine state, active instance and key; `:100` binds from the unkeyed flow |
| 5.5.1 composer gates, all eight rows | CODE + TEST | `ChatGateTest` 7 |
| 5.6/5.6.1 content/host split, pixel parity | CODE + DEVICE | `after-2b1` a01 0 changed pixels |
| 5.7 later seams (`ChatBootstrap`, `RunCoordinator`, `HandoffController`) | NOT DONE | expected post-Phase 6 |

## 4. Test registry (section 6)

| ID / clause | Status | Evidence |
|---|---|---|
| T2A-01..05 | TEST | `ChatViewStateTest` 10, `ChatGateTest` 7, `ChatHostInputsTest` 7, `ChatModelMetadataTest` 6, `ChatDispatchTest` 8 = 38 methods; the brief targets 28-36, records said 36 |
| T2A-04a | TEST | legacy suffix badge, `ChatModelMetadataTest` |
| T2B1-01..03 | TEST | `ChatContentTranscriptTest` 9, `ChatContentGateTest` 8, `ChatContentSheetsTest` 14 (records said 12; grew for Finding 11) |
| T2B1-04 / C.2 | TEST | `ChatHostCallbackTest` 4, Compose + Robolectric |
| T2B3-01 | TEST | `ReviewHistoryTest` 14 (2 added for supersede/cap) |
| T2B5-01 | TEST | `OnboardingContentTest` 10 |
| T3-01 navigation decision test | NOT DONE | no `shell`/`NavigationDecisionTest`; `ui/` tests contain only `screens/` |
| T6-01 / T6-02 | NOT DONE | no catalogue badge; no interaction-parity test |
| 6.1 coverage census | STALE | brief 716/76; tree 850 methods / 87 files / 91 suites |
| 6.4 test structure | PARTIAL/deviation | tests live under `ui/screens/*`, not `ui/chat`; recorded |
| 6.6 screenshot policy | NOT DONE | no screenshot plugin or automated suite; manual PNG manifests only |
| 6.7 instrumentation | NOT DONE | 5 classes / 12 tests, unchanged; CI compiles only |
| 6.8 CI | CODE | four-task gate, Temurin 21, engine-less APK; run `44542121606` |

## 5. Phases (section 7)

| Phase | Status |
|---|---|
| 0 baseline | COMPLETED (recorded, not re-run in V2-0) |
| 1 decisions | SUPERSEDED by the 2026-11-19 owner record |
| 2a projection | DONE |
| 2b.1 Chat | DONE + device |
| 2b.2 Projects | DONE |
| 2b.3 Review | DONE (populated card still NOT PERFORMED on device) |
| 2b.4 Settings | DONE (Finding 12 fixed later) |
| 2b.5 Onboarding | DONE (real key entry NOT PERFORMED) |
| 3 tooling/nav tests | NOT DONE |
| 4 tokens | NOT DONE |
| 5 shell + delete Models | NOT DONE |
| 6 chat redesign / T6 | NOT DONE |
| 7-10 | NOT DONE |

## 6. Gate, device and parity (section 8)

| Clause | Status | Evidence |
|---|---|---|
| 8.1 four-part gate | USED | batch records cite command output, XML and device ids |
| 8.2 device protocol | FOLLOWED | manifests; owner-only rows BLOCKED |
| 8.3 evidence standard | FOLLOWED | NOT PERFORMED/PARTIAL/BLOCKED used honestly |
| 8.4.1 project open/back-stack | CODE + DEVICE; test gap | `NavGraph.kt`; flow 26 PASS; `T3-01` absent |
| 8.4.2 Review lifecycle | CODE + TEST | `ReviewHistoryTest`, `ReviewContentTest` |
| 8.4.3 handoff restoration | CODE + TEST | `HandoffConsentContractTest` 11, order 2, chip 7 |
| 8.4.4 permission denial | CODE + TEST; one gap | 3 decisions and dismiss-deny pinned; engine-effect row #7 not found in the device records |
| 8.4.5 engine binding | CODE | prewarm race pinned in `ChatHostInputsTest` |
| 8.5 Set A | PARTIAL | 10/10 captured, 9 PASS + `a02` PARTIAL |
| 8.5 Set B | PARTIAL | 24 PASS / 0 FAIL / 4 NOT PERFORMED / 0 BLOCKED; flows 18/19/20/28 owner-performed; flow 13 pins a pre-existing defect |
| 8.5 both sets per later batch | PARTIAL | 2b.2-2b.5 re-walk their own screen region only; no full two-set re-walk |

## 7. Decisions and Appendix C

Section 9 items:

- SUPERSEDED by the 2026-11-19 owner record: #1 (3 destinations), #3/#4
  (pinned/follow deferred), #8/#9 (cost/context deferred), #17 (2a shipped),
  #18 (CORR-001..005 status restated).
- CODE/TEST as written: #2 stable M3; #5 session sheet; #11 no attachments;
  #14 no Review tab/revert-all; #15 Option A; #16 legacy badge half.
- PARTIAL: #6 Projects-only picker (handoff sheet selects a project by
  design); #12 accessibility continuous; #16 Phase 6 half.
- NOT DONE: #7 delete Models route; #10 app-lock status (optional); #13
  `ChatViewModel` extraction after redesign.

Appendix C:

| Clause | Status | Evidence |
|---|---|---|
| C.1 initial seed/precedence, six cases | CODE + TEST | `ChatHostInputs.kt`; `ChatHostInputsTest` |
| C.2 draft freshness | TEST | `ChatHostCallbackTest` 4/4 |
| C.3 exhaustive dispatch/effect table | CODE + TEST | `ChatViewModel.dispatch`; `ChatDispatchTest`; deviation: `ChatHostEffect.ConfirmHandoff` added, recorded |
| C.4 parity staging | PARTIAL | pre-batch sets captured; 2a recorded no device run; 2b.1 after-walk present |
| C.5 criterion 12 | CODE + TEST | effect table pinned |

## 8. Deviations and stale clauses (do not silently "preserve")

- Section 5.1 inventory, file sizes and every line anchor written before
  2026-11-18 are stale; re-baseline before citing.
- Section 6.1 and Appendix A census (716 tests / 76 suites) is stale;
  use 850 methods / 87 files / 91 suites at `1c902eb`.
- Section 7 phase table, section 12 "nothing implemented" and section 13
  "before 2a" checklists describe a pre-2b world.
- Section 9 status column is superseded by the owner record; do not read
  "PENDING" as still open where the owner decided.
- Section 0.2 authority order is now hybrid, not Rev 3.1 alone.
- The onboarding anchor `OnboardingScreen.kt:235-244` is stale (file is
  ~100 lines).
- `notice.stopNoticeHandled` is not in the shipped `NoticeView`; the
  documented contract and the code differ.
- Additive `ChatViewState` facts (`engineReady`, `prewarm`, session
  metadata, `unavailable`) are deliberate but not in the brief's contract;
  V2 must either document them as contract v2 or stop reading them.
- Test structure deviates (`ui/screens/*`, not `ui/chat`); two counts
  differ from records (sheets 14 vs 12; T2A files 38 vs 36). Counts are
  method counts, not a claim that every method maps 1:1 to a brief ID.

## 9. Must carry into V2

Live clauses, keep authoritative:

1. 5.2.1 + C.1: Option A keyed snapshot and the `Switching` invariant.
2. 5.4.2 + C.2: draft freshness and `rememberUpdatedState`.
3. 5.5.1: all eight composer gates.
4. 5.3.1: two-rule badge and the T2A-04a -> T6-01 retirement pair.
5. 6 registry IDs and "no test implied"; 8.5 two-set parity standard;
   8.4.1/8.4.2 matrices.
6. Review eligibility/process-scope contract; 4.7.7 onboarding must not
   imply validation.

Retire or re-baseline: 5.1 inventory, 6.1/Appendix A census, 7/12/13 phase
status, 9 status column, 0.2 authority order, stale line anchors.

Gaps that need a V2 work package:

| Package | Gap |
|---|---|
| V2-3 | delete `Screen.Models`; build the 3-destination shell and breakpoints (no bar exists today); add `T3-01` |
| V2-4 | Models-sheet loading state + "not in current list" marker; Projects "Current" marker from `activeBinding`; performance proof; resolve the second engine-state source before redesign |
| V2-6 | Diagnostics pinned engine version + contract level; report-level redaction/tail test; About build/engine/contract; Finding 13 copy |
| Test debt | container-level `applyBinding`/`bindingSnapshot` test; automated screenshot suite; instrumentation additions; `T6-01`/`T6-02` |

## 10. Exit and boundary

V2-0 task 4 is complete: every brief area has a status and an evidence
pointer, and the four earlier corrections are resolved (brief length,
2b.4 parity, O11 harness, TOCTOU wording). Task 6 (fresh screenshots)
remains NOT PERFORMED because no device was attached. Owner acceptance of
this ledger is the remaining V2-0 exit step. No implementation is
authorized by this document.
