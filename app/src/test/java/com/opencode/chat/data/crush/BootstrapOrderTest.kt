package com.opencode.chat.data.crush

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
 * The bootstrap ORDER is load-bearing and was established purely empirically on
 * device. Getting it wrong produces confusing failures:
 *
 *  - key after agent/init  -> HTTP 500 "coder agent configuration is missing"
 *  - model after init      -> every turn 400s "Model is unavailable", because
 *                             the coordinator captured the retired default
 *
 * NOTE ON TEST DESIGN: an earlier version of this file enqueued a FIXED list of
 * responses and they failed, because openWorkspace(yolo = true) fires an extra
 * `permissions/skip` call nobody counted. A positional queue silently shifts
 * responses onto the wrong endpoint and produces failures that look like app
 * bugs. This uses a path-keyed Dispatcher instead, so each endpoint answers
 * correctly no matter how many calls happen or in what order.
 */
class BootstrapOrderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CrushApi

    /** Every request the client made, in order, for order assertions. */
    private val seen = mutableListOf<String>()

    /** Endpoints that should fail, mapped to status + body. */
    private val failures = mutableMapOf<String, Pair<Int, String>>()

    @Before
    fun setUp() {
        seen.clear()
        failures.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                seen += path
                failures[path]?.let { (code, body) ->
                    return MockResponse().setResponseCode(code).setBody(body)
                }
                val body = when {
                    path.endsWith("/workspaces") -> """{"id":"ws-1","path":"/tmp","yolo":true}"""
                    path.endsWith("/permissions/skip") -> "{}"
                    path.endsWith("/config/provider-key") -> "{}"
                    path.endsWith("/config/model") -> "{}"
                    path.endsWith("/agent/init") -> "{}"
                    path.endsWith("/agent") -> """{"is_ready":true,"is_busy":false}"""
                    path.endsWith("/sessions") -> """{"id":"sess-1","title":"t"}"""
                    path.contains("/messages") -> "[]"
                    else -> "{}"
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
        api = CrushApi(0, baseUrlOverride = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

// No production default: a model id must be supplied by the caller after a
    // live check. "probe-me" stands in for whatever firstWorking() returned.
    private suspend fun bootstrap(modelId: String = "probe-me") =
        WorkspaceManager(api).let { mgr ->
            val s = CrushSession(api, mgr)
            s.openWorkspace("/data/tmp", "/data/tmp/state")
            s.ensureAgentReady("oc_sk_test", providerId = "opencode-zen", modelId = modelId)
        }

    /** Drains every recorded request. MockWebServer has no bulk accessor. */
    private fun drainRequests(): List<RecordedRequest> {
        val n = server.requestCount
        return (0 until n).map { server.takeRequest() }
    }

@Test
    fun `bootstrap sets key and model BEFORE the final agent init`() = runTest {
        val ready = bootstrap()

        assertTrue("bootstrap should succeed: ${ready.notes}", ready.isReady)

        // OBSERVED WIRE ORDER (this assertion was originally wrong):
        //   /workspaces -> permissions/skip -> agent/init -> provider-key
        //   -> config/model -> config/model -> agent/init
        //
        // WorkspaceManager.open() starts the agent itself, so an init happens
        // BEFORE any key exists. That first init is redundant: it historically
        // returned 500 "coder agent configuration is missing", and its failure
        // is only collected into notes. What actually matters is that the LAST
        // init happens after the key and model are in place, because the
        // coordinator snapshots them.
        val lastKey = seen.indexOfLast { it.endsWith("/config/provider-key") }
        val lastModel = seen.indexOfLast { it.endsWith("/config/model") }
        val lastInit = seen.indexOfLast { it.endsWith("/agent/init") }

        assertTrue("provider-key must be called, saw $seen", lastKey >= 0)
        assertTrue("config/model must be called, saw $seen", lastModel >= 0)
        assertTrue("agent/init must be called, saw $seen", lastInit >= 0)

        assertTrue("key must precede the FINAL init (order=$seen)", lastKey < lastInit)
        assertTrue("model must precede the FINAL init (order=$seen)", lastModel < lastInit)

        val initCount = seen.count { it.endsWith("/agent/init") }
        assertEquals("agent is initialised twice (see note above)", 2, initCount)
    }

    @Test
    fun `both large and small model slots are set`() = runTest {
        bootstrap()
        val modelCalls = drainRequests()
            .filter { it.path?.endsWith("/config/model") == true }
        assertEquals(
            "both slots must be set; saw ${seen}",
            2, modelCalls.size
        )
        val bodies = modelCalls.map { it.body.readUtf8() }
        assertTrue("expected a large slot in $bodies", bodies.any { it.contains("\"large\"") })
        assertTrue("expected a small slot in $bodies", bodies.any { it.contains("\"small\"") })
    }

    @Test
    fun `api key is sent as a quoted string never a byte array`() = runTest {
        // The published spec wrongly declares api_key as an integer array;
        // Crush models it as json.RawMessage and 400s with
        // "decode api key string:" otherwise.
        bootstrap()
        val req = drainRequests().first { it.path!!.endsWith("/config/provider-key") }
        val body = req.body.readUtf8()
        assertTrue("api_key must be a quoted string, got: $body", body.contains("\"api_key\":\"oc_sk_test\""))
        assertTrue("must not be an array, got: $body", !body.contains("api_key\":["))
    }

    @Test
    fun `NAME=value key is stripped before it hits the wire`() = runTest {
        WorkspaceManager(api).let { mgr ->
            val s = CrushSession(api, mgr)
            s.openWorkspace("/data/tmp", "/data/tmp/state")
            s.ensureAgentReady("OPENCODE_API_KEY=oc_sk_test", "opencode-zen", "probe-me")
        }
        val req = drainRequests().first { it.path!!.endsWith("/config/provider-key") }
        val body = req.body.readUtf8()
        assertTrue(
            "prefix must be stripped on the wire, got: $body",
            body.contains("\"api_key\":\"oc_sk_test\"")
        )
    }

    @Test
    fun `provider key failure is reported but bootstrap still continues`() = runTest {
        failures["/v1/workspaces/ws-1/config/provider-key"] =
            500 to """{"error":"nope"}"""
        val ready = bootstrap()
        assertTrue(
            "failure must appear in notes: ${ready.notes}",
            ready.notes.any { it.contains("FAILED") }
        )
        // Must still attempt init so the user sees combined notes, not a no-op.
        assertTrue("init should still be attempted, saw $seen", seen.any { it.endsWith("/agent/init") })
    }

    @Test
    fun `retired model failure is surfaced in notes`() = runTest {
        // What actually happens with deepseek-v4-flash-free today.
        failures["/v1/workspaces/ws-1/config/model"] =
            400 to """{"error":"Upstream request failed: Model is unavailable."}"""
        val ready = bootstrap(modelId = "deepseek-v4-flash-free")
        assertTrue(
            "notes should mention the model failure: ${ready.notes}",
            ready.notes.any { it.contains("model[large] FAILED") }
        )
    }

@Test
    fun `a failing init is recorded even when the agent reports ready`() = runTest {
        // Readiness is decided by /agent (is_ready), NOT by whether init
        // succeeded. A failing init is a real problem and must appear in notes
        // so it is not silently swallowed - the original "ready" bug was
        // provider failures being reported as success.
        failures["/v1/workspaces/ws-1/agent/init"] = 400 to
            """{"error":"agent coordinator not initialized"}"""

        val ready = bootstrap()

        assertTrue(
            "init failure must be recorded: ${ready.notes}",
            ready.notes.any { it.contains("agent/init FAILED") }
        )
        // agentInfo still says ready, so isReady reflects reality, not the
        // failed init call.
        assertEquals(true, ready.isReady)
    }
}
