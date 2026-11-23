# V2 audit fixes — implementation record

Status: **implemented and gate-green, 2026-11-23.** This record now covers three
phases of the same day. **V2-12** is the authorized audit-fix subset:
`V2-GAP-AUDIT.md` rows A1, A3, A4, A5, A8, A10, A11, the auto-approve
confirmation (`V2-AUDIT-4`) and the A14 comment correction — nine production
files and six JVM test classes. **V2-13** is the A7/A7b fail-closed correction
that V2-12 deliberately held back pending owner sign-off: permission-policy
application is now a hard prerequisite of `open()`, not a logged best-effort —
two production files and one new JVM test class. **V2-14** answers agent
questions instead of only denying them: a question-batch parser, an answer
dialog and a `SubmitQuestion` path through the ViewModel (A12) — two new
production files plus the `ChatAction`/`ChatViewModel` wiring, the `ChatViewState`
projection and the `ChatContent` dialog host, and four JVM test classes. The
remaining audit rows stay open and are listed in §5. This file is an
implementation record, not a release statement, and it does not upgrade any
device claim.

## 1. Scope

**Base:** `ced7bf6` plus each phase's working-tree diff. File paths, not
hashes, are the durable reference.

**In scope — V2-12 phase (authorized subset)**

- one destination-selection path shared by the bottom bar, rail and extended
  rail (A1);
- copy and state corrections in Review, Projects and Onboarding (A3, A4, A5);
- the atomic engine-slot reservation and the container-side registration
  ordering it depends on (A8);
- Settings key-save result handling, delete confirmation and the
  auto-approve confirmation (A10, A11, V2-AUDIT-4);
- comment drift on the two-slot budget and the legacy chat key (A14).

**In scope — V2-13 phase (A7/A7b, fail-closed)**

- permission-policy application is a hard prerequisite of `open()`: a rejected
  `permissions/mode` / `permissions/skip` fails the attach instead of being
  logged and ignored (A7);
- a rejected reattach no longer reports the workspace attached, because the
  failure path flushes the id and its generation (A7b).

**In scope — V2-14 phase (A12, agent questions)**

- an agent `ask` question batch is parsed into typed questions — yes/no, single
  choice, multi choice and free text — with `choices` accepted under its
  `options` alias, at most 5 questions × 5 options, and a cancel-only fallback
  when nothing usable parses (A12);
- a question dialog collects the answers: Submit is gated, Cancel always
  releases, a failed submit keeps the dialog and shows the error, and an
  UNKNOWN-shaped prompt disables Submit rather than guessing (A12);
- `ChatAction.SubmitQuestion` → `ChatViewModel.submitQuestion` posts
  `answerQuestions`; the existing deny/dismiss path is unchanged (A12).

The A7/A7b correction was held back in V2-12 because it changes user-visible
failure behaviour and needed explicit owner sign-off (`V2-AUDIT-0`). That
authorization was given before V2-13 was written; the V2-12 gate stays recorded
in §4 as the authorized-subset baseline. A12 was held back by V2-12 as too large
and was then delivered as its own phase, V2-14.

**Out of scope, by decision**

- A2 (project home as a destination), A6 (`chat` + `codeActive` routing) and
  A13 (runtime disposal).
- Any device, TalkBack, API 30+ or instrumented execution. Gate 7 is untouched.

No credential is read, written, printed or copied by any phase or this record.

## 2. Per-finding changes

