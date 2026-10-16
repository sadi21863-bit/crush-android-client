# Providers

How this app chooses which model provider to talk to, and how to add another.

## The short version

The provider id is **Catwalk's**, not the provider's public name. It is sent
verbatim to Crush, which resolves it through its own catalogue and returns
`HTTP 500 {"provider with ID ... not found"}` for anything it does not recognise.

| What you might type | Whether it is a real id |
|---|---|
| `opencode` | **no** — this is what caused a 500 in this project |
| `opencode-zen` | yes |
| `opencode-go` | yes — a *different* provider from `opencode-zen` |
| `openrouter` | yes — plain, no prefix |

Source of truth: `charmbracelet/catwalk`,
`internal/providers/configs/<id>.json`, whose `id` field is the canonical
identifier. Read on 2026-10-13.

One exception, verified in source on 2026-10-16: **`hyper` is not a Catwalk
config file.** Crush registers it inside `internal/config/load.go` as
`catwalk.InferenceProvider("hyper")`, reading `HYPER_API_KEY` from the
environment. Its id comes from that line, not from a JSON file.

## Version caveat

Crush **0.97.1** embeds a Catwalk *snapshot*, not Catwalk `main`. Ids read from
`main` may not resolve on this engine:

- a provider added to Catwalk after 0.97.1 shipped may not exist on device
- one removed since may no longer be offered

`GET /v1/workspaces/{id}/providers` remains the authority at runtime. The table in
`domain/provider/Providers.kt` is what the settings screen *offers*, not a
guarantee that the engine will accept.

**That endpoint is also credential-dependent.** On device it listed only
`opencode-zen` and `opencode-go` — because only a Zen key was stored. A provider
you have never configured may simply not appear.

## What is offered

Thirteen, curated from Catwalk's ~45. Listing all of them would bury the two that
matter to someone without a budget, and every extra row is another chance to
offer an id this engine's pinned Catwalk does not know.

| id | Free tier? | Notes |
|---|---|---|
| `opencode-zen` | **no** (as of 2026-10-11) | the only one verified end to end |
| `hyper` | **yes** | Charm Hyper — see below; 100 Hypercredits/month |
| `openrouter` | **yes** — `:free` models | the current route to testing |
| `opencode-go` | unknown | never completed a handshake here |
| `groq` | **yes** | free tier exists |
| `cerebras` | **yes** | free tier exists |
| `deepseek` | **yes** | cheap rather than free |
| `openai` | no | |
| `anthropic` | no | |
| `gemini` | no | |
| `vertexai` | no | service-account credentials |
| `xai` | no | |
| `copilot` | n/a | **OAuth, not supported yet** — see below |

`isKnown` is true for exactly one entry: `opencode-zen`. Everything else is
labelled unverified because no key has completed a handshake against it on a
device. A row labelled available that 500s on first use is worse than one
labelled unverified, because the user pays the key-setup cost first.

### Charm Hyper, specifically

Everything below is from Charm's official documentation and a live probe, read
on 2026-10-16 — not from inference:

- Docs: <https://hyper.charm.sh>. API base: `https://hyper.charm.land/v1`.
- Keys start `sk-hyper-`. Crush reads `HYPER_API_KEY` and an env assignment takes
  precedence over anything else, so pasting `HYPER_API_KEY=sk-hyper-...` into the
  key field works — `normalizeApiKey` strips the `NAME=` prefix.
- `GET /v1/models` returns HTTP 200 with 22 models **without auth**, so the model
  picker fills in before any key is stored.
- `GET /v1/credits` returns `{"balance":100}`; the free allowance is **100
  Hypercredits per month**.
- The documented error set is 401/5xx. There is **no 402/403 plan gate** the way
  OpenCode Zen has — worth stating because the Zen failure mode ("key works,
  every model refused") has already cost this project real debugging time.
- Crush's own sign-in for Hyper is a **device-code flow** (Crush prints a URL and
  a code, the user opens it in a browser and pastes the code back). That matters
  here: it is the one auth path that does not need an app link or a claimed
  loopback scheme, which is exactly what blocked OAuth for every other provider.

Not verified: a key has not yet completed a chat turn on-device. Until one does,
the app labels the row unverified, the same as every non-Zen provider.

## Testing without a paid plan

OpenCode Zen's free tier is **withdrawn** — measured, see
[project-analysis.md](project-analysis.md) §1a. The key authenticates and lists
43 models but can call none: 33 × HTTP 402, and the ten `*-free` ids × HTTP 403.

So for end-to-end testing, the two free routes are Hyper and OpenRouter:

1. Settings → Provider → **Hyper** (or **OpenRouter**)
2. Hyper: create a key at <https://hyper.charm.sh> (`sk-hyper-...`).
   OpenRouter: <https://openrouter.ai/keys> (`sk-or-...`)
3. Paste it, whole env assignment included if that is what you copied.
4. Pick a model. On OpenRouter choose one whose id ends `:free`.

Crush's Catwalk snapshot for 0.97.1 decides which OpenRouter models are visible.
If a `:free` model you expect is missing from the picker, the snapshot predates
it.

## OAuth is not implemented

Crush exposes `POST /v1/workspaces/{id}/config/refresh-oauth` and a Copilot
import. **The app calls neither** — there are zero OAuth references in the
source.

That is a deliberate gap rather than an oversight. `refresh-oauth` takes a
`provider_id` and `scope` and expects a token that *already exists*; the endpoint
that **initiates** an OAuth flow is not in the 68-endpoint spec at all. Wiring it
properly needs:

1. start the provider's auth flow
2. capture the redirect — Android app links (`android:autoVerify`) or a claimed
   loopback `intent://` scheme; a device-code flow needs neither
3. store the resulting token where Crush's credential store reads it
4. call `refresh-oauth` to keep it alive

Today step 2 needs a domain you control or a claimed loopback scheme, and neither
is configured. **Hyper is the exception**: its device-code flow (see above)
removes step 2 entirely, so if OAuth is ever wired up, that is the one to start
with. Until then, Hyper keys are pasted like any other.

It is worth doing: a Copilot or ChatGPT subscription is the cheapest route to a
*working* paid provider, and it is the one route that does not need this app to
hold a provider's raw API key.

## Adding a provider

1. Confirm the id. Either Catwalk's `internal/providers/configs/`, or — for
   providers Crush registers in `internal/config/load.go` rather than Catwalk —
   that registration line. Do not derive the id from the provider's name.
2. Add a `Provider` entry in `domain/provider/Providers.kt` with an honest
   `isKnown`.
3. Add a `ProvidersTest` case. `ProvidersTest` asserts no id is ever a bare
   provider name — that is the check that would have caught the original 500.
4. Never add it to a default path or a hardcoded model list. The app is
   dynamic-model-only by design; a hardcoded id is what produced
   `large model not found in provider`.
