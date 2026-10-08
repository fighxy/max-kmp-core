@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.ok
import com.max.core.transport.push
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration

/** The message edit time (`updateTime`) through [MaxClient]: login, history and edit pushes. */
class EditTimeClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private fun message(id: Long, time: Long, updateTime: Long? = null, status: String? = null, text: String = "m$id") =
        linkedMapOf<String, Any?>("id" to id, "sender" to 20L, "time" to time, "type" to "USER", "text" to text).also {
            if (updateTime != null) it["updateTime"] = updateTime
            if (status != null) it["status"] = status
        }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?) {
        val (header, _) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
    }

    @Test
    fun editTimeReachesTheStore() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(
            Opcode.LOGIN,
            mapOf(
                "profile" to mapOf("contact" to mapOf("id" to 9)),
                "chats" to listOf(mapOf("id" to 7, "type" to "CHAT", "status" to "ACTIVE", "lastMessage" to message(2, 200, 250))),
                "messages" to mapOf("7" to listOf(message(1, 100), message(2, 200, 250))),
                "time" to 1700L,
            ),
        )
        login.await()
        runCurrent()
        fun stored(id: Long) = c.store.state.value.messagesOf(7).single { it.id == id }
        assertNull(stored(1).updateTime)
        assertEquals(250L, stored(2).updateTime)
        assertEquals(250L, c.store.state.value.chats.getValue(7).lastMessage!!.updateTime)

        // an edit push moves it on, for the chat's last message too
        conn.feed(push(Opcode.NOTIF_MESSAGE.value, mapOf("chatId" to 7L, "message" to message(2, 200, 400, status = "EDITED", text = "v2"))))
        runCurrent()
        assertEquals(400L, stored(2).updateTime)
        assertEquals(400L, c.store.state.value.chats.getValue(7).lastMessage!!.updateTime)

        // a history page carries it
        val page = async { c.loadHistory(7) }
        runCurrent()
        conn.answer(Opcode.CHAT_HISTORY, mapOf("messages" to listOf(message(0, 50, 60), message(1, 100, 130))))
        assertEquals(listOf(60L, 130L), page.await().messages.map { it.updateTime })
        assertEquals(130L, stored(1).updateTime)
    }
}