| ID | Finding (audit) | What changed | Enforcement point |
|---|---|---|---|
| **A1** | Rail and extended rail discarded the Chat run-veto, so the same tap behaved differently by width | Extracted `selectPrimary(destination, nav, onSelectChat)`; the bottom bar, rail and extended rail all call it. A false callback now blocks navigation in all three, not just the bar | `app/src/main/java/com/crush/mobile/ui/navigation/CrushShell.kt` |
| **A3** | "Export a copy" hid current-vs-historical semantics | Per-file action renamed `Export a copy` → `Export current file`; added the header note "Historical turns are read-only; Export always copies the current file on disk, never that turn's snapshot." Revert stays disabled on a historical turn; export stays enabled | `app/src/main/java/com/crush/mobile/ui/screens/review/ReviewContent.kt` |
| **A4** | Projects destination explained itself twice | Removed the duplicate footer explanation. The first explanation, the in-place-edit note and every action stay | `app/src/main/java/com/crush/mobile/ui/screens/projects/ProjectsContent.kt` |
| **A5** | Onboarding promised "70+ models from a single API key", contradicting provider-scoped keys | Heading is provider-qualified and carries no catalogue number: "Connect a provider and chat with its available models" | `app/src/main/java/com/crush/mobile/ui/screens/onboarding/OnboardingContent.kt` |
| **A7 / A7b** | Permission-policy apply was fail-open (a rejection was logged and `agent/init` still ran), and reattach replayed the recorded request as if applied | `applyPermissionPolicy` now hard-fails: it logs the named mode (`ask` or `skip=<bool>`), flushes `workspaceId` and `workspaceGeneration`, and throws `PermissionPolicyException`. `open()` lets it escape **before** `agent/init`, so no agent runs under an unknown effective mode. The lease timestamp is set only after the policy is in force, so a later non-forced reattach cannot short-circuit past the retry. `reattachIfStale` returns false on rejection (the flush has already dropped the id). `ChatViewModel.bootstrap` catches the exception and sets a named phase with `engineReady=false`, so nothing is sent | `app/src/main/java/com/crush/mobile/data/crush/WorkspaceManager.kt`, `app/src/main/java/com/crush/mobile/ui/screens/chat/ChatViewModel.kt` |
| **A8** | Budget check and `STARTING` reservation were separate steps; a reservation was not counted | `requestStart` is one `synchronized(lock)` check-and-reserve. It counts other keys' live children **or** `STARTING` reservations. A terminal `Failed(willRetry=false)` releases the slot; a retrying failure keeps it. `AppContainer.requestEngineStart` calls `runtimeFor` before the gate, so a never-registered key cannot pass without recording a reservation | `app/src/main/java/com/crush/mobile/engine/EngineInstanceRegistry.kt`, `app/src/main/java/com/crush/mobile/di/AppContainer.kt` |
| **A10** | A failed key save closed the editor and discarded the draft before the write reported | `onSaveKey` now takes a result callback `(String, (Boolean) -> Unit) -> Unit`. Editor and draft stay open on failure; editor closes and draft clears only on success. Save is disabled while saving, and Cancel is disabled too. The host awaits `saveApiKeyAwait` in a `rememberCoroutineScope` | `app/src/main/java/com/crush/mobile/ui/screens/settings/SettingsContent.kt`, `SettingsScreen.kt` |
| **A11** | Key deletion had no confirmation | Delete opens "Delete saved key?", names the provider, and `onClearKey` runs only from the dialog's confirm button. Nothing is cleared until then | same two Settings files |
| **A12** | Agent questions could only be denied or dismissed; the `answerQuestions`/`cancelQuestions` transport existed but nothing built an answer body | `QuestionBatch.kt` parses an `ask` batch into four typed kinds — yes/no, single choice, multi choice and free text — accepting the option list under `choices` or its `options` alias, capping at 5 questions × 5 options, and degrading to a cancel-only batch when nothing usable parses. `QuestionAnswerBuilder` turns the drafts into the wire body. `QuestionDialog.kt` collects them: Submit is gated until the required answers exist, Cancel is always enabled and releases the turn, a failed submit keeps the dialog and surfaces `questionError`, and an UNKNOWN-shaped prompt disables Submit instead of guessing. `ChatAction.SubmitQuestion` → `ChatViewModel.submitQuestion` posts `answerQuestions`; `ChatViewState` projects the sanitized batch (raw payload dropped) and `questionError`, and `ChatContent` hosts the dialog. The deny/dismiss path is unchanged | `app/src/main/java/com/crush/mobile/ui/screens/chat/QuestionBatch.kt`, `.../chat/QuestionDialog.kt`, `.../chat/ChatAction.kt`, `.../chat/ChatViewModel.kt`, `.../chat/ChatViewState.kt`, `.../chat/ChatContent.kt` |
| **V2-AUDIT-4** | Enabling AUTO_APPROVE was a single unguarded switch tap | Turning ON asks first and names the consequences ("read, change and delete files", "run commands in its workspace"). OFF stays immediate. The switch reads the effective setting, so a cancelled tap leaves ASK rendered | same two Settings files |
| **A14** | Budget comments still said "chat + one project" | Corrected in the registry, `ChatViewModel` and `AppContainer`: two non-legacy project slots; Crush Chat runs on the legacy key, which is outside the budget | `EngineInstanceRegistry.kt`, `ui/screens/chat/ChatViewModel.kt`, `di/AppContainer.kt` |

