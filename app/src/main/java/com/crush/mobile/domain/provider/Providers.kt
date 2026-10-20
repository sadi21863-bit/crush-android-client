package com.crush.mobile.domain.provider

/**
 * A provider the app can route a turn to.
 *
 * WHY THIS IS A LIST AND NOT A STRING
 *
 * The app used to hardcode the provider id at seven call sites, which is what
 * made "add another provider" a find-and-replace rather than a feature. The
 * engine already takes the provider as a parameter on key-set, model-set and
 * init, so nothing downstream was blocked - only the plumbing was.
 *
 * WHERE THESE IDS COME FROM
 *
 * They are Catwalk's, read from `charmbracelet/catwalk`,
 * `internal/providers/configs/<id>.json`, where each file's `id` field is the
 * provider's canonical identifier. Crush resolves providers through that
 * catalogue and returns HTTP 500 {"provider with ID ... not found"} for anything
 * it does not recognise.
 *
 * `hyper` is the important case. It does NOT appear in Catwalk's
 * internal/providers/configs/ directory, so reading that directory alone leads
 * to concluding it does not exist - which is the wrong answer. Crush registers
 * it in internal/config/load.go as
 * `catwalk.InferenceProvider("hyper")`, reading HYPER_API_KEY from the
 * environment. A published config list is not the same thing as the set of
 * providers Crush will resolve.
 *
 * This matters because the obvious guess is usually wrong. `openrouter` is plain;
 * `opencode` is not a provider at all; `opencode-zen` and `opencode-go` are two
 * separate ones. Deriving an id from a provider's public name produced the
 * "provider with ID opencode not found" 500 this project already hit once.
 *
 * VERSION CAVEAT
 *
 * Crush 0.97.1 embeds a Catwalk snapshot, not Catwalk `main`. The ids below come
 * from `main` as read on 2026-10-13, so a provider added to Catwalk after
 * 0.97.1 shipped may not resolve on this engine, and one removed since may not
 * appear. `GET /v1/workspaces/{id}/providers` remains the authority; this table is
 * what the settings screen offers, not what the engine will necessarily accept.
 */
data class Provider(
    /** Catwalk's provider id, e.g. `openrouter`. Sent verbatim to the engine. */
    val id: String,
    /** Shown to the user. */
    val displayName: String,
    /**
     * True when [id] has been observed completing a handshake on this build.
     *
     * Conservative on purpose. A row labelled available that 500s on first use is
     * worse than one labelled unverified, because the user has already paid the
     * key-setup cost before finding out.
     */
    val isKnown: Boolean,
    /** Where the user obtains a key. Shown in Settings so the flow is clear. */
    val keyHint: String,
    /**
     * True when this provider has a genuinely free tier reachable by API key.
     *
     * Distinct from [isKnown]: these describe different facts. A provider can
     * be free yet not handshake-verified (none currently), or verified but a
     * bad TEST target ([groq]: 8K TPM caps every agent shape out of the free
     * tier). A user whose paid plan lapsed needs to know which of these can
     * still be tested against.
     *
     * 2026-10-17 corrections from per-provider research under docs/providers:
     * Hyper's free credits are retired and approved accounts get none;
     * DeepSeek has no free tier; Cerebras's $5 credit needs a payment card,
     * so none of the three count as testable-for-$0 any more.
     */
    val hasFreeTier: Boolean = false,
    /**
     * Base of the provider's OpenAI-compatible HTTP API, used for the live
     * model catalogue (`GET <modelsBase>/models`) and 1-token call probes.
     *
     * Null means the app has no verified compat base for this provider, and
     * must NOT silently fall back to Zen's: probing opencode.ai/zen with a
     * foreign key is exactly the bug this field exists to fix — the key
     * authenticates against a different host, and the ids it returns describe
     * a different provider. For [com.crush.mobile.data.api.ZenModelsApi] the
     * Zen base is the constructor default; a null entry is an honest
     * "not supported for the picker yet" until researched.
     */
    val modelsBase: String? = null,
    /**
     * Ids to probe FIRST, best first, when shortlisting from the live
     * catalogue. Measured, not preferred: for groq the natural order put
     * `allam-2-7b` first (alphabetically) which answers probes but cannot run
     * the agent ("tool calling is not supported with this model"), while the
     * models that can are named explicitly here. Empty = no preference.
     *
     * NOTE: groq itself is parked (2026-10-17). Its free tier caps every
     * model at 8,000 tokens per minute and no agent shape fits - the coder
     * agent's minimal request measures ~11.4K and even the leanest agent
     * measures ~8.6K on-device, both over the cap. This list stays because the
     * probe order is measured knowledge, not because groq is a supported
     * target.
     */
    val preferredModelIds: List<String> = emptyList(),
    /**
     * Engine agent id to seat as MAIN after init, or "" for the engine's own
     * default (the full-coder build agent).
     *
     * Currently unused by any listed provider: seating `plan` as main dropped
     * Groq's request to ~8.6K, but that is STILL over the free tier's 8,000
     * TPM cap on-device (measured twice, 2026-10-17), so no agent shape
     * survives there and the field is parked until a real use appears.
     * The plumbing (CrushSession.ensureAgentReady + POST /agent/main after
     * init) stays because it is provider-generic.
     */
    val mainAgent: String = ""
)

