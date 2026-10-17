# AI Provider Research — Signup, Keys, Free Tiers (2026-10-17)

Scope: verified October 2026 signup/keys/free-tier state of 13 providers, for a zero-budget tester running **charmbracelet/crush 0.97.1** (OpenAI-compatible chat completions via Catwalk provider ids). Ground rules: primary sources only (the provider's own docs/pricing/console pages), each claim cites its source. Everything then-current in this file is quoted or paraphrased from those pages; where a number or policy could not be confirmed from a primary source, it is listed under **Cannot verify**.

---

## 1. OpenCode Zen (opencode.ai)

- Zen is OpenCode's managed inference service; the marketing page asks new users to "Sign up and add **$20 balance**" and shows "$20 Pay as you go balance (+**$1.23 card processing fee**)" (source: https://opencode.ai/zen).
- The page shows no free tier, no trial credits, and no "free models" section for Zen accounts (source: https://opencode.ai/zen).
- API endpoints live at `https://opencode.ai/inference/...` (e.g. `https://opencode.ai/inference/openai/v1/responses` for the Responses API), with model ids such as `gpt-6-astra`, `gpt-6-sol`, `gpt-6.1-sol`, `gpt-6-luna` (source: https://opencode.ai/v2/docs/console/models).
- Console docs describe creating a workspace and API key at opencode.ai, and the console FAQ section exists but is rendered collapsed (source: https://opencode.ai/v2/docs/console/models).
- Provider id in Catwalk is `opencode-zen`; keys are `oc_sk_...` style (source: https://opencode.ai/zen).

### Cannot verify
- Exact 401/402/403 error text Zen returns for expired keys or gated models (no public error page).
- What happens at zero balance (hard stop vs degraded access).

---

## 2. Charm Hyper (hyper.charm.land)

- Hyper is Charm's hosted model service for Crush and other agents; the provider JSON reports type `hyper`, api_endpoint `https://hyper.charm.land/api/v1/fantasy`, and default models `qwen3.8-max` / `deepseek-v4.1-flash` (source: https://hyper.charm.land/v1/provider).
- API keys are prefixed `sk-hyper-` and are obtained from the Hyper dashboard at hyper.charm.land; authenticate via `Authorization: Bearer sk-hyper-...` (and `X-Api-Key` on `/v1/messages`) (source: https://hyper.charm.land/docs/api/authentication.md).
- Crush has first-class Hyper support: pick **Hyper** in the model picker, the browser opens hyper.charm.land, log in (or sign up), copy the device code back; alternatively set `HYPER_API_KEY="sk-hyper-..."` (source: https://hyper.charm.land/docs/coding-agents/crush.md).
- Unauthorized requests return 401 with `{"error":{"message":"invalid api key",...}}` on completions endpoints (source: https://hyper.charm.land/docs/api/authentication.md).
- Example per-1M pricing from the provider JSON: `deepseek-v4.1-flash` $0.33 input / $1.31 output; `prism` $3.2664 / $16.332 (source: https://hyper.charm.land/v1/provider).
- Billing unit: a **Hypercredit = $0.05**; subscriptions exist — **Shred** (individuals) and **Business**; weekly Hypercredits reset Sunday 00:00 UTC (source: https://hyper.charm.land/docs/plans-billing.md).
- Signup is at `hyper.charm.land/auth?mode=signup` ("Start using LLMs in minutes. No credit card required.", Cloudflare Turnstile) (source: https://hyper.charm.land/auth).
- Conflict: docs/plans-billing.md still says "Every new Hyper account starts on the **free plan** with 100 hypercredits that refresh monthly", while the site FAQ says "We've retired the monthly free Hypercredit allowance... prepaid credits... as little as five bucks." The FAQ (newer) and search-index snippets indicate a waitlist is active (source: https://hyper.charm.land/prose/faq).
- Waitlist is real: a Charm maintainer wrote on Reddit (r/charmland, 2026-08-05) "Hyper is still waitlisted, but we let more users in periodically", and a 2026-09-15 post reports being waitlisted at signup and admitted within a day (source: https://www.reddit.com/r/charmland/, https://bsky.app/ search snippet).
- Historical launch state (April 2026): private beta, 100 free monthly Hypercredits, approval emails — this is how the waitlist era began (source: https://github.com/charmbracelet/crush PR #2768 / v0.65.2 release notes, https://github.com/charmbracelet/crush).

### Cannot verify
- Which of the free-plan 100 hypercredit claim vs FAQ retirement is current for brand-new signups (docs page appears stale).
- Official error table for 402/429 (only 401 text is documented).

---

## 3. OpenRouter (openrouter.ai)

- OpenRouter is a normalized multi-provider aggregator; requests go to `POST https://openrouter.ai/api/v1/chat/completions` with `Authorization: Bearer <OPENROUTER_API_KEY>` (source: https://openrouter.ai/docs/api-reference/overview).
- Free usage limits apply to "a **free model variant** (with an ID ending in `:free`)" (source: https://openrouter.ai/docs/limits).
- The free tier is a queue/tier system: the docs define a table of "Credits purchased (all time) → Requests per minute, Requests per day" for free-model variants, and state the tier is selected by all-time credits purchased (source: https://openrouter.ai/docs/limits).
- `GET /api/v1/key` reports `is_free_tier` ("Whether the user has paid for credits before") and `free_model_daily_requests` (used/limit/remaining per UTC day), which gates free-model requests (source: https://openrouter.ai/docs/limits).
- 402 errors carry `error.metadata.limit_source` (`openrouter_credits`, `openrouter_key_limit`, `openrouter_in_flight_budget`); 429 errors can originate at OpenRouter (platform free-model caps) or upstream providers (source: https://openrouter.ai/docs/limits).
- Rate limits are global ("Making additional accounts or API keys will not affect your rate limits, as we govern capacity globally") (source: https://openrouter.ai/docs/limits).
- Signup is email-based at openrouter.ai ("Sign In" → create account → settings/keys); per-key credit caps and hourly/daily/monthly resets are supported (source: https://openrouter.ai/docs/api-reference/overview, /docs/limits).

### Cannot verify
- Exact numeric free-tier RPM/RPD values: the docs page injects them client-side from constants (`FREE_MODEL_CREDITS_THRESHOLD`, `FREE_MODEL_RATE_LIMIT_RPM`, `FREE_MODEL_NO_CREDITS_RPD`, `FREE_MODEL_CREDITS_RPD`) and the server-rendered HTML ships them empty (source: https://openrouter.ai/docs/limits raw HTML).
- Which specific models still have working `:free` endpoints today.

---

## 4. OpenCode Go (opencode.ai/go)

- **Go** is a subscription: $10/month for Go, $40/month for Go Plus; subscription management at opencode.ai/console (source: https://opencode.ai/go, https://opencode.ai/v2/docs/console/go/).
- The current Go model list includes "LongCat 2.5 Preview **Free**" (limited time) and "Beam **Free**" (limited time, limited regions) alongside subscription staples such as GLM-5.3-Flash, Kimi K3, Grok 4.7, GPT 6 Luna, Claude Haiku 5.5, Qwen3.8 Max/Flash, DeepSeek V4.1 Flash, MiniMax M3 (source: https://opencode.ai/v2/docs/console/go/ and opencode.ai/go).
- Go workspaces are single-member ("Personal") — no invites; team features are reserved for higher tiers (source: https://opencode.ai/v2/docs/console/go/).

### Cannot verify
- Whether the "Free"-suffixed models are usable **without** the $10 subscription (the relevant console FAQ entry was truncated in fetches).

---

## 5. Groq (groq.com)

- Sign up instantly at the Groq console and create keys from the **API Keys** section (source: https://console.groq.com/docs/openai).
- OpenAI compatibility: set `base_url = https://api.groq.com/openai/v1` with the Groq API key (source: https://console.groq.com/docs/openai).
- A Free Plan exists with real rate limits; the docs table for free accounts shows e.g. `qwen/qwen3.8-27b`: 30 RPM / 1,000 RPD / 8K TPM / 200K TPD, and `openai/gpt-oss-120b`: 30 RPM / 1K RPD / 8K TPM / 200K TPD (source: https://console.groq.com/docs/rate-limits).
- Rate limits are measured in RPM/RPD/TPM/TPD (and audio-second metrics); they apply at the **organization** level, not per user (source: https://console.groq.com/docs/rate-limits).
- HTTP 429 + `retry-after` on limit; `x-ratelimit-*` response headers report RPD and TPM; the exact limits page in console settings always supersedes docs ("The following is a high level summary and there may be exceptions") (source: https://console.groq.com/docs/rate-limits).
- Developer Plan ("Need higher rate limits? Upgrade to Developer plan...") offers higher limits plus Batch/Flex (source: https://console.groq.com/docs/rate-limits).
- Chat completions params not supported: `logprobs`, `logit_bias`, `top_logprobs`, `messages[].name`; `temperature: 0` clamps to 1e-8 (relevant when Crush sends model presets) (source: https://console.groq.com/docs/openai).

### Cannot verify
- Whether legacy frontier-tenant models (e.g. meta-llama text models) are present on free plan — the rate-limit table on the day of fetch listed only OSS/Qwen/whisper/prompt-guard models (source: https://console.groq.com/docs/rate-limits).

---

## 6. Cerebras (cerebras.ai)

- Signup: visit the Cloud Console at `https://cloud.cerebras.ai`, sign up/log in, go to **API Keys** in the left nav to create a key (source: https://inference-docs.cerebras.ai/quickstart).
- Base URL: `https://api.cerebras.ai/v1/chat/completions` with `Authorization: Bearer ${CEREBRAS_API_KEY}` (source: https://inference-docs.cerebras.ai/quickstart).
- Free accounts exist with lower limits: "Free accounts have lower per-minute limits than paid accounts" (source: https://inference-docs.cerebras.ai/quickstart).
- Pricing page: "Add a valid payment method to receive a one-time **$5 promotional credit**. The credit expires 30 days after activation. ... API and Playground access will pause when the credit expires or is exhausted unless you separately purchase PayGo credits." (source: https://inference-docs.cerebras.ai/support/pricing).
- Rate limiting is a **dual-bucket, org-level** system (requests+tokens per minute/hour/day, uncached-token buckets included) (source: https://inference-docs.cerebras.ai/support/rate-limits).
- Public model catalog includes `qwen-3.8-27b` and `gpt-oss-120b`/`gpt-oss-20b` (source: https://inference-docs.cerebras.ai/models/overview).

### Cannot verify
- Whether rate-limited free access survives after the 30-day promo without buying credits (pricing page implies access pauses; quickstart implies a free account tier exists).

---

## 7. DeepSeek (deepseek.com)

- Docs host: `api-docs.deepseek.com`; base URL and quickstart are on the docs site (source: https://api-docs.deepseek.com/quick_start/pricing).
- Pricing has制宜 **peak/off-peak** tables; as of fetch: one current model at cache-hit $0.003 / $0.022, cache-miss $0.15–$0.30 input, $0.60–$1.20 output per 1M tokens, with peak discounts continuing "V4 Pro after Sept 14, 2026" (source: https://api-docs.deepseek.com/quick_start/pricing).
- Balance model: "deducted from your topped-up balance or granted balance, with a preference for... **granted balance first**"; error 402 is Insufficient Balance (source: https://api-docs.deepseek.com/quick_start/pricing and /quick_start/error_codes).
- No published free tier and no published signup-granted balance amount; top-up accepted via PayPal, bank card, Alipay, WeChat Pay (source: https://api-docs.deepseek.com/quick_start/pricing).

### Cannot verify
- Any customer grant amount at signup or eligibility for the "granted balance" program (some regional partners receive credits; nothing self-serve is documented).

---

## 8. OpenAI (openai.com)

- No automatic trial/API credits on signup anymore; help center states "OpenAI is no longer giving any credits... for those that sign up" (source: https://help.openai.com/en/articles/4936850).
- Prepaid billing: minimum purchase **$5** (default $10); deposited credits expire one year after purchase (source: https://help.openai.com/en/articles/8264644).
- First API key requires **phone verification** (source: https://help.openai.com/en/articles/8983040).
- Cheapest usable chat models at fetch: `gpt-5-nano` $0.05 input / $0.40 output per 1M standard tier; `gpt-6-luna` $0.10 / $0.50; embedding `text-embedding-3-small` $0.02 (source: https://platform.openai.com/docs/pricing).
- Chat completions + Responses + Batch/Flex tiers exist; regional/FedRAMP endpoints carry +10% (source: https://platform.openai.com/docs/pricing).
- Keys are created in platform.openai.com → API keys (source: https://help.openai.com/en/articles/8983040).

### Cannot verify
- Whether any promotional grants currently exist for enterprise/edu signups.

---

## 9. Anthropic (claude.com)

- Docs at `platform.claude.com/docs` (docs.anthropic.com redirects here); API base `https://api.anthropic.com`; Console at `platform.claude.com`; keys at `platform.claude.com/settings/keys` (source: https://platform.claude.com/docs/en/api/overview).
- Official FAQ: "Are there free tiers or trials? **New users receive a small amount of free credits to test the API**." (source: https://platform.claude.com/docs/en/about-claude/pricing).
- Cheapest model: **Claude Haiku 5.5** — $0.10 input / $0.50 output per MTok for prompts ≤100k tokens ($0.50/$2.50 above 100k); Claude Sonnet 5.5 is $2/$10; Claude Opus 5.5 $4/$20 (source: https://platform.claude.com/docs/en/about-claude/pricing).
- Billing is prepaid credit card or monthly invoicing: "Credit card and invoicing options available... To buy credits or manage your payment methods, go to the Billing page (platform.claude.com/settings/billing)" (source: https://platform.claude.com/docs/en/about-claude/pricing).
- Rate-limit tiers are Start/Build/Scale (source: https://platform.claude.com/docs/en/api/rate-limits).

### Cannot verify
- The size of the "small amount of free credits" (not quantified).

---

## 10. Google Gemini API (ai.google.dev / AI Studio)

- Free tier officially exists: "Start building **free of charge** with generous limits, then scale up with prepaid and pay-as-you-go pricing" (source: https://ai.google.dev/gemini-api/docs/pricing).
- Example: `gemini-3.8-flash` input/output are "**Free of charge**" on the Free tier; note "Content used to improve our products" is a Free-tier condition, while Paid does not use your content for training (source: https://ai.google.dev/gemini-api/docs/pricing).
- Flagship `gemini-4-argon` is **not available** on Free tier or AI Pro plan (source: https://ai.google.dev/gemini-api/docs/pricing).
- Keys are created in Google AI Studio (cli equivalent: "Get API key" at ai.google.dev) (source: https://ai.google.dev/gemini-api/docs/openai).
- OpenAI-compatibility endpoint: `base_url = https://generativelanguage.googleapis.com/v1beta/openai/` with `Authorization: Bearer $GEMINI_API_KEY` → `/chat/completions` (source: https://ai.google.dev/gemini-api/docs/openai).
- Rate limits are per **project** (not per key), measured RPM/TPM/RPD; limits depend on usage tier and are viewable in AI Studio; a **Free** usage tier exists for "Active project or free trial" with no spend-based limit; spend-based limits exist for pay tiers ($10/$50/$200 per 10 min for Tier 1/2/3) (source: https://ai.google.dev/gemini-api/docs/rate-limits).
- RPD quotas reset at midnight Pacific (source: https://ai.google.dev/gemini-api/docs/rate-limits).

### Cannot verify
- Exact per-model Free-tier RPM/RPD numbers (docs deliberately point users to the AI Studio limits page rather than publishing them).

---

## 11. Google Vertex AI (cloud.google.com)

- **Express mode**: new Google Cloud users can "sign up for an express mode account in a **free tier** to try Vertex AI for free for up to **90 days**, within the specified quotas... You don't need to provide billing information" (source: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/start/express-mode/overview).
- An express-mode **API key** is issued for API access; existing/paying users go through a Cloud Billing account (source: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/start/express-mode/overview, https://docs.cloud.google.com/vertex-ai/generative-ai/docs/start quickstart).
- After enabling billing (or for pre-existing users) the 90-day limit is removed and you pay per use (source: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/start/express-mode/overview).
- The broader Google Cloud **Free Trial**: "$300 in Welcome credit... valid for 90 days" with payment method required at signup (distinct from express mode) (source: https://docs.cloud.google.com/free/docs/free-cloud-features).
- Claude models exist on VS (billed via Google Cloud), not relevant to a zero-budget tester (source: https://platform.claude.com/docs/en/about-claude/pricing).

### Cannot verify
- Express-mode Gemini model/model-id coverage for coding-style traffic and its quotas per day (quotas are shown in-console, not in docs).

---

## 12. xAI (x.ai)

- API console: `console.x.ai`; accounts shared with Grok but billing is separate (source: https://docs.x.ai/developers/faq/accounts).
- Base URLs: `https://api.x.ai/v1` (global) and `https://us.api.x.ai/v1` (US regional, 1.1x pricing, only grok-4.7/grok-4.6) (source: https://docs.x.ai/developers/pricing).
- Billing options are **prepaid credits** (auto top-up minimum $5) and monthly invoiced billing; requests are rejected when prepaid credits are depleted and the invoiced limit is $0 (source: https://docs.x.ai/console/billing).
- Cheapest text model: `grok-build-0.1` — $1.00 / $2.00 per 1M (<200k prompt tokens); `grok-4.7` is $2/$6 (<200k) (source: https://docs.x.ai/docs/models).
- Prepaid credits are non-refundable (source: https://docs.x.ai/developers/faq/billing).
- The officially published billing FAQ/console docs **do not mention any free credits program**; third-party references to "$25 signup" / "$150/month for data sharing" predate this and the historical `docs.x.ai/docs/data-sharing` page now 404s (source: https://docs.x.ai/console/billing + absence on official pages).
- Grok Build (xAI's coding product) offers a free tier, but Grok 4.7 Fast is explicitly "not available on the public xAI API, and Grok Build's free tier does not include it" (source: https://docs.x.ai/developers/pricing).

### Cannot verify
- Whether any current signup or data-sharing grant exists in 2026 (official pages are silent).

---

## 13. GitHub Copilot (docs.github.com)

- The LLM proxy exists at `https://api.githubcopilot.com/chat/completions` and follows the OpenAI request/response format; the official docs scope it to **Copilot Extensions**: "You can call Copilot's LLM deployment at ...chat/completions with a POST request... To authenticate, use the same `X-Github-Token` header sent to your agent" (source: https://docs.github.com/copilot/building-copilot-extensions/building-a-copilot-agent-for-your-copilot-extension/using-copilots-llm-for-your-agent).
- The REST API for Copilot (`docs.github.com/en/rest/copilot`) only manages policy/usage/members — no chat endpoint listed there (source: https://docs.github.com/en/rest/copilot).
- Access requires a Copilot subscription tier (Copilot Free/Pro/Pro+/Business/Enterprise) and building a GitHub App agent that receives the IDE/user token; there is **no static API key flow for arbitrary apps** (source: https://docs.github.com/copilot/building-copilot-extensions/about-github-copilot-extensions, https://docs.github.com/copilot).
- GitHub MCP-style remote servers authenticate at `https://api.githubcopilot.com/mcp/` via browser OAuth — again not a bearer-key provider (source: https://docs.github.com/en/copilot/how-tos/copilot-in-your-ide/customize-copilot/extend-copilot-with-tools-and-context/extend-copilot-chat-with-mcp).

### Cannot verify
- Any per-seat token/TPM abuse cap relevant to custom extensions (not documented publicly).

---

## Cannot verify (cross-provider)

- OpenRouter's exact free-tier RPM/RPD numbers (constants injected client-side, not present in fetched HTML).
- Hyper: whether the 100-hypercredit monthly free plan (docs) or the prepaid-only model (FAQ) applies to a brand-new October 2026 signup, and current waitlist depth.
- OpenCode Go: whether `*-Free` models work without the $10 subscription.
- Cerebras: behavior at zero balance after the 30-day $5 promo (page says access pauses; quickstart says free accounts have lower limits).
- DeepSeek/OpenAI/Anthropic promotional grants beyond the documented statements above.
- xAI: any current free-credit grant (official docs are silent; older third-party pages are stale).
- Gemini Free-tier per-model RPD table (published only via AI Studio).

---

## Ranked recommendation for free end-to-end testing (crush engine, zero budget)

1. **Groq** — instant email signup, no card, official OpenAI-compatible base (`https://api.groq.com/openai/v1`), and a real published Free Plan (30 RPM / 1K RPD on `qwen3.8-27b` and `gpt-oss-120b`). (Confidence: high — every claim from official pages.)
2. **Google Gemini API** — Free tier officially exists ("Free of charge" on gemini-3.8-flash), OpenAI-compatible base at `generativelanguage.googleapis.com/v1beta/openai/`, key from AI Studio, no card needed; trade-off: Free tier uses content for training and per-model RPM/RPD are only visible in AI Studio. (Confidence: high.)
3. **OpenRouter** — the most flexible free option (aggregated `:free` model variants, normalizes OpenAI schema, `GET /api/v1/key` exposes `free_model_daily_requests` quota); numbers/rpms unverified but the free tier and gating are officially documented. (Confidence: medium-high on mechanics, low on exact numbers.)
4. (Overlap) **Cerebras** — viable if a card is acceptable: confirmable $5/30-day promo credit plus rate-limited free account. (Confidence: high on facts, medium on "free".)
5. (If admitted) **Charm Hyper** — beautiful fit (first-class crush support, `HYPER_API_KEY`), but a **waitlist** gates new accounts. (Confidence: high that a waitlist exists; docs/FAQ conflict on current free credits is unresolved.)

Not suitable for zero-budget testing: OpenCode Zen ($20 + $1.23 fee), OpenCode Go ($10/mo sub), OpenAI/Anthropic (card + no trial credits at signup; Anthropic claims small free credits but they are unquantified), xAI (no documented free credits), DeepSeek (no published free tier), Vertex AI express (90-day trial but needs a Google Cloud account/API-key-bound flows), GitHub Copilot (extension-scoped proxy, not a generic API key).