A1's nuance from the audit still holds: the veto only suppresses a binding
reset. V2-12 makes that behaviour uniform across widths; it does not widen what
the veto does.

A7 has no effective-mode read-back. A 2xx on `permissions/mode` /
`permissions/skip` is the only proof the policy is in force: the fix makes a
rejection loud and unmissable, but it cannot detect an engine that accepts the
request and ignores it. `applyPermissionPolicy` also uses
`runCatchingNonCancellation`, so a cancelled caller aborts instead of being
converted into a policy failure.

## 3. What the tests pin

Counts are `@Test` methods in source at this tree. The six V2-12 classes total
92 methods, V2-13 adds 4, and V2-14 adds 18 new methods across three new classes
plus one new case in `ChatViewStateTest` (18 → 19). The eleven classes below
therefore hold **115 unique methods**; all of them ran in the gate (§4). The gate
delta V2-13 → V2-14 is 19 methods and 3 suites, exactly the 18 new + 1 changed
method and the three new classes. `BootstrapOrderTest` (9) is unchanged: 9
cases, still green in the V2-14 gate.

| Class (total) | New or changed cases | Assertion |
|---|---:|---|
| `ShellNavigationTest` (16) | 6 new, at 411/700/1000dp | `bottom bar vetoes the Chat switch when a run is in flight`; `bottom bar opens Chat when the switch is allowed`; `rail vetoes…`; `rail opens…`; `extended rail vetoes…`; `extended rail opens…`. Each starts on Projects, taps "Crush Chat", and asserts the callback ran exactly once and the resulting route is `projects` on veto, `chat` when allowed. The layout tag (`shell-bottom-bar`, `shell-rail`, `shell-extended-rail`) must be displayed first |
| `EngineInstanceRegistryTest` (19) | 4 new | `a STARTING reservation holds its slot before any child exists` — the third key is refused with `2/2` while the accepted key's grain is `STARTING`. `a terminal start failure releases the reservation` — after `Failed(willRetry=false)` the next key is allowed, and the failed key's grain is still `STARTING` (release is a liveness read, not a grain rewrite). `a retrying start failure keeps the reservation` — refusal non-null, counts `2/2`. `simultaneous starts never both take the last non-legacy slot` — two threads behind a `CyclicBarrier`, both finish, exactly one accepted, winner holds `STARTING`, refusal names the loser and counts `2/2`, a third key is still refused |
| `SettingsContentTest` (17) | 5 new; 3 updated | `save keeps the editor and draft until the result callback succeeds` — editor still present and Save disabled while the write is pending; only a `true` result closes the editor and clears the draft. `a failed save leaves the draft and the editor in place` — a `false` result leaves the editor open with Save enabled. `delete asks for confirmation naming the provider and dismiss does nothing` — zero clears before the dialog, the provider is named, Cancel leaves the vault untouched, only confirm clears once. `auto-approve asks first names the consequences and cancels to ASK` — the tap writes nothing, the switch is off, Cancel writes nothing, confirm is the only path to `AUTO_APPROVE`. `turning auto-approve off is immediate and needs no dialog` — writes `ASK`, no dialog. Updated: the stored-key test drives `delete-key` → `confirm-delete-key`; the dispatch and switch tests use the new signature/dialog |
| `ReviewContentTest` (13) | existing cases extended, no new methods | `Export current file` exists and `Export a copy` does not; the read-only note is displayed; on a historical turn revert is disabled while export is still enabled; the export click still routes the current path |
| `ProjectsContentTest` (15) | the empty-registry case extended | The first explanation and "Crush edits the folder you choose in place." are displayed; the removed footer string ("A project opens its home…") occurs zero times |
| `OnboardingContentTest` (12) | 1 new | `the heading promises provider models without a model count` — the exact heading is displayed and `70+` is absent |
| `WorkspacePermissionPolicyTest` (4) | 4 new (V2-13) | `rejected mode policy fails the open and never initialises the agent` — a 500 on `permissions/mode` throws `PermissionPolicyException` naming `ask`, no `/agent/init` request is recorded, and the workspace id is flushed. `rejected legacy skip policy fails the open and never initialises the agent` — same for `permissions/skip`, with the label starting `skip=`. `accepted policy still runs agent init and keeps the workspace` — a 2xx apply still reaches `/agent/init` and keeps the id, so the gate is not over-broad. `a failed reattach reports not attached and flushes the id` — after a successful open, a rejected forced reattach returns false and leaves no id |
| `QuestionBatchParserTest` (8, V2-14) | 8 new | `a full batch parses every question kind and its choices` — yes/no, single, multi and free text all survive with their ids. `the legacy options alias is accepted` — an `options` list parses like `choices`. `a choice question with no choices is unanswerable and stays UNKNOWN`, and `an unknown type stays UNKNOWN rather than guessed` — the parser never invents an answer shape. `a missing batch id cannot be answered and returns null`, `no questions returns null`, `a prompt without an id is skipped, and an all-skipped batch is null` — unusable input degrades to no batch (cancel-only) instead of a half-valid one. `the parser caps prompts and choices to the engine maxima` — 5 × 5 |
| `QuestionDialogTest` (6, V2-14) | 6 new | `single choice submits the selected id`; `multi choice submits every selected id`; `yes no submits the boolean` — each kind reaches the callback in the right shape. `submit stays disabled until every prompt is answered` — partial answers cannot be sent. `cancel releases and never submits` — Cancel always works and produces no answer. `an unanswerable prompt disables submit but cancel remains` — an UNKNOWN prompt can be dismissed but not answered |
| `QuestionAnswerBuilderTest` (4, V2-14) | 4 new | `a single and multi choice become selected ids in prompt order` — selection order is normalised to prompt order. `free text is trimmed and a blank answer is omitted` — whitespace-only text is not sent. `yes no keeps false as a real answer` — `false` is not dropped as falsy. `an empty selection list is omitted rather than sent as empty` — an unanswered optional list is absent, not `[]` |
| `ChatViewStateTest` (19, +1 V2-14) | 1 new | `a parsed question batch survives projection while the raw payload is dropped` — the projected state keeps the parsed batch (so the dialog can answer it) and does not carry the raw payload into the UI state |

