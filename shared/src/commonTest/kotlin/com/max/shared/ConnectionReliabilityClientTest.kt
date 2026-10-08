@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.ErrorKind
import com.max.core.auth.LoginRejection
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    /** A client restarted over [kv] whose stored-token `LOGIN` gets the ERROR [body]. */
    private suspend fun TestScope.rejectedStart(kv: KeyValueStore, body: Map<String, Any?>): Pair<MaxClient, ClientState> {
        val factory = ScriptedConnectionFactory()
        val c = client(kv, factory, backgroundScope)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        conn.fail(Opcode.LOGIN, body)
        val state = starting.await()
        runCurrent()
        return c to state
    }

    @Test
    fun loginTokenAndBlockedClearTheTokenAndShowTheServerText() = runTest {
        for ((error, reason) in listOf("login.token" to LoginRejection.TOKEN, "login.blocked" to LoginRejection.BLOCKED)) {
            val kv = InMemoryKeyValueStore()
            smsLogin(kv, ScriptedConnectionFactory()).disconnect()
            val (c, state) = rejectedStart(kv, mapOf("error" to error, "message" to "m", "localizedMessage" to "Войдите снова", "title" to "Сессия завершена", "description" to "d"))
            val rejected = assertIs<ClientState.TokenRejected>(state, error)
            assertEquals(reason, rejected.reason)
            assertTrue(rejected.tokenCleared)
            assertEquals(error, rejected.errorKey)
            assertEquals("Сессия завершена", rejected.serverText)
            assertEquals("Сессия завершена", rejected.title)
            assertEquals("Войдите снова", rejected.localizedMessage)
            assertEquals("d", rejected.description)
            assertEquals(ErrorKind.SESSION_EXPIRED, rejected.error?.kind)
            assertEquals("Сессия завершена", rejected.error?.serverText)
            assertEquals(rejected, c.state.value)
            assertNull(CredentialStore(kv, "max.default").load()!!.token)
            assertFalse(c.hasStoredToken)
        }
    }

    @Test
    fun loginFloodKeepsTheTokenForALaterStart() = runTest {
        val kv = InMemoryKeyValueStore()
        smsLogin(kv, ScriptedConnectionFactory()).disconnect()
        val (c, state) = rejectedStart(kv, mapOf("error" to "login.flood", "localizedMessage" to "Слишком много попыток"))
        val rejected = assertIs<ClientState.TokenRejected>(state)
        assertEquals(LoginRejection.FLOOD, rejected.reason)
        assertFalse(rejected.tokenCleared)
        assertEquals("Слишком много попыток", rejected.serverText)
        assertNull(rejected.title)
        assertEquals("login-2", CredentialStore(kv, "max.default").load()!!.token)
        assertTrue(c.hasStoredToken)

        // a later start tries the same token again
        val factory = ScriptedConnectionFactory()
        val again = client(kv, factory, backgroundScope)
        val starting = async { again.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        assertEquals("login-2", conn.answer(Opcode.LOGIN, loginReply(null))!!["token"])
        assertEquals(ClientState.Ready(5), starting.await())
    }

    @Test
    fun legacyTokenErrorStillRejects() = runTest {
        val kv = InMemoryKeyValueStore()
        smsLogin(kv, ScriptedConnectionFactory()).disconnect()
        val (_, state) = rejectedStart(kv, mapOf("error" to "FAIL_LOGIN_TOKEN"))
        val rejected = assertIs<ClientState.TokenRejected>(state)
        assertEquals(LoginRejection.TOKEN, rejected.reason)
        assertEquals("FAIL_LOGIN_TOKEN", rejected.errorKey)
        assertNull(rejected.serverText)
        assertNull(CredentialStore(kv, "max.default").load()!!.token)
    }

    @Test
    fun firstPingGoesOutRightAfterTheStoredTokenLogin() = runTest {
        val kv = InMemoryKeyValueStore()
        smsLogin(kv, ScriptedConnectionFactory()).disconnect()
        val factory = ScriptedConnectionFactory()
        val keepalive = config.copy(transport = quiet.copy(pingInterval = kotlin.time.Duration.parse("29s")))
        val c = client(kv, factory, backgroundScope, keepalive)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        conn.answer(Opcode.LOGIN, loginReply(null))
        assertEquals(ClientState.Ready(5), starting.await())
        runCurrent()
        val (h, payload) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(Opcode.PING.value, h.opcodeValue)
        assertEquals(mapOf("interactive" to true), payload)
        // the server's own PING is answered on its seq with an empty body
        conn.feed(com.max.core.transport.packet(com.max.core.protocol.CmdType.PUSH, 777, Opcode.PING.value, null))
        runCurrent()
        val (reply, body) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(1, reply.cmdValue)
        assertEquals(777, reply.seq)
        assertNull(body)
        c.close()
    }
}
