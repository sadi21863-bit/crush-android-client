package com.opencode.chat.data.crush

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Workspace transitions on the long-lived SSE connection.
 *
 * THE BUG THIS PINS
 *
 * `ensureAttached` used to update `workspaceId` and then return whenever the
 * reader thread was alive. So a workspace CHANGE while a stream was open left the
 * old reader blocked on the old workspace's socket while the field claimed the
 * new one. Every subsequent event was attributed to the new workspace, and the
 * thread never exited - so the reconnect never happened and the workspace was
 * reaped after ~30s, leaving the app silently receiving nothing.
 *
 * Crush re-attaches with a NEW workspace id on every restart, so this is the
 * common case, not an edge one.
 */
class CrushConnectionWorkspaceSwitchTest {

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

    private fun connection(): CrushConnection {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        return CrushConnection(
            port = server.port,
            router = router,
            gson = Gson(),
            client = client
        )
    }

    /** A body that never terminates, so the reader stays blocked in readUtf8Line. */
    private fun hangingStream() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"type\":\"message\"}\n\n")
        .setSocketPolicy(SocketPolicy.KEEP_OPEN)

    @Test
    fun `switching workspace replaces the reader rather than reusing it`() {
        // Two separate sockets: ws-A first, then ws-B.
        server.enqueue(hangingStream())
        server.enqueue(hangingStream())

        val conn = connection()
        conn.ensureAttached("ws-A", "client-1")
        val firstThread = conn.readerThreadForTest()
        assertTrue("first reader should be running", firstThread != null)

        conn.ensureAttached("ws-B", "client-1")
        val secondThread = conn.readerThreadForTest()

        assertNotEquals(
            "the reader must be REPLACED on a workspace change; reusing it is the " +
                "stale-socket bug",
            firstThread,
            secondThread
        )
        conn.stop()
    }

    @Test
    fun `the old socket is closed rather than left blocking`() {
        // The leak behind the bug: the old reader sat in readUtf8Line() until the
        // server closed, one thread per re-attach, for the life of the process.
        server.enqueue(hangingStream())
        server.enqueue(hangingStream())

        val conn = connection()
        conn.ensureAttached("ws-A", "client-1")
        val firstRequest = server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue("the first socket should have been opened", firstRequest != null)

        conn.ensureAttached("ws-B", "client-1")

        // Give the cancelled reader a moment to unwind, then confirm the NEW
        // socket was actually opened - which it cannot be unless the old call was
        // cancelled and the thread restarted.
        val secondRequest = server.takeRequest(10, TimeUnit.SECONDS)
        assertTrue(
            "a second socket was never opened, so the reader was not replaced",
            secondRequest != null
        )
        conn.stop()
    }

    @Test
    fun `reattaching the SAME workspace does not churn the reader`() {
        // Idempotence matters as much as the fix: bootstrap calls ensureAttached
        // repeatedly, and replacing the reader each time would lose events.
        server.enqueue(hangingStream())

        val conn = connection()
        conn.ensureAttached("ws-A", "client-1")
        val first = conn.readerThreadForTest()

        conn.ensureAttached("ws-A", "client-1")

        assertEquals(
            "an unchanged workspace must not replace a live reader",
            first,
            conn.readerThreadForTest()
        )
        conn.stop()
    }

    @Test
    fun `a change of client id on the same workspace still replaces the reader`() {
        // Client id scopes the claims the engine holds, so a new client on the old
        // socket is a new session as far as the engine is concerned.
        server.enqueue(hangingStream())
        server.enqueue(hangingStream())

        val conn = connection()
        conn.ensureAttached("ws-A", "client-1")
        val first = conn.readerThreadForTest()

        conn.ensureAttached("ws-A", "client-2")

        assertNotEquals(
            "a client change must replace the reader",
            first,
            conn.readerThreadForTest()
        )
        conn.stop()
    }
}