Limits of this evidence:

- A8's race test exercises the gate, not a spawned child. Child birth safety
  remains the phase-1 interlock in `CrushEngine`.
- A10 is pinned at the Content/state-callback boundary. No test forces the real
  Keystore writer to fail, so the host-wiring half is compile- and
  review-checked, not fault-injected.
- V2-AUDIT-4's tests drive the switch and the write request; no test forces a
  failed permission write and then reads the rendered switch.
- A14 is comment text; no test asserts prose.
- A7/A7b is pinned at the `WorkspaceManager` boundary against a path-keyed
  MockWebServer. That proves a rejection is fatal and that `agent/init` is
  skipped; it does not prove a real engine honours an accepted mode, because
  there is no effective-mode read-back. No device run covers A7.
- A12's free-text field is not Compose-tested: Robolectric cannot drive the
  text cursor loop reliably. The wire mapping for free text is covered by
  `QuestionAnswerBuilderTest`, so the untested part is the field interaction,
  not the payload.
- A12 has no process-death persistence: a pending batch lives in `ChatUiState`
  only, so a killed process loses the question rather than re-presenting it.
- No device run covers A12: submit/cancel were not exercised against a real
  engine, and no TalkBack traversal of the dialog was performed.

## 4. Gate

**V2-14 gate (current)** — 2026-11-23:

| Task set | Result |
|---|---|
| `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease` | **BUILD SUCCESSFUL in 17m 07s**, 2026-11-23 |
| Tests | **1020 tests / 108 suites / 0 failures / 0 errors / 0 skipped** |
| Lint | **0 errors / 1 pre-existing warning** |

