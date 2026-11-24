package com.crush.mobile.data.crush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V2-15 Phase D: the remaining A7/A7b scenario matrix.
 *
 * The four original cases in [WorkspacePermissionPolicyTest] pin the basic
 * rejection and successful open. This class adds the transport, cancellation,
 * retry, engine-restart, generation-change and concurrency scenarios, and
 * asserts that no credential-bearing request is made on any fail-closed path.
 *
 * The fake is path-keyed and records requests in order; policy responses can be
 * held on a latch so cancellation is deterministic rather than timing-based.
 */
class WorkspacePermissionPolicyScenariosTest {

    private data class Seen(val method: String, val path: String, val body: String)
    private data class Failure(
        val code: Int,
        val body: String,
        val disconnect: Boolean = false,
    )

    private lateinit var server: MockWebServer
    private lateinit var api: CrushApi

    private val seen = CopyOnWriteArrayList<Seen>()
    private val failures = ConcurrentHashMap<String, Failure>()

    /** Runs on the dispatcher thread before the response is produced. */
    @Volatile
    private var onRequest: ((String) -> Unit)? = null

    @Volatile
    private var policyEntered: CountDownLatch? = null

    @Volatile
    private var policyRelease: CountDownLatch? = null

    @Before
    fun setUp() {
        seen.clear()
        failures.clear()
        onRequest = null
        policyEntered = null
        policyRelease = null
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                seen += Seen(request.method.orEmpty(), path, request.body.readUtf8())
                onRequest?.invoke(path)
                if (path.endsWith("/permissions/mode") || path.endsWith("/permissions/skip")) {
                    policyEntered?.countDown()
                    policyRelease?.await(5, TimeUnit.SECONDS)
                }
                failures.entries.firstOrNull { path.endsWith(it.key) }?.value?.let { failure ->
                    if (failure.disconnect) {
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    return MockResponse().setResponseCode(failure.code).setBody(failure.body)
                }
                val body = when {
                    path.endsWith("/workspaces") ->
                        """{"id":"ws-scenario","path":"/tmp","yolo":false}"""
                    path.endsWith("/permissions/mode") -> "{}"
                    path.endsWith("/permissions/skip") -> "{}"
                    path.endsWith("/agent/init") -> "{}"
                    path.endsWith("/config/provider-key") -> "{}"
                    path.endsWith("/config/model") -> "{}"
                    path.endsWith("/agent") -> """{"is_ready":true,"is_busy":false}"""
                    path.endsWith("/sessions") -> """{"id":"sess-1","title":"t"}"""
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
        policyRelease?.countDown()
        server.shutdown()
    }

    private fun openSync(
        manager: WorkspaceManager,
        wire: String? = "ask",
        yolo: Boolean = false,
    ): Workspace = runBlocking {
        manager.open("/data/tmp", "/data/tmp/state", yolo = yolo, permissionModeWire = wire)
    }

    private fun hasInit(): Boolean = seen.any { it.path.endsWith("/agent/init") }
    private fun hasKey(): Boolean = seen.any { it.path.endsWith("/config/provider-key") }
    private fun hasMode(): Boolean = seen.any { it.path.endsWith("/permissions/mode") }
    private fun hasSkip(): Boolean = seen.any { it.path.endsWith("/permissions/skip") }

    @Test
    fun `accepted mode applies policy before init and sends no credentials`() {
        val manager = WorkspaceManager(api)
        val workspace = openSync(manager, "ask")

        val paths = seen.map { it.path }
        val create = paths.indexOfFirst { it.endsWith("/workspaces") }
        val policy = paths.indexOfFirst { it.endsWith("/permissions/mode") }
        val init = paths.indexOfFirst { it.endsWith("/agent/init") }
        assertTrue("create must be first: $paths", create >= 0 && policy > create)
        assertTrue("policy must precede init: $paths", init > policy)
        assertFalse("no legacy skip call on the mode family", hasSkip())
        assertFalse("WorkspaceManager must never read/send the key", hasKey())
        assertEquals("ws-scenario", workspace.id)
        assertEquals("ws-scenario", manager.currentWorkspaceId)
        assertNotNull("a successful apply establishes the lease", manager.lastAttachedAtMs)

        // A successful policy now permits credentials. The policy must still be
        // on the wire before the first provider-key request.
        runBlocking {
            CrushSession(api, manager).ensureAgentReady(
                apiKey = "secret-key",
                providerId = "provider",
                modelId = "model",
            )
        }
        val modeIndex = seen.indexOfFirst { it.path.endsWith("/permissions/mode") }
        val keyIndex = seen.indexOfFirst { it.path.endsWith("/config/provider-key") }
        assertTrue(
            "policy must precede credential use; saw ${seen.map { it.path }}",
            modeIndex >= 0 && keyIndex > modeIndex,
        )
        assertFalse("no legacy skip call on the mode family", hasSkip())
    }

    @Test
    fun `transport failure during policy apply fails closed`() {
        failures["/permissions/mode"] = Failure(0, "", disconnect = true)
        val manager = WorkspaceManager(api)

        val error = runCatching { openSync(manager, "ask") }.exceptionOrNull()

        assertTrue(
            "transport failure must be PermissionPolicyException, got $error",
            error is PermissionPolicyException,
        )
        assertNotNull("the transport cause must be preserved", error?.cause)
        assertFalse("agent/init must not run after a transport failure", hasInit())
        assertFalse("no credentials may be used", hasKey())
        assertNull("the half-attached id must be flushed", manager.currentWorkspaceId)
    }

    @Test
    fun `cancelled policy apply leaves no claim and never initialises`() = runBlocking {
        val manager = WorkspaceManager(api)
        policyEntered = CountDownLatch(1)
        policyRelease = CountDownLatch(1)

        val job = launch(Dispatchers.IO) {
            manager.open(
                "/data/tmp",
                "/data/tmp/state",
                yolo = false,
                permissionModeWire = "ask",
            )
        }
        assertTrue(
            "the policy call must be in flight before cancellation",
            policyEntered!!.await(5, TimeUnit.SECONDS),
        )

        job.cancel()
        policyRelease!!.countDown()
        job.join()

        assertTrue("cancellation must propagate", job.isCancelled)
        assertFalse("agent/init must not run after cancellation", hasInit())
        assertFalse("no credentials may be used", hasKey())
        assertNull(
            "a cancelled open must not leave a usable workspace claim",
            manager.currentWorkspaceId,
        )
    }

    @Test
    fun `cancelled reopen does not let the old fresh lease skip the policy`() = runBlocking {
        val manager = WorkspaceManager(api)
        openSync(manager, "ask") // establishes a fresh lease
        seen.clear()
        policyEntered = CountDownLatch(1)
        policyRelease = CountDownLatch(1)

        // A re-open that changes the policy, cancelled while the policy call is
        // in flight. The old lease is still inside the 15 s window.
        val job = launch(Dispatchers.IO) {
            manager.open(
                "/data/tmp",
                "/data/tmp/state",
                yolo = true,
                permissionModeWire = "yolo",
            )
        }
        assertTrue(
            "the policy call must be in flight before cancellation",
            policyEntered!!.await(5, TimeUnit.SECONDS),
        )
        job.cancel()
        policyRelease!!.countDown()
        job.join()
        assertTrue("cancellation must propagate", job.isCancelled)

        val before = seen.size
        val attached = manager.reattachIfStale()

        assertTrue("the workspace is recoverable", attached)
        assertTrue(
            "a cancelled re-open must re-apply the policy, not trust the old lease; " +
                "saw ${seen.drop(before).map { it.path }}",
            seen.drop(before).any { it.path.endsWith("/permissions/mode") },
        )
        assertTrue(
            "the re-apply must send the NEW mode",
            seen.drop(before).first { it.path.endsWith("/permissions/mode") }.body.contains("\"yolo\""),
        )
        assertFalse(hasKey())
    }

    @Test
    fun `a failed reattach can be retried and then applies the policy`() {
        val manager = WorkspaceManager(api)
        openSync(manager, "ask")
        seen.clear()

        failures["/permissions/mode"] = Failure(500, """{"message":"rejected"}""")
        assertFalse(runBlocking { manager.reattachIfStale(force = true) })
        assertNull(manager.currentWorkspaceId)
        assertFalse("a failed reattach must not init", hasInit())
        assertFalse(hasKey())

        failures.clear()
        seen.clear()
        assertTrue(runBlocking { manager.reattachIfStale(force = true) })
        assertTrue("recovery must re-apply the policy", hasMode())
        assertTrue("recovery must initialise", hasInit())
        assertFalse(hasKey())
        assertEquals("ws-scenario", manager.currentWorkspaceId)
    }

    @Test
    fun `repeated open after a rejection creates and applies again`() {
        val manager = WorkspaceManager(api)
        failures["/permissions/mode"] = Failure(500, """{"message":"rejected"}""")

        val first = runCatching { openSync(manager, "ask") }.exceptionOrNull()
        assertTrue(first is PermissionPolicyException)
        assertFalse("first rejection must not init", hasInit())
        assertFalse(hasKey())
        assertNull(manager.currentWorkspaceId)

        failures.clear()
        val second = openSync(manager, "ask")

        assertEquals(
            "each open must create again; saw ${seen.map { it.path }}",
            2, seen.count { it.path.endsWith("/workspaces") },
        )
        assertEquals(
            "the policy must be attempted on both opens",
            2, seen.count { it.path.endsWith("/permissions/mode") },
        )
        assertEquals(
            "only the successful open may init",
            1, seen.count { it.path.endsWith("/agent/init") },
        )
        assertFalse(hasKey())
        assertEquals("ws-scenario", second.id)
        assertEquals("ws-scenario", manager.currentWorkspaceId)
    }

    @Test
    fun `engine restart between policy and init invalidates the id before key use`() {
        val manager = WorkspaceManager(api)
        onRequest = { path ->
            if (path.endsWith("/permissions/mode")) {
                // Simulate the supervisor announcing a new engine run after the
                // policy was accepted but before agent/init.
                WorkspaceManager.onEngineRestarted()
            }
        }

        val workspace = openSync(manager, "ask")

        assertNull(
            "the id minted before the restart must be flushed on read",
            manager.currentWorkspaceId,
        )
        assertFalse("no credential may be sent after the restart", hasKey())

        seen.clear()
        val ready = runBlocking {
            CrushSession(api, manager).ensureAgentReady(
                apiKey = "secret-key",
                providerId = "provider",
                modelId = "model",
            )
        }
        assertFalse("the session cannot be ready without a current workspace", ready.isReady)
        assertTrue("the reason must be the flushed id: ${ready.notes}", ready.notes.any { it.contains("no workspace") })
        assertFalse("ensureAgentReady must not send the key without a workspace", hasKey())

        onRequest = null
        assertTrue("a later reattach must recover", runBlocking { manager.reattachIfStale(force = true) })
        assertNotNull(manager.currentWorkspaceId)
        assertFalse(hasKey())
    }

    @Test
    fun `generation change during reattach reopens, and a rejected new policy fails closed`() {
        val manager = WorkspaceManager(api)
        openSync(manager, "ask")
        seen.clear()

        WorkspaceManager.onEngineRestarted()
        assertTrue(runBlocking { manager.reattachIfStale(force = true) })
        assertTrue("the reattach must create a new workspace", seen.any { it.path.endsWith("/workspaces") })
        assertTrue("the reattach must re-apply the policy", hasMode())
        assertTrue("the reattach must init", hasInit())
        assertNotNull(manager.currentWorkspaceId)
        assertFalse(hasKey())

        seen.clear()
        WorkspaceManager.onEngineRestarted()
        failures["/permissions/mode"] = Failure(500, """{"message":"rejected"}""")
        assertFalse(runBlocking { manager.reattachIfStale(force = true) })
        assertNull("a rejected policy on the new generation must flush the id", manager.currentWorkspaceId)
        assertFalse(hasInit())
        assertFalse(hasKey())
    }

    @Test
    fun `two overlapping opens converge on one attached workspace`() = runBlocking {
        val manager = WorkspaceManager(api)

        val first = async(Dispatchers.IO) {
            manager.open("/data/tmp", "/data/tmp/state", yolo = false, permissionModeWire = "ask")
        }
        val second = async(Dispatchers.IO) {
            manager.open("/data/tmp", "/data/tmp/state", yolo = false, permissionModeWire = "ask")
        }
        val a = first.await()
        val b = second.await()

        assertEquals("ws-scenario", a.id)
        assertEquals("ws-scenario", b.id)
        assertEquals("ws-scenario", manager.currentWorkspaceId)
        assertNotNull(manager.lastAttachedAtMs)
        assertFalse("no credentials on any open", hasKey())
        assertTrue(
            "both opens must apply the policy; saw ${seen.map { it.path }}",
            seen.count { it.path.endsWith("/permissions/mode") } >= 2,
        )
    }
}
