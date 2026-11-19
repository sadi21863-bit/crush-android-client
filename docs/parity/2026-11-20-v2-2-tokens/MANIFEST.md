# V2-2 token pilot - device captures (2026-11-20)

Device: Redmi Note 7 Pro (`bbf8ca69`), API 29, arm64-v8a, 1080x2340 @440
(~393dp). App `com.crush.mobile` 1.0.0 (1), debug APK built by gate attempt 4
(`v2-2-gate-1120-021050.log`, 871 tests / 0 failures, lint 0 errors).
Engine: stopped during the captures (version/contract not re-queried; F-D
from the V2-0 baseline still stands).

Captured by `adb shell screencap -p /sdcard/<name>.png` + `adb pull`
(never `exec-out >`). Rendered-to-real scale ~1.17. `uiautomator dump` is
unusable on this ROM.

## Captures

| File | Surface | Proves | Does NOT prove |
|---|---|---|---|
| `01-current.png` | Chat empty state, light | warm paper base, graphite text/accent, no violet, 15sp empty-state line | streaming or populated transcript |
| `02-menu.png` | chat sessions sheet, light | filled graphite primary action, outlined secondaries, 15sp prose, section-sized empty-state title | sheet behaviour changed (it did not) |
| `03-settings.png` | Settings top, light | graphite section headers, "key stored" success green (was 2.48:1 pink), 15sp prose; cropped above the API card, so no key hint is present | any key value (none is shown) |
| `04-settings-bottom.png` | Tuning + About, light | 12sp metadata line, AUTO/MANUAL roles, 48dp segments, outlined action | tuning behaviour |
| `05-after-report.png` | Diagnostics pilot, **pre-fix** light | pilot components render; card fill is 1.02:1 and reads as no container (F-H) | post-fix appearance |
| `06-settings-appearance.png`, `07-appearance.png` | navigation intermediates | theme selector reached | nothing claimed |
| `08-dark-settings.png` | Settings **pre-fix** dark | warm near-black, off-white text, selection roles | nav bar was white (F-I) |
| `09-dark-about.png` | About **pre-fix** dark | dark outline button and metadata | nav bar was white (F-I) |
| `10-dark-diagnostics.png` | Diagnostics **pre-fix** dark | pilot in dark; cards barely separated | nav bar was white (F-I) |
| `11-dark-settings-fixed.png` | Settings dark, **post-fix** (cropped above the API card) | dark Provider card, off-white text, success green; no key hint present | navigation bar (cropped); F-I is verified in `12`/`13` |
| `12-dark-diagnostics-fixed.png` | Diagnostics dark, **post-fix** | **F-H fixed**: 1dp separator hairline makes both cards legible; nav bar dark | populated log/transcript states |
| `13-light-diagnostics-fixed.png` | Diagnostics light, **post-fix** | **F-H fixed** in light; nav bar white with dark icons (theme switch applied live) | 2.0x font scale |

## Findings

- **F-H - card boundary (fixed, DEVICE-VERIFIED 12/13).** `CrushCard`
  was fill-only; on paper the fill is 1.02:1 from the background and the
  light cards read as no container. The component now draws the brief's
  1dp `separator` hairline (no shadow, 0dp elevation).
- **F-I - navigation bar (fixed, DEVICE-VERIFIED 12/13).** The theme set
  only `statusBarColor`, so dark mode kept a white system navigation bar.
  `navigationBarColor` and `isAppearanceLightNavigationBars` now follow the
  same compiled theme decision as the status bar.
- **F-J - selection affordance (open, V2-4).** In Settings a selected
  segment ("15 min", "Light", "Dark", "Off") differs only by a subtle
  fill. With the violet accent gone, selection is weaker than before - the
  risk V2-A8 recorded. These are pre-existing segment/chip call sites, not
  `CrushChip`; the fix belongs to the chip/segment migration (filled check
  or weight + border), not a palette tweak.

## Limits

- `03-settings.png` and `11-dark-settings-fixed.png` are cropped above the
  API card, so the masked key hint is not in the repository at all. Nothing
  else was edited; no other capture is cropped or retouched.
- A screencap during the credential window shows the app beneath it, so a
  screenshot cannot prove the absence of a prompt. The owner unlocked the
  device for this run; the prompt was observed before each pass via
  `dumpsys activity activities`.
- No populated transcript, running tool row or file diff was captured with
  the new palette; those need a real file-changing turn.
- One device, 393dp, API 29. No tablet/wide-window or API 30+ claim.
- 2.0x font-scale rendering is NOT PERFORMED.
