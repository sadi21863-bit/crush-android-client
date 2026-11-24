# V2-15 Phase E remediation — A12 question lifecycle

Status: **implemented and verified, 2026-11-24.** This is the narrow
E1/E2/E5 remediation on top of the Phase E verification. No question-answering
redesign, no Go engine/dependency change, no device or instrumented claim. The
work is committed on `v2-15-lifecycle-hardening` as a follow-up to `9af99c1`;
E3/E4, process-death persistence and answer read-back remain deferred.

## 1. E1 — stale completion protection

A question operation now captures an exact `QuestionRef(questionId, batchId)`.
`QuestionOperationController.execute()` performs every terminal transition only
when `current() == lease.ref`:

- success clears the pending question only for the claimed identity;
- failure sets `questionError` only for the claimed identity;
- the in-flight marker is cleared only for the claimed identity;
- a stale completion releases only its own guard entry and never touches a
  newer batch.

This covers both Submit and Cancel. A failed operation still keeps its own
dialog open with the existing error strings; a stale failure cannot overwrite a
newer batch's error.

## 2. E2 — single in-flight operation

`QuestionOperationController.claim()` is synchronous and `@Synchronized`:

- the guard is keyed by `(questionId, batchId)`;
- a second claim for the same identity returns null, so no coroutine/POST is
  created;
- Submit and Cancel share the same key, making them mutually exclusive;
- a token makes release idempotent and prevents an old completion from
  releasing a newer claim;
- a new batch has a different key, so it is usable while an older batch's
  request is still unwinding;
- `execute()` releases in `finally`, including failure and cancellation.

`ChatViewModel` claims before launching and uses
`CoroutineStart.UNDISPATCHED` so the `try/finally` is entered immediately.
`ChatUiState.questionOperation` and `QuestionView.operation` expose
`SUBMIT`/`CANCEL`; the dialog disables both buttons while either is in flight.
A new `QUESTION_REQUEST` resets `questionOperation = null`, so a new batch does
not inherit the old state.

## 3. E5 — answer validation

`QuestionAnswerValidator.validate(batch, answers)` runs before any claim or
HTTP request. It requires:

- every expected prompt id exactly once, with no duplicate/unexpected ids;
- unique prompt and choice ids;
- one selected id for single choice, all selected ids belonging to the prompt
  for multi choice, and no duplicate selected ids;
- `yes` for yes/no (including `false`), no choice/text payload on that kind;
- non-blank free text with no choice/yes payload;
- no answer for `UNKNOWN`.

Invalid submissions get `questionError` and never reach the engine.

## 4. Production files

| File | Change |
|---|---|
| `QuestionLifecycle.kt` (new) | `QuestionOperation`, `QuestionRef`, token lease, synchronous per-identity guard, identity-checked `execute()` |
| `QuestionBatch.kt` | `QuestionAnswerValidator` |
| `ChatViewModel.kt` | synchronous claim, UNDISTPATCHED execute, identity-checked callbacks, new-batch operation reset, validator before HTTP |
| `ChatViewState.kt` | `questionOperation` projection into `QuestionView.operation` |
| `QuestionDialog.kt` | Submit and Cancel disabled while `operation != null` |

No unrelated UI or architecture work.

## 5. Tests

| Class | Tests | Focus |
|---|---:|---|
| `QuestionOperationControllerTest` | 12 | stale success/failure, duplicate Submit/Cancel, mutual exclusion, failure retry, cancellation, new-batch concurrency, guard release |
| `QuestionAnswerValidatorTest` | 7 | ids exactly once, unexpected/duplicate ids, choice ownership, kind shape, false yes, UNKNOWN |
| `QuestionDialogTest` | 9 | existing 7 + both buttons disabled in flight + new batch does not inherit operation state |
| `QuestionBatchParserTest` | 8 | unchanged parser contract |
| `QuestionAnswerBuilderTest` | 4 | unchanged wire contract |
| `ChatViewStateTest` | 19 | unchanged projection |

Focused run: **59 tests / 6 classes / 0 failures** in 2m48s
(`v2-49-phaseE-remediation-focused-final.log`). Full gate: **1083 tests / 117
suites / 0 failures / 0 errors / 0 skipped**, lint **0 errors / 1 pre-existing
warning** in 22m29s (`v2-50-phaseE-remediation-gate.log`). The new suites are
`QuestionOperationControllerTest` (12) and `QuestionAnswerValidatorTest` (7);
`QuestionDialogTest` grew 7 → 9.

## 6. Mutation evidence

| Mutation | Result |
|---|---|
| Identity check removed from `execute()` | **FAILED as intended** — stale submit/cancel success cleared the newer batch; stale failure overwrote the newer error; new-batch guard test failed (`v2-47-phaseE-mutation-identity2.log`) |
| Operation-guard check removed from `claim()` | **FAILED as intended** — duplicate submits/cancels and mutual-exclusion tests failed; old completion released the newer guard (`v2-48-phaseE-mutation-guard.log`) |

The controller was restored byte-identically after each mutation (SHA-256
`1513A1DDAEF6795B1173C7DE1093394A9E2023723C706E5F99E50A91A46F6598`).

## 7. Full gate

`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease`

**BUILD SUCCESSFUL in 22m29s**; **1083 tests / 117 suites / 0 failures / 0 errors /
0 skipped**; lint **0 errors / 1 pre-existing warning**; both APK assemblies
completed (`v2-50-phaseE-remediation-gate.log`).

## 8. Deferred / limitations

- **E3:** app-side session/run ownership remains engine-side only;
  `PendingQuestion` still has no `session_id`/`tool_call_id`.
- **E4:** pending questions still have no engine-generation identity across
  restart.
- Process-death persistence is not implemented.
- No effective-answer read-back; a 2xx remains the only proof.
- Free-text Compose behavior, device, TalkBack and API 30+ remain NOT PERFORMED.
- The focused tests are JVM/Robolectric; no device claim is made.

## 9. State

Branch `v2-15-lifecycle-hardening` remains at the `9af99c1` history; this
remediation is committed on the same branch as a follow-up. No PR, issue or
merge was made.
