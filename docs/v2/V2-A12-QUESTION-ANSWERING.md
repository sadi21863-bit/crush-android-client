# V2-14 / A12 - Agent question answering

**Status: COMPLETE (JVM scope), 2026-11-23.** Implementation record for audit
finding A12 ("the app cannot answer agent questions"). Before this change the
dialog could only dismiss, so the engine kept the run blocked until its idle
timeout; it now renders the parsed batch and posts real answers. Not a release
statement: no device, instrumented, TalkBack or API 30+ run, and Gate 7 is
untouched.

A12 was the last release-gating product gap in `V2-GAP-AUDIT.md`; that set is
now empty. Still open: A2 (project home), A6 (route/mode monitor), A13 (runtime
disposal), device/TalkBack/API 30+ rows, and Gate 7.

## 1. What the engine sends

Confirmed against upstream `charmbracelet/crush/internal/question/question.go`:

```
Request  { id, session_id, tool_call_id, questions[], confirm_title, confirm_description }
Question { id, type, label, question, description, choices[] }
Choice   { id, label, description }
```

Types are `yes_no`, `single_choice`, `multi_choice`, `free_text`; at most 5
questions and 5 choices per question. `choices` is the field; the parser also
accepts `options` as a legacy alias.

The answer wire, as modelled by the app's `QuestionAnswer`:

```
{ "batch_request_id": "...",
  "responses": [ { "request_id": "...", "selected_ids": [...],
                   "fill_in_text": "...", "yes": true, "notes": {...} } ] }
```

All response fields but `request_id` are optional. `QuestionResponse` already
carried `selected_ids`/`fill_in_text`; it gained `yes`. `notes` is unused.

## 2. Parser and model

New `app/src/main/java/com/crush/mobile/ui/screens/chat/QuestionBatch.kt`
defines `AskKind` (`YES_NO`, `SINGLE_CHOICE`, `MULTI_CHOICE`, `FREE_TEXT`,
`UNKNOWN`), `AskChoice`, `AskPrompt`, `AskBatch`, `AskAnswerDraft`,
`QuestionBatchParser.parse(JsonElement?)` → `AskBatch?`, and
`QuestionAnswerBuilder.build(batchId, answers)` → `QuestionAnswer`.

The parser is defensive: the app ships against engine versions whose question vocabulary it cannot verify at runtime:

| Condition | Result |
|---|---|
| not an object, no batch id, or no/empty `questions` | `null` - cancel-only fallback |
| a prompt is unparsable or has no id | skipped; if all are skipped, `null` |
| `single_choice`/`multi_choice` with no parsed choices | `UNKNOWN` |
| type outside the four known values | `UNKNOWN` |
| more than 5 prompts / more than 5 choices | first 5 kept |

`null` selects the pre-A12 cancel-only dialog, keeping the engine releasable.
`UNKNOWN` shows a prompt but blocks Submit, so no choice id is invented.

## 3. Dialog

`QuestionDialog.kt` adds `AnswerQuestionDialog`; `ChatContent` uses it only
when `batch != null`, otherwise the title-only `AlertDialog` remains.

- `yes_no` - Yes/No radios; `single_choice` - one radio per choice;
  `multi_choice` - one checkbox per choice; `free_text` - `BasicTextField`.
- Submit stays disabled until every prompt has an answer.
- Cancel and dismiss/back both send `ChatAction.ReleaseQuestion`; a gesture
  never submits a partial batch. `questionError` is rendered inside the dialog.
- An `UNKNOWN` prompt never produces a draft: Submit stays disabled, Cancel is
  the only exit. `confirm_title` is the title, else the prompt-derived one.

## 4. ViewModel and answer wire

`ChatAction.SubmitQuestion(answers: List<AskAnswerDraft>)` is the new action;
`ChatAction.ReleaseQuestion` remains the cancel path.

