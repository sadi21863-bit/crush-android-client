package com.crush.mobile.engine

import com.crush.mobile.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Loopback HTTP-CONNECT bridge that gives the bundled engine a working egress.
 *
 * ROOT CAUSE THIS CLOSES (measured 2026-11-02): the engine is a statically
 * linked Go binary running on Android, where /etc/resolv.conf does not exist.
 * Go's pure resolver then falls back to its compiled-in default nameservers
 * - 127.0.0.1:53 and [::1]:53 - and nothing on an Android phone serves DNS on
 * loopback (checked live: `ss -lntu` shows no :53 listener). Engine-egress
 * calls therefore died with `dial tcp: lookup opencode.ai on [::1]:53: read
 * udp ... connection refused` whenever no unrelated listener held :53 -
 * measured fenestration: turns succeeded only during tethering windows and
 * failed stand-alone (the "the app works while the phone is plugged into the
 * computer and fails on its own" defect). App-side JVM calls (catalogue,
 * probes, prices) were never affected: bionic resolves through netd.
 *
 * HOW THE BRIDGE FIXES IT: the engine's HTTP stack honours
 * HTTPS_PROXY/HTTP_PROXY env vars (Go stdlib `httpproxy`), and a proxy
 * addressed `http://127.0.0.1:<port>` needs NO DNS on the engine side at all.
 * So the app runs this bridge on an unprivileged loopback port and the engine
 * tunnels through it: the bridge resolves the CONNECT target host with the
 * JVM's own resolver (network-agnostic) and then relays raw bytes, so TLS
 * stays end-to-end between the engine and the provider - the bridge cannot
 * see plaintext, only `CONNECT host:port`. Both the bind address (strictly
 * 127.0.0.1) and the relay-only shape are documented here; nothing else binds,
 * listens or forwards.
 *
 * Lifecycle: started by [CrushEngine.start] just before the child spawns, and
 * stopped in [CrushEngine.stopBlocking] once the child is gone. `start()` is
 * idempotent, so the supervisor's bounded retry ladder can re-enter it.
 */
