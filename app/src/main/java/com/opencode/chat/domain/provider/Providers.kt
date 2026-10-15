package com.opencode.chat.domain.provider

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
     * Distinct from [isKnown]: `groq` and `cerebras` are unverified here but are
     * free, and `openrouter` is free for `:free` models. A user whose paid plan
     * lapsed needs to know which of these can still be tested against.
     */
    val hasFreeTier: Boolean = false
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
            keyHint = "opencode.ai/zen"
        ),
        Provider(
            // Catwalk id is plain "openrouter". Env var is OPENROUTER_API_KEY,
            // which normalizeApiKey strips from a pasted assignment, so
            // "OPENROUTER_API_KEY=sk-or-..." can be pasted verbatim.
            id = "openrouter",
            displayName = "OpenRouter",
            isKnown = false,
            keyHint = "openrouter.ai/keys",
            // Free models are ids ending ":free". They are the cheapest way to
            // exercise the agent end to end while Zen's own free tier is
            // withdrawn.
            hasFreeTier = true
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
            isKnown = false,
            keyHint = "console.groq.com/keys",
            hasFreeTier = true
        ),
        Provider(
            id = "cerebras",
            displayName = "Cerebras",
            isKnown = false,
            keyHint = "cloud.cerebras.ai",
            hasFreeTier = true
        ),
        Provider(
            id = "deepseek",
            displayName = "DeepSeek",
            isKnown = false,
            keyHint = "platform.deepseek.com",
            hasFreeTier = true
        ),
        Provider(
            id = "openai",
            displayName = "OpenAI",
            isKnown = false,
            keyHint = "platform.openai.com/api-keys"
        ),
        Provider(
            id = "anthropic",
            displayName = "Anthropic",
            isKnown = false,
            keyHint = "console.anthropic.com/settings/keys"
        ),
        Provider(
            id = "gemini",
            displayName = "Google Gemini",
            isKnown = false,
            keyHint = "aistudio.google.com/apikey"
        ),
        Provider(
            id = "vertexai",
            displayName = "Google Vertex AI",
            isKnown = false,
            keyHint = "GCP service-account credentials"
        ),
        Provider(
            id = "xai",
            displayName = "xAI",
            isKnown = false,
            keyHint = "console.x.ai"
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