# V2-7 - Deterministic intelligent UI foundation (opened 2026-11-22)

**Status: IN PROGRESS.** Owner authorized opening V2-7 on 2026-11-22, with one
explicit instruction: model reasoning is shown **collapsed**, and a setting can
turn it off completely. **V2-7.1 and V2-7.2 are complete and device-verified**
(2026-11-22); V2-7.3 (the `ui_block/v1` registry) is the remaining work.
Everything below is bounded by that instruction and by the research records:

- `V2-7-8-codebase-inventory-2026-11-22.md` (what the tree can support)
- `V2-7-8-web-research-2026-11-22.md` (A2UI / json-render / MCP Apps / structured
  output, 2026-11-22)

## 1. Scope selected for V2-7

The audit found more gaps than this package should swallow. These are the ones
that are useful as a **foundation** - deterministic, host-owned, and required by
any later structured renderer:

| ID | Item | Status |
|---|---|---|
| V2-7.1 | **Reasoning block + two-state setting.** Model reasoning renders as a collapsed disclosure above the reply; Settings offers **Collapsed / Off**, where Off never shows and drops reasoning at the transcript boundary. Reversible at any time. | **DONE** (gate + device: `docs/parity/2026-11-22-v2-7-reasoning/`) |
| V2-7.2 | **Unknown-part fallback.** A message part this build does not know renders as bounded plain text (`Unsupported content (type)`) instead of vanishing; hidden parts stay hidden. Acceptance-kit row 6. | **DONE** (gate; renderer pinned) |
| V2-7.3 | **Host-owned `ui_block/v1` registry.** A closed, versioned block model and a deterministic Compose renderer with validation, fallback, stable replacement on whole-message republish, and accessible semantics. No HTML/JS/WebView/URLs/actions. | PLANNED - spec first, then renderer |

### Not in V2-7

- Model-authored UI generation (that is V2-8).
- A2UI, json-render or any external UI framework as a dependency. Their
  **ideas** (closed catalog, host renderer, schema validation) are adopted; the
  libraries are not.
- New network access, image/URL loaders, clickable model links, HTML/JS.
- Tool or permission affordances in the Chat surface (the deny-all boundary is
  unchanged).
- Cost/context/token UI (still deferred by V2-A4), Markdown tables/images and
  syntax highlighting (V2-8 candidates), transcript search, edit/regenerate,
  and question answering.

## 2. Decisions

### D1 - Reasoning is collapsed by default, with three retention states (owner, 2026-11-22)

- The block is rendered for assistant rows when `thinking` is non-blank and the
  mode is **Collapsed**.
- It starts collapsed on every row, live and replayed. Tapping the header
  expands; the state is remembered per message while the row is alive.
- The content is **plain text, not Markdown**: model-authored headings or links
  must not impersonate host chrome.
- Expanded text is bounded at 8,000 characters with an explicit truncation
  marker; a runaway chain of thought cannot create an unbounded layout.
- Settings offers two states, persisted as `reasoning_display`:
  - **Collapsed** (default) - draw the closed block; tap to read.
  - **Off** - never show reasoning anywhere; drop it at the transcript boundary
    so the app does not keep or replay it. This is **reversible**: the choice is
    persisted until the owner switches back to Collapsed, and no engine or
    provider data is deleted. The engine may retain reasoning in its own
    history, so switching back and reloading can re-fetch it.
- **Off is not a generation switch.** The model may still produce reasoning;
  the app only controls what it stores and shows.

### D2 - Unknown parts are visible, bounded and inert

- Any part type outside `text / reasoning / finish / tool_call / tool_result /
  sub_agent_report` becomes plain text, preferring `text`, `content`, `message`,
  `title` or `thinking`, then the part's JSON.
- Per-part cap 1,200 chars, total cap 3,000; `data.hidden == true` suppresses it.
- The fallback is a receipt, not a new vocabulary: no actions, no parsing of
  nested content, no execution.

### D3 - The foundation is a closed host registry, not a model canvas

