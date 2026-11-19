# V2-1 design foundation (proposal)

Status: **PROPOSED, not approved.** Owner gate **O2**. Date 2026-11-19.

This document is the V2-1 deliverable that asks for a decision. It changes
no product source, test, Gradle file or navigation code, adds no preference
and touches no permission, persistence or engine semantics. V2-2 may not
start until the owner answers section 9.

Authority: Revision 4.0 V2-1 task list (design tokens, component states,
navigation prototypes, theme/density), constrained by the November 14
Rev 3.1 brief sections 4.2-4.9 and by the accepted decisions in
`ADR-REGISTER.md` (V2-A1 to V2-A7).

## 1. What V2-1 must deliver

| Rev 4.0 task | Status after this document |
|---|---|
| 1. Color, type, spacing, shape, elevation, motion, icon tokens | DONE (proposal) |
| 2. Shared components and state variants | PARTIAL: state catalogue only; no component mocks |
| 3. Phone navigation prototypes (drawer / bottom bar / switcher) | NOT PERFORMED: needs authorization, adds a screen |
| 4. Tablet rail/sidebar and list-detail prototypes | NOT PERFORMED: O3, and no device above 393dp |
| 5. Chat empty/populated/streaming/error prototypes | NOT PERFORMED (same reason as 3) |
| 6. Project home, project chat, Review, file-change prototypes | NOT PERFORMED (same reason as 3) |
| 7. Theme, density, accessibility behavior | DONE (spec) |
| 8. Validate flows against current app behavior | PARTIAL: device evidence for 15 surfaces, not a walkthrough of every flow |

V2-1 exit requires an approved spec **and** screen-state catalogue **and**
complete phone flows **and** specified tablet behavior. Three of those are
met or partly met; the prototype tasks are honestly open. V2-1 is
**PARTIAL**, not done.

## 2. Method and limits

- Contrast is WCAG 2.x relative luminance over sRGB, computed on the exact
  hex values below. It is not an APCA audit and no colour-vision-deficiency
  simulation was run. Ratios are reported, not asserted as "accessible"
  beyond the text-size rule they satisfy.
- Measured against the shipped theme at `app/src/main/java/com/crush/
  mobile/ui/theme/` (49-line `Color.kt`, 98-line `Theme.kt`, 76-line
  `Type.kt` at the locked baseline).
- Surface evidence comes from `docs/parity/2026-11-19-v2-0-baseline/`
  (device `bbf8ca69`, API 29, 1080x2340 @440 = about 393dp, app 1.0.0).
- No prototype exists yet, so nothing in this document is a visual
  validation. Every "target" is a specification, not an observation.

## 3. Current theme, measured

| Current token | Value | Pair measured | Ratio | Verdict |
|---|---|---|---|---|
| `OpenCodePrimary` | `#6C5CE7` | on `LightBackground` `#F8F9FA` | 4.61 | passes normal text, no headroom |
| `OpenCodePrimary` | `#6C5CE7` | on `LightSurface` `#FFFFFF` | 4.86 | passes |
| `onPrimary` | `#FFFFFF` | on `#6C5CE7` | 4.86 | passes |
| `OpenCodeAccent` / `tertiary` | `#FD79A8` | on `#FFFFFF` | **2.48** | **fails 4.5 as text** |
| `OpenCodeSecondary` / `SuccessColor` | `#00B894` | on `#FFFFFF` | **2.54** | **fails 4.5 as text** |
| `WarningColor` | `#FDCB6E` | on `#FFFFFF` | **1.51** | **fails even 3.0** |
| `ErrorColor` | `#E74C3C` | on `#FFFFFF` | **3.82** | fails 4.5 normal text |
| `SuccessColor` | `#00B894` | on `DarkSurface` `#1A1A2E` | 6.73 | passes in dark |
| `DarkBackground` | `#0F0F1A` | n/a | n/a | blue-tinted near-black; brief 4.4 rejects blue |
| `LightDiffAdded` / `LightDiffRemoved` | `#1B5E20` / `#B71C1C` | on `#FFFFFF` | 7.87 / 6.57 | already pass; keep |
| `DarkDiffAdded` / `DarkDiffRemoved` | `#A5D6A7` / `#EF9A9A` | on `#1A1A2E` | 10.38 / 7.93 | already pass; keep |

