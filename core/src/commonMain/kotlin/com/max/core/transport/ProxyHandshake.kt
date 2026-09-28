package com.max.core.transport

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Proxy tunnel handshakes (kolibri `transport/proxy.rs`, docs/protocol.md §B.8). They run over the
 * plain TCP connection to the proxy, before TLS; afterwards the same connection is a byte tunnel to
 * the target and TLS is started on top of it.
 *
 * The request builders and reply parsers are pure functions; [performProxyHandshake] drives them
 * over a [RawConnection] and never reads past the end of the proxy's reply, so no TLS bytes are
 * consumed.
 */

/** Longest HTTP CONNECT response head accepted (kolibri: 8192). */
const val HTTP_CONNECT_MAX_RESPONSE: Int = 8192

/**
 * Runs the handshake for [proxy] over [connection] so that it becomes a tunnel to
 * [targetHost]:[targetPort].
 *
 * @throws ProxyException if the proxy refuses.
 * @throws ConnectionClosedException if the proxy closes the connection mid-handshake.
 */
suspend fun performProxyHandshake(connection: RawConnection, proxy: ProxyConfig, targetHost: String, targetPort: Int) {
    when (proxy.kind) {
        ProxyKind.HTTP -> httpConnectHandshake(connection, proxy, targetHost, targetPort)
        ProxyKind.SOCKS5, ProxyKind.SOCKS5H -> socks5Handshake(connection, proxy, targetHost, targetPort)
    }
}

// ── HTTP CONNECT ────────────────────────────────────────────────────

/**
 * `CONNECT host:port HTTP/1.1` with `Host`, optional `Proxy-Authorization: Basic` (when
 * [ProxyConfig.username] is set; a missing password is sent as empty) and
 * `Proxy-Connection: keep-alive`, terminated by an empty line.
 */
@OptIn(ExperimentalEncodingApi::class)
fun buildHttpConnectRequest(targetHost: String, targetPort: Int, proxy: ProxyConfig): ByteArray {
    val authority = "${bracketIpv6(targetHost)}:$targetPort"
    val sb = StringBuilder()
    sb.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
    sb.append("Host: ").append(authority).append("\r\n")
    proxy.username?.let { user ->
        val token = Base64.encode("$user:${proxy.password.orEmpty()}".encodeToByteArray())
        sb.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
    }
    sb.append("Proxy-Connection: keep-alive\r\n\r\n")
    return sb.toString().encodeToByteArray()
}

/**
 * Status code from the first line of an HTTP response head (`HTTP/1.1 200 Connection
 * established`), or `null` if the line is malformed.
 */
fun parseHttpStatus(head: String): Int? {
    val statusLine = head.lineSequence().firstOrNull() ?: return null
    val parts = statusLine.trim().split(' ').filter { it.isNotEmpty() }
    if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
    val code = parts[1]
    if (code.length != 3 || !code.all { it in '0'..'9' }) return null
    return code.toInt()
}

/** Throws [ProxyException] unless the CONNECT response [head] has status 200 (like kolibri). */
fun requireHttpConnectOk(head: String) {
    val status = parseHttpStatus(head)
    if (status != 200) throw ProxyException("proxy CONNECT failed: ${status ?: "malformed response"}")
}

private suspend fun httpConnectHandshake(connection: RawConnection, proxy: ProxyConfig, host: String, port: Int) {
    connection.write(buildHttpConnectRequest(host, port, proxy))
    // byte by byte, so nothing after the empty line (the start of the tunnel) is consumed
    val head = ArrayList<Byte>(256)
    val one = ByteArray(1)
    while (true) {
        val n = connection.read(one, 0, 1)
        if (n < 0) throw ConnectionClosedException("proxy closed the connection during CONNECT")
        if (n == 0) continue
        head += one[0]
        val size = head.size
        if (size >= 4 && head[size - 4] == CR && head[size - 3] == LF && head[size - 2] == CR && head[size - 1] == LF) break
        if (size > HTTP_CONNECT_MAX_RESPONSE) throw ProxyException("proxy CONNECT response too long")
    }
    requireHttpConnectOk(head.toByteArray().decodeToString())
}

