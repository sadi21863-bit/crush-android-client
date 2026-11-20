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

## Final rows that are NOT PERFORMED

- Corrected-IA Crush Chat list with loaded legacy chats.
- Corrected-IA Crush Code project list and project session list with a running
  engine.
- IME fix on the final build (bottom bar hidden while typing).
- Populated Review card, provenance/partial states, confirmed revert.
- Byte-exact export on this build.
- Two-project isolation/switch on this build.
- Trust re-gating on a changed `crushrc` on this build.

The JVM gate (`v2-5-gate-1120-8.log`, 917 tests / 96 suites / 0 failures;
lint 0 errors / 1 pre-existing warning) and the source tests cover the code
paths, but they do not replace these device rows.

## Correction

An earlier filename, `13-ime-bar-hidden.png`, was wrong: the image shows the
bar **before** the fix. It was renamed to `13-ime-bar-before-fix.png`; no
device capture of the fixed IME state exists.
