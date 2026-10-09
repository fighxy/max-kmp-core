@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.core.transport

import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.decodePacket
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Server `RECONNECT` (opcode 3, `{redirectHost: "host:port", tls}`). */
class ServerRedirectTest {
    private val domains = ServerRedirect.DEFAULT_DOMAINS
    private val retrying = TransportConfig(host = "api.oneme.ru", pingInterval = Duration.INFINITE, autoReconnect = true)

    private fun TestScope.transport(factory: ConnectionFactory, config: TransportConfig = retrying, onConnected: (suspend (MaxTransport) -> Unit)? = null) =
        MaxTransport(config, factory, scope = backgroundScope, onConnected = onConnected)

    private fun redirect(host: String?, tls: Boolean? = null): ByteArray {
        val payload = linkedMapOf<String, Any?>()
        if (host != null) payload["redirectHost"] = host
        if (tls != null) payload["tls"] = tls
        return push(Opcode.RECONNECT.value, payload)
    }

    @Test
    fun onlyHostsOnTheAppDomainWithTlsAndAPortAreFollowed() {
        val ok = ServerRedirect.evaluate(mapOf("redirectHost" to "api2.oneme.ru:443", "tls" to true), domains)
        assertTrue(ok.accepted)
        assertEquals("api2.oneme.ru", ok.host)
        assertEquals(443, ok.port)
        assertNull(ok.reason)
        // tls defaults to true (the app's default)
        assertTrue(ServerRedirect.evaluate(mapOf("redirectHost" to "API-test.OneMe.ru.:8443"), domains).let { it.accepted && it.host == "api-test.oneme.ru" && it.port == 8443 && it.tls })
        assertTrue(ServerRedirect.evaluate(mapOf("redirectHost" to "oneme.ru:443"), domains).accepted)

        // empty / missing host: a restart on the current host
        for (payload in listOf(mapOf("redirectHost" to ""), mapOf("tls" to true), null)) {
            val restart = ServerRedirect.evaluate(payload, domains)
            assertTrue(restart.accepted)
            assertNull(restart.host)
            assertNull(restart.port)
        }

        val refused = listOf(
            "evil.com:443", "oneme.ru.evil.com:443", "evil-oneme.ru:443", "api.oneme.ru.:443x",
            "93.186.225.208:443", "api2.oneme.ru", ":443", "api2.oneme.ru:0", "api2.oneme.ru:70000",
            "bad_host.oneme.ru:443", "-x.oneme.ru:443",
        )
        for (host in refused) {
            val r = ServerRedirect.evaluate(mapOf("redirectHost" to host), domains)
            assertFalse(r.accepted, host)
            assertNull(r.host, host)
            assertTrue(r.reason != null, host)
        }
        val plain = ServerRedirect.evaluate(mapOf("redirectHost" to "api2.oneme.ru:443", "tls" to false), domains)
        assertFalse(plain.accepted)
        assertFalse(plain.tls)
        // no domains: no host switches at all (a bare restart still is one)
        assertFalse(ServerRedirect.evaluate(mapOf("redirectHost" to "api2.oneme.ru:443"), emptySet()).accepted)
        assertTrue(ServerRedirect.isAllowedHost("api.test", setOf("test")))
    }

    @Test
    fun acceptedRedirectReconnectsAtOnceToTheNewHostAndRunsTheHandshakeAgain() = runTest {
        val factory = ScriptedConnectionFactory()
        var handshakes = 0
        val t = transport(factory, onConnected = { handshakes++ })
        t.connect()
        val first = factory.lastConnection!!
        assertEquals("api.oneme.ru", factory.lastHost)

        val pending = async { runCatching { t.request(Opcode.SYNC, null) } }
        first.takeWritten()
        val before = currentTime
        first.feed(redirect("api2.oneme.ru:8443", tls = true))
        assertIs<ConnectionClosedException>(pending.await().exceptionOrNull())
        runCurrent()

        assertEquals(2, factory.openCount)
        assertEquals(before, currentTime) // no backoff
        assertEquals("api2.oneme.ru", factory.lastHost)
        assertEquals(8443, factory.lastPort)
        assertEquals(2, handshakes)
        assertEquals(ConnectionState.Connected, t.state.value)
        assertEquals("api2.oneme.ru", t.config.host)
        assertEquals(ServerRedirect("api2.oneme.ru:8443", true, "api2.oneme.ru", 8443, accepted = true, reason = null), t.lastRedirect.value)

        // the new connection works and starts its seq at 1; the old one is closed
        val second = factory.lastConnection!!
        assertFailsWithClosed { first.write(byteArrayOf(1)) }
        val r = async { t.request(Opcode.PING, null) }
        assertEquals(1, decodePacket(second.takeWritten()!!).first.seq)
        second.feed(ok(1, Opcode.PING.value))
        r.await()

        // a later drop reconnects to the redirected host (with the usual backoff)
        second.close()
        runCurrent()
        advanceTimeBy(3_301)
        runCurrent()
        assertEquals(3, factory.openCount)
        assertEquals("api2.oneme.ru", factory.lastHost)
        t.close()
    }

    @Test
    fun emptyRedirectRestartsOnTheSameHost() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        factory.lastConnection!!.feed(redirect(""))
        runCurrent()
        assertEquals(2, factory.openCount)
        assertEquals("api.oneme.ru", factory.lastHost)
        assertEquals(443, factory.lastPort)
        assertTrue(t.lastRedirect.value!!.accepted)
        assertEquals(ConnectionState.Connected, t.state.value)
        t.close()
    }

    @Test
    fun unsafeRedirectIsIgnoredAndTheConnectionStays() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!
        for (bad in listOf(redirect("evil.example:443"), redirect("api2.oneme.ru:443", tls = false))) {
            conn.feed(bad)
            runCurrent()
            assertFalse(t.lastRedirect.value!!.accepted)
        }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, factory.openCount)
        assertEquals(ConnectionState.Connected, t.state.value)
        assertEquals("api.oneme.ru", t.config.host)
        val r = async { t.request(Opcode.PING, null) }
        val seq = decodePacket(conn.takeWritten()!!).first.seq
        conn.feed(ok(seq, Opcode.PING.value))
        r.await()
        t.close()
    }

    @Test
    fun withoutAutoReconnectTheRedirectIsIgnored() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory, retrying.copy(autoReconnect = false))
        t.connect()
        factory.lastConnection!!.feed(redirect("api2.oneme.ru:443"))
        runCurrent()
        val r = t.lastRedirect.value!!
        assertFalse(r.accepted)
        assertEquals("auto-reconnect is off", r.reason)
        assertEquals(1, factory.openCount)
        assertEquals(ConnectionState.Connected, t.state.value)
        assertEquals("api.oneme.ru", t.config.host)
    }

    private suspend fun assertFailsWithClosed(block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertIs<ConnectionClosedException>(e)
    }
}
