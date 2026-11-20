# V2-4 - Chat redesign (in progress, started 2026-11-20)

Status: **IN PROGRESS.** This record is written before the code change so the
scope, decisions and evidence rows exist independently of the implementation.
Unfinished rows say NOT PERFORMED; they are filled only from a build log, a CI
run or a device capture.

Authority:

- Revision 4.0 (2026-11-19) V2-4 goal, tasks and exit criteria.
- Rev 3.1 brief: §4.7.1 (chat rules), §5.5.1 (composer gates), §5.6
  (Content/Host split), §4.9 (performance), §6 (test registry).
- Owner decision V2-A4 (2026-11-19): V2-4 tasks **4 (pinned/follow and
  jump-to-bottom) and 7's cost/context metadata are trimmed** and stay
  backlog. The picker itself stays, with authoritative provenance.
- V2-0 / V2-2 findings F-B, F-C, F-E and F-J are carried into this package.

## 1. Scope

In scope:

| # | Task | What "done" means here |
|---|---|---|
| V2-4.1 | Redesign `ChatContent` on the existing seam | Same `ChatViewState` in, same `ChatAction` out; no host or dispatch rewrite |
| V2-4.2 | Useful empty/welcome state | State-aware copy that never claims the engine is broken when prewarm is off; no fake buttons |
| V2-4.3 | Transcript rhythm, Markdown/code readability, message actions | Role labels, spacing/max width, heading fixes, code header rhythm; Copy / Save Markdown / Use-plan / Retry preserved |
| V2-4.5 | Composer/IME and Stop | Composer gates unchanged; taller growth, visible Stop while streaming |
| V2-4.6 | Session sheet loading/empty/unavailable | Distinct loading, empty, unavailable-with-cache and unavailable-empty states; current chat marked by icon + word |
| V2-4.7 | Model/provider picker provenance | Picker always visible; provider-aware sheet copy; loading, error, reload, and "not in current list" marker; no cost/context metadata |
| V2-4.8 | Preserve error card, Retry, Copy, Save Markdown, Save code | No behavioural change to these paths; existing tests stay green |
| V2-4.9 | Accessibility and font-scale source checks | Selection is never colour-alone; role labels are text; no fixed text heights; touch targets stay >= 48dp |
| Carry-in F-E | Header phase | Header status derives from the same coherent snapshot as the binding/readiness, not from a separately-updated prose field |
| Carry-in F-J | Selected-segment affordance (Settings) | Selected chip carries a filled check, border and weight, not colour alone |

Out of scope (explicitly):

- Pinned user message, follow-output toggle, jump-to-bottom FAB (V2-A4).
- Cost, price and context-window metadata in the picker (V2-A4).
- O4 tool-activity visibility changes. Existing tool-row presentation is kept;
  no new visibility level without owner approval.
- Delete-without-confirmation. Recorded pre-existing defect (flow 13); this
  package only improves the sheet's state distinctions.
- Parser feature work (tables, images, task lists, autolinks). Markdown parser
  behaviour is unchanged; only heading rendering is corrected.
- Draft persistence, attachments, voice, per-message regenerate/edit/delete.
- Any composer gate change. The Rev 3.1 §5.5.1 table is frozen:
  `canType = !streaming && (engineReady || !prewarm || recoverable)`,
  `canSend = !streaming && (engineReady || recoverable) && draft.isNotBlank()`,
  `canSubmitViaIme = canType && canSend`; Stop is enabled for the whole run;
  pending permission/question do not gate the composer.
- Surfaces outside Chat and the Settings chip migration needed for F-J.

## 2. Decisions

### V2-4-D1 - Header status comes from the readiness snapshot

`RunView.phase` is a free-text field written by several independent ViewModel
paths; F-E showed it can read `connecting to agent` long after the engine
stopped. `RunView.readiness` is computed in the projection from the same
`ChatHostInputs` (`snapshotReady`, instance-key match, engine state) that
produces `BindingView`. The header therefore renders a small pure
`RunView.statusLabel(kind)`:

- `Switching` -> `switching agent`
- `Ready` -> `ready`
- `Recoverable` -> `agent not ready - send to retry`
- `Starting` / `Failed` -> the raw phase, because there it is the only detail
  (attempt number / failure reason) and readiness already constrains it.

`ChatPool` keeps its surface hint (`Chat - no tools`) joined to the status with
a middle dot. A status dot plus the words replaces the success-colour-only
signal.

