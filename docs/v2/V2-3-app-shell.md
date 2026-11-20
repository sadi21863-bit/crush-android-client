# V2-3 adaptive app shell

Status: **COMPLETE** (implemented and device-verified 2026-11-20, authorized by
the owner).
Authority: Revision 4.0 V2-3; brief 4.6 (shell and navigation), V2-A2
(adaptive breakpoints), V2-A8 (neutral accent consequence).

## 1. Scope

The shell owns navigation between the three primary destinations. It does
not change engine bootstrap, provider/key handling, run policy, trust,
permission semantics or any screen's business logic. Review and Diagnostics
remain pushed routes without shell chrome.

## 2. Decisions

| Decision | Value | Source |
|---|---|---|
| Primary destinations | Chat, Projects, Settings, in that order | brief 4.6, V2-A2 |
| Widths | `<600dp` bottom bar, `600-839dp` rail, `>=840dp` extended rail | brief 4.6 |
| Selected state | filled icon + indicator fill + always-visible label | brief 4.6, V2-A8 |
| Back stack | Chat is the root; at most one secondary destination above it | "no duplicate destination/back-stack regression" |
| Secondary destinations | Review/Diagnostics push; Onboarding replaces itself | brief 4.6 |
| Chat sheet | session list + New chat + Review; Settings/Projects removed | single source of truth |
| Top-level back arrows | removed from Settings and Projects | a tab does not own Back |

`navigatePrimary` is the only function that switches primary destinations:
`popUpTo(Chat) { saveState = true }`, `launchSingleTop`, `restoreState`, so
repeated bar taps replace rather than stack.

## 3. What changed (CODE)

- New `ui/navigation/PrimaryDestinations.kt`: destination enum (routes,
  labels, filled/outlined icons), `shellLayoutFor(route, widthDp)` and
  `navigatePrimary`.
- New `ui/navigation/CrushShell.kt`: bottom bar / rail / extended rail,
  hidden on non-primary routes; consumes the insets the bar already applied
  so the chat composer does not double-pad above it.
- `MainActivity` hosts `NavGraph` inside `CrushShell`, passing the pinned
  launch route as the first-frame fallback.
- `NavGraph`: Chat loses the two destination callbacks; Projects/Settings
  lose `onBack`.
- Chat: `ChatAction.OpenSettings`/`OpenProjects` and
  `HostDestination.Settings`/`Projects` removed with their sheet buttons;
  `ChatDispatchTest`'s interaction ledger drops from 37 to 35 rows (22 action
  subtypes, 4 host continuations). Review keeps its sheet entry and the
  file-changes link.
- `strings.xml`: the three destination labels; never icon-only.

## 4. Invariants preserved

- **Finding 12:** the launch route is still decided once by
  `LaunchRouteState`; the shell never derives it from settings.
- **Project open:** still `popUpTo(Chat) { inclusive = true }` +
  `launchSingleTop`, so a project row cannot oscillate.
- **Lock:** still an overlay outside the NavHost, not a route.
- **Engine/session identity:** untouched; navigation only moves the
  controller.
- **Run policy/trust:** no action or ViewModel path changed.

## 5. Verification

| Check | Status |
|---|---|
| `PrimaryDestinationsTest`: exactly three routes; 599.9/600/839.9/840 boundaries; non-primary hidden at all widths | PASS (6 tests) |
| `ShellNavigationTest`: phone bar, rail at 800dp, extended rail at 1000dp, hidden on Review, Chat root, no duplicate after Projects->Settings->Chat, back returns to Chat, chat entry id survives a detour, rapid taps do not stack | PASS (8 tests) |
| `SettingsContentTest`/`ProjectsContentTest`: top-level screens expose no Back control | PASS |
| `ChatDispatchTest`: 35-row ledger, 22 action subtypes, 4 host continuations | PASS |
| Four-task gate | **PASS**: `v2-3-gate-final-1120-083146.log` - 885 tests / 0 failures / 0 skipped (95 suites); lint 0 errors, 1 pre-existing `NotShrinkingResources` warning |
| Phone device parity (bottom bar, sheet, Back, project open, IME) | **PASS on one phone**: captures 01-07, 10-12 in `docs/parity/2026-11-20-v2-3-shell/` |
| Large-screen emulator/device matrix | **NOT PERFORMED**: `emulator -accel-check` reports no hypervisor driver (code 6), and the only AVD is arm64. Robolectric renders the rail at 800dp and the extended rail at 1000dp; landscape on the phone produced a real rail (window <840dp after insets) but no extended rail |
| Rotation / process recreation | **PASS for route preservation**: portrait Chat -> landscape Chat -> portrait Chat, and Settings -> landscape Settings; the Activity was recreated (logcat `destroy`/`create`). Extended-rail layout and process death were not exercised |

## 6. Device evidence (2026-11-20)

`docs/parity/2026-11-20-v2-3-shell/MANIFEST.md` - 12 captures from
`bbf8ca69` (API 29, 393dp portrait). The phone pass covered: the bottom bar
with three labels in light and dark, selection by pill + filled icon, the
sheet with Settings/Projects removed, Projects and Settings without back
arrows, Back returning to Chat, the IME above the bar, Opening the Legacy
workspace row returning to Chat, and rotation preserving the route.

Landscape (850.9dp physical) produced the compact **rail**, not the extended
rail: the app window excludes the display cutout and the 3-button navigation
bar, so it is below 840dp. The extended rail is covered only at 1000dp in
Robolectric (recorded as F-K in the manifest).

## 7. Honest limits

- The phone pass can only run on API 29, 393dp portrait / ~795dp landscape
  (one device).
- The extended rail (>=840dp) has never run on real hardware.
- No posture/foldable handling beyond width breakpoints.
- The chat sheet loses two shortcuts; that is an intentional navigation
  change, recorded in `ChatDispatchTest`'s 35-row ledger.
- Populated transcript, tool row, diff, 2.0x font scale, reduced motion,
  density overrides, API 30+ and instrumented tests are NOT PERFORMED.
