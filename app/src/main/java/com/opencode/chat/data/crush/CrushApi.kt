package com.opencode.chat.data.crush

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the locally running Crush engine.
 *
 * The engine is unauthenticated, so this must only ever be pointed at
 * 127.0.0.1. Binding Crush to a routable interface would expose an agent with
 * shell execution to the network.
 */
class CrushApi(
    @Volatile var port: Int,
    private val gson: Gson = Gson(),
    /**
     * TEST-ONLY override for the base URL. Production MUST leave this null so
     * the client can only ever reach 127.0.0.1: the engine is unauthenticated and
     * grants shell execution, so binding it to a routable interface would expose
     * a remote-code-execution surface on the network.
     */
    private val baseUrlOverride: String? = null
) {

    /**
     * A dedicated client for streaming: no read timeout, because Crush sends no
     * heartbeat and an idle workspace legitimately produces zero bytes.
     */
    val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val client: OkHttpClient = streamClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

private val json = "application/json".toMediaType()
    private val base get() = baseUrlOverride ?: "http://127.0.0.1:$port"

    // ------------------------------------------------------------- system

    suspend fun version(): VersionInfo = get("/v1/version")

    /** 200 with an empty body; use [version] for anything meaningful. */
suspend fun health(): Boolean = runCatching {
        newCall("/v1/health").execute().code == 200
    }.getOrDefault(false)

    // ---------------------------------------------------------- workspace

    /**
     * Creates (or re-attaches to) a workspace.
     *
     * The workspace ID is an ephemeral UUID that changes on every engine start;
     * [path] is the stable identity. Call this after each boot.
     */
    suspend fun createWorkspace(req: CreateWorkspaceRequest): Workspace =
        post("/v1/workspaces", req)

    suspend fun listWorkspaces(): List<Workspace> = getList("/v1/workspaces")

suspend fun agentInfo(workspaceId: String): AgentInfo =
        get("/v1/workspaces/$workspaceId/agent")

    /**
     * Starts the agent coordinator for a workspace.
     *
     * REQUIRED before sending a prompt: without it `POST .../agent` fails with
     * 400 {"message":"agent coordinator not initialized"}, and `/agent` reports
     * is_ready=false.
     */
    suspend fun initAgent(workspaceId: String, interactive: Boolean = true) {
        postEmpty(
            "/v1/workspaces/$workspaceId/agent/init",
            mapOf("interactive" to interactive)
        )
    }

    /** Selects the primary agent for a workspace (build / plan / general). */
    suspend fun setMainAgent(workspaceId: String, agentId: String) {
        postEmpty("/v1/workspaces/$workspaceId/agent/main", mapOf("agent_id" to agentId))
    }

    suspend fun currentSession(workspaceId: String, sessionId: String) {
        postEmpty("/v1/workspaces/$workspaceId/current-session", mapOf("session_id" to sessionId))
    }

    // ------------------------------------------------------------ session

    suspend fun listSessions(workspaceId: String): List<Session> =
        getList("/v1/workspaces/$workspaceId/sessions")

    suspend fun createSession(workspaceId: String, req: CreateSessionRequest): Session =
        post("/v1/workspaces/$workspaceId/sessions", req)

    suspend fun messages(workspaceId: String, sessionId: String): List<StreamMessage> =
        getList("/v1/workspaces/$workspaceId/sessions/$sessionId/messages")

    /**
     * Deletes a session.
     *
     * Was a silent no-op: it built a GET (not a DELETE) and never checked the
     * status, so a 404 from a reaped workspace or a 405 from the wrong verb both
     * "succeeded". The caller computed `deleted = isSuccess` and therefore always
     * reported success - the row vanished from local state while the session
     * lived on and reappeared in the drawer on the next list.
     */
    suspend fun deleteSession(workspaceId: String, sessionId: String) {
        val path = "/v1/workspaces/$workspaceId/sessions/$sessionId"
        // A dropped connection here is SUCCESS, not failure.
        //
        // Crush reaps an unattached workspace after ~30s, and by the time a user
        // opens the chat list, taps delete and the request lands, the workspace
        // can already be gone - which closes the socket and surfaces as an
        // IOException with a null message. Observed on device as
        // "deleteSession failed: null" four times in a row.
        //
        // The session cannot outlive its workspace, so the user's intent - that
        // the chat is gone - is already satisfied. Reporting failure here would
        // tell someone their chat still exists when it does not.
        runCatching {
            streamClient.newCall(
                Request.Builder().url(base + path).delete().build()
            ).execute().use { resp ->
                if (resp.code == 404) return@use
                requireSuccess(resp, path)
            }
        }.onFailure { e ->
            val gone = e is java.io.InterruptedIOException ||
                e is java.io.EOFException ||
                (e.message ?: "").contains("workspace not found", ignoreCase = true)
            if (!gone) throw e
        }
    }

    // -------------------------------------------------------------- agent

    /** Fire-and-forget: returns as soon as the run is admitted (202). */
    suspend fun send(workspaceId: String, req: AgentMessageRequest) {
        postEmpty("/v1/workspaces/$workspaceId/agent", req)
    }

    suspend fun cancel(workspaceId: String, sessionId: String) {
        postEmpty("/v1/workspaces/$workspaceId/agent/sessions/$sessionId/cancel", emptyMap<String, Any>())
    }

    // -------------------------------------------------------- permissions

    suspend fun skipPermissions(workspaceId: String, skip: Boolean) {
        postEmpty("/v1/workspaces/$workspaceId/permissions/skip", SkipPermissionsRequest(skip))
    }

    /**
     * Echoes the entire request back, not just its id.
     */
    suspend fun grant(workspaceId: String, grant: PermissionGrant): PermissionGrantResponse =
        post("/v1/workspaces/$workspaceId/permissions/grant", grant)

    suspend fun answerQuestions(workspaceId: String, answer: QuestionAnswer) {
        postEmpty("/v1/workspaces/$workspaceId/questions/answer", answer)
    }

    suspend fun cancelQuestions(workspaceId: String) {
        postEmpty("/v1/workspaces/$workspaceId/questions/cancel", emptyMap<String, Any>())
    }

    // ------------------------------------------------------------- config

suspend fun setProviderKey(workspaceId: String, providerId: String, key: String) {
        postEmpty(
            "/v1/workspaces/$workspaceId/config/provider-key",
            SetProviderKeyRequest(
                scope = SetProviderKeyRequest.SCOPE_GLOBAL,
                providerId = providerId,
                apiKey = normalizeApiKey(key)
            )
        )
    }

    /**
     * Crush forwards api_key verbatim into the upstream Authorization header.
     * Keys are commonly stored in `NAME=value` form (that is how Crush writes
     * them back to crush.json, and how the on-device key file holds them), and
     * sending that whole string yields HTTP 401 "Invalid API key".
     *
     * Only strip when the prefix is unambiguously an env var name, so that
     * base64 padding (`=` at the end) is left alone.
     */
suspend fun providers(workspaceId: String): List<JsonObject> =
        getList("/v1/workspaces/$workspaceId/providers")

    suspend fun setModel(workspaceId: String, modelType: String, model: SelectedModel) {
        postEmpty(
            "/v1/workspaces/$workspaceId/config/model",
            SetModelRequest(
                scope = SetModelRequest.SCOPE_GLOBAL,
                modelType = modelType,
                model = model
            )
        )
    }

// ----------------------------------------------------------- plumbing

    private fun newCall(path: String) =
        client.newCall(Request.Builder().url(base + path).get().build())

    private fun newPostCall(path: String, body: Any) =
        client.newCall(
            Request.Builder()
                .url(base + path)
                .post(gson.toJson(body).toRequestBody(json))
                .build()
        )

    private suspend inline fun <reified T> getList(path: String): List<T> =
        withContext(Dispatchers.IO) {
            newCall(path).execute().use { resp ->
                requireSuccess(resp, path)
                val listType = object : TypeToken<List<T>>() {}.type
                gson.fromJson<List<T>>(resp.body?.string().orEmpty(), listType) ?: emptyList()
            }
        }

    /**
     * Decodes a body that is REQUIRED to produce a value.
     *
     * Gson returns null for an empty or literal-"null" body, so a 200/204 with
     * no content made this return null into a non-null T. Kotlin then threw
     * NullPointerException on the return-value check - and because doBootstrap
     * had no catch, that was process death rather than an error message.
     *
     * A wrong-shaped body throws JsonSyntaxException, which is equally unchecked
     * and equally fatal, so both are converted here into one ordinary exception
     * the caller can surface.
     */
    private inline fun <reified T> decodeRequired(body: String, path: String): T {
        if (body.isBlank() || body.trim() == "null") {
            throw IOException("empty body from $path (expected ${T::class.java.simpleName})")
        }
        return try {
            gson.fromJson<T>(body, T::class.java)
                ?: throw IOException("null body from $path (expected ${T::class.java.simpleName})")
        } catch (e: JsonSyntaxException) {
            throw IOException("malformed body from $path: ${e.message}", e)
        }
    }

    private suspend inline fun <reified T> get(path: String): T =
        withContext(Dispatchers.IO) {
            newCall(path).execute().use { resp ->
                requireSuccess(resp, path)
                decodeRequired(resp.body?.string().orEmpty(), path)
            }
        }

    private suspend inline fun <reified T> post(path: String, body: Any): T =
        withContext(Dispatchers.IO) {
            newPostCall(path, body).execute().use { resp ->
                requireSuccess(resp, path)
                decodeRequired(resp.body?.string().orEmpty(), path)
            }
        }

    private suspend fun postEmpty(path: String, body: Any) = withContext(Dispatchers.IO) {
        newPostCall(path, body).execute().use { requireSuccess(it, path) }
    }

    private fun requireSuccess(resp: okhttp3.Response, path: String) {
        if (resp.isSuccessful) return
        val detail = runCatching { resp.body?.string()?.take(300) }.getOrNull().orEmpty()
        throw IOException("HTTP ${resp.code} on $path: $detail")
    }
}

/**
 * Strips a `NAME=` prefix from an API key.
 *
 * Crush forwards api_key into the upstream Authorization header VERBATIM, so
 * anything extra in the string becomes part of the bearer token. Keys are
 * commonly stored in `NAME=value` form - that is how Crush writes them back to
 * crush.json - and sending that whole string yields HTTP 401 "Invalid API key".
 * This exact bug cost a full debugging cycle in production.
 *
 * Only strips when the prefix is unambiguously an env var name (uppercase,
 * underscores, at least one underscore). That guard is load-bearing: a bare key
 * can legitimately end in base64 padding (`AAAA==`), and a naive split on the
 * first `=` would truncate it to nothing.
 *
 * Top-level rather than a member so unit tests can reach it without constructing
 * the whole client.
 */
internal fun normalizeApiKey(raw: String): String {
    val trimmed = raw.trim()
    val eq = trimmed.indexOf('=')
    if (eq <= 0) return trimmed
    val prefix = trimmed.substring(0, eq)
    val looksLikeEnvName = prefix.contains('_') &&
        prefix.all { it.isUpperCase() || it.isDigit() || it == '_' }
    if (!looksLikeEnvName) return trimmed
    return trimmed.substring(eq + 1).trim().ifEmpty { trimmed }
}
