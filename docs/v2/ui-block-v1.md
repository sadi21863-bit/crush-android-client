# ui_block/v1 - host-owned block contract (V2-7.3)

Status: **implemented as a closed validator + renderer, producer-free.** No
production surface emits this yet; model-authored blocks remain the V2-8
experiment. This document is the contract those experiments must satisfy.

## 1. Envelope

```json
{
  "v": "ui_block/v1",
  "blocks": [ { "type": "text", "text": "..." } ]
}
```

- Exactly two root fields: `v`, `blocks`.
- `v` must equal `ui_block/v1`.
- `blocks` is an array of 1..32 objects.
- Any other root field rejects the whole document.

## 2. Block types

| `type` | Fields | Rules |
|---|---|---|
| `text` | `text` | required, non-blank, <= 4,000 chars |
| `code` | `lang?`, `text` | `text` required, non-blank, <= 8,000 chars; `lang` <= 32 chars |
| `table` | `columns`, `rows` | 1..8 columns, each non-blank <= 500 chars; 0..200 rows; each row must have exactly `columns.size` string cells, each <= 500 chars |
| `kv` | `items` | 1..32 items; each item has exactly `k` (non-blank, <= 80) and `v` (<= 1,000) |
| `callout` | `tone`, `title?`, `text` | `tone` is `info`, `warning` or `error`; `title` <= 120; `text` required, non-blank, <= 2,000 |
| `divider` | none | no other fields |

Every block object allows exactly the fields listed above. Unknown fields reject
the **whole** document; there is no partial render.

## 3. Limits

- Source: <= 16,000 characters (before JSON parsing).
- 1..32 blocks.
- No nesting: blocks are flat, so there is no depth limit to evade.
- The renderer adds no interaction and no network calls.

These are checked by `UiBlockParser`; a caller must treat
`UiBlockParse.Invalid(reason)` as "render the original text" and must not
attempt a repair.

## 4. Security posture

- No HTML, JavaScript, WebView, images, URLs, deep links or intents.
- No actions, buttons or tool calls. A block is display-only.
- No arbitrary component names: the six types are the complete vocabulary.
- Unknown version/type/field -> text fallback, not "best effort".
- The Chat deny-all boundary is untouched; this renderer cannot invoke a tool
  or request a permission.

## 5. Rendering contract

`UiBlockView(document)` maps each block to one fixed Compose structure:

| Block | Rendering |
|---|---|
| text | `CrushType.body`, `onSurface` |
| code | language label (`CrushType.metadata`) + horizontally scrollable code (`CrushType.code`) |
| table | header row (`CrushType.metadata`, semibold) + one row per record, weighted columns |
| kv | key (`CrushType.metadata`, muted) / value (`CrushType.body`), 40/60 split |
| callout | tone container + on-container text; optional title |
| divider | `HorizontalDivider` |

The projection is pure: the same document always produces the same tree. No
clock, no randomness, no device-dependent branching beyond normal Compose
measurement.

## 6. Producer status

- **V2-7:** host-authored/static only. The debug preview
  (`UiBlockPreviewActivity`) is the sole consumer.
- **V2-8:** the experiment may feed prompt-shaped envelopes through the same
  parser, read-only, debug-flag only. Adoption requires the V2-8 exit criteria,
  including accessibility, RTL, font scale and failure-taxonomy results.

## 7. Acceptance tests

- `UiBlockParserTest`: mixed valid document; wrong version; unknown type;
  unknown root/block field; table cell-count mismatch; too many columns; bad
  callout tone; missing kv key; malformed JSON; source cap.
- `UiBlockViewTest`: table headers/cells, kv pairs, callout title/body, code
  language/text render from the public model.

## 8. Non-goals

This is not A2UI, json-render or a general agent-UI protocol. It is a small
closed vocabulary the host owns. Wire compatibility with external agents is
explicitly out of scope until the owner asks for it.
