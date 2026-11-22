# V2-7 / V2-8 codebase inventory

Date: 2026-11-22
Repository root: `C:\Users\aditya\Desktop\opencode android app`
HEAD at survey time: `5cfedee` ("V2-9: release hardening - real-Keystore coverage, rotation runbook"), working tree clean.
Scope: read-only inventory for V2-7 "deterministic intelligent UI foundation" and V2-8 "structured model UI experiment".

Labels used below:
- **CODE** — exists in `app/src/main` and runs.
- **TEST** — pinned by an existing test.
- **DOCS** — stated only in documentation.
- **INFERENCE** — a conclusion drawn from the code, not stated anywhere.

A first finding that frames everything else: **V2-7 and V2-8 have no written specification in this repository.** They appear only as parked names:

- `docs/v2/README.md:6`, `:39`, `:57` — "V2-7/V2-8 remain parked", "separate owner approval".
- `docs/v2/ADR-REGISTER.md:76-77` — V2-7 "Deterministic intelligent UI foundation", V2-8 "Structured model UI experiment", both "separate approval; A2UI is alpha (`1.0.0-alpha02`, 2026-10-21)".
- `docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md:1104-1106`, `:1139-1140`, `:1189` — parked with O6/O7.
- `docs/v2/V2-0-baseline-lock-2026-11-19.md:294-307` — the only research note: A2UI `1.0.0-alpha02` (2026-10-21, Compose renderer for agent-generated UI) "cannot be a V2.0 dependency"; Vercel `json-render` has no Compose renderer; CopilotKit OpenIntelligentUI is a web architecture (sandboxed HTML iframe); "No framework, dependency or protocol from this list is adopted."
- `docs/v2/V2-0-signoff-2026-11-22.md:33` — research "DONE as reference refresh; nothing adopted".

So a V2-7/V2-8 change has no brief to conform to. Everything below is the as-built surface it would have to work with.

---

## 1. The assistant-message pipeline

### 1.1 Transport and framing

| Step | Code | Notes |
|---|---|---|
| SSE consume | `app/src/main/java/com/crush/mobile/data/crush/CrushEventStream.kt:28-121` | Reads only `data:` lines (`:58`, `DATA_PREFIX` at `:139`); no `event:`, no `id:`, no heartbeat (class doc `:12-21`). Malformed frames are skipped, not fatal (`:63-68`). |
| Frame decode | `CrushEventStream.kt:123-135` | Two-level envelope: `{"type","payload":{"type","payload":{...}}}`. Unknown types become `UNKNOWN` (`CrushEvent.kt:35-40`) rather than throwing. |
| Event model | `app/src/main/java/com/crush/mobile/data/crush/CrushEvent.kt:15-49` | `PayloadType` covers MESSAGE, RUN_COMPLETE, PERMISSION_REQUEST, PERMISSION_NOTIFICATION, QUESTION_REQUEST, QUESTION_NOTIFICATION, SESSION, AGENT_EVENT, CONFIG_CHANGED, FILE, LSP_EVENT, MCP_EVENT, SKILLS_EVENT, UPDATE_AVAILABLE, UNKNOWN. |
| Fan-out | `data/crush/CrushEventRouter.kt`; `ChatViewModel.kt:3746-3773` | Subscription is registered **before** the prompt POST so early frames are not lost. Falls back to a per-turn `CrushEventStream` when the shared connection is not CONNECTED. |
| Prompt POST | `ui/screens/chat/ChatViewModel.kt:3796` → `data/crush/CrushApi.kt:447` | `AgentMessageRequest(session_id, prompt, run_id)` (`CrushDtos.kt:61-65`). |

### 1.2 `StreamMessage` — the part model

`app/src/main/java/com/crush/mobile/data/crush/CrushEvent.kt:67-181`, `data class StreamMessage(id, role, session_id, parts: List<JsonObject>)`.

Key accessors:

| Accessor | Lines | Behaviour |
|---|---|---|
| `text` | `74-78` | Concatenates **all** `type=="text"` parts. If **any** text part has `data.hidden == true`, the whole concatenation is suppressed to `""`. |
| `thinking` | `80-82` | Concatenates `type=="reasoning"` → `data.thinking`. |
| `isFinished` | `84-85` | True when any `type=="finish"` part exists. |
| `finishError` | `102-113` | Non-null only for `finish.data.reason == "error"`. Handles `message` + `details` (string **or** one-level nested object) and falls back to `"provider error with no message"`. |
| `toolCalls` | `131-132` | `type=="tool_call"` parts. |
| `toolResults` | `150-159` | `type=="tool_result"` parts → `ToolResult(toolCallId, failed, durationMs)`. Duration comes from `metadata` which is a JSON **string** (`:217-225`). |
| `subAgentReports` | `170-180` | `type=="sub_agent_report"` parts → `SubAgentReport(label, output, failed, model)` (`:191-196`). |

Critical wire property, stated at `CrushEvent.kt:63-66`: **Crush republishes the WHOLE message on every update, never a delta, so text must be REPLACED, never appended.** The same class is used for history replay (`CrushApi.kt:342 messages(...): List<StreamMessage>`).

Safety helpers that must be reused by any new parser: `data()` at `CrushEvent.kt:234-237` (never `getAsJsonObject`, which throws on JSON null), `optObject()` `:243-246`, `optString()` `:256-257` (only primitives), `optBoolean()` `:259-260`, `optLongOrNull()` `:267-272`.

**Gap:** `StreamMessage` has no representation for any part type other than text, reasoning, finish, tool_call, tool_result and sub_agent_report. Any unknown part type is silently dropped by every accessor. This contradicts the acceptance kit row 6, `docs/engine-acceptance-kit.md:102`: "the UI must render unknown parts as text and never crash". It does not crash; it renders nothing.

### 1.3 Turn state machine

`ui/screens/chat/ChatViewModel.kt`:

