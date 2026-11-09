package com.crush.mobile.data.crush

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
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
        // Subscribe first, then start the reader: the ordering guarantee
        // lives at the comment inside the runBlocking block below.
        server.enqueue(hangingStream())

        // The first attempt must fail BEFORE reaching the server (no request
        // can exist for it), and the retry must pick up the now-present
        // socket and reach CONNECTED without any caller-side rebuild.
        //
        // Subscribe BEFORE attach (hardening 2026-11-07, after this suite's
        // first CI failure in a 40-run history landed on 2026-11-06, with a
        // same-day repeat on a different environment). The reader thread is
        // started inside ensureAttached below and can reach CONNECTED in
        // ~250ms, so a collector pieced together on the following lines raced
        // the publisher. A StateFlow leaks a pre-subscription value to a
        // later first{} only while the state STAYS connected - true for the
        // KEEP_OPEN stream in the common case, but that stability must not
        // be load-bearing for this assert. Subscribing first makes the
        // interleaving irrelevant.
        //
        // Budget 60s (was 30s, was 15s): the honest-fail ladder routes
        // success through real wall time. Its sleeps for six consecutive
        // failed attempts sum 250+500+1000+2000+4000+8000 = 15.75s, which is
        // DEEPER than the old 15s horizon in one failure run, and ALL THREE
        // observed failures (2026-11-06; 2026-11-08 twice, both on docs-only
        // commits with zero code delta) were full-suite runs on loaded
        // 2-core/2-vCPU CI hosts where the entire window was consumed inside
        // a single sustained stall - every immediate rerun was green. 60s
        // keeps a generous 3.8x headroom over the 15.75s ladder while
        // remaining a real bound. This test pins transport identity and
        // honest attempt shape, not a wall clock - the asserts below still
        // pin the green path exactly (>=2 probes, exactly one server
        // request), and a wrong-shape recovery surfaces as this timeout, not
        // as a wrong pass.
        // STRIKE-DIAGNOSTICS (2026-11-09, after the fourth strike consumed the
        // 60s horizon inside a local full-suite run): the bare withTimeout
        // surfaced a stackless TimeoutCancellationException, so every strike
        // was recorded as "it happened" with no view of where the ladder
        // stood. On lapse the SAME failure is now rethrown as an assertion
        // carrying the strike fingerprint - attempts served, server requests
        // actually received, connection state, and reader-thread aliveness.
        // attempts==1 with no server request means the reader never returned
        // from the first honest fail (host stall or reader-thread death);
        // attempts>=2 with no request pins the stall inside the ladder before
        // any socket opened; a request present means the reader/collector
        // boundary missed a completed CONNECTED. The failure shape is
        // unchanged; this only makes the next strike diagnosable to its
        // branch (the diagnosis doc records the ledger and the branches).
        try {
            runBlocking {
                withTimeout(60_000) {
                    // UNDISPATCHED runs the body inline on this thread until
                    // its first suspension, so the collector IS subscribed
                    // before ensureAttached runs below (a dispatching async
                    // would only queue the body and lose the ordering).
                    val connected = async(start = CoroutineStart.UNDISPATCHED) {
                        connection.state.first { it == CrushConnection.State.CONNECTED }
                    }
                    connection.ensureAttached("ws-A", "client-1")
                    connected.await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            val reader = connection.readerThreadForTest()
            throw AssertionError(
                "the 60s horizon lapsed before CONNECTED - strike fingerprint: " +
                    "attempts=${attempts.get()}, serverRequests=${server.requestCount}, " +
                    "state=${connection.state.value}, readerThreadAlive=" +
                    "${reader?.isAlive ?: "absent"}; attempts==1 with no server " +
                    "request means the reader never returned from the first " +
                    "honest fail (host stall or reader-thread death); " +
                    "attempts>=2 with no request means the ladder itself was " +
                    "stalled before any socket opened",
                e,
            )
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
