package com.max.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyHandshakeTest {

    /** Returns [serverBytes] to reads (at most [maxRead] per call) and records every write. */
    private class ScriptedStream(private val serverBytes: ByteArray, private val maxRead: Int = Int.MAX_VALUE) : RawConnection {
        var pos = 0
        val written = ArrayList<Byte>()
        val remaining: ByteArray get() = serverBytes.copyOfRange(pos, serverBytes.size)

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= serverBytes.size) return -1
            val n = minOf(length, serverBytes.size - pos, maxRead)
            serverBytes.copyInto(buffer, offset, pos, pos + n)
            pos += n
            return n
        }

        override suspend fun write(bytes: ByteArray) {
            bytes.forEach { written += it }
        }

        override suspend fun close() = Unit
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val tlsStart = "TLS-BYTES".encodeToByteArray()

    // ── HTTP CONNECT ────────────────────────────────────────────────

    @Test
    fun httpConnectRequestWithoutAuth() {
        val req = buildHttpConnectRequest("api.oneme.ru", 443, ProxyConfig(ProxyKind.HTTP, "p", 3128)).decodeToString()
        assertEquals(
            "CONNECT api.oneme.ru:443 HTTP/1.1\r\nHost: api.oneme.ru:443\r\nProxy-Connection: keep-alive\r\n\r\n",
            req,
        )
    }

    @Test
    fun httpConnectRequestWithBasicAuth() {
        val req = buildHttpConnectRequest("api.oneme.ru", 443, ProxyConfig(ProxyKind.HTTP, "p", 3128, "user", "pass")).decodeToString()
        assertTrue("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n" in req, req)
        // user without password → "user:"
        val req2 = buildHttpConnectRequest("h", 1, ProxyConfig(ProxyKind.HTTP, "p", 1, "user")).decodeToString()
        assertTrue("Basic dXNlcjo=\r\n" in req2, req2)
    }

    @Test
    fun httpStatusParsing() {
        assertEquals(200, parseHttpStatus("HTTP/1.1 200 Connection established\r\n\r\n"))
        assertEquals(200, parseHttpStatus("HTTP/1.0 200\r\n\r\n"))
        assertEquals(407, parseHttpStatus("HTTP/1.1 407 Proxy Authentication Required\r\n"))
        assertNull(parseHttpStatus("garbage"))
        assertNull(parseHttpStatus("HTTP/1.1 2000 x"))
        requireHttpConnectOk("HTTP/1.1 200 OK\r\n\r\n")
        assertFailsWith<ProxyException> { requireHttpConnectOk("HTTP/1.1 403 Forbidden\r\n\r\n") }
        assertFailsWith<ProxyException> { requireHttpConnectOk("HTTP/1.1 201 Created\r\n\r\n") }
        assertFailsWith<ProxyException> { requireHttpConnectOk("SSH-2.0-OpenSSH\r\n\r\n") }
    }

    @Test
    fun httpConnectHandshakeStopsAtEndOfHead() = runTest {
        val reply = "HTTP/1.1 200 Connection established\r\nVia: x\r\n\r\n".encodeToByteArray()
        val s = ScriptedStream(reply + tlsStart)
        performProxyHandshake(s, ProxyConfig(ProxyKind.HTTP, "p", 1), "api.oneme.ru", 443)
        assertEquals(
            "CONNECT api.oneme.ru:443 HTTP/1.1\r\nHost: api.oneme.ru:443\r\nProxy-Connection: keep-alive\r\n\r\n",
            s.written.toByteArray().decodeToString(),
        )
        assertContentEquals(tlsStart, s.remaining) // tunnel bytes were not consumed
    }

    @Test
    fun httpConnectHandshakeRejectsNon200() = runTest {
        val s = ScriptedStream("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".encodeToByteArray())
        assertFailsWith<ProxyException> { performProxyHandshake(s, ProxyConfig(ProxyKind.HTTP, "p", 1), "h", 443) }
    }

    @Test
    fun httpConnectHandshakeProxyClosesEarly() = runTest {
        val s = ScriptedStream("HTTP/1.1 200 OK\r\n".encodeToByteArray())
        assertFailsWith<ConnectionClosedException> { performProxyHandshake(s, ProxyConfig(ProxyKind.HTTP, "p", 1), "h", 443) }
    }

    // ── SOCKS5 ──────────────────────────────────────────────────────

    @Test
    fun socks5NoAuthDomainConnect() = runTest {
        val server = b(0x05, 0x00) + // method: no auth
            b(0x05, 0x00, 0x00, 0x01, 10, 0, 0, 1, 0x1F, 0x90) // success, BND 10.0.0.1:8080
        val s = ScriptedStream(server + tlsStart, maxRead = 3)
        performProxyHandshake(s, ProxyConfig(ProxyKind.SOCKS5H, "p", 1080), "api.oneme.ru", 443)
        val host = "api.oneme.ru".encodeToByteArray()
        assertContentEquals(
            b(0x05, 0x01, 0x00) + b(0x05, 0x01, 0x00, 0x03, host.size) + host + b(0x01, 0xBB),
            s.written.toByteArray(),
        )
        assertContentEquals(tlsStart, s.remaining)
    }

    @Test
    fun socks5UserPassAndDomainBoundAddress() = runTest {
        val server = b(0x05, 0x02) + // method: user/pass
            b(0x01, 0x00) + // auth ok
            b(0x05, 0x00, 0x00, 0x03, 4) + "gw.x".encodeToByteArray() + b(0x04, 0x38) // BND domain
        val s = ScriptedStream(server + tlsStart)
        performProxyHandshake(s, ProxyConfig(ProxyKind.SOCKS5, "p", 1080, "bob", "pw"), "api.oneme.ru", 443)
        val host = "api.oneme.ru".encodeToByteArray()
        assertContentEquals(
            b(0x05, 0x02, 0x00, 0x02) +
                b(0x01, 3) + "bob".encodeToByteArray() + b(2) + "pw".encodeToByteArray() +
                b(0x05, 0x01, 0x00, 0x03, host.size) + host + b(0x01, 0xBB),
            s.written.toByteArray(),
        )
        assertContentEquals(tlsStart, s.remaining)
    }

    @Test
    fun socks5Ipv6BoundAddressIsSkipped() = runTest {
        val server = b(0x05, 0x00) + b(0x05, 0x00, 0x00, 0x04) + ByteArray(16) + b(0x00, 0x50)
        val s = ScriptedStream(server + tlsStart)
        performProxyHandshake(s, ProxyConfig(ProxyKind.SOCKS5, "p", 1), "h", 443)
        assertContentEquals(tlsStart, s.remaining)
    }

    @Test
    fun socks5Ipv4TargetUsesAtyp1() {
        assertContentEquals(b(0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0x01, 0xBB), socks5ConnectRequest("1.2.3.4", 443))
        // not a valid IPv4 literal → domain
        assertEquals(0x03, socks5ConnectRequest("1.2.3.256", 443)[3].toInt())
    }

    @Test
    fun socks5Failures() = runTest {
        suspend fun fails(server: ByteArray, proxy: ProxyConfig = ProxyConfig(ProxyKind.SOCKS5, "p", 1)) {
            assertFailsWith<ProxyException> { performProxyHandshake(ScriptedStream(server), proxy, "h", 443) }
        }
        fails(b(0x04, 0x00)) // not SOCKS5
        fails(b(0x05, 0xFF)) // no acceptable method
        fails(b(0x05, 0x02)) // wants auth, none configured
        fails(b(0x05, 0x02, 0x01, 0x01), ProxyConfig(ProxyKind.SOCKS5, "p", 1, "u", "p")) // auth rejected
        fails(b(0x05, 0x00, 0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // connection refused
        fails(b(0x05, 0x00, 0x05, 0x00, 0x00, 0x09)) // bad ATYP
        assertFailsWith<ConnectionClosedException> {
            performProxyHandshake(ScriptedStream(b(0x05, 0x00, 0x05, 0x00, 0x00, 0x01, 1)), ProxyConfig(ProxyKind.SOCKS5, "p", 1), "h", 443)
        }
    }

    @Test
    fun socks5CredentialLimits() {
        assertContentEquals(b(0x01, 1, 'a'.code, 0), socks5UserPassRequest("a", ""))
        assertFailsWith<ProxyException> { socks5UserPassRequest("x".repeat(256), "") }
        assertFailsWith<ProxyException> { socks5ConnectRequest("x".repeat(256), 443) }
    }
}
