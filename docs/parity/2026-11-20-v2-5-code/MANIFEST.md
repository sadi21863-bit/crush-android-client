# V2-5 device evidence manifest (incomplete by owner choice)

Device: Redmi Note 7 Pro `bbf8ca69`, API 29, 393dp portrait, app
`com.crush.mobile`. The owner **skipped the final corrected-IA device pass on
2026-11-21**, so the final build's list-first surfaces, IME fix, populated
Review and export are **NOT PERFORMED** on device.

These files are kept for chronology, not as final acceptance evidence.

| File | What it actually shows | Status |
|---|---|---|
| `01-projects.png` | Pre-V2-A10 Projects list with the legacy workspace visible beside Code projects | BEFORE-state, not final |
| `02-project-chat-identity.png` | Project chat identity before the IA labels (header showed project label) | PARTIAL, pre-IA |
| `03-probe-turn.png` | Draft prompt in a project chat before the IA; no turn sent | NOT a claim |
| `04-probe-running.png` | Permission dialog for the abandoned write probe | NOT a claim |
| `10-projects-code-only.png` | Projects after V2-A10: legacy hidden, identity/trust/activity rows | PARTIAL, pre-IA labels |
| `11-project-home.png` | Project home after V2-A10: identity, trust, latest measured turn, Open Code chat, Review | PARTIAL, pre-IA labels |
| `12-code-chat-identity.png` | Project chat header identity (old Chat/Projects labels) | PARTIAL, pre-IA |
| `13-ime-bar-before-fix.png` | **Before the IME fix**: the old phone bottom bar is visible between the composer and the keyboard | BEFORE-state; renamed from a misleading `ime-bar-hidden` name |
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
| `39-ime-bar-still-visible.png` | **FAILURE on the final build**: the bottom bar is still above the keyboard. `WindowInsets.ime` read 0 under adjustResize. Fixed after this capture with visible-frame detection | FAIL, fixed in gate 9 |
| `40-ime-fixed.png` | Build from gate 9 (`v2-5-gate-1120-9.log`): keyboard open, composer directly above it, Crush Chat/Code/Settings bar hidden | PASS on device (IME fix) |

## Final rows that are NOT PERFORMED

Done on the final build: Crush Chat list (DONE: `34`), Crush Code project list
and project home (DONE: `35`-`36`), project session list (DONE: `37`),
project-bound Code chat (DONE: `38`), IME bar hidden (DONE: `40`, after the
first attempt failed in `39`). The running-session list with rows loaded was
not captured.

Still open:

- Populated Review card, provenance/partial states, confirmed revert.
- Byte-exact export on this build.
- Two-project isolation/switch on this build (two projects exist, `36`).
- Trust re-gating on a changed `crushrc` on this build.
- The chat deny-all policy spawn line from `app.log` for the legacy instance.

The phone dropped off USB after `40`, so the pass stopped there.

The JVM gate (`v2-5-gate-1120-9.log`, 917 tests / 96 suites / 0 failures;
lint 0 errors / 1 pre-existing warning) and the source tests cover the code
paths, but they do not replace these device rows.

## Correction

An earlier filename, `13-ime-bar-hidden.png`, was wrong: the image shows the
bar **before** the fix. It was renamed to `13-ime-bar-before-fix.png`; no
device capture of the fixed IME state exists.