These are live defects, not hypotheticals:

- `tertiary` (`#FD79A8`, 2.48:1) is used **as text** in three places:
  `ReviewContent.kt:91` ("A turn is running; revert is disabled"),
  `SettingsContent.kt:173` ("key stored"), and `SettingsContent.kt:600`
  (the AUTO/MANUAL performance label).
- `secondary` (`#00B894`, 2.54:1) is a scheme slot with no text contract
  today, but it is the value behind `SuccessColor`/`ToolCompletedColor`,
  so any success text added later starts from a failing colour.
- The dark background and surface are blue-black (`#0F0F1A`, `#1A1A2E`);
  brief 4.4 says near-black, **not blue**.

Structural findings (no pixel claim, verified in source):

- **No spacing, shape or dimension tokens exist.** There is no `Spacing`,
  `Dimens` or token object; literals are inline.
- **The type scale is stretched.** Slot usage across `ui/`: `bodySmall`
  (12sp) **52** call sites, `labelSmall` (10sp) 44, `bodyMedium` (14sp) 24,
  `titleMedium` 12, `labelMedium` 11, `bodyLarge` 7, `headlineMedium` 2,
  `labelLarge` 2, `titleLarge` 1. `bodySmall` is used for prose, not only
  metadata (for example the chat empty-state line at `ChatContent.kt:558`
  and sheet descriptions at `ChatContent.kt:1447`).
- **Monospace is inline, not a token.** `FontFamily.Monospace` appears at
  14 call sites across `ChatContent.kt` and `MarkdownText.kt`.
- `headlineMedium` (20sp) is used twice; `displayLarge` (32sp) is unused.

## 4. Proposed tokens

### 4.1 Colour

Roles are semantic. `MaterialTheme.colorScheme.tertiary` must not be used
for status; state colour comes from `LocalCrushColors`.

Light:

| Role | Hex | Ratio | Use |
|---|---|---|---|
| `paper` | `#FAF8F4` | base | window background (warm near-white) |
| `surface` | `#FFFFFF` | - | cards, sheets, composer |
| `ink` | `#1C1B18` | 16.24 paper / 17.22 surface | primary text |
| `muted` | `#5C5850` | 6.67 paper / 7.08 surface | secondary text |
| `faint` | `#8A857B` | 3.46 paper / 3.67 surface | **disabled/large only**, never prose |
| `separator` | `#E5E1D8` | 1.23 paper / 1.30 surface | decorative 1dp rule only |
| `accent` | `#5A46D6` | 6.03 paper / 6.40 surface | actions, links, selected |
| `onAccent` | `#FFFFFF` | 6.40 on accent | text on accent fills |
| `accentSoft` | `#EDE9FB` | 14.47 with ink | selected row tint |
| `success` | `#1B6B4A` | 6.09 paper / 6.46 surface | succeeded, stored key |
| `warning` | `#8A5A00` | 5.59 paper / 5.93 surface | running, revert-disabled notice |
| `error` | `#B3261E` | 6.16 paper / 6.54 surface | failure, destructive |
| `diffAdded` | `#1B5E20` | 7.87 on surface | added line text (kept) |
| `diffRemoved` | `#B71C1C` | 6.57 on surface | removed line text (kept) |
| `diffAddedBg` | `#E8F3EC` | 15.14 with ink | added line tint |
| `diffRemovedBg` | `#FBEAEA` | 14.81 with ink | removed line tint |

Dark:

| Role | Hex | Ratio | Use |
|---|---|---|---|
| `bg` | `#121210` | base | neutral warm near-black, not blue |
| `surface` | `#1B1B18` | - | cards, sheets, composer |
| `ink` | `#EDEBE5` | 15.73 bg / 14.48 surface | primary text |
| `muted` | `#A8A49B` | 7.55 bg / 6.94 surface | secondary text |
| `faint` | `#7E7A72` | 4.39 bg / 4.04 surface | disabled only |
| `separator` | `#2A2A26` | 1.30 bg / 1.20 surface | decorative 1dp rule only |
| `accent` | `#A99BFF` | 7.88 bg / 7.25 surface | actions, links, selected |
| `onAccent` | `#1A1533` | 7.36 on accent | text on accent fills |
| `accentSoft` | `#2A2545` | 12.16 with ink | selected row tint |
| `success` | `#7BD3A8` | 10.47 bg / 9.64 surface | succeeded |
| `warning` | `#F0B860` | 10.48 bg / 9.64 surface | running |
| `error` | `#FF8A80` | 8.22 bg / 7.56 surface | failure |
| `diffAdded` | `#A5D6A7` | 11.41 bg / 10.50 surface | added line text (kept) |
| `diffRemoved` | `#EF9A9A` | 8.72 bg / 8.02 surface | removed line text (kept) |
| `diffAddedBg` | `#1E2A22` | 12.50 with ink | added line tint |
| `diffRemovedBg` | `#2E1E1E` | 13.34 with ink | removed line tint |

