# Charm Hyper (Catwalk id: `hyper`) — deep research findings

> Research date: **2026-10-17** (browser probes performed 2026-10-16/17 UTC).
> Methods: live webfetch of official docs (`hyper.charm.land/docs/llms.txt` mirror),
> Playwright against the live SPA (landing, signup, FAQ, terms), live API probes
> via curl (`/v1/models`, `/v1/credits`, `/v1/provider`), and raw source reads at
> tag `v0.97.1` of `charmbracelet/crush`.
> App context: Crush Mobile embeds **Crush 0.97.1**; Crush registers `hyper`
> reading `HYPER_API_KEY`; base `https://hyper.charm.land/v1`; OpenAI-compatible.
>
> Legend: (source: URL) = verified against that URL today. **UNVERIFIED** = could
> not be confirmed against a primary source (needs a real account or server
> access).

---

## 1. What Hyper is; official URLs

- Charm Hyper is Charmbracelet, Inc.'s (Brooklyn NY) **inference service for
  agentic coding**, open-weight models served at full precision, positioned as
  "fast, cost-effective inference built for agentic coding… Most people are up
  and running in under two minutes." (source:
  https://hyper.charm.land/docs , https://hyper.charm.land/ )
- Focus claims: no quantized/trimmed variants, no model substitution ("The model
  you choose is the model you get"), optimized caching, hand-picked coding-only
  catalog, no training on your data by default, 30-day max retention, GDPR.
  (source: https://hyper.charm.land/docs , https://hyper.charm.land/ )
- **Prism**: Charm's own 8B-parameter "decision model" router — reads the prompt,
  routes to the cheapest suitable Hyper model; claims ~45% cost savings; "All
  Hyper users have access to Prism." Charm trains Prism on "real sessions". (source: https://hyper.charm.land/ ,
  https://hyper.charm.land/faq )
- Official URLs:
  - Landing: https://hyper.charm.land (source: link family above)
  - Docs: https://hyper.charm.land/docs/ + LLM bundles
    https://hyper.charm.land/docs/llms.txt and
    https://hyper.charm.land/docs/llms-full.txt (source:
    https://hyper.charm.land/docs/llms.txt )
  - FAQ: https://hyper.charm.land/faq (source: rendered page today)
  - Signup: https://hyper.charm.land/signup — Log in: `…/login` (source:
    landing nav links)
  - Dashboard sections: `/settings`, Plans & Billing, API Keys, Teams; device
    activation page ("Activate Device") (source: web-app JS bundle
    `/app/assets/index-Ch2bbGQa.js` served today; https://hyper.charm.land/docs/settings.md )
  - Models page: https://hyper.charm.land/models (linked from credits doc)
  - Legal: `…/legal/terms-of-service`, `…/legal/privacy-policy`, `…/refunds`
    (footer), also `…/terms`, `…/privacy` per FAQ footer (source: page footers
    today)
  - Support: `hypersupport@charm.land`, `enterprise@charm.land`, +1 (833)
    CHARM-88; `#hyper` on the Charm Discord (source: FAQ + landing today)
- **No public "console" URL beyond the dashboard** (dashboard is the SPA at
  hyper.charm.land itself). (source: docs + nav today)
- The v0.65.2 Crush release (2026-05-01) announced Hyper first-class support,
  described it as "our inference service for coding focused on price,
  performance, and privacy", "currently in private beta … 100 free monthly
  Hypercredits". That copy is now stale (see §3). (source:
  https://newreleases.io/project/github/charmbracelet/crush/release/v0.65.2 )

## 2. Signup TODAY — walk-through from a fresh browser (live-probed)

Verified step-by-step today with a dummy email (`@example.invalid`, never
verified, then abandoned):

1. `https://hyper.charm.land/` → nav has plain **"Sign up"** link (no
   waitlist/join-list wording anywhere on the landing page today). Pricing
   section direct CTAs: every plan card links to `/signup` ("Get Started"),
   PAYG card = "Add Credits" → `/signup`. (source: rendered landing today)
2. `https://hyper.charm.land/signup` renders: **"Continue with Google"**,
   **"Continue with GitHub"**, "or" a Name / Email / Password form with a
   **Sign Up** button. The OAuth buttons are links to
   `/oauth/google/signin?cf_turnstile_response=…` and
   `/oauth/github/signin?cf_turnstile_response=…`, i.e. **Cloudflare Turnstile**
   is generated per page load. (source: rendered signup + snapshot today; SPA
   bundle contains `turnstile_site_key` in `/auth/me` state)
3. Email form **submits without any queue/confirmation-traffic page**: POST
   goes to `/auth/register` (endpoint name from bundle) and the app immediately
   redirects to **`/verify-email-sent`**: *"Check your email — We sent a
   verification link to … Click the link to verify your account."* (source:
   live submit probe today)
4. **Post-verification state**: the live bundle contains a dedicated
   `/waitlist` route (auth status `waitlisted`) with the copy:
   *"You're on the waitlist."* / *"Thanks for signing up. Your account is
   pending approval. We'll let you know when it's been activated."* plus a
   Sign out button. Session state (`/auth/me`) carries an explicit boolean
   **`waitlisted`** flag (default false) alongside `authenticated`. (source:
   `/app/assets/index-Ch2bbGQa.js` — route table case 25 (`waitlist`), title
   `Waitlist`, auth-status switch `case 'waitlisted': … '/waitlist'`, page copy
   string)
   → A verified-but-unapproved account is parked on a **pending-approval**
   screen, not a token-issuing dashboard. There is **no visible queue position
   or ETA**, only the copy above. (source: same bundle; **UNVERIFIED**: actual
   approval latency / batch cadence today, and whether *any* current signup
   path bypasses the flag)
5. Calls-to-action on the landing page say "Let's Shred Some Tokens" (scrolls
   to pricing); no request-coalescing/queuebuster page exists pre-auth.
   (source: landing render today)

Corroborating (non-official) evidence the approval gate still bites:
- Charm's own Discord reply, 2026-08-05: *"Hyper is still waitlisted, but we
  let more users in periodically."* (source:
  https://www.reddit.com/r/charmland/comments/1vg29h0/hyper_subscription_and_usage/ )
- CodexBar maintainer "Peter is on the Charm Hyper **waitlist**, so we can't
  verify either implementation against a live account just yet" (issue thread,
  active Oct 2026). (source:
  https://github.com/steipete/CodexBar/issues/2318 )

## 3. Doc conflicts — the free-credit offer is RETIRED in some docs only

Where the *"100 free Hypercredits per month"* promise lives and its current
state, side by side (all fetched today):

| Source | Says |
|---|---|
| FAQ (rendered today) | **"Where's the free plan?" → "We've retired the monthly free Hypercredit allowance."** — use prepaid from $5 instead |
| Landing pricing (today) | Plans start at **$10/mo (Shred Diet)**; **PAYG "Start with five bucks"**. **No free tier shown.** |
| `docs/api/credits.md` | **STILL promises: "Every user gets 100 Hypercredits per month for free."** |
| `docs/plans-billing.md` | **"Every new Hyper account starts on the free plan with 100 hypercredits that refresh monthly."** |
| `docs/dashboard.md` | **"By default every new user starts on the free plan with 100 hypercredits refreshing monthly."** |
| Crush v0.65.2 release (May 2026) | "Beta users get 100 free monthly Hypercredits" |

**Explicit discrepancy**: the **FAQ + landing (newest, live product surface)
say the free monthly allowance is retired; three docs pages + the old release
note still advertise it.** The FAQ is the one that documents the *current*
business model (Shred/Business subscriptions + prepaid). No statement of the
date the free tier ended is published; the only dated privacy note is
*"nothing submitted before October 15, 2026 will be used for training"* (FAQ).
(source: all URLs above; docs team has not propagated the change — flagged)
**UNVERIFIED**: whether older legacy accounts still get their 100/month grant
(FAQ says legacy "original Shred plan" subscriptions run out their cycle, then
convert to PAYG).

Related freshness drift inside the same docs:
- `docs/models.md` cites "GLM 5.1" as attachment-capable while the Live catalog
  has GLM 5.2/5.3 only. (source: https://hyper.charm.land/docs/models.md vs
  live `/v1/models` probe today)
- Landing page has typos ("Hyprecredits will over time", "avilable").
  (source: rendered landing today)

## 4. API surface (long-sink: verbatim error tables)

Base: `https://hyper.charm.land/v1` (OpenAI-compatible);
Dashboards proxy path: catwalk `api_endpoint` = `https://hyper.charm.land/api/v1/fantasy`
(Crush's fantasy provider JSON; (source: live `GET /v1/provider` &&
`internal/agent/hyper/provider.json` @ v0.97.1)).

