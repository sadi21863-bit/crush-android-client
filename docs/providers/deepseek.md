# DeepSeek (platform.deepseek.com) as a model provider for Crush Mobile

**Researched: 2026-10-17** — from official sources only (api-docs.deepseek.com, platform.deepseek.com, official FAQ at static.deepseek.com, status.deepseek.com, official Terms of Service on cdn.deepseek.com). This is the **hosted API platform**, not local open-weight models. All facts checked live on 2026-10-17 unless marked **unverified**. Note: the current-docs model lineup (V4 series) is newer than the widely-known 2024/2025 R1/V3 naming.

---

## Summary

- The current model lineup is **`deepseek-flash`** (DeepSeek-V4.1-Flash, released 2026-09-10) and **`deepseek-v4-pro`** (DeepSeek-V4-Pro-0813); `deepseek-chat`/`deepseek-reasoner`/R1 naming no longer appears in current docs. **(source: https://api-docs.deepseek.com/quick_start/pricing)**
- OpenAI-compatible base URL is `https://api.deepseek.com`; Anthropic-format endpoint at `https://api.deepseek.com/anthropic`. **(source: https://api-docs.deepseek.com)**
- Pricing is all-in USD per 1M tokens with a peak/off-peak split; `deepseek-flash` cache-miss input is **$0.15–$0.30/1M** and output **$0.60–$1.20/1M** — very cheap for agent-style workloads, and per the 2026-09-10 announcement, **all `deepseek-v4-pro` traffic already routes to V4.1-Flash at Flash rates** (since 2026-09-14, until "V4.1-Pro launches"). **(source: https://api-docs.deepseek.com/news/news260910)**
- **There is no free tier or signup credit visible anywhere in the official docs, FAQ, or ToS today.** The pricing engine has a "granted balance" concept (deducted before topped-up balance), but no current official page states any grant amount for new users. A new international user effectively needs to **top up first**. **(source: /quick_start/pricing Deduction Rules; no grant advertised on docs, FAQ, or signup flow — historic trial credit existence is unverified for today)**
- Signup for international users is **email-only** (directly observed on the live signup form: "Only email registration is supported in your region" — email + password + emailed code; no phone, no ID field observed). Chinese users get phone/WeChat signup; Google SSO is available at sign-in (not for mainland-China IPs). **No waitlist.** **(source: https://platform.deepseek.com/sign_up, rendered 2026-10-17)**
- Top-up accepts **PayPal, bank card, Alipay, or WeChat Pay**; top-up balances **never expire**. **(source: FAQ — "How to Top Up?" and "Does the balance expire?" at https://static.deepseek.com/faq/index.html?lang=en#/category/4)**
- For our app's two calls — chat streaming and a models picker — everything needed is supported: SSE streaming with `data: [DONE]`, `GET /models` with rich per-model metadata, tool calling, thinking mode with `reasoning_content`, 1M context, up to 384K output. **(source: https://api-docs.deepseek.com/api/list-models, https://api-docs.deepseek.com/api/create-chat-completion)**

---

## Signup

1. **Path:** open `https://platform.deepseek.com` → "Sign up". **Directly observed 2026-10-17** on `https://platform.deepseek.com/sign_up` (rendered with an international locale):
   - Banner text: **"Only email registration is supported in your region."**
   - Fields: **Email address**, **Password**, **Confirm password**, **Code** (with a "Send code" button — an emailed verification code).
   - Consent link to the Open Platform ToS and Privacy Policy (en-US localized).
   - **No phone-number field, no ID/KYC field was presented** in this international flow.
2. **What a new international user faces:** email + password + clicking a 6-digit code from their inbox, then (post-login) creating an API key at `https://platform.deepseek.com/api_keys`, then topping up before calls succeed. Sign-in screen additionally offers **"Log in with Google"** and phone/email+password. **(source: https://platform.deepseek.com/sign_in and /sign_up, rendered 2026-10-17)**
3. **Phone requirement:** none for international regions (only email is offered there). China-side login exposes WeChat binding and SMS flows; the FAQ's verification-code answer covers both SMS and email codes. **(source: FAQ → Login Issues → "Why haven't I received the verification code?")**
4. **Google SSO:** available on the sign-in (and FAQ: "Google login is not supported for IPs in Mainland China"). **(source: FAQ → Login Issues → "Why can't I log in with Google?")**
5. **Email-domain filtering:** unknown/default email domains are rejected with **"Login failed. Your email domain is currently not supported for registration"**; FAQ recommends **Gmail, Outlook, Hotmail, or Yahoo**. **(source: FAQ → Login Issues → "What should I do if I see '...email domain...not supported...'?")**
6. **No waitlist** — signup form opens directly; no invite/queue language anywhere in docs or FAQ.
7. **Real-name verification:** the FAQ API category lists "Real-Name Verification Failed" and "Personal vs Enterprise Real-Name Verification" (verification materials differ; the FAQ states there is currently no benefit difference between Personal- and Enterprise-verified accounts). Whether any verification is *forced* on a fresh international account before usage **could not be confirmed without completing a real signup** (login-gated) — **unverified**. **(source: FAQ category list at https://static.deepseek.com/faq/index.html?lang=en#/category/4)**
8. Operator identity: Hangzhou DeepSeek Artificial Intelligence Co., Ltd. (mainland PRC; ICP filing 浙ICP备2023025841号 shown in the platform footer). **(source: ToS preamble; platform footer observed 2026-10-17)**

---

## Free tier

- **No free tier and no starting credit is advertised on any official page today** (docs, FAQ, news, ToS). Checked 2026-10-17.
- The clearest official statements:
  - FAQ "Are there plans with higher rate limits?": *"There is a unified pricing standard and no tiered plans."* **(source: FAQ → API)** — i.e., no free/basic/paid-tier structure.
  - ToS §6.2: recharge methods, prices, "consumption methods, and **free quota** for paid services are subject to the information displayed on our product pages and the platform website" — the ToS leaves room for a "free quota" to exist and to change, **but no active web page displays one**. **(source: https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html)**
  - Pricing page "Deduction Rules": fees are "deducted from your topped-up balance **or granted balance**, with a preference for using the granted balance first" — the accounting mechanism for any grant exists. **(source: https://api-docs.deepseek.com/quick_start/pricing)**
- Historically (2023–2024 era) new API accounts received a small trial credit, which was later discontinued; whether *any* trial credit still exists today **could not be confirmed from a live official page** — **unverified**. Working assumption for planning: **assume $0 grant; users must top up (unknown minimum) to first call** (top-up minimum is behind login — **unverified**). **(source: docs/FAQ/news sweep 2026-10-17; no current grant page exists)**
- Balance never expires once topped up ("Top-up balances do not expire." — FAQ) and unspent balance can be refunded in full (one-time, fees deducted) per ToS §6.3 — so a required top-up is not entirely sunk cost. **(source: FAQ → API; ToS §6.3)**

---

## Pricing

All prices **per 1M tokens, USD**, from the official pricing page (checked 2026-10-17). **(source: https://api-docs.deepseek.com/quick_start/pricing)**

| Model | Input cache-hit | Input cache-miss | Output |
|---|---|---|---|
| **deepseek-flash** — off-peak | $0.003 | $0.15 | $0.60 |
| **deepseek-flash** — peak | $0.006 | **$0.30** | **$1.20** |
| **deepseek-v4-pro** — off-peak | $0.022 | $0.66 | $1.98 |
| **deepseek-v4-pro** — peak | $0.044 | $1.32 | $3.96 |

- **Peak hours:** 01:00–04:00 and 06:00–10:00 UTC, Mon–Fri (excluding Chinese public holidays). Everything else — including all weekends and Chinese holidays — is off-peak at **exactly 50% of peak**. For an India-based user (UTC+5:30): peak ≈ 06:30–09:30 and 11:30–15:30 IST weekdays. **(source: pricing page, footnote 2)**
- **Model details (same page):**
  - Model versions: `deepseek-flash` = DeepSeek-V4.1-Flash; `deepseek-v4-pro` = DeepSeek-V4-Pro-0813.
  - Context length **1M**; max output **maximum 384K** (per /models: `context_window` 1048576, `max_output_tokens` 393216 — both models). **(source: /quick_start/pricing + api/list-models example)**
  - Vision input: **flash only** (pro: "Not supported"). Tool Calls ✓ both; JSON Output ✓ both; Anthropic API ✓ both; Responses API ✓ both; Chat Prefix Completion (Beta) ✓; FIM completion (Beta) — non-thinking mode only.
  - Concurrency limit: flash **2500**, pro **500** (free capacity-expansion request form exists; "no additional cost"). **(source: pricing page footnote 3; FAQ "higher rate limits")**
  - Legacy names: `deepseek-v4-flash` and `deepseek-v4-flash-vision-exp` are still accepted but route to V4.1-Flash at Flash price. **(source: pricing page footnote 1)**
- **Search & Fetch APIs** are billed separately per request: **$0.75 per 1,000 successful requests** each. **(source: pricing page "Search & Fetch Pricing")**
- **Pro is effectively gone:** per the V4.1-Flash release news, from **04:00 UTC Sept 14, 2026 all `deepseek-v4-pro` requests route to V4.1-Flash at V4.1-Flash rates**, "until V4.1-Pro launches"; DeepSeek says it is "phasing out V4-Pro". So in practice today everything bills at Flash rates and the pro rows above are largely theoretical. **(source: https://api-docs.deepseek.com/news/news260910)**
- Bill breakdown instantaneously visible in responses: `usage.prompt_tokens_details.cached_tokens` (= `prompt_cache_hit_tokens`), `prompt_cache_miss_tokens`, `completion_tokens_details.reasoning_tokens`. **(source: https://api-docs.deepseek.com/api/create-chat-completion)**
- **Context caching on disk is on by default for all users**; cache hits require full prefix-unit matches (SWA-aware rules; common prefixes carved at fixed token intervals; best-effort; cleared after hours–days of non-use). Manual cache resets **not documented as available** via simple endpoints — cache is name/prefix-based. **(source: https://api-docs.deepseek.com/guides/kv_cache)**
- Notice: this represents a **major lineup change** vs. the 2024/2025 docs era (V3/R1 era used `deepseek-chat`/`deepseek-reasoner` naming); pickers must not hard-code legacy `deepseek-chat`/`deepseek-reasoner` as if they were the only names — use the `/models` endpoint. **(source: current docs omit those names entirely; list-models is the recommended source)**

---

## API compatibility

**(source: https://api-docs.deepseek.com, https://api-docs.deepseek.com/api/create-chat-completion, checked 2026-10-17)**

- **Base URLs**
  - OpenAI format: `https://api.deepseek.com` (SDK-style; OpenAI SDKs work with `base_url` swapped; note: curl examples hit `/chat/completions` directly under this base).
  - Anthropic format: `https://api.deepseek.com/anthropic` (Anthropic-SDK compatible; has a full guide: https://api-docs.deepseek.com/guides/anthropic_api).
  - Beta features (chat prefix completion) need base `https://api.deepseek.com/beta`.
- **Endpoints (API reference)**: `POST /chat/completions`, `GET /models`( **list models**, returns rich metadata), plus Responses API, FIM, Files API, Search/Fetch, Get User Balance.
- **List-models for a picker**: `GET /models` returns `data[]` with `id`, `object: "model"`, `owned_by: "deepseek"`, `name` (display name, e.g. "DeepSeek-V4.1-Flash"), **`context_window`**, **`max_output_tokens`**, `input_modalities` (text/image), `output_modalities`, `effort.supported_levels` (e.g. ["low","high","max"]) + `effort.default_level` ("high"), and `api_capabilities` (e.g. Anthropic-format `system_prompt_update`: flash `in-history` vs pro `leading-only`). **(source: https://api-docs.deepseek.com/api/list-models)**
- **Streaming**: standard OpenAI SSE — `stream: true` → `data:`-only server-sent events terminated by `data: [DONE]`; all delta chunks carry `choices[].delta` (`content`, `reasoning_content`, `tool_calls`); token usage rides on the **last content chunk** (`finish_reason` non-null; no separate usage-only chunk), and `stream_options.include_usage: true` adds `usage` to every chunk (null except the last). Sending `stream_options` without `stream:true` → 400. **(source: /api/create-chat-completion)**
- **Idle/keep-alive behavior (important for mobile):** during long queue waits, non-streaming requests continuously return empty lines and streaming requests continuously send **SSE keep-alive comments (`: keep-alive`)**; if inference hasn't started within **10 minutes the server closes the connection**. **(source: https://api-docs.deepseek.com/quick_start/rate_limit)**
- **Function/tool calling**: supported; tools are `type:"function"` only; `strict: true` strict-schema mode is Beta (default false); `tool_choice`: `none` (default without tools) / `auto` (default with tools) / `required` / named forcing — but **`required` and named choices are NOT supported in thinking mode (400 error)**; disable thinking mode first. In thinking mode with `tools`, **every prior turn's `reasoning_content` must be passed back in subsequent requests, or the API returns 400**. **(source: /guides/thinking_mode "Tool Calls"; /api/create-chat-completion)**
- **JSON mode**: `response_format: {"type":"json_object"}` (must still prompt for JSON; may truncate on `finish_reason:"length"`). **(source: /api/create-chat-completion)**
- **Vision** (flash only): user message content-parts `{"type":"image_url", "image_url":{"url": ...}}` accepting http(s) URLs (max 8192 chars) or base64 data URLs; formats JPEG/PNG/GIF/WebP; `detail: low` downsamples to 512×512; file upload via Files API supported (`file_id` or `file_data`). **(source: /api/create-chat-completion; vision guide)**
- **Parameters & defaults (chat completions)**: `temperature` default **1.0** (but **ignored in thinking mode**); `top_p` default 1.0 (thinking mode: effective only in the 0.95–1.0 range on flash, values <0.95 clamp to 0.95; ignored per-model rules else); `presence_penalty` / `frequency_penalty` **deprecated — accepted but no effect**; `stop` up to 16 sequences; `logprobs`/`top_logprobs` (0–20) supported; optional `user_id` (regex `[a-zA-Z0-9\-_]+`, ≤512 chars) for content-safety / KVCache / scheduling isolation. **(source: /api/create-chat-completion)**
- **Context/output limits**: context 1,048,576 tokens; `max_tokens` up to 393,216 (384K). **Defaults when unset:** **8K non-thinking, 64K thinking, 128K when `reasoning_effort:"max"`**. **(source: /api/create-chat-completion max_tokens; /quick_start/pricing)**
- **Reasoning ("thinking") mode**: **enabled by default** on both models; toggle via `{"thinking":{"type":"enabled"|"disabled"}}` (OpenAI format, needs `extra_body` in OpenAI SDK) or `reasoning_effort` (`none` disables; supported `low|high|max`; compat mapping: `minimal`→low, `medium`/`xhigh`→high; default effort **high**). CoT returns at message/delta level as **`reasoning_content`** (same level as `content`; OpenAI extra field). Multi-turn: **CoT is NOT persisted across turns by the server** unless the request carries `tools`, in which case it must be passed back (else 400). Anthropic-format control differs (`reasoning.effort`: none→disabled; `output_config.effort`). **(source: https://api-docs.deepseek.com/guides/thinking_mode)**
- **Finish reasons / stream terminations**: `stop`, `length`, `content_filter`, `tool_calls`, `insufficient_system_resource`, `aborted`. **(source: /api/create-chat-completion)**
- Chat-prefix completion (Beta) exists with `prefix: true` (+ optional `reasoning_content` in the last assistant message). Brand note: official docs list integrations for Claude Code, Codex, **OpenCode**, and others. **(source: docs root integrations; chat-prefix guide)**

---

## Restrictions outside China

1. **Payment (top-up)** works with **PayPal, bank card, Alipay, or WeChat Pay** — i.e., international cards ARE accepted (bank card). **(source: FAQ → API → "How to Top Up?")** Exact minimum top-up / supported card brands not published on a public page — **unverified** (top-up page is login-gated).
2. **Refunds**: possible for the full unspent remainder (one-time, after fees) — ToS §6.3. **(source: ToS)**
3. **Jurisdiction / ToS**: ToS §1.4 — *"We make no warranty that the Services are available or will continue to be available in certain jurisdictions. The functions or features of the Services may also vary in different jurisdictions."* **No explicit country-exclusion list is published** in the ToS or FAQ — **unverified** whether any countries are product-level blocked. **(source: https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html)**
4. **Export control / sanctions** (ToS dedicated section): the *user* must comply with Export Control and Sanctions Laws; you must not use/export to comprehensively-sanctioned countries or restricted-party-listed parties. No GFW-style geo-block action item appears in the ToS itself. **(source: ToS "Export Control and Sanctions")**
5. **Governing law**: disputes governed by **mainland PRC law**, courts at DeepSeek's Hangzhou registered office. **(source: ToS §10)**
6. **Network reachability**: `api.deepseek.com` is the globally-served endpoint baked into all official examples/status (https://status.deepseek.com shows live API components); no official VCN/China-only endpoint split is published on public pages. Practical reachability from any specific network (e.g., corporate US networks) cannot be proven from official pages alone — **unverified** beyond "everything official assumes it works worldwide".
7. **Account ecosystem split (reverse restriction)**: Google SSO not available from mainland-China IPs (FAQ); conversely email-only signup flow observed outside China. Phone binding exists for some regions ("How do I change a phone number that is no longer in service?").
8. **Chinese-company exposure framing** (as referenced in project context): DeepSeek is Hangzhou DeepSeek AI (mainland PRC operator; ICP 示浙ICP备2023025841号); API keys and chat traffic go to a China-domiciled service provider; the ToS/privacy are en-US-localized but the user-facing data destination is their global infrastructure. Worlds as-shipped: one ToS covers open-platform API in en-US locale — **there is no separate "international only" clause overlay; the whole platform is one contract**. **(source: ToS; platform footer)**

---

## Gotchas (for an app like Crush Mobile)

- **A new user's key starts at $0 balance → first hard error will be HTTP 402 "Insufficient Balance"** (error-code table explicitly maps 402 → "check your account's balance, go to Top up"). The app should expect and surface 402 distinctly from 401 (wrong key). **(source: https://api-docs.deepseek.com/quick_start/error_codes)**
- **Keep-alive parsing must be tolerant**: streams interleave **`: keep-alive` SSE comment lines** before first token; naive SSE parsers that don't skip comment lines or blank lines will mis-queue. **(source: /quick_start/rate_limit)**
- **10-minute queue timeout**: if inference never starts within 10 min the server silently closes the connection — the app needs reconnect/timeout logic tied to connection behavior, not just per-chunk timeout. **(source: /quick_start/rate_limit)**
- **`deepseek-v4-pro` model name still works but is a zombie**: since 2026-09-14 it routes to V4.1-Flash at Flash rates; billing/metadata for both model IDs stays alive. Model pickers should present **flash as the primary/default** pick and de-emphasize pro. **(source: /news/news260910)**
- **Thinking mode ON by default** with effort `high` — bills out of `max_tokens` defaults of **64K** (128K at max effort) and answers are slower; a fast chat ray casts out of the box should send `{"thinking":{"type":"disabled"}}` (or `reasoning_effort:"none"`) for snappy replies. Also remember `temperature` is inert in thinking mode. **(source: /guides/thinking_mode; /api/create-chat-completion)**
- **Tool loop needs `reasoning_content` passed back on tools requests** or 400 — engine-side agent loops must persist CoT exactly like content when `tools` is present. **(source: /guides/thinking_mode)**
- **Named/`required` tool_choice + thinking = 400**. Disable thinking first when forces are needed. **(source: /api/create-chat-completion)**
- **`stream_options` without `stream:true` = 400**; usage rides the **last content chunk**, not a standalone chunk. **(source: /api/create-chat-completion)**
- **Cache is prefix-unit based (best-effort)**; savings ($0.15 miss → $0.003 hit on flash off-peak) show up in `usage.prompt_tokens_details.cached_tokens`. A chat app that keeps a stable system prompt will mostly hit cache from the second turn onward (from that turn's prefix actually). **(source: /quick_start/pricing; /guides/kv_cache)**
- **Peak pricing window** is 01:00–04:00 & 06:00–10:00 UTC weekdays — i.e., several *off-peak* PRIME hours for many Western users; a cheap-mode toggle could schedule non-critical retries/summarization off-peak at exactly half price. **(source: /quick_start/pricing footnote 2)**
- **Rate-limit flavor is concurrency, not RPM**: an account can do far more than 60 req/min as long as concurrent in-flight requests stay under 2500 (flash); hitting the cap → **HTTP 429**. No published per-key RPM/TPM numbers exist. **(source: /quick_start/rate_limit)**
- **Thai anchor emails**: unknown email domains bounce at signup with a specific error message that could be misread as a ban ("email domain not supported for registration") — the app's provider instructions should push Gmail/Outlook first, exactly as the FAQ does. **(source: FAQ Login Issues)**
- **Errors are OpenAI-style HTTP codes**: 400 / 401 / 402 / 422 / 429 / 500 / 503, well-documented, plus a special `insufficient_system_resource` finish_reason (graceful overload mid-stream) vs the usual truncation stories — worth handling in retry logic since it differs from "length". **(source: /quick_start/error_codes; /api/create-chat-completion)**
- **No reasoning-effort "medium"**: accepted but silently mapped to `high`; only low/high/max are real. UI pickers showing "medium" would lie. **(source: /guides/thinking_mode mapping table)**

---

## Verdict for Crush Mobile

A new international user **can realistically get a working key today for $0 in fees — but not $0 in balance**: signup is a 2-minute email-code flow with no phone, no KYC field, and no waitlist, the key prints immediately from `platform.deepseek.com/api_keys`, and there is **no free trial credit anywhere on current official pages** (no grant is advertised; assume none), so the token flow begins the moment HTTPS touches the API endpoint. With one top-up from PayPal or a bank card (DeepSeek does not publish the minimum on a public page), the cost falls well under $1 for a genuine engine conversation with the pinned agent looping tool calls and long multi-turn chains, since the agent-loop hot loop (system prompt + stable tools JSON prefix) rides the disk cache at $0.003/1M in off-peak and $0.30/1M miss worst-case at peak vs $1.20/1M out; an agent-loop session with 100 turns of top-up pricing is roughly $0.10–$0.30 all-in. Model-lineup risk is real: names moved off `deepseek-chat`/`deepseek-reasoner` and on to deepseek-flash/V4-pro (with pro already seamlessly routed to flash at flash rates), so the app **must** treat `GET /models` as the picker source of truth, and international ToS/PRC-law/sanctions context means additions should be framed to users honestly as PRC-border-to-server traffic.

---

## Sources (all accessed 2026-10-17)

- Docs root (base URLs, first call): https://api-docs.deepseek.com
- Models & Pricing: https://api-docs.deepseek.com/quick_start/pricing
- Rate Limit & Isolation: https://api-docs.deepseek.com/quick_start/rate_limit
- Error Codes: https://api-docs.deepseek.com/quick_start/error_codes
- Chat Completions API reference: https://api-docs.deepseek.com/api/create-chat-completion
- Lists Models: https://api-docs.deepseek.com/api/list-models
- Thinking Mode guide: https://api-docs.deepseek.com/guides/thinking_mode
- Context Caching guide: https://api-docs.deepseek.com/guides/kv_cache
- News — V4.1-Flash release (2026/09/10): https://api-docs.deepseek.com/news/news260910
- News — V4-Pro GA (2026/08/13): https://api-docs.deepseek.com/news/news260813
- Platform sign-in / sign-up (rendered live): https://platform.deepseek.com/sign_in | https://platform.deepseek.com/sign_up
- Official FAQ (rendered live): https://static.deepseek.com/faq/index.html?lang=en#/category/4 (API + Login Issues sections)
- Open Platform Terms of Service (eff. 2026-04-29): https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html
- Status: https://status.deepseek.com/
