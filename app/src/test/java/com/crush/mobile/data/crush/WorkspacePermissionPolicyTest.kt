package com.crush.mobile.data.crush

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A7/A7b (2026-11-23): a rejected permission policy is a HARD open failure.
 *
 * The regression this pins: `open()` used to log a failed
 * `permissions/mode` / `permissions/skip`, continue into `agent/init`, and
 * return a workspace. The UI could say ASK while the engine kept an earlier
 * auto-approve policy, and `reattachIfStale` replayed the request as if it had
 * been applied.
 *
 * Path-keyed dispatcher, not a response queue: open() makes create, policy and
 * init calls whose order/count must not make the fake answer the wrong endpoint.
 */
class WorkspacePermissionPolicyTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CrushApi

    private val seen = mutableListOf<String>()

    /** "mode" fails `/permissions/mode`; "skip" fails `/permissions/skip`. */
    private var failPermission: String? = null

    @Before
    fun setUp() {
        seen.clear()
        failPermission = null
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                seen += path
                val fail = when (failPermission) {
                    "mode" -> path.endsWith("/permissions/mode")
                    "skip" -> path.endsWith("/permissions/skip")
                    else -> false
                }
                if (fail) {
                    return MockResponse().setResponseCode(500)
                        .setBody("""{"message":"permission mode rejected"}""")
                }
                val body = when {
                    path.endsWith("/workspaces") ->
                        """{"id":"ws-1","path":"/tmp","yolo":true}"""
                    path.endsWith("/permissions/mode") -> "{}"
                    path.endsWith("/permissions/skip") -> "{}"
                    path.endsWith("/agent/init") -> "{}"
                    path.endsWith("/agent") -> """{"is_ready":true,"is_busy":false}"""
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

    private fun open(manager: WorkspaceManager, wire: String?) = runBlocking {
        manager.open(
            "/data/tmp",
            "/data/tmp/state",
            yolo = wire == null,
            permissionModeWire = wire,
        )
    }

    @Test
    fun `rejected mode policy fails the open and never initialises the agent`() {
        failPermission = "mode"
        val manager = WorkspaceManager(api)

        val error = runCatching { open(manager, "ask") }.exceptionOrNull()

        assertTrue(
            "a 500 on permissions/mode must throw PermissionPolicyException, got $error",
            error is PermissionPolicyException,
        )
        assertTrue(
            "the exception names the requested mode",
            (error as PermissionPolicyException).requestedMode == "ask",
        )
        assertFalse(
            "agent/init must be skipped when the policy was rejected: $seen",
            seen.any { it.endsWith("/agent/init") },
        )
        assertNull(
            "the workspace id must be flushed so no turn can use the half-configured workspace",
            manager.currentWorkspaceId,
        )
        assertFalse(
            "no credential-bearing request may follow a rejected policy: $seen",
            seen.any { it.endsWith("/config/provider-key") },
        )
    }

    @Test
    fun `rejected legacy skip policy fails the open and never initialises the agent`() {
        failPermission = "skip"
        val manager = WorkspaceManager(api)

        val error = runCatching { open(manager, wire = null) }.exceptionOrNull()

        assertTrue(
            "a 500 on permissions/skip must throw PermissionPolicyException, got $error",
            error is PermissionPolicyException,
        )
        assertTrue(
            "the exception names the legacy skip family",
            (error as PermissionPolicyException).requestedMode.startsWith("skip="),
        )
        assertFalse(
            "agent/init must be skipped when the policy was rejected: $seen",
            seen.any { it.endsWith("/agent/init") },
        )
        assertNull(manager.currentWorkspaceId)
        assertFalse(
            "no credential-bearing request may follow a rejected policy: $seen",
            seen.any { it.endsWith("/config/provider-key") },
        )
    }

    @Test
    fun `accepted policy still runs agent init and keeps the workspace`() {
        val manager = WorkspaceManager(api)

        val result = runCatching { open(manager, "ask") }

        assertTrue("a 2xx policy apply must open: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(
            "agent/init must still run after a successful apply: $seen",
            seen.any { it.endsWith("/agent/init") },
        )
        assertTrue(manager.currentWorkspaceId != null)
    }

    @Test
    fun `a failed reattach reports not attached and flushes the id`() {
        val manager = WorkspaceManager(api)
        assertTrue(runCatching { open(manager, "ask") }.isSuccess)
        assertTrue(manager.currentWorkspaceId != null)

        failPermission = "mode"
        val attached = runBlocking { manager.reattachIfStale(force = true) }

        assertFalse("a rejected policy must not report the workspace attached", attached)
        assertNull(
            "the stale id must be flushed after the failed reattach",
            manager.currentWorkspaceId,
        )
    }
}
