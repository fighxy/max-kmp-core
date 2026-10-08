@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.api

import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.MaxTransport
import com.max.core.transport.OutboundBlockedException
import com.max.core.transport.OutboundDecision
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The rules of both privacy switches and their transport guard. */
class GhostModeTest {
    private fun decide(op: Opcode, payload: Any?, ghost: Boolean = true, hide: Boolean = true) =
        GhostMode.decide(op.value, payload, ghost, hide)

    private fun rewritten(d: OutboundDecision): Any? = assertIs<OutboundDecision.Rewrite>(d).payload

    @Test
    fun ghostModeTakesTheOnlineFlagAndTypingOnly() {
        assertEquals(mapOf("interactive" to false), rewritten(decide(Opcode.PING, mapOf("interactive" to true))))
        assertEquals(mapOf("interactive" to false), rewritten(decide(Opcode.PING, null)))
        assertEquals(OutboundDecision.Pass, decide(Opcode.PING, mapOf("interactive" to false)))
        val login = linkedMapOf("token" to "t", "interactive" to true, "chatsCount" to 40)
        assertEquals(linkedMapOf<Any?, Any?>("token" to "t", "interactive" to false, "chatsCount" to 40), rewritten(decide(Opcode.LOGIN, login)))
        assertEquals(mapOf("chatId" to 1L, "interactive" to false), rewritten(decide(Opcode.CHAT_HISTORY, mapOf("chatId" to 1L, "interactive" to true))))
        // history without the flag stays as it is
        assertEquals(OutboundDecision.Pass, decide(Opcode.CHAT_HISTORY, mapOf("chatId" to 1L)))
        for (type in listOf("TEXT", "STICKER", "VIDEO_MSG", "AUDIO", "PHOTO", "VIDEO", "FILE")) {
            assertIs<OutboundDecision.Block>(decide(Opcode.MSG_TYPING, mapOf("chatId" to 1L, "type" to type), hide = false))
        }
        // off: everything goes as it is
        assertEquals(OutboundDecision.Pass, decide(Opcode.PING, mapOf("interactive" to true), ghost = false, hide = false))
        assertEquals(OutboundDecision.Pass, decide(Opcode.MSG_TYPING, mapOf("chatId" to 1L), ghost = false, hide = true))
        assertEquals(OutboundDecision.Pass, decide(Opcode.CHAT_MARK, mapOf("type" to "READ_MESSAGE"), ghost = true, hide = false))
    }

    @Test
    fun hiddenReadReceiptsHoldReadsStoryViewsAndDeliveries() {
        assertIs<OutboundDecision.Block>(decide(Opcode.CHAT_MARK, mapOf("chatId" to 1L, "type" to "READ_MESSAGE", "messageId" to 3L), ghost = false))
        assertIs<OutboundDecision.Block>(decide(Opcode.CHAT_MARK, mapOf("chatId" to 1L), ghost = false))
        // "mark unread" tells nobody anything and goes out
        assertEquals(OutboundDecision.Pass, decide(Opcode.CHAT_MARK, mapOf("chatId" to 1L, "type" to "SET_AS_UNREAD"), ghost = false))
        assertIs<OutboundDecision.Block>(decide(Opcode.STORIES_MARK, mapOf("storyId" to 1L), ghost = false))
        assertIs<OutboundDecision.Block>(decide(Opcode.MSG_DELIVERY, mapOf("chatId" to 1L), ghost = false))
        assertEquals(OutboundDecision.Pass, decide(Opcode.STORIES_MARK, mapOf("storyId" to 1L), ghost = true, hide = false))
    }

    @Test
    fun everyOtherOpcodePassesUntouchedWithBothSwitchesOn() {
        val touched = setOf(Opcode.PING, Opcode.LOGIN, Opcode.CHAT_HISTORY, Opcode.MSG_TYPING, Opcode.CHAT_MARK, Opcode.STORIES_MARK, Opcode.MSG_DELIVERY)
        val payload = mapOf("chatId" to 1L, "interactive" to true, "type" to "READ_MESSAGE")
        for (op in Opcode.entries) {
            if (op in touched) continue
            assertEquals(OutboundDecision.Pass, decide(op, payload), op.name)
        }
    }

    @Test
    fun localReadsRoundTripAndSkipJunk() {
        val reads = linkedMapOf(-70L to LocalRead(11, 1_100), 5L to LocalRead(2, 30))
        assertEquals("-70:11:1100,5:2:30", LocalRead.encode(reads))
        assertEquals(reads, LocalRead.decode("-70:11:1100,5:2:30"))
        assertEquals(mapOf(5L to LocalRead(2, 30)), LocalRead.decode("x:1:2, 5:2:30 ,1:2,"))
        assertEquals(emptyMap(), LocalRead.decode(null))
        assertEquals(emptyMap(), LocalRead.decode(""))
    }

    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)

    @Test
    fun theTransportGuardSeesEveryFrameWhenItIsWritten() = runTest {
        var ghost = false
        var hide = false
        val factory = ScriptedConnectionFactory()
        val t = MaxTransport(quiet.copy(pingInterval = 30.seconds), factory, scope = backgroundScope)
        t.outboundGuard = GhostMode.guard({ ghost }, { hide })
        t.connect()
        runCurrent()
        val conn = factory.lastConnection!!
        // the first PING goes out right after connect, before ghost mode is on
        assertEquals(mapOf("interactive" to true), decodePayloadPacket(conn.written.tryReceive().getOrNull()!!).second)
        ghost = true
        hide = true
        // fire-and-forget and request/response paths both stop, and nothing is written
        val typing = assertFailsWith<OutboundBlockedException> { t.sendRequest(Opcode.MSG_TYPING.value, mapOf("chatId" to 1L, "type" to "TEXT")) }
        assertEquals(Opcode.MSG_TYPING.value, typing.opcode)
        assertFailsWith<OutboundBlockedException> { t.requestRaw(Opcode.CHAT_MARK.value, mapOf("chatId" to 1L, "type" to "READ_MESSAGE")) }
        assertFailsWith<OutboundBlockedException> { t.request(Opcode.STORIES_MARK, mapOf("storyId" to 1L)) }
        assertNull(conn.written.tryReceive().getOrNull())
        // the PING timer keeps its flag but writes the guarded one
        assertTrue(t.pingInteractive)
        advanceTimeBy(30_001)
        runCurrent()
        val (h, payload) = decodePayloadPacket(conn.written.tryReceive().getOrNull()!!)
        assertEquals(Opcode.PING.value, h.opcodeValue)
        assertEquals(mapOf("interactive" to false), payload)
        // a blocking guard does not stop the timer
        t.outboundGuard = com.max.core.transport.OutboundGuard { _, _ -> OutboundDecision.Block("test") }
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(conn.written.tryReceive().getOrNull())
        ghost = false
        t.outboundGuard = GhostMode.guard({ ghost }, { hide })
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(mapOf("interactive" to true), decodePayloadPacket(conn.written.tryReceive().getOrNull()!!).second)
        t.close()
    }
}
