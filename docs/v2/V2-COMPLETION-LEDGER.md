# V2 completion ledger

Status: **record of what is shipped, tested, seen on device and still open**, at
tree `225ce94` plus the uncommitted V2-11 batch (three new JVM test classes,
docs, `tools/perf-baseline.ps1`). For each package, "complete" means the exit
criteria that package wrote for itself were met **for the phone scope recorded
in its own file**, and every claim below carries a path. It does not mean every
check exists: rows that were not run say NOT PERFORMED and stay that way. Gate 7
(signing, packaging, listing, submission, rollout) is a separate milestone and
is out of scope for everything here.

The **V2-12** audit-fix batch (2026-11-23) is appended as its own row in §2 and
its own line in §4; its four-task gate ran green at 997 tests / 104 suites / 0
failures (`v2-12-gate-2026-11-23.log`). The **V2-13** permission fail-closed
batch (2026-11-23, owner-authorized) is appended the same way; its gate ran green
at 1001 tests / 105 suites / 0 failures (`v2-14-a7-gate-2026-11-23.log`). The
V2-11 and V2-12 rows stay as written. The **V2-15** lifecycle batch
(2026-11-24, owner-authorized, committed as `1990118` on
`v2-15-lifecycle-hardening`, not merged) is appended as its own row in §2 and
its own line in §4.

## 1. Reading guide for the five evidence labels

| Label | Means | Requires |
|---|---|---|
| **SHIPPED** | the code is in `app/src/main` and is what a release build would run | a file path or package+screen; no test implied |
| **JVM-TESTED** | a JVM/Robolectric test class pins the behaviour **and** a recorded gate ran it green | class name + the package record that states the green gate (a class with no recorded green run is "added, result pending") |
| **INSTRUMENTED-TESTED** | an `app/src/androidTest` class was executed on a device and its output was stored | class + device id + stored output under `docs/parity/` |
| **DEVICE-VERIFIED** | a capture in `docs/parity/<run>/` shows the behaviour, and that run's `MANIFEST.md` names the capture | parity directory + capture number |
| **DEFERRED / NOT PERFORMED** | either not built by decision, or built and never measured | the reason, and the ADR id when it is a decision |

Rules that hold across this ledger:

- Labels never upgrade each other. SHIPPED is not JVM-TESTED; JVM-TESTED is not
  DEVICE-VERIFIED.
- Gate logs (`v2-*.log` at the repo root) are **local-only**: `.gitignore:25`
  ignores `*.log` and `git ls-files "*.log"` returns nothing. A gate claim is
  therefore a tracked package record asserting a count and naming the local log,
  not a committed artifact. The parity PNG/text evidence **is** tracked.
- "Added, result pending" is the only label used for a test class that exists but
  has no recorded green run.

## 2. Per-package ledger

