package com.crush.mobile.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure-JVM contract tests for [EngineSockets] - no Android, no Robolectric.
 *
 * The system property is the load-bearing part of the transport: junixsocket's
 * `FactoryArg` socket factory only accepts URL hosts that match
 * `org.newsclub.net.unix.socket.hostname`, which [EngineSockets]' init pins to
 * 127.0.0.1. That pinning is what lets the whole codebase keep building
 * `http://127.0.0.1:<port>` URLs while the transport underneath is the socket
 * file. Guarding it here ensures nobody "tidies" the init away silently.
 *
 * The missing-socket-file probe asserts the readiness contract both the
 * engine-side readiness loop (CrushEngine.start) and the supervisor's hang
 * detection rely on: while the engine has not created the socket file (or
 * nothing listens behind it), a probe must report "not up" as a null status,
 * never throw - a thrown exception would crash the boot loop instead of
 * keeping the 250ms retry cadence.
 */
class EngineSocketsHostnameTest {

    private val tmp: File = Files.createTempDirectory("engine-sockets-test").toFile()

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `referencing the object sets the junixsocket hostname property`() {
        // Reading const members alone does NOT force the singleton's init
        // (compile-time constants are inlined at the call site), so touch the
        // instance itself to guarantee the object is loaded.
        EngineSockets.hashCode()
        assertEquals(
            "127.0.0.1",
            System.getProperty("org.newsclub.net.unix.socket.hostname")
        )
        assertEquals("127.0.0.1", EngineSockets.LOCAL_HOSTNAME)
        assertEquals(
            EngineSockets.DefaultSocketHostnameProxy.PROPERTY,
            "org.newsclub.net.unix.socket.hostname"
        )
    }

    @Test
    fun `probe on missing adapter yields null`() = runBlocking {
        // The file does not exist, so the factory cannot connect to anything;
        // probe() must convert that into a plain null (its guard plus
        // runCatching) without ever throwing into the readiness loop.
        val missing = File(tmp, "nope.sock")
        assertNull(EngineSockets.probe(missing, "/v1/version"))
    }

    @Test
    fun `okClient for a missing socket file is null`() {
        // Same guard as probe()'s success path: okClient must refuse to build
        // a client against a socket file that does not exist yet.
        val missing = File(tmp, "nope.sock")
        assertNull(EngineSockets.okClient(missing))
    }
}
