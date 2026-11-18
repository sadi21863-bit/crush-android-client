# After Phase 2b.2 + Findings 10/11 fixes - device evidence

Device: `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a, 1080x2340.
APK: `versionName 1.0.0`, installed 2026-11-18 00:23:40 host time.
Source: working tree containing the Finding 10/11 fixes and the Phase 2b.2
`ProjectsContent` extraction. Full local gate before install: 812 tests /
87 suites green; lint 0 errors / 1 standing warning.

Device clock runs ahead of the host. Screenshots show the device clock; the
log excerpt keeps the app's own timestamps. Host file times are canonical.

## Findings

| # | Result | Evidence | Note |
|---:|---|---|---|
| 10 | PASS | `f10-dotted-gate.png`, `f10-dotted-cancel.png`, `f10-dotted-allowed-chat.png`, `f10-dotted-marker.png`, `findings-2b2.log.txt` | `DottedTrust3` carried ONLY `.crushrc` (32 B). The row tap raised F04 BEFORE any bind and no `.dotmarker3` existed. Cancel left no marker. Allow recorded the composite fingerprint `9bc5fe26...` (plain SHA-256 of the bytes is `2445e106...`, so the dotted file is inside the name-bound digest). The marker appeared only after the first typed character started `p189601f3`; the folder then held `.dotmarker3` (13 B). |
| 11 | PASS | `f11b-stopped.png`, `f11b-started.png`, `f11b-live-list.png`, `findings-2b2.log.txt` | `TestProject3` had two sessions and a stopped engine. The sheet rendered `Project - TestProject3` + `Chats aren't available` / `The engine isn't connected. Send a message to start it, then reopen this sheet.` - no `No chats yet`. One typed character started the engine; the reopened sheet listed `Verify repo tree against handoff summary` (8 msg) and `chat` (1 d). |

## Phase 2b.2 - Projects surface

| Check | Result | Evidence | Note |
|---|---|---|---|
| Screen render | PASS | `projects-top.png`, `projects-after.png` | Top band y=100..620 (header, Import folder, explanatory copy) is **pixel-identical** to the 2b.1 baseline `../after-2b1/f26-after-projects.png`: 0 changed pixels. The first two rows (y=620..1200) are also 0 changed pixels. Lower rows differ only because the registry now contains F14Fresh/F14Fresh2/DottedTrust3. |
| Legacy row | PASS | `projects-top.png` | `Legacy workspace` has no Remove affordance; every imported row does. |
| Import flow | PASS | `projects-dotted-imported.png` | SAF picker -> `DottedTrust3` -> Allow access -> registry row appears. The app then received the row with no trust record (verified in `projects.db`). |
| Remove confirm/cancel | PASS | `projects-remove-dialog.png`, `projects-remove-cancelled.png` | Dialog is titled `Remove project`, names `/storage/emulated/0/DottedTrust3`, and says the folder and files stay untouched. Cancel left the row and the folder intact. |
| Flow 26 open/back | PASS | `f26-post2b2-open.png` | Opening `DottedTrust3` replaced the stack with one chat entry; system Back resumed `com.miui.home` (the documented single-entry invariant). |

## Not performed / limits

- Full-screen pixel parity is not claimed: the registry content changed
  (three more rows), so only the stable top bands are exact.
- The actual destructive Remove was not tapped. The dialog copy and Cancel
  path were exercised; removal itself remains owner/destructive scope.
- API 30+ All-files-access rationale and wide/tablet layouts remain untested
  on this API 29 device.
- Driving note: raw `adb input tap` on the Chat sheet's `Projects` button was
  sometimes swallowed by the bottom sheet's drag gesture (Settings/Review
  responded, Projects intermittently did not). Keyboard focus (DPAD down) plus
  Enter opened Projects deterministically. This is in the unchanged 2b.1 chat
  sheet, not the Projects extraction; recorded as a walk-method observation,
  not a 2b.2 defect. A human finger tap is the normal path and was used in the
  original flow 26 pass.

## Cleanup state

- `DottedTrust3` remains imported and consented, with `.crushrc` and the
  expected `.dotmarker3`. Its chat session `db2554b6` exists.
- No project files were removed. The app was left on the `DottedTrust3` chat
  (engine ready, empty composer) before the final Back to launcher.