Log: `v2-16-a12-gate-2026-11-23.log` at the repo root. Gate logs are local-only:
`.gitignore:25` ignores `*.log`, so the claim is this record plus the XML result
files, not a committed artifact. The 1020/108 tally was re-read from
`app/build/test-results/testDebugUnitTest/*.xml` (108 suite files), which is
also where the 0/0/0 comes from. The delta against V2-13 is 19 tests and 3
suites: 1020 − 1001 and 108 − 105, matching the 18 new question cases + the one
changed `ChatViewStateTest` case, and the three new classes.

**V2-13 gate (history, superseded)** — the A7/A7b fail-closed baseline:

| Task set | Result |
|---|---|
| same task set | **BUILD SUCCESSFUL in 33m 38s**, 2026-11-23 |
| Tests | **1001 tests / 105 suites / 0 failures / 0 errors / 0 skipped** |
| Lint | **0 errors / 1 pre-existing warning** |

Log: `v2-14-a7-gate-2026-11-23.log` at the repo root (local, untracked). Its
delta against V2-12 was exactly the new class: 1001 − 997 = 4 tests and
105 − 104 = 1 suite.

**V2-12 gate (history, superseded)** — kept as the authorized-subset baseline:

| Task set | Result |
|---|---|
| same task set | **BUILD SUCCESSFUL in 34m 01s**, 2026-11-23 |
| Tests | **997 tests / 104 suites / 0 failures / 0 errors / 0 skipped** |
| Lint | **0 errors / 1 pre-existing warning** |

Log: `v2-12-gate-2026-11-23.log` at the repo root (local, untracked).

What the gate does **not** mean:

- `:app:assembleDebugAndroidTest` compiles the instrumented APK. Compiling is
  not executing; no instrumented class ran in any of the three phases.
- Every class in §3 is JVM/Robolectric. No device, TalkBack or API 30+ row was
  performed, so every such row stays NOT PERFORMED.
- Gate 7 (signing, packaging, listing, submission, rollout) was not touched.

## 5. Still open

| ID | State | Why it is not here | Decision needed |
|---|---|---|---|
| **A2** | Project home is still a modal sheet, not a destination | Design change; would reopen V2-5 | Owner design pass (`V2-AUDIT-7`) |
| **A6** | The `chat` route and `codeActive` jointly decide the selected tab | Works as built; a rewrite needs the state-contract input | Monitor only |
| **A13** | The registry retains every runtime for the process lifetime; `stop()` does not remove the entry | Architecture work; needs a disposal contract and a 3-child PSS measurement | Owner decision (`V2-AUDIT-6`) |
| Device rows | Compose semantics, wide/tablet, TalkBack, API 30+ | No device run in any phase | Device batch |
| Gate 7 | Untouched | Out of V2 scope | Release milestone |

A12 closed in V2-14 and is no longer an open row; its remaining gaps are the
limits in §3, not unfinished scope.

## 6. Evidence index

