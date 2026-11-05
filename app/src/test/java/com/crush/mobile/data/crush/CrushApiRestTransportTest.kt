package com.crush.mobile.data.crush

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.newsclub.net.unix.AFUNIXSocketFactory
import java.io.File
import java.io.IOException

/**
 * THE CONTRACT THIS PINS (S-04 / P1-6, 2026-11-05).
 *
 * The REST layer used to build its transport at construction and silently fall
 * back to a plain TCP client the moment the socket file was absent:
 * `EngineSockets.okClient(socketPath, plain) ?: plain`. The events stream lost
 * that fallback in 349bad3 (per-attempt honest resolution); this pins the
 * REST-side equivalent:
 *
 *  - a socket path with no file on disk yields NO client at all and surfaces a
 *    typed Unavailable carrying the same `"socket not present yet: <scrubbed>"`
 *    wording CrushConnection uses for events;
 *  - the transport heals on the SAME instance once the engine binds the file
 *    (per-call resolution, matching how the boot ladder re-enters);
 *  - the only plain-TCP producer is the explicitly marked test-only helper,
 *    reachable through the baseUrlOverride construction shape alone;
 *  - a bare production construction shape is refused outright.
 *
 * Everything here is pure JVM: the UDS path is only ever used for an existence
 * check and a factory construction, never a connect (connects to the app's
 * unix socket happen on the engine process, on device).
 */
class CrushApiRestTransportTest {

    private val createdSocketFiles = mutableSetOf<File>()

    @After
    fun tearDown() {
        createdSocketFiles.forEach { runCatching { it.delete() } }
    }

    /** A socket path whose file provably does not exist yet (pre-boundary). */
    private fun absentSocketFile(): File = File.createTempFile("crush-rest-absent", ".sock").apply {
        // createTempFile makes the file exist; the CONTRACT under test is the
        // pre-spawn window, so remove it and keep only the path.
        delete()
        createdSocketFiles.add(this)
    }

    @Test
    fun `bare production construction is refused instead of a silent plain client`() {
        // S-04: the exact shape that used to produce an ambient plain client.
        // Every production site passes socketPath; every test site passes
        // baseUrlOverride or socketPath, so the refusal has no call victims.
        val error = runCatching { CrushApi(1) }.exceptionOrNull()
        assertTrue(
            "constructing CrushApi with neither socketPath nor baseUrlOverride must " +
                "fail loudly (was: silent plain TCP selection up to this finding)",
            error is IllegalStateException
        )
    }

    @Test
    fun `socket absent yields no client and surfaces the typed unavailable honestly`() {
        val absent = absentSocketFile()
        val api = CrushApi(port = 1, socketPath = absent)

        val transport = api.resolveTransportNow()
        val unavailable = transport as? RestTransport.Unavailable
        assertTrue(
            "expected the typed Unavailable, got: $transport",
            unavailable != null
        )
        assertEquals(
            "the reason must be scrubbed through the same helper the events " +
                "transport uses (CrushConnection.scrubbedPath) so app.log greps " +
                "identically across both transports",
            "socket not present yet: ${CrushConnection.scrubbedPath(absent.absolutePath)}",
            unavailable!!.reason
        )

        // No client instance escapes: merely OBTAINING a client throws the
        // honest failure instead of returning something that dials TCP.
        val clientError = runCatching { api.streamClient }.exceptionOrNull()
        assertTrue(
            "streamClient access must fail while the socket is absent, got: $clientError",
            clientError is IOException
        )
        assertEquals(
            "rest must mirror the events wording with only the context tag changed",
            "rest: socket not present yet: ${CrushConnection.scrubbedPath(absent.absolutePath)}",
            clientError?.message
        )

        // End-to-end request path (the ladder entry point for createWorkspace):
        // version() has the same suspend shape WorkspaceManager.open wraps;
        // runBlocking only stands in for the coroutine the callers use.
        val requestError = runBlocking { runCatching { api.version() }.exceptionOrNull() }
        assertTrue(
            "the request must fail BEFORE any dial (an honest typed failure, not " +
                "a connect surrendered to the absent-socket state), got: $requestError",
            requestError is IOException &&
                requestError.message?.startsWith("rest: socket not present yet: ") == true
        )
    }

    @Test
    fun `the transport heals on the same instance once the socket binds`() {
        // Timing reality: bindEngine triggers bootstrap the instant the
        // supervisor reports Running while the child binds its file a moment
        // later. The pre-S-04 client PINNED the fallback at construction and
        // stayed broken until a caller rebuilt CrushApi; per-call resolution
        // means the same instance follows the socket when it appears.
        val path = absentSocketFile()
        val api = CrushApi(port = 1, socketPath = path)
        assertTrue(api.resolveTransportNow() is RestTransport.Unavailable)

        // THE BIND: same path, later in time - what the engine does right after
        // spawn. A byte is enough; the transport factory pins the PATH.
        path.writeText("")
        val transport = api.resolveTransportNow()
        assertTrue(
            "after the bind the same instance must carry the UDS transport " +
                "without any caller-side rebuild, got: $transport",
            transport is RestTransport.Uds
        )
        val uds = transport as RestTransport.Uds
        assertEquals(path.absolutePath, uds.socketPath)
        assertTrue(
            "the Uds client must carry the unix-socket factory (no plain socket)",
            uds.client.socketFactory is AFUNIXSocketFactory.FactoryArg
        )
    }

    @Test
    fun `the base url never leaves the http 127 0 0 1 shape - no tcp scheme`() {
        // Pin the UDS sentinel usage: in socket mode `port` is the meaningless
        // sentinel (CrushEngine.UDS_PORT_SENTINEL == 1) and the URL stays
        // http://127.0.0.1:<port> - the host is what the junixsocket factory
        // matches against, and production URLs must never grow a tcp:// scheme.
        val socketBound = CrushApi(port = 1, socketPath = absentSocketFile())
        assertEquals("http://127.0.0.1:1", socketBound.base)
        assertTrue(!socketBound.base.startsWith("tcp://"))
        assertTrue(socketBound.base.startsWith("http://127.0.0.1"))

        val overrideBound = CrushApi(
            port = 99,
            baseUrlOverride = "http://127.0.0.1:54321"
        )
        assertEquals("http://127.0.0.1:54321", overrideBound.base)
    }

    @Test
    fun `the test-only plain client is reachable and the only tcp selector`() {
        // The test affordance must REMAIN possible (MockWebServer suites), but
        // only through the override-marked construction shape: production
        // constructions never reach this helper.
        val overrideApi = CrushApi(port = 1, baseUrlOverride = "http://127.0.0.1:54321")
        val clientForTests: OkHttpClient = overrideApi.plainClientForTests()
        assertTrue("reachability from test code", clientForTests != null)
        assertTrue(
            "the override shape selects the explicitly marked test transport",
            overrideApi.resolveTransportNow() is RestTransport.TcpForTests
        )
    }

    @Test
    fun `scrubbing keeps failure details free of the raw app dir prefix`() {
        // Pure-string assert on the shared scrubber (identical to the events
        // transport's): the device files-dir prefix is abbreviated to a marker,
        // so no log or failure detail carries the raw absolute path.
        assertEquals(
            "<datadir>/com.crush.mobile/files/crush.sock",
            CrushConnection.scrubbedPath("/data/user/0/com.crush.mobile/files/crush.sock")
        )
        // A path without the marker passes through unchanged: the scrubber
        // must not mangle arbitrary paths.
        assertEquals(
            "/some/other/place.sock",
            CrushConnection.scrubbedPath("/some/other/place.sock")
        )
    }
}
