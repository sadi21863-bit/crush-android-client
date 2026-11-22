# V2-8 candidate A lab evidence (2026-11-22)

Device `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a. Debug build from the
V2-8 candidate-A tree; light theme.

## File

| File | What it shows | What it does not show |
|---|---|---|
| `01-lab-sample-parsed.png` | `UiBlockLabActivity`: the prompt, the pasted payload, `Parsed 4 block(s)`, and the rendered text / table / key-value / callout blocks | a **model-produced** payload; render timing; TalkBack, RTL, dark theme or 2.0x; A2UI or json-render |

The payload shown is the lab's built-in sample, not model output. It proves the
paste -> normalize -> validate -> render path on device.

## Method

```powershell
adb -s bbf8ca69 install -r -d app\build\outputs\apk\debug\app-debug.apk
adb -s bbf8ca69 shell am start -n com.crush.mobile/.ui.screens.spike.UiBlockLabActivity
# tap "Parse and render"
```

The activity is debug source set only, exported because this ROM's shell refuses
`am start` on a non-exported activity. It does not call the engine, touch Chat
state or write a file.

## Gate

`v2-8-lab-gate-1.log`: **959 tests / 101 classes / 0 failures**; lint
**0 errors / 1 pre-existing warning**. New: `UiBlockLabTest` (5 normalization
cases: one fence, bare fence, raw JSON, prose-wrapped, unterminated).

## What remains NOT PERFORMED

- **A real model run.** Crush has no per-turn structured-output request; the
  producer step is a manual paste. The sample frame must not be read as a model
  result.
- Render time/frame metrics, token cost, TalkBack, font scale, RTL, dark theme,
  and the three-candidate comparison (A2UI is blocked by default).
