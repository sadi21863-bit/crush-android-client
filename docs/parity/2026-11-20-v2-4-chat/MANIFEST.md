# V2-4 chat redesign - device capture manifest

Date: 2026-11-20. Device: Redmi Note 7 Pro `bbf8ca69`, API 29 (Android 10),
393dp portrait, app `com.crush.mobile` debug build, bundled engine 0.98.1
(project state). The provider shown in the captures is OpenCode Zen in the
early set and OpenCode Go in the later set; the sheet subtitle follows the
provider live, which is the F-B behaviour under test.

Captures `01`-`13` use the gate-3 APK (F-L fixed, F-M not). Captures `14`-`27`
use the gate-4 APK (F-L and F-M fixed). Captures `28`-`31` use the gate-5 APK
(F-N 2.0x chip wrapping fixed). The device was unlocked by the owner for each
secure-window session; no credentials were entered by the agent.

## Captures

| # | File | What it proves |
|---|---|---|
| 01 | `01-launch.png` | Cold start with prewarm off: "Start a conversation" + honest typing-to-start copy; status "engine stopped" with a dot; model picker visible as "Model" (F-C); composer disabled for send. |
| 02 | `02-model-sheet.png` | Model sheet: title, Reload, provider-aware subtitle, live list. The old hard-coded "OpenCode Zen / free models cost nothing" line is gone (F-B). |
| 03 | `03-session-sheet.png` | Session sheet with the engine disconnected: "Chats aren't available" + explanation, not "No chats yet" (Finding 11). Surface label names the project. |
| 04 | `04-ready-empty-draft.png` | After typing: header turns to a green dot + "ready" (F-E), empty state switches to the ready/project copy, picker shows the selected model, send is enabled, IME sits above the composer. |
| 05 | `05-streaming.png` | A running turn: Stop visible, composer disabled, role label `You` above the user bubble. F-L is visible here (dark prose on graphite). |
| 06 | `06-stopped.png` | After Stop: composer returns to idle (Send), no stale streaming state. |
| 07 | `07-session-current.png` | Session sheet with a live list: the current row carries a check, the word `Current`, fill and state description (F-J / D4). |
| 08 | `08-session-switched.png` | Switching to another session shows only its transcript (no bleed), with role labels, wrapped user bubbles, tool rows and Copy / Save Markdown / Use-plan actions. F-L is still present. |
| 09 | (not stored) | Settings top intermediate. It showed the masked key chip, so it was deleted before commit and is not transcribed. It was never a claim. |
| 10 | `10-after-fix-launch.png` | Post-reinstall cold start after the F-L build; app reopened on a new chat after the secure window. |
| 11 | `11-sessions-after-restart.png` | After restart + engine start: sessions list, current marker, and the handoff user bubble now light-on-graphite (F-L fixed). |
| 12 | `12-user-text-fixed.png` | Close-up of the previously unreadable user bubble: prose is now `onPrimaryContainer` on the graphite fill (F-L). |
| 13 | `13-settings-chips.png` | Settings timeout chips: selected "15 min" and "1 min" carry a filled check, accent border and semi-bold label (F-J). |
| 14 | `14-final-sessions.png` | Final build: session sheet after process recreation; current row marker intact. |
| 15 | `15-final-transcript.png` | Final build: white user prose, and the tool-only `agent_dispatch` row has no empty bubble beneath it (F-M). |
| 16-22 | model selection set | `17` shows the live provider as OpenCode Go with DeepSeek V4.1 Flash selected; `21` shows the optimistic switch to Claude Haiku; `22` shows the model reverted to Flash after the probe (the honest revert path). `16`, `18`-`20` are intermediates. |
| 23-27 | Retry set | `23`/`24`: failed turn ("This reply failed / no reply received"); `25`: Retry button; `26`: Retry re-ran the turn and the ASK permission dialog gated the agent's `bash` call; `27`: Deny settled the run, failed pair retained above. |
| 28-30 | 2.0x font scale | `28`: chat transcript at 2.0x (ellipsis in header/model, failed card and Retry readable, composer/nav fit); `29`: session sheet with current marker; `30`: model sheet with wrapped provenance and rows. |
| 31 | `31-fontscale-2x-settings.png` | Performance/tuning chip groups at 2.0x after the `FlowRow` fix: 5 min/15 min and 30 min/1 hour wrap instead of clipping. The Keyboard group uses the same `FlowRow`; its pre-fix clipping was observed live, but only the tuning capture is stored. |
| - | `_crop-user-bubble.png` | Before-state crop of F-L: near-black prose on graphite, kept as evidence, not a product surface. |

## Findings from this pass

- **F-L (fixed):** `MarkdownText` received the bubble colour but applied it only
  to inline-code backgrounds; paragraphs/headings/lists/quotes used the dark
  content colour. Fixed by passing the colour to every prose block. Re-captured
  in `12`/`15`.
- **F-M (fixed):** a tool-only assistant row rendered an empty filled bubble
  below its tool rows. Fixed by skipping the bubble when there is no text and
  no streaming placeholder. Re-captured in `15`.
- **F-N (fixed, DEVICE-VERIFIED `31-fontscale-2x-settings.png`):** at 2.0x
  font scale the Settings Keyboard and tuning chip rows clipped off the right
  edge. Migrated to `FlowRow`; the re-capture shows the tuning groups wrapping,
  and the Keyboard group uses the same layout. Chat, session sheet and model
  sheet held at 2.0x (`28`-`30`).
- **Observed, not a defect:** the provider shown in the sheet changed from
  OpenCode Zen to OpenCode Go during the pass and the subtitle followed it.
  Model selection optimistically showed Claude Haiku, then reverted to the
  persisted model after the probe - the designed revert path.

## Not proven

- Frame timing: no pre-change baseline was captured, so no before/after
  numbers are claimed.
- 2.0x font scale was checked on chat, session sheet, model sheet and Settings;
  the Settings chip overflow it found is fixed (F-N). Reduced motion, density
  overrides, wide/tablet and API 30+ remain NOT PERFORMED.
- Provider switching (Settings) and instrumented tests: NOT PERFORMED.
- Model-sheet loading and "not in current list" states are covered by JVM
  tests but were not captured on device (the fetch completed too quickly, and
  the absent-model state was not induced).
