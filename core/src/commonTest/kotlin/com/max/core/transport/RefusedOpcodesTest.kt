@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.transport

import com.max.core.ErrorKind
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.toMaxError
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration

/** 240 and 241 never reach the socket (the mobile server drops the connection on them); 242 does. */
class RefusedOpcodesTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)

    @Test
    fun pinsReadOpcodesAreNeverWritten() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = MaxTransport(quiet, factory, scope = backgroundScope)
        t.connect()
        runCurrent()
        val conn = factory.lastConnection!!
        // even a guard that passes everything cannot let them through
        t.outboundGuard = OutboundGuard { _, _ -> OutboundDecision.Pass }

        val viaRequest = assertFailsWith<OutboundBlockedException> { t.request(Opcode.GET_PINNED_MESSAGE_STATES, mapOf("chatIds" to listOf(7L))) }
        assertEquals(RefusedOpcodes.PINNED_UNSUPPORTED, viaRequest.errorKey)
        assertFailsWith<OutboundBlockedException> { t.request(Opcode.PINNED_MESSAGES_GET, mapOf("chatId" to 7L)) }
        assertFailsWith<OutboundBlockedException> { t.requestRaw(Opcode.PINNED_MESSAGES_GET.value, mapOf("chatId" to 7L)) }
        assertFailsWith<OutboundBlockedException> { t.sendRequest(Opcode.GET_PINNED_MESSAGE_STATES.value, mapOf("chatIds" to listOf(7L))) }
        runCurrent()
        assertNull(conn.written.tryReceive().getOrNull())
        assertEquals(0, t.pendingCount())

        val error = viaRequest.toMaxError()
        assertEquals(ErrorKind.SERVER, error.kind)
        assertEquals("pinned.unsupported", error.errorKey)

        // 242 still goes out
        t.sendRequest(Opcode.PINNED_MESSAGE_UPDATE.value, mapOf("chatId" to 7L, "action" to 0, "messageIds" to listOf(9L)))
        runCurrent()
        val (header, payload) = decodePayloadPacket(conn.written.tryReceive().getOrNull()!!)
        assertEquals(Opcode.PINNED_MESSAGE_UPDATE.value, header.opcodeValue)
        assertEquals(7L, ((payload as Map<*, *>)["chatId"] as Number).toLong())
        t.close()
    }

    @Test
    fun aGuardBlockWithoutKeyStaysUnknown() {
        val e = OutboundBlockedException(Opcode.MSG_TYPING.value, "ghost mode")
        assertNull(e.errorKey)
        assertEquals(ErrorKind.UNKNOWN, e.toMaxError().kind)
    }
}
