# MANIFEST — V2-15 Phase F device matrix

Date: 2026-11-25. Device: Redmi Note 7 Pro (`violet`), **API 29**, arm64-v8a,
1080x2340, 440 dpi, locale `en-IN`, serial `bbf8ca69`. Build: debug
`com.crush.mobile` 1.0.0 from this working tree, installed with
`adb install -r`; bundled engine reported `v0.100.0`.

Interpretation notes:

- These are raw `screencap` pulls. `v2-89` is a legitimate **0-byte** file: the
  system chooser surface blocks screen capture, and the share evidence is the
  resumed `ChooserActivity` component, not an image.
- `v2-72` is **mislabeled**: it shows the chat list, not the bottom of Settings
  (its successor `v2-93` is the real bottom-of-Settings 1.3x capture).
- Simulated wide captures (`v2-118`, `v2-119`) were taken at
  `wm size 1600x1000` + `wm density 240` (1066 dp wide); `v2-120` proves the
  native display was restored (`wm size reset` / `wm density reset`).
- TalkBack captures show the focus ring only; speech text was not captured.

Full narrative, verdicts and NOT PERFORMED reasons:
`docs/v2/V2-15-DEVICE-MATRIX-VERIFICATION.md`.

## Captures

| Capture | Shows |
|---|---|
| `v2-67-phaseF-current.png` | Current app state before the matrix run (baseline). |
| `v2-68-phaseF-chat-baseline.png` | Chat list baseline, 1.0x font. |
| `v2-69-phaseF-settings-baseline.png` | Settings baseline, 1.0x font. |
| `v2-70-phaseF-settings-13x.png` | Settings at `font_scale=1.3`: rows and controls intact. |
| `v2-71-phaseF-chat-13x.png` | Chat list at 1.3x: titles wrap, no clipping. |
| `v2-72-phaseF-settings-13x-bottom.png` | **Mislabeled**; actually the chat list at 1.3x. |
| `v2-73-phaseF-chat-rtl.png` | Early RTL attempt; layout stayed LTR. |
| `v2-84-phaseF-settings-unlocked.png` | Settings after unlock (share/copy path start). |
| `v2-85-phaseF-settings-lower.png` | Settings scrolled to the Diagnostics entry. |
| `v2-86-phaseF-settings-bottom.png` | Settings bottom (Diagnostics row). |
| `v2-87-phaseF-diagnostics.png` | Diagnostics screen with Copy and Share. |
| `v2-88-phaseF-diagnostics-copy.png` | "Diagnostics copied" toast after Copy. |
| `v2-89-phaseF-diagnostics-share.png` | **0 bytes**: chooser surface blocks screencap (see note). |
| `v2-90-phaseF-chat-rtl.png` | `debug.force_rtl=1` + process restart: still LTR. |
| `v2-91-phaseF-rtl-rotated.png` | Forced RTL after rotation: still LTR. |
| `v2-92-phaseF-code-13x.png` | Crush Code at 1.3x. |
| `v2-93-phaseF-settings-13x-bottom2.png` | Bottom of Settings at 1.3x: no truncation or overlap. |
| `v2-94-phaseF-talkback-on.png` | TalkBack enabled; its focus indicator on screen. |
| `v2-95-phaseF-talkback-focus.png` | TalkBack focus during the tutorial. |
| `v2-96-phaseF-talkback-app.png` | TalkBack tutorial page (before entering the app). |
| `v2-97-phaseF-talkback-tutorial.png` | Tutorial "Page 2 of 7" with the Next/Previous/Finish bar. |
| `v2-98-phaseF-talkback-after-finish.png` | Tutorial still up after an attempted Finish gesture. |
| `v2-99-phaseF-talkback-chat-focus1.png` | Settings; focus ring on the `5 min` timeout button. |
| `v2-100-phaseF-talkback-focus2.png` | Settings; focus ring on `Report a problem`. |
| `v2-101-phaseF-talkback-navfocus.png` | Bottom bar; focus ring wraps the whole `Crush Code` item plus label. |
| `v2-102-phaseF-talkback-chat.png` | Chat list; focus ring on the `Crush Chat` item. |
| `v2-103-phaseF-talkback-chat-focus.png` | Settings after a scroll; focus returns to `5 min`. |
| `v2-104-phaseF-chat-tab.png` | Tap miss on the system nav strip (state unchanged). |
| `v2-105-phaseF-chat-tab2.png` | Chat list reached with normal taps. |
| `v2-106-phaseF-newchat.png` | New chat composer, "Crush Chat · ready". |
| `v2-107-phaseF-code-tab.png` | Crush Code project list. |
| `v2-108-phaseF-code-project.png` | Project sheet: `Open Code chat` / `Review changes`. |
| `v2-109-phaseF-code-chat.png` | Code sessions for `Test project folder 2` (engine idle). |
| `v2-110-phaseF-code-session.png` | New code session, model DeepSeek V4.1, project bound. |
| `v2-111-phaseF-question-typed.png` | First prompt draft (with a literal `%b` escape). |
| `v2-112-phaseF-question-typed2.png` | Draft appended after a failed shell clear. |
| `v2-113-phaseF-question-typed3.png` | Clean prompt: "Use the question tool … Then stop." |
| `v2-114-phaseF-question-sent.png` | Turn running ("thinking..."). |
| `v2-115-phaseF-question-wait.png` | **A12 dialog**: real `yes_no` question, `Submit` disabled. |
| `v2-116-phaseF-question-yes-selected.png` | `Yes` selected; `Submit` enabled. |
| `v2-117-phaseF-question-submitted.png` | Answer accepted: question tool card, "You answered yes…". |
| `v2-118-phaseF-wide-extended-rail.png` | Simulated 1066 dp: extended rail + Code sessions pane. |
| `v2-119-phaseF-wide-chat.png` | Same width: extended rail + chat list, rail selection kept. |
| `v2-120-phaseF-after-reset.png` | Native 1080x2340 / 440 dpi restored, bottom bar back. |