| Package | Shipped | JVM-tested | Instrumented | Device-verified | Deferred / NOT PERFORMED | Evidence path |
|---|---|---|---|---|---|---|
| **V2-0** | none — docs only; boundary fixed in `V2-0-reconciliation.md` §10 | — (no source change; tree census recorded at 850 tests / 91 suites on `1679f25`) | — | `docs/parity/2026-11-19-v2-0-baseline/` 15 captures (F-A, F-B, F-D, F-E); `docs/parity/2026-11-15-c4-baseline/` Set A 10 states + Set B 28 flows; `docs/parity/2026-11-22-o11-session-loading/` 2 frames | lock-overlay screenshot, Onboarding, dark theme, API 30+/wide, O11 live window (debug preview stands in) | `docs/v2/V2-0-signoff-2026-11-22.md` §3; `docs/v2/V2-0-baseline-lock-2026-11-19.md` §6 |
| **V2-1** | no source — approved spec; implementation landed in V2-2 | — (tokens pinned later by `CrushTokensTest`) | — | — (no V2-1 device run; the closure cites the V2-3/V2-4/V2-5 sets `docs/parity/2026-11-20-v2-3-shell/`, `...-v2-4-chat/`, `...-v2-5-code/` as superseding evidence) | tablet/wide prototypes (task 4, O3); tasks 3/5/6 closed as superseded, no prototype built (V2-A13) | `docs/v2/V2-1-exit-reconciliation-2026-11-22.md` §2-§3 |
| **V2-2** | `ui/theme/Color.kt`, `ui/theme/Tokens.kt`, `ui/theme/Theme.kt`, `ui/theme/Type.kt`; `ui/components/CrushComponents.kt` (11 components); pilot `ui/screens/diagnostics/DiagnosticsScreen.kt` | `CrushTokensTest`; `CrushComponentsTest` (8 tests) | — | `docs/parity/2026-11-20-v2-2-tokens/` 13 files; F-H and F-I fixed, verified in `12`/`13` | screenshot harness (task 5 PARTIAL); font scale, reduced motion, density; dynamic colour; populated transcript/tool row/diff with the new palette | `docs/v2/V2-2-token-integration.md` §2, §5, §6 |
| **V2-3** | `ui/navigation/PrimaryDestinations.kt`, `ui/navigation/CrushShell.kt`, `MainActivity.kt`, `NavGraph.kt`, `res/values/strings.xml` destination labels | `PrimaryDestinationsTest` (6), `ShellNavigationTest` (10), `SettingsContentTest`/`ProjectsContentTest` (no Back), `ChatDispatchTest` (35-row ledger) | — | `docs/parity/2026-11-20-v2-3-shell/` 12 captures: bar `01`/`07`, sheet `02`, Back `12`, IME `10`, landscape rail `08`/`09` | extended rail ≥840dp (Robolectric 1000dp only, F-K); API 30+; tablet/wide; instrumented; 2.0x, reduced motion, density | `docs/v2/V2-3-app-shell.md` §5-§7 |
| **V2-4** | `ui/screens/chat/ChatContent.kt`, `ChatViewState.kt`, `ChatViewModel.kt`, `MarkdownText.kt`; `ui/components/CrushComponents.kt` (`CrushChoiceChip`); `ui/screens/settings/SettingsContent.kt` (chips, `FlowRow`) | `ChatViewStateTest`, `ChatContentTranscriptTest`, `ChatContentSheetsTest`, `CrushComponentsTest`; unchanged `ChatGateTest`, `ChatDispatchTest` | — | `docs/parity/2026-11-20-v2-4-chat/` 30 stored frames (`01`-`31`, `09` deleted): F-L `12`/`15`, F-M `15`, F-N `31`, Retry `23`-`27`, 2.0x `28`-`31` | frame timing (no baseline); reduced motion; density; wide/tablet; provider switch; model-sheet loading and "not in list" device rows; pinned message and cost/context trimmed (V2-A4) | `docs/v2/V2-4-chat-redesign.md` §5-§6 |
| **V2-5** | `ui/screens/projects/ProjectsContent.kt`, `ProjectsScreen.kt`, `ProjectsViewState.kt`; `ui/screens/review/ReviewContent.kt`, `ReviewScreen.kt`; `domain/file/ReviewHistory.kt`, `TurnChanges.kt`; `ui/screens/chat/ChatListContent.kt`, `ChatScreen.kt`; `ui/navigation/CrushShell.kt` (IME); `engine/CrushEngine.kt` (legacy deny-all); `ChatViewModel.kt`; `project/ProjectRef.kt`; `data/project/RoomProjectStore.kt` | `ProjectsContentTest`, `ReviewContentTest`, `ReviewHistoryTest`, `ChatListContentTest`, `ChatViewStateTest`, `ChatContentTranscriptTest`; unchanged `ProjectTrustServiceTest` | — | `docs/parity/2026-11-20-v2-5-code/`: final build `30`-`38`, `40`, `42`, `45`, `51`, `53`-`55`, `57`-`60`, `64`, `66`-`69`, `72`-`74`, `80`, `88`, `91`-`94`; before/partial `01`-`04`, `10`-`13`, `20`-`21`; log excerpt `2026-11-21-device-log-excerpts.txt` | tablet/wide; 2.0x and TalkBack on these surfaces; accept branch of the changed-`crushrc` dialog; a not-measured Review card on device; Chat-switch veto Toast; running session row; Review history across process death | `docs/v2/V2-5-code-projects-review.md` §5-§6 |
| **V2-6** | `ui/screens/settings/SettingsContent.kt`; `ui/screens/onboarding/OnboardingContent.kt` (hosts and state classes unchanged) | `SettingsContentTest` (12 tests), `OnboardingContentTest` (11 tests) | — | `docs/parity/2026-11-21-v2-6-settings/` 18 files: before `00`/`01`, after `12`-`22`, permissions `15`/`16`, Finding 13 `20`, round-trip `17`-`22`, 2.0x `24`-`27`, restored `28` | Onboarding device row; tablet/wide; Onboarding at 2.0x; key save/replace/delete on device (credential entry); Diagnostics pinned version by decision (V2-6 D7) | `docs/v2/V2-6-settings-onboarding.md` §5-§6 |
| **V2-7** | `domain/model/AppSettings.kt`, `data/local/SettingsDataStore.kt`, `data/repository/SettingsRepository.kt` (`reasoning_display`); `ui/screens/chat/ChatHostInputs.kt`, `ChatViewState.kt`; `data/crush/CrushEvent.kt`; `ChatContent.kt` (`ReasoningBlock`, `UnsupportedContentBlock`); `ui/blocks/UiBlock.kt`, `ui/blocks/UiBlockView.kt`; debug `app/src/debug/.../UiBlockPreviewActivity.kt` | `StreamMessageTest` (declared in `data/crush/CrushProtocolTest.kt`), `ChatHistoryReplayTest`, `ChatContentTranscriptTest`, `SettingsContentTest`, `ReasoningDisplayTest`, `UiBlockParserTest` (12), `UiBlockViewTest` (7) | — | `docs/parity/2026-11-22-v2-7-reasoning/` `01`-`05` (Collapsed, expand, Off, restore); `docs/parity/2026-11-22-v2-7-blocks/` `01` (host-authored document) | no production producer for `ui_block/v1`; blocks not device-checked in dark/RTL/2.0x/TalkBack; reasoning is plain text and provider-dependent | `docs/v2/V2-7-intelligent-ui.md` §3-§5; `docs/v2/ui-block-v1.md` |
| **V2-8** | nothing in production; debug-only `app/src/debug/.../UiBlockLabActivity.kt` + `normalizeUiBlockPayload` | `UiBlockLabTest`, `UiBlockParserTest` (12), `UiBlockViewTest` (7; includes the dark, 2.0x and RTL renders) | — | `docs/parity/2026-11-22-v2-8-lab/` `00`-`07`: three candidate-A runs, 1/3 schema-valid | model-authored production renderer (not adopted, V2-A19); render time, token cost, TalkBack, device dark/2.0x/RTL, three-candidate comparison NOT PERFORMED; A2UI blocked (alpha, protocol 0.9.1, `compileSdk 37.1`) | `docs/v2/V2-8-structured-ui-experiment.md` §7 |
| **V2-9** | no production source change; adds `app/src/androidTest/java/com/crush/mobile/SecureKeyStoreInstrumentedTest.kt`, `tools/check-secrets.ps1`, `docs/release-key-rotation.md` | no new class; ASK default pinned by `PermissionModeTest`, `SettingsContentTest` | `SecureKeyStoreInstrumentedTest` — `OK (3 tests)` on `bbf8ca69`; `docs/parity/2026-11-22-v2-9-hardening/01-securekeystore-instrumented.txt` | `01-securekeystore-instrumented.txt`, `02-secret-scan.txt` (PASS, 674 tracked files, 2 allowlisted fixtures) | auth-gated/biometric key path (absent by design); API 30+ Keystore/StrongBox; git history; Gate 7 | `docs/v2/V2-9-release-hardening.md` §6 |
| **V2-11** | docs, `tools/perf-baseline.ps1` (measurement tool; capture stored at `docs/parity/2026-11-23-v2-baseline-perf/`) and the `parseUncaughtFromLog` terminator fix in `app/src/main/java/com/crush/mobile/ui/screens/diagnostics/DiagnosticsReport.kt` | `AppContainerBindingTest` (4), `DiagnosticsReportTest` (11), `ChatAccessibilityTest` (4) = 19 tests, focused run green (`v2-ledger-tests-1123g.log`); full gate 981/104/0 — see §5 | — | `docs/parity/2026-11-23-v2-baseline-perf/MANIFEST.md`: 3 cold starts, project engine boot, one PSS/gfxinfo sample on `bbf8ca69` | frame budget; a second capture for comparison; any tablet/API 30+ row; instrumented execution | this file; `docs/v2/V2-GAP-AUDIT.md`; `docs/v2/V2-FINAL-INTEGRATION-REVIEW.md` (same batch) |
| **V2-12** | `ui/navigation/CrushShell.kt` (`selectPrimary` shared by bottom bar, rail and extended rail); `ui/screens/review/ReviewContent.kt` ("Export current file" label + historical read-only note); `ui/screens/projects/ProjectsContent.kt` (duplicate footer removed); `ui/screens/onboarding/OnboardingContent.kt` (provider-qualified heading, no hardcoded model count); `engine/EngineInstanceRegistry.kt` (atomic check-and-reserve in one monitor, terminal-failure release, `STARTING` counted) + `di/AppContainer.kt` (`requestEngineStart` registers the runtime before the gate); `ui/screens/settings/SettingsContent.kt` and `SettingsScreen.kt` (key save reports its real result and keeps the draft, delete confirmation, auto-approve confirmation); comment-only corrections in `ChatViewModel.kt`/`AppContainer.kt` describing two non-legacy project slots with chat on legacy | `ShellNavigationTest` (16; 6 new — veto and allowed switch per layout), `EngineInstanceRegistryTest` (19; 4 new — reservation, terminal release, retry keeps it, simultaneous starts), `SettingsContentTest` (17; 5 new), `OnboardingContentTest` (12; 1 new), `ProjectsContentTest` (15), `ReviewContentTest` (13) — all inside the full gate, 997/104/0 (`v2-12-gate-2026-11-23.log`) | — | — (gate only; no device or instrumented run) | device / TalkBack / API 30+ verification of every surface above; `A7`/`A7b` (owner sign-off), `A12` (answering agent questions), `A2`, `A6`, `A13` — §6 row 13 | `docs/v2/V2-AUDIT-FIXES.md`; `v2-12-gate-2026-11-23.log` |
| **V2-13** | `data/crush/WorkspaceManager.kt` — new `PermissionPolicyException`; `applyPermissionPolicy` hard-fails a rejected `permissions/mode`/`skip` (logs, flushes `workspaceId`/`workspaceGeneration`, throws); `open()` throws before `agent/init`; the lease is set only after the policy applies; `reattachIfStale` returns false on rejection and flushes the id. `ui/screens/chat/ChatViewModel.kt` — bootstrap catches the exception, sets a named phase and `engineReady=false` (this row supersedes the V2-12 row's `A7`/`A7b` deferral; that row is kept as written) | `WorkspacePermissionPolicyTest` (4; rejected mode fails the open without `agent/init`, rejected legacy skip does the same, an accepted policy still inits, a failed reattach reports not-attached and flushes the id); `BootstrapOrderTest` (9; unchanged) — all inside the full gate, 1001/105/0 (`v2-14-a7-gate-2026-11-23.log`) | — | — (gate only; no device or instrumented run) | effective-mode read-back (a 2xx on the apply is the proof); device row for the rejection phase; `A12` (answering agent questions), `A2`, `A6`, `A13` — §6 row 13 | `docs/v2/V2-A7-PERMISSION-FAIL-CLOSED.md`; `v2-14-a7-gate-2026-11-23.log` |
| **V2-14** | `ui/screens/chat/QuestionBatch.kt` (`AskKind`, `AskChoice`, `AskPrompt`, `AskBatch`, `AskAnswerDraft`, `QuestionBatchParser`, `QuestionAnswerBuilder`); `ui/screens/chat/QuestionDialog.kt` (`AnswerQuestionDialog`: yes/no radios, single-choice radios, multi-choice checkboxes, free-text field, Submit disabled until every prompt is answered); `ChatContent.kt` chooses the answer dialog when the batch parses and keeps the cancel-only `AlertDialog` otherwise; `ChatAction.SubmitQuestion`; `ChatViewModel.submitQuestion` POSTs `answerQuestions` and keeps the dialog open on failure; `data/crush/CrushDtos.kt` `QuestionResponse.yes` (this row supersedes the `A12` deferral in the V2-12 and V2-13 rows; those rows are kept as written) | `QuestionBatchParserTest` (8), `QuestionDialogTest` (6), `QuestionAnswerBuilderTest` (4), `ChatViewStateTest` (19; +1 for the batch projection) — focused run of the three new classes green at 18 tests; all inside the full gate, 1020/108/0 (`v2-16-a12-gate-2026-11-23.log`) | — | — (gate only; no device or instrumented run) | free-text field has no Compose test (Robolectric never reaches idle during the cursor animation; the wire mapping is covered by `QuestionAnswerBuilderTest`); no process-death persistence for a pending batch; malformed payload keeps the cancel-only fallback; no device/TalkBack/API 30+ run; no read-back that the engine accepted the chosen ids; `A2`, `A6`, `A13` — §6 row 13 | `docs/v2/V2-A12-QUESTION-ANSWERING.md`; `v2-16-a12-gate-2026-11-23.log` |

| **V2-15** | L1 `di/AppContainer.kt` (`ShutdownPlan`, `shutdown(): Int` snapshots the legacy supervisor plus every registered project supervisor; each stop isolated, one-shot); L2 `engine/CrushEngine.kt` (`runEngineStop` always runs child and proxy teardown) and `engine/EngineProxy.kt` (one cancellable scope per start, cancelled on stop); post-push L2 correction makes the supervisor's terminal `Failed` branch call `stopBlocking()`; L3 `data/crush/ConnectionRegistry.kt` (`isSuperseded(key, ownerEpoch)` and `registrationKey` deleted) plus `ui/screens/chat/ChatViewModel.kt` (`registeredConnectionKey` captured at attach, used by the stale check and `onCleared`); Phase C test-only `EngineSupervisor.hasActiveSupervisionForTest()`; L5 accepted as bookkeeping (no production change) | `ShutdownPlanTest` (4), `EngineStopCleanupTest` (3), `CrushEngineLifecycleTest` (1), `EngineProxyConnectTest` (+2), `ConnectionRegistryTest` (25; 15 retained/adapted + 10 adversarial), `EngineInstanceRegistryTest` (26; +7 Phase C), `EngineRuntimeLifecycleTest` (1), `AppContainerShutdownTest` (1), `EngineTerminalStartupCleanupTest` (1) - all inside the full gate, **1050/114/0** (`v2-23-fix-gate.log`) | — | — (gate + device switch-stop/PSS capture; no instrumented run) | instrumented/TalkBack; real spawned-engine timeout after proxy startup; `ChatViewModel` registration-key unit; 3-child PSS; thread census; L5 stays bookkeeping; `A13` runtime disposal covered at JVM level and one device switch-stop, not process termination | `docs/v2/V2-15-RECONCILIATION.md`, `docs/v2/V2-15-RUNTIME-LIFECYCLE-AUDIT.md`, `docs/v2/V2-15-CONNECTION-RETENTION-DESIGN.md`, `docs/v2/V2-15-ENGINE-BUDGET-VALIDATION.md`, `docs/v2/V2-15-RUNTIME-RETENTION-MEASUREMENTS.md`, `docs/v2/V2-15-INTEGRATION-REPORT.md`, `docs/v2/V2-15-POST-PUSH-AUDIT-CORRECTION.md`, `docs/parity/v2-15-device/V215-DEVICE-EVIDENCE.md` |

## 3. Capture sets referenced above

Every device claim in §2 resolves to one of these directories. Frames are
tracked; the gate logs cited in §4 sit at the repo root and are not.

| Parity directory | Size | What it covers | What it does not |
|---|---|---|---|
| `docs/parity/2026-11-15-c4-baseline/` | Set A 10 states, Set B 28 flows (+ `after-2b1/`) | parity baseline of record, pre-2b.1 | "before" half only; flow 13 pins a pre-existing delete defect |
| `docs/parity/2026-11-19-v2-0-baseline/` | 15 captures | F-A, F-B, F-D, F-E; the `sessionsUnavailable` state | no turn, no engine running, no Onboarding, no dark theme |
| `docs/parity/2026-11-20-v2-2-tokens/` | 13 files (2 intermediates) | tokens, components, Diagnostics pilot, light+dark | no populated transcript, tool row or diff |
| `docs/parity/2026-11-20-v2-3-shell/` | 12 captures | bar, rail, sheet, Back, IME, rotation | extended rail not on hardware (F-K) |
| `docs/parity/2026-11-20-v2-4-chat/` | 30 stored (`09` deleted) | chat states, sheets, picker, Retry, 2.0x | frame timing; loading/not-in-list device rows |
| `docs/parity/2026-11-20-v2-5-code/` | final build `30`-`38`, `40`, `42`, `45`, `51`, `53`-`55`, `57`-`60`, `64`, `66`-`69`, `72`-`74`, `80`, `88`, `91`-`94`; before/partial `01`-`04`, `10`-`13`, `20`-`21` | IA, project binding, Review, export, revert, trust re-gating, IME | tablet, 2.0x, TalkBack, accept branch |
| `docs/parity/2026-11-21-v2-6-settings/` | 18 files (`00`, `01`, `12`-`22`, `24`-`28`) | Settings before/after, permissions, Finding 13, 2.0x | Onboarding, key save/replace/delete |
| `docs/parity/2026-11-22-o11-session-loading/` | 2 frames + stall log | loading row copy per surface | live navigation window (debug preview stands in) |
| `docs/parity/2026-11-22-v2-7-reasoning/` | 5 frames | Collapsed, expanded, Off, restore | one device, one model, API 29 |
| `docs/parity/2026-11-22-v2-7-blocks/` | 1 frame | host-authored `ui_block/v1` render | any model producer |
| `docs/parity/2026-11-22-v2-8-lab/` | 8 frames | parse, reject, render; the 1/3 result | timing, cost, TalkBack, device dark/RTL/2.0x |
| `docs/parity/2026-11-22-v2-9-hardening/` | 2 text files | real-Keystore instrumented run; secret scan | auth-gated key, API 30+, history |
| `docs/parity/2026-11-23-v2-baseline-perf/` | 3 cold starts + 1 steady-state sample (`starts.csv`, raw `start-*`, `engine-boot-*`, `gfxinfo.txt`, `meminfo.txt`) | cold start 469-543 ms TotalTime; engine boot 2353-2637 ms; PSS 110,133 kB; 14-frame gfxinfo sample | no frame budget; no scroll; one device/API; lock gate and prewarm not driven |

## 4. Counts and how they were produced

- Executed JVM census, per package record: 871 (V2-2), 885 (V2-3), 899 (V2-4),
  917 (V2-5), 919 (V2-6), 938 (V2-7), 954 (V2-7.3), 962 (V2-8). Each package's
  **final** gate recorded `0 failures` and lint `0 errors / 1 pre-existing
  NotShrinkingResources warning`; earlier attempts varied and stay in each
  package's own gate table (V2-2 attempt 1 failed one test on a fixture typo and
  attempts 2-3 carried 3 and 1 warnings).
