# Provider research: Google Vertex AI (Catwalk id `vertexai`)

Research date: **2026-10-17**. Written for the Crush Mobile OpenAI-compat runtime.
Every claim is from an official Google Cloud doc unless marked UNVERIFIED.
Docs rebranded: Vertex AI is now "Gemini Enterprise Agent Platform"; old
`cloud.google.com/vertex-ai/...` URLs redirect (some 404, e.g. the old
`/vertex-ai/generative-ai/pricing_online` pricing page).

## 1. What Vertex AI is / what Catwalk `vertexai` means

Two different Google APIs sell Gemini models; Crush's Catwalk `vertexai` is the
**cloud-enterprise** one, not AI Studio.

| | Gemini API (AI Studio, Catwalk `gemini-api`) | Vertex AI (Catwalk `vertexai`) |
|---|---|---|
| Sales channel | Google AI Studio | Google Cloud |
| Endpoint | `generativelanguage.googleapis.com` | `aiplatform.googleapis.com` |
| Signup | Google Account only | Google Cloud account + billing (or express mode) |
| Auth | Static API key | Google Cloud service accounts / OAuth access tokens (or express API key) |
| Data | Free tier may train on prompts; paid tier never | **Never** used to improve Google products |
| Extras | Simple | Regional endpoints, MLOps, Model Garden, Claude/Llama/DeepSeek partner models, $300 trial credits |

Source: https://cloud.google.com/gemini-enterprise-agent-platform/models/migrate/migrate-google-ai (updated 2026-10-15).

Catwalk `vertexai` semantics (confirmed via LiteLLM provider docs, secondary
source): service-account JSON passed as credentials, OAuth scope
`https://www.googleapis.com/auth/cloud-platform`, project + location required,
base URL `{location}-aiplatform.googleapis.com`.

## 2. Auth flows

### 2a. Standard flow: service account + OAuth bearer token
- OpenAI-compat apps use the endpoint
  `https://aiplatform.googleapis.com/v1/projects/{PROJECT_ID}/locations/{LOCATION}/endpoints/openapi/chat/completions`
  with `Authorization: Bearer <OAuth2 access token>`.
- Access tokens live **~1 hour** and must be refreshed (ADC/google-auth handles
  this). Source: .../models/migrate/openai/auth-and-credentials.
- **Note the path segment is `endpoints/openapi`**, not `endpoints/openai`.
  `/v1beta1` is also valid. Source: .../models/start/openai (updated 2026-10-15).
- Only Google Cloud auth is supported on this endpoint (no API keys).
- Model IDs are prefixed `google/` (e.g. `google/gemini-3.5-flash`).
- Service-account key JSON contains a PEM `private_key`; your library signs a
  JWT with RS256 and swaps it at `https://oauth2.googleapis.com/token` for the
  access token.

### 2b. Express mode (API key, no GCP project needed)
- Signup at https://console.cloud.google.com/freetrial → API key issued
  immediately; **no billing info, no project, no org** required.
- Free tier for new GCP users: first **90 days**, model APIs free up to
  express quotas. Billing can be enabled any time to remove the 90-day limit
  and unlock higher quotas (pay-as-you-go from then on).
- Express endpoint (global, no project/location):
  `https://aiplatform.googleapis.com/v1/publishers/google/models/MODEL_ID:streamGenerateContent`
  with header `x-goog-api-key` or `?key=`.
- Express REST API is **Preview** (Pre-GA) status as of the reference page.
- 2026 status: **live and current** (docs updated 2026-10-15; rebranded from
  "Vertex AI in express mode" to "Agent Platform in express mode", same flows).
  Sources: .../models/start/express-mode/overview,
  .../reference/express-mode/api-reference.
- **Express mode supports the OpenAI SDK? NO — UNVERIFIED.** The express docs
  show the native `generateContent`/`streamGenerateContent` shapes only; the
  OpenAI-compat path is documented for the standard flow (service accounts).
  Treat express as native-REST-only until proven otherwise.

### 2c. IAM roles needed (standard flow)
- To call models: `google.cloud.aiplatform.user` (Vertex AI User) is the usual
  grant; generating tokens requires being able to act as the SA
  (`roles/iam.serviceAccountTokenCreator` only for impersonation/self-signing
  with dedicated keys not needed — a downloaded key self-signs).