object Providers {

    /**
     * Every provider this build offers.
     *
     * A curated subset of Catwalk's ~45. Listing all of them would bury the two
     * that matter to someone without a budget, and every extra row is a chance to
     * offer an id this engine's pinned Catwalk does not recognise.
     *
     * Ids are verbatim from Catwalk. [isKnown] is true only for the one that has
     * actually completed a handshake on a device.
     */
    val ALL: List<Provider> = listOf(
        Provider(
            id = "opencode-zen",
            displayName = "OpenCode Zen",
            isKnown = true,
            keyHint = "opencode.ai/zen",
            modelsBase = "https://opencode.ai/zen/v1"
        ),
        Provider(
            id = "hyper",
            displayName = "Charm Hyper",
            isKnown = false,
            keyHint = "hyper.charm.land (key starts sk-hyper-)",
            // 2026-10-17: the FAQ retires the monthly free Hypercredit allowance,
            // and a real approved account (first-hand, this project) shows no free
            // tier and no credits. Prepaid from $5 is the smallest entry. The old
            // comment here ("100 Hypercredits, still granted") was the docs' own
            // stale promise. See docs/providers/hyper.md.
            hasFreeTier = false,
            // Verified live 2026-10-16: GET /v1/models -> HTTP 200 with 22
            // models, no auth required to list.
            modelsBase = "https://hyper.charm.land/v1"
        ),
        Provider(
            id = "openrouter",
            displayName = "OpenRouter",
            // E2E-verified on device 2026-10-17: probe →
            // nvidia/nemotron-3-super-120b-a12b:free responded, the turn
            // streamed reasoning + text, finish end_turn, UI rendered it.
            // The first preferred id (gemma-4-31b) failed its probe; fallback
            // order is load-bearing.
            isKnown = true,
            keyHint = "openrouter.ai/keys",
            // Free models are ids ending ":free" and need NO credits: the cap
            // is per-REQUEST (20/min, 50/day without a $10 credit purchase -
            // docs/providers/openrouter.md), NOT per-token. That is what makes
            // this the project's test target from 2026-10-17: the agent's
            // ~11.4K-token request size, fatal under Groq's 8K-TPM cap, is
            // irrelevant here because free models carry 256K-1M contexts.
            hasFreeTier = true,
            modelsBase = "https://openrouter.ai/api/v1",
            // Tool-capable free ids, best first, measured live against
            // supported_parameters containing "tools" plus the moderation
            // flag (moderated free routes can refuse agent content, so
            // north-mini-code goes last despite being a code model).
            preferredModelIds = listOf(
                "google/gemma-4-31b-it:free",
                "nvidia/nemotron-3-super-120b-a12b:free",
                "thinkingmachines/inkling-small:free",
                "dots-studio/dots-3-note-preview:free",
                "cohere/north-mini-code:free"
            )
        ),
        Provider(
            id = "opencode-go",
            displayName = "OpenCode Go",
            isKnown = false,
            keyHint = "opencode.ai/go"
        ),
        Provider(
            id = "groq",
            displayName = "Groq",
            isKnown = true,
            keyHint = "console.groq.com/keys",
            // Parked 2026-10-17 as a test target: free tier caps every model
            // at 8K tokens per minute (per-TOKEN, not per-request) and no
            // agent shape fits - see the parked-list note on
            // preferredModelIds below and docs/providers/groq.md.
            hasFreeTier = true,
            modelsBase = "https://api.groq.com/openai/v1",
            // A 200 probe is not agent capability: allam-2-7b answers chat
            // probes but rejects the agent's tool schema outright, and the
            // oss models are the ones the engine accepted end-to-end. Order
            // taken from the live catalogue plus those measurements.
            preferredModelIds = listOf(
                "openai/gpt-oss-120b",
                "openai/gpt-oss-20b",
                "qwen/qwen3.8-27b",
                "allam-2-7b"
            )
        ),
        Provider(
            id = "cerebras",
            displayName = "Cerebras",
            isKnown = false,
            keyHint = "cloud.cerebras.ai",
            // The "$5 credit" (2026-10-17) requires a payment card and expires
            // in 30 days; it is a trial grant, not a free tier.
            hasFreeTier = false,
            modelsBase = "https://api.cerebras.ai/v1"
        ),
        Provider(
            id = "deepseek",
            displayName = "DeepSeek",
            isKnown = false,
            keyHint = "platform.deepseek.com",
            // Cheap, not free: no free tier or trial credit exists on any
            // current official page (2026-10-17; docs/providers/deepseek.md).
            hasFreeTier = false,
            modelsBase = "https://api.deepseek.com"
        ),
        Provider(
            id = "openai",
            displayName = "OpenAI",
            isKnown = false,
            keyHint = "platform.openai.com/api-keys",
            modelsBase = "https://api.openai.com/v1"
        ),
        Provider(
            id = "anthropic",
            displayName = "Anthropic",
            isKnown = false,
            keyHint = "console.anthropic.com/settings/keys"
            // No modelsBase: Anthropic's /v1/models is not OpenAI-shaped and
            // does not take a Bearer key header.
        ),
        Provider(
            id = "gemini",
            displayName = "Google Gemini",
            isKnown = false,
            keyHint = "aistudio.google.com/apikey",
            // Free tier exists for 3.x flash models, but free-tier content is
            // reviewed/used for training (2026-10-17).
            hasFreeTier = true,
            modelsBase = "https://generativelanguage.googleapis.com/v1beta/openai"
        ),
        Provider(
            id = "vertexai",
            displayName = "Google Vertex AI",
            isKnown = false,
            keyHint = "GCP service-account credentials"
            // Credentials are an OAuth access token, not a pasted key.
        ),
        Provider(
            id = "xai",
            displayName = "xAI",
            isKnown = false,
            keyHint = "console.x.ai",
            modelsBase = "https://api.x.ai/v1"
        ),
        Provider(
            id = "copilot",
            displayName = "GitHub Copilot",
            // OAuth, not an API key. Catwalk carries it and Crush exposes
            // /config/refresh-oauth for it, but this app has no OAuth flow yet -
            // see docs/providers.md. Listed so its absence is a known gap rather
            // than an oversight.
            isKnown = false,
            keyHint = "sign-in required (OAuth, not supported yet)"
        )
    )

    val DEFAULT: Provider = ALL.first { it.id == "opencode-zen" }

    fun byId(id: String?): Provider? =
        ALL.firstOrNull { it.id == id }

    /**
     * Resolves a stored id, falling back to [DEFAULT].
     *
     * Falling back rather than failing is deliberate: a provider id persisted by
     * an older build may not exist in this one, and refusing to start would
     * strand the user behind a settings screen they cannot reach. Losing the
     * preference is recoverable; not booting is not.
     */
    fun resolve(id: String?): Provider = byId(id) ?: DEFAULT

    /** True when the engine reported an id we do not recognise. */
    fun isUnknownToApp(id: String): Boolean = byId(id) == null

    /** Providers that can be tested without paying. */
    val FREE_TIER: List<Provider> = ALL.filter { it.hasFreeTier }
}