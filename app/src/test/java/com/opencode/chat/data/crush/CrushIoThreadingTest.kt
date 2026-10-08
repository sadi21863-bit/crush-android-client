package com.opencode.chat.data.crush

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Guards against `NetworkOnMainThreadException` in the Crush client.
 *
 * WHY THIS EXISTS
 *
 * `deleteSession` and `health` both called OkHttp's synchronous `execute()`
 * from a `suspend fun` WITHOUT `withContext(Dispatchers.IO)`. Being suspend
 * only permits suspension; it does not change threads. Both therefore threw
 * NetworkOnMainThreadException the moment they were called from a ViewModel.
 *
 * Delete was broken for a long time and the log said only
 * "deleteSession failed: null", because that exception has a NULL message.
 * The failure was invisible precisely because the error text was empty.
 *
 * The two tests below cannot detect a main-thread call directly under plain
 * JVM (StrictMode is Android-only), so they assert the far more useful thing:
 * that the request actually REACHES the server. A method that throws before
 * the call never gets a recorded request, which is exactly how the bug
 * presented - a delete that looked like it worked and did nothing.
 */
class CrushIoThreadingTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CrushApi

    @Before
    fun setUp() {        server = MockWebServer()
        server.start()
        api = CrushApi(port = server.port, baseUrlOverride = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `deleteSession actually issues the DELETE request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        runCatching { api.deleteSession("ws-1", "sess-1") }
        val recorded = drain()
        assertTrue(
            "deleteSession never reached the server - it must have thrown before " +
                "the call (NetworkOnMainThreadException has a null message, so this " +
                "failure was invisible for a long time)",
            recorded.any { it.method == "DELETE" && it.path!!.contains("/sess-1") }
        )
    }

    @Test
    fun `health actually issues the request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        api.health()
        val recorded = drain()
        assertTrue(
            "health never reached the server - a probe that throws before the " +
                "call reports 'unhealthy' and looks like an engine outage",
            recorded.any { it.path!!.contains("/v1/health") }
        )
    }

    @Test
    fun `health reports false rather than throwing when the server errors`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))
            assertTrue("health should be false on 500, not an exception", !api.health())
        }

    private fun drain() = (0 until server.requestCount).map { server.takeRequest() }
}


