@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import com.max.core.transport.push
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

/** Server RECONNECT diagnostics, login rejections and keepalive as seen through [MaxClient]. */
class ConnectionReliabilityClientTest {
    private val seed = 1234567890123L
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val config = MaxClientConfig(host = "api.test", transport = quiet)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private fun client(kv: KeyValueStore, factory: ScriptedConnectionFactory, scope: CoroutineScope, cfg: MaxClientConfig = config) =
        MaxClient(cfg, kv, factory, noHttp, scope)

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun FakeRawConnection.fail(opcode: Opcode, reply: Any?) {
        val (header, _) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue)
        feed(errorReply(header.seq, opcode.value, reply))
    }

    private fun loginReply(token: String?) = mapOf(
        "profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("name" to "Me")))),
        "chats" to emptyList<Any>(),
        "time" to 1700L,
        "config" to mapOf("hash" to "cfg-1"),
    ) + (if (token != null) mapOf("token" to token) else emptyMap())

    /** SMS flow on a fresh client; returns it logged in with token `login-2` stored in [kv]. */
    private suspend fun TestScope.smsLogin(kv: KeyValueStore, factory: ScriptedConnectionFactory, cfg: MaxClientConfig = config): MaxClient {
        val c = client(kv, factory, backgroundScope, cfg)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        assertIs<ClientState.AwaitingAuth>(starting.await())
        val verify = async { c.verifyCode("tmp", "123456") }
        runCurrent()
        conn.answer(Opcode.AUTH, mapOf("tokenAttrs" to mapOf("LOGIN" to mapOf("token" to "login-1"))))
        runCurrent()
        conn.answer(Opcode.LOGIN, loginReply("login-2"))
        verify.await()
        runCurrent()
        return c
    }

    @Test
    fun serverRedirectsAreLoggedAsDiagnostics() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(InMemoryKeyValueStore(), factory)
        val lines = ArrayList<String>()
        c.onDiagnostic = { lines += it }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.feed(push(Opcode.RECONNECT.value, mapOf("redirectHost" to "evil.example:443", "tls" to true)))
        runCurrent()
        conn.feed(push(Opcode.RECONNECT.value, mapOf("redirectHost" to "api2.oneme.ru:443")))
        runCurrent()
        assertEquals(
            listOf(
                "push 3 -> Reconnect | ignored (host outside oneme.ru): redirectHost=\"evil.example:443\", tls=true",
                // this test client runs without auto-reconnect
                "push 3 -> Reconnect | ignored (auto-reconnect is off): redirectHost=\"api2.oneme.ru:443\", tls=true",
            ),
            lines,
        )
        assertEquals(ClientState.Ready(5), c.state.value)
    }
}
