# A2 slice 1 — Stage 2 completion report (2026-11-28)

Baseline: `b72e5725ce485c679c6c7ebc3e9947b2de0b994f`, branch
`v2-16-planning-baseline`. Authorized scope: W1 terminal semantics, W2 failure
durability, W3 loss-safe replay, and the run-declaration lifecycle correction.
A1 remains accepted; A2 slice 1 remains **unaccepted** pending owner review.

## 1. Files changed

Production (9):
- `execution/ExecutionModels.kt` — `ExecutionEvent.executionId` and `superseded`;
  `ExecutionView.isCancelled`; typed `ConfirmedApply` result.
- `execution/ExecutionEventAdapter.kt` — declaration record with ACTIVE /
  SUPERSEDED / RETIRED states, bounded read-only history, `resolveDeclaration`
  exact lookup, `retireRun`, no latest-run fallback, event identity from the
  declaration.
- `execution/ExecutionDao.kt` — `byExecutionAndRun` plus guarded exact-pair
  writes: `markRunningExact`, `markTerminalExact`, `markFailedExact`,
  `correctGenericCompletionToFailed`.
- `execution/ExecutionStore.kt` — classified `applyConfirmedEvent` /
  `applyConfirmedFailure`, `NotApplicable` for healthy post-RUNNING progress,
  no one-sided generation skip.
- `execution/ConfirmedEventBuffer.kt` — `peek` + head-only `remove`; `drain`
  retained for legacy tests only.
- `di/AppContainer.kt` — typed callback/replay handling, no post-ACK
  `forgetRun`, bounded retry, explicit invalidation, cancellation propagation.
- `ui/screens/chat/ChatViewModel.kt` — explicit ids at declare; per-turn
  `finishError` write with exact identity.
- `ui/screens/chat/ChatViewState.kt` — cancelled outcome label.
- `execution/ExecutionController.kt` — terminal reason from `event.detail`.

Tests (4): `ExecutionTerminalPersistenceTest`, `ConfirmedEventBufferTest`,
`ExecutionEventAdapterTest` (declare signature), `ChatViewStateTest`, plus new
`RunDeclarationLifecycleTest`.

Diff summary: 13 tracked files changed, 654 insertions / 158 deletions; 1 new
test class and 9 archived artifacts.

## 2. Exact-identity contract

Every event write uses `execution_id + run_id + engine_generation` together.
`ExecutionEvent.executionId` is supplied by the declaration, never inferred from
the active execution; no latest-run or single-run fallback exists
(`ExecutionEventAdapter.kt` `resolveDeclaration`). All generation comparisons
fail closed when either side is null. A superseded or retired declaration can
resolve an old frame to its **own** execution but classifies it `Stale`; it
cannot authorize a mutation. `run_id` uniqueness is a UUID convention, not a DB
invariant; no migration was added.

## 3. Behaviour by workstream

- **W1:** confirmed cancellation keeps `phase=COMPLETED` (approved contract)
  with `terminal_reason=confirmed_cancelled`; `ExecutionView.isCancelled` and
  the UI render "Cancelled", never "completed". No `CANCELLED` enum value, no
  migration.
- **W2:** a confirmed `finishError` observed on the per-turn stream writes
  durable `FAILED` with the exact reason (fallback
  `"provider error with no message"`). A narrowly guarded correction
  `COMPLETED(confirmed_completion) → FAILED` requires exact execution/run/
  generation, ACKED state and unchanged reason; it cannot touch cancellation,
  an existing failure or another terminal state. Adapter-only MESSAGE errors
  without proven identity stay `UNATTRIBUTED` and write nothing.
- **W3:** typed `ConfirmedApply` outcomes; `peek` before apply, head removed
  only on `Applied`/`AlreadyApplied`/`NotApplicable`; `Deferred`/`Unavailable`
  keep evidence pending with bounded retry; `Stale`/`Conflict`/
  `IntegrityFailure` explicitly invalidate with a guarded, checked write;
  `CancellationException` propagates and is never converted to a DB failure.

## 4. Tests and evidence

- Targeted: `gradlew.bat :app:testDebugUnitTest --tests 'com.crush.mobile.execution.*' --tests 'com.crush.mobile.ui.screens.chat.ChatViewStateTest' --tests 'com.crush.mobile.ui.screens.chat.ChatListContentTest'` — `BUILD SUCCESSFUL in 18m 38s`; **110 tests, 0 failures**.
- Full gate: `gradlew.bat :app:testDebugUnitTest` with JBR 25 — `BUILD SUCCESSFUL in 30m 18s`; **128 suites / 1181 tests / 0 failures / 0 errors / 0 skipped** (previous baseline 127/1169; +12 tests, none removed or disabled).
- Archived: `a2s2-fullgate-run.txt` (raw log) and eight XMLs `a2s2-*-junit.xml`
  in `docs/parity/v2-16-phase0/`. Their sum is 83 tests; the full-gate total is
  from Gradle output and is not fully re-derivable from the subset.
- Tests call the real store/DAO/adapter/controller functions. No sleeps, no
  timing-dependent races.

## 5. Remaining limitations

- **Process-death pre-ACK durability** is not implemented and not claimed.
- **No device evidence**, no R37 fault injection, no atomic validate-and-attach.
- **No GitHub Actions** run exists for this branch; no CI claim is made.
- Adapter-only `finishError` remains unattributed and undurable by design.
- `run_id` is not UNIQUE; exact-pair identity is the guard, not a DB constraint.
- Full-gate counts are not fully auditable from the archived XML subset.

## 6. Worktree and authorization

Branch `v2-16-planning-baseline`, HEAD still `b72e572`; remote unchanged.
Worktree contains the changes above plus the pre-existing untracked
`v2-66-phaseF-stuck.png` (untouched). **No commit, push, PR, merge or release
was performed.** A2 slice 1 is not accepted by this report.
