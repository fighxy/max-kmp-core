@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.session

import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.ConnectTimeoutException
import com.max.core.transport.ConnectionClosedException
import com.max.core.transport.ConnectionFactory
import com.max.core.transport.ConnectionState
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.RequestTimeoutException
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportConfig
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import com.max.core.transport.push
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

class SessionMachineTest {

    private val device = DeviceInfo(
        deviceId = "d1e9c0de00000001",
        instanceId = "a1b2c3d4e5f60718",
        clientSessionId = 17,
    )

    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)

    private fun TestScope.machine(
        factory: ConnectionFactory,
        transport: TransportConfig = quiet,
        afterHandshake: (suspend (com.max.core.transport.MaxTransport, HandshakeInfo) -> Unit)? = null,
    ) = SessionMachine(SessionConfig(transport, device), factory, scope = backgroundScope, afterHandshake = afterHandshake)

    /** Records every state the machine goes through. */
    private fun TestScope.record(m: SessionMachine): List<SessionState> {
        val seen = ArrayList<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.state.toList(seen) }
        return seen
    }

    /** Reads the next request, checks it is SESSION_INIT, and answers it; returns the request payload. */
    private suspend fun FakeRawConnection.answerHandshake(reply: Any? = mapOf("callsSeed" to 42, "device_name" to "Pixel 8")): Map<*, *> {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(Opcode.SESSION_INIT.value, header.opcodeValue)
        feed(ok(header.seq, Opcode.SESSION_INIT.value, reply))
        return payload as Map<*, *>
    }

    @Test
    fun handshakeReachesOnline() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory)
        val seen = record(m)
        val connecting = async { m.connect() }
        runCurrent()
        assertEquals("api.test", factory.lastHost)
        factory.lastConnection!!.answerHandshake(mapOf("callsSeed" to 42, "device_name" to "Pixel 8", "app-update-type" to 0))
        val info = connecting.await()
        assertEquals(42L, info.callsSeed)
        assertEquals("Pixel 8", info.deviceName)
        assertEquals(0L, info.appUpdateType)
        assertEquals(SessionState.Online(info), m.state.value)
        assertEquals(ConnectionState.Connected, m.transport.state.value)
        assertEquals(
            listOf(SessionState.Disconnected, SessionState.Connecting, SessionState.Handshaking, SessionState.Online(info)),
            seen,
        )
        // requests and pushes go through the session
        val pushed = async { m.pushes.first() }
        runCurrent()
        factory.lastConnection!!.feed(push(Opcode.NOTIF_TYPING.value, mapOf("chatId" to 1)))
        assertEquals(mapOf("chatId" to 1), pushed.await().payload)
    }

    @Test
    fun handshakePayloadMatchesReferences() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory)
        val connecting = async { m.connect() }
        runCurrent()
        val payload = factory.lastConnection!!.answerHandshake()
        connecting.await()
        assertEquals(listOf("mt_instanceid", "userAgent", "clientSessionId", "deviceId"), payload.keys.toList())
        assertEquals("a1b2c3d4e5f60718", payload["mt_instanceid"])
        assertEquals(17, payload["clientSessionId"])
        assertEquals("d1e9c0de00000001", payload["deviceId"])
        val ua = payload["userAgent"] as Map<*, *>
        assertEquals(
            linkedMapOf<Any?, Any?>(
                "deviceType" to "ANDROID",
                "appVersion" to "26.25.0",
                "osVersion" to "Android 14",
                "timezone" to "Europe/Moscow",
                "screen" to "428dpi 428dpi 1080x2400",
                "pushDeviceType" to "GCM",
                "arch" to "arm64-v8a",
                "locale" to "ru",
                "buildNumber" to 6790,
                "deviceName" to "Pixel 8",
                "deviceLocale" to "ru",
            ),
            ua,
        )
        assertEquals(ua.keys.toList(), (ua as LinkedHashMap<*, *>).keys.toList())
    }

    @Test
    fun handshakePayloadVariants() {
        // defaults: PyMax-style random ids and a fresh clientSessionId in 1..70 per handshake
        val defaults = DeviceInfo()
        assertTrue(Regex("[0-9a-f]{16}").matches(defaults.deviceId))
        assertTrue(Regex("[0-9a-f]{16}").matches(defaults.instanceId))
        val ids = (1..200).map { HandshakePayload.build(defaults, Random(it))["clientSessionId"] as Long }
        assertTrue(ids.all { it in 1L..70L })
        assertTrue(ids.toSet().size > 1)

        // kolibri-style omissions: empty instance id, clientSessionId 0, empty arch, buildNumber 0,
        // optional headerUserAgent / isPwa appended when set
        val kolibri = DeviceInfo(
            deviceId = "dev",
            instanceId = "",
            clientSessionId = 0,
            userAgent = UserAgentInfo(arch = "", buildNumber = 0, pushDeviceType = null, headerUserAgent = "UA/1", isPwa = true, release = 5),
        )
        val p = HandshakePayload.build(kolibri)
        assertEquals(listOf("userAgent", "deviceId"), p.keys.toList())
        val ua = p["userAgent"] as Map<*, *>
        assertEquals(
            listOf("deviceType", "appVersion", "osVersion", "timezone", "screen", "locale", "deviceName", "deviceLocale", "release", "headerUserAgent", "isPwa"),
            ua.keys.toList(),
        )

        // PyMax web shape: userAgent subset + deviceId only, default browser UA
        val web = HandshakePayload.build(
            DeviceInfo(deviceId = "w", userAgent = UserAgentInfo(deviceType = "WEB", appVersion = "26.8.4", osVersion = "Linux", screen = "1080x1920 1.0x", deviceName = "Chrome")),
        )
        assertEquals(listOf("userAgent", "deviceId"), web.keys.toList())
        val webUa = web["userAgent"] as Map<*, *>
        assertEquals(
            listOf("deviceType", "locale", "deviceLocale", "osVersion", "deviceName", "headerUserAgent", "appVersion", "screen", "timezone"),
            webUa.keys.toList(),
        )
        assertEquals(DEFAULT_WEB_HEADER_USER_AGENT, webUa["headerUserAgent"])
        assertEquals("OKMessages/26.25.0 (Android 14; Pixel 8; 428dpi 428dpi 1080x2400)", UserAgentInfo().httpUserAgent)
    }

    @Test
    fun serverErrorOnHandshakeFails() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory)
        val connecting = async { runCatching { m.connect() } }
        runCurrent()
        val conn = factory.lastConnection!!
        val (header, _) = decodePayloadPacket(conn.takeWritten()!!)
        conn.feed(errorReply(header.seq, 6, mapOf("error" to "proto.state", "message" to "bad app version", "localizedMessage" to "Update the app")))
        val error = connecting.await().exceptionOrNull()
        assertIs<ServerErrorException>(error)
        assertEquals("proto.state", error.errorKey)
        val state = m.state.value
        assertIs<SessionState.Failed>(state)
        assertSame(error, state.cause)
        assertEquals(ConnectionState.Disconnected, m.transport.state.value)
        assertFailsWith<ConnectionClosedException> { m.request(Opcode.PING, null) }
    }

    @Test
    fun handshakeTimeoutFails() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory, quiet.copy(requestTimeout = kotlin.time.Duration.parse("10s")))
        val connecting = async { runCatching { m.connect() } }
        runCurrent()
        factory.lastConnection!!.takeWritten() // SESSION_INIT goes out, nobody answers
        val error = connecting.await().exceptionOrNull()
        assertIs<RequestTimeoutException>(error)
        assertEquals(Opcode.SESSION_INIT.value, error.opcode)
        assertEquals(10_000, currentTime)
        assertIs<SessionState.Failed>(m.state.value)
    }

    @Test
    fun connectTimeoutFails() = runTest {
        val m = machine(ConnectionFactory { _, _, _, _ -> awaitCancellation() })
        assertFailsWith<ConnectTimeoutException> { m.connect() }
        assertEquals(15_000, currentTime)
        assertIs<ConnectTimeoutException>((m.state.value as SessionState.Failed).cause)
        // a new connect() after Failed starts a new attempt
        assertFailsWith<ConnectTimeoutException> { m.connect() }
        assertEquals(30_000, currentTime)
    }

    @Test
    fun connectIsIdempotent() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory)
        val a = async { m.connect() }
        val b = async { m.connect() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answerHandshake()
        val info = a.await()
        assertEquals(info, b.await())
        assertEquals(1, factory.openCount)
        // Online: returns the same handshake without any traffic
        assertEquals(info, m.connect())
        assertEquals(1, factory.openCount)
        runCurrent()
        assertNull(conn.takeWritten(Duration.ZERO))
    }

    @Test
    fun disconnectFromDisconnectedAndOnline() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory)
        m.disconnect()
        assertEquals(SessionState.Closed, m.state.value)
        val connecting = async { m.connect() }
        runCurrent()
        factory.lastConnection!!.answerHandshake()
        connecting.await()
        m.disconnect()
        assertEquals(SessionState.Closed, m.state.value)
        assertEquals(ConnectionState.Disconnected, m.transport.state.value)
        m.disconnect() // repeatable
        assertEquals(SessionState.Closed, m.state.value)
        // reconnect after Closed works
        val again = async { m.connect() }
        runCurrent()
        factory.lastConnection!!.answerHandshake()
        again.await()
        assertEquals(2, factory.openCount)
        assertIs<SessionState.Online>(m.state.value)
    }

    @Test
    fun disconnectWhileConnecting() = runTest {
        val opened = CompletableDeferred<Unit>()
        val m = machine(ConnectionFactory { _, _, _, _ -> opened.complete(Unit); awaitCancellation() })
        val connecting = async { runCatching { m.connect() } }
        opened.await()
        assertEquals(SessionState.Connecting, m.state.value)
        m.disconnect()
        assertIs<SessionClosedException>(connecting.await().exceptionOrNull())
        assertEquals(SessionState.Closed, m.state.value)
        advanceTimeBy(60_000)
        assertEquals(SessionState.Closed, m.state.value)
    }

    @Test
    fun disconnectWhileHandshakingIgnoresLateReply() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory)
        val connecting = async { runCatching { m.connect() } }
        runCurrent()
        val conn = factory.lastConnection!!
        val (header, _) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(SessionState.Handshaking, m.state.value)
        m.disconnect()
        assertIs<SessionClosedException>(connecting.await().exceptionOrNull())
        runCatching { conn.feed(ok(header.seq, 6, mapOf("callsSeed" to 1))) }
        runCurrent()
        assertEquals(SessionState.Closed, m.state.value)
        assertEquals(ConnectionState.Disconnected, m.transport.state.value)
    }

    @Test
    fun dropWithoutAutoReconnectFails() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory)
        val connecting = async { m.connect() }
        runCurrent()
        factory.lastConnection!!.answerHandshake()
        connecting.await()
        factory.lastConnection!!.close()
        runCurrent()
        assertIs<ConnectionClosedException>((m.state.value as SessionState.Failed).cause)
        advanceTimeBy(60_000)
        assertEquals(1, factory.openCount)
        // disconnect from Failed
        m.disconnect()
        assertEquals(SessionState.Closed, m.state.value)
    }

    @Test
    fun reconnectRedoesHandshake() = runTest {
        val factory = ScriptedConnectionFactory()
        val hookCalls = ArrayList<HandshakeInfo>()
        val m = machine(factory, quiet.copy(autoReconnect = true)) { _, info -> hookCalls += info }
        val seen = record(m)
        val connecting = async { m.connect() }
        runCurrent()
        factory.lastConnection!!.answerHandshake(mapOf("callsSeed" to 1))
        connecting.await()

        factory.lastConnection!!.close() // drop
        runCurrent()
        assertEquals(SessionState.Reconnecting(1), m.state.value)
        // connect() while reconnecting waits for the next Online
        val waiting = async { m.connect() }
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, factory.openCount)
        assertEquals(SessionState.Handshaking, m.state.value)
        val second = factory.lastConnection!!
        val (header, payload) = decodePayloadPacket(second.takeWritten()!!)
        assertEquals(1, header.seq) // first request on the new connection
        assertEquals("d1e9c0de00000001", (payload as Map<*, *>)["deviceId"])
        second.feed(ok(header.seq, 6, mapOf("callsSeed" to 2)))
        assertEquals(2L, waiting.await().callsSeed)
        assertEquals(2L, (m.state.value as SessionState.Online).handshake.callsSeed)
        assertEquals(listOf(1L, 2L), hookCalls.map { it.callsSeed })
        assertEquals(ConnectionState.Connected, m.transport.state.value)

        val expected = listOf(
            SessionState.Disconnected, SessionState.Connecting, SessionState.Handshaking,
            SessionState.Online(hookCalls[0]), SessionState.Reconnecting(1), SessionState.Handshaking,
            SessionState.Online(hookCalls[1]),
        )
        assertEquals(expected, seen)
        m.disconnect()
    }

    @Test
    fun rejectedHandshakeDuringReconnectRetriesAndDisconnectStopsIt() = runTest {
        val factory = ScriptedConnectionFactory()
        val m = machine(factory, quiet.copy(autoReconnect = true))
        val connecting = async { m.connect() }
        runCurrent()
        factory.lastConnection!!.answerHandshake()
        connecting.await()

        factory.lastConnection!!.close()
        advanceTimeBy(2_001)
        runCurrent()
        val second = factory.lastConnection!!
        val (header, _) = decodePayloadPacket(second.takeWritten()!!)
        second.feed(errorReply(header.seq, 6, mapOf("error" to "service.unavailable", "message" to "try later")))
        runCurrent()
        val state = m.state.value
        assertIs<SessionState.Reconnecting>(state)
        assertEquals(2, state.attempt)
        assertIs<ServerErrorException>(state.lastError)

        // next attempt after 4 s; disconnect while reconnecting stops everything
        m.disconnect()
        assertEquals(SessionState.Closed, m.state.value)
        advanceTimeBy(60_000)
        assertEquals(2, factory.openCount)
        assertEquals(SessionState.Closed, m.state.value)
    }

    @Test
    fun afterHandshakeHookRunsBeforeOnlineAndItsFailureFails() = runTest {
        val factory = ScriptedConnectionFactory()
        // e.g. a stored-token LOGIN (opcode 19); here the hook sends one request and checks the reply
        val m = machine(factory) { transport, info ->
            assertEquals(42L, info.callsSeed)
            transport.request(Opcode.LOGIN, mapOf("token" to "stored"))
        }
        val connecting = async { runCatching { m.connect() } }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answerHandshake()
        runCurrent()
        val (login, payload) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(Opcode.LOGIN.value, login.opcodeValue)
        assertEquals(mapOf("token" to "stored"), payload)
        assertEquals(SessionState.Handshaking, m.state.value)
        conn.feed(errorReply(login.seq, Opcode.LOGIN.value, mapOf("error" to "login.token", "message" to "FAIL_LOGIN_TOKEN")))
        val error = connecting.await().exceptionOrNull()
        assertIs<ServerErrorException>(error)
        assertTrue(error.isSessionExpired)
        assertIs<SessionState.Failed>(m.state.value)
    }
}