- `send()` `:3501`; preflight and POST in `streamSend()` `:3661`.
- The turn's assistant bubble is minted at `:3688` (`"streaming-${UUID}"`) and owned by `RunHandle.bubbleId` (`RunHandle.kt:65-73`).
- Frame loop `awaitStream()` `:4186-4535`:
  - `MESSAGE` `:4331-4408`
    - assistant text: `updateStreaming { it.copy(text = m.text.ifBlank { it.text }, thinking = …, tools = merge.tools.ifEmpty { it.tools }, error = m.finishError ?: it.error) }` `:4360-4367`. Note: blank text keeps the previous value; error is **latched** and never cleared.
    - `role=="tool"` with `toolResults` `:4368-4401` — the only live completion signal; calls `handle.completeTool(...)`.
    - `subAgentReports` `:4402-4407` → `appendReportRow()` `:4584-4608`, inserted **above** the streaming bubble.
  - `PERMISSION_REQUEST` `:4413-4458`: on the ChatPool instance, auto-denies and appends a `role="system"` denial note (`:4416-4447`); otherwise sets `pendingPermission`.
  - `QUESTION_REQUEST` `:4460-4475`: keeps the raw batch in `PendingQuestion(id, raw: JsonElement)` (`:323-329`); the projection later keeps only id + display title.
  - `RUN_COMPLETE` `:4477-4525`: strict `runId` match; sets `isStreaming=false`, adopts `rc.text` if non-blank, settles RUNNING tool rows to COMPLETED; throws `StopStream`.
  - `else -> Unit` `:4527`.
- Whole-row replacement point: `updateStreaming()` `:4565-4574` maps the messages list and transforms only `handle.bubbleId`.
- Stop: `stopStreaming()` `:4693`; server cancel first, `StopAction` in `RunHandle.kt:242-259`.

### 1.4 History replay

`replayRow(m: StreamMessage): ChatMessage?` — `ChatViewModel.kt:98-127`:

- user row kept when text non-blank **or** reports present (`:108-114`);
- assistant row kept when text, tools, reports or error present (`:115-124`);
- parts-less assistant messages are deliberately dropped (`:90-96`).

Merging a fetch with live rows: `mergeReplay()` `:143-149`; carry-over rule `liveRowsForOpenSession()` `:163-169`; late sub-agent refresh `lateReportRefreshDue()` `:188-191` and poller `:4628-4670`.

### 1.5 UI state carrier

`ui/screens/chat/ChatViewModel.kt:279-311`:

```
@Immutable data class ChatMessage(
  id, role, text, thinking = "", isStreaming = false, error: String? = null,
  tools: List<ToolActivity> = emptyList(),
  reports: List<SubAgentReport> = emptyList()
)
```

`@Immutable` is an assertion for Compose skipping (`:272-278`); rows are replaced wholesale, never mutated.

`ChatUiState` (`:364-…`) holds `messages`, `models: List<LiveZenModel>`, `selectedModel`, `pendingPermission`, `pendingQuestion`, `sessions`, `fileChanges`, handoff state, `isStreaming`, `recoverable`, `stopNotice`, `deleteError`, `providerId`.

### 1.6 Projection and renderer entry

- `ChatHostInputs` + `BindingKind` / `EngineReadiness` / `EnterSendMode` / `FreeMarker` — `ui/screens/chat/ChatHostInputs.kt:13-100`.
- `ChatViewState` — `ui/screens/chat/ChatViewState.kt:17-27`; `TranscriptView` `:158-170` (`messages`, `toolsByCallId`, `reports`, `fileChanges`, `fileChangesLabel`); `ModelView` `:119-156`; `ComposerView` `:172-186`; `GateView` `:235-244`.
- Pure projection `ChatUiState.toViewState(inputs)` — `ChatViewState.kt:263-367` (declared deterministic/total/side-effect-free at `:257-262`).
- Transient values NOT rendered: `ChatMessage.thinking` is assigned at `ChatViewModel.kt:4363` and never read by any composable; `TranscriptView.toolsByCallId` and `fileChangesLabel` are projected (`ChatViewState.kt:165,169,336,339`) but not consumed by `ChatContent` (grep: only `ChatViewState.kt` itself).

---

## 2. Chat rendering

### 2.1 Hosts and top-level contents

| Surface | File:line | Notes |
|---|---|---|
| Chat host | `ui/screens/chat/ChatScreen.kt:28-261` | Owns Context, AppContainer, ViewModel, trust dialog, engine start. Builds the projection at `:107` (`remember(host) { host.chat.toViewState(host.inputs) }`). Switches list vs conversation at `:213-260`. |
| Conversation renderer | `ui/screens/chat/ChatContent.kt:73-566` | Public entry. Reads no Context/VM/nav aside from clipboard/SAF inside helpers. |
| Session-list renderer | `ui/screens/chat/ChatListContent.kt:52-160` | List-first home for both modes; renders **no messages**, only session rows (`SessionRow` `:162-213`). |
| Review renderer | `ui/screens/review/ReviewContent.kt:64-353` | Reuses `FileDiffRow` (`:42` import, `:171-176`). |
| Markdown | `ui/screens/chat/MarkdownText.kt:69-280` | Public composable; `MarkdownParser.parse` called per text change (`:76`). |

### 2.2 `ChatContent` structure (the hook surface)

- `ChatContent(...)` `:73-79` — parameters: `state: ChatViewState`, `onAction: (ChatAction) -> Unit`, `pendingTrust`, `onTrustConfirm`, `onTrustDismiss`.
- Local-only state: `showModels`, `showSessions` `:80-81`; `listState` `:82`.
- Autoscroll rule `:88-93` (only when within 3 items of the bottom).
- Scaffold `:115-350`:
  - top bar `:117-209` — session label, `CrushStatusLabel` from `binding.headerStatus(run)` `:130-139`, chats button `:142-151`, new chat `:152-162`, model picker `:167-206`.
  - bottom bar = `Composer(...)` `:210-238` — **values only from `state.composer`**.
  - body `:239-350`:
    - empty transcript → `state.emptyCopy()` and `CrushEmptyState` `:240-253`;
    - `LazyColumn` `:255-348`; handoff resume sticky header `:272-286`;
    - `itemsIndexed(messages, key = { _, m -> m.id })` `:287-321`; retry prompt derived from the preceding user row `:295-299`; `Bubble(...)` `:300-320`;
    - file-changes panel at the end of the list `:329-347`.

- Dialogs / sheets rendered outside the list:
  - permission `:355-416` (Allow once / Always / Deny; dismiss = deny via `ChatDispatch.permissionDismiss`);
  - question `:425-459` (this build can only release, not answer);
  - session sheet `:461-490`;
  - model sheet `:492-506`;
  - handoff consent sheet `:517-553`;
  - `CrushrcTrustDialog` `:558-565`.

### 2.3 The message row