| Path | Role |
|---|---|
| `docs/v2/V2-GAP-AUDIT.md` | finding IDs A1-A14, verdicts, severity, batch split, reproduction backlog |
| `docs/v2/V2-A7-PERMISSION-FAIL-CLOSED.md` | V2-13 phase record: A7/A7b fail-closed behaviour, enforcement points and its own gate |
| `docs/v2/V2-A12-QUESTION-ANSWERING.md` | V2-14 phase record: question parsing, answer dialog, wire body, limits and its own gate |
| `docs/v2/V2-FINAL-INTEGRATION-REVIEW.md` | clause-level verdicts; names the release-gating set A7/A7b and A12 after V2-12 closed A1/A8 (A7/A7b since closed by V2-13, A12 by V2-14) |
| `docs/v2/V2-COMPLETION-LEDGER.md` | shipped/tested labels, gate series, the gate-logs-are-local rule |
| `ui/navigation/CrushShell.kt` | A1 `selectPrimary` |
| `ui/screens/review/ReviewContent.kt` | A3 label + read-only note |
| `ui/screens/projects/ProjectsContent.kt` | A4 duplicate explanation removed |
| `ui/screens/onboarding/OnboardingContent.kt` | A5 heading |
| `engine/EngineInstanceRegistry.kt` | A8 gate, reservation accounting, A14 KDocs |
| `di/AppContainer.kt` | A8 register-before-gate, A14 KDoc |
| `ui/screens/settings/SettingsContent.kt` | A10 result callback, A11 delete dialog, V2-AUDIT-4 confirmation |
| `ui/screens/settings/SettingsScreen.kt` | A10 coroutine host wiring |
| `data/crush/WorkspaceManager.kt` | A7/A7b hard-fail, id+generation flush, lease-after-policy ordering |
| `ui/screens/chat/ChatViewModel.kt` | A14 comment; A7 named bootstrap phase with `engineReady=false`; A12 `submitQuestion` → `answerQuestions`, `questionError` on failure |
| `ui/screens/chat/QuestionBatch.kt` | A12 parser/model for yes/no, single, multi and free text, 5×5 cap, cancel-only fallback, `QuestionAnswerBuilder` wire body |
| `ui/screens/chat/QuestionDialog.kt` | A12 answer dialog: submit gating, always-enabled Cancel, error text, UNKNOWN disables Submit |
| `ui/screens/chat/ChatAction.kt` | A12 `SubmitQuestion(answers)` action |
| `ui/screens/chat/ChatViewState.kt` | A12 `QuestionView.batch` projection (raw payload dropped) and `GateView.questionError` |
| `ui/screens/chat/ChatContent.kt` | A12 dialog host: renders the batch, passes `questionError`, wires submit and cancel |
| `app/src/test/java/com/crush/mobile/...` | the eleven classes named in §3 |
| `app/src/test/java/com/crush/mobile/data/crush/WorkspacePermissionPolicyTest.kt` | V2-13 A7/A7b pins (4) |
| `app/src/test/java/com/crush/mobile/ui/screens/chat/QuestionBatchParserTest.kt` | V2-14 parser pins (8) |
| `app/src/test/java/com/crush/mobile/ui/screens/chat/QuestionDialogTest.kt` | V2-14 dialog pins (6) |
| `app/src/test/java/com/crush/mobile/ui/screens/chat/QuestionAnswerBuilderTest.kt` | V2-14 wire-body pins (4) |
| `app/src/test/java/com/crush/mobile/ui/screens/chat/ChatViewStateTest.kt` | V2-14 projection pin (1 new of 19) |
| `v2-12-gate-2026-11-23.log` | V2-12 full-gate log (local, untracked) |
| `v2-14-a7-gate-2026-11-23.log` | V2-13 full-gate log (local, untracked) |
| `v2-16-a12-gate-2026-11-23.log` | V2-14 full-gate log (local, untracked) |

## 7. How to verify

Focused run of the six V2-12 classes (`@Test` count in source: 92):

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.crush.mobile.ui.navigation.ShellNavigationTest" `
  --tests "com.crush.mobile.engine.EngineInstanceRegistryTest" `
  --tests "com.crush.mobile.ui.screens.settings.SettingsContentTest" `
  --tests "com.crush.mobile.ui.screens.review.ReviewContentTest" `
  --tests "com.crush.mobile.ui.screens.projects.ProjectsContentTest" `
  --tests "com.crush.mobile.ui.screens.onboarding.OnboardingContentTest"
```

A7/A7b focused run (V2-13: 4 new, plus the 9 unchanged `BootstrapOrderTest`
cases that cover the wire order around the new hard failure):

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.crush.mobile.data.crush.WorkspacePermissionPolicyTest" `
  --tests "com.crush.mobile.data.crush.BootstrapOrderTest"
```

A12 focused run (V2-14: the three new question classes, 18 cases — 8 + 6 + 4.
The one changed `ChatViewStateTest` case runs in the full gate, where the whole
19-method class is exercised):

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.crush.mobile.ui.screens.chat.QuestionBatchParserTest" `
  --tests "com.crush.mobile.ui.screens.chat.QuestionDialogTest" `
  --tests "com.crush.mobile.ui.screens.chat.QuestionAnswerBuilderTest"
```

Full gate, as recorded in §4:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

Read the tally from `app/build/test-results/testDebugUnitTest/*.xml` rather than
trusting Gradle stdout. The V2-14 gate took 17m07s on this box (V2-13 took
33m38s, V2-12 34m01s); run it detached and poll, and do not trust a reused
`run.log` (see `CLAUDE.md` rules 5 and 7).