Semantic roles map onto the base colours but stay **separate fields** so
they can diverge later without touching screens:

| Role | Maps to | Note |
|---|---|---|
| `diffAdded` / `diffRemoved` | base + tinted background | keep existing `LocalDiffColors` mechanism, extend it |
| `toolRunning` | `warning` | amber, never a spinner colour alone |
| `toolSucceeded` | `success` | - |
| `toolFailed` | `error` | - |
| `pending` | `muted` | unknown/not-collected stays visibly distinct from zero |
| `permissionAsk` | `accent` | text label always present, never colour-only |

Delivery: one `LocalCrushColors` (`staticCompositionLocalOf`) provided by
`CrushMobileTheme` next to the existing `LocalDiffColors`, selected by the
same compiled `themeMode` decision. No per-screen colour literals.

The accent stays the brand violet, darkened for contrast; the brief's
"one restrained accent" is satisfied because success, warning and error
are state roles, not decorative accents.

### 4.2 Spacing

| Token | dp | Use |
|---|---|---|
| `xs` | 4 | icon-to-label, tight inline gaps |
| `sm` | 8 | related controls, chip padding |
| `md` | 12 | list-row vertical padding, card padding |
| `lg` | 16 | screen gutter, card padding |
| `xl` | 24 | section gap |
| `xxl` | 32 | empty-state and onboarding blocks |

Derived (not in the brief, stated so it is reviewable): screen gutter 16,
adjacent touch targets at least 8 apart, transcript paragraph gap 12.

### 4.3 Shape

| Token | dp | Use |
|---|---|---|
| `small` | 8 | chips, tags, inline code |
| `standard` | 12 | inputs, cards, rows |
| `large` | 16 | dialogs, panels, popovers |
| `sheet` | 20 | top corners of modal sheets |

Full-round is reserved for avatars and status dots. No shape is decided
per screen.

### 4.4 Type

| Token | Size / line / weight | M3 slot | Use |
|---|---|---|---|
| `title` | 22 / 28 / Medium | `titleLarge` | screen and conversation title |
| `section` | 16 / 24 / Medium | `titleMedium` | section headers, dialog titles |
| `body` | 15 / 22 / Regular | `bodyLarge` | transcript, prose, empty-state sentences |
| `secondary` | 13 / 18 / Regular | `bodyMedium` | supporting prose, sheet descriptions |
| `metadata` | 12 / 16 / Regular | `bodySmall` | timestamps, counts, ids, labels |
| `code` | 13 / 20 / Regular, `FontFamily.Monospace` | `LocalCrushType.code` | code blocks, diffs, tool payloads |
| `control` | 14 / 20 / Medium | `labelLarge` | buttons and clickable labels |

Rules:

- Prose is `body` (15). A sentence never uses `metadata` (12).
- `title` is the largest size in product screens; `displayLarge` is not
  used. Onboarding may use `title` only.
- No bundled font. `FontFamily.Default` for UI and the platform
  `Monospace` for code: offline build, no APK growth, no licence surface.
- Text containers must survive 2.0x font scale; no fixed text heights.

### 4.5 Dimensions

| Token | Value | Source |
|---|---|---|
| `touchTarget` | 48dp min, 8dp apart | brief 4.5, 4.8 |
| `topBar` | 56dp | brief 4.5 |
| `bottomBar` | 80dp + navigation inset | brief 4.6, V2-A2 |
| `composerMin` | 56dp | brief 4.5 |
| `composerMaxLines` | 8 | brief 4.5 |
| `toolRowCollapsed` | 40dp | brief 4.5 |
| `transcriptMaxWidth` | 720dp at expanded widths | brief 4.5 |
| `sheetMaxWidth` | 640dp | derived, for wide windows |
| `gutter` | 16dp | derived from `lg` |

