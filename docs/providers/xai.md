# Provider research: xAI (Catwalk id: `xai`)

**Date of research: 2026-10-17.** Base URL: `https://api.x.ai/v1`.
Written for **Crush Mobile** (Crush 0.97.1 engine, OpenAI-compatible chat completions with streaming + tool calls).

Convention: every claim carries `(source: URL)`. Claims without a source URL are marked **UNVERIFIED**.
Note: docs use "SpaceXAI/SpaceXSI" branding in fetched HTML — same site, irrelevant.

---

## 1. Models TODAY (2026-10-17)

The model line is now **Grok 4.x generation only**. All 2025-era slugs (`grok-3`, `grok-4-0709`,
`grok-4-fast-*`, `grok-4-1-fast-*`, `grok-code-fast-1`, `grok-imagine-image-pro`) were
**retired 2026-05-15 12:00 PT**; requests to them silently redirect to `grok-4.3` (billed at 4.3 rates)
— do **not** keep old ids in a preset list.
(source: https://docs.x.ai/developers/migration/may-15-retirement)

Current text-model table on the models page — all rows verified from the live page. Note: there is
**no "small"/mini model tier anymore**; the cheap tier is `grok-4.3`/`grok-4.20`/`grok-build-0.1`
(same price class, large context). (source: https://docs.x.ai/docs/models, same table at
https://docs.x.ai/developers/pricing)

| Model id | Context | FC | Structured out | Reasoning | Notes |
|---|---|---|---|---|---|
| `grok-4.7` (newest flagship, Sep 17 2026) | 500k | Yes (source: model detail page) | Yes (source: model detail page) | Yes, efforts `low/medium/high(default)/xhigh`, cannot disable; knowledge cutoff May 2026 | flagship; Batch API **not** supported (source: https://docs.x.ai/developers/grok-4-7) |
| `grok-4.6` | 500k | Yes | Yes | `low/medium/high/xhigh`, cannot disable | (source: https://docs.x.ai/developers/pricing; fc/so from model line — fc detail UNVERIFIED) |
| `grok-4.5` | 500k | Yes | Yes | `low/medium/high` (default high), cannot disable | fc+reasoning per model page; fc detail UNVERIFIED |
| `grok-4.3` | 1,000,000 | Yes ("tops the leaderboards in agentic tool calling", source: migration guide) | Yes | efforts `none, low, medium, high` — **`none` makes it the practical "non-reasoning"/cheap pick** (source: https://docs.x.ai/developers/migration/may-15-retirement) | 20% batch discount |
| `grok-4.20-0309-reasoning` / `-non-reasoning` / `-multi-agent-0309` | 1M (3rd parties say 2M, UNVERIFIED) | UNVERIFIED | UNVERIFIED | 4.20+ drops `logprobs` support (silently ignored) | 20% batch discount (source: pricing page) |
| `grok-build-0.1` (coding/agentic, "early access") | 256k | Yes | Yes | Yes (effort levels UNVERIFIED) | aliases: `grok-code-fast-1`, `grok-code-fast` (source: https://docs.x.ai/developers/models/grok-build-0.1) |

Aliases: `<name>` → latest stable; `<name>-latest` → newest; `<name>-<date>` pinned.
(source: https://docs.x.ai/docs/models)

**NEWEST small model for an agent:** there is no dedicated small model. The cheapest agentic pick is
`grok-build-0.1` ($1/$2, 256k ctx, FC+SO+reasoning) or `grok-4.3` ($1.25/$2.50, 1M ctx, effort `none`
for non-reasoning). (sources: https://docs.x.ai/developers/models/grok-build-0.1,
https://docs.x.ai/developers/migration/may-15-retirement)

Image-in (text out) is supported on 4.7/4.6/4.5/4.3-class models: max 20MiB/image, jpg|png only,
no count limit, any text/image order. (source: https://docs.x.ai/docs/models)

## 2. Pricing TODAY

Pricing table location: **https://docs.x.ai/docs/models** (alias `docs.x.ai/developers/pricing` for the
same table + tools/batch/priority add-ons). Last-updated stamps seen: 2026-09-21 / 2026-10-10.
(source: https://docs.x.ai/docs/models, https://docs.x.ai/developers/pricing)

**Cheapest 3 (per 1M tokens, in / cached-in / out; short-context row = prompt < 200k tokens):**

| Model | In | Cached in | Out | Long-context (≥200k prompt) |
|---|---|---|---|---|
| `grok-build-0.1` (256k ctx) | **$1.00** | $0.20 | **$2.00** | $2 / $0.40 / $4 |
| `grok-4.3` / `grok-4.20-0309-*` / `-multi-agent` (1M ctx) | **$1.25** | $0.20 | **$2.50** | $2.5 / $0.40 / $5 |
| `grok-4.5` (500k ctx) | **$2.00** | $0.30 | **$6.00** | $4 / $0.60 / $12 |

(source: https://docs.x.ai/docs/models — full table reproduced there; matches
https://docs.x.ai/developers/pricing)

Long-context rule: once the *prompt* reaches the 200k threshold, **all** tokens of that request are
billed at the higher rate. (source: https://docs.x.ai/docs/models)

**Per-request add-ons (server-side "live-search" tools)** — billed per 1k tool *calls* on top of
tokens (source: https://docs.x.ai/developers/pricing#tools-pricing):

| Tool | Price |
|---|---|
| `web_search` (incl. image search) | **$5 / 1k calls** |
| `x_search` | **$5 / 1k posts + $10 / 1k profiles** (billed per item fetched, incl. parent/quoted posts) |
| `code_execution` / `code_interpreter` | $5 / 1k |
| `attachment_search` | $5 / 1k |
| `collections_search` / `file_search` | $2.50 / 1k |
| `view_image` / `view_x_video` / Remote MCP | token-billed only |

⚠️ Relevant for an agent: these are **OpenAI-style built-in tools** (type `web_search`, etc.), they are
priced per call, and Crush should **not** enable them by default. Old `$25 / 1K sources` "Live Search"
rate still shows on the legacy `docs.x.ai/models/grok-3` page — that page is pre-retirement legacy.

Other pricing facts:
- **Reasoning tokens are billed** as normal generation ("the reasoning tokens are billed as part of
  your total consumption"). (source: https://docs.x.ai/developers/model-capabilities/text/reasoning)
- Batch API: −20% only for the 4.20/4.3 family; `grok-4.7`/`grok-build-0.1` → no batch discount.
  (source: https://docs.x.ai/developers/pricing)
- Priority processing: 2x tokens (Chat Completions + Responses only; `service_tier: "priority"`).
  (source: https://docs.x.ai/developers/pricing)
- US regional endpoint `us.api.x.ai`: 1.1x token prices, only `grok-4.7`/`grok-4.6`.
  (source: https://docs.x.ai/developers/pricing)
- Usage-guideline violation fee: $0.05/request on Responses API. (source: pricing page)
- Prompt caching: cached input billed at ~1/4 (0.25x) of input; recommended to pass
  `prompt_cache_key` (Responses) / `x-grok-conv-id` (Chat Completions) for cache affinity.
  (source: https://docs.x.ai/docs/models table + https://docs.x.ai/developers/grok-4-7)

## 3. Free credits / signup grants TODAY — the key finding

### ❌ The $150/mo data-sharing program is DEAD (stale pages)

- xAI **ended the data-sharing program in May 2025** — reports of removal on 2025-05-13
  (source: https://x.com/techdevnotes/status/1922246002246811857, date 2025-05-13;
  https://www.reddit.com/r/grok/comments/1kliyxe/ — "xAI discontinuing their free API tier ($150 credits) by May 2025").
- The historical program text read: "Get $150 in free API credits each month… your team must have
  already spent a minimum of $5 on the API before choosing to opt in… only team admins can enable,
  cannot opt out" — that text survives **only on unofficial mirrors/trackers**
  (source: https://grok-api.apidog.io/free-credits-934025m0 — mirror of the old official page;
  https://cloudcredits.io/providers/xai — third-party, still lists it as active = stale tracker).
- **The current official docs contain no free-credits page at all.** Full docs sitemap
  (fetched 2026-10-17, https://docs.x.ai/sitemap.xml) contains **no** free-credits / promo page; the
  Billing docs (2026-10-10) describe only prepaid purchase, promo codes, and invoice billing.
  (source: https://docs.x.ai/console/billing, https://docs.x.ai/developers/faq/billing — no mention of
  any signup grant or data-sharing credits)
- The migration-retirement docs confirm the docs were restructured in 2026 and old hard-coded paths
  (docs.x.ai/docs/free-credits) now 404.

**Verdict: the $25 sign-up grant and the $150/mo data-sharing credits are STALE.** Do not bake them
into onboarding assumptions. There were mid-2026 third-party claims that "$25 at signup + $150/mo
after $5 spend" still worked (source: https://aitoolsrecap.com/Blog/how-to-get-free-grok-api-key-2026-step-by-step,
2026-04) but they contradict the current official docs page set and cite only a console screenshot —
**UNVERIFIED, treat as stale.** Only a **promo code** field exists today
(source: https://docs.x.ai/console/billing: "use a promo code if you have one").

### What console onboarding grants today

Per the official Quickstart: create account → **"then load it with credits to start using the API"**
→ create key. No free credits are mentioned anywhere in the current flow.
(source: https://docs.x.ai/developers/quickstart, fetched 2026-10-17)

## 4. Signup friction

- **x.com login NOT required.** Sign-in options: **X, email, Google, Apple, GitHub**
  (source: https://accounts.x.ai/sign-up?redirect=cloud-console, https://console.x.ai/login?mode=sign-up).
  Linking an X account is optional (same account works for Grok app + API; billing separate).
  (source: https://docs.x.ai/console/faq/accounts)
- **Phone verification:** not documented in official docs/pages found → **UNVERIFIED**. Multiple
  signup methods (X/Google/Apple/GitHub/email) suggest no hard phone gate for those methods.
- **Prepaid credits required:** current docs' billing model is 100% prepaid credits by default
  (monthly invoicing is opt-in via sales). API requests are rejected once credits run out ($0
  invoiced limit default). (source: https://docs.x.ai/console/billing)
- **India-specific:** Indian payment cards can't be saved (regulatory) — prepaid purchase must go
  through **Guest Checkout**. (source: https://docs.x.ai/console/faq/billing)
- Auto top-up minimum: **$5** per top-up (that's the cheapest concrete "purchase quantum" the docs
  quantify). (source: https://docs.x.ai/console/billing)
- No refunds on credits except where law requires. (source: https://docs.x.ai/console/faq/billing)
- Region restrictions: "model access might vary depending on geographical location" — xAI reserves
  this right but doesn't publish a country list in pricing docs
  (source: https://docs.x.ai/developers/pricing#billing-and-availability). EU rollout lags US:
  "Grok 4.5 is now available in the API console for EU users" (July 2026 news item) implies
  per-region model gating. (source: https://docs.x.ai/developers/release-notes)
  `grok-build-0.1` clusters: us-east-1, us-west-2 — EU availability unclear (UNVERIFIED).
  (source: https://docs.x.ai/developers/models/grok-build-0.1)

## 5. Rate limits TODAY

Two axes per model: **RPS** and **TPM**; tiers are based on **cumulative spend since Jan 1, 2026**,
auto-upgrade, never downgrade; `429` on exceed; RPS is derived as RPM/48 (min 2).
(source: https://docs.x.ai/developers/rate-limits, fetched 2026-10-17)

| Tier | Spend threshold |
|---|---|
| T0 | $0 |
| T1 | $50 |
| T2 | $250 |
| T3 | $1,000 |
| T4 | $5,000 |

Key T0 caps (all text models relevant to the app are generous):

| Model | RPS @T0 | TPM @T0 |
|---|---|---|
| `grok-4.7` | 150 | 50M |
| `grok-4.6` | 150 | 50M |
| `grok-4.5` | 150 | 50M |
| `grok-4.3` / `grok-4.20-*` / `grok-build-0.1` | 37 | 10M |
| `grok-4.20-multi-agent-0309` | 9 | 2.5M |

(source: https://docs.x.ai/developers/rate-limits)
Cached prompt tokens **still count toward TPM**. (source: rate-limits page, "What counts toward TPM")

Branch verdict for Crush Mobile: rate limits are a **non-issue** for a single-user phone agent at T0
on the mid-tier models (37 RPS / 10M TPM covers the whole device many times over).

## 6. OpenAI compatibility nuances (api.x.ai/v1, Crush 0.97.1 POV)

- **Endpoints:** `/v1/chat/completions` (**legacy, kept** — docs/x.ai note "Chat Completions is
  unchanged"; official openapi at https://docs.x.ai/openapi.json still ships full chat completions
  incl. `stream`, `response_format`, `stop`, `n`, `logprobs`) and `/v1/responses`
  (the "new" agentic surface). SDK examples in current docs default to OpenAI SDK base_url
  `https://api.x.ai/v1`. (source: https://docs.x.ai/openapi.json,
  https://docs.x.ai/developers/rest-api-reference/inference/chat-completions,
  https://docs.x.ai/developers/grok-4-7)
- **Function calling:** `type: "function"` with `name`, `description`, `parameters` (JSON Schema);
  `tool_choice`: `auto|required|none|{type:function,function:{name}}`; **parallel calls enabled by
  default** (`parallel_tool_calls: false` to disable); multiple `tool_calls` per response supported;
  up to **350 tools/request**; parameters root must be `object` (or all-object `anyOf`/`oneOf`) else
  **400**; mixing custom functions with built-in server tools in the same request works.
  (source: https://docs.x.ai/developers/tools/function-calling)
- ⚠️ **Streaming tool calls are NOT granular:** "With streaming, the function call is returned in
  whole in a single chunk, not streamed across chunks." So accumulate from the first `tool_calls`
  delta and don't expect OpenAI-style per-chunk argument growth. (source: same function-calling page)
- **Streaming format:** SSE, `data: {"object":"chat.completion.chunk", ...choices[0].delta...}`;
  reasoning models stream `delta.reasoning_content` before `delta.content`; each chunk carries
  `usage` (prompt/completion/incremental completion tokens incl. `prompt_tokens_details.cached_tokens`).
  (source: https://docs.x.ai/developers/model-capabilities/text/streaming)
- Role order is free (system/user/assistant mixed allowed). `logprobs`/`top_logprobs` silently
  ignored on 4.20+ models. (source: https://docs.x.ai/docs/models)
- `presence_penalty`, `frequency_penalty`, `stop` **error out on reasoning models** (4.7/4.6/4.5).
  (source: https://docs.x.ai/developers/model-capabilities/text/reasoning)
- **Structured outputs:** `response_format: {type:"json_schema", json_schema:{name, schema,
  strict:true}}` (or `json_object`) — supported; combined with tools on "Grok 4 family" models.
  (source: https://docs.x.ai/developers/model-capabilities/text/structured-outputs)
- **Reasoning / billing:** reasoning tokens are **billed** (input-classified on 4.20 family per docs'
  "Agent's internal thinking" bullet; all listed model classes bill them). `reasoning_effort`:
  4.7/4.6 `low|medium|high(default)|xhigh`; 4.5 drops `xhigh` (treated as high); 4.3 also offers
  `none` (non-reasoning). Set `low` on 4.7-class models for agent turns to cut reasoning spend;
  `none` is not available for 4.7-class — always at least "low"
  (source: reasoning page + model pages).
- **Caching recommendation (phone agent):** pass `prompt_cache_key` (Responses) /
  `x-grok-conv-id` header (Chat Completions) per conversation so repeated-turn system prompts hit
  cache (0.25x rate). (source: https://docs.x.ai/developers/grok-4-7)
- Vision: image-in via `image_url` content parts; 20 MiB, jpg/png. (source: docs/models bullets)

## 7. Verdict (2026-10-17)

- **No true $0 path exists on api.x.ai.** Current docs describe prepaid-credit-only activation;
  no documented signup grant, no documented free tier. The cheapest documented purchase quantum is
  the **$5** auto-top-up amount (billing page) → **minimum realistic spend to test = $5**.
- With **$5** on `grok-build-0.1` ($1/$2 per 1M): roughly **~1.6M "blended" tokens** — enough for
  hundreds of agent turns (a typical 1k-in / 500-out tool-calling turn ≈ $0.002 → ~2,500 cheap turns,
  far less with reasoning-bloated tokens, still ample).
- Pick strategy for the app: default model `grok-build-0.1` (cheapest, agentic); fallback
  `grok-4.3` with `reasoning_effort: none|low` for long-context (1M ctx) work; use 4.7/4.6/4.5 for
  heavy reasoning at 2–5x the price. Never offer pre-May-2026 ids.
- Keep credits **prepaid-only** in the app flow (no monthly invoice assumptions), and surface the
  `429`-with-backoff behavior from day one (though T0 limits won't be hit in practice).

### Stale claims that MUST be ignored (for future agents)

| Claim | Status |
|---|---|
| "$25/mo free credits for data sharing (spend $5)" | **Stale** — no official doc today; data-sharing program ended May 2025 |
| "$150/mo free API credits" | **Stale** (ended 2025-05, unofficial mirrors still show old page) |
| grok-3 pricing/context | **Stale** — grok-3 retired 2026-05-15, redirects to grok-4.3 |
| `x_search`/`web_search` "included free" | False — $5/1k calls |
| "Grok 4 Fast" as current model | Old name — retired slug, redirects |

*(research file generated 2026-10-17; re-verify slugs before release — xAI renames models fast:
grok-4-0709 → grok-4-1-fast → grok-4.5 → grok-4.6/4.7 within ~14 months)*