`Bubble(m, retryText, retryEnabled, onRetry, onUsePlan)` — `ChatContent.kt:573-726`, `private`.

Order of rendering inside a row:
1. `role == "system"` → centered `Text`, live region, returns early `:599-609` (no bubble, no actions).
2. `reportOnly` detection `:611` (reports present, no text, no tools) → suppresses the "You/Crush" label and user bubble.
3. Role label "You"/"Crush" `:619-626`.
4. Sub-agent report cards `:630-635` → `SubAgentReportCard` `:736-780` (label, model, raw `output` in `CrushType.code`; no Markdown, no parsing).
5. Tool activity **above** the reply `:646-649` → `ToolActivityList` `:887-897` → `ToolRow` `:1093-1215`:
   - collapsed row: state icon, tool name, args preview (`take(70)`), engine `durationMs` chip;
   - expanded: pretty-printed args JSON (`CrushType.code`), error, truncated output;
   - `remember(tool.id)`, auto-expanded only while `isRunning` `:1098`.
6. Error card `:650-662` → `FailedBubbleCard` `:798-877`: errorContainer surface, "This reply failed", **partial text still rendered as Markdown** `:840-843`, raw error, Retry.
7. Normal text `:663-708`: `"thinking..."`/`"working..."` placeholder `:685-690`, else `MarkdownText(m.text, …)` `:692-705` inside a bubble with role-dependent shape/colour.
8. Completed-assistant actions row `:720-722`, gated by `handoffEntryVisible(m)` (`HandoffConsent.kt:158-159`: `role != "user" && !isStreaming && error == null && text.isNotEmpty()`).

`FileChangesPanel` `:911-965`: "This turn's changes", counts, unreadable list, per-file `FileDiffRow` `:970-1084` (`internal`, reused by Review), and "Review all turns".

### 2.4 Markdown and code

- `MarkdownParser.kt` — hand-rolled, streaming-safe, deliberately not CommonMark (`:3-29`). `MdBlock` = Code/Paragraph/Heading/Bullet/Numbered/Quote/Rule `:30-38`; `MdInline` = Text/Code/Bold/Italic/Link `:40-46`; `parse()` `:56`. Unterminated fences still render as code (`:91-94`).
- `MarkdownText.kt:81-141` maps blocks to Text/Row/HorizontalDivider. Code blocks `:146-220`: language label, Copy, "Save as file", horizontal scroll, **`CODE_MAX_LINES = 200`** `:49`, truncation marker `:156-160`.
- `inlineAnnotated()` `:222-280`: `AnnotatedString` with SpanStyles. Links are **underlined only, not clickable** `:259-266` ("opening arbitrary URLs from a bubble needs a deliberate affordance and a confirmation, and that is a separate decision").
- No `Html.fromHtml`, no `AndroidView`, no WebView, no `androidx.webkit` anywhere (verified by search; see §3).
- Message-level export: `ChatExport.kt:185-225` (Copy message, Save as Markdown, Use this plan in Code) and SAF saver `:129`; code-block content is the untrimmed block `:58-59`.

### 2.5 Where a structured block renderer can hook in

Observed seams (design observation, not a change):

- **Data seam (minimal risk):** add an additive, defaulted field to `ChatMessage` (`ChatViewModel.kt:280-311`) and fill it in exactly two places — `replayRow()` `:98-127` and the MESSAGE branch of `awaitStream()` `:4331-4408`. `TranscriptView` (`ChatViewState.kt:158-170`) would need the same additive field only if the projection must expose it separately; it already carries `messages`, so a `blocks` field on `ChatMessage` needs no projection change.
- **View seam:** inside `Bubble`, the assistant text branch at `ChatContent.kt:663-708` (call the renderer beside/instead of `MarkdownText` at `:692`), or as a sibling above the bubble at the same level as `ToolActivityList` (`:646-649`). `Bubble` is `private`, so a new renderer can be added without widening the public surface.
- **Zero-model-change alternative:** a reserved fenced-code language tag parsed by `MarkdownParser` and rendered by `CodeBlock` (`MarkdownText.kt:146`). This changes no state and no test fixture, but the structured payload stays a string. **INFERENCE.**
- **Must not touch:** `Composer` (`ChatContent.kt:1218-1282`), `ComposerView`/`run`/`canType`/`canSend` in `ChatViewState.kt:284-286, 341-349`, or the bottom-bar wiring `ChatContent.kt:210-238`. The composer table is explicitly frozen: `docs/v2/V2-4-chat-redesign.md:46-50` states `canType = !streaming && (engineReady || !prewarm || recoverable)`, `canSend = !streaming && (engineReady || recoverable) && draft.isNotBlank()`, `canSubmitViaIme = canType && canSend`, Stop enabled for the whole run, pending permission/question do not gate.
- **Source-compatibility rule:** every additive `ChatViewState`/`ChatMessage` field so far is defaulted specifically so existing constructions stay source-compatible (`ChatViewState.kt:41-45, 52-59, 71-78, 143-149, 180-186`). Test fixtures construct these classes directly (`ChatContentTranscriptTest.kt:305-…`), so a non-defaulted field would break many tests at compile time.

---

## 3. Existing structured-output / agent-UI support (search)

Search terms run over `app/src` (main + test + debug + androidTest), plus Gradle files and docs. Results:

