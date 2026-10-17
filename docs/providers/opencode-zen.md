# OpenCode Zen — provider deep-dive (checked 2026-10-17)

App context: Crush Mobile embeds Crush 0.97.1 and calls Zen via OpenAI-compatible
chat completions (streaming + tool calls). A free tester previously saw 33×402 on
paid models and 403 on ten `*-free` models. Last provider research: 2026-10-02 era.

## 1. What Zen is / relationship graph

- OpenCode Zen = Anomaly's ("Anomaly", https://anoma.ly) curated, benchmarked
  model gateway, branded on the landing page as "OpenCode Zen" (source:
  https://opencode.ai/zen/).
- In the current docs generation the gateway is rebranded **"OpenCode Console"**:
  "OpenCode Console provides a list of models tested and verified by the OpenCode
  team"; /docs/zen now renders this page and points signups to
  https://opencode.ai/console (source: https://opencode.ai/v2/docs/console/models/).
- Sibling products: **OpenCode Go** (monthly subscription, see §4) and
  **OpenCode Go/Enterprise**; the CLI/desktop/clickable product is OpenCode itself.
  Previous sibling "OpenCode Go pricing is separate from Zen balance"; a Go
  subscription can fall back to the *Zen* balance via "Use balance" (source:
  https://opencode.ai/docs/go/).
- OpenCode repo moved: `sst/opencode` now serves the Anomaly/opencode tree
  (default branch v2, latest v2.0.30, 2026-10-15); changelog links only
  github.com/anomalyco/opencode (source: https://opencode.ai/changelog;
  repo listing fetched 2026-10-17 via github.com/sst/opencode → anomalyco/opencode).
- Crush's provider id remains **`opencode-zen`** regardless of the Console
  rebrand (source: §8).

## 2. Signup TODAY

- Land → "Get started with Zen" → https://opencode.ai/auth → console (app shell
  "OpenCode Console"); docs steps: "Sign in to the console, add your billing
  details and credits, and copy your API key" (source:
  https://opencode.ai/v2/docs/console/models/).
- **Self-serve yes, but pre-paid:** landing pushes "Add $20 Pay as you go balance
  (+$1.23 card processing fee)" (source: https://opencode.ai/zen/). Whether a key
  can be minted with a $0 balance and used for free models is NOT documented —
  UNVERIFIED (docs sequence implies billing first; #51562 shows a card was used).
- Waitlist: the only waitlist on the site is a generic "new products" newsletter,
  not a Zen access gate (source: https://opencode.ai/zen/). Zen access itself is
  not waitlisted. Site-wide signup blocks by region: UNVERIFIED (not documented).
  Region lists exist only per underlying free-model provider (Beam Free country
  ban list incl. China/Russia/US-sanctioned states; Muse Spark Contributor
  limited to Meta-allowed regions) (source:
  https://opencode.ai/v2/docs/console/models/; https://opencode.ai/docs/go/).
- Payment methods observed in issues: cards, WeChat Pay (#43208); Apple/Google
  Pay, crypto advertised by third-party aggregators only (secondary, not
  confirmed on opencode.ai) — UNVERIFIED.

## 3. Free tier TODAY (the 402/403 story)

- 15 models currently priced "Free" (per 1M token, all columns Free):
  big-pickle, glm-5.3-flash-free, deepseek-v4.1-flash-free, space-bunny-free,
  beam-free, ll-alpha-free, longcat-2.5-preview-free, exo-free,
  mimo-v2.6-flash-free, mimo-v2.5-free, nemotron-3-ultra-free,
  nemotron-3.5-lightning-free, nemotron-3.5-super-vl-free, jev-1.13-free,
  muse-spark-1.3-contributor-free (+ muse-spark-1.2-contributor-free in the live
  list). "Free for a limited time"/"stealth model that's free" wording persisted;
  several say "collect feedback to improve the model" (source:
  https://opencode.ai/v2/docs/console/models/).
- Live `/v1/models` (no auth) returns 93 ids and includes a stray `"test"` id
  (sources: LIVE probe 2026-10-17 https://opencode.ai/inference/v1/models and
  https://opencode.ai/zen/v1/models — both HTTP 200, identical payloads).
- What a *cheap/free* key hits today, per issue evidence:
  - **402 on paid models = still the no-balance response.** "402 — Insufficient
    account funds" even after a paid $20 top-up failed to settle (billing bug)
    (source: https://github.com/anomalyco/opencode/issues/51562, reopened 2026-09-30).
  - **403 on free models still exists, two distinct flavors:**
    (a) silent 403 with body `{"model":"..."}` on some free ids (mimo-v2.5-free,
    nemotron-*, laguna-s-2.1-free) while other frees worked — filed 2026-08-26,
    closed **not_planned** 2026-10-11 with "upgrade to 2.0" triage (source:
    https://github.com/anomalyco/opencode/issues/45132);
    (b) `403 FreeTierError "OpenCode's free tier can only be used from within
    OpenCode"` — **intentional since ~2026-09-18**: maintainer
    (rekram1-node): "We've been tightening our logic to fight abuse. You cannot
    use the free tier in other harnesses (this is only a limitation for the free
    tier nothing else)" (source:
    https://github.com/anomalyco/opencode/issues/49621 + comment 5723383322;
    related #49433). Third-party stacks (node/.NET/Python/Bun byte-faithful
    replays) all 403; genuine OpenCode binary passes on same key.
  - Client-version gate: free tier rejects old/beta clients with **HTTP 426**
    "OpenCode 1.18.0 or newer is required to use the free tier" (source:
    https://github.com/anomalyco/opencode/issues/50451).
  - **429** `rate_limit_error` "OpenCode Zen: Rate limit exceeded. Please try
    again later." — the free-usage cap is DYNAMIC, shifts with capacity, is not
    published, and 429s carry no Retry-After / x-ratelimit-* headers (source:
    https://github.com/anomalyco/opencode/issues/55816, filed 2026-10-15;
    background #28055 "free usage is dynamic").
- Net: the old claim "ten `*-free` models serve 403" is still partially true and
  now DOCUMENTED as intentional for third-party harnesses; the free-tier-403 has
  a reason line since Sept: FreeTierError.

## 4. Pricing TODAY

- Billing words: "charged per request... add credits" (source:
  https://opencode.ai/v2/docs/console/models/) while the price table is per
  **1M tokens** — effectively token-priced, deducted as requests are made.
  "pay per request with zero markups" (source: https://opencode.ai/zen/).
- Sample $/1M (input/output/cached-read), all from
  https://opencode.ai/v2/docs/console/models/:
  | Model | In | Out | Cached |
  |---|---|---|---|
  | GLM-5.3 Flash | $0.15 | $0.50 | $0.03 |
  | Claude Haiku 5.5 (≤100K) | $0.10 | $0.50 | $0.01 |
  | GPT 6 Luna (≤272K) | $0.10 | $0.50 | $0.01 |
  | Gemini 3.5 Flash Lite | $0.30 | $2.50 | $0.03 |
  | Kimi K3 | $3.00 | $15.00 | $0.30 |
  Cheapest paid model overall: jev-1.13 ($0.042 in / free out); cheapest
  chat/completions one: GLM-5.3 Flash.
- Cheapest paid plan: **no monthly plan exists on Zen/Console.** Minimum entry =
  $20 top-up (+ processing; fee disclosed as "4.4% + $0.30 per transaction"
  passed at cost) (sources: https://opencode.ai/zen/,
  https://opencode.ai/v2/docs/console/models/).
  The $10/month **OpenCode Go** plan (Go Plus $40/mo) is a separate subscription
  for open coding models (GLM-5.3.*/Kimi K3/K2.7/, GPT 6 Luna, MiniMax, Qwen,
  per-model $-amount limits: 5h=20%, week=50%, month=100% of that model's
  monthly $ cap; Go ≠ Zen; Go can fall back to Zen balance) (sources:
  https://opencode.ai/docs/go/, https://opencode.ai/go).
- Auto-top-up: below $5 balance → auto-add $20 (configurable/disable) (source:
  https://opencode.ai/v2/docs/console/models/).
- Card processing shown on landing as +$1.23 on a $20 load matches ~4.4%+$0.30.

## 5. API surface TODAY

- Base URL: docs now publish protocol-split roots under
  `https://opencode.ai/inference/...` — OpenAI chat/completions at
  `https://opencode.ai/inference/openai/v1/chat/completions`, `-compat` models
  (Big Pickle, free DeepSeek/GLM/Kimi/LongCat/MiniMax-etc. rows) on
  `/inference/openai/v1/chat/completions`, Anthropic-protocol on
  `/inference/anthropic/v1/messages`, Google-protocol on
  `/inference/google/v1beta/models/<id>`, OpenAI `responses` for GPT/Grok/Muse
  rows, `/inference/systemone/v1/systemone` for jev-* (source:
  https://opencode.ai/v2/docs/console/models/).
- **The legacy `https://opencode.ai/zen/v1` root is still live** and serves the
  same catalogue (LIVE probe 2026-10-17: GET /zen/v1/models → 200, identical to
  /inference/v1/models; Current-issue curl shows
  `https://opencode.ai/zen/v1/chat/completions` still working from plain clients
  for authed calls) (source: https://github.com/anomalyco/opencode/issues/45132).
- Model list endpoint (no auth): `curl https://opencode.ai/inference/v1/models`
  (source: https://opencode.ai/v2/docs/console/models/).
- OpenAI compatibility: endpoints are explicitly labeled for
  `@ai-sdk/openai-compatible` → standard chat/completions + standard SSE
  streaming ("data:" chunks, [DONE]); tool-calling runs through the same
  OpenAI-compat surface (Crush's agent runs its edit/stream/tool loop over it).
  Explicit prose doc of the stream format: UNVERIFIED (not written down; the SDK
  labels are the spec).
- Auth: **Bearer**. `Authorization: Bearer $OPENCODE_API_KEY` in all doc curls
  (incl. systemone) (source: https://opencode.ai/v2/docs/console/models/). Live
  probe: invalid Bearer → `401 {"error":{"type":"server_error","message":"Invalid
  credential"}}`; no header → 400 (LIVE probe 2026-10-17). No `x-api-key` path.
- Error family for the app to handle: 402 insufficient funds (paid models),
  403 FreeTierError/hard 403 (free models), 426 client-version gate (free
  models), 429 dynamic free-usage cap (source URLs in §3).

## 6. Model catalogue status TODAY (93 ids, live 2026-10-17)

- `space-bunny-free`: **alive** (and newly promoted as "new anonymous model"
  banner in Oct) (source: LIVE probe; https://opencode.ai/go).
- `deepseek-v4-flash-free`: **absent → retired/superseded**; its replacement
  `deepseek-v4.1-flash-free` IS free and alive (source: LIVE probe;
  https://opencode.ai/v2/docs/console/models/). Matches the app's stale-catalog
  402/403 pain.
- Alive free + chat/completions-capable set: big-pickle,
  glm-5.3-flash-free, deepseek-v4.1-flash-free, space-bunny-free, beam-free,
  ll-alpha-free, longcat-2.5-preview-free, exo-free, mimo-v2.6-flash-free,
  mimo-v2.5-free, nemotron-3-ultra-free, nemotron-3.5-lightning-free,
  nemotron-3.5-super-vl-free, jev-1.13-free, muse-spark-{1.2,1.3}-contributor-free
  (last three also on responses; Muse contributor only).
- Paid ids alive: gpt-5/5.1/5.2/5.3(.1)-codex*/5.4*/5.5*/5.6*/6-{astra,sol,luna},
  claude-{fable-5(.1),opus-4-5…5-5,sonnet-4-5…5-5,haiku-4-5,haiku-5-5},
  gemini-3{,.5,.6,.7,.8}-flash*, gemini-3.1/4-argon, grok-4.5/4.6/4.7,
  qwen3.{5,6,7,8}*, deepseek-v4{.1}-flash/v4-pro, minimax-m2.5/2.7/m3,
  glm-5…5.3(.flash), kimi-k2.5..k3, mistral-large-4, muse-spark-1.2/1.3, jev-1.13,
  hy4-preview/hy3 (Go endpoints), plus list quirks `test`,
  `claude-opus-5-5-backup` (source: LIVE probe /inference/v1/models 2026-10-17).
- Deprecation table (recent dates): e.g. Kimi K2.5 2026-08-05, MiniMax M2.5
  2026-08-05, GLM 5 2026-05-14, Claude Opus 4.1 2026-08-05, GPT-5.2-codex family
  2026-07-23 (source: https://opencode.ai/v2/docs/console/models/).
- Free lineup churn: "Step 5 Preview Free" added 2026-10-08 and "Fledge Alpha
  Free" removed (source: https://opencode.ai/changelog v2.0.26).

## 7. Rate limits / quotas (free tier)

- **Not published.** Free usage is a dynamic, capacity-shifting cap (maintainer
  comment in #28055, cited by #55816); 429s expose no current window or
  Retry-After; an open issue asks for the rule to be published (open as of
  2026-10-16) (source: https://github.com/anomalyco/opencode/issues/55816).
- Rough scale observed by a third party pre-refusal: ~966 requests on
  deepseek-v4.1-flash-free from 08:00 before the first 429, ~500 in the last
  hour (source: https://github.com/anomalyco/opencode/issues/55816) — treat as
  anecdote, not a spec.
- Published *paid-side* controls: workspace/monthly usage limits + auto-reload
  (source: https://opencode.ai/v2/docs/console/models/); Go's per-model 5h/week/
  month windows (source: https://opencode.ai/docs/go/).

## 8. Crush-specific wiring

- Crush provider id: **`opencode-zen`**; type `openai-compat`;
  BaseURL literal **`https://opencode.ai/zen/v1`**; key source env
  `OPENCODE_API_KEY` (source:
  https://github.com/charmbracelet/crush/blob/main/internal/agent/opencode_routing_test.go,
  fetched 2026-10-17).
- That base root is confirmed still serving (§5), so Crush needs no URL change.
  Crush's routing/hint logic special-cases opencode-zen models (qwen3.x etc.)
  (source: same file).
- Consequence for Crush Mobile's $0 path: because the free tier is
  harness-gated (#49621), a $0 key will likely work for FREE models only if the
  upstream gate accepts the client; historically it has 403'd non-OpenCode
  clients. Designs must not promise free models to third-party callers. UNVERIFIED
  whether eligibility is fingerprint- or header-based (issue author's matrix
  couldn't replicate the pass).
- Crush app policy defaults remaining open item (see CLAUDE.md): the zen key
  must be passed bare (`api_key` normalization) — historically 401 with visible
  padding/quoting issues; opencode-zen rejects non-bare keys (project note,
  verified in-app 2026-10).

## 9. Dated verdict — can a $0 tester run a real conversation TODAY?

**Yes for *some* free models from a *funded-flow* key, BUT for a third-party
harness (like our embedded Crush), the free tier is likely BLOCKED by an
intentional gate; a true $0 conversation through Crush specifically should be
treated as NO.**

- Evidence FOR $0 working: 15 ids are priced Free/Free (source:
  https://opencode.ai/v2/docs/console/models/); live keyst flows show free ids
  answering with cost 0 inside the official client — e.g. #50451 shows genuine
  1.18.31 client returns `FREE-OK` on mimo-v2.6-flash-free "with cost 0"
  (source: https://github.com/anomalyco/opencode/issues/50451).
- Evidence AGAINST $0 via third-party harness: maintainer-stated gate "you
  cannot use the free tier in other harnesses" with 403 FreeTierError
  (source: https://github.com/anomalyco/opencode/issues/49621#comment-5723383322);
  matching reports #45132 (silent 403, closed not_planned).
- The OLD "33 paid models 402 at $0" behavior logically persists — paid models
  still demand balance (402 "Insufficient account funds", #51562). So a $0 key
  cannot run any PAID model, and free ids are client-gated.
- Cheapest legitimate path to a real conversation today: $10/mo OpenCode Go (if
  the model set meets the need) or $20 PAYG balance; or test only inside
  OpenCode CLI/TUI with the free ids.

## Machine-readable quick facts

- signup: self-serve, billing-first, no waitlist, console-based
- base_url_primary: https://opencode.ai/inference/{openai,anthropic,google,systemone}/v1/...
- base_url_legacy_alias: https://opencode.ai/zen/v1 (working 2026-10-17)
- auth: Authorization: Bearer $OPENCODE_API_KEY
- 402: paid + no balance; 403: free-tier harness gate OR per-model refusal;
  426: client too old; 429: dynamic free cap
- free_ids_alive_2026_10_17: 16 ids incl. space-bunny-free, glm-5.3-flash-free,
  deepseek-v4.1-flash-free (deepseek-v4-flash-free RETIRED)
- crush: provider id opencode-zen, base https://opencode.ai/zen/v1,
  env OPENCODE_API_KEY
