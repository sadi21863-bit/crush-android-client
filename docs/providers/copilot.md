# GitHub Copilot as a Model-API Provider (Catwalk id: `copilot`)

Research date: **2026-10-17** · App context: Crush Mobile (Crush 0.97.1 engine, OpenAI-compatible
chat completions + streaming + tool calls) on API 29 phone · Target: authorize Copilot models
from a phone app with device-flow OAuth (`/config/refresh-oauth`).

Legend — every claim carries a source tag:
`[OFFICIAL:` URL`]` = GitHub/Anthropic/OpenAI primary source · `[COMMUNITY:` URL`]` = working
third-party implementation or maintainer write-up · `[UNVERIFIED]` = no source found; treat as assumption.

---

## 1. How Copilot is exposed as a model API today

### 1.1 There are now THREE sanctioned "Copilot client" products (all official)

1. **VS Code / IDE extensions** — the canonical client of `api.githubcopilot.com`.
2. **GitHub Copilot CLI** — official, Sep 2025 era; repo `github/copilot-cli`
   ("GitHub Copilot CLI brings the power of Copilot coding agent directly to your terminal";
   11.2k stars, actively maintained as of 2026-10-16) [OFFICIAL: https://github.com/github/copilot-cli].
   Docs hub is `docs.github.com/copilot/concepts/agents/about-copilot-cli`.
   npm package today is **`@github/copilot`** (`npm install -g @github/copilot`);
   brew cask `copilot-cli`; winget `GitHub.Copilot` [OFFICIAL: repo README].
   ⚠️ Note `@github/copilot-cli` on npm is the OLD 2021 CLI (deprecated). Do not confuse.
3. **GitHub Copilot SDK** — official repo `github/copilot-sdk`: "Multi-platform SDK for
   integrating GitHub Copilot Agent into apps and services" — Python/TS/Go/.NET/Java/Rust;
   talks to the CLI runtime over JSON-RPC. FAQ says: subscription required "unless you are
   using BYOK"; billing "based on the same model as the Copilot CLI"; "All models available
   via Copilot CLI are supported in the SDK", with a method returning available models
   [OFFICIAL: https://github.com/github/copilot-sdk].
   docs.github.com has a full SDK how-tos tree incl. **multi-tenancy + backend services** for
   shipping your own product ("Ship a standalone app with Copilot", "Users sign in with
   GitHub", per-session `gitHubToken`, stable custom `Copilot-Integration-Id` such as
   `my-product-agent` set via env var) [OFFICIAL: https://docs.github.com/en/copilot/how-tos/copilot-sdk/setup/multi-tenancy].

### 1.2 The wire endpoint used by VS Code/CLI-style clients

- Chat completions: `POST https://api.githubcopilot.com/chat/completions` — OpenAI-shaped
  (messages, model, stream, SSE `data:` lines, `[DONE]`) [COMMUNITY:
  https://github.com/Aider-AI/aider/blob/5dc9490b/aider/website/docs/llms/github.md (aider docs,
  official adjunct repo); https://tales.fromprod.com/2024/321/reverse-engineering-github-copilot-apis.html].
- Model list: `GET https://api.githubcopilot.com/models` — returns `{"data":[...]}` of model
  objects incl. `id`, `capabilities` (`supports.streaming/tool_calls/vision`, limits),
  `billing` (`is_premium`, `multiplier`), `model_picker_enabled`, `supported_endpoints`
  [COMMUNITY: https://github.com/olimorris/codecompanion.nvim/blob/main/lua/codecompanion/adapters/http/copilot/get_models.lua;
  https://github.com/copilot-api-proxy/copilot-api-proxy models.rs].
- Hosts: `api.individual.githubcopilot.com` (individual), `api.<tenant>.githubcopilot.com`
  (enterprise data residency, derived from the session token's `proxy-ep`), and the token
  response carries an `endpoints.api` field naming the right host [COMMUNITY:
  https://github.com/BodhiSearch/llm-liberty/blob/main/docs/github-copilot.md;
  https://docs.litellm.ai/docs/providers/github_copilot].
- **`https://api.github.com/models` is NOT the Copilot list.** There is no documented
  `api.github.com/models` endpoint. `docs.github.com/en/rest/models` documents the
  **GitHub *Models* product** (a separate pay-per-token inference catalog at
  `models.github.ai/catalog/models`) — different product, different billing; merely listed in
  Copilot plan tables as "Increased GitHub Models rate limits" add-on [OFFICIAL:
  https://docs.github.com/en/rest/models/catalog; OFFICIAL(public plans page):
  https://docs.github.com/en/enterprise-cloud@latest/copilot/about-github-copilot/plans-for-github-copilot].
- The githubcopilot.com wire surface is **not documented in the public REST reference**
  (`docs.github.com/en/rest/copilot` only covers usage/seat management endpoints). The CLI/SDK
  are the documented official interfaces; raw api.githubcopilot.com details are established
  by observation across ≥6 independent implementations [UNVERIFIED-as-official-spec].

### 1.3 Who may call it / the auth chain

- Scripted-friendly auth for Copilot CLI: OAuth (`copilot login`), env-var tokens,
  `gh` fallback. Supported token table [OFFICIAL:
  https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/authenticate-copilot-cli]:
  | Token | Prefix | Supported | Notes |
  |---|---|---|---|
  | OAuth token (browser OR device flow) | `gho_` | Yes | default via `copilot login` |
  | Fine-grained PAT | `github_pat_` | Yes | personal account only + **"Copilot Requests"** account permission |
  | GitHub App user-to-server | `ghu_` | Yes | via env var |
  | Classic PAT | `ghp_` | **No** | not supported |
- COPilot **short-lived session token** is minted by `GET
  https://api.github.com/copilot_internal/v2/token` with `Authorization: Bearer <gho_/ghu_ token>`
  + Copilot intro headers; response `{token, expires_at, endpoints,…}`. Session token exp ≈30 min.
  This endpoint is *internal*, not in public REST docs [COMMUNITY:
  llm-liberty doc; https://github.com/Alorse/copilot-to-api/blob/main/README.md].
- CONSENT: device flow ends at the GitHub UI screen "Authorize GitHub Copilot (CLI/editor)".
  Org users additionally need org policy to allow Copilot CLI/agents [OFFICIAL: copilot-cli README
  ("cannot use… if your organization owner… has disabled it")].
- A **plain personal PAT alone** (fine-grained, Copilot Requests) is officially supported by
  Copilot CLI as an auth source [OFFICIAL: auth page]. Whether a fine-grained PAT can mint
  session tokens via `copilot_internal/v2/token` directly is not documented [UNVERIFIED];
  community impls all use `gho_`/`ghu_` from device flow or PATs of type `github_pat_` in
  LiteLLM's documented flows [COMMUNITY: litellm page].

### 1.4 Unique headers (empirically required)

Required regime reported by all community clients + implied by `403 token not authorized for
this integration` errors [COMMUNITY: llm-liberty >403 quote; litellm header table;
https://github.com/sst/opencode-copilot-auth/blob/main/index.mjs (anomalyco fork of sanitizer)]:
- `Authorization: Bearer <session token>` (secondary `X-GitHub-Token` in some surfaces)
- `Copilot-Integration-Id: vscode-chat` (or your own stable id via SDK env var)
- `Editor-Version: vscode/1.95.x...`, `Editor-Plugin-Version: copilot-chat/0.x`,
  `User-Agent: GitHubCopilotChat/0.x`
- `X-GitHub-Api-Version: 2025-04-01` (newer) on `/models` [COMMUNITY: litellm; copilot-api-proxy]
- `Openai-Intent: conversation-panel|conversation-edits`; `X-Initiator: user|agent`
  (set to `agent` when history contains assistant/tool messages) [COMMUNITY:
  opencode-copilot-auth index.mjs; confirmed in Crush source:
  https://github.com/charmbracelet/crush/blob/main/internal/oauth/copilot/client.go]
- Vision: `Copilot-Vision-Request: true` [COMMUNITY: opencode-copilot-auth]

---

## 2. Official terms — is using Copilot models from YOUR tooling allowed?

State of the paper trail (2026):

- **Individual users (Free/Pro/Pro+/Max — the $0 case):** governed by **Section J (AI
  Features) of the GitHub Terms of Service** [OFFICIAL:
  https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features
  → "For all other GitHub Copilot users, your use of GitHub Copilot is governed by Section J (AI
  Features) of the GitHub Terms of Service"; OFFICIAL: https://docs.github.com/site-policy/github-terms/github-terms-of-service
  (Section J effective 2026-04-27)]. Section J = Acceptable Use + training-use license
  (opt-out available) + Output responsibility/indemnity. **No clause found that we could cite
  which flat-out bans using your own tooling against Copilot endpoints; equally, no clause
  grants a public-API right to `api.githubcopilot.com` for third-party apps** [UNVERIFIED
  absence — reconstructing from ToS + Acceptable Use; the endpoint is simply undocumented].
- **Today's actively-sanctioned path for building products:** the **Copilot SDK**. Official
  docs include "Ship a standalone app with Copilot", "Users sign in with GitHub", "Backend
  services", "Multi-tenancy", and **a policy asking you *not* to share one token across
  users** ("Avoid sharing one service token across users unless… service-account semantics";
  "Use a stable value such as `my-product-agent` for attribution") [OFFICIAL:
  https://docs.github.com/en/copilot/how-tos/copilot-sdk/setup/multi-tenancy].
  → Building a phone app that surfaces Copilot **via the official CLI/SDK runtime** is clearly
  intended-use. Reimplementing the vscode-chat client against `api.githubcopilot.com` is
  **not** given any such documentation — it forks the identity of the VS Code client.
- **The emulation client id** `Iv1.b507a08c87ecfe98` is "GitHub Copilot's public application
  identifier… hardcoded in official Copilot extensions" [COMMUNITY:
  https://github.com/Alorse/copilot-to-api README]. Using it with the four editor headers is
  what aider/litellm/crush/zed-adjacent tools all do — de-facto tolerated, no public
  guarantee. One implementation's own comment: "The Copilot token endpoint is private; there
  is no public OAuth scope or app registration for it. GitHub could change or revoke this at
  any time, which would break all third-party integrations simultaneously." [COMMUNITY:
  https://docs.rs/corvus/latest/src/corvus/providers/copilot.rs.html]
- **Risk flags (official):** GitHub Acceptable Use (spam/inauthentic activity, abuse of
  infrastructure) + the "GitHub Security Notice" community tools now carry: excessive
  automated/bulk Copilot use "may trigger GitHub's abuse-detection systems… temporary
  suspension of your Copilot access" [COMMUNITY reproduction of GitHub's notice:
  https://github.com/ericc-ch/copilot-api/blob/master/README.md].
- **Bundling/competitor restrictions:** current terms contain **no** "no-competitor" clause we
  could cite for individuals. Business/Enterprise terms were superseded 2026-03-05 by the
  **GitHub Generative AI Services Terms** [OFFICIAL:
  https://github.com/customer-terms/github-copilot-product-specific-terms (deprecated note);
  OFFICIAL: https://github.com/customer-terms/github-generative-ai-services-terms; OFFICIAL:
  https://github.com/customer-terms/general-terms §1.12 Restrictions: no reverse engineering,
  no working around technical limitations, no selling/sublicensing the Product].
  §1.12(d) ("work around technical limitations… in Product documentation") is the closest
  hook GitHub could use against the vscode-emulation path — but as of today it is applied
  nowhere publicly against multi-TB third-party tooling [UNVERIFIED enforcement posture].
- Claude-specific: Anthropic ZDR members of the Copilot catalog pressure **internal
  operations only**: "may not make the model endpoints or outputs available externally"
  [OFFICIAL: https://docs.github.com/en/copilot/reference/ai-models/supported-models footnote].
  By symmetry this is the sharpest written-out restriction in the catalog — it constrains
  *model outputs being re-exposed*, not your client existence. A phone client that keeps
  outputs to the phone user is analogous to an IDE.

---

## 3. Which model ids are listable (`GET …/models`)

- Id shape: lowercase, provider-mixed — e.g. `gpt-4.1`, `gpt-4o-mini`, `claude-3.5-sonnet`,
  `claude-3.7-sonnet`, `o3-mini`, `gemini-2.5-pro` [COMMUNITY: opencode PR fix list:
  https://github.com/sst/opencode/pull/305 commentary; aider docs]. Newer catalog:
  `claude-sonnet-4.x`, `gpt-5-mini`, `grok-code-fast-1`, `raptor-mini`, `gpt-5.4`, etc.
  [COMMUNITY: docs.github.com model names + aider code path; exact current id strings on any
  dated day must be fetched live — UNVERIFIED until you call `/models` with a session token].
- Object carries per-model `capabilities.supports.tool_calls` / `.streaming` / `.vision`,
  `capabilities.limits.{max_output_tokens,max_prompt_tokens,max_context_window_tokens}`,
  `billing.{is_premium,multiplier}`, `model_picker_enabled`, `supported_endpoints`
  (`/chat/completions` vs `/responses`) [COMMUNITY: codecompanion get_models.lua].
- Plan gating (ids themselves are listed per plan in the UI; `/models` returns yours):
  [OFFICIAL: https://docs.github.com/copilot/reference/ai-models/supported-models]
  - **Free & Student: models available through AUTO model selection only** (no explicit picker).
  - Pro: "a selection of models". Pro+: "Access to premium models". Max: "Priority access
    to premium models" [OFFICIAL: docs.github.com/en/copilot/get-started/plans].
  - Current marquee models (2026-10): GPT-5.x/6.x (Luna/Sol/Terra/Astra/6.1 Sol), Claude
    Fable 5/5.1, Claude Opus 4.8/5/5.5, Claude Sonnet 4.6/5/5.5, Claude Haiku 4.5/5.5,
    Gemini 3.7/3.8 Flash, Gemini 4 Argon, MAI-Code-1.1-Flash, Kimi K2.7/K3, Grok 4.5–4.7
    [OFFICIAL: supported-models page].
  - Free-plan marketing lists overlap Pro's list; the docs note is authoritative that
    Free users are auto-model-only [OFFICIAL: supported-models note; UNVERIFIED whether `/models`
    for a Free account returns pickable ids honored by chat/completions].

---

## 4. Entitlements: plans, costs, tokens

### 4.1 Plans & pricing (2026-10, all OFFICIAL)

- Price table [OFFICIAL: https://docs.github.com/en/copilot/get-started/plans]:
  Free $0 · Student $0 · **Pro $10** · **Pro+ $39** · **Max $100** · Business **$19/seat** ·
  Enterprise **$39/seat**.
- **GitHub AI Credits regime (replaces premium requests from 2026-06-01)** [OFFICIAL:
  https://github.blog/news-insights/company-news/github-copilot-is-moving-to-usage-based-billing/]:
  > "all GitHub Copilot plans will transition to usage-based billing on June 1, 2026…
  > every Copilot plan will include a monthly allotment of GitHub AI Credits… Usage will be
  > calculated based on token consumption… using the listed API rates for each model."
  - 1 AI credit = $0.01 [OFFICIAL: plans page FAQ].
  - Allowances (docs table) [OFFICIAL: plans + usage-based-billing page]:
    Pro: $10 base + $5 flex = 1,500 credits/mo · Pro+: $39+31 = 7,000 · Max: $100+100 = 20,000 ·
    Business: 1,900/user · Enterprise: 3,900/user. **Free: "an allowance of GitHub AI
    Credits" — exact number NOT published on any page we could fetch [UNVERIFIED].**
  - Per-token rates: "Models and pricing for GitHub Copilot" `docs.github.com/en/copilot/reference/copilot-billing/models-and-pricing` [OFFICIAL].
- **Legacy premium-request numbers** (still useful as pre-June baseline and for planning):
  Free 50/mo (all chat counts as premium), Student 300, Pro 300, Pro+ 1,500, Business
  300/user, Enterprise 1,000/user [OFFICIAL:
  https://docs.github.com/en/enterprise-cloud@latest/copilot/about-github-copilot/plans-for-github-copilot;
  OFFICIAL: docs.github.com/en/copilot/reference/copilot-billing/request-based-billing-legacy/copilot-requests].
  Free extra-purchase is not available (Free cannot buy additional) [OFFICIAL: copilot-requests page].
- **Free plan exists** (GitHub Copilot Free) and per plans FAQ remains "limited to 2000
  completions and 50 chat requests" era language; the AI-credit page says Free includes 2,000
  code completions/month [OFFICIAL: features/plans FAQ + docs.github.com/en/copilot/concepts/billing-and-usage/individuals/billing].

### 4.2 Token mechanics / refresh

- Long-lived `gho_` OAuth token (device flow) → short-lived Copilot session token ≈30 min →
  `GET copilot_internal/v2/token` refresh with the same Bearer + headers. NOT a standard
  `grant_type=refresh_token` — Copilot-specific branch required [COMMUNITY: llm-liberty; pi repo
  https://github.com/earendil-works/pi/blob/main/packages/ai/src/auth/oauth/github-copilot.ts].
- Device code TTL ≈15 min, poll `interval` seconds, honor `slow_down` (+5s). [COMMUNITY:
  Alorse README + pi implementation; matches Crush's implementation
  internal/oauth/copilot/oauth.go].
- Revocation: Setting>Authorized OAuth Apps>Revoke kills the `gho_` grant [OFFICIAL: auth page].
- `copilot_internal/user` exists to fetch user/plan info [COMMUNITY: tokn-provider-copilot].

---

## 5. Rate limits / quotas per plan

- **Quotas per plan** = as in §4.1 (credits today, premium-request legacy numbers). All chat
  interactions counted on Free's legacy 50/mo [OFFICIAL: copilot-requests page].
- **Rate limits in RPM** ("Rate limits restrict requests within a specific time period",
  "containers may see rate limiting… during high demand") are *referenced* by many billing
  pages, but the actual numbers page is currently NOT reachable at the URLs tried
  (`/copilot/reference/rate-limits`, `/copilot/reference/usage-limits` → 404).
  The referenced title "Usage limits for GitHub Copilot" exists but we did not capture its
  current URL. **Exact RPM/per-plan HTTP limits: UNVERIFIED on 2026-10-17.**
- Model multipliers (for budget math): 0.33× (Haiku 4.5, Gemini 3 Flash, GPT-5.4 mini),
  0.25× (Grok Code Fast 1), 1× (Sonnet, Gemini 2.5/3.1 Pro, GPT-5.x non-mini), 3× (Opus 4.5/4.6),
  30× (Opus 4.6 fast mode), 0× (GPT-4.1/4o, GPT-5 mini, Raptor mini) on paid plans; Free = flat
  1 per interaction model. Auto-mode benefits get a 10% multiplier discount, not Free
  [OFFICIAL: docs.github.com/en/copilot/concepts/billing/copilot-requests].
- Anthropic/OpenAI-comparison: not applicable — Copilot is its own provider catalog.

---

## 6. Crush / phone-app specifics (the part we act on)

### 6.1 What Crush 0.97.1 already does (source: its own code)

Crush **already implements the whole Copilot OAuth** — no new engine code needed:
- `clientID = "Iv1.b507a08c87ecfe98"`; `POST https://github.com/login/device/code`
  (scope `read:user`) → `{device_code, user_code, verification_uri=https://github.com/login/device,
  expires_in≈900, interval}`.
- Polls `POST https://github.com/login/oauth/access_token` with
  `grant_type=urn:ietf:params:oauth:grant-type:device_code`, handles
  `authorization_pending`/`slow_down`.
- On success immediatelyExchange: `GET https://api.github.com/copilot_internal/v2/token` with the
  `gho_` token + Copilot headers → stores `{access_token=session token, refresh_token=gho_,
  expires_at}` [SOURCE: https://github.com/charmbracelet/crush/blob/main/internal/oauth/copilot/oauth.go
  and urls.go; OFFICIAL-of-GitHub not applicable — it's CRUSH's implementation].
- Per-request transport injects `X-Initiator: user|agent` based on body regex, protects against
  missing headers (`http.RoundTrip` wrapper) [SOURCE: …/internal/oauth/copilot/client.go].
- Refresh path exists in engine: `RefreshToken(githubToken)` at `/config/refresh-oauth` (the
  phone surface). All the phone app needs is to present the device code in a browser and, on
  completion, tell Crush to store/refresh.
- Signup helpers embedded in engine: `https://github.com/github-copilot/signup?editor=crush`
  and free-access doc link `docs.github.com/en/copilot/how-tos/manage-your-account/get-free-access-to-copilot-pro`
  [SOURCE: urls.go]. (That second URL is a *promo*; don't rely on it — historically "free
  Copilot Pro" promos existed while Pro+ was being spammed.)

### 6.2 What "the phone IMPL" must do (our app)

Assuming Catwalk provider id `copilot` + Crush exposes `/config/refresh-oauth` (per project
state). In order:
1. On user picking Copilot, app asks Crush to initiate device flow (or performs the two HTTP
   calls itself from Kotlin — CORS does not bind native code).
2. Show `user_code` prominently AND open **Android Custom Tabs** to `verification_uri`
   (`https://github.com/login/device`). **No loopback/callback needed — there is no
   redirect_uri in this flow** [COMMUNITY: llm-liberty "No localhost callback… no port that
   needs to be free"]. User authenticates in browser (fingerpin/PIN on this phone) and presses
   Authorize — the whole flow completes inside the phone. ✅ feasible.
3. Poll `access_token` respecting `interval` + `slow_down`; on success exchange for session
   token; persist BOTH (`refresh_token` = `gho_`, `access_token` = session) in the app's
   keystore-backed storage (never filesDir-copyable, never logs).
4. Drive refresh via `/config/refresh-oauth` (or re-exchange natively) BEFORE expiry — mock
   25-min tick. The refresh response carries `endpoints.api` → **use THAT host** for
   `/models` and `/chat/completions` (per-plan/enterprise aware) [COMMUNITY: litellm].
5. Always send `Copilot-Integration-Id`, editor headers, `X-Initiator` (agent when history has
   assistant), `X-GitHub-Api-Version` — else expect `403 token not authorized for this
   integration`.
6. Budget: session token ≈30 min ≫ needed; watch `finish` reason / HTTP 403 "exceeded your
   monthly allowance" — model fallback needed for Free users.
7. **Do NOT assume api.githubcopilot.com** — a Copilot org/enterprise user on this phone may
   be routed elsewhere (business/enterprise data-residency host).

---

## 7. Streaming + tool calls on `/chat/completions`

- **Streaming (SSE)**: `stream: true` is the DEFAULT posture of the endpoint family; each
  `data:` JSON line carries `choices[0].delta.content`, ended by `data: [DONE]`; **Copilot
  Enterprise tokens REQUIRE stream:true (non-streaming returns 400)** [COMMUNITY: llm-liberty
  doc + curl example; Alorse README ("Add stream: true… real-time streaming")]. Crush streams
  OpenAI-compat deltas fine.
- **Tool calls**: `/models` exposes `capabilities.supports.tool_calls` per model (codecompanion
  reads it to decide `can_use_tools`); the OpenAI `tools`/`tool_calls` wire-objects are consumed
  unchanged by the endpoint per multiple integrations [COMMUNITY: codecompanion; LiteLLM
  function-calling docs for `github_copilot`; copilot-api OpenAI-compat proxy claims]. No
  public GitHub doc enumerates the restricted subset of tool fields. [OFFICIAL: —]
- **Alternate endpoint**: some models report `supported_endpoints: ["/responses"]` (OpenAI
  Responses API shape) — codecompanion routes those to `/responses` instead of
  `/chat/completions`; Responses-API "alternate input types" (function_call, mcp_call,
  reasoning…) appear in opencode's copilot-auth plugin mapping [COMMUNITY: both].
- Vision needs `Copilot-Vision-Request: true`; not all plans/models [COMMUNITY].
- OpenAI-strict fields observed to differ: `openai-intent`/`Openai-Intent` header is used to
  signal "conversation-panel" vs "conversation-edits` (affects internal routing); `usage`
  object can be present but token math varies [COMMUNITY].

---

## 8. Dated verdict — could a **$0 tester** (Copilot Free) authenticate Copilot models from a phone app TODAY (2026-10-17)?

### Verdict: **YES for auth itself — with a real caveat on the model call and ToS posture.**

**Auth chain exactly as needed (fully community-proven, ends entirely inside the phone):**

```
[Kotlin/HTTP] POST github.com/login/device/code
   body: client_id=Iv1.b507a08c87ecfe98 & scope=read:user
   → {device_code, user_code, verification_uri=https://github.com/login/device, expires_in≈900, interval}
[Custom Tabs / Chrome] user opens verification_uri, logs in (PIN/fingerprint), enters code, "Authorize GitHub Copilot"
[Kotlin/HTTP] poll POST github.com/login/oauth/access_token
   body: client_id, device_code, grant_type=urn:ietf:params:oauth:grant-type:device_code
   until access_token (honor authorization_pending + slow_down)
[Kotlin/HTTP] GET https://api.github.com/copilot_internal/v2/token
   headers: Authorization: Bearer <gho_…> + x-github-api-version
            User-Agent GitHubCopilotChat/0.x, Editor-Version vscode/1.107.0,
            Editor-Plugin-Version copilot-chat/0.x, Copilot-Integration-Id vscode-chat
   → {token (tid=…;exp=…;proxy-ep=…), expires_at}   ← session token ≈30 min
[Engine → models] <endpoints.api from token>/chat/completions with same headers
   model: any Free-visible id, stream=true
```

- **$0 cost**: a GitHub account + Copilot Free entitlement is enough for the exchange
  (`403` if truly no active subscription — the subscription check is specifically this
  endpoint, llm-liberty).
- **Phone-complete**: device flow has NO redirect_uri; the Custom-Tab browser is where the
  human act happens. The four HTTP calls happen in native app code (no CORS applies).
  → today, a phone user can complete `/login/device` + return to app. Crush-with-engine path
  identical, minus step-2 dispatcher being user-visible only once (vs repeated).
- **The honest risk side** (don't skip this in the product doc):
  1. **Not-on-the-contract API.** `api.githubcopilot.com` is an *undocumented-for-third-
     parties* endpoint; the officially blessed in-app embedding is the Copilot SDK (which we
     can't run inside libcrush.so today). Emulating `vscode-chat` client id works for 6+
     major tools but is **revocable without notice** [corvus comment; copilot-api warning].
  2. **ToS posture for individuals = GitHub ToS §J + Acceptable Use.** Nothing found that says
     "your tooling is illegal"; but "work around technical limitations in a Product or
     restrictions in Product documentation" (General Terms §1.12(d)) is the realistic hook —
     combined with "may trigger abuse-detection systems" (GitHub's own warning in
     copilot-api). Automated bulk use is exactly our phone-agent pattern — throttle it.
     Budget math is unforgiving: Free's legacy 50 chat/mo with no purchase option means a
     single 20-turn agent session can drain the month — expect 403/429 mid-session.
  3. **Free plan model restriction**: docs says Free is AUTO-selection only — hard-coding
     "claude-…" against a Free account may 403 or silently downgrade (worth a follow-up test,
     UNVERIFIED). Safest: call `/models`, pick `model_picker_enabled` ids, honor
     `billing.multiplier=…`/`is_premium` for Free=1.
  4. **Annual-plan migration**: today (Oct 2026) legacy annual Pro/Pro+ users still on
     request-based billing persist; a session may belong to either regime & multiplier — rely
     on `/models` `billing`, not hand tables [OFFICIAL: github.blog 2026-04-27].
  5. **Data/training**: individual subscriptions' prompts may be used by GitHub for training
     unless opt-out (settings→features) [OFFICIAL: ToS §J.3 + 2026-03-25 changelog]. A consent
     note is warranted in-app.
- **Recommendation for our app**: ship the flow, keep the vscode-chat emulation behind a
  "experimental provider" label, default provider still Zen; hard-fail gracefully to picker
  when `/models` denies; prefer `endpoints.api` host always; never log the `tid=…` session
  or `gho_…` (state Already documented in our security rules).

**Roadmap alternative (if GitHub ever denies the emulation path):** no official HTTP-only
alternative for a non-JVM in-process engine today; the sanctioned CLI binary could be embedded,
but the official `copilot` CLI ships Linux/macOS/Windows only — **no Android/arm64 build
[UNVERIFIED]**, so the SDK-over-CLI route is unusable on-device today. Revisit if GitHub
publishes a mobile/Android SDK surface.

Label every claim above carries a citation; anything tagged UNVERIFIED needs a hands-on
verification sprint before we label the provider "supported" in the app.
