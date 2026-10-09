@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.core.api

import com.maxly.core.protocol.CmdType
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.session.DeviceInfo
import com.maxly.core.session.SessionConfig
import com.maxly.core.session.SessionMachine
import com.maxly.core.transport.ConnectionClosedException
import com.maxly.core.transport.ScriptedConnectionFactory
import com.maxly.core.transport.TransportConfig
import com.maxly.core.transport.ok
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class TypingTest {

    @Test
    fun typingPayloadWithoutPost() = runTest {
        val sink = ScriptSink()
        assertTrue(MessagesApi(sink).sendTyping(239067070, TypingType.STICKER))
        assertEquals(listOf(Opcode.MSG_TYPING), sink.opcodes)
        val payload = sink.sent[0].second as Map<*, *>
        assertEquals(listOf("chatId", "type"), payload.keys.toList())
        assertEquals(mapOf("chatId" to 239067070L, "type" to "STICKER"), payload)
        // fixmap(2) "chatId" uint32 239067070, "type" "STICKER"
        assertEquals("82a6636861744964ce0e3fdfbea474797065a7535449434b4552", sink.hex(0))
    }

    @Test
    fun typingPayloadWithPost() = runTest {
        val sink = ScriptSink()
        assertTrue(MessagesApi(sink).sendTyping(-100, TypingType.FILE, postId = 42))
        val payload = sink.sent[0].second as Map<*, *>
        assertEquals(listOf("chatId", "type", "postId"), payload.keys.toList())
        assertEquals(mapOf("chatId" to -100L, "type" to "FILE", "postId" to 42L), payload)
    }

    @Test
    fun typingTypesAndTheirNormalisation() {
        assertEquals(listOf("TEXT", "AUDIO", "VIDEO_MSG", "PHOTO", "VIDEO", "FILE", "STICKER"), TypingType.all)
        assertEquals(
            TypingType.all,
            listOf(TypingType.TEXT, TypingType.AUDIO, TypingType.VIDEO_MSG, TypingType.PHOTO, TypingType.VIDEO, TypingType.FILE, TypingType.STICKER),
        )
        for (t in TypingType.all) assertEquals(t, TypingType.effective(t))
        assertEquals(TypingType.TEXT, TypingType.effective(null))
        assertEquals(TypingType.TEXT, TypingType.effective(""))
        assertEquals(TypingType.TEXT, TypingType.effective("  "))
        assertEquals(TypingType.TEXT, TypingType.effective("sticker"))
        assertEquals(TypingType.TEXT, TypingType.effective("SOMETHING_NEW"))
        // sending passes any string through unchanged
        assertEquals("SOMETHING_NEW", MessagesApi(ScriptSink()).typingPayload(1, "SOMETHING_NEW")["type"])
    }

    @Test
    fun everyCallSendsAFrameWithoutThrottling() = runTest {
        val sink = ScriptSink()
        val api = MessagesApi(sink)
        repeat(3) { assertTrue(api.sendTyping(1, TypingType.TEXT)) }
        assertTrue(api.sendTyping(1, TypingType.AUDIO))
        assertEquals(4, sink.sent.size)
        assertEquals(listOf("TEXT", "TEXT", "TEXT", "AUDIO"), sink.sent.map { (it.second as Map<*, *>)["type"] })
    }

    @Test
    fun sendErrorsAreIgnoredButCancellationIsNot() = runTest {
        val api = MessagesApi(ScriptSink(ConnectionClosedException("not connected"), serverError(Opcode.MSG_TYPING, "proto.state")))
        assertFalse(api.sendTyping(1, TypingType.FILE))
        assertFalse(api.sendTyping(1, TypingType.FILE))
        val cancelled = MessagesApi(ScriptSink(CancellationException("stop")))
        assertFailsWith<CancellationException> { cancelled.sendTyping(1, TypingType.FILE) }
    }

    @Test
    fun sessionSendsTypingWithoutWaitingForAReply() = runTest {
        val device = DeviceInfo(deviceId = "d1e9c0de00000001", instanceId = "a1b2c3d4e5f60718", clientSessionId = 17)
        val factory = ScriptedConnectionFactory()
        val config = SessionConfig(TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false), device)
        val m = SessionMachine(config, factory, scope = backgroundScope)
        val api = MaxApi(m) { 1759100000000L }

        // not connected: nothing is thrown
        assertFalse(api.messages.sendTyping(5, TypingType.FILE))

        val connecting = async { m.connect() }
        runCurrent()
        val conn = factory.lastConnection!!
        val (hello, _) = decodePayloadPacket(conn.takeWritten()!!)
        conn.feed(ok(hello.seq, Opcode.SESSION_INIT.value, mapOf("callsSeed" to 1)))
        connecting.await()

        // returns without any reply from the server, and no waiter stays registered
        assertTrue(api.messages.sendTyping(5, TypingType.FILE, postId = 9))
        assertEquals(0, m.transport.pendingCount())
        val (header, payload) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(Opcode.MSG_TYPING.value, header.opcodeValue)
        assertEquals(CmdType.REQUEST.value, header.cmd)
        assertEquals(mapOf("chatId" to 5, "type" to "FILE", "postId" to 9), payload)
        // a late reply to it is dropped, and the session keeps working
        conn.feed(ok(header.seq, Opcode.MSG_TYPING.value, emptyMap<String, Any?>()))
        runCurrent()
        assertEquals(0, m.transport.pendingCount())
    }
}
