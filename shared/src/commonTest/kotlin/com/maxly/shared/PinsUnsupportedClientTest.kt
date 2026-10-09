@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.shared

import com.maxly.core.ErrorKind
import com.maxly.core.api.PinAction
import com.maxly.core.media.HttpResponse
import com.maxly.core.media.MediaHttp
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.toMaxError
import com.maxly.core.transport.FakeRawConnection
import com.maxly.core.transport.OutboundBlockedException
import com.maxly.core.transport.ScriptedConnectionFactory
import com.maxly.core.transport.TransportConfig
import com.maxly.core.transport.ok
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** [MaxClient.pinnedStates] / [MaxClient.pinnedMessages] fail at once and write nothing; 242 still goes out. */
class PinsUnsupportedClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?) {
        val (header, _) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
    }

    /** Opcodes of every frame written from now until the socket is quiet. */
    private suspend fun FakeRawConnection.drain(): List<Int> = buildList {
        while (true) add(decodePayloadPacket(takeWritten(100.milliseconds) ?: break).first.opcodeValue)
    }

    @Suppress("DEPRECATION")
    @Test
    fun pinsReadsAreRefusedAndNeverWritten() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(
            Opcode.LOGIN,
            mapOf("profile" to mapOf("contact" to mapOf("id" to 5L, "names" to listOf(mapOf("name" to "Me")))), "chats" to emptyList<Any?>(), "time" to 1700L),
        )
        login.await()
        runCurrent()
        conn.drain()

        val states = runCatching { c.pinnedStates(listOf(7L)) }.exceptionOrNull()
        val messages = runCatching { c.pinnedMessages(7L, 9L, 20) }.exceptionOrNull()
        assertIs<OutboundBlockedException>(states)
        assertIs<OutboundBlockedException>(messages)
        for (e in listOf(states, messages)) {
            val error = e.toMaxError()
            assertEquals(ErrorKind.SERVER, error.kind)
            assertEquals("pinned.unsupported", error.errorKey)
        }
        val written = conn.drain()
        assertFalse(Opcode.GET_PINNED_MESSAGE_STATES.value in written)
        assertFalse(Opcode.PINNED_MESSAGES_GET.value in written)

        // 242 is untouched
        val update = async { c.api.messages.updatePinnedMessages(7L, PinAction.PIN, listOf(9L)) }
        runCurrent()
        conn.answer(Opcode.PINNED_MESSAGE_UPDATE, mapOf("pinnedMessagesState" to mapOf("chatId" to 7L, "lastAction" to 0, "totalPinnedMessagesCount" to 1)))
        assertEquals(7L, update.await().chatId)
    }
}