### 4.6 Elevation

0dp on all surfaces. Separation is a 1dp `separator` rule, never a shadow.
Sheets use a scrim plus `surface`, not elevation. No gradient headers, no
card dashboards, no hero empty states, no decorative charts (brief 4.4).

### 4.7 Motion

- Overlays (sheets, dialogs, menus): 180ms, ease-out. Within the brief's
  150-200ms band.
- **No animation on text-changing nodes while streaming.** No enter
  animation per token, no layout animation on tool rows while a tool runs.
- Nothing else animates: no page transitions beyond platform defaults, no
  ambient motion.
- When the system animator duration scale is 0, overlays appear instantly.

### 4.8 Icons

`material-icons-extended` is already a dependency
(`app/build.gradle.kts:262`). Default 24dp, inline 20dp, tint from
`LocalContentColor`. The selected bottom-bar destination uses the filled
variant **and** colour **and** its always-visible label (brief 4.6: never
icon-only).

## 5. Migration map (V2-2, only after approval)

| Current | Proposed | Action |
|---|---|---|
| `OpenCodePrimary` `#6C5CE7` | `accent` `#5A46D6` | value change; dark uses `#A99BFF` |
| `LightBackground` `#F8F9FA` | `paper` `#FAF8F4` | warm the base |
| `LightSurface` `#FFFFFF` | `surface` `#FFFFFF` | unchanged |
| `LightOnBackground` `#1A1A2E`, `LightOnSurface` `#2D3436` | `ink` `#1C1B18` | one text colour, not two |
| `DarkBackground` `#0F0F1A` | `bg` `#121210` | remove the blue cast |
| `DarkSurface` `#1A1A2E` | `surface` `#1B1B18` | remove the blue cast |
| `OpenCodeSecondary` / `SuccessColor` `#00B894` | `success` `#1B6B4A` / `#7BD3A8` | 2.54 -> 6.46 on surface |
| `WarningColor` / `ToolRunningColor` `#FDCB6E` | `warning` `#8A5A00` / `#F0B860` | 1.51 -> 5.93 on surface |
| `ErrorColor` / `ToolErrorColor` `#E74C3C` | `error` `#B3261E` / `#FF8A80` | 3.82 -> 6.54 on surface |
| `OpenCodeAccent` (`tertiary`) `#FD79A8` | no text role | see the three call sites below |
| `LightDiffAdded/Removed`, `DarkDiffAdded/Removed` | keep values | already measured good; add tinted backgrounds |

`tertiary` call sites need a deliberate role, not a rename:

- `ReviewContent.kt:91` revert-disabled notice -> `warning` (a caution).
- `SettingsContent.kt:173` "key stored" -> `success` (a positive state).
- `SettingsContent.kt:600` AUTO/MANUAL label -> per-branch review:
  automatic is `muted`, overridden (`MANUAL`) is `accent`. Neither is a
  warning. Note the current emphasis is inverted: AUTO takes `primary`
  while MANUAL takes the pink caution colour.

`bodySmall` has 52 call sites and is not a mechanical rename: prose moves
to `body` (15), true metadata stays at 12. Section 9 Q4 asks whether that
reclassification is in V2-2 scope.

## 6. Screen-state catalogue (phone, 393dp)

Evidence labels: **CODE** = present in source; **DEVICE** = captured on
`bbf8ca69` on 2026-11-19; **NOT DONE** / **NOT PERFORMED** as stated.

### Shell and navigation

| Surface / state | Today | Evidence |
|---|---|---|
| Three primary destinations | no shell component; Chat/Projects/Settings are reached from the sessions sheet | CODE (`NavGraph.kt`); DEVICE `01-sessions-sheet-unavailable.png` |
| Bottom bar <600dp | does not exist | NOT DONE (V2-3) |
| Rail 600-839dp, drawer/extended >=840dp | does not exist | NOT DONE (V2-3, O3) |
| Back from a pushed route | returns to the bound chat surface | DEVICE `06-sheet-after-back.png` |

### Chat