| Term | Matches in code | Verdict |
|---|---|---|
| `A2UI`, `a2ui` | **0** in `app/src`; **0** in `*.gradle.kts` | DOCS only (`docs/v2/V2-0-baseline-lock:299`, `ADR-REGISTER:76`). Not adopted, not a dependency. |
| `json-render`, `jsonrender` | 0 | DOCS only (`V2-0-baseline-lock:300`). |
| `AG-UI` / `agui` | 0 | Absent entirely (not even docs). |
| `CopilotKit` | 0 in code | DOCS only (`V2-0-baseline-lock:301`). |
| `WebView`, `webkit`, `AndroidView`, `setJavaScriptEnabled`, `loadDataWithBaseURL`, `android.text.Html`, `fromHtml` | **0** | No HTML/WebView path exists. |
| `AnnotatedString` | `MarkdownText.kt:26,28,223-227`; `ChatExport.kt:23,171` | Real: styled inline text and clipboard only. |
| `JsonElement` / `JsonObject` | `ChatViewModel.kt:70-72,328,355-360,4470-4472`; `CrushEvent.kt`; `ToolActivity.kt`; `EngineConfigInjector.kt` | Real: Gson parsing of engine payloads. **No JSON tree/viewer composable**; tool args are pretty-printed *text*. |
| `schema` | `EngineConfigInjector.kt:44,509,557` (engine config/catwalk schema); `EngineConfigInjectorTest.kt:130-137,216`; Room-migration comments (`ProjectDatabase.kt:17-18`, `ProjectTrustRoomRoundtripTest.kt:26,60`, `HandoffRoomRoundtripTest.kt:29-30,105`) | Real but unrelated to response rendering. No JSON-Schema parser, validator, or renderer. |
| `structured` | Comments only: `Providers.kt:275` ("rejects the agent's tool schema"), `EngineProxy.kt:210`, `FirstTurn.kt:35`, `ProjectTreeFingerprint.kt:10,44,47`, `HandoffPackage.kt:22,124` | No structured-response feature. |
| `adaptive` | `CrushShell.kt:45` (adaptive shell); mipmap `adaptive-icon` XML | Real, but layout breakpoints only (bottom bar <600dp, rail 600-839dp, extended rail >=840dp, `CrushShell.kt:44-57`). |
| `intelligent` | 0 | — |
| `renderer` / `render(` | `EngineApiContracts.kt:45` comment ("types the renderer must tolerate"); `SpikeActivity.kt:363,377` (debug-only state printer) | No renderer abstraction. |
| `deterministic` | `ChatViewStateTest.kt:19,25`; `ChatViewModel.kt:1212,1858`; `WorkspaceManager.kt:144-149`; file-digest code; `ProjectTreeFingerprintTest` | Real but about projection/state/session identity, not UI generation. |
| `todos` | `EngineConfigInjector.kt:435,481`; `EngineCatalogues.kt:63,74` | Only the deny list / catalogue. No todo UI. |

Provider research docs mention structured outputs (`docs/providers/openai.md:29`, `groq.md:307`, `xai.md:209`, `gemini.md:53`, `vertexai.md:103`, `cerebras.md:237`) — **DOCS only**, and about upstream APIs, not this app.

Conclusion: there is **no structured-output or agent-UI support, not even a dormant flag**. The only "agent UI" primitives are tool rows, sub-agent report cards, diffs, markdown text and the error card.

---

## 4. Model / provider metadata

### 4.1 What the app knows about a model

`data/api/ZenModelsApi.kt:49-64`:

```
data class LiveZenModel(id: String, name: String, isFree: Boolean, supportsImages: Boolean = false)
```

That is the complete UI-facing capability set. There is **no** context-window, max-output, tool-calling, reasoning, JSON-schema or structured-output field anywhere in the app's model type.

- `isFree` comes from Crush's `/providers` pricing cache or explicit `/models` prices; unknown is not free (`ZenModelsApi.kt:288-304`, `ModelPrice.isKnownZero` `:87-92`).
- `supportsImages` is injected from the engine catalogue: `visionCapableModelIds()` `ZenModelsApi.kt:379-400`, read from `capabilities.vision` with `supports_images` fallback, fail-closed; applied at `ChatViewModel.kt:1416-1470` and `:1918-1933`. Tests: `VisionCapableModelIdsTest` (5) and `EngineVisibleModelIdsTest` (3).
- `engineVisibleModelIds()` `ZenModelsApi.kt:426` filters out ids the engine no longer knows (`ChatViewModel.kt:1407, 1454-1463`).
- A separate, config-only model type exists in `data/crush/EngineConfigInjector.kt:48-64` (`ModelSpec` with `contextWindow`, `defaultMaxTokens`, `canReason`, `reasoningEfforts`, `defaultReasoningEffort`, `supportsVision`, `supportsAudio`). It is used **only** for the groq catalogue override (`:75-108`); it is not exposed to the picker or transcript.

### 4.2 Catalogue loading and selection

- Picker is fed the provider's live `/models`, not Crush's embedded catalogue: `ZenModelsApi.kt:16-47`, `fetch()` `:230-286`; used by `ChatViewModel.loadModels()` `:1880-…` and the bootstrap path `:1407-1486`.
- Selection is **measured**, not trusted: `ModelChoice.shortlist()` `ui/screens/chat/ModelChoice.kt:61-67` (preferred → free → rest, stable), `ZenModelsApi.firstWorking()` `:553`, `probe()` `:463`.
- A tap probes before writing the slots: `ChatViewModel.setModel()` `:2067-…`.
- The model must be written to both `large` and `small` slots (`CrushDtos.kt:127-148`).
- UI display: `ModelSheet` `ChatContent.kt:1525-…`; name, id, "Images" badge (`:1649-1659`), "FREE" badge (`:1660-1677`), selected check, "not in current list" banner (`:1577-1586`).
- No cost or context-window display; `ChatModelMetadataTest.kt:80-89` uses reflection to assert no price/cost accessor exists ("CORR-002: price detail is deferred"). `docs/ui-spec.md:108-112, 174-176` records context/cost as still open.

### 4.3 Can the app know a model's structured-output ability?

**No.** There is no field, no parsing and no configuration for it:
- `LiveZenModel` has four fields only.
- `ModelSpec` has no JSON-schema/structured-output flag.
- `SetModelRequest`/`SelectedModel` (`CrushDtos.kt:138-165`) carry `model`, `provider`, `reasoning_effort`, `max_tokens` — no response-format/schema field.
- `AgentMessageRequest` (`CrushDtos.kt:61-65`) carries only `session_id`, `prompt`, `run_id`.

Any V2-8 capability gate would therefore have to be **provider/model-allowlist knowledge added by the app** (or a new engine field), not something the current metadata can answer. **INFERENCE.**

---

## 5. Agent boundary, permissions, security rules

### 5.1 Tool calls and permissions as surfaced

