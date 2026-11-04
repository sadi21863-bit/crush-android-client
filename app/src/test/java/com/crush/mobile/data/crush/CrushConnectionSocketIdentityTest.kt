package com.crush.mobile.data.crush

import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE BUG THIS PINS (S0 / review F03, 2026-11-04).
 *
 * The events transport used to be ONE OkHttpClient pinned at construction.
 * Both flaws stem from that pin:
 *
 *  1. IDENTITY BY PORT. `attachEventStream` reused a connection whenever the
 *     port was unchanged - and every engine reports the sentinel UDS port 1.
 *     An instance switch within one ViewModel therefore kept streaming the
 *     PREVIOUS instance's socket file.
 *  2. A PINNED FACTORY OUTLIVING ITS FILE. When the previously bound engine
 *     was stopped (Go's UnixListener unlinks the socket file on close), the
 *     pinned factory pointed at a DELETED file and the retry loop failed with
 *     "No such file or directory" for the whole turn, while call-time REST
 *     (built per call) kept succeeding - the run completed, the banner lied.
 *
 * The fix resolves the transport PER ATTEMPT from the connection's socket
 * file: a missing file fails one honest attempt, a respawned socket at the
 * same path is followed by the next retry, and plain-TCP can never appear as
 * an implicit fallback.
 */
class CrushConnectionSocketIdentityTest {

    private lateinit var server: MockWebServer
    private lateinit var router: CrushEventRouter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        router = CrushEventRouter()
    }

    @After
    fun tearDown() {
        router.close()
        runCatching { server.shutdown() }
    }

    /** A body that never terminates, so the reader stays in readUtf8Line. */
    private fun hangingStream() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"type\":\"message\"}\n\n")
        .setSocketPolicy(SocketPolicy.KEEP_OPEN)

    @Test
    fun `a not-yet-bound socket fails attempts honestly and the respawned socket is followed`() {
        val plain = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val attempts = AtomicInteger(0)
        val connection = CrushConnection(
            port = server.port,
            router = router,
            gson = Gson(),
            socketFile = File("/tmp/identity-test.sock"),
            // Attempt 1: the engine has not bound yet (file absent). Every
            // later attempt: the engine respawned and bound the SAME path.
            clientSource = { if (attempts.incrementAndGet() == 1) null else plain }
        )
        server.enqueue(hangingStream())

        connection.ensureAttached("ws-A", "client-1")

        // The first attempt must fail BEFORE reaching the server (no request
        // can exist for it), and the retry must pick up the now-present
        // socket and reach CONNECTED without any caller-side rebuild.
        runBlocking {
            withTimeout(15_000) {
                connection.state.first { it == CrushConnection.State.CONNECTED }
            }
        }
        assertTrue(
            "the transport must have been resolved more than once (per attempt), got " +
                "${attempts.get()}",
            attempts.get() >= 2
        )
        assertEquals(
            "the request that finally connected must exist (the failed attempt " +
                "never opened a socket)",
            1,
            server.requestCount
        )
        connection.stop()
    }
}
