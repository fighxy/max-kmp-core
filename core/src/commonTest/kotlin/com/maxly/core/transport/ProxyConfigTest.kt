package com.maxly.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyConfigTest {

    @Test
    fun httpWithCredentials() {
        assertEquals(
            ProxyConfig(ProxyKind.HTTP, "10.0.0.1", 8080, "bob", "secret"),
            ProxyConfig.parse("http://bob:secret@10.0.0.1:8080"),
        )
    }

    @Test
    fun httpWithoutCredentials() {
        assertEquals(ProxyConfig(ProxyKind.HTTP, "proxy.local", 3128), ProxyConfig.parse("http://proxy.local:3128"))
    }

    @Test
    fun socks5AndSocks5h() {
        val s = ProxyConfig.parse("socks5://127.0.0.1:1080")
        assertEquals(ProxyKind.SOCKS5, s.kind)
        assertEquals("127.0.0.1", s.host)
        assertEquals(1080, s.port)
        assertNull(s.username)
        assertNull(s.password)

        assertEquals(
            ProxyConfig(ProxyKind.SOCKS5H, "gw.example.org", 9050, "u", "p"),
            ProxyConfig.parse("socks5h://u:p@gw.example.org:9050"),
        )
        assertEquals(ProxyKind.SOCKS5H, ProxyConfig.parse("SOCKS5H://h:1").kind)
    }

    @Test
    fun userWithoutPassword() {
        val p = ProxyConfig.parse("socks5://alice@h:1080")
        assertEquals("alice", p.username)
        assertNull(p.password)
    }

    @Test
    fun urlEncodedCredentials() {
        val p = ProxyConfig.parse("http://us%65r%3Aname:p%40ss%3Aw0rd%2F%25+x%D0%BF@h:8080")
        assertEquals("user:name", p.username)
        assertEquals("p@ss:w0rd/%+xп", p.password)
    }

    @Test
    fun rawAtInPasswordUsesLastAt() {
        // like kolibri (rsplit_once('@')): the last '@' separates userinfo from host
        val p = ProxyConfig.parse("http://u:p@ss@h:1")
        assertEquals("p@ss", p.password)
        assertEquals("h", p.host)
    }

    @Test
    fun ipv6HostInBrackets() {
        val p = ProxyConfig.parse("socks5://[::1]:1080/")
        assertEquals("::1", p.host)
        assertEquals(1080, p.port)
    }

    @Test
    fun toStringHidesPassword() {
        val s = ProxyConfig.parse("http://u:topsecret@h:1").toString()
        assertFalse("topsecret" in s)
        assertTrue("***" in s)
    }

    @Test
    fun invalidInputs() {
        listOf(
            "",
            "h:1080", // no scheme
            "ftp://h:21", // unsupported scheme
            "https://h:443", // https proxies are not supported
            "socks4://h:1080",
            "http://host", // no port (kolibri has no default ports)
            "http://host:", // empty port
            "http://host:0",
            "http://host:65536",
            "http://host:80a",
            "http://host:-1",
            "http://:8080", // empty host
            "http://u:p@:8080",
            "http://:p@h:8080", // empty user
            "http://h:8080/path",
            "socks5://::1:1080", // IPv6 without brackets
            "socks5://[::1]1080",
            "http://u:%zz@h:1", // bad escape
            "http://u:%4@h:1", // truncated escape
            "http://u:%C3%28@h:1", // invalid UTF-8
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { ProxyConfig.parse(url) }
        }
    }
}
