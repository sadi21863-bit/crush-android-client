# V2-8 candidate A lab evidence (2026-11-22)

Device `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a. Debug build from the
V2-8 candidate-A tree; light theme. Provider `opencode-go`, model DeepSeek V4.1
Flash.

## Files

| File | What it shows | What it does not show |
|---|---|---|
| `00-lab-sample-parsed.png` | the lab's built-in sample parsed into 4 blocks: text, table, key-value, callout | a model-produced payload |
| `01-model-reply.png` | the real model reply (tail): a `kv` block using `key`/`value`, a callout using `body`, and an invented `anthropic/claude-sonnet-4` model line | the start of the reply; the full prompt exchange |
| `02-lab-model-rejected.png` | the same reply pasted into the lab and rejected: **`block 3: item 0: unexpected field 'key'`** | any rendered UI for invalid output |

## Real run (one prompt, one model)

The lab's prompt was copied to the clipboard, pasted into Crush Chat, and sent.
The model answered with a JSON envelope, but it drifted from the contract:

- `kv` items used `"key"` / `"value"` instead of `"k"` / `"v"`;
- the callout used `"body"` instead of `"text"`;
- it invented `anthropic/claude-sonnet-4` as the model and token counts even
  though the session was `opencode-go` / DeepSeek V4.1 Flash.

The host did not attempt a repair. `UiBlockParser` rejected the whole document,
the lab showed the exact reason, and nothing was rendered. **Parse result:
0/1 schema-valid; 1 rejection; fallback path proven.**

This is a negative result for candidate A as prompted: a plain-text request is
not a structured-output guarantee. It supports keeping the strict validator and
not adopting model-generated UI without a schema-constrained channel.

## Method

```powershell
adb -s bbf8ca69 install -r -d app\build\outputs\apk\debug\app-debug.apk
adb -s bbf8ca69 shell am start -n com.crush.mobile/.ui.screens.spike.UiBlockLabActivity
# Copy prompt -> Chat -> paste -> send -> Copy message -> lab -> select all -> paste -> Parse
```

The activity is debug source set only, exported because this ROM's shell refuses
`am start` on a non-exported activity. It does not call the engine, touch Chat
state or write a file.

## Gate

`v2-8-a11y-gate-1.log`: **962 tests / 101 classes / 0 failures**; lint
**0 errors / 1 pre-existing warning**.

## Limits

- One prompt, one model, one sample. This is a signal, not a corpus.
- The JSON was pasted by the owner/agent through the UI; no automated producer.
- Render time, token cost, TalkBack, device dark/2.0x/RTL and the three-candidate
  comparison remain NOT PERFORMED.
- The reply's content (including the invented model line) is recorded only as
  evidence of schema drift; it is not a claim about the model's general
  behaviour.
