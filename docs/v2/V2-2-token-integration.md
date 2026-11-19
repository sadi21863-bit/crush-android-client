# V2-2 token integration and component pilot

Status: **COMPLETE against the Revision 4.0 exit criteria (2026-11-20)**:
tokens compile and are used by a small pilot surface; component and
accessibility tests pass; existing screens are behaviourally unchanged.
Two task-list items are only partly performed and are labelled so below
(screenshot tests, and the font-scale/reduced-motion/density checks).
The component set is deliberately not yet applied to every screen - that
is V2-3/V2-4 work.
Authority: Revision 4.0 V2-2 task list; `V2-1-design-foundation.md` sections
4-8 as approved by **V2-A8**; brief Rev 3.1 sections 4.5, 4.7, 4.8.

Evidence labels: CODE (source inspection), TEST (JVM/Robolectric),
DEVICE-VERIFIED, PARTIAL, NOT PERFORMED. No device screenshot is claimed in
this record unless a row says so.

## 1. Boundary

V2-2 changes visual tokens, type scale, shared components and the call-site
classification that the token change makes necessary. It does **not** change
business behaviour, persistence, permission semantics, engine/bootstrap
order or navigation. No layout-preference singleton, no new preference, no
new destination. V2-3 shell work is out of scope.

## 2. What landed (CODE)

### 2.1 Theme layer

| File | Change |
|---|---|
| `ui/theme/Color.kt` | warm-neutral palette, neutral graphite accent, semantic status colours, diff text + tint pairs, Material container/inverse neutrals. Replaces the violet/pink/green set |
| `ui/theme/Tokens.kt` (new) | `CrushColors` + `LocalCrushColors`, `CrushSpacing`, `CrushShape`, `CrushDimens`, `CrushType` |
| `ui/theme/Theme.kt` | M3 schemes specify every role the app reads; `crushColorScheme`/`crushColors`/`crushDiffColors` are internal for tests; `LocalCrushColors` and `LocalDiffColors` provided from the **compiled** theme mode |
| `ui/theme/Type.kt` | Material type slots mapped onto `CrushType` so existing `MaterialTheme.typography` call sites move with the scale |

Neutral accent consequence (V2-A8): the accent is 1.53:1 from `ink` in
light and 1.29:1 in dark, so state is never colour-alone — inline links are
underlined, selection uses `accentSoft` fill + filled icon + weight, and
`CrushChip` is read-only by construction.

### 2.2 Status-colour call sites (CODE)

`MaterialTheme.colorScheme.tertiary` now has **zero** call sites outside
`ui/theme/`. The three former pink-text sites moved to deliberate roles:

| Site | Was | Now |
|---|---|---|
| `ReviewContent.kt` revert-disabled notice | `tertiary` (2.48:1) | `CrushColors.warning` |
| `SettingsContent.kt` "key stored" | `tertiary` | `CrushColors.success` |
| `SettingsContent.kt` AUTO/MANUAL | `primary` / `tertiary` | `onSurfaceVariant` / `CrushColors.accent` |

Tool and diff state now read semantic fields (`toolRunning`,
`toolSucceeded`, `toolFailed`, `pending`) instead of overloading `primary`
or `onSurfaceVariant`. `ChatContent` engine-ready, tool-row and diff headers
changed accordingly.

### 2.3 Type reclassification (CODE)

- `bodySmall` 52 -> 8 call sites. The 44 migrated: prose -> `CrushType.body`
  (15sp), monospace payloads -> `CrushType.code` (13sp).
- The 8 remaining are metadata by the brief's definition: Diagnostics
  status label/value rows and log count; Settings provider key hint, masked
  key hint, version line, engine attribution, device-profile line.
- `FontFamily.Monospace` appears once, inside `CrushType.code`.
- Empty-state titles that were `bodyLarge` are now `CrushType.section`.

### 2.4 Components (new, `ui/components/CrushComponents.kt`)

`CrushSectionHeader`, `CrushStatusLabel` (words required, dot decorative),
`CrushCard` (0dp elevation, 1dp separation), `CrushChip` (non-clickable),
`CrushEmptyState`, `CrushLoadingRow` (polite live region), `CrushBanner`,
`CrushButton` (48dp min), `CrushIconButton` (48dp), `CrushSheet`,
`CrushDialog`.