- **V2-9 is an outlier and is not comparable.** Its record says `924 tests /
  97 classes`, and `v2-9-gate-1.log` finished in 35s with 91 of 95 tasks
  up-to-date and `:app:testDebugUnitTest` UP-TO-DATE. That number is a census
  read from existing results, not a fresh execution of the 962-test tree. This
  ledger quotes it as the package recorded it and does not present the series as
  cumulative.
- V2-11 adds 19 `@Test` methods (11 + 4 + 4). The full gate then executed the
  whole tree: **981 tests / 104 suites / 0 failures / 0 errors / 0 skipped**,
  lint **0 errors / 1 pre-existing warning** (`v2-11-gate-2026-11-23.log`,
  2026-11-23). See §5 for the classes.
- V2-12 (the audit-fix batch) adds 16 `@Test` methods over V2-11:
  `ShellNavigationTest` 10 → 16, `EngineInstanceRegistryTest` 15 → 19,
  `SettingsContentTest` 12 → 17, `OnboardingContentTest` 11 → 12;
  `ProjectsContentTest` (15) and `ReviewContentTest` (13) changed assertions
  without changing counts. The full four-task gate then executed the whole tree:
  **997 tests / 104 suites / 0 failures / 0 errors / 0 skipped**, lint **0
  errors / 1 pre-existing warning** (`v2-12-gate-2026-11-23.log`, 2026-11-23,
  34m01s).
