# V2-15 Phase E — question-answering verification

Status: **verification complete, 2026-11-24.** Scope is the existing V2-14 A12
question-answering implementation. No production change was made; one Compose
regression test was added for batch replacement. No device, instrumented or
TalkBack run. Findings E1/E2 below are source-inspected lifecycle risks, not
fixed in this phase. Phase E is committed on `v2-15-lifecycle-hardening` as a
follow-up to `878afa9`; no PR or issue was made.

## 1. Verdict

| Contract | Verdict | Evidence |
|---|---|---|
| Engine question payload parses defensively | **Holds** | `QuestionBatchParserTest` 8; §2 |
| The UI can only submit parsed choice ids | **Holds for the normal UI** | `QuestionDialogTest` 7; parser drops choice-less/unknown prompts |
| Submit is gated on every prompt | **Holds** | `QuestionDialogTest`; §2 |
| Cancel/dismiss never submits a partial batch | **Holds** | `QuestionDialogTest` cancel; `ChatContent` routes dismiss to ReleaseQuestion |
| A new batch does not inherit the old batch's draft | **Holds** | New `QuestionDialogTest` case; §5 |
| Answer wire body has the exact batch/question ids and omits blanks | **Holds** | `QuestionAnswerBuilderTest` 4; §2 |
| Failed answer/cancel keeps the dialog open with an error | **Holds by source inspection** | `ChatViewModel.submitQuestion`/`resolveQuestion`; no JVM ViewModel harness |
| A stale answer success cannot clear a newer question | **Open risk (E1)** | §4; unconditional clear on success |
| Duplicate Submit/Cancel cannot race | **Open risk (E2)** | §4; no in-flight guard or disabled state |
| Questions are app-side scoped to the viewed session/run | **Partial** | §4 E3; ownership relies on the engine's current-session scoping |
| Engine restart/reattach leaves no stale question | **Open limitation (E4)** | §4; no generation/session identity in `PendingQuestion` |
| Pending question survives process death | **No** | §6 |
| Effective answer acceptance can be read back | **No** | §6; a 2xx is the only proof |

## 2. Implementation trace

1. `QUESTION_REQUEST` → `QuestionBatchParser.parse(ev.body)` in
   `ChatViewModel` (`:4614-4631`). The raw body is kept only in
   `PendingQuestion`; the view-state projection drops it.
2. `QuestionBatchParser` (`QuestionBatch.kt`) accepts
   `yes_no`/`single_choice`/`multi_choice`/`free_text`, the `options` alias,
   caps at 5 prompts × 5 choices, and returns `null` for an unparseable batch.
   A choice prompt without choices becomes `UNKNOWN`; an unknown type becomes
   `UNKNOWN`. It never invents a choice id.
3. `AnswerQuestionDialog` (`QuestionDialog.kt`) builds drafts only from parsed
   ids. Submit is enabled only when every prompt has a usable draft; Cancel and
   dismiss call `ReleaseQuestion`; `UNKNOWN` blocks Submit.
4. `submitQuestion` (`ChatViewModel.kt:2717`) requires an open
   `pendingQuestion`, a parsed batch, and `answers.size == prompts.size`,
   re-attaches, requires a workspace id, builds `QuestionAnswer` with
   `QuestionAnswerBuilder`, and POSTs `/questions/answer`. Success clears the
   batch; failure keeps it and sets `questionError`.
5. `resolveQuestion` (`:2669`) re-attaches and POSTs `/questions/cancel`; it
   clears only on success.
6. `QuestionAnswerBuilder` serializes `batch_request_id`, one response per
   draft, selected ids, trimmed non-blank free text, and `yes = false` when
   false; empty/blank fields are omitted.

## 3. What the existing tests prove

| Class | Tests | Contract |
|---|---:|---|
| `QuestionBatchParserTest` | 8 | happy path, `options` alias, missing batch id/questions, skipped prompt, empty-choice downgrade, caps, unknown type |
| `QuestionDialogTest` | 7 | single/multi/yes-no submission, disabled Submit, cancel, UNKNOWN, batch replacement resets the draft |
| `QuestionAnswerBuilderTest` | 4 | selected ids, free-text trim/blank omission, `yes = false`, empty-selection omission |
| `ChatViewStateTest` | 19 | includes the batch projection into `QuestionView` |

Focused run: **38 tests / 4 classes / 0 failures in 1m16s**
(`v2-42-phaseE-focused-final.log`; the dialog class alone is
`v2-41-phaseE-dialog.log`, 7/7).

The free-text field still has no Compose test: Robolectric never reaches idle
while the cursor animates. The wire mapping is covered; the field itself is
not. This is the same known limit recorded in `V2-A12-QUESTION-ANSWERING.md`.

## 4. Source-level lifecycle findings

### E1 — a stale answer success can clear a newer batch (open)