private const val CR: Byte = 13
private const val LF: Byte = 10

// ── SOCKS5 (RFC 1928 / RFC 1929) ────────────────────────────────────

/** Greeting: `05 01 00` (no auth) or `05 02 00 02` (no auth + user/pass) when credentials are set. */
fun socks5Greeting(withAuth: Boolean): ByteArray =
    if (withAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)

/** RFC 1929 request: `01 ulen user plen pass` (UTF-8, each at most 255 bytes). */
fun socks5UserPassRequest(username: String, password: String): ByteArray {
    val user = username.encodeToByteArray()
    val pass = password.encodeToByteArray()
    if (user.size > 255 || pass.size > 255) throw ProxyException("SOCKS5 credentials too long")
    return byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass
}

/**
 * CONNECT request `05 01 00 ATYP addr port`: an IPv4 literal as ATYP 0x01, anything else as a
 * domain, ATYP 0x03, for both `socks5` and `socks5h` (see [ProxyConfig] on DNS).
 */
fun socks5ConnectRequest(targetHost: String, targetPort: Int): ByteArray {
    val portBytes = byteArrayOf((targetPort ushr 8).toByte(), targetPort.toByte())
    val ipv4 = parseIpv4(targetHost)
    if (ipv4 != null) return byteArrayOf(0x05, 0x01, 0x00, 0x01) + ipv4 + portBytes
    val name = targetHost.encodeToByteArray()
    if (name.isEmpty() || name.size > 255) throw ProxyException("SOCKS5 target host length ${name.size} not in 1..255")
    return byteArrayOf(0x05, 0x01, 0x00, 0x03, name.size.toByte()) + name + portBytes
}

/** Human-readable RFC 1928 reply code. */
fun socks5ReplyMessage(code: Int): String = when (code) {
    0x01 -> "general SOCKS server failure"
    0x02 -> "connection not allowed by ruleset"
    0x03 -> "network unreachable"
    0x04 -> "host unreachable"
    0x05 -> "connection refused"
    0x06 -> "TTL expired"
    0x07 -> "command not supported"
    0x08 -> "address type not supported"
    else -> "reply $code"
}

private suspend fun socks5Handshake(connection: RawConnection, proxy: ProxyConfig, host: String, port: Int) {
    val withAuth = proxy.username != null
    connection.write(socks5Greeting(withAuth))
    val method = connection.readExactly(2)
    if (method[0] != 0x05.toByte()) throw ProxyException("not a SOCKS5 proxy")
    when (val m = method[1].toInt() and 0xFF) {
        0x00 -> Unit
        0x02 -> {
            if (!withAuth) throw ProxyException("SOCKS5 proxy wants user/pass but none configured")
            connection.write(socks5UserPassRequest(proxy.username.orEmpty(), proxy.password.orEmpty()))
            val reply = connection.readExactly(2)
            if (reply[1] != 0x00.toByte()) throw ProxyException("SOCKS5 auth rejected")
        }
        0xFF -> throw ProxyException("SOCKS5 proxy rejected auth methods")
        else -> throw ProxyException("SOCKS5 unexpected method $m")
    }

    connection.write(socks5ConnectRequest(host, port))
    val head = connection.readExactly(4)
    if (head[0] != 0x05.toByte()) throw ProxyException("bad SOCKS5 reply version ${head[0]}")
    val rep = head[1].toInt() and 0xFF
    if (rep != 0x00) throw ProxyException("SOCKS5 connect failed: ${socks5ReplyMessage(rep)}")
    // skip BND.ADDR + BND.PORT
    val addrLen = when (val atyp = head[3].toInt() and 0xFF) {
        0x01 -> 4
        0x04 -> 16
        0x03 -> connection.readExactly(1)[0].toInt() and 0xFF
        else -> throw ProxyException("SOCKS5 bad address type $atyp")
    }
    connection.readExactly(addrLen + 2)
}

private fun parseIpv4(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        val v = part.toInt()
        if (v > 255) return null
        out[i] = v.toByte()
    }
    return out
}

private fun bracketIpv6(host: String): String = if (':' in host && !host.startsWith("[")) "[$host]" else host
