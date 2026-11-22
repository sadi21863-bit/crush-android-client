# V2-8 - Structured model UI experiment (opened 2026-11-22)

**Status: OPEN - candidate A run set complete (1/3 schema-valid); no adoption is
proposed.** Owner authorized opening V2-8 on 2026-11-22. This record fixes the
experiment's boundaries and exit criteria before any result, so the experiment
cannot quietly become a production renderer.

## 1. What the research settled

From `V2-7-8-web-research-2026-11-22.md` (all sources retrieved 2026-11-22):

- **No production framework is adoptable.** AndroidX A2UI Compose is alpha
  (`1.0.0-alpha02`), implements protocol **0.9.1** while A2UI 1.0 is current,
  and requires `compileSdk 37.1` (app: 37). json-render has no Compose renderer.
  CopilotKit/OpenIntelligentUI are web/iframe; MCP Apps is HTML in a sandboxed
  iframe and explicitly defers native widgets.
- **Crush cannot request structured output per turn.** `POST .../messages`
  carries `{session_id, prompt, run_id, attachments?, channel?,
  hidden_user_message?}` - no `response_format`. Provider `provider_options` /
  `extra_body` are global model configuration, not per-turn schemas. The Crush
  CLI JSON request is still an open issue (#1034).
- **The only channel today is prompt-shaped JSON in an ordinary text part**
  (best-effort, no schema guarantee), or tool calls (blocked by Chat deny-all).
- **Determinism guarantees are narrow.** Constrained decoding guarantees shape
  only on supported endpoints; the host's closed registry guarantees no unknown
  components; nothing guarantees truthful data, correct semantics or safe
  actions. Published failure rates are material (turn vs multi-turn gaps,
  deceptive-design findings), so the fallback path matters as much as the happy
  path.
- **The safest native pattern** is a host-owned, closed, versioned component
  catalogue with strict validation and text fallback. That is V2-7.3.

## 2. Experiment scope

**Debug/internal flag only. Read-only. Not shipping.**

| Candidate | What it is | Gate |
|---|---|---|
| A. Prompt-only `ui_block/v1` | Ask the model to emit a strict JSON block envelope in a text part; validate and render through the V2-7.3 registry. The parser/renderer and a **debug-only paste lab** exist (`UiBlockLabActivity`); a real model run still needs an owner paste | No new endpoint; no actions |
| B. A2UI `1.0.0-alpha02` | Official Compose renderer with a tiny Basic Catalog subset, in an isolated debug module | Requires an explicit owner decision on alpha + `compileSdk 37.1` + protocol 0.9.1 |
| C. json-render-style adapter | A local Compose adapter for a flat `{root, elements}` spec, no JS dependency | Design reference only; no Vercel dependency |

Candidate B is **blocked by default**: the stable-dependency posture and the
compileSdk cost are owner decisions, not experiment conveniences. Candidate C
must not import the TypeScript package; it is a local interpretation.

### Hard boundaries

- No WebView, HTML, JS, network, image loader, `openUrl`, deep links or new
  permissions.
- No actions from generated UI. Every button is either absent or mapped to a
  host-owned, pre-declared command with a confirmation gate; no tool calls, no
  permission grants, no navigation outside the app.
- No change to the Chat deny-all boundary.
- No effect on release builds: flag off, code debug-only or release-inert, same
  pattern as the O11 stall.
- Model output stays untrusted input: schema, size and depth limits; unknown
  block/field -> text fallback; no raw JSON dump beyond the bounded receipt.

## 2.1 Candidate A harness (built 2026-11-22)

`UiBlockLabActivity` is a debug-only screen:

1. shows the experiment prompt (selectable, not auto-sent);
2. accepts pasted model output;
3. strips **at most one** outer Markdown fence (`normalizeUiBlockPayload`);
4. runs the same strict `UiBlockParser`;
5. renders a valid document through `UiBlockView`, or shows the exact rejection
   reason.

The producer step is manual because Crush has no per-turn structured-output
request (research §5). This is a deliberate limit, not a claim of automation.
The lab does not call the engine, touch Chat state, or write any file.

What it can measure now: parse success/fallback, block counts, the rejection
reason, and whether the rendered tree is legible. What it cannot measure without
more work: render time/frame metrics, token cost, TalkBack/font-scale/RTL, and
the three-candidate comparison. Those remain open in §3.

Device evidence: `docs/parity/2026-11-22-v2-8-lab/` (sample parsed on device;
a real model payload is still NOT PERFORMED).

## 3. Measurements to record

On `bbf8ca69` (Redmi Note 7 Pro, API 29), same prompts per candidate:

1. parse/validate success rate and fallback rate;
2. render time and jank for a representative block tree;
3. TalkBack, font-scale (1.0x, 1.3x, 2.0x), dark theme and RTL results;
4. token cost of the envelope vs plain text;
5. failure taxonomy: structural, semantic, data, safety (VGV four layers);
6. what the model actually produced when asked for the envelope (raw samples
   stored only if they contain no workspace data).

Status (2026-11-23): three candidate-A runs were performed with DeepSeek V4.1
Flash on `opencode-go`. **1/3 was schema-valid.** Runs 1 and 2 drifted
(`key`/`value` instead of `k`/`v`; `body`/`variant` instead of `text`/`tone`) and
were rejected whole-document; run 3, with the exact key names appended to the
prompt, parsed and rendered 4 blocks. The model invented a model/provider/token
line in run 1 and used `n/a` in run 3. **Prompt precision helps; it is not a
guarantee.** Item 3 has Robolectric render checks for dark theme, 2.0x font
scale and RTL; TalkBack and all device accessibility rows remain NOT PERFORMED.
Items 2 and 4 remain NOT PERFORMED. Evidence:
`docs/parity/2026-11-22-v2-8-lab/`.

## 4. Exit criteria

Adopt a structured renderer into production only if **all** hold:

- a candidate shows a measurable UX win over plain text on the same content;
- parse success and fallback behaviour are understood, not just the happy path;
- the accessibility/theme/RTL rows pass on device;
- the toolchain cost is accepted explicitly (compileSdk/alpha, if candidate B);
- the security model is host-owned and cannot invoke tools or permissions.

Otherwise V2-8 closes as **"not adopted"**, V2-7's registry and text fallback
remain, and the experiment's evidence is kept.

## 5. Open owner questions

Defaults in brackets are what this record assumes unless the owner says
otherwise:

1. Is any **model-authored** UI allowed, or only host-authored blocks?
   [Default: experiment only; production stays host-authored.]
2. Is a **new per-request structured-output endpoint** in Crush acceptable?
   [Default: no; no new provider API in V2-8.]
3. Is an **alpha** AndroidX dependency acceptable behind a debug flag?
   [Default: no; A2UI stays reference-only unless explicitly approved.]
4. Which surfaces: Chat only, or Code/Projects too? [Default: Chat only.]
5. If generated UI is ever interactive, who authorizes actions and which are
   permanently forbidden? [Default: read-only; no actions.]
6. Accessibility/i18n bar? [Default: TalkBack + 2.0x + dark + RTL before any
   production claim.]
7. Is A2UI **wire compatibility** with external agents required? [Default: no;
   internal block format.]
8. Timebox and kill criteria? [Default: one experiment pass, then a keep/kill
   decision; no telemetry containing workspace data.]
9. Is WebView absolutely excluded? [Default: yes, hard boundary.]
10. Invalid/unsafe output fallback? [Default: plain text + an "unsupported"
    receipt; no retry loop.]

## 6. Evidence

- Codebase: `V2-7-8-codebase-inventory-2026-11-22.md`.
- External: `V2-7-8-web-research-2026-11-22.md` (source table, verdicts,
  contradictions).
- V2-7 implementation: `V2-7-intelligent-ui.md`; the closed contract is
  `ui-block-v1.md` (validator + renderer + debug preview, producer-free).
- Candidate A lab: `UiBlockLabActivity` (debug only, "Copy" prompt button),
  normalization tests `UiBlockLabTest`, device sample in
  `docs/parity/2026-11-22-v2-8-lab/`.
- Robolectric render checks: `UiBlockViewTest` covers dark theme, 2.0x font
  scale and RTL; TalkBack and device rows remain open.
- No model-produced result exists yet; this record must not be read as one.