- To create SA keys: `roles/iam.serviceAccountKeyAdmin` (or Owner on the
  project). Source: https://cloud.google.com/iam/docs/keys-create-delete.

## 3. Signup friction (fresh account, standard flow)

1. Google account → GCP project console (card only at billing enablement).
2. Enable billing: card required ($0–$1 temporary authorization for identity
   check, removed). Source: https://cloud.google.com/free/docs/free-cloud-features (updated 2026-10-13).
3. $300 trial credits, valid **90 days**; trial auto-closes at day 90 or when
   credit is spent (30-day grace period after).
4. Enable the `aiplatform.googleapis.com` API on the project.
5. Create service account + JSON key (keys page), grant `aiplatform.user`.
6. Region pick matters: non-`global` inference is priced +10%; region is
   embedded in every request URL.

## 4. Free tier

- **$300 / 90 days** trial credits usable on standard Vertex Gemini models.
- **Express mode free tier**: 90 days of free model API calls within express
  quotas (no billing info at all).
- UNVERIFIED/edge: whether trial credits apply equally to partner models
  (Claude etc.) — the free-features doc says trial credits are *not* usable for
  "Gemini API in AI Studio" or MaaS open models; Gemini-on-Vertex standard
  models are paid-credit eligible.
- Cannot request quota increases during the trial.

## 5. Models + OpenAI compatibility

- Current Gemini line on Vertex (2026-10): **Gemini 4 Argon**, 3.8/3.7/3.6
  Flash, 3.5 Flash(-Lite), 3.1 Pro Preview, 2.5 Pro/Flash/Flash-Lite; image
  Nano Banana family; Veo; TTS; embeddings. Sources: docs model index, pricing
  page.
- OpenAI-compat: `/v1/projects/{p}/locations/{l}/endpoints/openapi/chat/completions`,
  `stream=True` SSE supported, function calling + structured outputs supported.
  Sources: .../models/start/openai, .../models/migrate/openai/auth-and-credentials.
- Express mode: native `streamGenerateContent` SSE (OpenAI-compat endpoint not
  documented for express — UNVERIFIED, see 2b).
- Pricing page note: "Only requests with 200 responses are billed."

## 6. Pricing (per 1M tokens, global region) — pricing page updated ~2026-10

| Model | Input | Output |
|---|---|---|
| Gemini 4 Argon | $2.00 (promo via credits) → **$4.00 from 2026-12-01** | $10.00 → **$20.00** |
| Gemini 3.8/3.7/3.6 Flash | $0.75 intro → **$1.50 from 2027-01-01** | $3.75 → **$7.50** |
| Gemini 3.5 Flash | $1.50 | $9.00 |
| Gemini 3.5 Flash-Lite | $0.30 | $2.50 |
| Gemini 3.1 Flash-Lite | $0.25 | $1.50 |
| Gemini 3.1 Pro Preview | $2.00 (≤200k ctx) | $12.00; both double >200k |

Context caching: input reads ~90% off ($0.10–0.15/1M class). Non-global region
+10%. Grounding with Google Search: 5,000 queries/mo free then $14/1,000.
Source: https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing.

