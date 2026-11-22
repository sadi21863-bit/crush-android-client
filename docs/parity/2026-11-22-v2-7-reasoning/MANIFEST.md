# V2-7 reasoning evidence (2026-11-22)

Device `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a. Debug build from the
V2-7 tree; provider `opencode-go`, model DeepSeek V4.1 Flash, light theme.

## Files

| File | What it shows | What it does not show |
|---|---|---|
| `01-settings-collapsed.png` | Settings -> Chat: **Model reasoning** with two choices, **Collapsed** selected; copy says the closed block opens on tap | the transcript |
| `02-settings-off.png` | **Off** selected; copy says reasoning is never shown, is dropped before the transcript, and **can be switched back any time** | the transcript |
| `03-chat-reasoning-collapsed.png` | a live assistant turn with the **Reasoning** block closed above the answer `391` - captured **after** switching Off -> Collapsed and reopening, so it also proves the choice is reversible | the reasoning text while closed |
| `04-chat-reasoning-expanded.png` | the same turn expanded: the model's actual chain of thought, plain text | that the reasoning is truthful or complete |
| `05-chat-off.png` | the same chat reopened while **Off**: the Reasoning block is gone, the answer remains | that the engine deleted its copy (it did not) |

The two Settings frames are the final two-chip UI. An earlier switch-based
capture was replaced; no stale frame is kept.

## Commands

```powershell
# gate (JBR 25)
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
# log: v2-7-gate-2.log -> 938 tests / 98 classes / 0 failures,
# lint 0 errors / 1 pre-existing warning

# install and launch
adb -s bbf8ca69 install -r -d app\build\outputs\apk\debug\app-debug.apk
adb -s bbf8ca69 shell am start -n com.crush.mobile/.MainActivity

# capture protocol: screencap to /data/local/tmp, pull as a file (zero-byte
# pulls happen; retry until stat -c %s is non-zero)
adb -s bbf8ca69 shell 'screencap -p /data/local/tmp/frame.png'
adb -s bbf8ca69 pull /data/local/tmp/frame.png frame.png
```

## What the device pass proves

1. The setting is discoverable and persists across a reinstall/relaunch (the
   Settings frame after reinstall still showed the chosen state).
2. **Collapsed** renders the real `ReasoningBlock` from a live provider turn,
   closed by default.
3. Tapping expands the actual reasoning text, bounded by the 8,000-char cap.
4. **Off** removes the block from an existing chat that contained reasoning.
5. Switching back to **Collapsed** and reopening restores the block - the
   setting is not permanent.

## Limits

- One device, API 29. No API 30+, tablet, 2.0x, RTL or reduced-motion row.
- Reasoning availability is provider/model-dependent. DeepSeek V4.1 Flash
  emitted `reasoning` parts on this turn; a model that does not reason shows no
  block and the app does not invent one.
- **Off controls the app, not generation.** The model may still produce
  reasoning; the app drops it before `ChatMessage`/replay. The engine may
  retain its own copy, which is why switching back can restore it.
- The frames are screenshots, not a recording; streaming timing is not shown.
