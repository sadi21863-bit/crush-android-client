# V2-7.3 ui_block/v1 evidence (2026-11-22)

Device `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a. Debug build from the
V2-7.3 tree; light theme.

## File

| File | What it shows | What it does not show |
|---|---|---|
| `01-ui-block-preview.png` | the real `UiBlockView` rendering a **host-authored** `ui_block/v1` document: text, key-value rows, a table, a warning callout, a code block, a divider and a closing text block | a model producing blocks; interaction; dark theme/RTL/2.0x; a production producer |

## Method

The preview activity renders a static document through the same composable the
V2-8 experiment will use. It is debug source set only and is not reachable from
a release build. The manifest entry is exported because this ROM's shell refuses
`am start` on a non-exported activity; the screen is static and takes no input.

```powershell
adb -s bbf8ca69 install -r -d app\build\outputs\apk\debug\app-debug.apk
adb -s bbf8ca69 shell am start -n com.crush.mobile/.ui.screens.spike.UiBlockPreviewActivity
adb -s bbf8ca69 shell 'screencap -p /data/local/tmp/block.png'
adb -s bbf8ca69 pull /data/local/tmp/block.png block.png
```

## Gate

`v2-7-blocks-gate-1.log`: **954 tests / 100 classes / 0 failures**; lint
**0 errors / 1 pre-existing warning**. New tests:

- `UiBlockParserTest` - 12 validation cases (valid mixed document, wrong
  version, unknown type/field, table shape, callout tone, kv key, malformed
  JSON, source cap).
- `UiBlockViewTest` - 4 renderer cases (table, key-value, callout, code).

## Limits

- **No production producer.** V2-7 ships the contract, validator and renderer;
  model-authored blocks are V2-8.
- One device and light theme. Dark, RTL, 2.0x font and TalkBack are not
  performed.
- The document is deliberately read-only: no actions, URLs, images or network.
- The parser is strict and fail-closed; the preview does not exercise a
  fallback path because it starts from a fabricated valid document.