## Logs

| Log | Shows |
|---|---|
| `v2-64-phaseF-securekeystore.log` | `SecureKeyStoreInstrumentedTest` OK (3 tests), 0.287s. |
| `v2-65-phaseF-room.log` | `ProjectStoreRoomPersistenceTest` OK (2 tests), 0.286s. |
| `v2-66-phaseF-smoke.log` | `MainActivitySmokeTest` OK (2 tests), 401.11s (MIUI-deferred). |
| `v2-76-phaseF-androidtest-build.log` | androidTest APK rebuild, BUILD SUCCESSFUL 13m23s. |
| `v2-77-phaseF-twoproject-rerun.log` | `TwoProjectAttachTest` OK (1 test), 4.293s. |
| `v2-78-phaseF-twoengine-rerun.log` | `TwoEngineInstancesPerProjectTest` OK (1 test), 6.463s. |
| `v2-79-phaseF-lockflow.log` | Lock suite: tests 1–3 pass, test 4 interrupted by MIUI `OneKeyClean` (signal 9). |
| `v2-80-phaseF-lock-keystore-roundtrip.log` | Keystore round-trip test OK (0.149s). |
| `v2-81-phaseF-lock-foreground-flag.log` | FLAG_SECURE test OK; `Time: 1,432.201` = 1432.201 s (23m52.2 s), including the ~24 min MIUI wait for RESUMED. |
| `v2-82-phaseF-lock-recreation.log` | Recreation test attempt A: `recreate()` timeout behind the credential screen. |
| `v2-83-phaseF-lock-recreation-retry.log` | Recreation test attempt B: `expected:<PROMPT> but was:<OPEN>` after a real unlock. |

The failing first runs of the two engine-isolation classes (`v2-74`, `v2-75`)
and the build logs `v2-62`/`v2-63` stay in the repository root; they are not
part of this capture set.
