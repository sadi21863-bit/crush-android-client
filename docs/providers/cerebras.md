# Cerebras Inference — provider research (Catwalk id: `cerebras`)

**Research date: 2026-10-17.** Method: official docs (inference-docs.cerebras.ai, `.md` mirrors via
`/llms.txt`), cerebras.ai marketing pages, change log, live no-auth probe of
`https://api.cerebras.ai/public/v1/models` (fetched 2026-10-17, output on file). Third-party
sites used only to locate official URLs; tied claims are marked.

App context: phone agent app embedding Crush 0.97.1 → OpenAI-compatible `/v1/chat/completions`
with streaming + tool calls. Base `https://api.cerebras.ai/v1`.

---

## 1. What Cerebras is; wafer-scale inference; OpenAI-compat surface

- Cerebras Inference is an LLM inference cloud running the Cerebras Wafer-Scale Engine (WSE,
  CS-3/CS-4 line), "58× larger than GPUs", claiming inference "up to 30x faster than GPU
  systems" and ~2,000–3,000 tok/s on flagship models. (source: https://www.cerebras.ai/inference)
- Speed claim on the pricing page: "~3000 tokens/s" for `gpt-oss-120b`, "~1,800 tokens/s" for
  Gemma 4 31B-class models. (source: https://www.cerebras.net/pricing)
- OpenAI compatibility is the primary ingestion path: point an OpenAI client at
  `base_url = https://api.cerebras.ai/v1` with a Cerebras key; "two code changes" to migrate.
  (source: https://inference-docs.cerebras.ai/resources/openai.md)
- Companion tool/app config table for "custom OpenAI-compatible provider" (what Crush is):
  Base URL `https://api.cerebras.ai/v1`, key from cloud.cerebras.ai API Keys page, model =
  exact model id (`gpt-oss-120b`, `qwen-3.8-27b`); "Most tools append `/chat/completions`
  automatically". (source: https://inference-docs.cerebras.ai/resources/openai.md)
- Endpoints documented: `POST /v1/chat/completions`, `POST /v1/completions`, models list/retrieve,
  plus a keyless `/public/v1/models` (with `?format=openrouter|huggingface`), Batch + Files API,
  and Dedicated-Inference management APIs. (source: https://inference-docs.cerebras.ai/llms.txt;
  https://inference-docs.cerebras.ai/api-reference/models/public-models.md)
- Chunked streaming: standard OpenAI SSE `chat.completion.chunk` deltas; since 2026-03-30 the
  stream ends with a guaranteed terminal `data: [DONE]` event ("exactly once, always last").
  (source: https://inference-docs.cerebras.ai/support/change-log.md label 2026-03-30)
- Streaming with `tools`/`response_format` streaming is supported for all models (since
  2025-12-16). (source: same change-log, label 2025-12-16)
- Compatibility differences that matter to an OpenAI-compatible client: `n` must be 1;
  `service_tier` unavailable on Shared Inference; image URLs must be base64 data-URIs (no
  external HTTPS URLs, no `image_url.detail`); tool streaming = plain `stream: true`
  ("Do not send `tool_stream`"). (source: https://inference-docs.cerebras.ai/resources/openai.md)

## 2. Keys & signup NOW (console flows)

- Console is cloud.cerebras.ai. Landing shows "Sign up or log in": work email
  ("Continue with email" magic link) or Google or GitHub; protected by reCAPTCHA. There is
  also a mirror `cloud.cerebras-dev.ai`. (source: https://cloud.cerebras.ai/ rendered page, 2026-10-17)
- API keys are generated under **API Keys**; keys are static, **do not expire**, rotation =
  create-new → swap → delete-old; deletion is immediate/`401` for stale users.
  (source: https://inference-docs.cerebras.ai/console/api-keys.md)
- **On Free Trial accounts, Projects are not available** — skip project selection and go
  straight to "Generate API Key". (Paid accounts keys inherit a project's limits.)
  (source: https://inference-docs.cerebras.ai/console/api-keys.md)
- Org/team model exists and is real: Organization Admin / Project Admin / Project Member
  roles, email invites, Organization ID visible in Settings. (source:
  https://inference-docs.cerebras.ai/console/account-billing.md)
- **Credit card demanded?** Yes for the trial benefit. Official FAQ: "New accounts receive
  **$5 in free credits after adding a verified payment method** … If you skip adding a payment
  method at sign-up, **Playground and API access remain inactive until you do**."
  (source: https://inference-docs.cerebras.ai/support/rate-limits.md — FAQ)
  Same statement on the Billing page. (source: https://inference-docs.cerebras.ai/console/account-billing.md)
- "There is no charge for adding a payment method — you only pay if you choose to purchase
  additional credits." (+ pricing-page footnote: "You will not be charged or automatically
  enrolled in paid usage.") (source: account-billing.md; https://www.cerebras.ai/pricing)
- Phone verification: **UNVERIFIED.** No official doc mentions an SMS/phone gate; the login
  surface is email/Google/GitHub + reCAPTCHA. Third-party "AI signup" directories surveying
  89 platforms for SMS gates do not list Cerebras (their catalogue has entries for e.g.
  Cursor, Gemini, Claude — none for Cerebras), weakly suggesting no phone gate, but no
  official statement was found. (source: https://www.cerebras.ai/pricing for signup surface;
  absence-of-listing at https://sms-act.net/en/popular-services — NOT official)
- Org/team: available; free-trial accounts have no Projects subsystem (see above).
- Self-serve paid start: "Add funds starting at just $10" (Dev tier), i.e. PayGo via Billing
  tab; Cerebras Code Pro ($50/mo) and Max ($200/mo) subscription tiers are shown but both
  marked "sold out" as of the fetch. (source: https://inference-docs.cerebras.ai/support/pricing.md — nav render;
  https://www.cerebras.ai/pricing for the $10 start)
- Account deletion is NOT self-service; must email support@cerebras.net.
  (source: console/account-billing.md)

## 3. FREE tier TODAY — the "$5 credit" model

- The current product (since **2026-07-16**, change log) is a **Free Trial**: "$5 in free
  credits after adding a verified payment method. Credits work across all models on public
  endpoints and **expire 30 days after Cerebras grants them**." (source:
  https://inference-docs.cerebras.ai/support/change-log.md label 2026-07-16)
- Explicitly: "**Is there a permanently free tier?** No. The Free Trial is time- and
  credit-bounded … Cerebras doesn't currently offer a no-cost tier that renews automatically
  or a per-model always-free allowance." (source: rate-limits.md FAQ)
- When credits run out or expire: "API and Playground access stop on the Free Trial tier
  until you purchase credits. Your API keys, projects, and settings remain intact."
  (source: rate-limits.md FAQ)
- Credit mechanics: balance on console **Overview** tab; grants history on "Pay as you go"
  tab; auto-recharge off by default (opt-in). First PayGo purchase moves you to the
  Developer tier. (source: console/account-billing.md; rate-limits.md FAQ)
- Is it really $0 without a card? **No.* The account itself costs nothing, but API access
  stays *inactive* until a payment method is verified, so the credits are unreachable without
  one. Adding the card carries no charge. (*official FAQ wording; see §2.)
  (source: rate-limits.md FAQ; account-billing.md)
- Token economy on trial, per model: **5 requests/min, 30K uncached + 90K total tokens/min,
  1M tokens/hour AND 1M tokens/day** (both `gpt-oss-120b` and `qwen-3.8-27b`). Context/output
  are HALVED vs paid (§4). (source: https://inference-docs.cerebras.ai/support/rate-limits.md — Free Trial tab)
- No per-model free choice — "Every model in the Shared Inference Model Catalog is available
  on the Free Trial tier, subject to the per-model Free Trial rate limits."
  (source: rate-limits.md FAQ)
- Historical note (context, not live): the previous "always-free" allowance (widely reported
  as ~1M tokens/day, no card) was replaced by this card-gated scheme on 2026-07-16. Calendars
  disagree on the exact RPD of the old tier (commonly quoted as 1M/day); treat old-tier
  numbers as folklore, confirmed only in spirit by the change-log entry.
  (source: change-log.md label 2026-07-16; third-party corroboration:
  https://pricepertoken.com/endpoints/cerebras/free — NOT official)

## 4. Models today (verified LIVE from `api.cerebras.ai/public/v1/models`, 2026-10-17)

**The Shared/public catalog is exactly 2 models:**

| Model id | Owner | Toll (per 1M) | Context / max out (LIVE anon probe) | Vision | Tool calls in metadata | Notes |
|---|---|---|---|---|---|---|
| `gpt-oss-120b` | OpenAI | $0.35 / $0.75 | 131,072 / 40,960 | no | `tools` ✓, `tool_choice` ✓, **`parallel_tool_calls:false`** | reasoning default `medium`; harmony tokenizer |
| `qwen-3.8-27b` | Cerebras-hosted Qwen | $0.99 / $1.49 | 65,536 / 32,768 | ✓ | `tools` ✓, `tool_choice` ✓, `parallel_tool_calls:true` | reasoning default `high` |

(source: LIVE https://api.cerebras.ai/public/v1/models fetched 2026-10-17; confirmed by
https://inference-docs.cerebras.ai/models/overview.md "Available Models" table which lists
exactly `gpt-oss-120b` + `qwen-3.8-27b`)

- Tool-calling support matrix (docs): `qwen-3.8-27b` and `gpt-oss-120b` — `tool_choice`
  none/auto/required/named, **strict mode ✓, parallel ✓, multi-turn ✓**, "Shared Inference";
  `kimi-k2.7-code` = "Customer trials only"; `gemma-4-31b` = "Dedicated Inference" only.
  (source: https://inference-docs.cerebras.ai/capabilities/tool-use.md)
- **Discrepancy to watch:** the docs table claims "Parallel calls: Yes" for `gpt-oss-120b`,
  but the live models API reports `parallel_tool_calls: false` for it (and the OpenRouter-format
  public-models doc sample shows the same `false`). Expect canary difference; don't rely on
  parallel calls on gpt-oss-120b, and tolerate both.
  (source: docs https://inference-docs.cerebras.ai/capabilities/tool-use.md vs LIVE probe 2026-10-17;
  https://inference-docs.cerebras.ai/api-reference/models/public-models.md example JSON)
- Context-window rigidness by TIER (same model id, different ceiling):
  - `gpt-oss-120b`: **Free Trial 65k** context / 32k max output; **paid 131k / 40k**.
  - `qwen-3.8-27b`: **Free Trial 64k** / 32k; **paid 128k / 40k**.
  (source: https://inference-docs.cerebras.ai/models/overview.md table "Context (free / paid)";
  model pages openai-oss.md and qwen-3.8-27b.md; anon probe showing 65,536 for qwen matches trial size)
- Gone/deprecated (do NOT put in the picker): `gemma-4-31b` removed from public endpoints
  2026-09-03 (dedicated only); `zai-glm-4.7` deprecated 2026-08-17; earlier deprecations
  `llama-4-maverick` (2025-10-15), `llama-4-scout` (2025-11-03), `qwen-3-coder-480b`
  (2025-11-05), `qwen-3-235b-a22b-thinking` (2025-11-14); `llama-3.1-8b`/`llama-3.3-70b`/
  `qwen-3-32b`/`qwen-3-235b-2507`/`zai-glm-4.6` and friends do not appear in the current
  shared catalog. **There is no `llama3.x*` public model today; the "qwen3" cousin is
  `qwen-3.8-27b`.** (sources: change-log.md entries at those dates; models/overview.md;
  tool-use.md)
- OpenCode integration page lists `gpt-oss-120b` + `gemma-4-31b` — the gemma row is stale
  relative to the 2026-09-03 removal (gemma is dedicated-only now). `opencode models
  cerebras --refresh` reloads the catalog; `opencode.json` provider customization is the
  escape hatch. (source: https://inference-docs.cerebras.ai/integrations/opencode.md + change-log 2026-09-03)
- Strongest small model for an agent: with only two ids, **`gpt-oss-120b` is the strongest
  tool-calling model on the public endpoint** (120B, cheapest, fastest ~3000 tok/s, strict +
  parallel per docs), with `qwen-3.8-27b` as the vision/strict-parallel alternative. Caveat
  from the model page: gpt-oss "may call tools that aren't directly specified … reprompt with
  'you're hallucinating a tool call'" — a real risk for an agent that executes tools, and its
  parallel support is marked false in live metadata. (source: models/openai-oss.md Notes;
  tool-use.md; LIVE probe)

## 5. Rate limits — documented values (per tier, per model)

- Measurement model: RPM/RPH/RPD + TPM/TPH/TPD, whichever triggers first; **org-level**, not
  user-level; token-bucket algorithm with continuous replenishment; token estimate includes
  input + `max_completion_tokens` (set it low to avoid pre-run over-billing); project-level
  ceilings can further constrain within org limits; real numbers live per-account in Console
  → Limits. (source: https://inference-docs.cerebras.ai/support/rate-limits.md;
  https://inference-docs.cerebras.ai/console/usage-monitoring.md)
- **Dual-bucket token limits** (rolled out through 2026-08-17): two independent quotas per org —
  *Uncached TPM* (cache-miss tokens) and *Total TPM* (uncached + cached; default 3× uncached).
  429 responses name the bucket that was exceeded. (source: rate-limits.md "Uncached vs Total
  Tokens"; change-log label 2026-07-16)
- **Free Trial** (per model): RPM 5; uncached TPM 30K; total TPM 90K; TPH 1M; TPD 1M;
  images ≤2/request (qwen). Applies to both `gpt-oss-120b` and `qwen-3.8-27b`.
  (source: rate-limits.md — Free Trial tab)
- **Developer / Pay-as-you-go** (per model): `gpt-oss-120b` 1K RPM / 1M uncached / 3M total TPM,
  no hourly/daily caps; `qwen-3.8-27b` 300 RPM / 150K uncached / **750K total (temporary bump
  from 450K per 2026-09-22 change-log)**. (source: rate-limits.md — Developer tab; change-log
  label 2026-09-22)
- **Enterprise**: provisioned per org; contact sales; qwen vision up to 10 images/request.
  (source: rate-limits.md — Enterprise tab)
- **RPD (requests/day): no documented value for any tier** — daily caps in documentation are
  token-based (1M TPD on trial; none on Developer). Requests-per-day limits are not published.
  (source: rate-limits.md)
- Marketing tier copy ("10x higher rate limits than free tier", "higher priority processing")
  matches the shape of the docs tables (5 RPM → 300–1000 RPM). (source:
  https://www.cerebras.net/pricing vs rate-limits.md)

## 6. Pricing per 1M tokens (current Debian…Developer/Dev tier = PayGo)

- `gpt-oss-120b` — **$0.35 input / $0.75 output**. (source:
  https://inference-docs.cerebras.ai/models/openai-oss.md; LIVE probe `"prompt":"0.00000035",
  "completion":"0.00000075"` 2026-10-17)
- `qwen-3.8-27b` — **$0.99 input / $1.49 output**. (source:
  https://inference-docs.cerebras.ai/models/qwen-3.8-27b.md; LIVE probe `"prompt":"0.00000099",
  "completion":"0.00000149"`)
- Only two models carry published per-token pricing today because only two are public. For
  completeness, the marketing table also lists (restricted or retired SKUs): ZAI GLM 4.7
  **$2.25/$2.75** (deprecated 2026-08-17 — dates suggest this table row is stale) and
  Gemma 4 31B **$0.99/$1.49** (dedicated-only since 2026-09-03). (source:
  https://www.cerebras.net/pricing Developer Tier Pricing table — STALENESS CAVEAT;
  change-log.md 2026-08-17 / 2026-09-03)
- Cached prompt tokens: **no surcharge** — "Input tokens, whether served from the cache or
  processed fresh, are billed at the standard input token rate." (`prompt_cache_key` optional,
  no billing effect). (source: https://inference-docs.cerebras.ai/capabilities/prompt-caching.md FAQ)
- Batch API: 50% cheaper than interactive tokens (2025-12-18 changelog).
  (source: change-log.md label 2025-12-18)
- PayGo entry: first credit purchase ≥ $10; Cost tab in console breaks spend by model.
  (source: rate-limits.md FAQ "Where can I see pricing?"; account-billing.md)

## 7. Special quirks (relevant to a streaming tool-calling client)

- **Halved context on trial is enforced per tier** on the *same* model ids (65k/131k,
  64k/128k; 32k max output everywhere vs 40k paid) — a mobile client must read current
  context from the models API/console, not hardcode. (source: models/overview.md; LIVE probe
  returning 65,536/32,768 for qwen today, i.e. trial-sized values reported anonymously)
- **`reasoning_effort` differs per model and defaults deviate from OpenAI:** `gpt-oss-120b`
  default `medium` (low/medium/high); `qwen-3.8-27b` **default `high`** (none/low/medium/high;
  `high` maps to Qwen native `xhigh`); `none` disables reasoning on qwen (huge token savings
  for agent loops). (source: chat-completions.md `reasoning_effort` schema; models/qwen-3.8-27b.md)
- **`seed`: supported**, "best effort" determinism, not guaranteed. **No `language` parameter
  exists** — UNVERIFIED-NEGATIVE: absent from the Chat Completions schema; nothing official
  documents a language control. (source: https://inference-docs.cerebras.ai/api-reference/chat-completions.md
  — `seed` description; schema has no language field anywhere)
- **Streaming chunk shape with tool calls:** standard OpenAI delta chunks; tool calls arrive
  as `delta.tool_calls[]` with `{index,id,type,function:{name,arguments}}` fragments (live
  metadata for both models has `tools:true, tool_choice:true`); finish chunk carries
  `finish_reason: "tool_calls"`. Streaming + tools is GA'd for all models since 2025-12-16,
  and SSE `{type:"stop"}`→ terminally `data:[DONE]`. Do NOT send `tool_stream`; use plain
  `stream:true`. (source: openai.md "Tool streaming" row; change-log 2025-12-16; 2026-03-30;
  chat-completions.md error enum; http://data/TBD live-verified below)
- **`gpt-oss-120b` role elevation quirk:** both `system` AND `developer` roles map to
  developer-level instructions "elevated above normal user instructions" — same prompt can
  behave differently vs OpenAI. (source: models/openai-oss.md Notes;
  https://inference-docs.cerebras.ai/resources/openai.md "Developer-level instructions" section)
- **`gpt-oss-120b` hallucinated tool calls** are a documented failure mode: "may call tools
  that aren't directly specified — Monitor for non-approved tools and reprompt …"
  (source: models/openai-oss.md)
- **`tools` + `response_format` combination is hazardous:** "Do not combine them unless the
  selected model's documentation explicitly supports … `gpt-oss-120b` rejects requests
  containing both fields." Alternatives: tools-only, structured-output-only, or a 2-step
  pipeline. (source: resources/openai.md "Unsupported parameter combinations")
- **`json_object` mode is incompatible with streaming** (`stream` must be false);
  `json_schema` Strict mode streaming is fine. (source: chat-completions.md `response_format`)
- **Strict-mode schema constraints:** `additionalProperties:false` at every object level (API
  v2 default strictness since 2026-07-22); `qwen-3.8-27b` forbids `pattern`/`minLength`/
  `maxLength` in strict tool schemas; `kimi-k2.7-code` needs uniform strict across functions.
  (source: capabilities/tool-use.md; change-log labels 2026-01-21 + 2026-07-22)
- **Prompt caching:** automatic on all models/requests; 128-token block matching; TTL
  guaranteed 5 min, up to 1 h under load; cached tokens count toward *total* TPM only (free
  quota triple whammy: same-uncached budget serves 3× total); `usage.prompt_tokens_details.
  cached_tokens` exposes hit rate; org-scoped, ZDR-compliant, never persisted; optional
  `prompt_cache_key` (≤1024 chars) routes one conversation to one cache.
  (source: capabilities/prompt-caching.md; rate-limits.md dual-bucket)
- **Multi-token streaming:** chunks are batched — "~200 evenly-spaced events per second"
  rather than per-token events (2025-10-06 rollout) — a client that assumes token-per-chunk
  cadence can mis-render progress. (source: change-log.md label 2025-10-06)
- **Validation errors changed 422 → 400** (2026-04-24); SDK exception mapping changed
  accordingly. (source: change-log.md label 2026-04-24)
- **msgpack + gzip body support** (`Content-Type: application/vnd.msgpack`, `Content-Encoding:
  gzip`) to cut TTFT on long prompts. (source: chat-completions.md headers; change-log 2026-04-10)
- **`n:1` only**; `frequency_penalty`/`presence_penalty`/`logit_bias` available on all models
  (since 2026-03-31); temperature max 2.0 (since 2026-03-26); `stop` up to 4 sequences;
  `time_info.{queue_time,prompt_time,completion_time,total_time}` in every response — useful
  for on-phone latency instrumentation. (source: chat-completions.md; change-log 2026-03-26, 2026-03-31)
- **Trial image input** (qwen): base64 PNG/JPEG data URIs only, ≤2 images/request, ≤10 MiB
  payload, ≤15,000 px/side (413 above). (source: capabilities/tool-use.md imagery notes in
  rate-limits tab; chat-completions.md; change-log 2026-07-26)
- Model-portability note for multimodal: image tokens appear as `usage.image_tokens`, cached
  tokens inside `prompt_tokens_details`. (source: chat-completions.md usage schema)

## 8. Dated verdict — can a $0 tester run chats + TOOL CALLS today without a credit card?

**NO (as of 2026-10-17).**

- The trial's $5 credits are granted **only after** "adding a verified payment method", and
  until then "**Playground and API access remain inactive**" — so a cardless account cannot
  even make chat completions, tool calls or not.
  (sources: https://inference-docs.cerebras.ai/rate-limits.md FAQ 2026-10-17;
  `account-billing.md`; change-log 2026-07-16)
- A cardless signup can still create an API key via API Keys page (Free Trial accounts skip
  project selection), but the endpoint won't serve requests. (source: console/api-keys.md Step 2)

**If a card is added (no charge, cancellable only by not purchasing): the recommended path is**

- Model: **`gpt-oss-120b`** — cheapest ($0.35/$0.75), fastest (~3000 tok/s), strict tool
  calling; **$5 buys ≥ 2.9M input / 2.4M output token-equivalents** (~$3.57 raw at even split
  — realistic agent mix gives roughly 20–40 assistant turns/day for 30 days if kept lean).
  (source: models/openai-oss.md pricing; rate-limits.md FAQ "expire 30 days")
- Constraints, in bite order:
  1. **RPM=5** bites first for agents: tool-call rounds + parallel bursts exceed 5 requests
     inside a minute → `429` with "uncached or total" bucket listed. (source: rate-limits.md
     Free Trial tab + Notes)
  2. **1M tokens/day** with reasoning tokens on top (gpt-oss default `medium`) is the second
     cliff for a chatty day-agent; set `reasoning_effort:"low|none"` (qwen: `none`) to slow
     the burn. (source: rate-limits.md TPD=1M; chat-completions.md reasoning_effort)
  3. **30K uncached TPM** on cold-cache prefixes long system prompts/thousands-tool
     definitions quickly eat the bucket; caching helps (cached ≠ uncached).
     (source: rate-limits.md; prompt-caching.md)
  4. **Context halved to 65k** (gpt-oss) on trial — biggest for a file-review agent; shard
     context or accept loss. (source: models/overview.md free/paid table)
- Qwen-3.8-27b alternative for tool agents: strict/parallel live ✓, but default `high`
  reasoning effort eviscerates a 1M TPD budget; always set `reasoning_effort:"none"` there.
  (source: models/qwen-3.8-27b.md)

## Appendix: sources (fetched 2026-10-17 unless noted)

- https://inference-docs.cerebras.ai/llms.txt
- https://inference-docs.cerebras.ai/support/rate-limits.md
- https://inference-docs.cerebras.ai/support/change-log.md
- https://inference-docs.cerebras.ai/models/overview.md
- https://inference-docs.cerebras.ai/models/openai-oss.md
- https://inference-docs.cerebras.ai/models/qwen-3.8-27b.md
- https://inference-docs.cerebras.ai/capabilities/tool-use.md
- https://inference-docs.cerebras.ai/capabilities/prompt-caching.md
- https://inference-docs.cerebras.ai/capabilities/streaming.md
- https://inference-docs.cerebras.ai/resources/openai.md
- https://inference-docs.cerebras.ai/console/api-keys.md
- https://inference-docs.cerebras.ai/console/account-billing.md
- https://inference-docs.cerebras.ai/console/usage-monitoring.md
- https://inference-docs.cerebras.ai/api-reference/chat-completions.md
- https://inference-docs.cerebras.ai/support/pricing.md (nav: Free/Dev/Enterprise, Cerebras Code)
- https://www.cerebras.ai/inference ; https://www.cerebras.net/pricing ; https://www.cerebras.ai/pricing
- https://cloud.cerebras.ai/ (live landing, signup surface)
- https://api.cerebras.ai/public/v1/models (LIVE, no auth, 2026-10-17)
- Third-party, non-authoritative: https://pricepertoken.com/endpoints/cerebras/free ;
  https://benchlm.ai/free-tier/cerebras ; https://free-llm.com/provider/cerebras ;
  https://sms-act.net/en/popular-services (only to locate official pages / trial old-tier
  history; every tied claim above cites the official page instead)

### UNVERIFIED / open items

- Phone verification: no official doc; third-party SMS-gate indexes don't list Cerebras —
  likely none, needs a live signup to confirm. (see §2)
- Whether the Free Trial gets the $5 promo credit with **Google/GitHub** SSO equally, or only
  email (docs say "after adding a verified payment method" for any new account).
- Exact live `RPM` per anonymous probe (public models API shows `requests_per_minute:null`)
  — actual trial limits are account-scoped; console **Limits** is ground truth per user.
- Live `GET /v1/models` response *with a real key* (requires an account; doc reference says
  it returns the key's visible models).
