package com.opencode.chat.data.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Live model catalogue from OpenCode Zen.
 *
 * WHY THIS EXISTS, AND WHY IT IS NOT READ FROM CRUSH:
 *
 * Crush carries its own embedded model catalogue (maintained by Charm's
 * Catwalk project) and still advertises `deepseek-v4-flash-free`. Zen has since
 * RETIRED that model. Picking from Crush's list therefore produces a model that
 * fails every turn with:
 *
 *     Bad Request: Upstream request failed: Model is unavailable.
 *
 * That is not theoretical - it is what broke the first end-to-end run on this
 * project. The only trustworthy source of "can I actually call this model" is
 * Zen's own /models endpoint, so the picker is built on that instead.
 *
 * The key is read from [com.opencode.chat.data.local.SecureKeyStore], never
 * from disk directly, and is never logged.
 *
 * Named LiveZenModel to avoid colliding with the older unused `LiveZenModel` in
 * ZenApi.kt, which lacks name/isFree and is part of the dead direct-API path.
 */
data class LiveZenModel(
    val id: String,
    val name: String,
    /** True when Zen prices it at zero, i.e. it costs nothing to use. */
    val isFree: Boolean
)

/** Why a probed model did or did not answer. */
enum class ProbeVerdict { WORKS, RETIRED, NOT_ENTITLED, BAD_KEY, UNKNOWN }

data class Probe(
    val model: String,
    val verdict: ProbeVerdict,
    /** Upstream message when it failed, for honest error text. */
    val detail: String?
)

/**
 * Outcome of probing a shortlist.
 *
 * [badKey] separates "this key is rejected" from "none of these models are
 * callable", which are very different messages for a user to act on.
 */
data class FirstWorking(
    val model: String?,
    val probed: Int,
    val retired: Int,
    val denied: Int,
    val badKey: Boolean
)

class ZenModelsApi(
    private val keyProvider: () -> String,
    private val client: OkHttpClient = defaultClient()
) {

data class Result(
        val models: List<LiveZenModel>,
        val error: String? = null
    )

    /**
     * Model id -> real price, sourced from Crush's provider catalogue.
     *
     * Zen's own /models has no price field, so without this the only way to
     * label a model "free" was to guess from its id. Crush reports
     * cost_per_1m_in / cost_per_1m_out per model, which is what the engine will
     * actually charge, so that is what [fetch] uses to decide.
     *
     * Call it BEFORE fetch so the prices are available when models are parsed.
     */
    fun applyPricing(pricesById: Map<String, Pair<Double, Double>>) {
        cachedPricing = pricesById
    }

    private var cachedPricing: Map<String, Pair<Double, Double>> = emptyMap()

/**
     * Fetches the live catalogue. Sorted free-first, then alphabetically, so the
     * free models are reachable without scrolling on a phone.
     *
     * SUSPENDING, and it moves itself to IO. OkHttp's execute() blocks, and this
     * was previously called straight from viewModelScope, whose dispatcher is
     * Main - so opening the model picker threw
     * NetworkOnMainThreadException and the list came back empty. The exception
     * was caught by the surrounding runCatching and surfaced as the useless
     * "NetworkOnMainThreadException:null" with no models, which read like an
     * empty catalogue rather than a threading bug.
     */
    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        val key = keyProvider().trim()
        if (key.isEmpty()) {
            return@withContext Result(emptyList(), "no API key")
        }
        runCatching {
            val req = Request.Builder()
                .url("$BASE/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Body intentionally omitted: it can echo request material.
                    // Labelled return: this is now inside withContext{}, so a bare
                    // `return` would be a compile error rather than an early exit.
                    return@withContext Result(emptyList(), "HTTP ${resp.code} from Zen /models")
                }
                val body = resp.body?.string().orEmpty()
                val arr = JsonParser.parseString(body).asJsonObject
                    .getAsJsonArray("data")
                val list = arr.mapNotNull { el ->
                    val o = el.asJsonObject
                    val id = o.get("id")?.asString ?: return@mapNotNull null
                    LiveZenModel(
                        id = id,
                        name = o.get("display_name")?.asString ?: o.get("name")?.asString ?: id,
                        isFree = isFree(o)
                    )
                }
                Result(
                    list.sortedWith(
                        compareByDescending<LiveZenModel> { it.isFree }.thenBy { it.name }
                    )
                )
            }
        }.getOrElse { Result(emptyList(), it.javaClass.simpleName + ": " + (it.message ?: "unknown error")) }
    }

