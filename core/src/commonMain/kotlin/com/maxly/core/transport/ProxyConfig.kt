package com.maxly.core.transport

/** Proxy protocol. */
enum class ProxyKind {
    /** HTTP CONNECT tunnel. */
    HTTP,

    /** SOCKS5 (`socks5://`). */
    SOCKS5,

    /** SOCKS5 with the target name resolved by the proxy (`socks5h://`). */
    SOCKS5H,
}

/**
 * An outbound proxy for the transport socket (kolibri `ProxyConfig`, docs/protocol.md §B.8).
 * [username] / [password], if set, are used for HTTP Basic auth or SOCKS5 user/pass (RFC 1929).
 *
 * DNS: for both [ProxyKind.SOCKS5] and [ProxyKind.SOCKS5H] the target host name is sent to the
 * proxy as a domain (ATYP 0x03) and resolved there, as kolibri does for both schemes. Resolving
 * locally for plain `socks5` (curl semantics) is deliberately not done: common code has no DNS API,
 * and local resolution would leak the lookup outside the proxy. IPv4 literals are sent as ATYP
 * 0x01.
 */
data class ProxyConfig(
    val kind: ProxyKind,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    override fun toString(): String =
        "ProxyConfig(kind=$kind, host=$host, port=$port, username=$username, password=${if (password == null) null else "***"})"

    companion object {
        /**
         * Parses `scheme://[user[:pass]@]host:port` with scheme `http`, `socks5` or `socks5h`
         * (case-insensitive). The port is required (kolibri has no default ports). User and
         * password are percent-decoded (UTF-8), so `p%40ss` gives `p@ss`. IPv6 hosts go in
         * brackets: `socks5://[::1]:1080`. A trailing `/` is allowed.
         *
         * @throws IllegalArgumentException on a malformed URL.
         */
        fun parse(url: String): ProxyConfig {
            val schemeEnd = url.indexOf("://")
            require(schemeEnd > 0) { "proxy url has no scheme: ${redact(url)}" }
            val kind = when (url.substring(0, schemeEnd).lowercase()) {
                "http" -> ProxyKind.HTTP
                "socks5" -> ProxyKind.SOCKS5
                "socks5h" -> ProxyKind.SOCKS5H
                else -> throw IllegalArgumentException("unsupported proxy scheme: ${url.substring(0, schemeEnd)}")
            }
            val rest = url.substring(schemeEnd + 3).removeSuffix("/")
            require('/' !in rest && '?' !in rest && '#' !in rest) { "proxy url must not have a path: ${redact(url)}" }

            val at = rest.lastIndexOf('@')
            val userInfo = if (at >= 0) rest.substring(0, at) else null
            val authority = if (at >= 0) rest.substring(at + 1) else rest

            var username: String? = null
            var password: String? = null
            if (userInfo != null) {
                val colon = userInfo.indexOf(':')
                username = percentDecode(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
                password = if (colon >= 0) percentDecode(userInfo.substring(colon + 1)) else null
                require(username.isNotEmpty()) { "proxy url has an empty user name: ${redact(url)}" }
            }

            val host: String
            val portText: String
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                require(close > 1) { "bad IPv6 proxy host: ${redact(url)}" }
                host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                require(after.startsWith(":")) { "proxy url has no port: ${redact(url)}" }
                portText = after.substring(1)
            } else {
                val colon = authority.lastIndexOf(':')
                require(colon >= 0) { "proxy url has no port: ${redact(url)}" }
                host = authority.substring(0, colon)
                portText = authority.substring(colon + 1)
                require(':' !in host) { "IPv6 proxy host must be in brackets: ${redact(url)}" }
            }
            require(host.isNotEmpty()) { "proxy url has no host: ${redact(url)}" }
            val port = portText.toIntOrNull()
            require(port != null && portText.all { it in '0'..'9' } && port in 1..65535) {
                "bad proxy port: $portText"
            }
            return ProxyConfig(kind, host, port, username, password)
        }

        /** Decodes `%XX` escapes as UTF-8. `+` is kept as is (userinfo is not form-encoded). */
        internal fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] == '%') {
                    require(i + 2 < text.length) { "truncated percent escape in proxy url" }
                    val hi = hexValue(text[i + 1])
                    val lo = hexValue(text[i + 2])
                    require(hi >= 0 && lo >= 0) { "bad percent escape in proxy url" }
                    out += ((hi shl 4) or lo).toByte()
                    i += 3
                } else {
                    // copy the whole literal run at once so surrogate pairs stay intact
                    val end = text.indexOf('%', i).let { if (it < 0) text.length else it }
                    text.substring(i, end).encodeToByteArray().forEach { out += it }
                    i = end
                }
            }
            return try {
                out.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw IllegalArgumentException("percent escape in proxy url is not valid UTF-8", e)
            }
        }

        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        private fun redact(url: String): String {
            val schemeEnd = url.indexOf("://")
            val at = url.lastIndexOf('@')
            return if (schemeEnd >= 0 && at > schemeEnd) url.substring(0, schemeEnd + 3) + "***@" + url.substring(at + 1) else url
        }
    }
}