Pilot surface: **Diagnostics**. Read-only, lowest behavioural risk; it uses
`CrushSectionHeader`, `CrushCard`, `CrushStatusLabel`, `CrushType.body` and
`CrushType.code`. Other screens consume the tokens but not yet every
component; that remains V2-3/V2-4 work.

### 2.5 Defect found and fixed (CODE)

The user bubble paints `primaryContainer` while `MarkdownText` defaulted to
`onSurface`: **2.05:1** in light theme. The bubble now passes
`onPrimaryContainer`. `CrushTokensTest` pins the pair.

### 2.6 Revision 4.0 task check

| Task | Status |
|---|---|
| 1. Semantic theme tokens and component styles | DONE |
| 2. Typography / spacing / shape scales | DONE |
| 3. Reusable buttons, icon buttons, cards, chips, sheets, dialogs, banners, empty states, loading states, section headers | DONE - all eleven exist in `CrushComponents.kt` |
| 4. Accessible status/provenance labels | DONE - `CrushStatusLabel` always carries words; the dot is decorative |
| 5. Screenshot/Compose tests for component variants | PARTIAL - Compose tests cover the variants; the repo has no screenshot-test harness, so device captures stand in for image comparison |
| 6. Theme, font scale, reduced motion and density verification | PARTIAL - theme verified by tests and device (light/dark); font scale, reduced motion and density are NOT PERFORMED |

## 3. Tests

| Test | What it pins | Status |
|---|---|---|
| `CrushTokensTest` (JVM) | text/accent/status/diff/container contrast floors, scheme-role mapping, type scale, spacing monotonicity, 48dp targets, diff-colour theme selection | TEST: passed in gate attempts 2-4 |
| `CrushComponentsTest` (Robolectric) | status label words, chip has no click action, 48dp icon/primary buttons, empty-state action, banner text, theme provider reaches composition | TEST: passed in gate attempts 2-4 (7 tests) |

## 4. Gate

Four-task gate (`testDebugUnitTest`, `assembleDebug`,
`assembleDebugAndroidTest`, `lintRelease`) with JDK 25.3.

| Attempt | Log | Result |
|---|---|---|
| 1 (2026-11-20 00:08) | `v2-2-gate-1120-000820.log` | **FAILED** in `:app:testDebugUnitTest`: 871 tests, 1 failure. `CrushComponentsTest.emptyStateShowsItsActionAndFires` asserted a button labelled "Import a folder" while the fixture passes "Import folder". Test typo only; fixed. The other tasks did not run because Gradle stopped at the failed task |
| 2 (2026-11-20 00:28) | `v2-2-gate-1120-002828.log` | **PASSED** in 36m49s: 871 tests / 0 failures / 0 skipped; `assembleDebug`, `assembleDebugAndroidTest` and `lintRelease` green. Lint: 0 errors, 3 warnings, two of them on the new component signatures (`Modifier` not the first optional parameter) |
| 3 (2026-11-20 01:08) | `v2-2-gate-1120-010839.log` | **PASSED** in 27m37s: 871 / 0 / 0; lint 0 errors, 1 warning (pre-existing `NotShrinkingResources`). Component signatures reordered |
| 4 (2026-11-20 02:10) | `v2-2-gate-1120-021050.log` | **PASSED** in 10m46s: 871 / 0 / 0; lint 0 errors, 1 warning (pre-existing). Includes the `CrushCard` hairline and the themed navigation bar found on device |

CI: run `44618219274` on head `36e5189` - **success** (the same four-task
gate on Temurin 21, engine-less APK by design).

## 5. Device evidence (2026-11-20)