| State | Today | Evidence |
|---|---|---|
| Empty, engine stopped | honest empty copy; subtitle can say `connecting to agent` while stopped | DEVICE `11-chat-empty-stale-phase.png`; defect F-E |
| Composer focused | focus ring, keyboard, insets respected | DEVICE `12-composer-focused.png` |
| Streaming | text replaces, does not append; no per-token animation | CODE; 2026-11-15 `after-f12` captures |
| Tool running / succeeded / failed | name, action, status, duration; expandable | CODE; no fresh device capture |
| Error / finish error | `ErrorCard`; `finishError` checked | CODE |
| Stop, unconfirmed stop | Stop tappable while streaming; `stopNotice` path | CODE; fault injection not possible via adb on this ROM |
| Permission dialog (3 decisions) | fail-closed, dismiss denies | CODE; no device capture |
| Question dialog, pending question | notice with one release action | CODE |
| Handoff consent | sheet + trust flow | CODE |
| Populated transcript | works | DEVICE 2026-11-15 `after-f12` (not re-run 2026-11-19) |

### Sheets

| State | Today | Evidence |
|---|---|---|
| Sessions, engine stopped | "Chats aren't available - send a message to start it" | DEVICE `01-sessions-sheet-unavailable.png` |
| Sessions, populated | list with active project named | CODE |
| Sessions, loading | no loading state | NOT DONE (O11) |
| Models, fresh chat | opens; subtitle names OpenCode Zen while Settings selects Go; no selection; no loading | DEVICE `10-model-sheet.png` |
| Models, scrolled | free-named rows carry no FREE badge | DEVICE `10b-model-sheet-lower.png` |
| Models, error | `modelsError` string exists | CODE |

### Projects

| State | Today | Evidence |
|---|---|---|
| Empty / import-only header | import affordance, explanation | CODE |
| Populated | rows, legacy row openable without Remove | DEVICE `07-projects.png` |
| Active (`activeBinding`) | **no Current marker**; active row styled like every other row | DEVICE `08-projects-lower.png`; NOT DONE |
| Remove confirmation | named target | CODE |

### Review

| State | Today | Evidence |
|---|---|---|
| Empty | "Nothing collected yet" plus process-scope copy | DEVICE `09-review-empty.png` |
| Populated, newest first | cards with counts, per-file diff | CODE; NOT PERFORMED on device |
| Turn in flight | revert disabled notice (pink, 2.48:1) | CODE; `ReviewContent.kt:91` |
| Older card read-only | states it is read-only | CODE |
| Export | byte-exact | CODE |

### Settings

| State | Today | Evidence |
|---|---|---|
| Top: providers, selected radio, stored-key chips | 5 providers incl. Groq; Go selected | DEVICE `02-settings-top.png` |
| Keyboard + permissions | "ASK EACH TIME"; profile `MID - API 29 - 3615MB - 8 cores` | DEVICE `03-settings-keyboard-permissions.png` |
| Performance + About | AUTO/MANUAL rows; About shows `Version 1.0.0` only | DEVICE `04-settings-performance-about.png` |
| Diagnostics entry | pushed route | CODE |

### Diagnostics

| State | Today | Evidence |
|---|---|---|
| Status | app/version, API, device, ABIs, engine file, engine stopped, "not checked" | DEVICE `05-diagnostics-status.png` |
| Engine pinned version + contract level | **absent** | DEVICE `05-diagnostics-status.png`; NOT DONE |
| Log tail | readable in-app | DEVICE `05b-diagnostics-log.png` |
| Copy / share + redaction | present; report-level redaction untested | CODE; test NOT DONE |

### Onboarding and lock

| State | Today | Evidence |
|---|---|---|
| Welcome -> provider -> key -> save -> chat | saves without validating | CODE; no device capture (would need a data wipe) |
| Lock overlay | `AppLockPolicy.decide`, never a route, chat refuses bootstrap while locked | CODE; log evidence only (secure window cannot be screenshotted) |

## 7. Navigation decision sheet

Accepted direction (V2-A2, not reopened here): bottom bar below 600dp,
rail 600-839dp, drawer or extended rail at 840dp and above; Projects may
split at wide widths.

