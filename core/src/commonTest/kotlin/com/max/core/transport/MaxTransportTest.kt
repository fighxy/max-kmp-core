@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.transport

import com.max.core.protocol.CmdType
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.protocol.decodePayloadPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MaxTransportTest {

    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)

    private fun TestScope.transport(
        factory: ConnectionFactory,
        config: TransportConfig = quiet,
        onConnected: (suspend (MaxTransport) -> Unit)? = null,
    ) = MaxTransport(config, factory, scope = backgroundScope, onConnected = onConnected)

    private fun decode(frame: ByteArray): Pair<PacketHeader, Any?> = decodePayloadPacket(frame)

    @Test
    fun connectPassesConfigToFactory() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory, quiet.copy(port = 8443, proxyUrl = "socks5h://u:p%21@127.0.0.1:1080", insecureTls = true))
        assertEquals(ConnectionState.Disconnected, t.state.value)
        t.connect()
        assertEquals(ConnectionState.Connected, t.state.value)
        assertEquals("api.test", factory.lastHost)
        assertEquals(8443, factory.lastPort)
        assertEquals(TlsOptions(insecure = true, trustMincifryCa = true, connectTimeout = 15.seconds), factory.lastTls)
        assertEquals(ProxyConfig(ProxyKind.SOCKS5H, "127.0.0.1", 1080, "u", "p!"), factory.lastProxy)
        t.connect() // already connected: no second open
        assertEquals(1, factory.openCount)
        t.close()
        assertEquals(ConnectionState.Disconnected, t.state.value)
        assertFailsWith<ConnectionClosedException> { t.request(Opcode.PING, null) }
    }

    @Test
    fun requestSeqStartsAtOneAndRepliesMatchOutOfOrder() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!

        val first = async { t.request(Opcode.CHAT_INFO, mapOf("chatIds" to listOf(1))) }
        val frame1 = conn.takeWritten()!!
        val second = async { t.request(Opcode.CHAT_HISTORY, null) }
        val frame2 = conn.takeWritten()!!

        val (h1, p1) = decode(frame1)
        val (h2, p2) = decode(frame2)
        assertEquals(PROTOCOL_VERSION, h1.version)
        assertEquals(CmdType.REQUEST.value, h1.cmd)
        assertEquals(1, h1.seq)
        assertEquals(Opcode.CHAT_INFO.value, h1.opcodeValue)
        assertEquals(mapOf("chatIds" to listOf(1)), p1)
        assertEquals(2, h2.seq)
        assertEquals(0, h2.length) // null payload → empty body
        assertNull(p2)

        conn.feed(ok(2, Opcode.CHAT_HISTORY.value, mapOf("r" to 2)))
        conn.feed(ok(1, Opcode.CHAT_INFO.value, mapOf("r" to 1)))
        assertEquals(mapOf("r" to 2), second.await().payload)
        val r1 = first.await()
        assertEquals(mapOf("r" to 1), r1.payload)
        assertEquals(1, r1.seq)
        assertEquals(CmdType.OK.value.toInt(), r1.cmd)

        // third request continues the sequence
        val third = async { t.request(Opcode.PING.value, null) }
        assertEquals(3, decode(conn.takeWritten()!!).first.seq)
        conn.feed(ok(3, 1))
        third.await()
    }

    @Test
    fun errorAndNotFoundReplies() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!

        val err = async { runCatching { t.request(Opcode.LOGIN, mapOf("token" to "x")) } }
        val s1 = decode(conn.takeWritten()!!).first.seq
        conn.feed(errorReply(s1, Opcode.LOGIN.value, mapOf("error" to "login.token", "message" to "FAIL_LOGIN_TOKEN", "localizedMessage" to " Session expired ")))
        val e = assertIs<ServerErrorException>(err.await().exceptionOrNull())
        assertEquals("Session expired", e.message)
        assertEquals("login.token", e.errorKey)
        assertTrue(e.isSessionExpired)

        val nf = async { runCatching { t.request(Opcode.CHAT_INFO, null) } }
        val s2 = decode(conn.takeWritten()!!).first.seq
        conn.feed(notFound(s2, Opcode.CHAT_INFO.value))
        val n = assertIs<NotFoundException>(nf.await().exceptionOrNull())
        assertEquals(CmdType.NOT_FOUND.value.toInt(), n.packet.cmd)

        // requestRaw returns the reply for any cmd
        val raw = async { t.requestRaw(Opcode.CHAT_INFO.value, null) }
        val s3 = decode(conn.takeWritten()!!).first.seq
        conn.feed(errorReply(s3, Opcode.CHAT_INFO.value, mapOf("title" to "t")))
        assertEquals(CmdType.ERROR.value.toInt(), raw.await().cmd)
        assertEquals("t", ServerErrorException.from(raw.await()).message)
    }

    @Test
    fun requestTimesOutAfter30s() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!
        val start = currentTime
        val e = assertFailsWith<RequestTimeoutException> { t.request(Opcode.SYNC, null) }
        assertEquals(30_000, currentTime - start)
        assertEquals(1, e.seq)
        assertEquals(Opcode.SYNC.value, e.opcode)
        // a late reply is dropped: it is neither a push nor a crash
        conn.takeWritten()
        conn.feed(ok(1, Opcode.SYNC.value))
        runCurrent()
        assertEquals(ConnectionState.Connected, t.state.value)
    }

    @Test
    fun pushesAndRawChunksReachTheFlows() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!

        val pushed = async { t.pushes.first() }
        val chunk = async { t.receive().first() }
        runCurrent()
        // an OK reply for a seq nobody waits for must not show up as a push
        val orphan = ok(99, 1)
        val notif = push(Opcode.NOTIF_MESSAGE.value, mapOf("chatId" to 5, "message" to mapOf("text" to "hi")))
        conn.feed(orphan + notif)
        val p = pushed.await()
        assertEquals(Opcode.NOTIF_MESSAGE.value, p.opcode)
        assertEquals(0, p.cmd)
        assertEquals(mapOf("chatId" to 5, "message" to mapOf("text" to "hi")), p.payload)
        assertContentEquals(orphan + notif, chunk.await())
    }

    @Test
    fun repliesSplitAcrossChunksAndBatched() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!
        val a = async { t.request(Opcode.CHATS_LIST, null) }
        conn.takeWritten()
        val b = async { t.request(Opcode.CONTACT_INFO, null) }
        conn.takeWritten()
        val c = async { t.request(Opcode.CONFIG, null) }
        conn.takeWritten()

        val r1 = ok(1, Opcode.CHATS_LIST.value, mapOf("chats" to List(50) { it }))
        val r2 = ok(2, Opcode.CONTACT_INFO.value, mapOf("c" to "x"))
        val r3 = ok(3, Opcode.CONFIG.value, mapOf("k" to true))
        val stream = r1 + r2 + r3
        conn.feed(stream.copyOfRange(0, 7))
        conn.feed(stream.copyOfRange(7, r1.size + 4))
        conn.feed(stream.copyOfRange(r1.size + 4, stream.size))
        assertEquals(List(50) { it }, (a.await().payload as Map<*, *>)["chats"])
        assertEquals(mapOf("c" to "x"), b.await().payload)
        assertEquals(mapOf("k" to true), c.await().payload)
    }

    @Test
    fun pingGoesOutEveryIntervalStartingAfterOneInterval() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory, quiet.copy(pingInterval = 30.seconds))
        t.connect()
        val conn = factory.lastConnection!!

        advanceTimeBy(29_999)
        runCurrent()
        assertNull(conn.written.tryReceive().getOrNull())
        advanceTimeBy(2)
        runCurrent()
        val (h, payload) = decode(conn.written.tryReceive().getOrNull()!!)
        assertEquals(Opcode.PING.value, h.opcodeValue)
        assertEquals(CmdType.REQUEST.value, h.cmd)
        assertEquals(1, h.seq)
        assertEquals(mapOf("interactive" to true), payload)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, decode(conn.written.tryReceive().getOrNull()!!).first.seq)
        // fire-and-forget: the reply is simply dropped
        conn.feed(ok(1, 1))
        runCurrent()
        assertEquals(ConnectionState.Connected, t.state.value)
        t.close()
    }

    @Test
    fun dropFailsPendingAndReconnectsWithBackoff() = runTest {
        val openTimes = ArrayList<Long>()
        var failuresLeft = 0
        var current: FakeRawConnection? = null
        val factory = ConnectionFactory { _, _, _, _ ->
            openTimes += currentTime
            if (failuresLeft > 0) {
                failuresLeft--
                throw ConnectionClosedException("refused")
            }
            FakeRawConnection().also { current = it }
        }
        val t = transport(factory, quiet.copy(autoReconnect = true))
        t.connect()
        val first = current!!

        val pending = async { runCatching { t.request(Opcode.SYNC, null) } }
        first.takeWritten()
        failuresLeft = 3
        first.close() // peer closes the stream → read returns -1
        assertIs<ConnectionClosedException>(pending.await().exceptionOrNull())
        runCurrent()
        assertEquals(ConnectionState.Disconnected, t.state.value)

        // attempts after 2, 4, 8 s fail; the one after 15 s more succeeds
        advanceTimeBy(29_001)
        runCurrent()
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 29_000L), openTimes)
        assertEquals(ConnectionState.Connected, t.state.value)

        // after a success the backoff restarts at 2 s
        val second = current!!
        val dropAt = currentTime
        second.close()
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(dropAt + 2_000, openTimes.last())
        assertEquals(ConnectionState.Connected, t.state.value)

        // new connection → seq restarts at 1
        val third = current!!
        val r = async { t.request(Opcode.PING, null) }
        assertEquals(1, decode(third.takeWritten()!!).first.seq)
        third.feed(ok(1, 1))
        r.await()
        t.close()
        val before = openTimes.size
        advanceTimeBy(60_000)
        assertEquals(before, openTimes.size) // close stops reconnecting
    }

    @Test
    fun noReconnectWhenDisabled() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        factory.lastConnection!!.close()
        advanceTimeBy(120_000)
        assertEquals(1, factory.openCount)
        assertEquals(ConnectionState.Disconnected, t.state.value)
        t.connect() // manual reconnect works
        assertEquals(2, factory.openCount)
        assertEquals(ConnectionState.Connected, t.state.value)
    }

    @Test
    fun connectTimeoutAfter15s() = runTest {
        val t = transport(ConnectionFactory { _, _, _, _ -> awaitCancellation() })
        assertFailsWith<ConnectTimeoutException> { t.connect() }
        assertEquals(15_000, currentTime)
        assertEquals(ConnectionState.Disconnected, t.state.value)
    }

    @Test
    fun onConnectedRunsBeforeConnectedAndCanRequest() = runTest {
        val factory = ScriptedConnectionFactory()
        val seen = CompletableDeferred<Pair<ConnectionState, Any?>>()
        val t = transport(factory, onConnected = { tr ->
            val stateDuring = tr.state.value
            val handshake = async { tr.request(Opcode.SESSION_INIT, mapOf("deviceId" to "d")) }
            val conn = factory.lastConnection!!
            val (h, _) = decode(conn.takeWritten()!!)
            conn.feed(ok(h.seq, Opcode.SESSION_INIT.value, mapOf("callsSeed" to 7)))
            seen.complete(stateDuring to handshake.await().payload)
        })
        t.connect()
        assertEquals(ConnectionState.Connecting to mapOf("callsSeed" to 7), seen.await())
        assertEquals(ConnectionState.Connected, t.state.value)
    }

    @Test
    fun failingOnConnectedDropsTheConnection() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory, onConnected = { error("handshake rejected") })
        assertFailsWith<IllegalStateException> { t.connect() }
        assertEquals(ConnectionState.Disconnected, t.state.value)
        assertFailsWith<ConnectionClosedException> { factory.lastConnection!!.write(byteArrayOf(1)) }
        assertFalse(t.state.value == ConnectionState.Connected)
    }

    @Test
    fun sendWritesRawBytes() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        assertFailsWith<ConnectionClosedException> { t.send(byteArrayOf(1)) }
        t.connect()
        t.send(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), factory.lastConnection!!.takeWritten(1.milliseconds))
    }
}
