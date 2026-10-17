# Groq — provider deep research (catwalk id: `groq`)

**Retrieved:** 2026-10-17 · **Method:** webfetch of official pages only
(console.groq.com/docs/*, console.groq.com model pages, console.groq.com/keys,
groq.com, community.groq.com, github.com/groq) + websearch to locate official pages.
Secondary blogs used only to locate official URLs; their claims are NOT used except
where explicitly marked.

**App context:** phone-agent embedding Crush 0.97.1, OpenAI-compatible chat
completions with streaming + tool calls, base `https://api.groq.com/openai/v1`.

---

## 1. What Groq is

- Groq is an independent AI inference cloud built on its own **LPU** ("Language
  Processing Unit") hardware; it markets itself as "the premier neocloud for fast
  inference" and recently introduced **LPX**, which "works alongside NVIDIA's
  next-generation GPUs"; it closed a $350M Series A. (source: https://groq.com)
- The Groq API is **OpenAI-compatible**: "We designed Groq API to be mostly
  compatible with OpenAI's client libraries"; you keep your OpenAI client and swap
  `base_url` to `https://api.groq.com/openai/v1` + Groq API key.
  (source: https://console.groq.com/docs/openai)
- Endpoint surface at `https://api.groq.com/openai/v1` (source: https://console.groq.com/docs/api-reference):
  - `POST /chat/completions` (OpenAI-compatible chat completions)
  - `POST /responses` (OpenAI Responses API, beta)
  - `POST /audio/transcriptions`, `POST /audio/translations`, `POST /audio/speech`
  - `GET /models`, `GET /models/{id}`
  - `POST /batches` + retrieve/list/cancel (batch API); `/files` upload/list/delete/retrieve/download; legacy `/fine-tuning/*`
- Auth: `Authorization: Bearer $GROQ_API_KEY` per request.
  (source: https://console.groq.com/docs/api-reference)
- API keys are `gsk_…` — the official Security Onboarding page shows
  `export GROQ_API_KEY="gsk_your_secret_key_here"`.
  (source: https://console.groq.com/docs/production-readiness/security-onboarding)
- Also `GET /openai/v1/models` and other OpenAI paths; OpenAI SDKs work by changing
  only `base_url` + `api_key`. (source: https://console.groq.com/docs/openai)

## 2. Models TODAY (as retrieved 2026-10-17)

### 2a. Catalogue state — the 2026 cull

Groq retired almost everything non-gpt-oss for free/developer tiers in 2026
(source: https://console.groq.com/docs/deprecations — all dates below from that page):

| Shutdown | Model | Replacement |
|---|---|---|
| 2026-09-21 | `groq/compound`, `groq/compound-mini` (agentic systems) | — (decommissioned) |
| 2026-09-14 | `qwen/qwen3.6-27b` | `qwen/qwen3.8-27b` |
| 2026-08-16 | **`llama-3.1-8b-instant`, `llama-3.3-70b-versatile`** (announced 2026-06-17) | `openai/gpt-oss-20b` (8B) / `openai/gpt-oss-120b` or `qwen/qwen3.8-27b` (70B). "This deprecation applies to free and developer-tier usage; enterprise customers with a committed-spend contract are not affected." |
| 2026-07-17 | `qwen/qwen3-32b`, `meta-llama/llama-4-scout-17b-16e-instruct` | `openai/gpt-oss-120b` |
| 2026-04-15 | `moonshotai/kimi-k2-instruct-0905` | `openai/gpt-oss-120b` |
| 2026-03-09 | `meta-llama/llama-4-maverick-17b-128e-instruct` | `openai/gpt-oss-120b` |

Consequence TODAY: on the live [models page](https://console.groq.com/docs/models),
`llama-3.1-8b-instant`, `llama-3.3-70b-versatile`, `minimaxai/minimax-m2.7`,
`meta-models/muse-spark-open` are tagged **Enterprise / "Contact Sales"** — i.e.
paths and model-card pages still exist but they are not standard free/pay-as-you-go
models anymore. Warning: stale docs curl examples still say
`"model": "llama-3.3-70b-versatile"` (e.g. api-reference) — do not copy them.

### 2b. FREE-plan-capable models (from the free-tier rate-limit table)

The free-tier rate-limit table includes exactly these models
(source: https://console.groq.com/docs/rate-limits):

| Model id | Type | Speed |
|---|---|---|
| `openai/gpt-oss-120b` | chat, tools, reasoning, browser-search/code-exec built-ins | ~500 t/s (source: https://console.groq.com/docs/model/openai/gpt-oss-120b) |
| `openai/gpt-oss-20b` | chat, tools, reasoning, built-ins | ~1000 t/s (source: https://console.groq.com/docs/models) |
| `qwen/qwen3.8-27b` | chat, tools (parallel ✅), vision, reasoning | ~450 t/s (source: https://console.groq.com/docs/model/qwen/qwen3.8-27b) |
| `openai/gpt-oss-safeguard-20b` | moderation classifier | ~1000 t/s (source: https://console.groq.com/docs/models) |
| `whisper-large-v3`, `whisper-large-v3-turbo` | speech-to-text | — (source: https://console.groq.com/docs/models) |
| `canopylabs/orpheus-v1-english`, `canopylabs/orpheus-arabic-saudi` | text-to-speech (preview) | — (source: https://console.groq.com/docs/models) |
| `meta-llama/llama-prompt-guard-2-22m/86m` | prompt-attack classifiers (512-tok ctx) | — (source: https://console.groq.com/docs/models) |

### 2c. Strongest tool-calling model (per the official tools matrix)

The [tool-use overview](https://console.groq.com/docs/tool-use/overview) states
"All models hosted on Groq support tool use" and lists per-model support:

| Model | Local & remote tool use | Parallel tool use | JSON mode | Built-in tools |
|---|---|---|---|---|
| `openai/gpt-oss-20b` | ✅ | ❌ | ✅ | ✅ |
| `openai/gpt-oss-120b` | ✅ | ❌ | ✅ | ✅ |
| `openai/gpt-oss-safeguard-20b` | ✅ | ❌ | ✅ | ❌ |
| `qwen/qwen3.8-27b` | ✅ | ✅ | ✅ | ❌ |
| `minimaxai/minimax-m2.7` (Enterprise) | ✅ | ✅ | ✅ | ❌ |
| `meta-models/muse-spark-open` (Enterprise) | ✅ | ✅ | ✅ | ❌ |
| `llama-3.3-70b-versatile` (Enterprise-only) | ✅ | ✅ | ✅ | ❌ |
| `llama-3.1-8b-instant` (Enterprise-only) | ✅ | ✅ | ✅ | ❌ |

- **Best free-tier tool-caller: `qwen/qwen3.8-27b`** — the only non-Enterprise
  chat model with **parallel tool use ✅**, plus JSON schema mode, 131K context,
  16,384 max completion tokens, vision input, and strong agentic benchmarks
  (SWE-bench Pro 61.7%, Terminal-Bench 2.1 73.0%).
  (source: https://console.groq.com/docs/model/qwen/qwen3.8-27b)
- Note the removal: the Llama models (historically the recommended function-callers,
  e.g. `llama-3.3-70b-versatile`) are **still listed in the tools matrix but are
  Enterprise-only since 2026-08-16**; on free/dev keys your replacements are
  gpt-oss/qwen. (source: https://console.groq.com/docs/deprecations)
- gpt-oss models additionally support Groq **built-in tools** (browser search,
  code execution) server-side. (source: https://console.groq.com/docs/tool-use/overview)

### 2d. Small model for a weak app

- **`openai/gpt-oss-20b`** — ~1000 t/s, $0.075/$0.30 per 1M in/out, 131,072 context,
  65,536 max completion tokens, tool use + JSON + reasoning. The models page
  presents it as the direct successor to the old 8B Llama "Instant".
  (source: https://console.groq.com/docs/models, https://console.groq.com/docs/deprecations)

## 3. Rate limits TODAY (free vs Developer)

(source: all values this subsection: https://console.groq.com/docs/rate-limits unless noted)

Dimensions: **RPM** (req/min), **RPD** (req/day), **TPM** (tokens/min), **TPD**
(tokens/day), plus ASH/ASD (audio sec/hr, day) and optional ITPM/OTPM (separate
input/output per-minute caps; hover your TPM on /settings/limits to see a
"X in / Y out" split). **Cached tokens do not count toward rate limits.**
Limits apply **at the organization level, not per user**; you hit whichever
threshold exhausts first.

**Free Plan Limits (published table):**

| Model id | RPM | RPD | TPM | TPD |
|---|---|---|---|---|
| `openai/gpt-oss-120b` | 30 | 1K | 8K | 200K |
| `openai/gpt-oss-20b` | 30 | 1K | 8K | 200K |
| `qwen/qwen3.8-27b` | 30 | 1K | 8K | 200K |
| `openai/gpt-oss-safeguard-20b` | 3 | 1K | 2K | 200K |
| `whisper-large-v3` / `-turbo` | 20 | 2K | — | — (7.2K ASH / 28.8K ASD) |
| `canopylabs/orpheus-*` | 10 | 100 | 1.2K | 3.6K |
| `meta-llama/llama-prompt-guard-2-*` | 30 | 14.4K | 15K | 500K |

**Developer Plan base limits** (models page column; the rate-limits page notes
"limits shown below are the base limits for the Developer plan, and higher limits
are available for select workloads and enterprise use cases")
(source: https://console.groq.com/docs/models):

| Model id | RPM | TPM |
|---|---|---|
| `openai/gpt-oss-120b` / `openai/gpt-oss-20b` | 1K | 250K |
| `qwen/qwen3.8-27b` | 1K | 250K |
| `openai/gpt-oss-safeguard-20b` | 1K | 150K |
| `whisper-large-v3` | 300 RPM | 200K ASH |
| `whisper-large-v3-turbo` | 400 RPM | 400K ASH |
| `canopylabs/orpheus-*` | 250 RPM | 50K TPM |
| `llama-prompt-guard-2-*` | 100 RPM | 30K TPM |

(Dev-plan RPD/TPD values are not published on the pages fetched → beyond the
columns above: UNVERIFIED.)

**24h-window semantics:** The rate-limits page defines RPD/TPD but never says
"rolling 24h" vs "UTC midnight"; the response headers
`x-ratelimit-reset-requests` / `x-ratelimit-reset-tokens` are returned as
**relative countdowns** ("2m59.56s"), which implies a rolling window you should
read at runtime. The rate-limits page says: view your current, exact limits at
`/settings/limits`; "there may be exceptions to these limits".
(source: https://console.groq.com/docs/rate-limits) — exact window boundary
(rolling-24h vs UTC-day) is **UNVERIFIED** in official docs; treat the
`x-ratelimit-reset-*` headers as ground truth.

**Headers always sent on responses** (source: https://console.groq.com/docs/rate-limits):
`retry-after` (seconds, only on 429), `x-ratelimit-limit-requests` (= **RPD**),
`x-ratelimit-limit-tokens` (= **TPM**), `x-ratelimit-remaining-requests`,
`x-ratelimit-remaining-tokens`, `x-ratelimit-reset-requests`, `x-ratelimit-reset-tokens`.

**On exceed:** 429 with `error.message/type=json body
{"error":{"message":…,"type":…}}` (source: https://console.groq.com/docs/errors).
Other codes of note: 413 body too large, 422 semantic failure, 498 custom
"flex tier capacity exceeded", 499 caller-cancelled (logs only), 5xx not billed.
(source: https://console.groq.com/docs/errors)

## 4. Pricing

- There is **no public pricing page** anymore: https://groq.com/pricing serves the
  homepage; per-model prices are published on the docs models pages.
  (source: https://groq.com/pricing)
- **Free tier is $0** and needs no payment method — "You'll get immediate access to
  the free tier … without requiring a credit card."
  (source: https://community.groq.com/help-center-14/how-do-i-create-a-free-groq-account-134)
- The free tier ends at the published free limits above; going past them means
  upgrading to the **Developer plan** (pay-as-you-go), which requires a payment
  method: credit card (Visa/MasterCard/AmEx/Discover), US bank account, or SEPA
  debit. Billing is monthly in arrears with **progressive thresholds** ($1, $10,
  $100, $500, $1,000 charged immediately when first crossed; India: $1/$10 then
  recurring $100); invoices <$0.50 are not charged; you can downgrade back to Free
  anytime after paying the final invoice. Developer unlocks higher limits, Flex,
  Batch, spend limits, chat support.
  (source: https://console.groq.com/docs/billing-faqs)
- **$/1M tokens as of retrieval** (source: https://console.groq.com/docs/models
  and the per-model pages below):
  - `openai/gpt-oss-120b`: **$0.15 input / $0.60 output**, cached input $0.075
    (prompt caching, 50% off cached input; source: https://console.groq.com/docs/model/openai/gpt-oss-120b)
  - `openai/gpt-oss-20b`: **$0.075 / $0.30**, cached input $0.0375-class ($0.037/M cached per 2025 changelog; price unchanged since Sep 2025).
    (source: https://console.groq.com/docs/models, https://console.groq.com/docs/changelog)
  - `qwen/qwen3.8-27b`: **$0.80 input / $4.00 output** (preview).
    (source: https://console.groq.com/docs/model/qwen/qwen3.8-27b)
  - `whisper-large-v3`: $0.111 per audio hour; `whisper-large-v3-turbo`: $0.04/hour.
    (source: https://console.groq.com/docs/models)
  - Orpheus TTS: $22.00 (English) / $40.00 (Arabic) per 1M characters.
    (source: https://console.groq.com/docs/models)
  - prompt-guard classifiers: $0.03–0.04 / 1M.
    (source: https://console.groq.com/docs/models)
- **Llama / other chat ids:** no public price ("Contact Sales", Enterprise).
  (source: https://console.groq.com/docs/models)
- **2026 price/catalogue changes:** the deprecation cull (§2a) removed the
  previously-published pay-as-you-go per-model catalogue (kimi-k2 $1.00/$3.00,
  qwen3-32b $0.29/$0.59 from 2025 changelog entries are gone) with no replacement
  public list beyond gpt-oss/qwen3.8/whisper/orpheus. No official 2026 announcement
  of a gpt-oss or whisper price change; prices captured today match their 2025
  launch prices. (source: https://console.groq.com/docs/changelog,
  https://console.groq.com/docs/deprecations)

## 5. Signup & keys

- **Instant / email verified only:** sign up at https://console.groq.com with an
  email address or GitHub/Google account; email-verification flow for email
  signups; **immediate free-tier access "without requiring a credit card."**
  (source: https://community.groq.com/help-center-14/how-do-i-create-a-free-groq-account-134)
- **Phone verification:** no official page states whether a phone number is
  requested during signup → **UNVERIFIED** (neither confirmed nor ruled out).
- **Card:** never needed for Free; required only to upgrade to Developer
  (Visa/MC/AmEx/Discover, US bank, SEPA). (source: https://console.groq.com/docs/billing-faqs)
- **Org / project structure:** accounts live in an **organization**; within it you
  create **projects** (e.g. `myapp-dev`/`myapp-staging`/`myapp-prod`). Keys are
  **project-scoped**: "Any API keys generated will be specific to the project you
  have selected. Any logs will also be project-specific." Roles: Owner /
  Developer ("Currently same as Owner") / Reader; "Only team owners or users with
  the developer role may create or manage API keys."
  (source: https://console.groq.com/docs/projects, https://console.groq.com/keys/)
- Rate limits are enforced per **organization** with per-project customization
  (projects can only be *more* restrictive; org limits are the ceiling).
  (source: https://console.groq.com/docs/projects)
- **Multi-org/“svc keys”:** switching orgs gives separate org identity + keys;
  a formal "service account key" product is not documented on fetched pages →
  **UNVERIFIED**. Practical takeaway for an app: create one project, one key per
  project, one key per org — keys are not shared across orgs.
  (source: https://console.groq.com/docs/projects)
- Key handling rules: never hardcode/never ship in client bundles, rotate
  quarterly, revoke immediately on leak; prefix `gsk_`.
  (source: https://console.groq.com/docs/production-readiness/security-onboarding)

## 6. Streaming, tool calls, and gotchas

### Streaming (SSE)

- `stream: true` → "partial message deltas … sent as data-only server-sent events …
  with the stream terminated by a `data: [DONE]` message."
  (source: https://console.groq.com/docs/api-reference#chat-create)
- `stream_options` object exists (include usage in-stream) — only valid with
  `stream: true`. (source: https://console.groq.com/docs/api-reference)
- Non-streaming responses carry Groq-specific usage extras (`queue_time`,
  `prompt_time`, `completion_time`, `total_time`) and an `x_groq` metadata object
  with request `id` like `req_01j…`. (source: https://console.groq.com/docs/api-reference)
- Reasoning models stream reasoning separately (below) — a bare `delta.content`
  reader must not render `reasoning` as chat text.

### Tool calls (local function calling)

- Flow: `tools` array (JSON-schema defs, max **128 functions**) → response may carry
  `message.tool_calls[]` with `id`, `function.name`, `function.arguments` (JSON
  string) and `finish_reason: "tool_calls"`; you execute locally and append a
  `role: "tool"` message with matching `tool_call_id`. Loop until no more
  `tool_calls`. (source: https://console.groq.com/docs/tool-use/overview,
  https://console.groq.com/docs/tool-use/local-tool-calling)
- `tool_choice`: `auto` (default when tools present) / `none` / `required` /
  `{"type":"function","function":{"name":…}}`. Documented edge cases:
  `required` can 400 if the model declines to call a tool; with some models
  `none` can still attempt calls → 400 when blocked; steer in-prompt and/or
  lower temperature and retry. (source: https://console.groq.com/docs/tool-use/local-tool-calling)
- `parallel_tool_calls` request param (defaults true) gates parallel calling —
  but only the models marked ✅ in §2c actually produce parallel calls.
  (source: https://console.groq.com/docs/api-reference,
  https://console.groq.com/docs/tool-use/overview)
- Streaming tool calls: aggregate `delta.tool_calls` chunks (standard OpenAI
  index-based delta accumulation) then dispatch on `finish_reason == "tool_calls"`.
  (source: https://console.groq.com/docs/tool-use/local-tool-calling)
- `disable_tool_validation: true` makes Groq return called tools without checking
  they exist in `request.tools` (tool_choice enforcement still applies).
  (source: https://console.groq.com/docs/api-reference)
- Docs trick: for the final post-tool summarization call, pass
  `tool_choice="none"` so the model cannot emit another `tool_calls` round.
  (source: https://console.groq.com/docs/tool-use/local-tool-calling)
- Temperature guidance: keep 0.0–0.5 for best tool-calling results
  (groq examples use 0.5 and note "Keep temperature low for deterministic tool calling").
  (source: https://console.groq.com/docs/tool-use/local-tool-calling)

### Gotchas that bite an OpenAI-compatible client

1. **`temperature: 0` is converted to `1e-8`.** If that misbehaves, "try setting
   the value to a float32 > 0 and <= 2". (source: https://console.groq.com/docs/openai)
2. **Unsupported OpenAI fields → 400:** `logprobs`, `logit_bias`, `top_logprobs`,
   `messages[].name`, and `n` must equal 1. (source: https://console.groq.com/docs/openai)
3. **`max_tokens` is deprecated in favor of `max_completion_tokens`** (both accepted;
   total limited by model context). (source: https://console.groq.com/docs/api-reference)
4. **Reasoning models change the decode surface:**
   - gpt-oss: reasoning arrives in a `message.reasoning` field by default;
     control with `include_reasoning` (true/false); `reasoning_format` is **NOT
     supported** for gpt-oss; `reasoning_effort` low/medium/high (default medium).
     (source: https://console.groq.com/docs/reasoning)
   - qwen3.8-27b: `reasoning_effort` none/default/low/medium/high; **default is
     `none`** ("default" also emits no reasoning on Groq); `reasoning_format`
     parsed/raw/hidden — and **`raw` is forbidden with JSON mode or tool use
     (400)**; default falls to parsed under tools/JSON. Multi-turn: push only
     final outputs (drop think content) into history. (source:
     https://console.groq.com/docs/reasoning, https://console.groq.com/docs/model/qwen/qwen3.8-27b)
5. **JSON-schema structured outputs** supported per-model (json_schema > json_object);
   qwen3.8 & gpt-oss both support it. (source: https://console.groq.com/docs/api-reference)
6. **Prompt caching is automatic** for gpt-oss models: 50% cost cut on cached
   input and **cached tokens don't count against rate limits** — keep the system
   prompt a stable prefix. (source: https://console.groq.com/docs/rate-limits,
   https://console.groq.com/docs/changelog)
7. `service_tier` request param: `auto | on_demand | flex | performance`
   (default `on_demand`); `auto` = "highest tier available within the rate limits
   of your organization"; flex = "succeed or fail quickly" (498 when at capacity);
   strict OpenAI deployments report Groq tiers as `default`.
   (source: https://console.groq.com/docs/api-reference)
8. Assistant prefilling is supported (guide at /docs/prefilling).
   (source: https://console.groq.com/docs)
9. Compound-era request fields (`compound_custom`, `search_settings`,
   `citation_options`, `documents`) still appear in the API reference
   but their flagship consumer `groq/compound` was decommissioned 2026-09-21 —
   do not design around them for chat. (source: https://console.groq.com/docs/api-reference,
   https://console.groq.com/docs/deprecations)

## 7. SDK support (OpenAI SDK & official SDKs)

- **OpenAI SDK:** official configuration is `base_url="https://api.groq.com/openai/v1"`,
  `api_key=$GROQ_API_KEY`; docs show this exact snippet for Python (and JS).
  (source: https://console.groq.com/docs/openai)
- No provider-specific request headers are required for compatibility; the only
  Groq-specific surface is response-side (`x-ratelimit-*`, `x_groq` metadata).
  Optional extra integrations documented: Vercel AI SDK (`@ai-sdk/groq`), LiteLLM,
  LangChain, Cline/Roo/Kilo/Factory/OpenCode integration pages.
  (source: https://console.groq.com/docs/quickstart, https://console.groq.com/docs)
- **Groq SDKs** (source: https://console.groq.com/docs/libraries,
  https://console.groq.com/docs/changelog):
  - Python `groq` — **v1.2.0** (v1.0.0 GA Dec 2025; binary request streaming in
    v1.1.0; Python 3.9 deprecated → 3.10+ effectively)
  - TypeScript `groq-sdk` — **v1.1.2** (streaming fixed in v1.1.1)
- Community libraries (C#, Dart/Flutter, PHP, Ruby) exist but "use at your own risk".
  (source: https://console.groq.com/docs/libraries)

## 8. Listing models dynamically (app picker mirror)

```
GET https://api.groq.com/openai/v1/models
Authorization: Bearer gsk_…
```
(source: https://console.groq.com/docs/api-reference#models-list)

Response: `{"object":"list","data":[{…}]}` where each entry carries
`id`, `object` ("model"), `created`, `owned_by`, `active`, `context_window`,
`public_apps`; `GET /models/{id}` adds `max_completion_tokens`.
(source: https://console.groq.com/docs/api-reference#models-list,
#models-retrieve)

Picker guidance for the app:
- The endpoint returns **everything** (transcribers with `context_window: 448`,
  TTS models, safety classifiers), so filter to chat-capable ids by allowlist
  rather than by endpoint type — Groq's `/models` does not tag modality/support
  today. Known chat ids on a free key: `openai/gpt-oss-120b`, `openai/gpt-oss-20b`,
  `qwen/qwen3.8-27b` (chat+tools), plus `openai/gpt-oss-safeguard-20b` (moderation).
  (source: https://console.groq.com/docs/models, https://console.groq.com/docs/rate-limits)
- `active: true` plus the deprecation page is your freshness check: the free/dev
  model catalogue churns (see §2a), so re-derive the allowlist per release and
  treat `429` on a model that "should" exist as a signal to re-check limits.
  (source: https://console.groq.com/docs/deprecations)

## 9. Verdict (dated 2026-10-17)

**Can a $0 tester run uninterrupted app-keyed chats today: YES — but it is a
metered trickle, not a dev sandbox.** The free tier requires no card
(https://community.groq.com/help-center-14/how-do-i-create-a-free-groq-account-134)
and gives real inference on the current library, with hard caps per model of
30 RPM / 1,000 req/day / 8K tokens/min / 200K tokens/day for both `openai/gpt-oss-120b`,
`openai/gpt-oss-20b` and `qwen/qwen3.8-27b`
(https://console.groq.com/docs/rate-limits). Practical envelope: ~41 requests/hour
(1,000/day), 8K tokens/min means a large agentic turn (big system prompt + file
context) can consume the whole minute's token budget in one request — keep
turns lean and the system prompt a stable prefix so prompt caching kicks in
(cached tokens bypass the limit; https://console.groq.com/docs/rate-limits,
https://console.groq.com/docs/changelog). Handle 429 via `retry-after` +
`x-ratelimit-reset-requests`; never back off blindly into a TPD/RPD window
(seconds won't help), swap model or wait hours.

**Top model id for an agent that often triggers tool calls:**
`qwen/qwen3.8-27b` — the only free-tier chat model with **parallel tool use ✅**,
JSON schema out, 131K ctx / 16K out, top agentic benchmarks in its class, ~450 t/s
(https://console.groq.com/docs/tool-use/overview,
https://console.groq.com/docs/model/qwen/qwen3.8-27b). Caveat: it defaults to
`reasoning_effort=none` (cheap and fast; tune `low/medium/high` when needed)
and it's **Preview** — per Groq's own policy preview models "may be discontinued
at short notice" (https://console.groq.com/docs/deprecations), so keep
`openai/gpt-oss-120b` as fallback (tools ✅ but parallel ❌, 65K max out,
cheaper ~$0.15/$0.60 paid). The old Llama tool-calling workhorses
(`llama-3.3-70b-versatile`, `llama-3.1-8b-instant`) are **dead for free/dev keys
since 2026-08-16** (source: https://console.groq.com/docs/deprecations).

### Field checklist for Crush 0.97.1 integration

- Base: `https://api.groq.com/openai/v1`, Bearer `gsk_…` key.
- Use `max_completion_tokens` (not `max_tokens`); never send `logit_bias`/`logprobs`;
  `n` must be 1; avoid `temperature: 0` (auto-bumped to 1e-8).
- SSE: read `data:` lines, terminal `data: [DONE]`; aggregate tool-call deltas by
  index; treat `delta.reasoning` as non-displayable (or display in a collapse).
- gpt-oss caps: no `reasoning_format`; use `include_reasoning:false` to silence.
- Parallel tools: only qwen3.8 among free models; respect its
  `reasoning_effort` default `none`.
- Mirror `GET /openai/v1/models` for the picker, but allowlist chat models client-side.
- Free-plan ceilings for ergonomics: 8K TPM per model → one long agentic turn per
  minute-bucket; 1K RPD → ~41 req/hr steady-state.