private fun isFree(o: com.google.gson.JsonObject): Boolean {
        // Real prices come from Crush's provider catalogue, which is what the
        // engine will actually charge. Preferred over anything inferrable from
        // the id, because "-free" in a name is a naming convention, not a price.
        val id = o.get("id")?.asString.orEmpty()
        cachedPricing[id]?.let { (inPrice, outPrice) ->
            return inPrice <= 0.0 && outPrice <= 0.0
        }
        // Zen's own /models carries no price. If no price is known, say nothing
        // rather than claim a model is free.
        val inPrice = o.get("cost_per_1m_in")?.takeIf { !it.isJsonNull }?.asDouble
        val outPrice = o.get("cost_per_1m_out")?.takeIf { !it.isJsonNull }?.asDouble
        if (inPrice != null || outPrice != null) {
            return (inPrice ?: 0.0) <= 0.0 && (outPrice ?: 0.0) <= 0.0
        }
        return false
    }

    /**
     * Extracts model id -> (cost_in, cost_out) from Crush's /providers payload
     * for the given provider. Pure and separate so it can be unit-tested without
     * a running engine.
     */
    companion object {
        const val BASE = "https://opencode.ai/zen/v1"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * Extracts model id -> (cost_in, cost_out) from Crush's /providers payload.
         *
         * In the companion object so it is testable without constructing a client
         * that would need a key. Static because it touches nothing on the instance.
         */
        fun pricesFromProviders(
            providers: List<JsonObject>,
            providerId: String
        ): Map<String, Pair<Double, Double>> {
        val out = mutableMapOf<String, Pair<Double, Double>>()
        for (p in providers) {
            if (p.get("id")?.asString != providerId) continue
            val models = p.getAsJsonArray("models") ?: continue
            for (m in models) {
                val mo = m.asJsonObject
                val id = mo.get("id")?.asString ?: continue
                val cin = mo.get("cost_per_1m_in")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                val cout = mo.get("cost_per_1m_out")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                out[id] = cin to cout
            }
        }
return out
        }
    }

    /**
     * Asks Zen whether a model can ACTUALLY be called right now, with this key.
     *
     * /models is a public catalogue that still advertises retired models, so
     * membership proves nothing. Measured on device: `deepseek-v4-flash-free`
     * is listed but answers HTTP 400 "Model is unavailable", while
     * `space-bunny-free` answers 200 with the same key. Guessing from the list
     * is what broke a shipped build; this is the fix.
     *
     * Costs one token (max_tokens=1) and returns no usable content.
     */
    suspend fun probe(model: String): Probe = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("model", model)
                addProperty("max_tokens", 1)
                add(
                    "messages",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("role", "user")
                                addProperty("content", "hi")
                            }
                        )
                    }
                )
            }
            client.newCall(
                Request.Builder()
                    .url("$BASE/chat/completions")
                    .addHeader("Authorization", "Bearer ${keyProvider()}")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
            ).execute().use { resp ->
                val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                if (resp.isSuccessful) {
                    Probe(model, ProbeVerdict.WORKS, null)
                } else {
                    // The upstream reason matters: 402/403 "Model access is
                    // disabled" means this KEY may not use this model, which is
                    // a different problem from the model being retired.
                    val msg = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
                        .find(text)?.groupValues?.get(1).orEmpty()
                    val verdict = if (resp.code == 401) {
                        ProbeVerdict.BAD_KEY
                    } else if (msg.contains("Model is unavailable", true)) {
                        ProbeVerdict.RETIRED
                    } else if (resp.code == 402 || resp.code == 403) {
                        ProbeVerdict.NOT_ENTITLED
                    } else {
                        ProbeVerdict.UNKNOWN
                    }
                    Probe(model, verdict, msg.ifBlank { "HTTP ${resp.code}" })
                }
            }
        }.getOrElse { Probe(model, ProbeVerdict.UNKNOWN, it.message ?: "probe failed") }
    }

    suspend fun probeWorks(model: String): Boolean = probe(model).verdict == ProbeVerdict.WORKS

    /**
     * Probes candidates in order and returns the first that really works.
     *
     * Measured against the live service with a free-tier key: of 86 listed
     * models exactly ONE answered 200, while 53 returned 403 and 30 returned
     * 402 ("Model access is disabled") and 2 returned 400 (retired). So with a
     * free-tier key the only usable model can be anywhere in the list, and a
     * small budget reports failure on a perfectly good key.
     *
     * Each probe is one token, so sweeping the list is cheap in money but not
     * free in time: 86 sequential requests on a phone is a visible stall. The
     * budget is therefore generous but bounded, and callers should pass a
     * free-first shortlist (see ModelChoice.shortlist) so the common case hits
     * on the first try.
     *
     * A BAD_KEY verdict stops the sweep immediately - every remaining probe
     * would fail the same way, and retrying a rejected key dozens of times just
     * burns the user's time before the same message appears.
     */
    suspend fun firstWorking(candidates: List<String>, maxProbes: Int = 24): FirstWorking {
        var probed = 0
        var retired = 0
        var denied = 0
        for (m in candidates.take(maxProbes)) {
            if (m.isBlank()) continue
            probed++
            when (val p = probe(m).verdict) {
                ProbeVerdict.WORKS -> return FirstWorking(m, probed, 0, 0, false)
                ProbeVerdict.BAD_KEY ->
                    return FirstWorking(null, probed, retired, denied, true)
                ProbeVerdict.RETIRED -> retired++
                ProbeVerdict.NOT_ENTITLED -> denied++
                ProbeVerdict.UNKNOWN -> Unit
            }
        }
        return FirstWorking(null, probed, retired, denied, false)
    }
}