- V2-13 (the permission fail-closed batch) adds 4 `@Test` methods over V2-12:
  `WorkspacePermissionPolicyTest` 0 → 4; `BootstrapOrderTest` stays 9, unchanged.
  The full four-task gate then executed the whole tree: **1001 tests / 105 suites
  / 0 failures / 0 errors / 0 skipped**, lint **0 errors / 1 pre-existing
  warning** (`v2-14-a7-gate-2026-11-23.log`, 2026-11-23, 33m38s).
- V2-14 (the A12 question-answering batch) adds 19 `@Test` methods over V2-13:
  `QuestionBatchParserTest` 0 → 8, `QuestionDialogTest` 0 → 6,
  `QuestionAnswerBuilderTest` 0 → 4, `ChatViewStateTest` 18 → 19. The full
  four-task gate then executed the whole tree: **1020 tests / 108 suites / 0
  failures / 0 errors / 0 skipped**, lint **0 errors / 1 pre-existing warning**
  (`v2-16-a12-gate-2026-11-23.log`, 2026-11-23, 17m07s).
- V2-15 (the lifecycle batch, committed as `1990118` on
  `v2-15-lifecycle-hardening`, plus the post-push correction) adds 30 `@Test`
  methods over V2-14:
  L1/L2 10 (`ShutdownPlanTest` 0 → 4, `EngineStopCleanupTest` 0 → 3,
  `CrushEngineLifecycleTest` 0 → 1, `EngineProxyConnectTest` +2), L3 10
  (`ConnectionRegistryTest` 15 → 25), Phase C 9 (`EngineInstanceRegistryTest`
  19 → 26, `EngineRuntimeLifecycleTest` 0 → 1, `AppContainerShutdownTest` 0 → 1),
  post-push L2 1 (`EngineTerminalStartupCleanupTest` 0 → 1).
  The post-push correction was validated by a full four-task gate: **1050
  tests / 114 suites / 0 failures / 0 errors / 0 skipped**, lint **0 errors /
  1 pre-existing warning** (`v2-23-fix-gate.log`, 2026-11-24, 33m01s). The
  pre-correction gate (`v2-21-phasec-gate-final.log`) was 1049/113/0; the
  earlier `v2-20-phasec-gate.log` was interrupted mid-lint.
- V2-15 Phase D (permission-policy verification) adds 9 `@Test` methods over
  the post-push gate: `WorkspacePermissionPolicyScenariosTest` 0 → 9; the
  existing `WorkspacePermissionPolicyTest` (4) now also asserts no
  `/config/provider-key` request on rejection. The full gate ran **1059 tests /
  115 suites / 0 failures / 0 errors / 0 skipped**, lint **0 errors / 1
  pre-existing warning** (`v2-30-phaseD-gate.log`, 2026-11-24, 18m12s).
- V2-15 Phase E (question-answering verification) adds 1 `@Test` method:
  `QuestionDialogTest` 6 → 7 (replacement batch inherits no old selection).
  Focused A12 run: **38 tests / 4 classes / 0 failures** (`v2-42-phaseE-focused-final.log`,
  1m16s). No production change, so the full gate was not rerun; the latest
  1061-test gate predates the new test.
- V2-15 Phase E remediation (A12 E1/E2/E5) adds 21 `@Test` methods:
  `QuestionOperationControllerTest` 12, `QuestionAnswerValidatorTest` 7,
  `QuestionDialogTest` +2 (9 total). Focused **59 tests / 6 classes / 0
  failures** in 2m48s; full gate **1083 tests / 117 suites / 0 failures / 0
  errors / 0 skipped**, lint **0 errors / 1 pre-existing warning** in 22m29s
  (`v2-50-phaseE-remediation-gate.log`).
- V2-15 Phase E stale-engine follow-up adds 4 `@Test` methods (controller
  12 → 16): stale-claim rejection, null/empty identity keys, and
  replacement-during-reattach zero-POST for Submit and Cancel. Focused **63
  tests / 6 classes / 0 failures** in 1m26s; full gate **1087 tests / 117
  suites / 0 failures / 0 errors / 0 skipped**, lint **0 errors / 1 pre-existing
  warning** in 21m37s (`v2-61-phaseE-followup-gate2.log`); the stale-engine
  mutation failed as intended (`v2-59-phaseE-mutation-stale-engine2.log`).
- Instrumented: 6 classes / 15 `@Test` methods exist under `app/src/androidTest`;
  only `SecureKeyStoreInstrumentedTest` (3 tests) has a stored device run.
- `:app:assembleDebugAndroidTest` compiles the test APK in every gate. Compiling
  is not executing.

## 5. Validation gaps closed in V2-11

Three test classes were added in this batch. Two of them target rows that the
V2-0 sign-off (`V2-0-signoff-2026-11-22.md` §3) left open; the third covers the
accessibility `PARTIAL` row in `V2-0-reconciliation.md` §4.8. All three ran green
in a focused run of those classes: 19 tests, 0 failures
(`v2-ledger-tests-1123g.log`). The full four-task gate then passed: **981
tests / 104 suites / 0 failures / 0 errors / 0 skipped**, lint **0 errors / 1
pre-existing warning** (`v2-11-gate-2026-11-23.log`).

