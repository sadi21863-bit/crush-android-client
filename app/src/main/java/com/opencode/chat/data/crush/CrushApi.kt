package com.opencode.chat.data.crush

import com.google.gson.Gson
import com.google.gson.JsonParser
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
/**
 * Outcome of a delete, distinguishing "gone" from "unknown".
 *
 * WHY THIS IS A TYPE AND NOT A BOOLEAN
 *
 * This previously converted a dropped connection, `InterruptedIOException`,
 * `EOFException`, and anything containing "workspace not found" into SUCCESS,
 * justified by the comment that "a session cannot outlive its workspace". That
 * premise is false: sessions are persistent engine state keyed by the
 * workspace's cwd, and the workspace is only an attachment handle that comes and
 * goes with the ~30s reap. So a timeout proved nothing, the app reported the
 * chat deleted, and the conversation reappeared in the drawer later.
 *
 * A boolean cannot carry "we do not know", and a caller told "false" would
 * prompt the user to retry something that had already succeeded. The three
 * cases need three answers.
 */
sealed interface DeleteOutcome {
    /** The server confirmed the session is gone. */
    object Deleted : DeleteOutcome

    /**
     * The server said the session does not exist, on a workspace we have just
     * confirmed is live. So it is genuinely gone - not merely unreachable.
     */
    object AlreadyGone : DeleteOutcome

    /** We do not know. The session may or may not still exist. */
    data class Failed(val reason: String) : DeleteOutcome
}

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

/**
     * Liveness probe. 200 with an empty body; use [version] for anything
     * meaningful.
     *
     * `suspend` does not move threads. Without withContext(Dispatchers.IO) this
     * throws NetworkOnMainThreadException when called from a ViewModel - the
     * same defect that silently broke deleteSession.
     */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            newCall("/v1/health").execute().code == 200
        }.getOrDefault(false)
    }

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
     * Paths the engine recorded this session touching.
     *
     * Confirmed from the spec, which is why this returns a plain `array[string]`
     * and not a guessed object: `GET .../sessions/{sid}/filetracker/files`.
     *
     * Returns EMPTY on any failure rather than throwing, because the caller is
     * finishing a turn and a missing file list must not fail the turn. But empty
     * also means "nothing changed", so the caller must not present it as proof
     * the agent edited nothing - [FileChangeCollector] has no way to tell the two
     * apart either, and says so via its own unreadable/snapshot flags.
     */
    suspend fun trackedFiles(workspaceId: String, sessionId: String): List<String> =
        runCatching {
            withContext(Dispatchers.IO) {
                streamClient.newCall(
                    Request.Builder()
                        .url(base + "/v1/workspaces/$workspaceId/sessions/$sessionId/filetracker/files")
                        .get()
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) emptyList()
                    else parseStringArray(resp.body?.string())
                }
            }
        }.getOrElse { emptyList() }

    private fun parseStringArray(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JsonParser.parseString(body).asJsonArray
            (0 until arr.size()).mapNotNull { i ->
                val el = arr.get(i)
                when {
                    el.isJsonNull -> null
                    // `asString` on a JSON number returns "42" and on a boolean
                    // returns "true", which would become a file PATH named 42. The
                    // spec says array[string], so anything else is not a path and
                    // is skipped rather than coerced into meaningless one.
                    !el.isJsonPrimitive || !el.asJsonPrimitive.isString -> null
                    else -> el.asString
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Deletes a session.
     *
     * Was a silent no-op: it built a GET (not a DELETE) and never checked the
     * status, so a 404 from a reaped workspace or a 405 from the wrong verb both
     * "succeeded". The caller computed `deleted = isSuccess` and therefore always
     * reported success - the row vanished from local state while the session
     * lived on and reappeared in the drawer on the next list.
     */
    /**
 * Deletes a session.
 *
 * Transport failures are reported, never swallowed. The caller re-acquires the
 * workspace and retries; only a server-confirmed 404 counts as success.
 */
suspend fun deleteSession(workspaceId: String, sessionId: String): DeleteOutcome {
    val path = "/v1/workspaces/$workspaceId/sessions/$sessionId"
    // withContext(Dispatchers.IO) is LOAD-BEARING. OkHttp's execute() is
    // synchronous and being `suspend` does not change threads; without this it
    // throws NetworkOnMainThreadException from a ViewModel, so the request never
    // leaves the device. It was invisible because that exception has a NULL
    // message and the log printed only the message.
    return withContext(Dispatchers.IO) {
        runCatching {
            streamClient.newCall(
                Request.Builder().url(base + path).delete().build()
            ).execute().use { resp ->
                when {
                    resp.isSuccessful -> DeleteOutcome.Deleted
                    // 404 on a LIVE workspace means the session truly is gone.
                    resp.code == 404 -> DeleteOutcome.AlreadyGone
                    else -> DeleteOutcome.Failed("HTTP ${resp.code}")
                }
            }
        }.getOrElse { e ->
            // Explicitly NOT success. A dropped socket is an unknown outcome,
            // and reporting it as deleted is how a chat comes back from the
            // dead after telling the user it was removed.
            DeleteOutcome.Failed("${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
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

