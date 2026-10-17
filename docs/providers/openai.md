# Provider research: OpenAI Platform API (Catwalk id: `openai`)

- Researched: **2026-10-17** (all facts checked this date)
- Base: `https://api.openai.com/v1`
- App context: Crush Mobile 0.97.1 / Crush engine calling **OpenAI-compatible Chat Completions** with `stream: true` + tool calls.
- Sources policy: official only (`developers.openai.com`, `platform.openai.com`, `help.openai.com`, `openai.com`). Community links used ONLY as secondary/discovery. `UNVERIFIED` = claim not confirmable on an official page.
- Doc convention that worked well: every docs page has a Markdown version — append `.md` to the URL.

## 1. Models TODAY (small + cheap + tool-calling, usable over `v1/chat/completions`)

Docs have moved off `platform.openai.com/docs` to **`developers.openai.com/api/docs/...`** (old URLs redirect). Model catalog: models page.

Current lineup (2026-10): flagship **GPT‑6 Astra** (`gpt-6-astra`), mid **GPT‑6.1 Sol** (`gpt-6.1-sol`) + **GPT‑6 Sol** (`gpt-6-sol`), efficient **GPT‑6 Luna** (`gpt-6-luna`); the GPT‑5.6 (`sol/terra/luna`), GPT‑5.5/5.4/5.2/5.1/5 family and GPT‑4.1/4o generations are still listed in the pricing table. (source: https://developers.openai.com/api/docs/models , https://developers.openai.com/api/docs/pricing.md )

### The models that matter for a tool-calling phone agent (Chat Completions)

| Model | In $/1M | Cached in | Out $/1M | Context | Max input / output | Tool calling on CC | Notes |
|---|---|---|---|---|---|---|---|
| `gpt-5-nano` | **$0.05** | $0.005 | **$0.40** | 400,000 | 272K / 128K | Supported | Reasoning tokens supported; snapshot `gpt-5-nano-2025-08-07` (source: https://developers.openai.com/api/docs/models/gpt-5-nano.md) |
| `gpt-4.1-nano` | **$0.10** | $0.025 | **$0.40** | 1,047,576 | — / 32,768 | Supported ("excels at instruction following and tool calling") | No reasoning step → no reasoning-token output cost (source: https://developers.openai.com/api/docs/models/gpt-4.1-nano.md) |
| `gpt-6-luna` | **$0.10** | $0.01 (writes $0.125) | **$0.50** | 1,050,000 | 922K / 128K | **CC only with `reasoning_effort: "none"`** | Newest efficient model; >272K input re-prices whole request at 2x in / 1.5x out (source: https://developers.openai.com/api/docs/models/gpt-6-luna.md) |
| `gpt-5-mini` | $0.25 | $0.025 | $2.00 | 400,000 | 272K / 128K | Supported | (source: https://developers.openai.com/api/docs/models/gpt-5-mini.md) |

Constraints that disqualify some ids for our CC path:
- **"Starting with GPT‑5.4, Chat Completions does not support tool calling with `reasoning_effort` values other than `none`."** (source: https://developers.openai.com/api/docs/guides/migrate-to-responses.md)
- GPT‑6 Astra and GPT‑6.1 Sol **require the Responses API for tool calling** (source: https://developers.openai.com/api/docs/guides/function-calling.md)
- Flagship pricing for reference only: `gpt-6-astra` $10/$50, `gpt-6.1-sol` $2/$10, `gpt-5.6-terra` $2/$12, `gpt-5.4-nano` $0.20/$1.25, `gpt-5.6-luna` $0.20/$1.20, `gpt-4o-mini` $0.15/$0.60 (source: https://developers.openai.com/api/docs/pricing.md)

Reasoning flags: `gpt-6-luna` supports `reasoning.effort` `none|low|medium(default)|high|xhigh|max` (source: https://developers.openai.com/api/docs/models/gpt-6-luna.md). All models listed support text+image input, streaming, structured outputs, prompt caching (source: model pages).

## 2. Pricing TODAY — cheapest 3 usable models

Pricing table lives at **`https://developers.openai.com/api/docs/pricing`** (Markdown: append `.md`). Note: `openai.com/api/pricing/` currently serves a Business/Enterprise-plan marketing shell, not the API token table (source: fetched https://openai.com/api/pricing/ this date); there is also a model-card comparison there (?filterTags variants seen in search) — treat `developers.openai.com/api/docs/pricing` as canonical.

| Rank | Model | In /1M | Cached in /1M | Out /1M | Verdict for Crush |
|---|---|---|---|---|---|
| 1 | `gpt-5-nano` | $0.05 | $0.005 | $0.40 | Cheapest overall; tool calling works on CC (source: pricing.md + gpt-5-nano.md) |
| 2 | `gpt-4.1-nano` | $0.10 | $0.025 | $0.40 | Same output price, no reasoning-token spend, 1M ctx (source: gpt-4.1-nano.md) |
| 3 | `gpt-6-luna` | $0.10 | $0.01 | $0.50 | Must force `reasoning_effort:"none"` on CC; >272K input doubles input price (source: gpt-6-luna.md + pricing.md) |

Modifiers (apply to all): **Batch and Flex = 50%** of Standard rates; **Fast mode = 2x** (renamed from Priority on 2026-07-30); **Ultrafast** tier exists (2x of Fast pricing shown for astra/sol only); data-residency endpoints **+10%** for models released on/after 2026-03-05. Cached input = 10% of input; cache writes = 1.25x input (Luna). (source: https://developers.openai.com/api/docs/pricing.md , https://developers.openai.com/api/docs/guides/rate-limits.md)

## 3. Free tier / trial credits

- **The $5 (or $3.50) signup trial grant is dead.** Today's help-center billing articles document only *purchased* credits ("Purchased credits expire after 1 year … non-refundable") (source: https://help.openai.com/en/articles/8264644-setting-up-and-managing-prepaid-api-billing). There is **no current official page** announcing a free API grant; the only official mention is the conditional sentence "If your account has free credits, those are used before purchased credits" (i.e., grants can exist, none are documented as given).
- Historical/secondary confirmation that the program was scrapped entirely: OpenAI community threads state "There are no more free credits granted, and anybody that would have the $5 trial credits that were long-discontinued will also have had those expire" and credits "expired after about three months from account creation and OpenAI has stopped giving free credits altogether." (source: https://community.openai.com/t/you-exceeded-your-current-quota/1081467 , https://community.openai.com/t/how-can-i-check-when-my-openai-account-was-made/781054 — community, secondary)
- The FAQ on the current pricing page mentions "The free version of ChatGPT" — that's ChatGPT consumer, **not** the API. (source: https://openai.com/api/pricing/ fetched shell)
- The quickstart says "Congrats on running a free test API request!" with no documented dollar amount behind it (source: https://developers.openai.com/api/docs/quickstart.md) — UNVERIFIED whether any smoke-test credit exists for a brand-new org today.
- $100 "Codex credits for students" are ChatGPT/Codex-plan credits, **not** API credits (source: https://help.openai.com/en/articles/20001147-codex-credits-for-students-terms-of-service).

**Conclusion: no documented $0 path for the API.**

## 4. Signup friction

- **Phone verification**: required at `platform.openai.com` **only for generating the first API key** (not for later keys); codes only via SMS or WhatsApp (where available); a number can verify **at most 3 accounts, forever**, deleted-account reuse only after 30 days (source: https://help.openai.com/en/articles/8983040 , https://help.openai.com/en/articles/8983031-how-many-times-can-i-use-the-same-phone-number-to-complete-the-phone-verification-associated-with-an-openai-accounts-first-api-key-generation).
- **Org verification** (government-ID + selfie): only when OpenAI *asks* for "certain products, features, models, or programs" — not required for a plain API key + basic models. ID can verifyorganizations … one org per ID within 90 days. (source: https://help.openai.com/en/articles/10910291-api-organization-verification)
- **Payments**: new accounts are on **prepaid billing**. Initial credit purchase: **min $5, default $10**. "Use auto-reload" is **ON by default during setup** — must be unchecked at purchase or set a threshold (source: https://help.openai.com/en/articles/8264644-setting-up-and-managing-prepaid-api-billing).
- Trust tier (usage/payment history) caps the max balance at once (source: https://help.openai.com/en/articles/8264778-what-is-prepaid-billing).

## 5. Rate limits — tiers (old Tier 0/1/2 → Free/Build/Launch/Grow)

Tier names changed; tiers upgrade **automatically** as cumulative credit purchases cross thresholds (source: https://developers.openai.com/api/docs/guides/rate-limits.md):

| Tier | Qualification | Usage limit |
|---|---|---|
| Free | allowed geography only | $100/month |
| **Build** (entry, ~old Tier 1) | **$5 total credit purchases** | $500/month |
| Launch | $100 total purchases | $5,000/month |
| Grow | $500 total purchases | $200,000/month |

Build-tier RPM/TPM (Standard) — relevant to every fresh $5 account (sources: rate-limits.md; model pages gpt-6-luna.md / gpt-5-mini.md / gpt-5-nano.md / gpt-4.1-nano.md):

| Model group | RPM | TPM |
|---|---|---|
| Astra / Sol / Terra | 5,000 | 1,000,000 |
| Luna (gpt-6 + families) / gpt-5-mini / gpt-5-nano / gpt-4.1-nano | 5,000 | 2,000,000 |

- Long-context requests (>128K input, e.g. gpt-4.1-nano) have separate limits: Build **500 RPM / 1M TPM** (source: gpt-4.1-nano.md).
- Limits set at **org + project** level, not user; some families share limits; live values at Settings > Organization > Limits (source: rate-limits.md).
- Error handling: `429 rate_limit_error/slow_down` = burst ramp too fast; `503 …/server_is_overloaded` = model overload; follow `Retry-After` + jitter; SDKs auto-retry eligible 429/503 (source: rate-limits.md). A **429 can also mean exhausted balance** (`credit_balance_exhausted`, `organization_usage_limit_exceeded`, spend limits) — retrying never fixes those (source: https://help.openai.com/en/articles/5955604-troubleshooting-api-rate-limits-and-429-errors).
- UNVERIFIED: whether the "Free" tier can serve any real API traffic before the first $5 purchase (no per-model RPM/TPM published for it; console-only).

## 6. API surface — Chat Completions vs Responses for `openai` driver

- **Chat Completions remains supported; Responses is recommended for all new projects** (source: https://developers.openai.com/api/docs/guides/migrate-to-responses.md). Multiple model pages list `v1/chat/completions` = Supported, with features `streaming` + `function_calling` (source: model pages).
- For an **OpenAI-compatible** client (Crush's driver shape), the workable CC surface today:
  - Tool defs are **externally tagged**: `{"type":"function","function":{"name",…}}`; CC is **non-strict by default** (strict mode available per-function) (source: migrate-to-responses.md, function-calling.md).
  - Streaming: "`stream` to `true` … Streaming function calls works like streaming regular responses" and tool-call **arguments arrive as incremental deltas to aggregate** (guide demonstrates with Responses events; CC uses the classic `delta` streaming shape) (source: https://developers.openai.com/api/docs/guides/function-calling.md). The exact CC wire field (`choices[].delta.tool_calls`) — UNVERIFIED on this date's official pages (not shown in the .md extracts); behaviour presumed unchanged from CC v1 compat.
  - **Reasoning flag interaction (the trap):** on CC, tool calling needs `reasoning_effort: "none"` for GPT‑5.4+ and Luna-family models; older families (gpt‑5, gpt‑4.1, gpt‑4o) keep normal CC tool calling (source: migrate-to-responses.md, gpt-6-luna.md).
  - New accounts: **chat completions are stored by default** → send `store: false` on privacy grounds (source: migrate-to-responses.md).
- **Responses API** (`v1/responses`) is what unlocks built-in tools (web search, file search, computer use, MCP), server-side state (`previous_response_id`, `store:true`), better reasoning behavior (+3% SWE-bench internal, 40–80% better cache hits) (source: migrate-to-responses.md). Crush maps its OpenAI-compatible calls to CC, so: **safe to assume CC for tool calls + streaming; do not pick models that demand Responses.**

## 7. Cost control — how billing actually works today

- **Prepaid credits**: buy credits → balance decreases per usage. Free credits (if present) are consumed *before* purchased ones. Auto-recharge: adds credits when balance < threshold; **min recharge $5**; optional monthly recharge; default literally during first purchase (see §4). If recharge fails → email + usage halts at $0 (source: https://help.openai.com/en/articles/8264644-setting-up-and-managing-prepaid-api-billing).
- **Expiry/limits**: purchased credits **expire after 1 year**, non-refundable (official); balance max capped by trust tier (source: prepaid articles + https://openai.com/policies/service-credit-terms/).
- **Billing cutoff lag**: usage can overshoot $0 before cutoff; overshoot appears as **negative balance deducted from the next purchase** (source: helpcenter prepaid article text mirrored in https://help.openai.com/en/articles/8264778-what-is-prepaid-billing).
- **Caps you can set**: per-org/project **spend alerts** (notify only) and **hard spend limits** → affected requests get `429` (source: https://developers.openai.com/api/docs/guides/rate-limits.md §Spend limits, https://help.openai.com/en/articles/6614457-troubleshooting-api-usage-and-spend-limits).
- **Hidden billables for an agent**: tool/function definitions inside `tools` are billed as **input tokens** (count toward context); reasoning models bill reasoning tokens as **output**; built-in tools are expensive — web search $10/1k calls, file search $2.50/1k calls (source: function-calling.md §Token Usage, https://developers.openai.com/api/docs/pricing.md).
- **Savings levers**: prompt caching (cached input 10% of input price — big win for a fixed big system prompt); `reasoning_effort: none` where acceptable; Batch (−50%, non-realtime, not usable for chat); **avoid Fast/Ultrafast modes** (2x/ +). (source: pricing.md + prompt caching guide link therein)

### The billable "budget-to-five-dollars" story for a phone-agent tester (estimates)

Charges are computed, not official quotes (model prices above are official):
- Assumed per agent turn: 4K input (system + runner prompt + tool defs + history) + 0.8K output (chat or tool-call args), no cache hit.
  - `gpt-5-nano`: 4K×$0.05/1M + 0.8K×$0.40/1M ≈ **$0.00052/turn**
  - `gpt-4.1-nano`: 4K×$0.10 + 0.8K×$0.40 ≈ **$0.00072/turn**
  - `gpt-5-mini`: 4K×$0.25 + 0.8K×$2.00 ≈ **$0.0026/turn**
  - `gpt-6-luna` (reasoning none): 4K×$0.10 + 0.8K×$0.50 ≈ **$0.0008/turn**
- So **$5 ≈ 6,000–10,000 turns on the nanos**, ~2,000 on mini. A multi-tool task (≈4 turns) lands ≈ **$0.002–$0.003/task** → nano budget scales to >1,500 realistic test tasks. UNVERIFIED (my estimate): real phone-agent traffic if the engine auto-summarises/compacts — reasoning tokens and long outputs can multiply output cost 5–10x on nano (128K max output = the true worst-case damage vector). **Caveat with min latency: reasoning output is billed at output rates; cap `max_tokens` and keep `reasoning_effort` low/none.**

## 8. DATED VERDICT (2026-10-17)

- **$0 path: NO.** No trial grant documented anywhere official today; API requests without purchased credits return `credit_balance_exhausted` (source: help 5955604 + 8264644). Any smoke-test credit from quickstart is UNVERIFIED.
- **Minimum realistic test budget: $5** (the minimum prepaid purchase; auto-recharge ON by default so turn it OFF or set threshold + monthly cap at purchase time). It buys ~millions of nano tokens — far more than needed for app testing.
- **Practical $5 recipe for Crush Mobile:**
  - Model default: `gpt-5-nano` (cheapest, tools OK on CC) with `gpt-4.1-nano` fallback (no reasoning tokens, 1M ctx).
  - Avoid `gpt-6-luna` on the CC driver unless the driver sets `reasoning_effort:"none"`; avoid `gpt-5.4+`/`gpt-6-sol/astra` on CC+tools entirely.
  - Request-friendly headers already flow: `x-ratelimit-*` + `Retry-After`; SDK retry policy should not double-send `credit_balance_exhausted`.
  - Settings: hard spend limit at org+project; `store:false` on CC; enable prompt caching-friendly stable system prompt; cap `max_tokens`.
- Risk flags for release: phone verification consumes 1 of a number's **3 lifetime uses**; org ID verification model pegged to SKU/features; negative-balance overshoot beyond $5 is possible but small for plain chat.

## Source index (official)

- Pricing table: https://developers.openai.com/api/docs/pricing.md
- Models catalog: https://developers.openai.com/api/docs/models ; model pages `…/models/{gpt-6-luna,gpt-5-nano,gpt-4.1-nano,gpt-5-mini}.md`
- Function calling: https://developers.openai.com/api/docs/guides/function-calling.md
- Responses migration (CC status, 5.4 restriction, storage default): https://developers.openai.com/api/docs/guides/migrate-to-responses.md
- Rate limits (tiers table, Build RPM/TPM, error codes, spend limits): https://developers.openai.com/api/docs/guides/rate-limits.md
- 429/balance troubleshooting: https://help.openai.com/en/articles/5955604-troubleshooting-api-rate-limits-and-429-errors
- Prepaid billing + auto-recharge + $5 min + 1yr expiry: https://help.openai.com/en/articles/8264644-setting-up-and-managing-prepaid-api-billing , https://help.openai.com/en/articles/8264778-what-is-prepaid-billing , https://openai.com/policies/service-credit-terms/
- Limits troubleshooting (tier names Build/Launch/Grow): https://help.openai.com/en/articles/6614457-troubleshooting-api-usage-and-spend-limits
- Phone verification: https://help.openai.com/en/articles/8983040 , https://help.openai.com/en/articles/8983031-…first-api-key-generation
- Org verification: https://help.openai.com/en/articles/10910291-api-organization-verification
- Quickstart ("free test API request"): https://developers.openai.com/api/docs/quickstart.md
- Business pricing shell (not API tokens): https://openai.com/api/pricing/
- Secondary (community, for discontinued-grants history only): https://community.openai.com/t/you-exceeded-your-current-quota/1081467 , https://community.openai.com/t/how-can-i-check-when-my-openai-account-was-made/781054 , https://community.openai.com/t/message-no-credit-grants-found-in-the-new-api-openai-accounts/712611
