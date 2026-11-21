# V2-5 device evidence manifest (final pass, 2026-11-21)

Device: Redmi Note 7 Pro `bbf8ca69`, API 29, 393dp portrait, app
`com.crush.mobile`.

The owner resumed the final corrected-IA device pass on 2026-11-21 and it ran to
completion on the phone. Every frame below was checked against the code path it
claims; screenshots that proved nothing (failed taps, duplicate sheets, the
launcher after Back) were deleted rather than kept as noise.

`2026-11-21-device-log-excerpts.txt` holds the two `app.log` lines this pass
cites: the legacy `[]` deny-all policy line and the changed-`crushrc` handoff
block. No credentials are in it.

| File | What it actually shows | Status |
|---|---|---|
| `01-projects.png` | Pre-V2-A10 Projects list with the legacy workspace visible beside Code projects | BEFORE-state, not final |
| `02-project-chat-identity.png` | Project chat identity before the IA labels (header showed project label) | PARTIAL, pre-IA |
| `03-probe-turn.png` | Draft prompt in a project chat before the IA; no turn sent | NOT a claim |
| `04-probe-running.png` | Permission dialog for the abandoned write probe | NOT a claim |
| `10-projects-code-only.png` | Projects after V2-A10: legacy hidden, identity/trust/activity rows | PARTIAL, pre-IA labels |
| `11-project-home.png` | Project home after V2-A10: identity, trust, latest measured turn, Open Code chat, Review | PARTIAL, pre-IA labels |
| `12-code-chat-identity.png` | Project chat header identity (old Chat/Projects labels) | PARTIAL, pre-IA |
| `13-ime-bar-before-fix.png` | **Before the IME fix**: the old phone bottom bar is visible between the composer and the keyboard | BEFORE-state |
| `20-pre-fix-code-sessions.png` | Corrected labels and Crush Code tab selected for a project, but the engine was stopped and the list falsely said "No sessions yet" | BEFORE the list-state fix |
| `21-pre-fix-crush-chat.png` | Corrected Crush Chat label/tab, but the stopped engine falsely said "No chats yet" | BEFORE the list-state fix |
| `30-final-restored.png` | Final build: restored project binding shows **Crush Code** selected and the stopped-engine copy "Sessions aren't available", not "No sessions yet" | PASS on device |
| `31-crush-chat-unavailable.png` | Final build: **Crush Chat** tab, stopped engine says "Chats aren't available", not "No chats yet" | PASS on device |
| `32-crush-chat-new.png` | Final build: New chat bound the **legacy** engine (`Crush Chat · connecting to agent`) | PASS on device |
| `33-crush-chat-qa.png` | Final build: legacy Q&A turn ("4") with `Crush Chat · ready`; no tool rows | PASS on device |
| `34-crush-chat-list-loaded.png` | Final build: Crush Chat list with the new Q&A chat and old legacy history; Current marker | PASS on device |
| `35-crush-code-projects.png` | Final build: Crush Code project list (no legacy), identity/trust/activity | PASS on device |
| `36-project-home-final.png` | Final build: project home with identity, trust, latest measured turn, Open Code chat, Review | PASS on device |
| `37-code-sessions-final.png` | Final build: project session list; stopped engine says sessions aren't available | PASS on device |
| `38-code-chat-final.png` | Final build: project-bound Code chat, `Crush Code` tab selected, project label in header | PASS on device |
| `39-ime-bar-still-visible.png` | **FAILURE on the final build**: the bottom bar is still above the keyboard. `WindowInsets.ime` read 0 under adjustResize, fixed after this capture with visible-frame detection | FAIL, fixed in gate 9 |
| `40-ime-fixed.png` | Build from gate 9: keyboard open, composer directly above it, Crush Chat/Code/Settings bar hidden | PASS on device (IME fix) |
| `42-code-new-session.png` | Final build: project 2 chat after "New session"; header `chat` + project label, Code tab selected, empty conversation | PASS on device |
| `45-code-sessions-loaded.png` | Final build: project 2 Code sessions, five rows, Current on the new chat | PASS on device |
| `51-composer-focused.png` | Final build, Code chat: IME open, composer sits directly above the keyboard, phone bar hidden | PASS on device (IME fix in Code) |
| `52-prompt-ready.png` | Draft prompt typed in the project composer; not sent yet | NOT a claim (pre-send) |
| `53-turn-sent.png` | Turn running: user bubble, `write` tool row, `working…`, Stop button visible and enabled | PASS on device (running state) |
| `54-permission-dialog.png` | ASK mode: "Allow this action?" for the write with Always / Deny / Allow once | PASS on device (permission policy) |
| `55-turn-after-allow.png` | Completed turn: `write` result, Done, no invented test result, "This turn's changes: 1 file changed +1 −0, HELLO.md new", Review all turns | PASS on device |
| `57-review-screen.png` | Populated Review: Turn at 10:22:58, session `d294c825`, project label, captured time, 1 file +1 −0, `HELLO.md` new, Revert this file, Export a copy | PASS on device |
| `58-export-save-to.png` | SAF "Save to" sheet with `HELLO.md` in the name field and SAVE not yet armed | PASS on device (save step) |
| `59-export-drawer.png` | SAF drawer with the Downloads target selected | PASS on device (save step) |
| `60-export-downloads.png` | Downloads folder, `HELLO.md` name field, SAVE armed | PASS on device (save step) |
| `64-unlocked-state.png` | Review after the process unlock shows "Exported a copy of HELLO.md."; the card is still present | PASS on device |
| `66-revert-confirm.png` | Revert dialog names the file, the turn (10:22:58) and the project, and states the refuse-if-changed rule | PASS on device |
| `67-revert-result.png` | After revert: "Reverted" disabled, "Done: created file deleted"; shell check confirms `HELLO.md` is gone on disk | PASS on device |
| `68-sessions-after-turn.png` | Project session list after the turn: "Create HELLO.md with hello from crush", 4 msg, Current | PASS on device |
| `69-projects-after-chat.png` | Projects list shows the measured turn ("Latest turn: 1 file(s) +1 -0 at 10:22.") next to "Configuration approved" | PASS on device |
| `72-trust-regate-changed.png` | F04 gate on the cold-resume path after the `crushrc` bytes changed: "The crushrc CHANGED since your last approval - the earlier consent no longer covers these bytes." Cancel / Run it and open | PASS on device |
| `73-after-trust-cancel.png` | After Cancel: unbound Crush Chat list, stopped engine, "Chats aren't available" - nothing bound, no engine touched | PASS on device |
| `74-project-home-restored.png` | Original 17-byte `crushrc` restored: project 2 home opens with "Configuration approved" and **no** dialog | PASS on device |
| `80-switch-start-p2-current.png` | Two-project switch, start: project 2 carries Current in the Code list | PASS on device |
| `88-open-code-chat-p1-sessions.png` | "Open Code chat" on project 1 lands on project 1's Code sessions, Code tab selected | PASS on device |
| `91-current-on-p1.png` | Code list: Current moved from project 2 to project 1 | PASS on device |
| `92-p2-home.png` | Project 2 home again (restored `crushrc`, approved) before the return leg | PASS on device |
| `93-p2-sessions.png` | "Open Code chat" on project 2 lands on project 2's Code sessions | PASS on device |
| `94-current-back-on-p2.png` | Code list: Current moved back to project 2 | PASS on device |

## Not captured on device (and not claimed)

- Tablet/wide panes: out of scope (V2-A4/O3), unobservable on this phone.
- 2.0x font scale and TalkBack traversal on the affected surfaces.
- The **accept** branch of the changed-`crushrc` dialog: the pass cancelled it
  (`72`-`73`) and restored the original bytes (`74`) instead of re-approving.
  The record-consent path is covered by `ProjectTrustServiceTest`.
- A "not measured" Review card: unit-tested (`ReviewHistoryTest`,
  `ReviewContentTest`), not produced by a device turn during this pass.
- The Chat-switch veto Toast while a project run is in flight: pinned by
  `ShellNavigationTest`, not captured here (no run was in flight at the switch).
- A running-session row in a Code session list: the list was captured stopped
  (`37`, `93`) and loaded after a turn (`45`, `68`).

The JVM gate (`v2-5-gate-1120-9.log`, 917 tests / 96 suites / 0 failures; lint
0 errors / 1 pre-existing warning) covers the code paths; it is not a substitute
for the frames above, and these frames do not cover the rows listed just above.

## Correction

An earlier filename, `13-ime-bar-hidden.png`, was wrong: the image shows the
bar **before** the fix. It was renamed to `13-ime-bar-before-fix.png`.
