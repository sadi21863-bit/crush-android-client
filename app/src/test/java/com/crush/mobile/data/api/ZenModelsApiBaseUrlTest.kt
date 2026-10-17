package com.crush.mobile.data.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The catalogue client must call the SELECTED provider's base, not Zen's.
 *
 * The first version of this client had no base parameter and always probed
 * opencode.ai/zen - with a Groq key that answers 401, and the app then showed
 * an empty catalogue that looked like "the provider is broken" rather than
 * "the base is wrong". These tests pin the routing: the base passed to the
 * constructor is the one called, for listing and for the 1-token probe alike,
 * and the responses it parses are the OpenAI-compatible `data[]` shape every
 * carrier provider returns (Groq's here, because that is the provider being
 * tested for end to end).
 */
class ZenModelsApiBaseUrlTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/models") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """{"object":"list","data":[
                                {"id":"openai/gpt-oss-120b","owned_by":"openai"},
                                {"id":"qwen/qwen3.8-27b","owned_by":"Groq"}
                            ]}"""
                        )
                    path.endsWith("/chat/completions") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """{"id":"x","object":"chat.completion","choices":[
                                {"index":0,"message":{"role":"assistant","content":"hi"}}
                            ]}"""
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        base = server.url("/v1").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetch calls the constructed base and parses a groq shaped catalogue`() = runTest {
        val zen = ZenModelsApi({ "test-key" }, base)

        val result = zen.fetch()

        assertTrue("expected success, got: ${result.error}", result.error == null)
        assertEquals(
            listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b"),
            result.models.map { it.id }
        )
        val path = server.takeRequest().path.orEmpty()
        assertTrue("expected <base>/models, was $path", path.endsWith("/v1/models"))
    }

    @Test
    fun `probe calls the constructed base's chat completions`() = runTest {
        val zen = ZenModelsApi({ "test-key" }, base)

        val verdict = zen.probe("openai/gpt-oss-120b").verdict

        assertEquals(ProbeVerdict.WORKS, verdict)
        val path = server.takeRequest().path.orEmpty()
        assertTrue(
            "expected <base>/chat/completions, was $path",
            path.endsWith("/v1/chat/completions")
        )
    }
}