| Phone option | Cost | Benefit |
|---|---|---|
| Bottom bar, 3 labels, filled+colour selected | 80dp + inset of a 393dp-wide viewport (about 10% height) | persistent, discoverable, matches brief 4.6 and V2-A2 |
| Drawer / sheet switcher (today) | zero permanent chrome | hides all three destinations behind a gesture; no selected state |
| Compact top-level switcher in the top bar | fits inside 56dp | least discoverable; competes with title and model chip |

**Prototypes are NOT PERFORMED.** Deciding this by intuition is exactly
what brief 4.6 forbids, and building a prototype adds a screen, which
needs authorization.

One interpretation is recorded rather than silently resolved: brief 4.2
principle 1 budgets "permanent chrome <= 56dp on a 360dp phone", while
brief 4.6 requires an 80dp bottom bar. Read together with V2-A2, the 56dp
figure is the top-bar/transcript budget and the bottom bar is separate.
If the owner meant total chrome, V2-A2 must be revisited before V2-3.

Wide-window behaviour is **specified, not validated**: no device above
393dp is attached, and O3 keeps list-detail panes out of V2.0. No tablet
or foldable claim may cite this document.

## 8. Accessibility, theme and density

- **Theme:** `themeMode` stays `system` / `light` / `dark`. No new
  appearance preference (V2-A4). Dark becomes warm-neutral `#121210`.
- **Density:** no new preference. Default M3 density; layout must survive
  the system font scale at 1.0, 1.3 and 2.0 without clipping or overlap.
- **Contrast:** prose and metadata at least 4.5:1; large text at least
  3.0:1; non-text boundaries that carry state at least 3.0:1. Decorative
  separators are exempt and are never the only signal.
- **Never colour alone:** tool status carries text, diff lines carry `+`
  and `-`, selected navigation carries the filled icon and its label,
  `pending` says "not collected" rather than showing a zero.
- **Targets:** 48dp minimum, 8dp apart; every icon button gets a content
  description; all three permission actions are understandable out of
  context.
- **Focus:** logical order; sheets and dialogs trap focus and restore it;
  live regions announce once, not per token.
- **Motion:** system animator scale 0 disables overlay animation.

## 9. Open questions (blocking V2-1 exit)

| # | Question | Recommended answer |
|---|---|---|
| Q1 | Accent hue: brand violet, darkened to `#5A46D6` (light) / `#A99BFF` (dark), or a neutral graphite accent? | Keep the violet: it is the only brand signal and now clears 6.0:1 |
| Q2 | Body text: 15sp per brief, or keep the current dominant 12-14sp? | 15sp. It costs lines per screen; legibility and the brief both win |
| Q3 | Code font: platform `Monospace`, or bundle a font? | Platform monospace: offline, no APK growth, no licence surface |
| Q4 | V2-2 scope: theme/token files only, or also the mechanical call-site reclassification (52 `bodySmall`, 3 `tertiary`, 14 inline monospace)? | Include it, reviewed screen by screen; otherwise the new scale is cosmetic and prose stays at 12sp |

Answering Q1-Q4 and approving sections 4-8 is the V2-1 visual-direction
sign-off. It authorizes V2-2 token work, not V2-3 shell work.

## 10. Boundary

Proposal only. No source, test, Gradle or navigation change; no preference,
permission, persistence or engine semantics change; no prototype built; no
device claim beyond the 2026-11-19 captures. Nothing here may be cited as
approved or implemented.

## Appendix: reproduce the measurements

```powershell
function L([string]$hex) {
  $h = $hex.TrimStart('#'); $r=[Convert]::ToInt32($h.Substring(0,2),16)/255
  $g=[Convert]::ToInt32($h.Substring(2,2),16)/255; $b=[Convert]::ToInt32($h.Substring(4,2),16)/255
  $f={ param($c) if ($c -le 0.03928) { $c/12.92 } else { [Math]::Pow((($c+0.055)/1.055),2.4) } }
  0.2126*(& $f $r) + 0.7152*(& $f $g) + 0.0722*(& $f $b)
}
function C([string]$a,[string]$b) {
  $la=L $a; $lb=L $b; $hi=[Math]::Max($la,$lb); $lo=[Math]::Min($la,$lb)
  [Math]::Round((($hi+0.05)/($lo+0.05)),2)
}
C '#FD79A8' '#FFFFFF'   # 2.48 - current tertiary on white
C '#5A46D6' '#FAF8F4'   # 6.03 - proposed accent on paper
```
