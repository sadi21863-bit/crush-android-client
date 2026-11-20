# V2-3 shell - device captures (2026-11-20)

Device: Redmi Note 7 Pro (`bbf8ca69`), API 29, arm64-v8a, 1080x2340 @440
(393dp portrait). App `com.crush.mobile` 1.0.0 (1), debug APK built by the
final four-task gate (`v2-3-gate-final-1120-083146.log`: 885 tests /
0 failures / 0 skipped, lint 0 errors, 1 pre-existing warning).
Engine: stopped at the start and end of the pass (the chat subtitle read
"engine stopped"); no turn was sent.

Captured by `adb shell screencap -p /sdcard/<name>.png` + `adb pull`
(never `exec-out >`). Rendered-to-real scale ~1.17. `uiautomator dump` is
unusable on this ROM, so no capture claims exact dp bounds.

The credential prompt appeared once after install (the activity was
recreated). The owner unlocked the phone; the agent entered nothing and
never saw the credential. No key was entered in Settings during this pass.

## Captures

| File | Surface | Proves | Does NOT prove |
|---|---|---|---|
| `01-chat-bottombar-light.png` | Chat, portrait, light | bottom bar with three always-visible labels; Chat selected by indicator fill + filled icon + label; composer sits above the bar with no double navigation inset | populated transcript, running tool row or diff |
| `02-sheet-light.png` | sessions sheet, light | the sheet is now only New chat + Review changes; Settings/Projects are not duplicated here | sheet behaviour changed elsewhere (with the exception of the removed rows) |
| `03-projects-light.png` | Projects, light | Projects is reachable from the bar and is selected there; no back arrow (a primary destination does not own Back) | project import/remove (not exercised) |
| `04-settings-light.png` | Settings top, light, cropped to 1080x1610 | Settings is reachable from the bar; no back arrow; provider cards and the success-green "key stored" role render | the bottom bar (cropped); any key value (the crop is above the API card) |
| `05-settings-bottom-light.png` | Settings bottom, light | full-screen Settings with the bar visible and Settings selected; no key card in frame | tuning behaviour |
| `06-settings-dark.png` | Settings bottom, dark | dark bar surface, themed system navigation bar, selected Settings pill + filled icon | 2.0x font scale |
| `07-chat-dark.png` | Chat, portrait, dark | dark bar and composer; Chat selected; no white bar or white nav bar | populated transcript |
| `08-landscape-settings-rail.png` | Settings, landscape, light | compact **rail** replaces the bar in the 600-839dp window; labels stay visible; Settings selected; no back arrow | the extended-rail (>=840dp) branch |
| `09-landscape-chat-rail.png` | Chat, landscape, light | rail navigation works; content and composer reflow beside the rail | the extended-rail branch |
| `10-ime-composer-portrait.png` | Chat, portrait, keyboard open | the bar stays above the IME, the content resizes, and the composer remains fully visible | hardware-keyboard behaviour |
| `11-project-open-chat.png` | Chat after opening the Legacy workspace row | Projects row -> Chat returns to the conversation; the binding changed (subtitle lost "Chat - no tools") and the bar follows to Chat | per-project engine identity (only the shared legacy workspace was opened) |
| `12-back-from-settings-chat.png` | Chat after system Back from Settings | system Back from a primary destination returns to the conversation; Chat is selected again | Back from Review/Diagnostics (those are pushed routes with their own arrows) |

## Findings

- **No new visual defect found.** The bottom bar reads as its own surface in
  both themes (white on paper in light, `surface` on near-black in dark)
  without a divider, so none was added.
- **F-K - extended rail not exercised on hardware (limit, not a defect).**
  Landscape is 850.9dp physically, but the app window excludes the display
  cutout and the 3-button navigation bar, so `shellLayoutFor` receives a
  width below 840dp and correctly selects the compact rail (capture 08).
  The extended rail is covered only by `ShellNavigationTest` at 1000dp.
  A tablet or a gesture-nav device with a wider window is still required
  before that branch can be called device-verified.
- **Suspected rotation-restore anomaly, disproved.** One early attempt
  appeared to restore Projects after rotating Chat back to portrait. The
  re-run (three temporary captures, Chat -> landscape Chat -> portrait Chat)
  restored Chat both times. The cause was the test harness, not the app: the
  scripted tap aimed at the composer landed in the bottom bar's Projects hit
  area after rotation. No product claim was recorded from the bad attempt.
- **Rotation settings note.** Auto-rotate was switched off and `user_rotation`
  forced for the landscape captures, then `user_rotation` returned to 0 and
  `accelerometer_rotation` restored to 1 (the pre-test default assumed).

## Limits

- `04-settings-light.png` is cropped at 1080x1610, above the API card, so the
  masked key hint is not in the repository. `05-settings-bottom-light.png`
  is uncropped and contains no key card because the list is scrolled to the
  bottom. No other capture is cropped or retouched.
- No populated transcript, running tool row or file diff was captured.
- 2.0x font scale, reduced motion, density overrides and wide/tablet layouts
  are NOT PERFORMED.
- API 30+ behaviour (edge-to-edge enforcement, gesture nav) is NOT PERFORMED:
  this device runs API 29.
- No instrumentation test was executed; `assembleDebugAndroidTest` compiled
  the test APK only.
- One device, one user, one locale.
