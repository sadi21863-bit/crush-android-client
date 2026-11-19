# V2-0 - Baseline lock and design research (2026-11-19)

**Status: IN PROGRESS.** Planning record only. No source, test or build
file changed. Authority is hybrid (owner, 2026-11-19, §5.10 of
`../CRUSH-MOBILE-STATUS-AND-DECISIONS.md`): Revision 4.0 is the forward
roadmap; the November 14 Rev 3.1 brief remains authoritative for the UI
state contract, test registry, composer gates and parity standard.

This file locks the facts a V2 package must cite. It does not restate the
brief and it does not authorize implementation.

---

## 1. Locked checkpoint

| Fact | Value |
|---|---|
| Date of lock | 2026-11-19 |
| Branch | `main` |
| HEAD | `3457a8b2d9bd4235b702e7f37cc63387252a095a` |
| HEAD tree | `58b9f0e3a166d5eebcbde07261adee4b0ade466b` |
| Last code-bearing commit | `1679f25` (2026-11-18) |
| Docs commits above it | `96bf7ec`, `3457a8b` |
| Working tree | clean before this record |
| `origin/main` | same SHA at lock time |
| CI | run `44542121606` at `3457a8b`, success |
| Last local full gate | `run-f12-gate.log`, 2026-11-18 23:30, BUILD SUCCESSFUL 20m 24s |
| Device | `bbf8ca69` **not connected at lock time** |
| Engine of record | Crush v0.100.0, contracts 0.99.0 (v0.99/v0.100 wire families) |

`git diff 1679f25..3457a8b` is docs only, so the code under this lock is the
code that passed the 2026-11-18 gate. CI runs `96bf7ec` (`44541587089`)
and `3457a8b` (`44542121606`) are both green.

## 2. Toolchain and build

| Component | Version | Source |
|---|---|---|
| Gradle | 9.8.0 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.4.1 | `build.gradle.kts:2` |
| Kotlin (KGP) | 2.4.10 | `build.gradle.kts:8`, project-owned |
| Compose compiler plugin | 2.4.20 | `build.gradle.kts:12` |
| KSP | 2.3.13 | `build.gradle.kts:19` |
| Build JDK | 25.0.3 Android Studio JBR | `build-install.bat`; docs/toolchain.md |
| CI JDK | Temurin 21 | `.github/workflows/android-ci.yml:95` |
| Bytecode target | 17, deliberate | `app/build.gradle.kts` |
| compileSdk / targetSdk / minSdk | 37 / 36 / 29 | `app/build.gradle.kts:44,58,59` |
| Compose BOM | 2026.09.00 | `app/build.gradle.kts:255` |
| versionName / code | 1.0.0 / 1 | `app/build.gradle.kts:60,61` |
| namespace / applicationId | `com.crush.mobile` | `app/build.gradle.kts:41,47` |

CI pins Temurin 21 because Robolectric 4.17 needs a Java 21 runtime for API
36 sandboxes; the app bytecode target stays 17 (`2272d71`).

## 3. Architecture as implemented

```
Compose UI -> unix://files/crush.sock/v1 -> libcrush.so (bundled Go) -> provider API
```

- Transport is a Unix domain socket; there is no TCP listener (P1, 2026-10-18).
- Engine ships as `app/src/main/jniLibs/arm64-v8a/libcrush.so` (gitignored),
  executed from `nativeLibraryDir`.
- Three ViewModels own production state: `ChatViewModel`,
  `ProjectsViewModel`, `SettingsViewModel`.
- Since Phase 2b, each screen is split into a pure `*Content` composable fed
  by an explicit `*ViewState` plus callbacks, and a thin host that owns the
  ViewModel, platform launchers and navigation. This seam is the foundation
  V2 must build on, not replace.
- Chat and Code are separate engine pools/policies; project identity is
  `Project -> WorkspaceBinding -> Session -> Run`; trust is checked at bind.
- The launch route is pinned once per process (`LaunchRouteState`, Finding
  12 fix `1c902eb`); it is not derived from live settings.

## 4. Source inventory (verified at the locked tree)

### 4.1 Navigation destinations

`app/src/main/java/com/crush/mobile/ui/navigation/NavGraph.kt` defines 7
routes and 6 composables:

| Route | Composable | Role |
|---|---|---|
| `onboarding` | `OnboardingScreen` | start route when the selected provider has no stored key |
| `chat` | `ChatScreen` | primary destination |
| `projects` | `ProjectsScreen` | project-bound Code entry |
| `settings` | `SettingsScreen` | pushed destination |
| `review` | `ReviewScreen` | pushed plainly on chat (no `popUpTo`) |
| `diagnostics` | `DiagnosticsRoute` | pushed from Settings |
| `models` | **none** | dead `Screen.Models` object, `NavGraph.kt:16`; delete in V2-3 |

Nav arguments/back-stack rule in force: opening a project replaces the stack
back to a single chat entry (`NavGraph.kt:100-102`, ADR-0001 E5).

