# V2 audit fixes — implementation record

Status: **implemented and gate-green, 2026-11-23.** This is the record for the
2026-11-23 audit-fix batch: `V2-GAP-AUDIT.md` rows A1, A3, A4, A5, A8, A10,
A11, the auto-approve confirmation (`V2-AUDIT-4`) and the A14 comment
correction. Nine production files and six JVM test classes carry it. Everything
else in the audit stays open and is listed in §5. This file is an
implementation record, not a release statement, and it does not upgrade any
device claim.

## 1. Scope

**Base:** `ced7bf6` plus this batch's working-tree diff. File paths, not
hashes, are the durable reference.

**In scope**

- one destination-selection path shared by the bottom bar, rail and extended
  rail (A1);
- copy and state corrections in Review, Projects and Onboarding (A3, A4, A5);
- the atomic engine-slot reservation and the container-side registration
  ordering it depends on (A8);
- Settings key-save result handling, delete confirmation and the
  auto-approve confirmation (A10, A11, V2-AUDIT-4);
- comment drift on the two-slot budget and the legacy chat key (A14).

**Out of scope, by decision**

- A7/A7b. Permission-policy application stays fail-open and reattach still
  replays the recorded request. The correction changes failure behaviour and
  needs explicit owner sign-off (`V2-AUDIT-0`) before it is written.
- A12 (question answering), A2 (project home as a destination), A6 (`chat` +
  `codeActive` routing) and A13 (runtime disposal).
- Any device, TalkBack, API 30+ or instrumented execution. Gate 7 is untouched.

No credential is read, written, printed or copied by this batch or this record.

## 2. Per-finding changes

| ID | Finding (audit) | What changed | Enforcement point |
|---|---|---|---|
| **A1** | Rail and extended rail discarded the Chat run-veto, so the same tap behaved differently by width | Extracted `selectPrimary(destination, nav, onSelectChat)`; the bottom bar, rail and extended rail all call it. A false callback now blocks navigation in all three, not just the bar | `app/src/main/java/com/crush/mobile/ui/navigation/CrushShell.kt` |
| **A3** | "Export a copy" hid current-vs-historical semantics | Per-file action renamed `Export a copy` → `Export current file`; added the header note "Historical turns are read-only; Export always copies the current file on disk, never that turn's snapshot." Revert stays disabled on a historical turn; export stays enabled | `app/src/main/java/com/crush/mobile/ui/screens/review/ReviewContent.kt` |
| **A4** | Projects destination explained itself twice | Removed the duplicate footer explanation. The first explanation, the in-place-edit note and every action stay | `app/src/main/java/com/crush/mobile/ui/screens/projects/ProjectsContent.kt` |
| **A5** | Onboarding promised "70+ models from a single API key", contradicting provider-scoped keys | Heading is provider-qualified and carries no catalogue number: "Connect a provider and chat with its available models" | `app/src/main/java/com/crush/mobile/ui/screens/onboarding/OnboardingContent.kt` |
| **A8** | Budget check and `STARTING` reservation were separate steps; a reservation was not counted | `requestStart` is one `synchronized(lock)` check-and-reserve. It counts other keys' live children **or** `STARTING` reservations. A terminal `Failed(willRetry=false)` releases the slot; a retrying failure keeps it. `AppContainer.requestEngineStart` calls `runtimeFor` before the gate, so a never-registered key cannot pass without recording a reservation | `app/src/main/java/com/crush/mobile/engine/EngineInstanceRegistry.kt`, `app/src/main/java/com/crush/mobile/di/AppContainer.kt` |
| **A10** | A failed key save closed the editor and discarded the draft before the write reported | `onSaveKey` now takes a result callback `(String, (Boolean) -> Unit) -> Unit`. Editor and draft stay open on failure; editor closes and draft clears only on success. Save is disabled while saving, and Cancel is disabled too. The host awaits `saveApiKeyAwait` in a `rememberCoroutineScope` | `app/src/main/java/com/crush/mobile/ui/screens/settings/SettingsContent.kt`, `SettingsScreen.kt` |
| **A11** | Key deletion had no confirmation | Delete opens "Delete saved key?", names the provider, and `onClearKey` runs only from the dialog's confirm button. Nothing is cleared until then | same two Settings files |
| **V2-AUDIT-4** | Enabling AUTO_APPROVE was a single unguarded switch tap | Turning ON asks first and names the consequences ("read, change and delete files", "run commands in its workspace"). OFF stays immediate. The switch reads the effective setting, so a cancelled tap leaves ASK rendered | same two Settings files |
| **A14** | Budget comments still said "chat + one project" | Corrected in the registry, `ChatViewModel` and `AppContainer`: two non-legacy project slots; Crush Chat runs on the legacy key, which is outside the budget | `EngineInstanceRegistry.kt`, `ui/screens/chat/ChatViewModel.kt`, `di/AppContainer.kt` |

