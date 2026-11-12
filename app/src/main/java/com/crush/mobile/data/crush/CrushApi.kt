package com.crush.mobile.data.crush

import com.crush.mobile.engine.EngineSockets
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
import java.io.File
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

/**
 * How a REST request can actually leave this process (S-04 / P1-6, 2026-11-05).
 *
 * This used to be implicit: `buildStreamClient()` built a UDS client when the
 * socket file happened to exist and otherwise SILENTLY handed back a plain TCP
 * client (`okClient(...) ?: plain`), so the transport a request took depended on
 * a boot-order accident. The referenced finding is that the shape can leak the
 * provider key into a non-private channel in unlucky builds.
 *
 * The events transport got honest per-attempt resolution in 349bad3; this is
 * the REST-side equivalent. There is deliberately NO fallback case reachable
 * from a socket-path construction: absent means [Unavailable], never plain.
 */
sealed interface RestTransport {
    /** Bound to the engine's unix socket file: app-private, kernel-permission gated. */
    data class Uds(val client: OkHttpClient, val socketPath: String) : RestTransport

    /**
     * The requested socket file is not on disk yet. The reason is already
     * scrubbed and carries the same wording CrushConnection uses for events so
     * app.log greps identically across both transports.
     */
    data class Unavailable(val reason: String) : RestTransport

