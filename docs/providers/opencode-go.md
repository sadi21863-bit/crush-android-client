# OpenCode Go (Catwalk id: `opencode-go`)

Research date: **2026-10-17**. Sources: official opencode.ai pages/docs, the
anomalyco/opencode repo, Docker's AI docs, plus **live wire probes** against
`opencode.ai/inference/go/*` from this machine (marked `probe`). App context:
Crush Mobile (Crush 0.97.1 engine, OpenAI-compatible chat completions).

## TL;DR

| Question | Answer |
|---|---|
| What is Go | Monthly subscription model-access plan from Anomaly/OpenCode (not a harness, not a device tier) (source: https://opencode.ai/go) |
| Price today | Go **$10/mo**, Go Plus **$40/mo** (source: https://opencode.ai/docs/go/) |
| Free-model usage for $0 testers | **No.** Everything on Go routes, even Free-badged models, requires a Go credential (probe) |
| Zen key on Go | **Rejected** — "Invalid credential"; separate key families (probe) |
| Base URL (OpenAI-compat) | `https://opencode.ai/inference/go/openai/v1/chat/completions` (source: https://opencode.ai/docs/go/#endpoints) |
| Verdict for $0 tester | **No** today. Minimum spend = one Go month (**$10/mo**, possibly **$5** if the first-month promo still exists — UNVERIFIED for today) |

## 1. What OpenCode Go actually IS

- OpenCode Go is a **low-cost monthly subscription** giving "generous limits and
  reliable access to the most capable open-source models"; tagline "Low cost
  coding models for everyone". It is a **model-access plan**, not an app, not a
  harness, and not a device-like tier. (source: https://opencode.ai/go)
- Two plans: **Go ($10/month)** and **Go Plus ($40/month)** with higher limits.
  Token prices are identical between the plans; only the monthly usage limits
  differ. (source: https://opencode.ai/docs/go/)
- It is "designed primarily for international users and provides stable global
  access" — i.e., it smooths out flaky third-party open-weights providers.
  (source: https://opencode.ai/docs/go/)
- Positioning: "Use with any agent. Top up credit if needed. Cancel any time."
  — it is meant to work in OpenCode or any other agent. (source: https://opencode.ai/go)
- The docs say OpenCode Go "works like any other provider in OpenCode" and is
  "completely optional". (source: https://opencode.ai/docs/go/)
- **Who it is for:** budget-conscious developers who want capped, predictable
  cost on open models; press coverage compares it to Claude Max ($100+/mo) as a
  cheap alternative. (source: https://kaino.dev/news/opencode-launches-go-subscription-for-lower-cost-access-to-coding-models,
  https://www.alura.com.br/artigos/kimi-k2-opencode)
- Go is served by Anomaly (the OpenCode company; the GitHub org `sst` was
  renamed to `anomalyco` in Sept 2026). (source: https://www.getorchestra.io/knowledge-hub/comparisons/claude-code-vs-opencode,
  https://opencode.ai/go footer "©2026 Anomaly")
- **Announcement timeline (best-effort, dated):**
  - 2026-03-30: OpenCode team member posts "We've signed Zero Data Retention
    agreements with all providers for OpenCode Go … It's one of our
    fastest-growing plans" — implies Go existed as a plan by then
    (source: https://www.linkedin.com/posts/stefan-avram-62696713a_opencode-go-low-cost-coding-models-for-activity-7444376498016272384-DvwY).
    Exact first-announcement date: **UNVERIFIED**.
  - 2026-05-24: third-party coverage "OpenCode Go … for just $5/month. $5 first
    month, $10 after." (source: https://www.linkedin.com/posts/stephenlovino_this-is-wild-opencode-go-gives-you-access-activity-7464432549302255616--_90)
  - 2026-06-02/03: referral links circulating; third-party writeup of official
    docs "$5 first month, then $10/month", launch described as live alongside
    the opencode.json permission schema (~June 20, 2026 GA)
    (source: https://aytac.dev/en/news/2026-06-03-opencode-go-subscription-5-dollars/,
    https://www.reddit.com/r/opencode/comments/1tum8we/referral_code_for_opencode_go/)
  - 2026-08-17: X post "Operation Cheepseek: Phase 1 Complete — OpenCode Go
    subscribers now get $30 of usage for $10" (value framing before the
    per-model limit tables) (source: https://twiscan.com/en/x/opencode/2089428008268402786)
  - The official docs page `opencode.ai/docs/go/` (current $10/$40 shape) is
    dated 2026-10-12 in search metadata (source: https://opencode.ai/docs/go/).
    An early beta version of `go.mdx` in the repo still reads "$5 for your
    first month … currently in beta"
    (source: https://github.com/anomalyco/opencode/blob/7daea69e/packages/web/src/content/docs/go.mdx).
- Note for our app: **`opencode-go` is a separate Catwalk provider id** from
  `openrouter` and `opencode-zen`/`opencode` (app context, confirmed by the
  config format `opencode-go/<model-id>`)
  (source: https://opencode.ai/docs/go/).

## 2. Cost TODAY and how it differs from opencode-zen

- **Go: $10/month. Go Plus: $40/month.** The landing page advertises exactly
  these two price points; no other tiers. (source: https://opencode.ai/go)
- **Included usage is a monthly dollar budget per model**, not credits and not
  an unlimited plan. Example (Go plan): GLM-5.3-Flash $60/mo, Kimi K3 $15/mo,
  DeepSeek V4.1 Flash $80/mo, Step 5 Preview $60/mo (4× promo), LongCat 2.5
  Preview Free ∞. Go Plus roughly triples most: GLM-5.3-Flash $180/mo, Kimi K3
  $60/mo. (source: https://opencode.ai/docs/go/#usage-limits)
- Per-model **5-hour limit = 20% of the model's monthly limit, weekly = 50%,
  monthly = 100%**. (source: https://opencode.ai/docs/go/#usage-limits)
- Historical pricing quirks: at launch it was "$5 first month, then $10/month"
  (source: https://github.com/anomalyco/opencode/blob/7daea69e/packages/web/src/content/docs/go.mdx),
  and as of the Sept 16, 2026 r/opencode discussion "the offer was simple:
  $10/month gives you $60 of usage" before limits were re-expressed per-model
  (source: https://www.reddit.com/r/opencode/comments/1vx13o9/opencode_gos_usage_limits_have_become_absurdly/).
  The **$5-first-month promo is not shown on today's page** — whether new
  signups still get it: **UNVERIFIED**.
- **Credits/top-up:** Go has no separate credits; if you also hold Console
  (Zen) balance you can enable **"Use balance"** so usage past Go's limits
  falls back to pay-as-you-go instead of blocking requests. Landing page: "Top
  up credit if needed." (source: https://opencode.ai/docs/go/#usage-beyond-limits,
  https://opencode.ai/go)
- **Difference from opencode-zen:** official console FAQ — "Zen is
  pay-as-you-go. Go is a monthly subscription with generous limits and reliable
  access to a curated model lineup." (source: https://dev.opencode.ai/console/go)
  - Zen = Console pay-as-you-go: sign in, add billing + credits ($20 minimum
    balance + card fee), copy API key, `opencode/<model-id>` ids, priced per
    1M tokens, auto-reload at <$5. (source: https://opencode.ai/zen,
    https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/models.mdx)
  - Go = flat subscription: no per-token billing inside the included monthly
    usage; `opencode-go/<model-id>` ids. (source: https://opencode.ai/docs/go/)
  - Go's model lineup is a subset tuned for cheap/reliable coding; Zen's
    catalog is much larger (GPT 6/5.x family, Claude Opus/Sonnet/Fable, Gemini
    3.x/4, Mistral, Jev, etc.). (source: https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/models.mdx)

## 3. Signup / API key today

- **Go keys are gated on subscribing.** Official flow: "Sign in to the OpenCode
  Console, **subscribe to Go or Go Plus**, add your billing details, and copy
  your API key." No subscription, no Go key. (source: https://opencode.ai/docs/go/)
- The subscribe buttons point at `opencode.ai/console/go` (card/billing in
  Console). (source: https://opencode.ai/go)
- Constraint: Go plans are for **single-member workspaces only** ("A workspace
  with a Go subscription cannot invite members", "Only one member per workspace
  can subscribe"). (source: https://opencode.ai/docs/go/)
- **Can someone self-serve an API key without the Go plan?** Not a *Go* key.
  But yes in general: a **Console/Zen pay-as-you-go key** is self-serve after
  adding billing + credits — that is a different key that hits
  `/inference/openai/...` (Zen), not `/inference/go/...`
  (source: https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/models.mdx;
  probe below: Zen key rejected on Go route).
- **Does the Go plan grant Zen-style API keys?** Yes in shape: a Console API
  key ("copy your API key"), Bearer-auth, used with `/connect` → OpenCode Go.
  Whether the Go key is byte-format distinct from Zen keys: **UNVERIFIED**
  (documented pages show the same copy-a-key flow; our probe could not
  distinguish formats without a Go subscription).
- Referral program exists: both referrer and new subscriber get **$5 usage
  credit toward Go usage limits**. (source: https://www.reddit.com/r/opencode/comments/1ubzi1z/opencode_go_referral_thread_drop_your_links/)

## 4. The "Free"-badged models

- Go's catalog contains two free-badged models right now: **LongCat 2.5 Preview
  Free** (`longcat-2.5-preview-free`) and **Beam Free** (`beam-free`) — both
  "Free / Free / Free" tokens and **Unlimited** monthly usage, "limited time"
  (Beam Free also limited regions, incl. exclusions for RU/CN/Iran/etc.).
  (source: https://opencode.ai/docs/go/)
- **Do they work TODAY with any key? No.** Live probe (2026-10-17): POST
  `https://opencode.ai/inference/go/openai/v1/chat/completions`, model
  `longcat-2.5-preview-free`, no key → `400 MissingSessionID` (missing
  `x-opencode-session` header); retry with a session header + custom
  User-Agent, still no key → **401** `{"error":{"message":"Upstream request
  failed: Go requires a credential"}}`. A paid model (`glm-5.3-flash`) gets
  the same 401. **probe: this session, 2026-10-17** — the Go route demands a
  Go credential for everything, including free-badged models.
- The free-badged models also exist in the **Zen/Console catalog**
  (`beam-free`, `longcat-2.5-preview-free`, `glm-5.3-flash-free`,
  `deepseek-v4.1-flash-free`, `mimo-*-free`, `nemotron-*-free`, `big-pickle`,
  `ll-alpha-free`, `exo-free`, `space-bunny-free`, …) — priced Free per 1M
  tokens there too. (source: https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/models.mdx)
- **But the Zen free tier is client-gated:** probe (2026-10-17) POST
  `https://opencode.ai/inference/openai/v1/chat/completions`, model
  `longcat-2.5-preview-free`, UA `crushmobile/0.1` → **403**
  `{"type":"FreeTierError","message":"OpenCode's free tier can only be used
  from within OpenCode. Download it at https://opencode.ai …"}`. Same 403 with
  the user's real stored Zen key and with a bogus key — the gate checks the
  client, not the key. Third-party agents (like our app) **cannot use the free
  tier**; the official inference docs also state "Paid models require the
  header; free chat models can be called without it" — "within OpenCode"
  being the operative gate (source: https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/api/inference.mdx;
  probe: this session, 2026-10-17).
- **What do free models cost on paid usage?** Within a Go plan they cost $0
  and don't consume the dollar limits (Free / Unlimited). On Zen/Console
  pay-as-you-go they are also $0 per 1M tokens. Their listed "cost" is 0 in
  both catalogs; the catch is purely access (Go credential / OpenCode client).
  (source: https://opencode.ai/docs/go/,
  https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/models.mdx;
  probes above)

## 5. API surface

- **Go routes live under `https://opencode.ai/inference/go/…`**, by API family:
  OpenAI Chat Completions `/inference/go/openai/v1/chat/completions`, OpenAI
  Responses `/inference/go/openai/v1/responses` (Grok/GPT/Muse models),
  Anthropic Messages `/inference/go/anthropic/v1/messages` (Claude Haiku 5.5,
  MiniMax M3, Qwen 3.7/3.8), Gemini
  `/inference/go/google/v1beta/models/<model>`. Model list endpoint:
  `curl https://opencode.ai/inference/go/v1/models`. Model ids are plain
  (`glm-5.3-flash`, `kimi-k3`, …), config format `opencode-go/<model-id>`.
  (source: https://opencode.ai/docs/go/#endpoints)
- **OpenAI-compatible:** the chat-completions family is explicitly
  `@ai-sdk/openai-compatible`; Docker Agent documents Go as "API Type:
  OpenAI-compatible (`openai_chatcompletions`)" with base URL
  `https://opencode.ai/zen/go/v1`. (source: https://opencode.ai/docs/go/#endpoints,
  https://docs.docker.com/ai/docker-agent/providers/opencode-go/)
- **`zen/go/v1` alias verified live:** probe (2026-10-17) POST
  `https://opencode.ai/zen/go/v1/chat/completions` → same 401 "Go requires a
  credential" (route alive, same auth gate). Both hosts serve Go.
  (probe: this session, 2026-10-17)
- **Streaming:** standard AI-SDK usage implies SSE streaming (docs are built
  around `@ai-sdk/*` packages and coding-agent traffic), but **no official
  explicit statement about SSE/streaming for Go: UNVERIFIED.**
- **Tool calling:** not explicitly documented for Go. The docs say Go is
  "designed for OpenCode and other coding agents that produce similar types of
  requests", and validated clients include Claude Code and Codex — both
  tool-calling harnesses — so tool support is strongly implied
  (**UNVERIFIED**, no official endpoint-level tool docs found).
- **Session routing requirement:** clients must send a stable
  **`x-opencode-session`** header per conversation ("so we can optimize
  routing and prompt caching"), identify with a real product User-Agent
  ("rather than a generic SDK or HTTP-library name"), and send "typical coding
  agent traffic"; "Traffic is monitored for abuse that degrades the experience
  for other users." (source: https://opencode.ai/docs/go/#where-can-i-use-it)
  - Probe evidence of enforcement: the Go route **rejects before auth** with
    `400 MissingSessionID` when the header is absent. (probe: 2026-10-17)
  - Validated third-party clients list: Hermes, Claude Code, Codex, ZCode, Pi,
    jcode, Kilo Code CLI — several needed PRs to send the session header.
    (source: https://opencode.ai/docs/go/#where-can-i-use-it)
- **Auth:** `Authorization: Bearer <token>` on the `/inference` routes
  (source: https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/api/inference.mdx;
  probe: no-token → 401 "Go requires a credential").
- **Models endpoint is public:** GET
  `https://opencode.ai/inference/go/v1/models` returns the full JSON list
  without auth (38 models observed, including extra ids not in the docs table:
  `glm-5.1`, `kimi-k2.6`, `minimax-m2.5`, `minimax-m2.7`, `qwen3.6-plus`,
  `qwen3.7-max`, `grok-4.6`, `omen-alpha`). (probe: 2026-10-17)

## 6. Rate limits / quotas (as published)

- **Per-model dollar budgets** as in section 2; each model enforces
  5-hour (20% of monthly), weekly (50%), monthly (100%). Crossing a limit
  blocks that model until the window resets (or falls back to Console balance
  if "Use balance" is enabled). (source: https://opencode.ai/docs/go/#usage-limits,
  https://opencode.ai/docs/go/#usage-beyond-limits)
- **No per-request concurrency or RPM quotas are published** for Go — nothing
  beyond the 5h/weekly/monthly dollar envelopes found in official docs
  (**UNVERIFIED** whether unpublished ones exist).
- Docs advice: "If you reach the usage limit, you can continue using the free
  models." (source: https://opencode.ai/docs/go/)
- Docker Agent's integration page documents the dollar-shaped limits
  explicitly (5-hour $12 / weekly $30 / monthly $60 for the Go plan) and notes
  limits "are defined as dollar values". (source: https://docs.docker.com/ai/docker-agent/providers/opencode-go/)
- Usage is visible in the Console. (source: https://opencode.ai/docs/go/)
- User-experience caveat: r/opencode complains (2026-09-16) that limit display
  changed and is confusing; the yellow bar chart on the landing page is
  request *estimates*, not hard limits. (source: https://www.reddit.com/r/opencode/comments/1vx13o9/opencode_gos_usage_limits_have_become_absurdly/)

## 7. Bring your own Zen plan / shared state

- **No BYO-Zen for the Go API.** Probe (2026-10-17): the user's valid, working
  Zen/Console key (stored locally, never printed) sent as Bearer against
  `https://opencode.ai/inference/go/openai/v1/chat/completions` with the FREE
  model → **401 "Invalid credential"**. Same key on the Zen route gets a
  different, non-auth response (FreeTierError), proving the key itself is
  valid and the Go route specifically rejects Zen keys. (probe: this session)
- **Shared state goes one way: Go → Zen balance.** "If you also have credits
  in your Console balance, you can enable the Use balance option in the
  console. When enabled, Go will fall back to your pay-as-you-go balance after
  you've reached your usage limits instead of blocking requests."
  (source: https://opencode.ai/docs/go/#usage-beyond-limits)
- Docker's docs phrase it the same way: "You can also add Zen balance to
  continue usage beyond the limits." (source: https://docs.docker.com/ai/docker-agent/providers/opencode-go/)
- Both products live behind one Console login/workspace, but subscriptions are
  per-workspace-single-member; a Console (pay-as-you-go) account does **not**
  include Go, and vice versa. (source: https://opencode.ai/docs/go/,
  https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/models.mdx)

## 8. Dated verdict

**2026-10-17: can a $0 tester use OpenCode Go TODAY? — NO.**

- Every request to Go endpoints (including the two Free-badged models) must
  carry a Go credential; without one the route 401s
  (`probe: "Go requires a credential"`, 2026-10-17). There is no published
  free trial, no published keyless free tier on the Go routes, and the
  landing page offers no free plan (source: https://opencode.ai/go).
- The Zen free tier is a dead end for third-party apps: OpenCode-client-only
  (`FreeTierError` 403 even with a valid Zen key; `probe: 2026-10-17`).
- **Minimum spend to be usable: $10 (one month of Go).** That buys $10 of
  monthly per-model dollar budgets whose largest lines are GLM-5.3-Flash
  $60/mo, DeepSeek V4.1 Flash $80/mo, Kimi K3 $15/mo (5h ≈ 20% of each) —
  plus two unlimited Free-badged models while the promo lasts
  (source: https://opencode.ai/docs/go/#usage-limits).
  A **$5 first-month promo** existed historically and may still apply at
  signup — UNVERIFIED for today (source: https://github.com/anomalyco/opencode/blob/7daea69e/packages/web/src/content/docs/go.mdx).
- For Crush Mobile, **`opencode-zen`/Console pay-as-you-go stays the only
  $0-or-cheap self-serve path that actually answers requests from a
  non-OpenCode client**; Go becomes an option only if the user buys $10/mo,
  and then we must send `x-opencode-session` + a real product User-Agent
  (source: https://opencode.ai/docs/go/#where-can-i-use-it; probes 2026-10-17).

## Probe log (all 2026-10-17, this machine)

| # | Target | Key | Result |
|---|---|---|---|
| P1 | GET `inference/go/v1/models` | none | 200, 38 models (public) |
| P2 | POST `inference/go/openai/v1/chat/completions`, `longcat-2.5-preview-free` | none, no session hdr | 400 `MissingSessionID` |
| P3 | same, session hdr + UA `crushmobile/0.1` | none | 401 "Go requires a credential" |
| P4 | same, `glm-5.3-flash` | none | 401 "Go requires a credential" |
| P5 | POST `zen/go/v1/chat/completions`, free model | none | 401 "Go requires a credential" (alias alive) |
| P6 | `inference/go/openai/v1/chat/completions`, free model | user's Zen key (not printed) | 401 "Invalid credential" |
| P7 | `inference/openai/v1/chat/completions`, free model | user's Zen key | 403 `FreeTierError` (client-gated, key accepted) |
| P8 | `inference/openai/v1/chat/completions`, free model | bogus key | 403 `FreeTierError` (gate ignores key) |

## Sources

- Landing page: https://opencode.ai/go
- Official Go docs: https://opencode.ai/docs/go/ (also https://opencode.ai/v2/docs/console/go/)
- Console FAQ mirror: https://dev.opencode.ai/console/go
- Inference API docs (repo): https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/api/inference.mdx
- Console models/Zen docs (repo): https://raw.githubusercontent.com/anomalyco/opencode/bc5ebf00a4c169ece10f276e6ce02eaf18c326e6/services/www/src/docs/content/console/models.mdx
- go.mdx history (beta, $5 first month): https://github.com/anomalyco/opencode/blob/7daea69e/packages/web/src/content/docs/go.mdx
- Zen landing: https://opencode.ai/zen
- Changelog: https://opencode.ai/changelog
- Docker Agent integration: https://docs.docker.com/ai/docker-agent/providers/opencode-go/
- Team ZDR post: https://www.linkedin.com/posts/stefan-avram-62696713a_opencode-go-low-cost-coding-models-for-activity-7444376498016272384-DvwY
- Launch-window secondary: https://aytac.dev/en/news/2026-06-03-opencode-go-subscription-5-dollars/, https://kaino.dev/news/opencode-launches-go-subscription-for-lower-cost-access-to-coding-models
- X (via twiscan mirror): https://twiscan.com/en/x/opencode/2089428008268402786
- r/opencode limit-change thread: https://www.reddit.com/r/opencode/comments/1vx13o9/opencode_gos_usage_limits_have_become_absurdly/