A1's nuance from the audit still holds: the veto only suppresses a binding
reset. This batch makes that behaviour uniform across widths; it does not widen
what the veto does.

## 3. What the tests pin

Counts are `@Test` methods in source at this tree. The six classes below total
92 methods; all of them ran in the gate (§4).

| Class (total) | New or changed cases | Assertion |
|---|---:|---|
| `ShellNavigationTest` (16) | 6 new, at 411/700/1000dp | `bottom bar vetoes the Chat switch when a run is in flight`; `bottom bar opens Chat when the switch is allowed`; `rail vetoes…`; `rail opens…`; `extended rail vetoes…`; `extended rail opens…`. Each starts on Projects, taps "Crush Chat", and asserts the callback ran exactly once and the resulting route is `projects` on veto, `chat` when allowed. The layout tag (`shell-bottom-bar`, `shell-rail`, `shell-extended-rail`) must be displayed first |
| `EngineInstanceRegistryTest` (19) | 4 new | `a STARTING reservation holds its slot before any child exists` — the third key is refused with `2/2` while the accepted key's grain is `STARTING`. `a terminal start failure releases the reservation` — after `Failed(willRetry=false)` the next key is allowed, and the failed key's grain is still `STARTING` (release is a liveness read, not a grain rewrite). `a retrying start failure keeps the reservation` — refusal non-null, counts `2/2`. `simultaneous starts never both take the last non-legacy slot` — two threads behind a `CyclicBarrier`, both finish, exactly one accepted, winner holds `STARTING`, refusal names the loser and counts `2/2`, a third key is still refused |
| `SettingsContentTest` (17) | 5 new; 3 updated | `save keeps the editor and draft until the result callback succeeds` — editor still present and Save disabled while the write is pending; only a `true` result closes the editor and clears the draft. `a failed save leaves the draft and the editor in place` — a `false` result leaves the editor open with Save enabled. `delete asks for confirmation naming the provider and dismiss does nothing` — zero clears before the dialog, the provider is named, Cancel leaves the vault untouched, only confirm clears once. `auto-approve asks first names the consequences and cancels to ASK` — the tap writes nothing, the switch is off, Cancel writes nothing, confirm is the only path to `AUTO_APPROVE`. `turning auto-approve off is immediate and needs no dialog` — writes `ASK`, no dialog. Updated: the stored-key test drives `delete-key` → `confirm-delete-key`; the dispatch and switch tests use the new signature/dialog |
| `ReviewContentTest` (13) | existing cases extended, no new methods | `Export current file` exists and `Export a copy` does not; the read-only note is displayed; on a historical turn revert is disabled while export is still enabled; the export click still routes the current path |
| `ProjectsContentTest` (15) | the empty-registry case extended | The first explanation and "Crush edits the folder you choose in place." are displayed; the removed footer string ("A project opens its home…") occurs zero times |
| `OnboardingContentTest` (12) | 1 new | `the heading promises provider models without a model count` — the exact heading is displayed and `70+` is absent |

Limits of this evidence:

- A8's race test exercises the gate, not a spawned child. Child birth safety
  remains the phase-1 interlock in `CrushEngine`.
- A10 is pinned at the Content/state-callback boundary. No test forces the real
  Keystore writer to fail, so the host-wiring half is compile- and
  review-checked, not fault-injected.
- V2-AUDIT-4's tests drive the switch and the write request; no test forces a
  failed permission write and then reads the rendered switch.
- A14 is comment text; no test asserts prose.

## 4. Gate

| Task set | Result |
|---|---|
| `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease` | **BUILD SUCCESSFUL in 34m 01s**, 2026-11-23 |
| Tests | **997 tests / 104 suites / 0 failures / 0 errors / 0 skipped** |
| Lint | **0 errors / 1 pre-existing warning** |