- `ToolActivity` (`ui/screens/chat/ToolActivity.kt:70-133`): id, name, pretty args, state, output (truncated 4000 chars `:168`), description, path, error, firstSeenAt/finishedAt, engine `durationMs`. Parser `ToolActivityParser.from()` `:183-205`; state resolution `:230-238` (only an explicit `finished:true` is COMPLETED; otherwise RUNNING/UNKNOWN).
- Tool rows render in the assistant bubble above the text (`ChatContent.kt:646-649, 887-1215`).
- Permission request DTO: `CrushDtos.kt:169-178` (id, session_id, tool_call_id, tool_name, description, action, path, params).
- Project/Code mode: `pendingPermission` → dialog (`ChatContent.kt:355-416`); decisions `AllowOnce` / `AllowForSession` / `DenyPermission` (`ChatAction.kt:31-33`), mapped in `ChatDispatch.kt:64-69`. Default mode is ASK (`domain/model/PermissionMode.kt:15-35`, fails safe to ASK).
- Question batches: `PendingQuestion` (`ChatViewModel.kt:323-329`); raw payload retained but the projection keeps only `QuestionView(id, title)` (`ChatViewState.kt:241-244`); the dialog cannot choose answers (`ChatContent.kt:425-459`; `resolveQuestion()` `ChatViewModel.kt:2589`).

### 5.2 Chat (legacy/pool) deny-all

Two independent layers:

1. **Engine boundary (fail-closed).** `EngineConfigInjector.injectChatToolPolicy()` `EngineConfigInjector.kt:241-380` writes `options.disabled_tools` into the instance's `crush.json` pre-spawn and verifies it by read-back (`verifyChatPolicyOnDisk` `:401-427`). The list `CHAT_DISABLED_TOOLS` `:471-489` has **35 ids** (bash, edit, multiedit, write, view, fetch, agentic_fetch, glob, grep, ls, sourcegraph, download, job_*, lsp_*, crush_info, crush_logs, todos, question, task, diagnostics, list_mcp_resources, read_mcp_resource, agent/agent_dispatch/agent_send/agent_status/agent_stop). Spawn gate: `engine/CrushEngine.kt:273-341` — `chatPolicyGate()` `:663-674`; on any `PolicyFailure` the engine does **not** spawn (`EngineState.Failed`, willRetry).
2. **App-side defense in depth.** A `permission_request` arriving on the chat instance is auto-denied and a `role="system"` transfer note is appended (`ChatViewModel.kt:4416-4447`; note construction and persistence rules `:200-269`).

The policy applies to both `CHAT_INSTANCE_KEY = "chat"` and `""` (legacy) — `CrushEngine.kt:269-276`, `EnginePaths.kt:104-114`. Tests: `EngineConfigInjectorChatPolicyTest` (14), `ChatPolicySpawnGateTest` (5), `ChatDenialNoteTest` (5).

Standing rule, `docs/v2/ADR-REGISTER.md:82`: "Chat deny-all policy is an engine boundary, not a visual toggle."

### 5.3 Security rules a UI renderer must respect

Derived from code:

- **No arbitrary code/HTML execution.** There is no WebView, no JS engine, no HTML renderer in the app (§3). Adding one would create a new execution surface and a new egress path; nothing in the app currently sandboxes model output.
- **Model-authored links are not clickable** (`MarkdownText.kt:259-266`). Any renderer that adds actions over model text should keep that posture unless a consent design is added.
- **Network:** manifest has `INTERNET` + `ACCESS_NETWORK_STATE` only (`AndroidManifest.xml:5-6`); cleartext disabled globally and permitted only for `127.0.0.1`/`localhost` (`res/xml/network_security_config.xml:8-17`; `usesCleartextTraffic="false"` at manifest `:42`). Engine traffic is over the app-private Unix socket (`engine/EngineSockets.kt:16-28, 68-75`), with the HTTP host used only as a socket-factory key. A renderer must not add a network dependency.
- **Workspace is app-private:** legacy workspace is `filesDir/workspace` (`ReviewScreen.kt:53`); project engines bind to the imported folder only after the trust gate. Diffs/revert/export are host-owned and consent-gated (`ReviewScreen.kt:20-33`; `ReviewContent.kt:53-61`).
- **Model output is never executed or written as a file without the user**: chat save goes through a SAF CreateDocument picker (`ChatExport.kt:129-173`), and the bytes are exactly the emitted text.
- **No silent capability escalation:** the acceptance kit requires zero `observed tool_call` events in Chat (`docs/engine-acceptance-kit.md:99`). A renderer that implies interactive tool affordances in Chat would contradict a proven rail.

---

## 6. Build / dependency constraints

### 6.1 Versions (verified)

| Component | Version | Source |
|---|---|---|
| Gradle | 9.8.0 | `gradle/wrapper/gradle-wrapper.properties`; `docs/toolchain.md:24` |
| AGP | 9.4.1 | `build.gradle.kts:2` |
| Kotlin (KGP) | 2.4.10 | `build.gradle.kts:7` |
| Compose compiler plugin | 2.4.20 | `build.gradle.kts:11` |
| KSP | 2.3.13 | `build.gradle.kts:17` |
| compileSdk / targetSdk / minSdk | 37 / 36 / **29** | `app/build.gradle.kts:44,58-59` |
| Bytecode target | 17 (deliberate) | `app/build.gradle.kts:153-166`, `:248-252` |
| Build JDK | JBR 25.0.3 | `build-install.bat`; `docs/toolchain.md:23` |
| CI JDK | Temurin 21 | `.github/workflows/android-ci.yml` setup-java step |
| Compose BOM | 2026.09.00 | `app/build.gradle.kts:255` |

### 6.2 Dependencies (`app/build.gradle.kts:254-340`)

Compose UI/graphics/tooling-preview/material3/material-icons-extended; activity-compose 1.13.0; lifecycle 2.11.0; navigation-compose 2.10.2; datastore-preferences 1.2.1; Room 2.8.5 (+KSP); core-ktx 1.19.1; fragment-ktx 1.8.9; OkHttp 5.5.0 + okhttp-sse; junixsocket-common 2.10.1 (vendored native .so); Gson 2.14.0; coroutines-android 1.11.0.

Test: JUnit 4.13.2, coroutines-test 1.11.0, **Robolectric 4.17**, MockWebServer 5.5.0, Compose `ui-test-junit4 1.12.1` (test + debug). androidTest: `androidx.test:core 1.5.0`, `ext:junit 1.1.5`, `runner 1.5.0`, `ui-test-junit4 1.12.1`.

**No** Markdown library, no WebView/webkit, no A2UI, no JSON-schema library, no image loader. Markdown is hand-rolled by design (`MarkdownText.kt:51-59`).

### 6.3 Plausibility of adding a renderer dependency