    /**
     * TEST-ONLY plain TCP: selected exclusively by the [CrushApi] construction
     * with an explicit [CrushApi.baseUrlOverride] (MockWebServer on the JVM and
     * the override-based spike shapes). To keep the test affordance explicit,
     * the only producer is [CrushApi.plainClientForTests].
     */
    data class TcpForTests(val client: OkHttpClient) : RestTransport
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
    private val baseUrlOverride: String? = null,
    /**
     * Unix-socket transport: pass [EngineSockets.socketPath] here once the
     * engine is spawned with `-H unix://<path>` instead of binding TCP. All
     * URLs still read `http://127.0.0.1:<port>/...` - the FactoryArg socket
     * factory matches the host and ignores the port, so nothing else changes,
     * and the port itself is only a sentinel (CrushEngine.UDS_PORT_SENTINEL).
     *
     * Non-null while the file is NOT YET on disk (bootorder: bindEngine starts
     * bootstrap as soon as the supervisor reports Running, and the child binds
     * the socket a moment later): there is now NO fallback client. Every call
     * resolves the transport at call time and, while absent, fails with
     * `rest: socket not present yet: <scrubbed>` - the same honest per-call
     * contract 349bad3 gave the events stream - which the existing engine
     * boundary re-triggers re-run the ladder on without any caller change.
     */
    private val socketPath: File? = null
) {

    /**
     * S-04 / P1-6 (2026-11-05): refuse the BARE production construction shape at
     * instantiation time, not by convention.
     *
     * Every production caller passes [socketPath]; every test caller passes
     * [baseUrlOverride] (MockWebServer) or [socketPath]. A construction with
     * NEITHER used to be exactly the shape that silently produced a plain-TCP
     * client, so a socket path forgotten at a future call site would look
     * healthy until an unlucky build - the accept-surface regression the
     * finding calls out. Making the bare shape an error keeps the test-only TCP
     * affordance explicit rather than ambient.
     */
    init {
        if (socketPath == null && baseUrlOverride == null) {
            throw IllegalStateException(
                "CrushApi needs socketPath (or an explicit baseUrlOverride for tests): " +
                    "the engine runs unauthenticated, so production must never dial plain TCP (S-04)"
            )
        }
    }

    /**
     * A dedicated client for streaming: no read timeout, because Crush sends no
     * heartbeat and an idle workspace legitimately produces zero bytes.
     *
     * S-04 / P1-6 (2026-11-05): resolves PER CALL, like CrushConnection resolves
     * PER ATTEMPT after 349bad3. The previous buildStreamClient() pinned a
     * client at construction and silently fell back to plain TCP when the
     * socket file was absent (`okClient(...) ?: plain`) - an unauthenticated
     * control plane that can lose the provider key leaking into a non-private
     * channel. Now a socket path that is not on disk yet yields no client at
     * all: the property access throws `rest: socket not present yet: <scrubbed>`;
     * the recipient is an IOException exactly like the connect-refused it used
     * to mask, so every existing caller ladder keeps working with no code
     * change. Once the file is bound the same access heals on any later call,
     * without recreating CrushApi - the timing window (bindEngine fires
     * bootstrap the instant the supervisor reports Running, while the engine
     * binds the socket a moment later) is closed by the engine-boundary
     * re-triggers that already rerun bootstrap on state changes and user sends.
     */
    val streamClient: OkHttpClient
        get() = when (val transport = resolveTransportNow()) {
            is RestTransport.Uds -> transport.client
            is RestTransport.TcpForTests -> transport.client
            is RestTransport.Unavailable -> throw IOException("rest: ${transport.reason}")
        }

    /**
     * Streaming-shaped prototype the UDS client and, explicitly for tests, the
     * plain client are both built from. Kept byte-for-byte from the pre-S-04
     * fallback builder so a migration of transport never smuggles in a timeout
     * change.
     */
    private val streamPrototype: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Built once per [CrushApi] and reused: the factory pins the PATH, not the
     * file handle, so a socket deleted and rebound at the same path (Go unlinks
     * on close, then the child rebinds during restart) is followed by the very
     * same client - matching what CrushConnection already established for the
     * events transport. The cache is only served while the file still exists:
     * once it is unlinked again (engine down), calls report the honest
     * "socket not present yet" wording instead of a raw ENOENT, keeping one
     * grep line for every transport-absent phase. Never a fallback.
     */
    @Volatile
    private var udsClientCache: OkHttpClient? = null

    private val testTcpClient: OkHttpClient by lazy { plainClientForTests() }

    private val testTcpSelect: RestTransport.TcpForTests by lazy {
        RestTransport.TcpForTests(testTcpClient)
    }

    /**
     * The transport as of RIGHT NOW. Performs no network work and never blocks:
     * resolving is an existence check against the socket file. Internal so the
     * transport contract is observable by tests without issuing a request.
     */
    internal fun resolveTransportNow(): RestTransport {
        // The elvis-return is intentional and LOAD-BEARING: null socketPath has
        // no honest UDS transport to offer, and returning the test-TCP selector
        // here is the ONLY route plain TCP can ever be selected from.
        val path = socketPath ?: return testTcpSelect
        udsClientCache?.takeIf { path.exists() }?.let {
            return RestTransport.Uds(it, path.absolutePath)
        }
        // Reading through EngineSockets (not a bare factory) preserves the
        // guarantee that object's init has run before any FactoryArg is
        // constructed, so the 127.0.0.1 URL host is recognised. Null while the
        // file is absent is EngineSockets' own documented contract.
        val built = EngineSockets.okClient(path, streamPrototype)
        return if (built != null) {
            udsClientCache = built
            RestTransport.Uds(built, path.absolutePath)
        } else {
            RestTransport.Unavailable(
                "socket not present yet: ${CrushConnection.scrubbedPath(path.absolutePath)}"
            )
        }
    }

    private val client: OkHttpClient
        get() = streamClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

private val json = "application/json".toMediaType()
    /**
     * internal visibility is test-facade only (S-04, 2026-11-05): the URL shape
     * is pinned by test (`http://127.0.0.1:<port>`, never a tcp:// scheme, the
     * port being the UDS sentinel that carries no meaning in socket mode).
     */
    internal val base get() = baseUrlOverride ?: "http://127.0.0.1:$port"

    /**
     * TEST-ONLY; production never dials TCP (S-04 / P1-6, 2026-11-05).
     *
     * The pure-socket production construction has no path to a plain client at
     * all: resolveTransportNow() can return Uds or Unavailable and nothing else,
     * and this helper is reached solely from the override-marked test
     * construction (TcpForTests, see above) and from test code. Marked
     * internal for test reachability, in the spirit of normalizeApiKey: the
     * affordance must stay explicit and greppable rather than ambient.
     *
     * Returns the streaming-shaped client byte-identical to the one the deleted
     * fallback builder used to keep, so MockWebServer-based tests see the same
     * timeouts as before the fallback was removed.
     */
    internal fun plainClientForTests(): OkHttpClient = streamPrototype

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

    /**
     * Reports which session this client is viewing (engine v0.98.2).
     *
     * Permission/question prompts are scoped: they are delivered only to
     * clients whose reported current session matches the one that raised the
     * prompt. An empty sessionId clears the entry (the landing-screen state),
     * and a client that never reports at all fails OPEN instead - which is why
     * reporting is optional-but-doing-it is correct: without a report we are
     * still stuck with v0.98.1-style broadcast behaviour, receiving prompts
     * raised in sessions we are not looking at.
     *
     * `client_id` is REQUIRED on this route (a UUID - without it the call is a
     * 400) and MUST be the very same id the SSE stream attached with, because
     * prompt scoping is keyed on that client identity. Callers therefore pass
     * `WorkspaceManager.clientId`, the same value handed to
     * [attachEventStream]. Engine v0.98.1 has no such route at all: the
     * failure then logs a warning and everything behaves as before (D12).
     */
    suspend fun currentSession(workspaceId: String, clientId: String, sessionId: String = "") {
        postEmpty(
            "/v1/workspaces/$workspaceId/current-session?client_id=$clientId",
            mapOf("session_id" to sessionId)
        )
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
     * v0.100.0+ replacement for [skipPermissions] (that endpoint was REMOVED
     * upstream). [mode] is "normal" | "yolo" | "sysadmin"; this app never
     * sends "sysadmin" (EngineProtocol.modeFor). EngineProtocol picks the
     * family from the bound engine's observed version — never both calls.
     */
    suspend fun setPermissionMode(workspaceId: String, mode: String) {
        postEmpty("/v1/workspaces/$workspaceId/permissions/mode", PermissionModeBody(mode))
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