Device `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a, 1080x2340 @440
(~393dp), app 1.0.0 (1). Pre-fix captures come from the gate-attempt-3 APK;
the fixed captures come from gate attempt 4 (`v2-2-gate-1120-021050.log`).
Captures and the full per-file ledger live in
`docs/parity/2026-11-20-v2-2-tokens/MANIFEST.md`.

| Capture | Surface | What it shows |
|---|---|---|
| `01-current.png` | Chat empty state, light | warm paper background, graphite header, muted subtitle, 15sp empty-state line, no violet anywhere |
| `02-menu.png` | chat sheet, light | filled graphite "New chat" with white text, outlined siblings, 15sp prose, `CrushType.section` title for "Chats aren't available" |
| `03-settings.png` | Settings top, light | section headers in graphite, "key stored" in the new success green (was 2.48:1 pink), 15sp explanatory prose. Cropped above the API card so no key hint is present |
| `04-settings-bottom.png` | Tuning + About, light | 12sp device-profile metadata, AUTO/MANUAL roles (muted vs accent), 48dp segmented options, outlined "Report a problem" |
| `05-after-report.png` | **Diagnostics pilot, light** | `CrushCard`, `CrushSectionHeader`, `CrushStatusLabel("Engine responding: not checked")` with words + decorative dot, monospace 13sp log |
| `08-dark-settings.png` | Settings, dark | warm near-black `#121210`, off-white text, cards at `#1B1B18`; **navigation bar still white** (F-I) |
| `09-dark-about.png` | About, dark | dark typography and outline button; nav bar still white |
| `10-dark-diagnostics.png` | Diagnostics pilot, dark | pilot components in dark; **cards barely separated, nav bar still white** |
| `11-dark-settings-fixed.png` | Settings, dark, post-fix (cropped above the API card) | dark Provider card, off-white text and success green; no key hint present. F-I is verified in `12`/`13` |
| `12-dark-diagnostics-fixed.png` | Diagnostics pilot, dark, post-fix | **F-H verified**: 1dp separator hairline makes both cards legible; nav bar dark |
| `13-light-diagnostics-fixed.png` | Diagnostics pilot, light, post-fix | **F-H verified in light**; theme switched live and the nav bar is white with dark icons |

`06-settings-appearance.png` and `07-appearance.png` are navigation
intermediates, not claims.

### Findings from the device pass

- **F-H (fixed, DEVICE-VERIFIED `12`/`13`):** `CrushCard` was fill-only. On
  paper the fill is 1.02:1 from the background and the light Diagnostics
  cards read as no container at all. Fixed by adding the brief's 1dp
  `separator` hairline to the component (not per screen).
- **F-I (fixed, DEVICE-VERIFIED `12`/`13`):** dark mode left the system
  navigation bar white - the theme set only `statusBarColor`. Fixed by
  setting `navigationBarColor` and `isAppearanceLightNavigationBars` from
  the same compiled theme decision.
- **F-J (open, V2-4):** in Settings, a selected segment ("15 min", "Light",
  "Dark", "Off") differs from its siblings only by a subtle fill. With the
  violet accent gone, selection is weaker than before, exactly as V2-A8
  predicted. These are pre-existing chip/segment call sites, not the new
  `CrushChip`; the fix is the chip/segment migration (filled check or
  weight + border), not a V2-2 palette tweak.

### Not proven by these captures

- Screenshots during/after a credential prompt cannot prove the absence of
  the prompt (the secure window is not captured). The owner unlocked the
  device for this pass.
- No populated transcript, tool row or diff was captured with the new
  palette: that needs a real file-changing turn.
- 2.0x font scale, wide windows and API 30+ behaviour remain NOT PERFORMED.

## 6. Honest limits

- **PARTIAL:** on-device visual verification covers Chat empty state, the
  chat sheet, Settings (light and dark), About and the Diagnostics pilot.
  A populated transcript, a running tool row and a file diff were **NOT
  PERFORMED** with the new palette (they need a real file-changing turn).
- `03-settings.png` and `11-dark-settings-fixed.png` are cropped above the
  API card so no key hint is stored in the repo; nothing else was edited.
- **NOT PERFORMED:** 2.0x font-scale rendering, reduced-motion behaviour and
  density verification. The spec forbids fixed text heights, the components
  contain no bespoke animation (Compose's platform duration scale applies),
  and sizing is in dp - but those are source arguments, not observations.
- **NOT PERFORMED:** wide-window layout checks. The only device is 393dp,
  API 29 (O3).
- **NOT PERFORMED:** dynamic-colour comparison. The app does not enable
  Material You; this was not changed.
- **OPEN (V2-4):** F-J, the weak selected-segment affordance in Settings,
  is recorded with device evidence but not fixed here - it belongs to the
  chip/segment migration.
- Icon audit: filled/outlined pairs for bottom-bar selection belong to V2-3
  because no bottom bar exists yet in this phase.
- `DiagnosticsScreen` has no pre-existing Compose test. Its pilot change is
  covered by source review, the component tests and the device captures;
  a report-injection seam for a render test is not added in this batch.