class EngineProxy(
    /**
     * The seam that turns a CONNECT authority's host into an address.
     * The default is the JVM's own resolver (which works wherever ANY Android
     * network exists). Injectable so a unit test can pin both the "resolved"
     * and the "unresolvable -> 502" paths without a real network.
     */
    private val resolver: (String) -> InetAddress? = { runCatching { InetAddress.getByName(it) }.getOrNull() },
    /** Upstream TCP connect budget per tunnel. */
    private val connectTimeoutMs: Int = 10_000,
    /**
     * Where trace lines go. Defaults to the app file log; injectable because
     * the JVM unit tests must not touch android.util.Log via AppLog.
     */
    private val log: (String) -> Unit = { AppLog.d(TAG, it) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = ConcurrentHashMap<Socket, Boolean>()
    private var server: ServerSocket? = null

    @Volatile
    private var boundPort: Int = -1

    /** TCP port the bridge listens on, or -1 when not running. */
    val port: Int get() = boundPort

    /** True while the bridge holds a live listener. */
    val isRunning: Boolean get() = boundPort > 0 && server?.isClosed == false

    private fun badGateway() = "HTTP/1.1 502 Bad Gateway\r\n\r\n"

    /**
     * Binds a loopback-only listener on a free unprivileged port. Returns
     * false (and logs) if the bind itself fails; a bind failure leaves the
     * engine on the broken system resolver, which is degraded but not fatal,
     * so the caller proceeds rather than hard-failing the engine start.
     */
    fun start(): Boolean {
        if (isRunning) return true
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
            }
        } catch (e: IOException) {
            log("bridge bind failed: ${e.message}")
            return false
        }
        server = s
        boundPort = s.localPort
        log("bridge up on 127.0.0.1:$boundPort")
        scope.launch {
            try {
                while (server === s && !s.isClosed) {
                    val client = s.accept()
                    scope.launch { serve(client) }
                }
            } catch (e: IOException) {
                if (server === s && !s.isClosed) log("accept loop ended: ${e.message}")
            }
        }
        return true
    }

    /**
     * Closes the listener and every open tunnel. The child-side sockets are
     * dead anyway by the time this runs ([CrushEngine] calls it after the
     * process is destroyed), but cleaning up here keeps reusing the instance
     * (supervisor retry ladder) honest.
     */
    fun stop() {
        val s = server ?: return
        server = null
        boundPort = -1
        runCatching { s.close() }
        relays.keys.forEach { runCatching { it.close() } }
        relays.clear()
        log("bridge stop")
    }

    /**
     * Reads the CONNECT handshake off [client], dials the target through
     * [resolver], answers 200 or 502, then relays bytes in both directions
     * until either side closes. Peer TLS is opaque here by construction.
     */
    private fun serve(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val request = readAsciiLine(client.getInputStream()) ?: return
            if (!request.startsWith("CONNECT ")) {
                log("rejecting non-CONNECT request")
                writeReply(client, badGateway())
                return
            }
            val target = parseTarget(request.removePrefix("CONNECT ").trim().substringBefore(" HTTP/"))
            // The remaining request headers must be consumed BEFORE relaying:
            // a payload could arrive in the same TCP segment as the headers.
            while (readAsciiLine(client.getInputStream())?.isNotEmpty() == true) { /* headers */ }

            if (target == null) {
                log("malformed CONNECT authority")
                writeReply(client, badGateway())
                return
            }
            val (host, port) = target
            val addr = resolver(host)
            if (addr == null) {
                log("CONNECT $host:$port failed: unresolvable")
                writeReply(client, badGateway())
                return
            }
            val up = Socket()
            upstream = up
            up.connect(InetSocketAddress(addr, port), connectTimeoutMs)
            client.soTimeout = 0
            writeReply(client, "HTTP/1.1 200 Connection established\r\n\r\n")
            log("CONNECT $host:$port ok")
            relay(client, up)
        } catch (e: SocketTimeoutException) {
            log("handshake timeout")
            runCatching { writeReply(client, badGateway()) }
            runCatching { client.close() }
            runCatching { upstream?.close() }
        } catch (e: IOException) {
            log("serve ended: ${e.message}")
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /** Host/port pair from a CONNECT authority (`host:port`, bracketed IPv6 allowed, default 443). */
    private fun parseTarget(authority: String): Pair<String, Int>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else 443
            return host to port
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to 443
        val port = authority.substring(colon + 1).toIntOrNull() ?: return null
        return authority.substring(0, colon) to port
    }

    /** CRLF line ends matter to wire clients, so replies are written verbatim. */
    private fun writeReply(client: Socket, reply: String) {
        runCatching {
            client.getOutputStream().apply {
                write(reply.toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
    }

    private fun relay(a: Socket, b: Socket) {
        relays[a] = true
        relays[b] = true
        scope.launch {
            // Two structured children; each ends on EOF (half-closing its
            // output) or a hard reset (closing both sockets, which wakes the
            // sibling's blocked read). stop() closes the sockets, which stops
            // the pumps the same way.
            launch { copyOneWay(a, b) }
            launch { copyOneWay(b, a) }
        }
    }

    /** One direction of the relay: raw byte copy until EOF or reset. */
    private suspend fun copyOneWay(from: Socket, to: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
            }
            // EOF from one side: announce EOF downstream without killing the
            // reverse direction's in-flight bytes.
            runCatching { to.shutdownOutput() }
        } catch (_: IOException) {
            // Hard reset on either side wakes the sibling pump's blocked read.
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    /**
     * Byte-wise line reader. BufferedReader would over-read past the header
     * block into the tunneled payload (payloads can arrive in the same
     * segment), which would corrupt the first TLS bytes - so lines are
     * assembled one read() at a time and never beyond the header terminator.
     */
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = try {
                input.read()
            } catch (_: IOException) {
                return null
            }
            if (c == -1) return null
            if (c == '\n'.code) break
            if (c == '\r'.code) continue
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "EngineProxy"
    }
}
