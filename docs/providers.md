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

Twelve, curated from Catwalk's ~45. Listing all of them would bury the two that
matter to someone without a budget, and every extra row is another chance to
offer an id this engine's pinned Catwalk does not know.

| id | Free tier? | Notes |
|---|---|---|
| `opencode-zen` | **no** (as of 2026-10-11) | the only one verified end to end |
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

## Testing without a paid plan

OpenCode Zen's free tier is **withdrawn** — measured, see
[project-analysis.md](project-analysis.md) §1a. The key authenticates and lists
43 models but can call none: 33 × HTTP 402, and the ten `*-free` ids × HTTP 403.

So for end-to-end testing:

1. Settings → Provider → **OpenRouter**
2. Get a key from <https://openrouter.ai/keys> (`sk-or-...`)
3. Paste it. `normalizeApiKey` strips an `OPENROUTER_API_KEY=` prefix, so pasting
   the whole assignment works.
4. Pick a model whose id ends `:free`

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

1. open the provider's auth URL in a Custom Tab
2. capture the redirect — Android app links (`android:autoVerify`) or a claimed
   loopback `intent://` scheme
3. store the resulting token where Crush's credential store reads it
4. call `refresh-oauth` to keep it alive

Step 2 needs a domain you control or a claimed loopback scheme, and neither is
configured. That is real work, not a checkbox.

It is worth doing: a Copilot or ChatGPT subscription is the cheapest route to a
*working* paid provider, and it is the one route that does not need this app to
hold a provider's raw API key.

## Adding a provider

1. Confirm the id in Catwalk's `internal/providers/configs/`. Do not derive it
   from the provider's name.
2. Add a `Provider` entry in `domain/provider/Providers.kt` with an honest
   `isKnown`.
3. Add a `ProvidersTest` case. `ProvidersTest` asserts no id is ever a bare
   provider name — that is the check that would have caught the original 500.
4. Never add it to a default path or a hardcoded model list. The app is
   dynamic-model-only by design; a hardcoded id is what produced
   `large model not found in provider`.