`ChatViewModel.submitQuestion(answers)` requires an open `pendingQuestion` with
a non-null batch and a response count equal to the prompt count ("Answer every
question before submitting."), re-attaches via `reattachIfStale()`, requires a
workspace id, builds with `QuestionAnswerBuilder.build(...)`, and POSTs
`answerQuestions` (`/questions/answer`). A 2xx clears `pendingQuestion` and
`questionError`; a re-attach, missing-workspace or POST failure keeps the batch
and dialog open, sets `questionError`, and logs the reason.

`resolveQuestion()` is unchanged: re-attach, POST `cancelQuestions`, clear only
on success, else keep the dialog and show the error. Neither path strands a run.

`QuestionView` carries `id`, `title` and the sanitized `batch`. The raw JSON
stays inside the ViewModel's `PendingQuestion` (used for `displayTitle()`) and
is dropped at the view-state boundary, so the UI holds no unparsed payload.

`QuestionAnswerBuilder` omits empty fields: blank `fill_in_text` and empty
`selected_ids` are not serialized. `yes = false` is serialized; `yes = null`
means "not a yes/no prompt".

## 5. Tests

| Test class | Tests | Coverage |
|---|---|---|
| `QuestionBatchParserTest` | 8 | happy path, `options` alias, missing batch id/questions, skipped prompt, empty-choice downgrade, caps, unknown type |
| `QuestionDialogTest` | 6 | single choice, multi choice, yes/no, cancel, disabled Submit, UNKNOWN |
| `QuestionAnswerBuilderTest` | 4 | selected ids, free-text trim/blank, `yes = false`, empty-selection omission |
| `ChatViewStateTest` | +1 | batch projection into `QuestionView` |

Focused run of the three new classes: **18 tests green**.

## 6. Gate

`v2-16-a12-gate-2026-11-23.log` (repo root, local-only; `*.log` is gitignored):

| Item | Result |
|---|---|
| Tasks | `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintRelease` |
| Build | **BUILD SUCCESSFUL in 17m 7s** |
| Tests | **1020 tests / 108 suites / 0 failures / 0 errors / 0 skipped** |
| Lint | **0 errors / 1 pre-existing warning** |

`:app:assembleDebugAndroidTest` was UP-TO-DATE (no androidTest change), so the
instrumentation APK was cached, not freshly compiled.

## 7. Limits

- The free-text field has no Compose test: Robolectric never reaches idle while
  the cursor animates. `QuestionAnswerBuilderTest` covers the wire mapping, not
  the field.
- Pending questions are not persisted across process death; the blocked run
  hits its idle timeout. Malformed payloads keep the cancel-only fallback (a
  parsed batch with no choice list cannot be answered). No `notes` UI.
- No device, instrumented, TalkBack or API 30+ run covers the dialog, the
  free-text field, radio/checkbox semantics or focus order.
- A 2xx on `answerQuestions` is the proof; there is no read-back that the engine
  accepted the chosen ids.

## 8. Evidence index

| Path | Role |
|---|---|
| `docs/v2/V2-GAP-AUDIT.md` | A12 verdict and the release-gating set |
| `docs/v2/V2-COMPLETION-LEDGER.md` | V2-14 row, gate tally, remaining open rows |
| `ui/screens/chat/QuestionBatch.kt`, `QuestionDialog.kt` | parser/model/answer builder, `AnswerQuestionDialog` |
| `ui/screens/chat/ChatContent.kt` | batch vs. cancel-only dialog selection |
| `ui/screens/chat/ChatViewModel.kt` | `SubmitQuestion`, `submitQuestion`, `resolveQuestion`, event parse, error strings |
| `ui/screens/chat/ChatViewState.kt` | `QuestionView` projection (raw dropped) |
| `data/crush/CrushDtos.kt` | `QuestionAnswer`, `QuestionResponse` (`yes`; `notes` unused) |
| `app/src/test/java/com/crush/mobile/ui/screens/chat/` | the four test classes above |
| `v2-16-a12-gate-2026-11-23.log` | full-gate log (local, untracked) |

Paths above are relative to `app/src/main/java/com/crush/mobile/` unless absolute.

## 9. How to verify

Focused run (18 tests):

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.crush.mobile.ui.screens.chat.QuestionBatchParserTest" `
  --tests "com.crush.mobile.ui.screens.chat.QuestionDialogTest" `
  --tests "com.crush.mobile.ui.screens.chat.QuestionAnswerBuilderTest"
```

Add `--tests "com.crush.mobile.ui.screens.chat.ChatViewStateTest"` for the
view-state pin. Full gate:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

Re-read `app/build/test-results/testDebugUnitTest/*.xml` for the tally, not a
summary line.
