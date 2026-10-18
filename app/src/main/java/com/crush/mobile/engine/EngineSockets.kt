package com.crush.mobile.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.newsclub.net.unix.AFUNIXSocketFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Transport for talking to the embedded Crush engine over a Unix domain socket
 * (P1, audit F01).
 *
 * The engine is spawned with `-H unix://<socketPath>` (verified supported by
 * enchant/crush v0.98.1 - `ParseHostURL` in `internal/server/server.go` routes
 * the `unix` scheme to `net.Listen("unix", path)`, and the stale-socket probe
 * in `net_other.go` removes leftovers). The socket file is created by the
 * ENGINE in our app-private files directory, so the kernel file permissions are
 * the whole authentication story: a sibling Android UID cannot connect to the
 * path at all. This replaces the previous `-H tcp://127.0.0.1:<port>` spawn,
 * where any app with INTERNET permission could scan the ephemeral port and
 * read config/api_key/user code from an unauthenticated engine API.
 *
 * OkHttp needs a SocketFactory that maps the URL to the socket FILE; junixsocket's
 * `FactoryArg` factory does exactly that, but only accepts the URL-host that
 * matches `org.newsclub.net.unix.socket.hostname` ("localhost" by default).
 * We set the property to 127.0.0.1 once, so all existing `http://127.0.0.1:<port>`
 * client URLs keep working byte-for-byte while the underlying transport is the
 * socket file. The URL port is meaningless in UDS mode (state carries a
 * sentinel; see CrushEngine).
 */
object EngineSockets {
    /** Every engine URL uses this hostname so the factory matches it. */
    const val LOCAL_HOSTNAME = "127.0.0.1"

    init {
        // Must run before any FactoryArg construction; idempotent.
        System.setProperty(
            DefaultSocketHostnameProxy.PROPERTY,
            DefaultSocketHostnameProxy.DEFAULT_HOSTNAME
        )
    }

    /** Socket file path inside the app-private, app-owned directory. */
    fun socketPath(context: Context): File = File(context.filesDir, "crush.sock")

    /**
     * Client for all ENGINE traffic (REST + SSE): connects to the socket file.
     * The URL may still be built as http://127.0.0.1:<port>/... - the factory
     * ignores host and port and connects to [socketFile].
     *
     * Returns null when [socketFile] does not exist or is not yet usable:
     * callers that are probing readiness treat that as "engine not up" exactly
     * as before. Once it exists, subsequent connect failures are real errors
     * and surface as IOExceptions to the existing callers.
     */
    fun okClient(
        socketFile: File,
        client: OkHttpClient? = null
    ): OkHttpClient? {
        if (!socketFile.exists()) return null
        val base = client ?: OkHttpClient()
        return base.newBuilder()
            .socketFactory(AFUNIXSocketFactory.FactoryArg(socketFile))
            .build()
    }

    /** GET helper used by the three readiness/diagnostic probes. */
    suspend fun probe(
        socketFile: File,
        path: String,
        connectTimeoutMs: Long = 1200,
        readTimeoutMs: Long = 1200
    ): Int? = withContext(Dispatchers.IO) {
        // Guard twice: no socket file means the engine has not bound yet -
        // skip any socket construction (and its JNI work) entirely.
        if (!socketFile.exists()) return@withContext null
        runCatching {
            val client = OkHttpClient.Builder().socketFactory(
                AFUNIXSocketFactory.FactoryArg(socketFile)
            ).connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val resp =
                client.newCall(
                    Request.Builder()
                        .url("http://$LOCAL_HOSTNAME$path")
                        .build()
                ).execute()
            resp.use { if (it.isSuccessful) it.code else null }
        }.getOrNull()
    }

    /**
     * Sh solitary placeholder object that both carries the system property
     * names and allows the init {} above to trigger early under the test JVM.
     */
    object DefaultSocketHostnameProxy {
        const val PROPERTY = "org.newsclub.net.unix.socket.hostname"
        const val DEFAULT_HOSTNAME = LOCAL_HOSTNAME
    }
}