### 4.2 Content composables and state

| Screen | Content composable | View state | Host-owned work |
|---|---|---|---|
| Chat | `ChatContent.kt:59` | `ChatViewState.kt:17` | engine, SSE, permissions, handoff, export, sessions |
| Projects | `ProjectsContent.kt:59` | `ProjectsViewState.kt:14` | Room, SAF/AFA launchers, trust callbacks, routing |
| Review | `ReviewContent.kt:50` | `ReviewViewState.kt:13` | history, export, revert scope |
| Settings | `SettingsContent.kt:66` | `SettingsViewState.kt:16` | key vault writes, provider resolution, tuning |
| Onboarding | `OnboardingContent.kt:44` | `OnboardingViewState.kt:13` | Keystore save, async result, completion |

Chat also exposes host contracts at `ChatHostInputs.kt` (`ChatHostInputs`,
`ChatHostState`, `PendingTrustUi`) and actions in `ChatAction.kt` /
`ChatDispatch.kt`.

### 4.3 Chat-specific building blocks

`SessionList.kt`, `ModelChoice.kt` (catalogue ordering + free/unpriced
grouping), `ToolActivity.kt` (parsing), `MarkdownParser.kt` /
`MarkdownText.kt`, `ChatExport.kt`, `HandoffConsent.kt` +
`HandoffConsentSheet.kt`, `RunHandle.kt`, `ProviderSwitchPolicy.kt`.
Trust and lock UI sit outside the screens: `ui/CrushrcTrustDialog.kt`,
`screens/lock/LockOverlay.kt`.

### 4.4 Reusable component library

There is **no** `ui/components` package. Shared widgets currently live inside
the screen that first needed them (for example `FileDiffRow` inside
`ChatContent.kt:891`). V2-2 starts from a greenfield component layer; it must
extract rather than invent duplicates.

### 4.5 Theme

`ui/theme/Color.kt`, `Type.kt`, `Theme.kt`. Color values are raw brand
constants (`OpenCodePrimary` etc.); there is no spacing, shape, elevation,
motion or semantic-token layer. `Settings` already exposes a theme choice;
density/navigation/response-rendering preferences are backlog.

### 4.6 Tests

| Area | Files | `@Test` methods |
|---|---:|---:|
| `data` | 29 | 251 |
| `domain` | 12 | 167 |
| `engine` | 9 | 68 |
| `ui` | 34 | 328 |
| `di` | 1 | 5 |
| `project` | 1 | 8 |
| `util` | 1 | 23 |
| **Total** | **87** | **850** |

The full gate reports 850 tests across 91 suites (suite count includes
parameterised/derived suites). Content-layer coverage: Chat content 31
(`ChatContentGateTest` 8, `ChatContentSheetsTest` 14,
`ChatContentTranscriptTest` 9); `OnboardingContentTest` 10;
`ProjectsContentTest` 11; `ReviewContentTest` 10; `SettingsContentTest` 10.
`app/src/androidTest` holds 5 instrumentation files; CI compiles them but
does not execute them.

## 5. Parity ledger re-read (V2-0 task 2)

All five Phase 2b manifests plus the Finding 12 record were re-read. Nothing
below is upgraded by this record.

| Record | Scope | Result | Outstanding rows |
|---|---|---|---|
| `2026-11-15-c4-baseline` | pre-2b.1 baseline | Set A 10/10 captured, 9 PASS / 1 PARTIAL | baseline only; no after claim |
| `after-2b1` | 2b.1 Chat | Set B 24 PASS / 0 FAIL / 4 NOT PERFORMED / 0 BLOCKED; chat package 27 suites / 256 tests; gate 789/86 | Stop-unconfirmed, session delete, session empty/loading, notices |
| `after-2b2` | 2b.2 Projects | trust gate raised before bind; no marker on cancel; marker only when a typed character starts the engine | none new |
| `after-2b3` | 2b.3 Review | empty state + Back pixel-identical; gate 824/88 | populated Review card NOT PERFORMED (device) |
| `after-2b4` | 2b.4 Settings | Settings content pixel-identical; Back returns to the same chat; gate 834/89 | provider tap ejected to Onboarding = Finding 12, fixed in `1c902eb` |
| `after-2b5` | 2b.5 Onboarding | render parity and provider menu PASS; gate 844/90 | real key entry NOT PERFORMED (credential write, owner only) |
| `after-f12` | Finding 12 fix | device-verified: key-less tap stays in Settings, provider restored via UI, Back returns to the same chat | none |

Dispositions:

- **Stop-unconfirmed / notices** need `crushApi.cancel` to fail; adb cannot
  signal the app's engine on this ROM. Fault injection must run inside the
  app (spike `FAULT` intent) or under instrumentation.
- **Session delete** is destructive and was not re-run; the baseline FAIL and
  `T2B1-03`'s pin remain the evidence.
- **Session empty/loading** sits in a sub-second window and survived two
  manual retries. It needs an injected stall or instrumentation (this is
  O11); another manual pass will not close it.