`DiagnosticsReportTest` also found a real production defect:
`parseUncaughtFromLog` used `Regex.matches`, which requires a whole-line match, so
the crash-trace terminator never fired and a trace swallowed every later log
entry. Fixed to `containsMatchIn` in
`app/src/main/java/com/crush/mobile/ui/screens/diagnostics/DiagnosticsReport.kt`.

| Class | Tests (source count) | What it pins | Was |
|---|---:|---|---|
| `app/src/test/java/com/crush/mobile/di/AppContainerBindingTest.kt` | 4 | `bindingSnapshot` publishes binding + instance key **together** (initial legacy pair, ChatPool pair, project pair); a same-key label rename still advances the snapshot before the early return; every emitted pair carries its own key | §3 test debt: "container-level `applyBinding`/`bindingSnapshot` test" |
| `app/src/test/java/com/crush/mobile/ui/screens/diagnostics/DiagnosticsReportTest.kt` | 11 | whole-block order of the report; key masking in the log tail, in crash text and in collection notes; the truncation marker appears only when the caller flags truncation, and the supplied last line survives (the collector's own 200-line cut is not exercised); a null probe renders "not checked", never "no"; `parseUncaughtFromLog` stops at the next timestamp, takes the LAST uncaught, keeps a header-only mid-write block, and returns null with no crash | §3: "report-level redaction/tail test" |
| `app/src/test/java/com/crush/mobile/ui/screens/chat/ChatAccessibilityTest.kt` | 4 | icon-only chrome announces actions ("Open chats list", "Choose model", "Send"); the picker's changing label stays out of the spoken name and rides `stateDescription`; a gated send stays present but disabled; streaming swaps Send for Stop; the reasoning disclosure reports Collapsed/Expanded | `V2-0-reconciliation.md` §4.8 accessibility `PARTIAL`; no chat-chrome semantics test |

What these close, precisely:

- **The diagnostics redaction gap from `V2-0-signoff-2026-11-22.md` §3 is
  closed by `DiagnosticsReportTest` at the JVM-report level only.** The report
  builder is now pinned. The screen/share path (clipboard, share intent) and any
  device run of it remain **NOT PERFORMED**.
- **Accessibility is closed at the semantics level only.** `ChatAccessibilityTest`
  pins the properties a screen reader consumes; a **TalkBack device audit is NOT
  PERFORMED**, and 48dp touch targets are not asserted there (that artifact has
  no touch-bounds matcher) — the token floor stays with `CrushTokensTest` and the
  component sets with `CrushComponentsTest`.
- `AppContainerBindingTest` pins the container seam; it does not exercise a
  device or a live engine. Its DataStore seed/persistence test was removed (5 →
  4) because `DataStore.edit` deadlocks under Robolectric's paused main looper;
  the binding-token codec stays pinned by `EngineBindingTokenTest`.

> Focused run: 19 tests / 0 failures (`v2-ledger-tests-1123g.log`). Full four-task gate: green — 981 tests / 104 suites / 0 failures / 0 errors / 0 skipped, lint 0 errors / 1 pre-existing warning (`v2-11-gate-2026-11-23.log`).

## 6. Carry-over rows from the V2-0 sign-off (§3)

None of these blocks the V2 exit: V2-A14 accepted the ledger with these rows
named and open. They are listed here so "complete" cannot be read as "closed".

| # | Carry-over row | Evidence now | Still missing | Blocks release? |
|---:|---|---|---|---|
| 1 | V2-4 performance proof (frame timing) | `tools/perf-baseline.ps1` and `docs/parity/2026-11-23-v2-baseline-perf/`: 3 cold starts (469/517/543 ms), project engine boot (2353/2477/2637 ms; run 2 also logged the legacy engine at 2391 ms), PSS 110,133 kB, one 14-frame gfxinfo sample | a frame-pacing measurement over a scripted interaction; an accepted frame budget; a second capture for comparison | No — open |
| 2 | Engine boot / perf baseline | same capture parses `engine Running after Nms` from logcat: 2353-2637 ms across three launches | a repeat on another build/device; no budget asserted | No — open |
| 3 | Diagnostics/About pinned engine version + contract level | V2-6 D7 records the decision: no host-exposed value exists, so nothing is shown. `docs/parity/2026-11-19-v2-0-baseline/MANIFEST.md` states the version was not re-queried that session | a host-exposed value. The records also disagree on the current one — `V2-0-baseline-lock-2026-11-19.md` §6 says v0.100.0 / contracts 0.99.0, `docs/parity/2026-11-20-v2-4-chat/MANIFEST.md` says bundled 0.98.1; neither is a UI claim | No (decision, not a defect) |
| 4 | Diagnostics report redaction/tail test | **`DiagnosticsReportTest`** (11 tests), added in V2-11; focused run green and full gate green (981/104/0 — see §5) | screen/share path and device run NOT PERFORMED | No — special case below |
| 5 | Container-level `applyBinding`/`bindingSnapshot` test | **`AppContainerBindingTest`** (4 tests), added in V2-11; focused run green and full gate green (981/104/0 — see §5) | no device | No |
| 6 | Screenshot-test harness | none; manual PNG manifests only (V2-2 §2.6 task 5 PARTIAL; `V2-0-reconciliation.md` §4 item 6.6) | any image-comparison infrastructure | No |
| 7 | Instrumentation suite additions | 6 classes / 15 tests compiled under `app/src/androidTest`; 3 executed in V2-9 | 5 classes / 12 tests never executed on a device; no CI execution; O11's instrumented launch was abandoned under API 29 background-start rules | No |
| 8 | `T6-01` catalogue `isFree` badge | the legacy `-free` suffix rule is still the pixels, pinned by `ChatModelMetadataTest` (`ChatViewState.kt`) | a catalogue-driven badge | No |
| 9 | `T6-02` interaction-parity test | `ChatDispatchTest`'s 35-row ledger is the closest thing and is pinned | the brief's `T6-02` shape | No |
| 10 | Onboarding device row | content pinned by `OnboardingContentTest` (11/11) | a device frame; reason recorded in the V2-6 manifest (a fresh install or a key-less provider, which costs the owner's stored state) | No |
| 11 | Device matrix (tablet/wide, extended rail, API 30+) | `ShellNavigationTest` renders rails at 800dp and 1000dp; landscape produced a real compact rail on device (`08`/`09`) | any ≥840dp hardware, any tablet/foldable, any API 30+ runtime | No (O3) |
| 12 | Accessibility | semantics: `ChatAccessibilityTest` (V2-11, focused run green — see §5), `CrushComponentsTest`, `CrushTokensTest` 48dp floor; 2.0x device rows V2-4 `28`-`31`, V2-6 `24`-`27` | **TalkBack traversal (NOT PERFORMED anywhere)**; 1.3x font scale (only 2.0x rows exist); accessibility of `ui_block/v1` and RTL on device | No |
| 13 | External audit findings | `docs/v2/V2-GAP-AUDIT.md` verified A1-A14 with file:line. The V2-12 fix batch is recorded in `docs/v2/V2-AUDIT-FIXES.md` and sits inside the green gate (997/104/0): `A1` (one `selectPrimary` veto for all three layouts), `A3` ("Export current file" + historical read-only note), `A4` (duplicate Projects footer removed), `A5` (provider-qualified heading), `A8` (atomic reservation, terminal-failure release, pre-registration), `A10` (key save reports its result and keeps the draft), `A11` (delete confirmation), the auto-approve confirmation (`V2-AUDIT-4`), and `A14` (comments describe two project slots, chat on legacy). The V2-13 batch is recorded in `docs/v2/V2-A7-PERMISSION-FAIL-CLOSED.md` and sits inside the green gate (1001/105/0): `A7`/`A7b` — a rejected `permissions/mode`/`skip` now fails `open()` with `PermissionPolicyException` before `agent/init`, flushes the id and generation, sets the lease only after the apply, and a failed reattach reports not-attached (`WorkspacePermissionPolicyTest` 4; `BootstrapOrderTest` 9 unchanged). The V2-14 batch is recorded in `docs/v2/V2-A12-QUESTION-ANSWERING.md` and sits inside the green gate (1020/108/0): `A12` — a parsed question batch now renders yes/no, single-choice, multi-choice and free-text controls and posts `answerQuestions`, while an unparseable payload keeps the cancel-only fallback (`QuestionBatchParserTest` 8, `QuestionDialogTest` 6, `QuestionAnswerBuilderTest` 4, `ChatViewStateTest` +1) | `A12` is fixed (V2-14) and the release-gating set is empty; `A2` (project home), `A6` (route/mode dual meaning — monitor only) and `A13` (runtime disposal) stay open. No effective-mode read-back exists — a 2xx on the apply is the proof — and no device, TalkBack or API 30+ run covers any fixed row | Not for V2 exit; the audit's `A7` security release gate is met at JVM level only (no read-back, no device run), and `A12` is closed at JVM level only (no device/TalkBack/API 30+ run, no read-back of the accepted ids); `A2`, `A6` and `A13` do not block |

Rows 4 and 5 change class in this batch: the *test* halves are done
at the report/container level, so they move out of "no test exists" into
"pinned in JVM, unmeasured on device". Row 12's TalkBack half is unchanged.

**V2-12 (2026-11-23).** Row 13 changes class: eight audit findings
(`A1`, `A3`, `A4`, `A5`, `A8`, `A10`, `A11`, `A14`) and the delete/auto-approve
confirmations are implemented and inside the green gate, so the row no longer
reads "none of the fixes are implemented". The row's subject stays the audit
itself, so it does not take a JVM-TESTED label; the classes that pin each fix
are named in the V2-12 row of §2. Row 13 keeps `A7`/`A7b` as the release gate
and adds no device claim. Row 10's count also moves: `OnboardingContentTest` is
12/12 after the V2-12 heading case; the row keeps its 11/11 as the V2-11-era
number.

**V2-13 (2026-11-23).** Row 13 changes class again: `A7`/`A7b` is implemented and
inside the green gate (1001/105/0), so the row no longer names it as the open
release gate. A rejected permission policy now fails the open with
`PermissionPolicyException` before `agent/init`, the id is flushed, the lease is
set only after the apply, and a failed reattach reports not-attached; the class
that pins it is named in the V2-13 row of §2. The row keeps `A12` (answering
questions) as the remaining release-gating product gap, plus `A2`, `A6` and
`A13`, and adds no device claim: there is no effective-mode read-back (a 2xx on
the apply is the proof) and no device row for the rejection phase.

**V2-14 (2026-11-23).** Row 13 changes class again: `A12` is implemented and
inside the green gate (1020/108/0), so the row no longer names a release-gating
product gap. A parsed question batch now renders yes/no, single-choice,
multi-choice and free-text controls and posts `answerQuestions`; an unparseable
payload keeps the cancel-only fallback, and a failed POST keeps the dialog open
with `questionError`. The classes that pin it are named in the V2-14 row of §2.
The row keeps `A2`, `A6` and `A13` open and adds no device claim: the free-text
field has no Compose test, nothing is persisted across process death, and a 2xx
on `answerQuestions` is still the only proof that an answer was accepted.

**V2-15 (2026-11-24, committed as `1990118` on
`v2-15-lifecycle-hardening`, not merged).** Row 13's `A13` runtime-disposal half changes
class: L1 stops every registered supervisor through one snapshot plan, L2 always
tears down the egress proxy and cancels its per-start scope, L3 deletes the
connection tombstone map, and Phase C shows a stopped runtime retains bounded
metadata only. L5 is accepted as bookkeeping with no production change. The
classes that pin each half are named in the V2-15 row of §2. A device run on
`bbf8ca69` added switch-stop/PSS evidence: the project child and its socket file
were gone after the switch, with no listener left and the app alive. `A13` stays
open for process-termination verification: the device stop was a binding switch,
not `Application.onTerminate`; there is no real spawned-engine timeout after
proxy startup and no 3-child PSS. The V2-15 row adds a device switch-stop/PSS
claim but no instrumented or TalkBack claim. A post-push audit corrected the
terminal-startup cleanup: the supervisor's terminal `Failed` branch now calls
`stopBlocking()`, pinned by `EngineTerminalStartupCleanupTest` (1). The
`containerScope` cancellation is documented as deliberately deferred (it owns
no engine resource), and no start/stop concurrency change was introduced; see
`V2-15-POST-PUSH-AUDIT-CORRECTION.md`.

**V2-15 Phase D (2026-11-24, committed on `v2-15-lifecycle-hardening` as a
follow-up to `a1be361`).** The V2-13 A7/A7b
fail-closed implementation was re-verified across the ten permission-policy
scenarios. Rejection and transport failure stop before `agent/init`, never issue
`/config/provider-key`, flush id and generation, and surface the named
policy-rejection phase; generation changes flush the id and `ensureAgentReady`
returns “no workspace” without using the key; a failed reattach reports false
and a retry recovers. One reproducible defect was found: a cancelled re-open
could leave the old fresh `lastAttachedAtMs` covering a newly claimed id, so a
later non-forced reattach could return true without re-applying the policy.
`WorkspaceManager.open()` now clears the lease when it claims the id and flushes
id/generation/lease on `CancellationException`. No trustworthy effective-mode
read-back exists — `GET /permissions/skip` and the workspace `yolo` field are
booleans, not the v0.100.0 mode enum — so a 2xx apply remains the only proof.
The scenario suite is `WorkspacePermissionPolicyScenariosTest` (9), plus the
existing policy class (4, now credential-asserting) and `WorkspaceLeaseTest`
(14); focused 27/27 green and full gate 1059/115/0 (`v2-30-phaseD-gate.log`).
No device, instrumented or TalkBack run covers Phase D.

**V2-15 Phase D follow-up (2026-11-24, committed on
`v2-15-lifecycle-hardening` as a follow-up to `6379165`).** Different-mode
concurrent opens are reachable: `WorkspaceManager` instances are created per
bootstrap, the `ChatViewModel.bootstrapping` guard is per instance, and a
cleared ViewModel's blocking OkHttp policy call can outlive it while a
replacement reads a changed mode. `WorkspaceManager.open()` now takes a
companion per-path `Mutex` and holds it through create/policy/init, so the
later call's ASK/YOLO mode is the final apply; `reattachIfStale()` uses the same
path lock through `open()`. The out-of-order ASK/YOLO regression is the tenth
scenario test; disabling the lock makes it fail at the serialization assertion
(`v2-34-phaseD-serialization-mutation.log`), and the restored fix passes
focused 28/28. The full gate is **1060 tests / 115 suites / 0 failures / 0
errors / 0 skipped**, lint **0 errors / 1 pre-existing warning**
(`v2-35-phaseD-serialization-gate.log`, 17m53s). No device or instrumented
run covers this follow-up.

**V2-15 reattach fast-path follow-up (2026-11-24, committed on
`v2-15-lifecycle-hardening` as a follow-up to `3a261e6`).**
`reattachIfStale()` evaluated its fresh-lease short-circuit before taking the
per-path lock, so while an open was still inside `createWorkspace` (the old
lease was not yet cleared) it could return true and let a caller proceed under
the old policy. The fast path and the reopen now both run under
`openLock(path).withLock`; the reopen calls `openLocked()` directly, avoiding
recursive acquisition of the non-reentrant mutex. Failed create, cancellation,
policy rejection, generation changes and successful fresh-lease reattachments
retain their prior outcomes. The tenth scenario test
(`fresh-lease reattach waits for a concurrent YOLO open`) holds the YOLO
create, asserts the reattach is still suspended, then releases it and verifies
the final mode is YOLO; with the fast path mutation it fails at
`reattach must not accept the old lease while the YOLO open is in progress`
(`v2-37-reattach-mutation.log`). Focused 29/29 green; full gate **1061 tests /
115 suites / 0 failures / 0 errors / 0 skipped**, lint **0 errors / 1
pre-existing warning** (`v2-38-reattach-gate.log`, 16m26s). No device or
instrumented run covers this follow-up.

**V2-15 Phase E (2026-11-24, committed on `v2-15-lifecycle-hardening` as a
follow-up to `878afa9`).** A12 question answering was
verified against its parser, dialog, wire and lifecycle contracts. Parser (8),
dialog (7, including a replacement batch that inherits no old selection), wire
builder (4) and the view-state projection (19) ran focused: **38 tests / 4
classes / 0 failures** (`v2-42-phaseE-focused-final.log`, 1m16s). No production
change was made, so the full gate was not rerun; the latest 1061-test gate
predates the new UI test. Open source-inspected findings: E1, a stale
`submitQuestion`/`resolveQuestion` success clears `pendingQuestion`
unconditionally and can drop a newer batch; E2, Submit/Cancel have no
in-flight guard and duplicate taps can race; E3, ownership relies on the
engine's current-session scoping because `PendingQuestion` carries no
session/run identity; E4, a pending question has no engine-generation identity
across restart. No answer read-back, no process-death persistence, no
device/TalkBack run. Detail:
`docs/v2/V2-15-QUESTION-ANSWERING-VERIFICATION.md`.

**V2-15 Phase E remediation (2026-11-24, committed on
`v2-15-lifecycle-hardening` as `289de6f`).** E1/E2 are fixed at
the question-operation boundary: `QuestionOperationController` captures the
exact `(questionId, batchId)`, claims synchronously, keys the in-flight guard by
that identity, and only clears/errors/clears-operation when the displayed
batch still matches; a token prevents an old completion from releasing a newer
guard. `ChatViewModel` claims before launching, uses `UNDISPATCHED` so the
`finally` is entered, exposes `SUBMIT`/`CANCEL`, and resets the operation state
on a new question. E5 is enforced by `QuestionAnswerValidator` before any claim
or HTTP (expected ids exactly once, no duplicates/unexpected ids, choice
ownership, prompt-kind shape; `yes = false` valid; UNKNOWN rejected). The
dialog disables both buttons while in flight. Focused **59 tests / 6 classes /
0 failures** in 2m48s; mutation of identity protection failed the stale-batch
tests and mutation of the guard failed the duplicate/mutual-exclusion tests
(`v2-47`/`v2-48`). Full gate **1083 tests / 117 suites / 0 failures / 0 errors
/ 0 skipped**, lint **0 errors / 1 pre-existing warning** in 22m29s
(`v2-50-phaseE-remediation-gate.log`). E3/E4, process-death persistence, answer
read-back and device/TalkBack remain deferred. Detail:
`docs/v2/V2-15-QUESTION-ANSWERING-REMEDIATION.md`.

**V2-15 Phase E stale-engine follow-up (2026-11-24, committed on
`v2-15-lifecycle-hardening` as a follow-up to `289de6f`).**
`QuestionOperationController.claim()` now rejects a non-current identity, the
guard is keyed by the typed `QuestionRef` (no string encoding/null-empty
collision), and `executeWithReattach()` revalidates the lease after
reattachment before invoking the POST. A batch replaced while reattachment is
suspended therefore sends zero answer/cancel requests; both Submit and Cancel
are covered by deterministic replacement tests. Mutation of this revalidation
posted the obsolete submit and cancel (`expected 0, was 1`), proving the tests
are meaningful (`v2-59-phaseE-mutation-stale-engine2.log`). Focused 63/63
green; full gate **1087/117/0**, lint 0/1, 21m37s
(`v2-61-phaseE-followup-gate2.log`). E3/E4 and the previously deferred limits
are unchanged.

**V2-15 Phase F — device matrix (2026-11-25, local commit only).** The deferred
matrix rows were executed on `bbf8ca69` (Redmi Note 7 Pro, **API 29**, 1080x2340,
440 dpi, `en-IN`, engine `v0.100.0`). Instrumented classes were run one at a
time: `SecureKeyStoreInstrumentedTest` 3/3, `ProjectStoreRoomPersistenceTest`
2/2, `MainActivitySmokeTest` 2/2, `TwoProjectAttachTest` 1/1 and
`TwoEngineInstancesPerProjectTest` 1/1 — **14 test methods green, 0
app-behaviour failures**. `MainActivityLockFlowTest` returned 5 PASS and 1
BLOCKED: `recreationKeepsGateDecisionAndLockStateObject` cannot pass on a device
that auto-prompts through a system credential activity (the prompt holds the
activity PAUSED past `recreate()`'s timeout, and a real unlock legitimately turns
`PROMPT` into `OPEN`); it is a test-design limit, not an app defect, and the
decision inputs stay pinned by `LockStateTest` (9). The two engine-isolation
classes failed only because they predated the `v0.100.0` permission wire; they
now pass `EngineProtocol.modeFor(ASK)` and needed no production change.

UI rows: 1.3x font scale PASS; Diagnostics copy (toast) and share
(`ChooserActivity`) PASS; A12 `yes_no` dialog PASS end-to-end against a real
engine `question` tool call (dialog → Submit gated → answer accepted → run
finished); TalkBack PASS for focus traversal on Settings, the chat list and both
navigation layouts, with speech text not captured; wide window + extended rail
PASS **simulated** at 1066 dp via `wm size 1600x1000`/`wm density 240`, restored
to native afterwards. NOT PERFORMED with reasons: RTL (`debug.force_rtl` is
ignored by this ROM — launcher and app both stayed LTR after restart and
rotation; `am start` has no `--locale` on API 29; a real test needs a system
locale change plus reboots), API 30+, tablet/foldable hardware, and the
`single_choice`/`multi_choice`/`free_text` device dialogs. Environment finding:
MIUI defers instrumentation activity starts for minutes until an explicit
launcher-category `am start`; wall times are not test times, and one lock-suite
run was killed externally by `OneKeyClean` (signal 9) and is not counted as a
failure. Detail: `docs/v2/V2-15-DEVICE-MATRIX-VERIFICATION.md`.

**Device-matrix decision (2026-11-23).** No emulator CI job was added in this
batch. The rows stay NOT PERFORMED because: the local machine reports no
hypervisor driver (`emulator -accel-check`, code 6) and its only AVD is arm64;
CI is deliberately engine-less (`libcrush.so` is gitignored and the engine is
required for the meaningful multi-instance suites); and the instrumented suites
that would run are Keystore/device-dependent. An emulator job is separate
infrastructure work with its own flakiness and cost, not a documentation fix.
`ShellNavigationTest` already renders the 800dp/1000dp layouts on the JVM; it is
not a hardware claim.

## 7. Explicitly out of scope: Gate 7

Gate 7 — release signing, packaging, store listing, submission and staged
rollout — is a **separate milestone that requires its own authorization**. It is
not a V2 package, no V2 record claims any part of it, and nothing in this ledger
signs, uploads or publishes anything.

| Gate 7 item | Status here |
|---|---|
| Release signing key / keystore / Play App Signing | NOT PERFORMED — no artifact created, referenced or validated |
| Release packaging, shrinking, versionCode for a store build | NOT PERFORMED — gates build `assembleDebug` and `assembleDebugAndroidTest` only, plus `lintRelease` |
| Store listing, screenshots, descriptions, content rating, data-safety forms | NOT PERFORMED — no Play Console listing, signing or upload was performed. A parked copy deck exists (declaration answers, listing copy, demo script, reviewer access): prepared, never submitted, `docs/play-afa-submission-2026-11-14.md` |
| Submission and staged rollout | NOT PERFORMED |
| Post-release monitoring / rollback plan | NOT PERFORMED |

- `docs/release-key-rotation.md` and `tools/check-secrets.ps1` (V2-9) are
  credential hygiene, not release preparation.
- V2-9's own record states the same boundary; V2-A15 opened it for hardening
  only.
- A V2 "complete" row means the UI/behaviour work is done and evidenced. It says
  nothing about store readiness, store policy or rollout risk.

## 8. Still NOT PERFORMED anywhere (consolidated)

Status line as at 2026-11-24; the Phase F note below records what changed on
2026-11-25 without rewriting this list.

Extended rail ≥840dp on hardware; tablet/foldable/wide windows (O3); any API 30+
run; landscape beyond the compact rail; TalkBack on any surface; 1.3x font
scale; reduced motion; density overrides; dynamic colour; screenshot/image
comparison harness; a frame-pacing measurement over scripted interaction and any
frame budget (one 14-frame launch sample exists, not a pacing result); Onboarding
on device; key save/replace/delete on device; the accept branch of the
changed-`crushrc` dialog; a not-measured Review card on device; the Chat-switch
veto Toast; a running session row; Review history across process death; the 5
unevaluated instrumented classes; model-authored `ui_block` timing, token cost
and accessibility; the Diagnostics screen/share path on device; any device row
for the V2-12 fixes (rail veto, Review export label, Projects footer, Settings
confirmations); any device row for the V2-13 permission-rejection phase; an
effective-mode read-back from the engine (a 2xx on the policy apply is the only
proof today).

For V2-15, the switch-stop/PSS capture in `docs/parity/v2-15-device/` was
performed (manifest + raw evidence there); a 3-child PSS sample, a per-process
thread census, and a shutdown-on-process-termination capture remain NOT
PERFORMED.

**Phase F (2026-11-25) closed part of this list on the API 29 phone:** TalkBack
focus traversal (Settings, chat list, both navigation layouts; speech not
captured), the 1.3x font scale, the Diagnostics copy/share path, the A12
`yes_no` question dialog end-to-end, the five previously unexecuted instrumented
classes, and the wide/extended-rail layout **as a 1066 dp `wm size` simulation,
not hardware**. Still NOT PERFORMED after Phase F: extended rail on ≥840dp
hardware, any tablet/foldable, any API 30+ run, RTL (this ROM ignores
`debug.force_rtl`), landscape beyond the compact rail, 2.0x on the V2-15
surfaces, reduced motion, density overrides, TalkBack speech content, the
`single_choice`/`multi_choice`/`free_text` device dialogs, and the
`recreationKeepsGateDecisionAndLockStateObject` device test (blocked by the
system credential activity). Detail:
`docs/v2/V2-15-DEVICE-MATRIX-VERIFICATION.md`.

Each item above appears in a package record's limits section or in
`docs/v2/V2-GAP-AUDIT.md`. None is implied to work.

## 9. Related records

| Record | Role |
|---|---|
| `docs/v2/README.md` | package table, scope, per-package entry conditions |
| `docs/v2/V2-0-signoff-2026-11-22.md` | V2-0 acceptance and the §3 carry-over rows quoted in §6 |
| `docs/v2/V2-0-baseline-lock-2026-11-19.md` | locked SHA/toolchain/device limits (§6), parity re-read |
| `docs/v2/V2-0-reconciliation.md` | Rev 3.1 line-by-line reconciliation and the pre-closure gap list |
| `docs/v2/V2-1-design-foundation.md`, `V2-1-exit-reconciliation-2026-11-22.md` | tokens, state catalogue, phone-scope closure (V2-A13) |
| `docs/v2/V2-2-token-integration.md` … `V2-6-settings-onboarding.md` | per-package implementation and evidence records |
| `docs/v2/V2-7-intelligent-ui.md`, `docs/v2/ui-block-v1.md` | reasoning/unknown-part/registry foundation (V2-A18) |
| `docs/v2/V2-8-structured-ui-experiment.md` | closed as not adopted (V2-A19) |
| `docs/v2/V2-9-release-hardening.md` | hardening scope closure (V2-A15) |
| `docs/v2/ADR-REGISTER.md` | accepted decisions V2-A1…V2-A19, open O3-O14 |
| `docs/v2/V2-GAP-AUDIT.md` | external audit verified against source, with file:line and batch IDs |
| `docs/v2/V2-AUDIT-FIXES.md` | the V2-12 audit-fix batch: per-`A` change, the tests that pin it, and what stayed open (§2, §6 row 13) |
| `docs/v2/V2-A7-PERMISSION-FAIL-CLOSED.md` | the V2-13 permission fail-closed batch: per-file change, the tests that pin it, and the read-back limit (§2, §6 row 13) |
| `docs/v2/V2-A12-QUESTION-ANSWERING.md` | the V2-14 A12 question-answering batch: parser/model/dialog/answer wire, the tests that pin it, and the device/read-back limits (§2, §6 row 13) |
| `docs/v2/V2-FINAL-INTEGRATION-REVIEW.md` | final pass over the Rev 3.1 state contract, composer gates, security boundaries (same batch) |
| `tools/perf-baseline.ps1`, `docs/parity/2026-11-23-v2-baseline-perf/` | the measurement script and the first stored capture (cold start, engine boot, PSS, one gfxinfo sample) |
| `docs/parity/*/MANIFEST.md` | the 12 capture sets in §3; each names what it does and does not prove |

## 10. How to update this ledger

1. **Append, do not overwrite.** Add a dated row (or a dated subsection) with the
   new label and its path. An old evidence label stays as written; if it is
   superseded, say so in the new row and point at it.
2. **A claim without a path is deleted.** No path, no row. "Verified", "works",
   "green" and "done" are not labels; use §1's five.
3. **Never promote a label by hand.** A test class becomes JVM-TESTED only when a
   gate record exists; a capture becomes DEVICE-VERIFIED only when its manifest
   names the frame. Not performed stays not performed until it is performed.
4. **Record disagreements instead of resolving them silently.** If two records
   state different facts (row 3 in §6 is the live example), the ledger keeps both
   and marks the conflict.
5. **Keep Gate 7 out.** Anything that signs, uploads, lists or publishes belongs
   to its own milestone with its own authorization.