- A2UI `1.0.0-alpha02` is recorded as "alpha; cannot be a V2.0 dependency; evaluation stays parked with V2-8" (`docs/v2/V2-0-baseline-lock-2026-11-19.md:299`). The project's stated posture is stable BOM only; `docs/ui-spec.md:30-34, 153` explicitly rejects alpha M3 for the same reason. **INFERENCE:** adopting A2UI would require an owner decision to break that posture, not just a Gradle line.
- `json-render` has no Compose renderer (`:300`) and cannot be used natively.
- CopilotKit is a web/iframe architecture (`:301`) and conflicts with the no-WebView posture.
- A hand-written Compose renderer over the app's own JSON (like the hand-rolled Markdown parser) adds **no** dependency and is the only path that fits the current constraints. **INFERENCE.**

### 6.4 Module layout

- Single Gradle module: `settings.gradle.kts` includes only `:app` (`:12-13`); `rootProject.name = "CrushMobile"`.
- Source sets: `app/src/main`, `app/src/test`, `app/src/androidTest`, `app/src/debug` (debug-only `SpikeActivity` + manifest — `AndroidManifest.xml:55-59`).
- Packages: `data/{api,crush,local,project,handoff,repository}`, `domain/{file,handoff,model,provider}`, `engine`, `ui/{screens/{chat,projects,review,settings,onboarding,diagnostics,lock}, navigation, components, theme}`, `di`, `project`, `util`.

### 6.5 Gate commands

