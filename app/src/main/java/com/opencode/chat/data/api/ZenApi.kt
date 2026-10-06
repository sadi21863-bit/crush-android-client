package com.opencode.chat.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.*

/**
 * OpenCode Zen API client.
 * Connects to https://opencode.ai/zen/v1 — a unified cloud API for 70+ AI models.
 */
interface ZenApi {

    @GET("models")
    suspend fun listModels(
        @Header("Authorization") apiKey: String
    ): ZenModelsResponse

    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") apiKey: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse

    @Streaming
    @POST("chat/completions")
    suspend fun chatCompletionStream(
        @Header("Authorization") apiKey: String,
        @Body request: ChatCompletionRequest
    ): okhttp3.ResponseBody
}

// ── Request Models ──────────────────────────────────────────

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens") val maxTokens: Int = 4096
)

data class ChatMessage(
    val role: String,
    val content: String
)

// ── Response Models ─────────────────────────────────────────

data class ZenModelsResponse(
    val data: List<ZenModel> = emptyList()
)

data class ZenModel(
    val id: String = "",
    val owned_by: String = ""
)

data class ChatCompletionResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null
)

data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    val delta: ChatDelta? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class ChatDelta(
    val role: String? = null,
    val content: String? = null
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("total_tokens") val totalTokens: Int = 0
)
