package com.opencode.chat.ui.screens.chat

import com.opencode.chat.data.api.LiveZenModel

/**
 * Chooses which model to configure the agent with.
 *
 * WHY THIS EXISTS
 *
 * `space-bunny-free` used to be hardcoded in two places and written to both the
 * large and small slots before `agent/init`. On a phone where Zen would not
 * accept it, Crush rejected the whole handshake:
 *
 *   HTTP 500 /v1/workspaces/{id}/agent/init
 *   {"message":"large model not found in provider"}
 *
 * Nothing in that message says "your API key cannot see this model", so the app
 * reported an engine-level complaint the user could not act on. The engine's
 * model catalogue is per-credential: it reflects what THIS key is allowed to
 * call, so the only honest source of a valid id is a live catalogue fetch with
 * the same key.
 *
 * The rule that follows: never configure a model id that has not come back from
 * a live fetch. A hardcoded default is a guess, and this engine treats a guess
 * as a fatal configuration error.
 *
 * AND WHY THE CATALOGUE ALONE IS STILL NOT ENOUGH
 *
 * Membership in /models does not mean the model can be called. Zen's catalogue
 * is public - `GET /models` answers HTTP 200 with 86 models and NO api key at
 * all - and it still lists entries that are dead. Verified on device with one
 * key:
 *
 *   deepseek-v4-flash-free  -> HTTP 400 "Model is unavailable"
 *   space-bunny-free        -> HTTP 200
 *
 * So this object no longer *selects*; it only produces an ORDERED SHORTLIST to
 * probe. The final choice is made by measurement - see
 * [com.opencode.chat.data.api.ZenModelsApi.firstWorking]. Returning an unprobed
 * id here is precisely the bug that shipped.
 */
object ModelChoice {

    /**
     * Builds the probe order from a live catalogue. Nothing is hardcoded.
     *
     * Models Zen prices at zero go first so a missing subscription cannot block
     * the app, then everything else. Order within a tier is stable so repeated
     * launches probe the same candidates and do not burn tokens rediscovering.
     *
     * @return ids to probe, best-first. Empty when Zen returned nothing usable.
     */
    fun shortlist(models: List<LiveZenModel>): List<String> {
        val free = models.filter { it.isFree && it.id.isNotBlank() }.map { it.id }
        val rest = models.filter { !it.isFree && it.id.isNotBlank() }.map { it.id }
        return (free + rest).distinct()
    }

/**
     * Fallback used ONLY when every probe failed. Returning null makes the app
     * say so honestly instead of silently configuring something known-broken.
     */
    fun pick(models: List<LiveZenModel>): String? = shortlist(models).firstOrNull()

    /**
     * Explains "no model answered a probe" without blaming the wrong thing.
     *
     * Verified against the live service with a free-tier key: of 86 listed
     * models, 53 answered 403 and 30 answered 402 - both meaning "Model access
     * is disabled" for this key - while only one worked. So the overwhelmingly
     * likely cause is a key without access to paid models, NOT a broken app and
     * not a dead catalogue. Saying so saves the user from chasing the wrong bug.
     */
    fun explainNoUsableModel(listed: Int, result: com.opencode.chat.data.api.FirstWorking): String {
        if (result.badKey) {
            return "OpenCode Zen rejected this API key while checking which models it can " +
                "call. Replace it in Settings."
        }
        return when {
            result.denied > 0 && result.retired == 0 ->
                "This API key cannot call any of the $listed models Zen lists - Zen answered " +
                    "\"Model access is disabled\" ${result.denied} time(s). It likely needs a " +
                    "Zen plan with model access."
            result.retired > 0 ->
                "None of the models Zen lists can be called right now " +
                    "(${result.retired} retired, ${result.denied} not enabled for this key)."
            else ->
                "Could not reach OpenCode Zen to check which models this key can call."
        }
    }

    /**
     * Turns a catalogue-fetch failure into something a person can act on.
     *
     * The distinction that matters: a network failure and a rejected key look
     * identical to a user otherwise, and they need different responses - one is
     * "try again later", the other is "check your key".
     */
    fun explainFetchFailure(error: String?): String {
        val e = error.orEmpty()
        return when {
            e.isBlank() -> "Could not reach OpenCode Zen. Check your connection."
            e.contains("no API key", ignoreCase = true) ->
                "No API key is stored yet. Add one in Settings."
            // 401/403 is the signature of a key Zen will not accept. Worth
            // naming explicitly because it is the single most likely reason a
            // friend's fresh install cannot chat.
            e.contains("401") || e.contains("403") || e.contains("Unauthorized", true) ->
                "OpenCode Zen rejected this API key. Check it in Settings and try again."
            e.contains("empty", ignoreCase = true) ->
                "This API key cannot see any models right now. Check it in Settings."
            // Connectivity must be named as connectivity. These all used to fall
            // through to the generic branch, so a dropped wifi was reported as
            // "Could not load models ... timeout" - indistinguishable from a
            // broken app, and it sent people to re-type a key that was fine.
            NETWORK_MARKERS.any { e.contains(it, ignoreCase = true) } ->
                "Could not reach OpenCode Zen. Check your connection."
            else -> "Could not load models from OpenCode Zen: $e"
        }
    }

    /**
     * Substrings that mean "the network failed", as opposed to "the key was
     * rejected". Kept as data so the distinction is testable and cannot drift.
     */
    private val NETWORK_MARKERS = listOf(
        "timeout", "timed out", "unable to resolve host", "unknownhost",
        "connection refused", "connection reset", "connection aborted",
        "no address associated", "network is unreachable", "ssl",
        "failed to connect", "econn", "socket", "unexpected end of stream"
    )

    /** True when a fetch error looks like a credential problem, not connectivity. */
    fun isCredentialProblem(error: String?): Boolean {
        val e = error.orEmpty()
        return e.contains("401") || e.contains("403") ||
            e.contains("Unauthorized", true) || e.contains("no API key", ignoreCase = true)
    }
}
