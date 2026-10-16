package com.crush.mobile.data.crush

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Delete must distinguish "gone" from "unknown".
 *
 * THE LIE THIS PINS
 *
 * `deleteSession` used to convert a dropped connection, `InterruptedIOException`,
 * `EOFException` and anything containing "workspace not found" into SUCCESS,
 * on the reasoning that "a session cannot outlive its workspace". That premise
 * is false. Sessions are persistent engine state keyed by the workspace's cwd;
 * the workspace is only an attachment handle that the ~30s reap takes away and
 * hands back.
 *
 * So a timeout proved nothing at all. The app removed the chat locally, reported
 * nothing to the user, and the conversation reappeared in the drawer later. The
 * user had been told - by the absence of an error - that it was gone.
 *
 * The failure was also invisible in the log: `NetworkOnMainThreadException` has
 * a null message, and the log printed only the message, so every attempt read
 * "deleteSession failed: null". A diagnostics aid that can hide its own failure
 * is what let this survive so long.
 */
class DeleteOutcomeTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CrushApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = CrushApi(port = server.port, baseUrlOverride = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun drain() = (0 until server.requestCount).map { server.takeRequest() }

    @Test
    fun `a 204 is Deleted and the request really was issued`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val outcome = api.deleteSession("ws-1", "sess-1")
        assertEquals(DeleteOutcome.Deleted, outcome)
        val req = drain().first()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.contains("/sess-1"))
    }

    @Test
    fun `a 200 is also Deleted`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(DeleteOutcome.Deleted, api.deleteSession("ws-1", "sess-1"))
    }

    @Test
    fun `a 404 on a live workspace is AlreadyGone, not merely unknown`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val outcome = api.deleteSession("ws-1", "sess-1")
        // Genuinely gone: the server answered, on a workspace we know is live.
        assertEquals(DeleteOutcome.AlreadyGone, outcome)
    }

    @Test
    fun `a 500 is Failed, never success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val outcome = api.deleteSession("ws-1", "sess-1")
        assertTrue("500 must not read as deleted", outcome is DeleteOutcome.Failed)
    }

    @Test
    fun `a dropped connection is Failed, not success`() = runBlocking {
        // The critical regression: this used to be reported as a successful
        // delete, so the chat vanished locally and came back on the next list.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val outcome = api.deleteSession("ws-1", "sess-1")
        assertTrue(
            "a dropped socket must be an unknown outcome, got $outcome",
            outcome is DeleteOutcome.Failed
        )
    }

    @Test
    fun `a failure carries a reason the user can be shown`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val outcome = api.deleteSession("ws-1", "sess-1")
        assertTrue(outcome is DeleteOutcome.Failed)
        assertTrue(
            "reason was empty",
            (outcome as DeleteOutcome.Failed).reason.isNotBlank()
        )
    }

    @Test
    fun `the three outcomes are distinguishable`() {
        // Guards against collapsing back to a boolean, which is what caused this.
        val all = listOf<DeleteOutcome>(
            DeleteOutcome.Deleted,
            DeleteOutcome.AlreadyGone,
            DeleteOutcome.Failed("x")
        )
        assertEquals(3, all.distinct().size)
        assertTrue(DeleteOutcome.Deleted::class != DeleteOutcome.AlreadyGone::class)
    }
}
