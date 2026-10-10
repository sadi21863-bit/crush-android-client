package com.opencode.chat.data.crush

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * The filetracker endpoint.
 *
 * Confirmed from the spec: `GET /v1/workspaces/{id}/sessions/{sid}/filetracker/files`
 * returns `array[string]`. The shape is not a guess, but the endpoint was still
 * the source of two subtle problems that a happy-path test would not find.
 */
class TrackedFilesTest {

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

    @Test
    fun `a list of paths is returned`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""["a.kt","src/b.kt"]"""))
        val files = api.trackedFiles("ws-1", "sess-1")
        assertEquals(listOf("a.kt", "src/b.kt"), files)
    }

    @Test
    fun `the request targets the confirmed path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        api.trackedFiles("ws-1", "sess-1")
        val req = server.takeRequest()
        assertEquals(
            "/v1/workspaces/ws-1/sessions/sess-1/filetracker/files",
            req.path
        )
    }

    @Test
    fun `an empty array is empty, not a crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(api.trackedFiles("ws-1", "sess-1").isEmpty())
    }

    @Test
    fun `nulls in the array are dropped rather than crashing`() = runBlocking {
        // Gson parses `null` as JsonNull; treating it as a string path would
        // produce the literal text "null", and the collector would then hunt for
        // a file named null.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""["a.kt",null,"b.kt"]"""))
        assertEquals(listOf("a.kt", "b.kt"), api.trackedFiles("ws-1", "sess-1"))
    }

    @Test
    fun `a non-string element is skipped, not coerced`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""["a.kt",42,true]"""))
        assertEquals(listOf("a.kt"), api.trackedFiles("ws-1", "sess-1"))
    }

    @Test
    fun `a 404 is empty rather than an exception`() = runBlocking {
        // A reaped workspace returns 404. The caller is finishing a turn, and a
        // failure to list files must not fail the turn.
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(api.trackedFiles("ws-1", "sess-1").isEmpty())
    }

    @Test
    fun `a 500 is empty rather than an exception`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(api.trackedFiles("ws-1", "sess-1").isEmpty())
    }

    @Test
    fun `malformed JSON is empty rather than an exception`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))
        assertTrue(api.trackedFiles("ws-1", "sess-1").isEmpty())
    }

    @Test
    fun `an object where an array was expected is empty`() = runBlocking {
        // Trusting the spec blindly to the point of crashing on a different body
        // would turn a diagnostics glitch into a failed chat turn.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":["a.kt"]}"""))
        assertTrue(api.trackedFiles("ws-1", "sess-1").isEmpty())
    }

    @Test
    fun `an empty body is empty rather than an exception`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        assertTrue(api.trackedFiles("ws-1", "sess-1").isEmpty())
    }
}