### V2-4-D2 - Empty state is state-aware and makes no false claim

The old single line (`Engine starts when you begin typing`) was right only for
prewarm-off cold start. The new copy is a pure projection of
`ChatViewState.emptyCopy()` with this order:

1. `Failed` -> "Agent unavailable" + the failure phase.
2. `Recoverable` -> "Agent needs attention" + "Send a message to retry".
3. `!prewarm && !engineReady` -> "Start a conversation" + the first message
   starts the agent.
4. `Ready` -> "Start a conversation" + an honest capability line
   (`Chats` binding also says tools are off; `Project` says work happens in the
   project).
5. `Starting` / `Switching` -> "Start a conversation" + "Starting the agent...".

No action button is added: every action either exists in the top bar already
or would be a fake affordance on a new chat.

### V2-4-D3 - Picker is always visible and its provenance is authoritative

- The top-bar control renders `displayName`, then the id with the legacy
  `-free` suffix stripped, then the literal word `Model`. It is therefore
  visible on a new chat (F-C) and carries a dropdown arrow so it reads as a
  picker.
- The sheet subtitle uses `state.model.providerName`, never a hard-coded
  `OpenCode Zen` (F-B). `FREE` remains a marker on rows, explained generically.
- New `ModelCatalogueView.loading` (defaulted false) is projected from a new
  `ChatUiState.modelsLoading`; opening the sheet triggers the existing
  `OpenModels`/`loadModels` path. Loading, error and empty are distinct.
- When the selected model is absent from the live catalogue, the sheet shows a
  warning banner naming the id and a `Reload` action that dispatches the
  previously-unemitted `ReloadModels`. Selecting a different row still works.

### V2-4-D4 - Selection is never colour-alone

- Session sheet: the active row gets a filled check plus the word `Current`
  and `stateDescription = "Current chat"`, in addition to the existing fill.
- Settings (F-J): a new `CrushChoiceChip` wraps `FilterChip` with a filled check
  on selection, an accent border and semi-bold label. All existing Settings
  FilterChips migrate to it. Provider rows already use a `RadioButton` and are
  unchanged.

### V2-4-D5 - Transcript rhythm and role identity

- Every message gets a visible `You` / `Crush` label in metadata type; system
  notes keep their dedicated treatment. Role is therefore text, not bounds or
  hue.
- Message gap rises to the 12dp spacing token; the transcript keeps its
  near-bottom-only autoscroll rule unchanged.
- User bubbles wrap their content and stay right-aligned; assistant bubbles get
  a `widthIn(max = 640dp)` cap so a wide window does not produce 700dp prose.
  The cap is source-level only on this 393dp device.
- Existing bubble colours and the user `onPrimaryContainer` contrast fix are
  kept.

### V2-4-D6 - Markdown/code readability without parser changes

- Heading span size is applied to the whole heading, so bold/italic/code words
  inside a heading inherit the level size instead of falling back to 16sp.
- The per-level size is clamped to a 12sp floor (level 6 previously produced
  8sp text).
- Code blocks keep the 200-line display cap, full-block Copy/Save, language
  label, monospace surface and horizontal scroll. Header buttons use the
  metadata type at the existing 48dp touch target.

### V2-4-D7 - Composer and Stop

- `CrushDimens.composerMaxLines` (8) replaces the hard-coded 5; the composer
  keeps its 56dp minimum and its existing `canType` / `canSend` wiring.
- `Stop` stays enabled for the entire streaming run, and the field stays
  disabled while streaming exactly as today. Streaming text, `finishError`
  handling and Retry are untouched.
- IME behaviour stays on the manifest's `adjustResize`; no `imePadding` is
  stacked on top of it because that would double-shift the bar.

### V2-4-D8 - O4 stays open

Tool rows keep today's collapsed/expanded presentation and status words. The
unused `transcript.toolsByCallId` and `ChatMessage.thinking` remain unrendered;
surfacing them is a visibility decision, not a visual redesign.

## 3. Code map

