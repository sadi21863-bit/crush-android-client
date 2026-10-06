package com.opencode.chat.data.crush

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Request/response shapes for the Crush HTTP API.
 *
 * These are hand-written rather than generated: Crush serves an OpenAPI 3.2.1
 * document at /v1/docs/openapi.json, but it declares every schema inline with
 * no `$ref`, so a generator would emit anonymous, unusable types. The live
 * spec (saved at spec/crush-openapi.json) was the source of truth.
 */

// ---------------------------------------------------------------- system

data class VersionInfo(
    val version: String = "",
    val commit: String = "",
    @SerializedName("build_id") val buildId: String = "",
    @SerializedName("go_version") val goVersion: String = "",
    val platform: String = ""
)

data class ApiError(val error: String = "")

// ------------------------------------------------------------ workspace

/**
 * `id` MUST be a valid UUID, and so must `client_id`. Crush validates both
 * with uuid.Parse and returns 400 otherwise.
 */
data class CreateWorkspaceRequest(
    val id: String,
    val path: String,
    @SerializedName("data_dir") val dataDir: String? = null,
    val yolo: Boolean = true,
    @SerializedName("client_id") val clientId: String? = null
)

data class Workspace(
    val id: String = "",
    val path: String = "",
    @SerializedName("data_dir") val dataDir: String = "",
    val yolo: Boolean = false,
    @SerializedName("client_id") val clientId: String = ""
)

/**
 * An agent turn request. `run_id` is not optional in practice: Crush returns
 * every RunComplete on the workspace stream, so without it a queued prompt can
 * terminate on another turn's completion.
 */
data class AgentMessageRequest(
    @SerializedName("session_id") val sessionId: String,
    val prompt: String,
    @SerializedName("run_id") val runId: String
)

data class AgentInfo(
    @SerializedName("is_busy") val isBusy: Boolean = false,
    @SerializedName("is_ready") val isReady: Boolean = false
)

// -------------------------------------------------------------- session

data class CreateSessionRequest(
    val id: String,
    @SerializedName("parent_session_id") val parentSessionId: String = "",
    val title: String = ""
)

data class Session(
    val id: String = "",
    @SerializedName("parent_session_id") val parentSessionId: String = "",
    val title: String = "",
    @SerializedName("message_count") val messageCount: Int = 0,
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("summary_message_id") val summaryMessageId: String = "",
    val cost: Double = 0.0,
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0,
    @SerializedName("is_busy") val isBusy: Boolean = false,
    @SerializedName("attached_clients") val attachedClients: Int = 0
)

// --------------------------------------------------------------- config

/**
 * Provider API key.
 *
 * `scope` is an integer enum (0 = global, 1 = workspace), NOT a string. The Go
 * type has no MarshalText so `"global"` would be rejected.
 *
 * `apiKey` MUST be a plain JSON string. Crush models it as `json.RawMessage`
 * and `json.Unmarshal`s it into a Go string, so the raw bytes need to be a
 * quoted JSON string. The published OpenAPI spec declares this field as
 * `{"type":"array","items":{"type":"integer"}}`, which is WRONG - it is how the
 * spec generator renders json.RawMessage. Sending a byte array fails with
 * HTTP 400 {"message":"decode api key string:"}.
 */
data class SetProviderKeyRequest(
    val scope: Int,
    @SerializedName("provider_id") val providerId: String,
    val kind: String = "string",
    @SerializedName("api_key") val apiKey: String
) {
    companion object {
        const val SCOPE_GLOBAL = 0
        const val SCOPE_WORKSPACE = 1
    }
}

data class SkipPermissionsRequest(val skip: Boolean)

/**
 * Preferred model selection, `POST /v1/workspaces/{id}/config/model`.
 *
 * `model_type` is "large" for the main agent and "small" for the auxiliary
 * model Crush uses for session titles. Both default to `deepseek-v4-flash-free`,
 * which Zen has since retired, so both must be set or every turn fails with
 * `Model is unavailable`.
 *
 * `model` is `config.SelectedModel`: model id, provider id, and the sampling
 * knobs. Model ids are bare (`deepseek-v4-pro`), never `opencode/`-prefixed.
 */
data class SetModelRequest(
    val scope: Int,
    @SerializedName("model_type") val modelType: String,
    val model: SelectedModel
) {
    companion object {
        const val SCOPE_GLOBAL = 0
        const val LARGE = "large"
        const val SMALL = "small"
    }
}

data class SelectedModel(
    val model: String,
    val provider: String,
    @SerializedName("reasoning_effort") val reasoningEffort: String = "medium",
    @SerializedName("max_tokens") val maxTokens: Int = 8192
)

// ---------------------------------------------------------- permissions

data class PermissionRequest(
    val id: String = "",
    @SerializedName("session_id") val sessionId: String = "",
    @SerializedName("tool_call_id") val toolCallId: String = "",
    @SerializedName("tool_name") val toolName: String = "",
    val description: String = "",
    val action: String = "",
    val path: String = "",
    val params: JsonObject? = null
)

/** The whole request is echoed back, not just its id. */
data class PermissionGrant(
    val permission: PermissionRequest,
    val action: String
) {
    companion object {
        const val ALLOW = "allow"
        const val ALLOW_SESSION = "allow_session"
        const val DENY = "deny"
    }
}

data class PermissionGrantResponse(val resolved: Boolean = false)

data class QuestionAnswer(
    @SerializedName("batch_request_id") val batchRequestId: String,
    val responses: List<QuestionResponse>
)

data class QuestionResponse(
    @SerializedName("request_id") val questionId: String,
    @SerializedName("selected_ids") val selectedIds: List<String>? = null,
    @SerializedName("fill_in_text") val fillInText: String? = null
)