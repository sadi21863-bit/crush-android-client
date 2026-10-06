package com.opencode.chat.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.opencode.chat.data.api.ChatCompletionRequest
import com.opencode.chat.data.api.ChatMessage
import com.opencode.chat.data.api.ZenApi
import com.opencode.chat.domain.model.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Repository for OpenCode Zen API.
 * Handles model listing, chat completions, and streaming.
 */
class ZenRepository {

    private val gson = Gson()
    private var apiKey: String = ""

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val api: ZenApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://opencode.ai/zen/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ZenApi::class.java)
    }

    fun setApiKey(key: String) {
        apiKey = key
    }

    // ── Models ──────────────────────────────────────────────

    suspend fun listModels(): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.listModels("Bearer $apiKey")
            response.data.map { model ->
                ModelInfo(
                    id = model.id,
                    name = model.id,
                    provider = model.owned_by,
                    reasoning = false
                )
            }
        }
    }

    // ── Chat ────────────────────────────────────────────────

    suspend fun sendMessage(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double = 0.7
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = ChatCompletionRequest(
                model = model,
                messages = messages,
                stream = false,
                temperature = temperature
            )
            val response = api.chatCompletion("Bearer $apiKey", request)
            response.choices.firstOrNull()?.message?.content ?: ""
        }
    }

    // ── Streaming ───────────────────────────────────────────

    fun streamChat(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double = 0.7
    ): Flow<String> = flow {
        withContext(Dispatchers.IO) {
            val request = ChatCompletionRequest(
                model = model,
                messages = messages,
                stream = true,
                temperature = temperature
            )

            val httpRequest = Request.Builder()
                .url("https://opencode.ai/zen/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(
                    gson.toJson(request)
                        .toRequestBody("application/json".toMediaType())
                )
                .build()

            val response = client.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                val code = response.code
                val detail = runCatching {
                    response.body?.string()?.take(200)
                }.getOrNull().orEmpty()
                val message = when (code) {
                    401 -> "Invalid API key. Check your key in Settings."
                    402 -> "Payment required. Add credits at opencode.ai/zen"
                    403 -> "Access denied for this model."
                    404 -> "Model or endpoint not found."
                    429 -> "Rate limited. Try again shortly."
                    in 500..599 -> "Server error ($code). Try again later."
                    else -> "Request failed ($code)"
                }
                throw Exception("$message $detail".trim())
            }

            val body = response.body ?: throw Exception("Empty response")

            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ")
                    if (data == "[DONE]") break
                    try {
                        val json = JsonParser.parseString(data).asJsonObject
                        val choices = json.getAsJsonArray("choices")
                        if (choices != null && choices.size() > 0) {
                            val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                            val content = delta?.get("content")?.asString ?: ""
                            if (content.isNotEmpty()) {
                                emit(content)
                            }
                        }
                    } catch (e: Exception) {
                        // Skip malformed lines
                    }
                }
            }
        }
    }
}