| Area | File | Change |
|---|---|---|
| Projection | `ChatViewState.kt` | `ModelCatalogueView.loading`; pure `RunView.statusLabel()` and `emptyCopy()` |
| State | `ChatViewModel.kt` | `modelsLoading` flag around `loadModels`, reset on terminal paths/provider change |
| Chat surface | `ChatContent.kt` | header status + picker label; empty state; role labels/spacing; composer max lines; session/model sheet states |
| Markdown | `MarkdownText.kt` | heading span inheritance + 12sp floor |
| Components | `CrushComponents.kt` | `CrushChoiceChip` |
| Settings | `SettingsContent.kt` | FilterChip -> `CrushChoiceChip` (F-J) |
| Tests | `ChatViewStateTest`, `ChatContentTranscriptTest`, `ChatContentSheetsTest`, `CrushComponentsTest` | new pins listed below |

Unchanged on purpose: `ChatAction.kt`, `ChatDispatch.kt`, `ChatScreen.kt`,
`ChatGateTest`, `ChatDispatchTest`, `MarkdownParser.kt`, `SessionList.kt`,
`ChatExport.kt`, trust/handoff flows.

## 4. Test plan

| Pin | File |
|---|---|
| Status label: ready wins over a stale `connecting to agent` phase; Failure keeps its reason; ChatPool keeps its hint | `ChatViewStateTest` |
| Empty copy: cold start, ready, recoverable, failed; no "engine stopped" claim | `ChatViewStateTest` + `ChatContentTranscriptTest` |
| Picker visible with no selection and falls back to `Model`; selected display name wins | `ChatContentTranscriptTest` |
| Transcript shows `You` and `Crush` role labels; user bubble stays right, assistant actions unchanged | `ChatContentTranscriptTest` |
| Model sheet: provider-aware subtitle, loading row, error banner, not-in-list banner + Reload dispatch | `ChatContentSheetsTest` |
| Session sheet: current row announces `Current chat`; unavailable-with-cache banner; delete still immediate (pinned defect) | `ChatContentSheetsTest` |
| Settings chip: selected chip exposes selected semantics and check; clicking a sibling selects it | `CrushComponentsTest` |
| Existing 35-row dispatch ledger, 8-row composer gate table, export, parser, streaming and replay suites | unchanged, must stay green |

## 5. Evidence

| Claim | Evidence | Status |
|---|---|---|
| Four-task gate | `v2-4-gate-1120-2.log`: `BUILD SUCCESSFUL in 13m 57s` | PASS (attempt 2) |
| Unit-test count / failures | 899 tests, 95 suites, 0 failures, 0 errors, 0 skipped | PASS |
| Lint | `lint-results-release.txt`: 0 errors, 1 `NotShrinkingResources` warning (pre-existing) | PASS |
| CI | GitHub Actions run on the pushed commit | NOT PERFORMED |
| Chat empty state, ready status, picker visible | device capture set below | NOT PERFORMED (device off USB) |
| Streaming + Stop + Retry | device capture set below | NOT PERFORMED (device off USB) |
| Session sheet current marker / unavailable state | device capture set below | NOT PERFORMED (device off USB) |
| Model sheet provenance / loading / not-in-list | device capture set below | NOT PERFORMED (device off USB) |
| Settings F-J selected chip | device capture set below | NOT PERFORMED (device off USB) |
| Process recreation / no transcript bleed | device steps + log | NOT PERFORMED (device off USB) |
| Frame timing baseline vs post-change | `dumpsys gfxinfo` numbers | NOT PERFORMED; no pre-change baseline was captured on this device |

Gate attempt 1 (`v2-4-gate-1120-1.log`) failed one test: the session-sheet
current-row assertion used `hasAnyAncestor` on a clickable row whose text is
merged into the state-description node. The assertion was corrected to match
the same merged node, not the production code. Attempt 2 is the green run
above. A main-source compile failure before that (`gutter` read from
`CrushSpacing` instead of `CrushDimens`) is also recorded rather than hidden.

Device environment when the pass runs: Redmi Note 7 Pro `bbf8ca69`, API 29,
393dp portrait, app `com.crush.mobile`. Captures go in
`docs/parity/2026-11-20-v2-4-chat/`.

## 6. Honest limits

- API 29 cannot prove API 30+ behaviour, instrumented-test execution, or
  wide/tablet layouts. The 640dp bubble cap is a source rule, not an
  observation.
- 2.0x font scale is checked by source rules (sp type, no fixed text heights,
  wrapping rows) and, if the device pass allows it, by capture - not by an
  automated screenshot harness (the repo has none).
- The extended-rail branch remains unobservable on this phone (V2-3 F-K).
- Tool-activity visibility (O4), delete confirmation (flow 13) and the
  redundant pre-key `agent/init` stay open; this record does not claim them.
