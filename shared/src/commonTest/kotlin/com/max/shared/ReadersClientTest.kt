@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.api.MessageReader
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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/** [MaxClient.loadMessageReaders] and [MaxClient.isMessageReadersAvailable] over a scripted connection. */
class ReadersClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private fun message(id: Long, time: Long, sender: Long) =
        mapOf("id" to id, "chatId" to 100L, "sender" to sender, "time" to time, "type" to "USER", "text" to "m$id")

    private fun group(participants: Map<String, Long>, count: Int = 5) = mapOf(
        "id" to 100, "type" to "CHAT", "status" to "ACTIVE", "owner" to 9, "lastEventTime" to 1_000,
        "participantsCount" to count, "participants" to participants, "lastMessage" to message(5, 1_000, 20),
    )

    private fun loginReply() = mapOf(
        "profile" to mapOf("contact" to mapOf("id" to 9, "names" to listOf(mapOf("firstName" to "Me")))),
        "contacts" to listOf(mapOf("id" to 20, "names" to listOf(mapOf("firstName" to "Ann")))),
        "chats" to listOf(
            group(mapOf("9" to 1_000L, "20" to 1_000L, "21" to 500L, "22" to 800L, "23" to 400L)),
            mapOf("id" to 200, "type" to "DIALOG", "status" to "ACTIVE", "owner" to 9, "participants" to mapOf("9" to 1L, "20" to 1L)),
        ),
        "messages" to mapOf("100" to listOf(message(4, 900, 9), message(5, 1_000, 20))),
        "config" to mapOf("server" to mapOf("max-readmarks" to 5)),
        "time" to 1700L,
    )

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun FakeRawConnection.fail(opcode: Opcode) {
        val (header, _) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(errorReply(header.seq, opcode.value, mapOf("error" to "not.found", "message" to "not found")))
    }

    private suspend fun TestScope.loggedIn(factory: ScriptedConnectionFactory): MaxClient {
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(Opcode.LOGIN, loginReply())
        login.await()
        runCurrent()
        return c
    }

    @Test
    fun availabilityFollowsTheStoredChatAndTheServerLimit() = runTest {
        val c = loggedIn(ScriptedConnectionFactory())
        assertEquals(5, c.accountConfig.value!!.maxReadmarks)
        assertTrue(c.isMessageReadersAvailable(100))
        assertFalse(c.isMessageReadersAvailable(200))
        assertFalse(c.isMessageReadersAvailable(999))
    }

    @Test
    fun readersMergeFreshMarksPushesAndReactions() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val conn = factory.lastConnection!!
        // 21 read up to 1 000 after the last snapshot
        conn.feed(push(Opcode.NOTIF_MARK.value, mapOf("setAsUnread" to false, "chatId" to 100L, "userId" to 21L, "mark" to 1_000L)))
        runCurrent()

        // message 4 is mine (in the store): no MSG_GET
        val load = async { c.loadMessageReaders(100, 4) }
        runCurrent()
        val info = conn.answer(Opcode.CHAT_INFO, mapOf("chats" to listOf(group(mapOf("9" to 1_000L, "20" to 1_000L, "21" to 500L, "22" to 1_500L, "23" to 400L)))))!!
        assertEquals(listOf(100L), (info["chatIds"] as List<*>).map { (it as Number).toLong() })
        runCurrent()
        val sent = conn.answer(Opcode.MSG_GET_DETAILED_REACTIONS, mapOf("reactions" to listOf(mapOf("userId" to 23, "reaction" to "👍"), mapOf("userId" to 9, "reaction" to "❤️"))))!!
        assertEquals(setOf<Any?>("chatId", "messageId", "count"), sent.keys)
        assertEquals(100L, (sent["count"] as Number).toLong())
        runCurrent()
        val names = conn.answer(
            Opcode.CONTACT_INFO,
            mapOf("contacts" to listOf(21L, 22L, 23L).map { mapOf("id" to it, "names" to listOf(mapOf("firstName" to "U$it"))) }),
        )!!
        assertEquals(setOf(21L, 22L, 23L), (names["contactIds"] as List<*>).map { (it as Number).toLong() }.toSet())
        assertEquals(
            listOf(MessageReader(23, null, "👍"), MessageReader(22, 1_500, null), MessageReader(20, 1_000, null), MessageReader(21, 1_000, null)),
            load.await(),
        )
        // the fresh chat info is in the store
        assertEquals(1_500L, c.store.state.value.chats.getValue(100).participants[22L])
        assertEquals("U22", c.store.state.value.users.getValue(22).displayName)

        // failed reactions: readers only, users already known
        val second = async { c.loadMessageReaders(100, 5) }
        runCurrent()
        conn.answer(Opcode.CHAT_INFO, mapOf("chats" to listOf(group(mapOf("9" to 1_000L, "20" to 1_000L, "21" to 500L, "22" to 1_500L, "23" to 400L)))))
        runCurrent()
        conn.fail(Opcode.MSG_GET_DETAILED_REACTIONS)
        runCurrent()
        assertEquals(listOf(MessageReader(22, 1_500, null), MessageReader(21, 1_000, null)), second.await())

        // the group grew past max-readmarks: nothing but CHAT_INFO
        val third = async { c.loadMessageReaders(100, 5) }
        runCurrent()
        conn.answer(Opcode.CHAT_INFO, mapOf("chats" to listOf(group(mapOf("9" to 1_000L), count = 6))))
        runCurrent()
        assertTrue(third.isCompleted)
        assertEquals(emptyList(), third.await())
        assertFalse(c.isMessageReadersAvailable(100))
    }
}
