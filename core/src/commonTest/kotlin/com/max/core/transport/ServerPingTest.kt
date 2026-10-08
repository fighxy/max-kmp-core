@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.transport

import com.max.core.api.GhostMode
import com.max.core.protocol.CmdType
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePacket
import com.max.core.protocol.decodePayloadPacket
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration

/** The server's own PING (opcode 1, `cmd` 0) is answered with `cmd` 1, the same `seq`, an empty body. */
class ServerPingTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)

    private fun TestScope.transport(factory: ConnectionFactory) =
        MaxTransport(quiet, factory, scope = backgroundScope)

    private fun serverPing(seq: Int, payload: Any? = null): ByteArray = packet(CmdType.PUSH, seq, Opcode.PING.value, payload)

    @Test
    fun serverPingIsAnsweredWithAnEmptyOkOnTheSameSeq() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!

        conn.feed(serverPing(seq = 4242))
        runCurrent()
        val (h, body) = decodePacket(conn.takeWritten()!!)
        assertEquals(CmdType.OK.value, h.cmd)
        assertEquals(4242, h.seq)
        assertEquals(Opcode.PING.value, h.opcodeValue)
        assertEquals(0, h.length)
        assertEquals(0, h.compressionFlag)
        assertEquals(0, body.size)

        // the answer does not use the client's seq counter: the next request is still seq 1
        val r = async { t.request(Opcode.CHAT_INFO, null) }
        assertEquals(1, decodePacket(conn.takeWritten()!!).first.seq)
        conn.feed(ok(1, Opcode.CHAT_INFO.value))
        r.await()
    }

    @Test
    fun serverPingIsNotAPushAndItsBodyIsIgnored() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!

        val next = async { t.pushes.first() }
        val nextReliable = async { t.reliablePushes().first() }
        runCurrent()
        conn.feed(serverPing(seq = 9, payload = mapOf("interactive" to true)))
        conn.feed(push(Opcode.NOTIF_TYPING.value, mapOf("chatId" to 1)))
        // only the real push reaches the flows
        assertEquals(Opcode.NOTIF_TYPING.value, next.await().opcode)
        assertEquals(Opcode.NOTIF_TYPING.value, nextReliable.await().opcode)
        val (h, body) = decodePacket(conn.takeWritten()!!)
        assertEquals(9, h.seq)
        assertEquals(0, body.size)
    }

    @Test
    fun ghostModeStillAnswersWithAnEmptyBody() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.outboundGuard = GhostMode.guard(ghostMode = { true }, hideReadReceipts = { true })
        t.connect()
        val conn = factory.lastConnection!!

        conn.feed(serverPing(seq = 3))
        runCurrent()
        val (h, payload) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(CmdType.OK.value, h.cmd)
        assertEquals(3, h.seq)
        assertEquals(0, h.length)
        assertNull(payload) // not rewritten to {interactive: false}

        // the client's own PING still goes through the guard
        t.sendRequest(Opcode.PING.value, mapOf("interactive" to true))
        assertEquals(mapOf("interactive" to false), decodePayloadPacket(conn.takeWritten()!!).second)
    }

    @Test
    fun aClientPingReplyIsStillOnlyAReply() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = transport(factory)
        t.connect()
        val conn = factory.lastConnection!!
        // cmd 1 for opcode 1 is the answer to our own PING, not a server PING: nothing is written back
        conn.feed(ok(5, Opcode.PING.value))
        runCurrent()
        assertNull(conn.written.tryReceive().getOrNull())
    }
}