`submitQuestion` captures `q`/`batch`, suspends through reattach and POST, then
unconditionally does:

```kotlin
_state.update { it.copy(pendingQuestion = null, questionError = null) }
```

If the engine emits the next `QUESTION_REQUEST` while the answer POST is in
flight, that new `pendingQuestion` is replaced before the old success runs; the
old success then clears the **new** batch. The user loses a live question and
the run can block until the idle timeout. `resolveQuestion` has the same
unconditional clear. The answer body still carries the old batch id, so this is
a client-state race, not an answer to the wrong batch. There is no JVM
`ChatViewModel` harness, so this is source-inspected rather than test-pinned.
The narrow future guard is an identity check before clearing, e.g. clear only
when `pendingQuestion?.id == q.id` (and keep a per-batch in-flight flag).

### E2 — duplicate Submit/Cancel is not gated (open)

`submitQuestion` and `resolveQuestion` read `pendingQuestion` and launch a
coroutine; neither marks the batch in flight, and `AnswerQuestionDialog` leaves
Submit enabled while the POST is pending. Two taps can launch two POSTs. The
second success can clear a batch that arrived in between, and a Cancel can race
an Answer. The UI has no `submitting`/`cancelling` state. This is source-inspected;
no ViewModel or multi-tap Compose test covers it.

### E3 — session/run ownership is engine-side only (partial)

`PendingQuestion` carries only `id`, `raw` and the parsed `batch`; it drops
`session_id` and `tool_call_id`. The event handler accepts any question event
on the workspace stream. Ownership relies on the engine's v0.98.2
current-session scoping: `reportCurrentSession` is fire-and-forget with a
bounded retry, and on failure or old engines the app deliberately falls back to
workspace broadcast. There is therefore no app-side check that a question
belongs to the session currently displayed. This is a documented compatibility
fallback, not a new regression; it means "question shown" is not proof that the
question belongs to the visible conversation.

### E4 — engine restart/reattach can leave an unanswerable dialog (open)

A pending question has no engine-generation identity. After an engine restart,
`submitQuestion`/`resolveQuestion` reattach to the new workspace and POST the
old batch id; the engine may reject it, the dialog stays open with an error,
and Cancel may clear it without releasing a real run. There is no automatic
dismissal on generation change. This is a UX/lifecycle gap, not a credential or
policy bypass.

### E5 — answer validation is structural, not id-set validation

`submitQuestion` checks the response **count**, not that every prompt id is
present exactly once or that each selected id belongs to that prompt. The UI is
the invariant: `AnswerQuestionDialog` builds drafts only from parsed prompts and
choices, and `UNKNOWN` produces no draft. A caller constructing
`ChatAction.SubmitQuestion` directly could send non-conforming ids; that action
is internal to the Compose surface, not an external API.

## 5. Batch replacement test

`QuestionDialogTest` now includes:

`a replacement batch starts with no selection from the old batch`

It renders batch 1, selects `A.kt`, asserts Submit enabled, swaps to a new
`QuestionView` with a new batch id, and asserts Submit is disabled again while
no action was emitted. This pins the UI's `remember(view.id)` scoping. It does
not cover the E1 ViewModel race (a new batch arriving during an in-flight POST).

## 6. Limits retained

- No effective-answer read-back: a 2xx on `/questions/answer` is the only proof
  the engine accepted the ids.
- No process-death persistence for a pending batch; the blocked run reaches its
  idle timeout.
- No device, instrumented, TalkBack or API 30+ run covers the dialog, radio/
  checkbox semantics, focus order or free text.
- Free-text field is not Compose-tested (Robolectric cursor loop).
- No `notes` UI (the answer field exists in the wire model but is unused).
- E1/E2 remain unfixed in this verification phase; no production change was
  made.

## 7. Evidence

| Path / run | Role |
|---|---|
| `v2-42-phaseE-focused-final.log` | 38 focused A12 tests green (1m16s) |
| `v2-41-phaseE-dialog.log` | 7 dialog tests green after the batch-replacement test |
| `v2-38-reattach-gate.log` | last full gate, **1061/115/0**, lint 0/1; predates the new test (no production change, full gate not rerun) |
| `app/src/main/java/.../QuestionBatch.kt` | parser and builder |
| `app/src/main/java/.../QuestionDialog.kt` | answer dialog and draft scoping |
| `app/src/main/java/.../ChatViewModel.kt` | event handling, submit/cancel, clear points |
| `app/src/main/java/.../ChatViewState.kt` | `QuestionView` projection |
| `app/src/test/java/.../Question*Test.kt` | parser/dialog/builder tests |

## 8. State

No production code changed; only `QuestionDialogTest` gained the batch-replacement
case and the reports were updated. Branch `v2-15-lifecycle-hardening` remains at
`878afa9` history; the Phase E test/report changes are committed on the same
branch as a follow-up. No PR or issue was made. No device verification is
claimed from these JVM tests.
