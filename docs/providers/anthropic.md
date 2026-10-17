# Provider research: Anthropic API (Catwalk id: `anthropic`)

**Date of research: 2026-10-17.** Written for **Crush Mobile** (Crush 0.97.1 engine,
OpenAI-compatible chat completions with streaming + tool calls).

Convention: every claim carries `(source: URL)`. Claims without a source URL are marked **UNVERIFIED**.

Note: `docs.anthropic.com` and `support.anthropic.com` now redirect to
**`platform.claude.com/docs`** and **`support.claude.com`**; the login/keys console is
`platform.claude.com` (was `console.anthropic.com`). Same org, rebranded docs. All URLs below are
recorded as the final (redirected) official pages.

---

## 1. Models TODAY (2026-10-17)

Current line is the **Claude 5.x generation** (2026: Fable 5.1, Opus 5.5, Sonnet 5.5, Haiku 5.5;
2025's Claude 4.x models are legacy but mostly still serveable). All four current models support
text+image input, text output, vision, and **tool use**.
(source: https://platform.claude.com/docs/en/models/overview)

| Model id | Context | Max out | Thinking | Notes |
|---|---|---|---|---|
| `claude-fable-5-1` | 1M | 128K | Adaptive, **always on** | demanding reasoning / long-horizon agentic; $10/$50 MTok |
| `claude-opus-5-5` | 1M | 128K | Adaptive, **always on** | "long-running agentic coding"; $4/$20 MTok |
| `claude-sonnet-5-5` | 1M | 128K | Adaptive (default effort `high`) | $2/$10 MTok |
| `claude-haiku-5-5` | 1M | 128K | Adaptive (default effort `medium`) | cheapest; tiered pricing by prompt length (see §2) |

(source: https://platform.claude.com/docs/en/models/overview — comparison table, retrieved 2026-10-17)

- **Naming:** every Claude model ID is a **pinned snapshot**; dateless IDs ("from the 4.6 generation
  on") are their own pinned snapshot, so `claude-opus-5-5` does not move under you.
  (source: https://platform.claude.com/docs/en/models/overview)
- Legacy still listed: `claude-fable-5`, `claude-opus-5`, `claude-opus-4-8/4-7/4-6/4-5`,
  `claude-sonnet-5`, `claude-sonnet-4-6`, `claude-haiku-4-5` (some retired except on Bedrock/GCloud).
  (source: https://platform.claude.com/docs/en/models/overview)
- **Smallest model that does TOOL CALLS:** `claude-haiku-5-5` — "All current models support ... tool
  use" and the tool-use pricing table lists Haiku 5.5 with its own system-prompt token counts
  (286/406 tokens). Cheapest tool-calling model by far.
  (sources: https://platform.claude.com/docs/en/models/overview,
  https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)
- Models API: `GET /v1/models` returns `max_input_tokens`, `max_tokens`, a `capabilities` object and
  a `line` field — the right way to build pickers instead of hardcoding ids.
  (source: https://platform.claude.com/docs/en/models/overview)
- **Tokenizer warning:** Claude 4.7+ models use a newer tokenizer producing **~30% MORE tokens** for
  the same text vs 4.6-and-earlier models — Haiku 5.5's headline $0.10/MTok is effectively ~$0.13
  per "old" token-equivalent.
  (source: https://platform.claude.com/docs/en/about-claude/pricing)

## 2. Pricing TODAY ($/1M tokens)

Full table: https://platform.claude.com/docs/en/about-claude/pricing. All prices USD, direct API.

**Cheapest 3 models (in / 5m-cache-write / cache-HIT / out):**

| Model | In | 5m cache write | Cache hit | Out |
|---|---|---|---|---|
| `claude-haiku-5-5` (prompt ≤100k) | **$0.10** | $0.125 | **$0.01** (0.1×) | **$0.50** |
| `claude-haiku-5-5` (prompt >100k) | $0.50 | $0.625 | $0.05 | $2.50 |
| `claude-sonnet-5-5` | **$2.00** | $2.50 | **$0.10** (0.05×) | **$10.00** |
| `claude-opus-5-5` | **$4.00** | $5.00 | **$0.20** (0.05×) | **$20.00** |

(source: https://platform.claude.com/docs/en/about-claude/pricing — model pricing table, 2026-10-17)

Cache-read multipliers spelled out on the pricing page:
- Standard models: cache read = **0.1×** base input.
- **Claude Opus 5.5 and Sonnet 5.5: 0.05×** base input ("a cache hit costs 5% of the standard input
  price — $0.20/MTok on Opus 5.5, $0.10 on Sonnet 5.5").
- Claude Fable 5.1 / Mythos 5.1: 0.025× ($0.25/MTok).
- 5-minute cache write = **1.25×** base input; 1-hour cache write = **2×**.
- Caching "pays off after one cache read for the 5-minute duration (1.25x write)".
- All multipliers **stack** with the Batch API (−50%) and data-residency 1.1×.
- Long context: 4.6+ models (except Haiku 5.5) include the full 1M window at standard per-token
  price; **Haiku 5.5 alone** jumps to the >100k row above once a request's prompt (incl. cache
  reads/writes) crosses 100k tokens, **per request**.
- Batch API: flat 50% off both in and out (Haiku 5.5 ≤100k: $0.05 / $0.25).
- Data residency `inference_geo: "us"`: 1.1× on everything, 4.6+ models only.

## 3. Free tier TODAY

- **Official wording (whole claim):** *"New users receive a small amount of free credits to test the
  API. Contact sales for information about extended trials for enterprise evaluation."* — that is
  the ENTIRE official free-tier statement; **no amount is published**.
  (source: https://platform.claude.com/docs/en/about-claude/pricing, FAQ "Are there free tiers or trials?")
- Third-party reporting consistently puts the new-user credit at **≈$5** for new Console accounts,
  but no official page states a figure — treat as **UNVERIFIED**. No official page states it is
  tied to phone verification either; phone verification is an account-creation requirement for
  everyone (see §4), not a credit-boosting step. The historical "you must add $5 before you can
  create a key" claim is contradicted by the official get-api-key flow (no credit minimum listed).
  (sources: secondary, https://aifree.dev/learn/free-claude-api-credits (updated 2026-10-13),
  https://blog.laozhang.ai/en/posts/claude-api-key-free-tier (2026-04, updated later), official
  flow: https://platform.claude.com/docs/en/get-api-key)
- Grant is **one-time, promotional**: expires 1 year after issue unless otherwise stated,
  non-refundable, non-transferable. After it runs out, API + playground calls **stop** until
  prepaid credits are bought (prepaid billing is the default; credits expire 1 year after
  purchase, non-refundable).
  (sources: https://support.claude.com/en/articles/8977456-how-do-i-pay-for-my-claude-api-usage,
  secondary https://aifree.dev/learn/free-claude-api-credits)
- No recurring free tier exists for the API. Claude Pro subscription includes **zero** API credit;
  Free/Pro/Enterprise plans are explicitly not eligible for the monthly API credit.
  (source: https://support.claude.com/en/articles/17154008-monthly-api-credits-for-max-and-team-plans)
- **Paid plans that include API credits** (not free, but the cheapest non-trial recurring route):
  **Max 5x = $100/mo API credit, Max 20x = $200/mo, Team = $20/seat ($100/premium seat, pooled,
  cap $500)**; claimable after **7 days** on the plan, by linking a Console org on claude.ai;
  **"No payment method needed"** on the Claude Platform side; credits spent before purchased ones;
  unused monthly credit **expires each cycle**. Note: that credit explicitly does **not** cover
  interactive Claude Code, but does cover API / playground.
  (sources: https://support.claude.com/en/articles/17154008-monthly-api-credits-for-max-and-team-plans,
  https://justinmckelvey.com/blog/claude-api-credits)

### 3b. Alternative $0 paths (evaluation)

- **Evaluation tier (structural, not credit-based):** "New organizations and organizations with
  limited usage history may start in the Evaluation tier, with limits **below the standard limits**
  ... while account history is established" — automatic, fraud-prevention throttling, not money.
  (source: https://platform.claude.com/docs/en/api/rate-limits)
- **Console playground** — usable in-browser with the new-user credit; consuming the same prepaid
  pool. (source: https://support.claude.com/en/articles/8977456-how-do-i-pay-for-my-claude-api-usage)
- **Application-based credit programs** (not casual-applicable, listed for completeness):
  AI for Science (up to **$20,000** credits / 6 months, research orgs, monthly review),
  External Researcher Access (**$1,000**, alignment researchers), Claude Startups (currently
  **cohort full** — not accepting applications; members got up to $1,000 credit + $100K VC-redeemed).
  (sources: https://support.claude.com/en/articles/11199177-anthropic-s-ai-for-science-program,
  https://support.claude.com/en/articles/9125743-what-is-the-external-researcher-access-program,
  https://claude.com/programs/startups, https://justinmckelvey.com/blog/claude-api-credits)
- **No free "workbench credits"** beyond the new-user grant was found — UNVERIFIED as absence
  (nothing documented).

## 4. Signup friction (today)

- **Phone verification: REQUIRED, cannot skip** — "We require phone verification for all new users,
  and there isn't a way to skip this step"; six-digit SMS code; **VoIP/Google Voice/app-generated
  numbers and landlines rejected**; number must come from a **supported location**; a number can
  verify at most **3 accounts** ever; the number **cannot be changed** after verification.
  (source: https://support.claude.com/en/articles/8287232-verify-your-phone-number)
- **Geographic gate:** only users *physically located* in a supported location can create/use
  accounts, and the phone number must be from a supported location. Full list:
  https://support.claude.com/en/articles/8461763-where-can-i-access-claude (list content not
  enumerated here; India availability: this phone is the user's — UNVERIFIED per-country).
  (source: https://support.claude.com/en/articles/8287232-verify-your-phone-number)
- **Console onboarding:** create Console account → fill "some additional information about your
  organization and use case" → enter payment details and fund the account (prepaid credits) →
  keys at Settings → API keys (`sk-ant-…`, shown once). An org needs **Admin or Billing role** to
  buy credits. Keys can be scoped to workspaces and given expirations.
  (sources: https://support.claude.com/en/articles/8114531-i-created-a-claude-console-organization-how-do-i-start-using-the-claude-api,
  https://support.claude.com/en/articles/8977456-how-do-i-pay-for-my-claude-api-usage)
- **Card:** major credit cards accepted; billing is prepaid credits, not postpay. The official
  flow does **not** document a minimum purchase or a card-before-key requirement —
  key creation and billing are two separate steps.
  (sources: https://platform.claude.com/docs/en/about-claude/pricing,
  https://support.claude.com/en/articles/8977456-how-do-i-pay-for-my-claude-api-usage)
- Claude (chat) account and Console account share the same login, but **no consumer plan includes
  API access**. (source: https://support.claude.com/en/articles/17154008-monthly-api-credits-for-max-and-team-plans)

## 5. Rate limits (today)

Tiers are now **Evaluation (new orgs, sub-standard limits) → Start → Build → Scale → Custom**,
auto-assigned by usage history, raised via "Request tier increase" in the Console.
(source: https://platform.claude.com/docs/en/api/rate-limits)

- **Monthly spend caps:** Start **$500**, Build **$1,000**, Scale **$200,000**, Custom uncapped.
  At the cap: 429 `rate_limit_error` with `details.error_code: "enforced_spend_limit_reached"`,
  **no `retry-after` header**, access resumes 00:00 UTC on the 1st; self-set lower limits return
  **400** `invalid_request_error` instead.
- **Start tier (Messages API, per model class):** **1,000 RPM / 2,000,000 ITPM / 400,000 OTPM**
  for Opus 5.x, Opus 4.x, Sonnet 5.x, Sonnet 4.x, Haiku 5.5, Haiku 4.5 (all the same row).
  Fable 5.x (5.1+5 combined): 1,000 RPM / 500,000 ITPM / 100,000 OTPM. Haiku 3.5 (retired):
  100,000 ITPM and it is the only model whose **cache reads count** toward ITPM.
- **Build tier:** 5,000 RPM / 5,000,000 ITPM / 1,000,000 OTPM (same model rows; Fable: 2,000/1.5M/300k).
- **Scale tier:** 10,000 RPM / 10,000,000 ITPM / 2,000,000 OTPM (Fable: 4,000/4M/800k).
- **Cache-aware ITPM is the big one for a chatty agent:** for most models **only uncached input
  tokens + cache-creation tokens count toward ITPM**; `cache_read_input_tokens` do NOT count.
  "With a 2,000,000 ITPM limit and an 80% cache hit rate, you could effectively process 10,000,000
  total input tokens per minute." OTPM counts only actual generated tokens (high `max_tokens` is
  free, rate-limit-wise).
- Token-bucket enforcement; short bursts can trip 429s even under the RPM average; possible
  **acceleration limits** on sharp usage ramps — ramp gradually. 429 responses carry
  `retry-after` when a rate limit (not spend cap) triggers; full set of
  `anthropic-ratelimit-*-limit/remaining/reset` response headers is documented.
- Per-**workspace** sub-limits are configurable (not on the default workspace).
- Sources: all from https://platform.claude.com/docs/en/api/rate-limits (retrieved 2026-10-17).
- The old Tier1–4 deposit ladder ($5/$40/$200/$400) is **gone** — current docs have no
  deposit-based tiers. (source: https://platform.claude.com/docs/en/api/rate-limits)

## 6. API surface (what Crush Mobile must know)

- **Endpoint:** REST at `https://api.anthropic.com`; chat = **`POST /v1/messages`**
  (NOT `/v1/chat/completions`). SDKs handle auth/version headers automatically.
  (source: https://platform.claude.com/docs/en/api/overview)
- **Auth:** EITHER `Authorization: Bearer <key>` (primary) **OR** `x-api-key: <key>` (legacy
  fallback, **still supported**); `anthropic-version: 2023-06-01` required on all requests;
  `anthropic-workspace-id` header only for multi-workspace keys.
  (source: https://platform.claude.com/docs/en/api/overview)
- **Streaming:** SSE with **named events** — `message_start` → per block
  (`content_block_start` / `content_block_delta`×n / `content_block_stop`) → `message_delta`
  (cumulative usage) → `message_stop`, plus `ping` and mid-stream `error` events (e.g.
  `overloaded_error` = HTTP 529 equivalent). Text arrives as `text_delta`; usage in
  `message_delta` is cumulative. Unknown event types must be tolerated.
  (source: https://platform.claude.com/docs/en/build-with-claude/streaming)
- **Tool use blocks:** request defines tools as `{name, description, input_schema}`;
  response block = `{"type":"tool_use","id":"toolu_…","name":…,"input":{object}}` with
  `stop_reason:"tool_use"`; results come back in a **user** message as
  `{"type":"tool_result","tool_use_id":…, "content":…}`. Streaming tool args arrive as
  `input_json_delta.partial_json` fragments (accumulate → parse at `content_block_stop`).
  `tool_choice: {type:"auto"|"any"|"tool"|"none"}`, `disable_parallel_tool_use` supported.
  Opus 5.5/Sonnet 5.5/Fable 5.1/Mythos 5.1 do NOT support forced tool use (any/tool) — use auto.
  (sources: https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview,
  https://platform.claude.com/docs/en/build-with-claude/streaming)
- **Prompt caching for a chatty agent:** top-level `cache_control` (automatic breakpoint
  management, recommended) or per-block `cache_control` breakpoints. With OpenAI-style
  replay-the-whole-history clients (exactly what Crush does), conversation-prefix caching is the
  whole cost story: on Haiku 5.5, re-reading a 100k-token context every turn costs
  100k × $0.01/MTok = **$0.001/turn** vs **$0.01/turn** uncached (10×), and the 5m write is only
  1.25× — pay off after a single re-read. On Sonnet 5.5 hits are 5% (0.05×). For Opus 5.5-class
  agents the docs' own worked example: $0.58/hour session → $0.428 with 80% cache reads.
  Rate limits compound the effect (§5: cache reads don't count toward ITPM).
  (sources: https://platform.claude.com/docs/en/about-claude/pricing,
  https://platform.claude.com/docs/en/api/rate-limits)
- **Request size:** 32 MB (Messages / token counting); 413 `request_too_large` beyond.
  Token counting endpoint `POST /v1/messages/count_tokens` for preflight budgets.
  (source: https://platform.claude.com/docs/en/api/overview)

## 7. OpenAI-compat shim (official) — the key finding for a generic app

- **Anthropic ships an official OpenAI SDK compatibility layer.** Point any OpenAI SDK at
  **`base_url = https://api.anthropic.com/v1/`** with a Claude API key and keep using
  `chat.completions.create(...)` — including **streaming** (`stream: true`, `stream_options`) and
  **tool calls** (`tools` / `functions`, `parallel_tool_calls`, assistant `tool_calls`, `tool`
  role with `tool_call_id`).
  (source: https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)
- The compat layer speaks the OpenAI **chat-completions** dialect, NOT the Messages API — so an
  OpenAI-compatible client does **not** need `anthropic-version` or `x-api-key`; the OpenAI SDK
  sends `Authorization: Bearer <key>`, which is the primary auth scheme on `api.anthropic.com`.
  (sources: https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk,
  https://platform.claude.com/docs/en/api/overview)
- Compat limitations that matter to Crush Mobile:
  - **Prompt caching is NOT supported through the OpenAI shim** (native API only) — a chatty agent
    loses the 10×/20× read discount unless Crush speaks the native Messages API.
  - **`strict` tool schemas ignored** (no guaranteed schema conformance; native API has Structured
    Outputs / strict tool use).
  - System/developer messages are **hoisted** and `\n`-concatenated into the top-level `system`.
  - `n` must be 1; `response_format`, `logprobs`, `seed`, penalties, `user`, `service_tier` all
    ignored; audio and file-type image parts stripped.
  - **On Claude 4.7+ models: temperature must be 1 (or omitted) and `top_p` must be 0.99 (or
    omitted)** — sending other values is a **400 error**, not a clamp. `reasoning_effort` is
    ignored (use the `thinking` extra-body param instead; adaptive thinking is default-on on 5.x).
  - `tool_choice` `"required"`/named-function: supported generally, **but error on
    Opus 5.5 / Sonnet 5.5 / Fable 5.1 / Mythos 5.1** — use `"auto"`.
  - `x-ratelimit-*` + `retry-after` headers are OpenAI-style; `openai-version: 2020-10-01`.
  (source: https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)
- Anthropic positions the shim as *"primarily intended to test and compare model capabilities, and
  is not considered a long-term or production-ready solution"* — but it "is intended to remain
  fully functional and not have breaking changes". Rate limits are the standard `/v1/messages` ones.
  (source: https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)
- Cloud variants exist but are NOT OpenAI-shaped (Bedrock / Vertex use AWS/GCP IAM + their own
  URLs; those are platform-native integrations, not compat shims).
  (source: https://platform.claude.com/docs/en/api/overview)

### How Catwalk/Crush wires `anthropic` (verified in Crush source)

- Crush's `ProviderConfig.Type` is a first-class **`catwalk.TypeAnthropic`** — NOT openai-compat.
  For `type: "anthropic"` providers Crush defaults `BaseURL` to `https://api.anthropic.com/v1`
  and authenticates with **`x-api-key` + `anthropic-version: 2023-06-01`** headers
  (key-test hits `GET {base}/models`).
  (source: https://github.com/charmbracelet/crush/blob/main/internal/config/config.go,
  `TestConnection` switch, retrieved 2026-10-17)
- I.e. Crush 0.97.1 talks the **native Anthropic Messages protocol** (via go-anthropic-style
  client), which is *better* than the OpenAI shim for this app: it means native prompt caching
  is available at the engine layer, streaming is the Anthropic SSE shape, and the app-side
  `/v1` endpoint stays OpenAI-shaped regardless. Model ids are plain `claude-*` strings.
  (source: https://github.com/charmbracelet/crush/blob/main/internal/config/config.go;
  engine-side SSE specifics UNVERIFIED from here — verify in the embedded libcrush build)

## 8. Verdict for the phone agent (dated 2026-10-17)

- **True $0 path:** effectively **NO** for third-party use. The official grant is "a small amount
  of free credits" with **no published amount**; even if it is the commonly reported ≈$5, it is a
  one-shot test balance, and nothing documents a card/verification-free claim. Everything else
  (research/startup credits) is application-gated. UNVERIFIED figure, but direction is clear:
  Anthropic is the **most expensive** of the surveyed providers and the least free.
- **Realistic minimum to test the agent a few hours:** buy the smallest prepaid credit top-up the
  Console allows (UI minimum not documented — UNVERIFIED; historically $5, and credits are
  non-refundable and expire 1 year). A few hours of a Haiku 5.5-driven tool agent at ≤100k
  contexts runs ≈ $0.10-in/$0.50-out per MTok ⇒ **even 5M in + 1M out ≈ $1.00**, and with prefix
  caching roughly half that. Sonnet 5.5 would be ≈ $10× that shape. So **$5 of credits ≈ a full
  day of Haiku experimentation**, comfortably "a few hours" even on Sonnet.
- **Adapter verdict:** do NOT run Anthropic through a hand-rolled OpenAI shim in-app; Crush's
  native `type: anthropic` provider (x-api-key + anthropic-version → /v1/messages) is the
  supported path and keeps prompt caching. If the app must stay OpenAI-generic for this provider,
  the official compat layer at `https://api.anthropic.com/v1/` does work (streaming + tool calls),
  but silently drops caching and `strict` schemas, and 400s on non-default temperature/top_p for
  4.7+ models.
- **Cost/UX risks to encode in the app:** 1M default context is a billing hazard (Haiku 5.5
  >100k-prompt price jumps 5×); 30% tokenizer inflation on 4.7+; spend-cap 429s lack
  `retry-after`; Evaluation-tier new accounts get sub-standard limits (expect 429s on day one);
  tool_choice forced-call errors on the 5.x flagships.
