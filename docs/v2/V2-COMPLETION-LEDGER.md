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
failures (`v2-12-gate-2026-11-23.log`). The V2-11 rows stay as written.

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
| 13 | External audit findings | `docs/v2/V2-GAP-AUDIT.md` verified A1-A14 with file:line. The V2-12 fix batch is recorded in `docs/v2/V2-AUDIT-FIXES.md` and sits inside the green gate (997/104/0): `A1` (one `selectPrimary` veto for all three layouts), `A3` ("Export current file" + historical read-only note), `A4` (duplicate Projects footer removed), `A5` (provider-qualified heading), `A8` (atomic reservation, terminal-failure release, pre-registration), `A10` (key save reports its result and keeps the draft), `A11` (delete confirmation), the auto-approve confirmation (`V2-AUDIT-4`), and `A14` (comments describe two project slots, chat on legacy) | `A7`/`A7b` (a rejected permission policy is logged and remembered as applied) still needs owner sign-off; `A12` (answering questions), `A2` (project home), `A6` (route/mode dual meaning — monitor only) and `A13` (runtime disposal) stay open; no device, TalkBack or API 30+ run covers any fixed row | Not for V2 exit; the audit names A7 as a release gate for any later shipping milestone |

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
confirmations).

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
