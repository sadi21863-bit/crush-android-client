# V2-8 candidate A lab evidence (2026-11-23)

Device `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a. Debug build from the
V2-8 candidate-A tree; light theme. Provider `opencode-go`, model DeepSeek V4.1
Flash.

## Files

| File | What it shows |
|---|---|
| `00-lab-sample-parsed.png` | the built-in sample parsed and rendered (4 blocks) |
| `01-model-reply.png` | run 1 reply: `key`/`value`, `body`, invented `anthropic/claude-sonnet-4` |
| `02-lab-model-rejected.png` | run 1 rejected: `block 3: item 0: unexpected field 'key'` |
| `03-run2-reply.png` | run 2 reply: same `key`/`value` drift, plus `variant` instead of `tone` |
| `04-run2-rejected.png` | run 2 rejected: same block/field |
| `05-run3-reply.png` | run 3 reply after appending "kv items use k and v; callout uses tone and text": uses `k`/`v`, `tone`/`text`, values `n/a` |
| `06-run3-parsed.png` | run 3 accepted: `Parsed 4 block(s)` |
| `07-run3-rendered.png` | run 3 rendered: text, table, key-value rows, warning callout |

## Results

| Run | Prompt | Parse | What happened |
|---|---|---|---|
| 1 | lab prompt, unchanged | **rejected** | `kv` used `key`/`value`; callout used `body`; invented model/tokens; validator rejected the whole document |
| 2 | lab prompt, unchanged | **rejected** | same `key`/`value` drift; callout used `variant` instead of `tone` |
| 3 | lab prompt + "STRICT: kv items use k and v. callout uses tone and text." | **valid** | 4 blocks rendered; the model used `n/a` instead of inventing model/token data |

**Schema-valid: 1/3.** Runs 1-2 are consistent schema drift; run 3 shows prompt
precision can fix the keys, but the result remains prompt-dependent. The
fail-closed path worked on every invalid document: no partial render, exact
rejection reason shown.

## Why this matters

- A plain-text prompt is not a structured-output guarantee. Naming the exact
  keys helps, but nothing enforces them.
- The host-owned validator is the safety boundary; it rejected two of three
  documents without rendering anything.
- The model filled absent facts with `n/a` in run 3 rather than inventing them,
  but run 1 invented a model/provider/token line. Data trust remains a model
  behaviour, not a schema property.

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

- Three prompts, one model, one sample. A signal, not a corpus.
- The JSON was moved by copy/paste through the UI; no automated producer.
- Render time, token cost, TalkBack, device dark/2.0x/RTL and the three-candidate
  comparison remain NOT PERFORMED.
- The replies' content is evidence of schema drift only, not a general claim
  about the model.