Log: `v2-12-gate-2026-11-23.log` at the repo root. Gate logs are local-only:
`.gitignore:25` ignores `*.log`, so the claim is this record plus the XML result
files, not a committed artifact. The 997/104 tally above was re-read from
`app/build/test-results/testDebugUnitTest/*.xml` (104 suite files), which is
also where the 0/0/0 comes from.

What the gate does **not** mean:

- `:app:assembleDebugAndroidTest` compiles the instrumented APK. Compiling is
  not executing; no instrumented class ran in this batch.
- All six classes are JVM/Robolectric. No device, TalkBack or API 30+ row was
  performed, so every such row stays NOT PERFORMED.
- Gate 7 (signing, packaging, listing, submission, rollout) was not touched.

## 5. Still open

| ID | State | Why it is not here | Decision needed |
|---|---|---|---|
| **A7 / A7b** | Permission-policy apply is still fail-open; reattach still replays the request as if applied; no read-back | Would change user-visible failure behaviour | Explicit owner sign-off (`V2-AUDIT-0`): a non-2xx apply fails `open()` with a named result, `agent/init` is skipped, a failed reattach must not report attached |
| **A12** | Agent questions can only be denied/dismissed; `answerQuestions`/`cancelQuestions` transport exists | Large: needs choice/free-text UI plus resolution state | Schedule as its own milestone (`V2-AUDIT-5`) |
| **A2** | Project home is still a modal sheet, not a destination | Design change; would reopen V2-5 | Owner design pass (`V2-AUDIT-7`) |
| **A6** | The `chat` route and `codeActive` jointly decide the selected tab | Works as built; a rewrite needs the state-contract input | Monitor only |
| **A13** | The registry retains every runtime for the process lifetime; `stop()` does not remove the entry | Architecture work; needs a disposal contract and a 3-child PSS measurement | Owner decision (`V2-AUDIT-6`) |
| Device rows | Compose semantics, wide/tablet, TalkBack, API 30+ | No device run in this batch | Device batch |
| Gate 7 | Untouched | Out of V2 scope | Release milestone |

## 6. Evidence index

| Path | Role |
|---|---|
| `docs/v2/V2-GAP-AUDIT.md` | finding IDs A1-A14, verdicts, severity, batch split, reproduction backlog |
| `docs/v2/V2-FINAL-INTEGRATION-REVIEW.md` | clause-level verdicts; names the release-gating set A7/A7b and A12 after V2-12 closed A1/A8 |
| `docs/v2/V2-COMPLETION-LEDGER.md` | shipped/tested labels, gate series, the gate-logs-are-local rule |
| `ui/navigation/CrushShell.kt` | A1 `selectPrimary` |
| `ui/screens/review/ReviewContent.kt` | A3 label + read-only note |
| `ui/screens/projects/ProjectsContent.kt` | A4 duplicate explanation removed |
| `ui/screens/onboarding/OnboardingContent.kt` | A5 heading |
| `engine/EngineInstanceRegistry.kt` | A8 gate, reservation accounting, A14 KDocs |
| `di/AppContainer.kt` | A8 register-before-gate, A14 KDoc |
| `ui/screens/settings/SettingsContent.kt` | A10 result callback, A11 delete dialog, V2-AUDIT-4 confirmation |
| `ui/screens/settings/SettingsScreen.kt` | A10 coroutine host wiring |
| `ui/screens/chat/ChatViewModel.kt` | A14 comment |
| `app/src/test/java/com/crush/mobile/...` | the six classes named in §3 |
| `v2-12-gate-2026-11-23.log` | full-gate log (local, untracked) |

## 7. How to verify

Focused run of the six classes (`@Test` count in source: 92):

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.crush.mobile.ui.navigation.ShellNavigationTest" `
  --tests "com.crush.mobile.engine.EngineInstanceRegistryTest" `
  --tests "com.crush.mobile.ui.screens.settings.SettingsContentTest" `
  --tests "com.crush.mobile.ui.screens.review.ReviewContentTest" `
  --tests "com.crush.mobile.ui.screens.projects.ProjectsContentTest" `
  --tests "com.crush.mobile.ui.screens.onboarding.OnboardingContentTest"
```

Full gate, as recorded in §4:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

Read the tally from `app/build/test-results/testDebugUnitTest/*.xml` rather than
trusting Gradle stdout. The full gate took 34m01s on this box; run it detached
and poll, and do not trust a reused `run.log` (see `CLAUDE.md` rules 5 and 7).