- **Populated Review card** had no completed file-changing turn in that
  session; the rows remain unit-pinned, not device-driven.
- **Onboarding key entry** requires the owner's real credential; the agent
  must not perform it. Branches remain unit-pinned.

No outstanding item was marked closed by this re-read.

## 6. Device and form-factor coverage

| Fact | Value |
|---|---|
| Device | Xiaomi Redmi Note 7 Pro (`violet`), serial `bbf8ca69` |
| Android | API 29 |
| ABI | arm64-v8a |
| Display | 1080x2340, density 440 (about 393dp wide: compact width) |
| App | `com.crush.mobile` 1.0.0, debuggable |
| Engine | Crush v0.100.0, contracts 0.99.0 |

Cannot be validated on this device, and must not be claimed:

- API 30+ All Files Access rationale and behaviour.
- Medium/expanded window classes (600dp+): rail, drawer or list-detail.
- Tablets, foldables, desktop windowing, landscape large windows.
- API 36 runtime behaviour; only compile/target are set to 36.
- Instrumentation suite execution (compiled only in CI).

Existing capture set under `docs/parity/2026-11-15-c4-baseline/` is the
phone baseline of record. **Fresh V2-0 screenshots were NOT PERFORMED on
2026-11-19 because no device was attached.** Capture protocol when it is:
full-resolution `screencap`, pull as file (never `adb exec-out > file` on
this host), record device/API/build/engine, scale rendered->real about 1.17.

## 7. Gate and CI evidence

- Local, `1679f25`: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease` on JBR 25 ->
  **850 tests / 91 suites, 0 failures / 0 errors; lint 0 errors / 1 warning
  (`NotShrinkingResources`); BUILD SUCCESSFUL in 20m 24s**
  (`run-f12-gate.log`, 2026-11-18 23:30).
- CI, `3457a8b`: run `44542121606` success; `96bf7ec`: run `44541587089`
  success. CI builds an engine-less APK because `libcrush.so` is not
  committed; green CI is necessary, not sufficient.
- This V2-0 record changes Markdown only, so no gate was rerun for it.

## 8. Research log (V2-0 task 5, retrieved 2026-11-19)

| Reference | Dated state | Consequence |
|---|---|---|
| Android adaptive navigation guide | current page retrieved 2026-11-19: `NavigationSuiteScaffold` shows a navigation bar in compact/tabletop and a rail otherwise; a drawer can be selected at expanded widths | supports brief §4.6 breakpoints; V2-1 validates them, it does not reopen the pattern |
| AndroidX A2UI | `1.0.0-alpha02`, 2026-10-21; Compose renderer for agent-generated UI | alpha; cannot be a V2.0 dependency; evaluation stays parked with V2-8 |
| Vercel `json-render` | repo retrieved 2026-11-19; React/Vue/Svelte/Solid/React Native renderers; no Compose renderer | not adoptable for native Compose; design reference only |
| CopilotKit OpenIntelligentUI | repo retrieved 2026-11-19; Next.js front end + Python agent; custom UI streamed into a sandboxed HTML iframe | web architecture; design reference only |
| T3 Code | `pingdotgg/t3code`, MIT; desktop/web/phone; UI overhaul PR #24865 merged 2026-10-25 behind a Beta toggle. Third-party reconstruction cites v0.1.8 (not an upstream version claim) | comparative reference; screen-level inspection NOT PERFORMED |
| T3 Code assessment in Revision 4.0 | journalistic summary only | do not treat as evidence about this repo |

No framework, dependency or protocol from this list is adopted. A2UI and
structured UI stay parked with V2-8/V2-7; adaptive navigation remains a
Compose-first V2-1 prototype.

## 9. V2-0 task and exit ledger

| # | Task | Status | Evidence / next |
|---:|---|---|---|
| 1 | Record SHA, tree, toolchain, architecture, CI | DONE | sections 1-3, 7 |
| 2 | Re-read all five 2b manifests, close outstanding parity | DONE (re-read) / OPEN items | section 5; closures need device, fault injection or instrumentation |
| 3 | Source inventory | DONE | section 4 |
| 4 | Reconcile Rev 3.1 line by line | IN PROGRESS | `V2-0-reconciliation.md` |
| 5 | T3/A2UI/json-render/CopilotKit/adaptive research | DONE as reference refresh | section 8; no adoption |
| 6 | Fresh phone screenshots + unsupported form factors | NOT PERFORMED (no device) / list DONE | section 6 |
| 7 | ADR register for open owner decisions | DONE | `ADR-REGISTER.md` |

Exit criteria: "no unresolved disagreement about what is already
implemented" and "every V2 requirement has a source reference and an evidence
label" are only satisfied once task 4 lands and the owner accepts it. V2-0
still requires owner acknowledgement before V2-1 work.

## 10. Boundary

This record moved docs only. It authorizes no V2 implementation, no visual
change, no preference and no navigation change. The next source change must
be a separately authorized package with its own gate, device evidence and
rollback path.