Endpoints (source: https://hyper.charm.land/docs/llms-full.txt ):
- `GET /v1/models` — OpenAI list, **no auth required.** Rich extras:
  `display_name`, `context_window`, `max_output_tokens`,
  `capabilities.vision`, `reasoning.effort_levels` +
  `reasoning.default_effort_level`, `pricing{input,output,cache_create,cache_hit}`
  in USD per 1M tokens.
- `POST /v1/chat/completions` — standard OpenAI params incl. `temperature`,
  `max_tokens`, **`tools`** (tool-calls accepted), `stream`, `top_p`, `stop`
  fields; passes through to upstream. SSE streaming: each chunk = `data:`
  line with `chat.completion.chunk`; ends `data: [DONE]`. Custom usage on both
  normal + streaming responses (final chunk when
  `stream_options.include_usage`):
  `usage.cost.usd`, `usage.cost.hypercredits`, `usage.remaining.hypercredits`.
- `POST /v1/responses` — OpenAI **Responses API** standard pass-through
  (streaming standardized).
- `POST /v1/messages` — **Anthropic Messages** compatible:
  header `anthropic-version: 2023-06-01` plus standard fields; **also accepts
  `X-Api-Key: sk-hyper-…`** for Claude Code compatibility.
- `GET /v1/credits` — team balance; body `{"balance": 100}` (int); supports
  display-disabled teams which return dollars instead of credit figures
  (balance field then null; see §8).
- `GET /v1/provider` — serves the same catwalk provider JSON Live-updated.
- Fantasy SDK (Go) documented against `https://hyper.charm.land/v1`.
  (source: docs/api/fantasy.md)

Auth header: `Authorization: Bearer sk-hyper-xxxxxxxxxxxxxxxxxxxxxxxx`;
key prefix **`sk-hyper-`**; keys minted from the dashboard API Keys page.
(source: authentication.md ; settings docs)

Error tables — verbatim:
- Definition: (source: authentication.md):
  `{"error":{"message":"invalid api key","type":"invalid_request_error","code":null}}`
  on 401 for `/v1/models`-family; `/v1/messages` instead returns
  `{"type":"error","error":{"type":"authentication_error","message":"invalid api key"}}`.
- Chat Completions + Responses (source: openai-chat-completions.md /
  openai-responses.md):
  | Status | `error.type` | Cause |
  |---|---|---|
  | 400 | `invalid_request_error` | bad JSON, missing model/messages, context window exceeded |
  | 401 | `authentication_error` | missing/invalid key |
  | 402 | `billing_error` | insufficient Hypercredits |
  | 403 | `permission_error` | forbidden |
  | 404 | `invalid_request_error` | model not found |
  | 429 | `rate_limit_error` | rate limit exceeded |
  | 5xx | `server_error` | upstream provider failure |
- Anthropic Messages (source: anthropic-messages.md): 401 `authentication_error`,
  404 `not_found_error`, 429 `rate_limit_error`, 5xx `api_error`,
  400 `invalid_request_error`, 403 `permission_error`.
- Credits (source: credits.md): 401 `authentication_error`, 5xx `server_error`.

Live probes today (source: curl run 2026-10-17):
- `GET /v1/models` → `200`, **22 models**, including `prism`,
  deepseek-v4.1-flash / -v4-pro-0813 / -v4-flash-0731, kimi-k3 / k2.7-code /
  k2-thinking, glm-5.3 / -flash / 5.2, minimax-m3 / m2.7, inkling, qwen3.8-max
  / -flash / -27b / -2.4t-a95b, qwen3.7-max / -plus / -flash, gpt-oss-120b,
  gemma-4-26b-a4b-it.
- `GET /v1/credits` without auth → `401`
  `{"error":{"message":"missing authorization","type":"authentication_error","code":null}}`.

Catalog drift to know: Crush 0.97.1 embeds a catwalk JSON containing
`deepseek-v4-flash` and no `prism`; the live service today serves no
`deepseek-v4-flash` (only `-0731` and `-v4.1-flash`) and added `prism`. This is
the *exact same failure mode as Zen's previously-retired `deepseek-v4-flash-free`*: when Crush
resolved a stale embedded model id, calls failed "Model is unavailable"-class errors.
(source: dynamic comparison live `/v1/models` &&
`internal/agent/hyper/provider.json` @ v0.97.1)

## 5. Device-code / OAuth flow (from Crush source, verbatim code)

Crush's Hyper login is a browser-device flow: (source:
`https://github.com/charmbracelet/crush/blob/v0.97.1/internal/oauth/hyper/device.go`
and `/internal/login/login.go`; mirrored at Hyper docs
`/docs/coding-agents/crush.md`: "Crush opens a browser tab to
hyper.charm.land… copy the device code back into Crush.")

Endpoints on cr-account base (`hyper.BaseURL()` = `$HYPER_URL` or
`https://hyper.charm.land`, (source: `internal/agent/hyper/provider.go`)):
1. `POST /device/auth` body `{"device_name":"Crush (<hostname>)"}` →
   `{"device_code","user_code","verification_url","expires_in"}`.
2. Poll `GET /device/auth/<device_code>` **every 5 s**; response carries
   `{refresh_token?, user_id, organization_id, organization_name}` or
   `{"error":"authorization_pending"|…}`; aborts on other errors; returns
   refresh token when set.
3. `POST /token/exchange` `{"refresh_token": …}` → access token
   (`oauth.Token`, expiry stamped server-side).
4. `POST /token/introspect` — RFC 7662 `{"token": …}` →
   `{active, sub, org_id, exp, iat, iss, jti}`; login requires `active=true`.
(web-app side exposes `/device/authed/deny|verify|approve` for dashboard
approval; (source: SPA bundle today)).

Overview: the docs don't condition login on holding Hypercredits or a plan —
login is just "log in (or sign up) and copy the device code back into Crush."
(source: https://hyper.charm.land/docs/coding-agents/crush.md ). **UNVERIFIED**:
whether a wait-listed unapproved account can complete the device
approval (couldn't be tested without a genuine user), or whether the flow
requires an active plan/credits to mint tokens.

Also note: **`crush login hyper`** is exported as an official CLI flow:
`crush` supports `Run(ctx, "hyper")`.
(source: `/internal/login/login.go` @ v0.97.1)

## 6. Credits: pricing

- **1 Hypercredit = $0.05** ("One Hypercredit is worth 5¢"). (source:
  https://hyper.charm.land/faq ; https://hyper.charm.land/docs/models.md "1
  hypercredit equating to $0.05")
- Pricing is per-model per-1M-token in USD; `/v1/models` lists live pricing.
  Live sample (2026-10-17, in/out, USD per 1M tokens):
  - `deepseek-v4.1-flash` **$0.33 / $1.31**, cache-read $0.03, vision ✓
  - `glm-5.3-flash` **$0.16332 / $0.5444**, cache-read $0.031575
  - `kimi-k3` **$3.2664 / $16.332**, cache-read $0.32664
  - `prism` **$3.2664 / $16.332** (same headway as kimi-k3 — router premium)
- Usage extensions: every response carries `usage.cost.usd` /
  `usage.cost.hypercredits` / `usage.remaining.hypercredits` so the client can
  display your per-request spend and Live-updated team balance
  (source: openai-chat-completions.md): exact fields replayed in Crush:
  "Hypercredits are now displayed in real time!" went in with release v0.96.0
  (source: https://github.com/charmbracelet/crush/releases/tag/v0.96.0 )
- Prism routing claims ~45% savings vs manual model choice (marketing-level,
  source: landing).
- Bundles: one-time purchases that don't expire, stack on plan balance.
  (source: plans-billing.md)

## 7. Rate limits / quotas (all documented, none server-verified)

From FAQ + landing pricing page (the complete table):
- **Rolling 5-hour limit** per plan: how many Hypercredits may be consumed
  within any 5-hour window; "no resets every five hours … as the window moves,
  earlier usage falls outside it". Diet 100 / Classic 150 / Energy 250 / Nitro
  400 / Animal 700 HC per 5 h.
- **Weekly Hypercredit allowance** refreshes every **Sunday 00:00 UTC**; does
  not roll over. Diet 180 / Classic 250 / Energy 400 / Nitro 690 / Animal 1200.
- **Concurrent requests**: Diet 2 / Classic 3 / Energy 4 / Nitro 8 / Animal 12.
  **PAYG/prepaid: reported "subject to concurrency limits … Go crazy"** —
  the FAQ says prepaid has *no* concurrency limit ("aren't subject to
  concurrency limits").
- Paid plans: Shred = individuals, Business (ZDR) = teams. "Bigger plans give
  more of all three."
- API-level: only a `429 rate_limit_error` is documented; **no published
  numeric RPM/TPM** for raw API use. (source: error tables; **UNVERIFIED**
  numbers beyond the plan table)
- Monthly Hypercredit grants for free users: **retired** per FAQ §3 (docs
  elsewhere say 100/month — see conflict section).

(source: https://hyper.charm.land/faq rendered today;
https://hyper.charm.land/ rendered pricing today)

## 8. The Catwalk / Crush side (source reading @ v0.97.1)

Registration locations (all at https://github.com/charmbracelet/crush/tree/v0.97.1
— SHA `5ac3cffe…`):
- **`internal/agent/hyper/provider.json`** — embeds the catwalk provider:
  `{ "name": "Charm Hyper", "id": "hyper", "api_endpoint":
  "https://hyper.charm.land/api/v1/fantasy", "type": "hyper",
  "default_large_model_id": "qwen3.8-max",
  "default_small_model_id": "deepseek-v4.1-flash", "models": [23 entries] }`.
  Refreshed via `//go:generate wget -O provider.json
  https://hyper.charm.land/v1/provider`. (source: raw file)
- **`internal/agent/hyper/provider.go`** — `Package hyper` exposes:
  `Name = "hyper"`, `DisplayName = "Charm Hyper"`, `BaseURL()` =
  `$HYPER_URL |> https://hyper.charm.land`; `HYPER_URL` overrides the API base
  (appended `/api/v1/fantasy`); **prism headers surfaced into provider
  metadata**: `X-Prism-Model-Id`, `X-Prism-Model-Name`,
  `X-Prism-Hypercredit-Savings`, `X-Prism-Dollar-Savings` (the savings variants
  arrive as HTTP **trailers** on streaming turns); plus `FetchCredits()` →
  `GET {BaseURL}/v1/credits`, parsing `{"balance": int|null}` (null ⇒ credits
  display disabled, server reports dollars instead) with a process-wide
  `Balance()` getter. (source: raw file)
- **`internal/config/load.go`** — the switch you asked about, verbatim:
  ```go
  case catwalk.InferenceProvider("hyper"):
      if apiKey := env.Get("HYPER_API_KEY"); apiKey != "" {
          prepared.APIKey = apiKey
          prepared.APIKeyTemplate = apiKey
      } else {
          v, err := resolver.ResolveValue(p.APIKey)
          if v == "" || err != nil {
              if configExists {
                  slog.Warn("Skipping Hyper provider due to missing API key", …)
                  c.Providers.Del(string(p.ID))
              }
              continue
          }
      }
  ```
  i.e. `HYPER_API_KEY` wins first; else the value templated into the provider
  (which is the **OAuth-maintained token**: `Load()` wires a Hyper token
  refresher for configured providers, `store.RefreshOAuthToken(ctx,
  ScopeGlobal, "hyper")`, retried on catalog-401). No key anywhere ⇒ the
  provider is *skipped* at startup.
  (source: `raw.githubusercontent.com/charmbracelet/crush/v0.97.1/internal/config/load.go`
  line-level)
- Catalog fetch: `defaultCatwalkURL = "https://catwalk.charm.land"` — hyper's
  embedded provider keeps its own definition independent of that store.
  (source: same file)
- Test-auth env: `CRUSH_HYPER_API_KEY` in `internal/agent/.env.sample`.
  (source: file)
- README table: `HYPER_API_KEY` mapped to `[Charm Hyper]`.
  (source: repo README @ main)

App-relevant wrap-up for our engine:
- The **embedded libcrush 0.97.1 binary** thus: registers `hyper` on
  boot (embedded provider.json, id `hyper`), pulls `HYPER_API_KEY` from env
  (else OAuth-refresh credential), defaults on `qwen3.8-max` /
  `deepseek-v4.1-flash`, and targets `https://hyper.charm.land/v1` for
  OpenAI-compat chat.
- Real drift risk: the Live model set now includes `prism` and no longer lists
  `deepseek-v4-flash` — beware re-embedding a fallback.
- Because catalog fetch happens at boot, the omitempty + auto-refresh path via
  `/v1/provider` or `/v1/models` is the sane live-refresh source.

## 9. Dated verdict — can a $0 tester get a working `sk-hyper-` key today?

**2026-10-17 verdict: NO — not as a first-run, free path.**

Exact signup path (probed today from a fresh browser):
1. https://hyper.charm.land → "Sign up" → form (Google / GitHub / email) —
   *no* in-line waitlist at this step.
2. Submit → instant redirect to `/verify-email-sent`; you must click the
   emailed verification link.
3. On the Live web app, the route banner for unapproved accounts is
   `/waitlist`, with verbatim copy: *"You're on the waitlist."* / *"Thanks for
   signing up. Your account is pending approval. We'll let you know when it's
   been activated."* `authenticated=false` + `waitlisted=true` from `/auth/me`
   keeps you logged in but unbilled.
4. Charm's own outreach since May 2026 has kept saying approval comes in
   periodic batches: *"Hyper is still waitlisted, but we let more users in
   periodically."* (2026-08-05) and, more recently, a prominent Issues tracker
   contributor is still stuck on the waitlist in October 2026 — **UNVERIFIED**
   from Charm's own pages.

**The current blocker**: account-approval. Everything from a key mint
(`/api-keys/create`) to any inference would be fired from a web session that
only exists after approval. Even once approved, the **free monthly allowance
has been retired** (FAQ §3), so a $0 tester can no longer assume free monthly
credits — they must purchase **PAYG prepaid from $5** (ZDR, no concurrency
limit) or a subscription (Shred from **$10/mo** weekly allowance). So $0
today works at exactly $0 **only if** Charm periodically waves
someone through the waitlist queue; otherwise the smallest spendable entry is
$5 (PAYG) or $10/mo (Shred).

**First-hand check (2026-10-17, this project's user):** a waitlisted account
came back **approved** — and the approved account shows **no free tier and no
free credits** of any kind. This confirms the FAQ's "retired the monthly free
Hypercredit allowance" with an observed account rather than docs alone, and
confirms that approval itself does not grant any spendable $0 balance.

**UNVERIFIED**: (a) actual approval generosity/latency today — no public
policy page covers it; the first-hand check shows approval does happen (one
account moved from waitlist to active the same month), but the cadence
remains undocumented; (b) whether a paid plan selection bypasses the waitlist
at signup; (c) whether legacy pre-2026-10-15 waitlisted accounts got an
automatic approval cadence based on day-of-signup.

**Bottom line for the app**: keep `HYPER_API_KEY` as the mobile non-OAuth
fallback (`api_key` inline config in the Crush engine), document a
plan/credit entry gate in onboarding messaging ("free waitlist + $5 prepaid"
rather than the stale docs' "100/month free"), and treat embedded catwalk
model IDs as cache — verify against Live `/v1/models`, which today doesn't
offer `deepseek-v4-flash` anymore (add a `prism`-routing summary too). For
full GetStarted UX parity, note the login page is a device-code support in the
web UI at `/device` ("Activate Device") for Crush sign-in.

---

### Probe notes (for reproducibility)

- Rendered landing: Playwright accessibility snapshot 2026-10-16T19:30Z,
  nav/heading list captured verbatim; zero matches for `waitlist|invite` on
  landing.
- Rendered signup: snapshot 2026-10-16T19:27Z (fields Name/Email/Password +
  OAuth links w/ `cf_turnstile_response`).
- Signup submit via dummy identity: navigated to `/verify-email-sent`
  2026-10-16T19:36Z. **No follow-up verification e-mail can be received**
  (`.invalid` TLD) — the probe deliberately stops there. The Left-on-server
  dangling account will effectively self-expire.
- Live bundle: `https://hyper.charm.land/app/assets/index-Ch2bbGQa.js`
  (~430 KB) fetched in page context; `waitlisted` flag + waitlist page copy
  extracted (see §2/§9).
- curl: `GET /v1/models` = 200 (22 models); `GET /v1/credits` unauth = 401
  verbatim error; `GET /v1/provider` = 200 (catwalk JSON incl. `prism`).
- Raw source reads at tag `v0.97.1` exactly as pinned in §8.
