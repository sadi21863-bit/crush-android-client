package com.crush.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Pins the loopback CONNECT bridge the engine child tunnels its provider
 * egress through (see [EngineProxy] for the measured defect it closes: the
 * bundled Go engine cannot resolve DNS on Android - no /etc/resolv.conf,
 * fallback ::1:53, nothing serving it - so provider calls only survived
 * during tethering windows).
 *
 * These tests exercise the wire shape a Go `httpproxy` client expects:
 * the 200 handshake with CRLF ends, byte relay in BOTH directions afterwards,
 * the 502 path when the target is unresolvable, and the resolver seam being
 * called with the parsed host. The relay is ASYNCHRONOUS (coroutine pumps),
 * so every post-handshake assertion goes through a bounded wait instead of
 * racing the copy.
 */
class EngineProxyConnectTest {

    /** Bounded wait for an asynchronous effect; fails with a clear message on tunnel death. */
    private fun await(queue: ArrayDeque<String>, timeoutMs: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            queue.removeFirstOrNull()?.let { return it }
            Thread.sleep(10)
        }
        return queue.removeFirstOrNull()
    }

    @Test(timeout = 15_000)
    fun `connect handshake relays bytes both ways through the resolver seam`() {
        val upstream = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val tunneled = ArrayDeque<String>()
        val resolverHosts = ArrayList<String>()
        Thread({
            runCatching {
                val s = upstream.accept()
                val buf = ByteArray(1024)
                val n = s.getInputStream().read(buf)
                tunneled.add(String(buf, 0, n, Charsets.US_ASCII))
                s.getOutputStream().write("PONG".toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()
                s.close()
            }
        }).start()

        val proxy = EngineProxy(
            resolver = { host ->
                resolverHosts.add(host)
                InetAddress.getLoopbackAddress()
            },
            log = {}
        )
        assertTrue("bridge must start", proxy.start())
        assertTrue(
            "bridge must bind an unprivileged loopback port, was ${proxy.port}",
            proxy.port in 1024..65535
        )

        var client: Socket? = null
        try {
            client = Socket(InetAddress.getLoopbackAddress(), proxy.port)
            client.getOutputStream().apply {
                write(
                    ("CONNECT bridge.test:${upstream.localPort} HTTP/1.1\r\n" +
                        "Host: bridge.test:${upstream.localPort}\r\n" +
                        "User-Agent: engine-proxy-test\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                flush()
            }
            val reader = BufferedReader(
                InputStreamReader(client.getInputStream(), Charsets.US_ASCII)
            )
            assertEquals("HTTP/1.1 200 Connection established", reader.readLine())
            assertEquals("200 reply ends with a blank header line", "", reader.readLine())

            client.getOutputStream().apply {
                write("HELLO".toByteArray(Charsets.US_ASCII))
                flush()
            }
            assertEquals("upstream must see the tunneled bytes", "HELLO", await(tunneled))
            client.soTimeout = 5_000
            val back = ByteArray(4)
            DataInputStream(client.getInputStream()).readFully(back)
            assertEquals("tunneled reply must reach the client", "PONG", String(back, Charsets.US_ASCII))
            assertEquals("resolver seam must get the parsed host", "bridge.test", resolverHosts.single())
        } finally {
            runCatching { client?.soTimeout = 0 }
            runCatching { client?.close() }
            runCatching { upstream.close() }
            proxy.stop()
        }
    }

    @Test(timeout = 15_000)
    fun `unresolvable target answers 502 and does not open a tunnel`() {
        val proxy = EngineProxy(resolver = { null }, log = {})
        assertTrue(proxy.start())
        try {
            val client = Socket(InetAddress.getLoopbackAddress(), proxy.port)
            client.getOutputStream().apply {
                write("CONNECT dead.test:443 HTTP/1.1\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val reader = BufferedReader(
                InputStreamReader(client.getInputStream(), Charsets.US_ASCII)
            )
            assertEquals("HTTP/1.1 502 Bad Gateway", reader.readLine())
            runCatching { client.close() }
        } finally {
            proxy.stop()
        }
    }
}
