@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.shared

import com.maxly.core.api.TypingType
import com.maxly.core.media.HttpResponse
import com.maxly.core.media.MediaHttp
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.transport.FakeRawConnection
import com.maxly.core.transport.ScriptedConnectionFactory
import com.maxly.core.transport.TransportConfig
import com.maxly.core.transport.ok
import com.maxly.core.transport.push
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** [MaxClient.sendTyping] (fire-and-forget `MSG_TYPING` 65) and the typing `type` in the store. */
class TypingClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?) {
        val (header, _) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
    }

    private fun TestScope.client(factory: ScriptedConnectionFactory) =
        MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, backgroundScope)

    @Test
    fun typingGoesOutWithoutAReplyAndOfflineIsNotAnError() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = client(factory)
        // not connected yet: no exception, just false
        assertFalse(c.sendTyping(100, TypingType.STICKER))

        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(Opcode.LOGIN, mapOf("profile" to mapOf("contact" to mapOf("id" to 9)), "time" to 1700L))
        login.await()
        runCurrent()

        assertTrue(c.sendTyping(100, TypingType.FILE))
        assertTrue(c.sendTyping(100, "ANY_OTHER", postId = 5))
        val (h1, p1) = decodePayloadPacket(conn.takeWritten()!!)
        val (h2, p2) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(Opcode.MSG_TYPING.value, h1.opcodeValue)
        assertEquals(mapOf("chatId" to 100, "type" to "FILE"), p1)
        assertEquals(Opcode.MSG_TYPING.value, h2.opcodeValue)
        assertEquals(mapOf("chatId" to 100, "type" to "ANY_OTHER", "postId" to 5), p2)

        // the incoming type reaches the store next to the unchanged typingUsers
        conn.feed(push(Opcode.NOTIF_TYPING.value, mapOf("chatId" to 100, "userId" to 20, "type" to "STICKER")))
        conn.feed(push(Opcode.NOTIF_TYPING.value, mapOf("chatId" to 100, "userId" to 21)))
        runCurrent()
        val s = c.store.state.value
        val now = s.typing.getValue(100).getValue(20)
        assertEquals(setOf(20L, 21L), s.typingUsers(100, now))
        assertEquals(mapOf(20L to "STICKER", 21L to "TEXT"), s.typingUsersWithType(100, now))
        assertEquals("TEXT", s.typingType(100, 21, now))
        assertNull(s.typingType(100, 22, now))
        c.close()
    }
}