**Quotas (RPM/TPM):** no central defaults table anymore; per-model/per-region
`generateContent_requests_per_minute_per_project_per_base_model` metrics are
adjustable in Cloud Quotas (can't increase during trial). Embeddings have
fixed defaults (e.g. 100k requests/min). Express mode: ~10 RPM on 2.5-tier
models; 3.x models "Dynamic" quotas. Sources: .../models/quotas (updated
2026-10-15), express overview.

## 7. Service account JSON on a phone = big deal

Downloaded key shape (official):
```json
{ "type": "service_account", "project_id": "...", "private_key_id": "...",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n",
  "client_email": "sa@PROJECT.iam.gserviceaccount.com", "client_id": "...",
  "auth_uri": "...", "token_uri": "https://oauth2.googleapis.com/token", ... }
```
Implications for Crush:
- The `private_key` is an **RSA-2048 PEM** — it IS the raw RSA key. Anyone with
  the file is the service account until you rotate. Not a scoped API key.
- Rotating/revoking: create a new key, delete the old one (cannot download
  twice). Up to 10 keys per SA; keys never expire by default.
- Google explicitly says: keys are for workloads **outside Google Cloud**;
  prefer Workload Identity Federation elsewhere; keys aren't ideal for any
  client that can't peer Forge JWTs.
- **Crush requires RS256 self-signing for the JWT bearer flow.** libcrush
  (Go) would do `crypto/rsa`+`sha256`+oauth2 jws — feasible in Go, but the
  private key now lives in the app's SecureKeyStore the same as an
  OCR'able API key, except it's far more powerful (full SA perms) and
  revocation is slower than deleting an API key.
- **2026-08-27 warning in IAM docs:** Google will temporarily *restrict new
  access to the Gemini API from service accounts*; new integrations should use
  "authentication-enabled API keys." Applies to the Gemini API
  (`generativelanguage`), not yet shown for Vertex — but signals Google's
  direction away from raw SA keys for AI APIs.

## 8. Verdict (2026-10-17): can a $0 tester mesh Vertex into Crush in one evening?

**Speed:** fast path = **express mode** (API key instantly, no card, 10 RPM on
2.5 flash tier, native streamGenerateContent). The OpenAI endpoint (Dick our
app's shape) requires a standard-flow SA + Cloud billing ($300 trial, card)
plus JWT signing in-process. Verdict: express is 1-evening compatible only if
Crush speaks native Vertex REST (it speaks OpenAI chat completions today);
standard flow is also 1-evening feasible if you accept trial billing + RSA
private key handling — Rust `crush-providers` would need a JWT signer, which
is 30 min with ring/JWT crates.

**Difficulty: Express = trivial. Standard = medium JWT work** (sign JWT with
RS256 → exchange at oauth2.googleapis.com/token → auto-refresh on 401).
**Burn:** free (express ≤ quota; standard within $300/90-day trial).

| | Express mode | Standard (trial SA) |
|---|---|---|
| Time to first model reply | ~5 minutes (curl OK) | ~30 min (project, billing, API enable, SA key) |
| Crush OpenAI adapter | **Not available** (only native SSE shape) UNVERIFIED | Yes (OpenAI Bearer) |
| Card needed | No | Yes (identity check only) |
| Key stores in Crush as | API key | RSA PEM private key (whole-or-nothing SA) |
| Streaming | SSE native | SSE via OpenAI endpoint |
| Cooling-off on quotas | ~10 RPM on 2.5 tier; 3.x dynamic | per-project/region, adjustable after trial only |

**Bottom line:** for the Crush OpenAI-compat runtime, the standard flow with a
$300 trial credits + SA key is a **yes-with-caveats**: (1) requires a card
(identity check), (2) requires signing JWTs in Rust to mint access tokens
(hourly refresh), (3) the private key material is sensitive (file-based RSA
key, revocation = key-delete not token-delete), (4) new-service-account
deprecation warning on the Gemini API side suggests this path may tighten by
2026-08-27. Express mode removes items (1)–(3) but changes the request shape
off OpenAI-compat (would need a Crush-side emitter adapter, and it's
Preview). Neither is a 10-minute skip like AI Studio, so if the goal is
"fastest possible key in Crash today," Vertex AI is **worse** than
`gemini-api` for the OpenAI path but **better free-tier stamina** ($300 for 90
days vs Gemini free tier daily caps).

## Sources (visited 2026-10-17)
- Overview/differences: .../models/migrate/migrate-google-ai
- Express overview: .../models/start/express-mode/overview
- Express REST ref: .../reference/express-mode/api-reference
- OpenAI compat: .../models/start/openai
- OpenAI auth: .../models/migrate/openai/auth-and-credentials
- Pricing: .../generative-ai/pricing
- Quotas: .../models/quotas
- Free trials: https://cloud.google.com/free/docs/free-cloud-features
- IAM keys: https://cloud.google.com/iam/docs/keys-create-delete
- LiteLLM `vertexai` provider: https://docs.litellm.ai/docs/providers/vertex (secondary)