The web research's verdict is unambiguous:

- AndroidX A2UI Compose is `1.0.0-alpha02` (2026-10-21), still implements
  protocol **0.9.1** while A2UI 1.0 is current, and requires
  `minCompileSdk 37.1` - the app is on 37. It is reference-only.
- json-render is a TypeScript framework with no Compose renderer.
- CopilotKit / OpenIntelligentUI / MCP Apps are web/iframe architectures; the
  app has no WebView and should not add one.
- Crush exposes **no per-turn `response_format`**, so schema-constrained model
  output is not available today.

Therefore V2-7.3 defines a host-owned block envelope (`ui_block/v1`) with a
closed block set (text, code, table, key-value, callout, divider) and a strict
validator. Unknown types/fields fall back to text. The first shippable use is
host-authored blocks; whether a model may produce them is V2-8's question, not
V2-7's assumption.

### D4 - One new display preference, recorded as an exception

V2-A4 trimmed "new response-rendering preferences". The owner explicitly asked
for the reasoning toggle, so it is an approved exception, recorded as V2-A16.
No other preference is added in this package.

## 3. Implementation map

| Piece | File |
|---|---|
| Persisted preference | `domain/model/AppSettings.kt`, `data/local/SettingsDataStore.kt`, `data/repository/SettingsRepository.kt`, `ui/screens/settings/SettingsViewModel.kt` |
| Settings row | `ui/screens/settings/SettingsContent.kt` ("Chat" section, two chips: `reasoning-collapsed` / `reasoning-off`) |
| Projection input | `ui/screens/chat/ChatHostInputs.kt`, `ChatViewModel.hostState` |
| State | `ChatMessage.thinking` / `ChatMessage.unknownText`, `ChatViewState.reasoningDisplay`, `ChatUiState.reasoningDisplay` |
| Replay | `ChatViewModel.replayRow` (thinking + unknownText) |
| Parser | `data/crush/CrushEvent.kt` (`unknownText`, known-type set, hidden rule, caps) |
| Renderer | `ui/screens/chat/ChatContent.kt` (`ReasoningBlock`, `UnsupportedContentBlock`, inside the private `Bubble`) |

The composer gates (`canType`, `canSend`, `canSubmitViaIme`) and the Chat
deny-all boundary are untouched.

## 4. Test plan

- **JVM:** `StreamMessageTest` - unknown part text, hidden suppression, caps;
  `ChatHistoryReplayTest` - reasoning and unknown text survive replay.
- **Robolectric/Compose:** `ChatContentTranscriptTest` - reasoning collapsed by
  default, expands on tap, absent when the setting is off; unknown fallback is
  visible.
- **Settings:** `SettingsContentTest` - choosing **Off** dispatches
  `ReasoningDisplay.OFF`; the auto-approve switch is addressed by tag so the
  controls cannot be confused. `ReasoningDisplayTest` pins parse/round-trip and
  the fail-safe default.
- **Discard:** `ChatHistoryReplayTest` - `keepReasoning = false` returns a row
  with empty `thinking`; the live branch uses the same mode.
- **Gate:** `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease`.
  `v2-7-gate-2.log`: **938 tests / 98 classes / 0 failures**; lint **0 errors /
  1 pre-existing warning**.
- **Device:** `docs/parity/2026-11-22-v2-7-reasoning/` - Settings in both
  states, a live reasoning turn collapsed and expanded, the same chat with Off,
  and the restored block after switching back. API 29 only.

## 5. Limits

- The reasoning text depends on the provider actually emitting `reasoning`
  parts. A model that does not reason shows no block; the app does not invent
  one.
- The renderer is plain text. It does not parse reasoning Markdown, linkify it,
  or offer copy/save per block.
- The unknown-part fallback is a raw receipt, not a semantic rendering; a future
  part type gets a real renderer only by adding it to the registry.
- V2-7 does not make model output structured. It makes the transcript
  **tolerate and display** what the engine already sends; V2-8 tests whether
  the model can produce host-renderable blocks.