- Project rule: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease` on JBR 25 (`docs/v2/README.md:62-63`; `README.md:460`; CI uses the same at `.github/workflows/android-ci.yml:95`).
- Install loop: `build-install.bat` → `gradlew :app:assembleDebug` then `adb -s bbf8ca69 install -r -d`.
- Unit-test caveats: `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts:184`), `isIncludeAndroidResources = true` (`:192`), Robolectric `--add-opens` list (`:200-212`), `maxHeapSize = "2g"` and `forkEvery = 50` (`:373-374`).
- Latest recorded gate: V2-9, **924 tests / 0 failures**, lint 0 errors / 1 warning (`docs/v2/README.md:58`).
- Device: Redmi Note 7 Pro, API 29, serial `bbf8ca69` (project state).

---

## 7. Determinism and existing "intelligent" behaviour

There is a real, test-pinned determinism story — but it is about **state projection**, not about generating or adapting UI.

| Behaviour | Code | Determinism evidence |
|---|---|---|
| Pure state projection | `ChatViewState.kt:257-367` | Declared "Deterministic, total and side-effect free"; plain-JUnit pin `ChatViewStateTest.kt:24-57` ("projection is deterministic for equal inputs"). |
| Clock-free transcript projection | `ChatViewState.kt:52-58` | Relative time is computed at render (`ChatListContent.kt:62`, `SessionList.detail(...)`) precisely because the projection may not read a clock. |
| Model choice by measurement | `ModelChoice.kt:42-73`; `ZenModelsApi.firstWorking` `:553` | Stable shortlist order; probe order biased by provider-verified ids; never a hardcoded default (`ModelChoice.kt:5-41`). |
| Session resume rule | `ChatViewModel.kt:1855-1877` | "deterministic resume the restart proof needs, never last-with-content" (pending handoff target first, else newest with messages). |
| Workspace identity | `WorkspaceManager.kt:144-149` | Id derived from the path. |
| Static tuning | `domain/model/TuningPolicy.kt:44-91` | `USER > AUTO > safe default`, null = unset, clamped. |
| Dynamic tuning | `domain/model/DynamicPolicy.kt:97-194` | Pressure levels, asymmetric hysteresis (3 calm samples), never sheds engine while `workActive`. |
| Provider switch serialisation | `ProviderSwitchPolicy.kt:29-56` | NoOp / ClearPending / ApplyNow / Defer. |
| Readiness | `ChatHostInputs.kt:92-99` | Single rule; `Ready` unreachable on binding/key mismatch. |
| Adaptive layout | `CrushShell.kt:44-57` | Width breakpoints only. |
| File-change measurement | `domain/file/ProjectTreeFingerprint.kt:10,44-47` | Deterministic stat-only walk, sorted, SHA-256. |

What does **not** exist:
- No notion of a "deterministic UI" beyond the pure projection; no schema-driven UI, no component registry, no state-machine-driven renderer, no snapshot/golden render tests.
- No adaptation of the **transcript** to model/provider/device. The only adaptation is layout width (shell) and colour/type tokens.
- No response-rendering preference; new rendering preferences are explicitly deferred (`docs/v2/ADR-REGISTER.md:23`, `docs/v2/README.md:43-44`, `docs/v2/V2-6-settings-onboarding.md:29,38`). Settings has theme, enter-send, permission mode, tuning only (`domain/model/AppSettings.kt:14-39`; `SettingsContent.kt:306-341`).
- No model-generated UI of any kind. The engine emits only the part types listed in §1.2; the app renders a fixed set of surfaces.

**INFERENCE:** "deterministic intelligent UI foundation" would today mean formalising the existing projection/`@Immutable`/stable-key discipline (LazyColumn keys `ChatContent.kt:287`, stable `ToolActivity.id`, `handoffEntryVisible`) into a renderer contract. There is no foundation to extend for model-driven UI; it would be new.

---

## 8. Tests and fixtures a V2-7/V2-8 change must keep green

Per-class counts below are `@Test` methods counted in the tree (not the archived gate census).

### 8.1 Chat pipeline / state

| Test | Path | Count | Pins |
|---|---|---|---|
| `StreamMessageTest` (inside `CrushProtocolTest.kt`) | `app/src/test/java/com/crush/mobile/data/crush/CrushProtocolTest.kt:129` | 14 (file 26) | text/reasoning/finish/finishError shapes, malformed parts |
| `ChatHistoryReplayTest` | `.../ui/screens/chat/ChatHistoryReplayTest.kt` | 10 | finish-error rows, empty-parts, tool-only, reports, tool_result duration |
| `ChatStateMergeTest` | `.../ChatStateMergeTest.kt` | 3 | replay/live merge, no duplication |
| `ChatSessionTranscriptGuardTest` | `.../ChatSessionTranscriptGuardTest.kt` | 4 | rows do not leak across sessions |
| `ChatViewStateTest` | `.../ChatViewStateTest.kt` | 18 | determinism, totals, transcript map, readiness |
| `ChatHostInputsTest` | `.../ChatHostInputsTest.kt` | 7 | readiness/seed rules |
| `ChatGateTest` | `.../ChatGateTest.kt` | 7 | **the eight composer rows are exact** (`:48`) |
| `ChatDispatchTest` | `.../ChatDispatchTest.kt` | 8 | 37-row interaction ledger |
| `ChatHostCallbackTest` | `.../ChatHostCallbackTest.kt` | 4 | draft callback freshness |
| `ChatDenialNoteTest` | `.../ChatDenialNoteTest.kt` | 5 | one note per turn, dedupe |
| `LateReportRefreshTest` | `.../LateReportRefreshTest.kt` | 4 | bounded sub-agent refresh |
| `RunHandleTest` | `.../RunHandleTest.kt` | 13 | stop identity, tool merge/completion/duration |
| `NewChatBootstrapTest` | `.../NewChatBootstrapTest.kt` | 3 | new-chat bootstrap |
| `ProviderSwitchPolicyTest` | `.../ProviderSwitchPolicyTest.kt` | 7 | defer/no-op rules |

### 8.2 Rendering (Robolectric + Compose)

| Test | Path | Count | Pins |
|---|---|---|---|
| `ChatContentTranscriptTest` | `.../ChatContentTranscriptTest.kt` | 13 | empty copy, transcript rows, actions, resume bar; **defines `ChatFixtures`** at `:305-…` |
| `ChatContentGateTest` | `.../ChatContentGateTest.kt` | 8 | permission dialog decisions, question release, `canType`/`canSend` wiring |
| `ChatContentSheetsTest` | `.../ChatContentSheetsTest.kt` | 16 | session/model/handoff sheets |
| `ChatListContentTest` | `.../ChatListContentTest.kt` | 6 | list-first home, loading/unavailable/empty |
| `ChatExportTest` | `.../ChatExportTest.kt` | 7 | code content ignores display trim, save names |
| `HandoffEntryChipTest` | `.../HandoffEntryChipTest.kt` | 7 | the Copy/Save/Use-plan gate |
| `HandoffConsentContractTest` | `.../HandoffConsentContractTest.kt` | 11 | consent contract |
| `HandoffConsentOrderTest` | `.../HandoffConsentOrderTest.kt` | 2 | ordering |
| `ReviewContentTest` | `.../ui/screens/review/ReviewContentTest.kt` | 13 | Review cards, revert/export surfaces |
| `MarkdownParserTest` | `.../MarkdownParserTest.kt` | 28 | blocks/inlines/partial streaming input |
| `ToolActivityParserTest` | `.../ToolActivityParserTest.kt` | 28 | wire shapes, state, truncation, shape log |

### 8.3 Model / provider / engine boundary

| Test | Path | Count | Pins |
|---|---|---|---|
| `ChatModelMetadataTest` | `.../ChatModelMetadataTest.kt` | 6 | selected-model identity, legacy `-free` suffix rule, **no price/cost accessor** |
| `ModelChoiceTest` | `.../ModelChoiceTest.kt` | 22 | shortlist/probe order, explanations |
| `ModelAccessExplanationTest` | `.../ModelAccessExplanationTest.kt` | 9 | user-facing model failure copy |
| `SessionListTest` | `.../SessionListTest.kt` | 16 | ordering/labels/cost/forks |
| `SessionContinuityTest` | `.../SessionContinuityTest.kt` | 7 | session reuse |
| `ProvidersTest` | `.../domain/provider/ProvidersTest.kt` | 22 (per project notes) | provider table/order |
| `VisionCapableModelIdsTest` | `.../data/api/VisionCapableModelIdsTest.kt` | 5 | vision capability parsing |
| `EngineVisibleModelIdsTest` | `.../data/api/EngineVisibleModelIdsTest.kt` | 3 | catalogue filtering |
| `EngineConfigInjectorTest` | `.../data/crush/EngineConfigInjectorTest.kt` | 12 | catalogue schema, reasoning/vision |
| `EngineConfigInjectorChatPolicyTest` | `.../data/crush/EngineConfigInjectorTest.kt` | 14 | deny-list install/readback, fail-closed |
| `ChatPolicySpawnGateTest` | `.../engine/ChatPolicySpawnGateTest.kt` | 5 | spawn veto mapping |
| `EngineApiContractTest` | `.../data/crush/EngineApiContractTest.kt` | 4 | surface contract incl. new SSE shapes |
| `CrushEventRouterTest` | `.../data/crush/CrushEventRouterTest.kt` | 21 | run fan-out |
| `TuningPolicyTest` / `DeviceProfileTest` | `.../domain/model/TuningPolicyTest.kt` | 9 / 5 | precedence, clamping, RAM tiers |
| `DynamicPolicyTest` | `.../domain/model/DynamicPolicyTest.kt` | (suite) | pressure, hysteresis, workActive |
| `PermissionModeTest` | `.../domain/model/PermissionModeTest.kt` | (suite) | ASK default, fail safe |

### 8.4 Instrumented (`app/src/androidTest/java/com/crush/mobile/`)

`MainActivitySmokeTest.kt`, `MainActivityLockFlowTest.kt`, `SecureKeyStoreInstrumentedTest.kt`, `TwoProjectAttachTest.kt`, `TwoEngineInstancesPerProjectTest.kt`, `ProjectStoreRoomPersistenceTest.kt`. None touches message rendering.

### 8.5 Fixtures and structural constraints

- `ChatFixtures.view(...)` (`ChatContentTranscriptTest.kt:305-…`) is the single fabricated `ChatViewState` builder used by the content tests. It constructs every nested view explicitly, so additive fields must be defaulted or all call sites must change.
- `ChatFixtures` is `internal` in the test source set; a new renderer test should reuse it rather than create a second fixture (the file's comment at `:301-304` says it is deliberately not a production helper).
- Robolectric config used by content tests: `@RunWith(RobolectricTestRunner::class)`, `@Config(qualifiers = "w411dp-h891dp")` (`ChatContentTranscriptTest.kt:27-29`).

---

## 9. What exists / what is missing

### Exists (CODE or CODE+TEST)

- One assistant pipeline with typed accessors for text, reasoning, finish/error, tool calls, tool results (engine duration) and sub-agent reports; whole-message replace is the documented and implemented rule.
- One immutable `ChatMessage`, one pure `ChatViewState` projection, one action sink (`ChatAction`), one host/content seam (`ChatScreen` → `ChatContent`).
- Markdown rendering with code blocks, per-block copy/save, bounded layout (200 lines).
- Tool supervision rows (collapsed/expanded, state, duration, args, output), error card with partial text + Retry, sub-agent cards, per-turn diff panel, Review with per-file revert/export.
- Session list-first Chat/Code IA; model sheet with free marker and image capability.
- Hardened agent boundary: engine-level deny-all for Chat with read-back verification and fail-closed spawn; app-side auto-deny and denial note; ASK default for project engines.
- Deterministic, test-pinned projection and a large regression suite (924 tests recorded at V2-9).

### Missing (no code at all)

- Any structured-output capability metadata on models/providers (no context window, no JSON-schema, no tool-calling flag in `LiveZenModel`).
- Any renderer for structured/JSON content beyond pretty-printed text in a code style.
- Any representation of unknown message parts: they are dropped, despite `docs/engine-acceptance-kit.md:102` requiring them to render as text.
- Any clickable model-authored link or action (deliberate).
- Any HTML/WebView/JS surface (deliberate).
- Any response-rendering preference or per-message rendering mode.
- Any component registry / schema-driven UI / golden-render harness.
- Any written V2-7 or V2-8 brief; only parked names and the A2UI/json-render/CopilotKit research note are on disk.
- Rendering of `thinking`/`reasoning` (parsed to state, never shown) and of the projected `toolsByCallId`/`fileChangesLabel`.

---

## 10. Constraints and inferences for a V2-7/V2-8 change

Constraints grounded in code/tests:

1. Do not change composer gates. The formulas are frozen in `docs/v2/V2-4-chat-redesign.md:46-50` and pinned by `ChatGateTest` 7 and `ChatContentGateTest` 8.
2. Keep `ChatViewState`/`ChatMessage` additive fields defaulted; fixtures construct them directly.
3. Reuse the crash-safe JSON helpers in `CrushEvent.kt:234-271`; `getAsJsonObject` on nullable `data` is a known crash class.
4. Unknown engine part types must never crash and (per the acceptance kit) should render as text; today they vanish.
5. The transcript is republished whole; any new renderer must be idempotent per frame and must not accumulate duplicate rows (the same rule as `RunHandle.mergeTools` and `appendReportRow`).
6. No new network, no WebView/HTML/JS, no clickable model links without a consent design; cleartext only for loopback.
7. Chat deny-all is an engine boundary; a renderer must not offer tool affordances in Chat.
8. Any new surface must not disturb the Chat/Code list-first route logic (`ChatScreen.kt:111-121`) or the shell breakpoints.

Inferences (labelled):

- **INFERENCE:** the least invasive V2-8 path is a typed block field on `ChatMessage`, populated in `replayRow` and the `MESSAGE` branch, rendered inside `Bubble`'s assistant branch; it touches no composer code and can be defaulted to keep every existing construction compiling.
- **INFERENCE:** A2UI cannot be adopted without an owner decision that reverses the "stable BOM, no alpha" posture recorded in `docs/ui-spec.md:30-34,153` and `docs/v2/V2-0-baseline-lock:299`.
- **INFERENCE:** V2-7's "deterministic" half can only be satisfied by strengthening the existing pure-projection contract (and adding a render-level pin), because no model-driven or adaptive-UI machinery exists to make deterministic.

---

## Appendix A — primary file map (absolute paths under the repo root)

| Area | File |
|---|---|
| SSE transport | `app/src/main/java/com/crush/mobile/data/crush/CrushEventStream.kt` |
| Event model / parts | `app/src/main/java/com/crush/mobile/data/crush/CrushEvent.kt` |
| DTOs | `app/src/main/java/com/crush/mobile/data/crush/CrushDtos.kt` |
| REST client | `app/src/main/java/com/crush/mobile/data/crush/CrushApi.kt` |
| Turn state machine | `app/src/main/java/com/crush/mobile/ui/screens/chat/ChatViewModel.kt` |
| Turn/stop identity | `app/src/main/java/com/crush/mobile/ui/screens/chat/RunHandle.kt` |
| Projection | `app/src/main/java/com/crush/mobile/ui/screens/chat/ChatViewState.kt` |
| Host inputs | `app/src/main/java/com/crush/mobile/ui/screens/chat/ChatHostInputs.kt` |
| Renderer | `app/src/main/java/com/crush/mobile/ui/screens/chat/ChatContent.kt` |
| List renderer | `app/src/main/java/com/crush/mobile/ui/screens/chat/ChatListContent.kt` |
| Markdown | `.../chat/MarkdownParser.kt`, `.../chat/MarkdownText.kt` |
| Tool rows | `.../chat/ToolActivity.kt` |
| Export/actions | `.../chat/ChatExport.kt` |
| Handoff gate | `.../chat/HandoffConsent.kt` |
| Model choice | `.../chat/ModelChoice.kt` |
| Review | `app/src/main/java/com/crush/mobile/ui/screens/review/ReviewContent.kt`, `ReviewScreen.kt` |
| Model catalogue | `app/src/main/java/com/crush/mobile/data/api/ZenModelsApi.kt` |
| Chat deny policy | `app/src/main/java/com/crush/mobile/data/crush/EngineConfigInjector.kt`, `engine/CrushEngine.kt`, `engine/EnginePaths.kt` |
| Engine contract | `app/src/main/java/com/crush/mobile/data/crush/EngineApiContracts.kt` |
| Tuning | `app/src/main/java/com/crush/mobile/domain/model/TuningPolicy.kt`, `DynamicPolicy.kt`, `PermissionMode.kt` |
| Shell | `app/src/main/java/com/crush/mobile/ui/navigation/CrushShell.kt`, `NavGraph.kt` |
| Build | `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` |
| Tests | `app/src/test/java/com/crush/mobile/**`, fixtures at `ui/screens/chat/ChatContentTranscriptTest.kt:305` |
| CI / gate | `.github/workflows/android-ci.yml`, `docs/v2/README.md:62-63` |

## Appendix B — commands used by the project

- Full gate: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease` (JBR 25; CI Temurin 21).
- Install: `build-install.bat` (assembleDebug + `adb -s bbf8ca69 install -r -d`).
- Instrumented: `./gradlew :app:connectedDebugAndroidTest` on `bbf8ca69` (API 29).
- Latest recorded result: 924 tests / 0 failures; lint 0 errors / 1 warning (`docs/v2/README.md:58`).
