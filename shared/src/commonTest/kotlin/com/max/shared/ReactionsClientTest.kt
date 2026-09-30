@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.api.ReactionCounter
import com.max.core.api.ReactionUser
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.ok
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

/** Setting, removing and reloading reactions through [MaxClient], and the reaction catalog. */
class ReactionsClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private val message = mapOf(
        "id" to 5L, "chatId" to 100L, "sender" to 20L, "time" to 10L, "type" to "USER", "text" to "hi",
        "reactionInfo" to mapOf("counters" to listOf(mapOf("reaction" to "👍", "count" to 1)), "totalCount" to 1),
    )

    private fun loginReply() = mapOf(
        "profile" to mapOf("contact" to mapOf("id" to 9, "names" to listOf(mapOf("firstName" to "Me")))),
        "chats" to listOf(mapOf("id" to 100, "type" to "CHAT", "status" to "ACTIVE", "owner" to 9, "lastEventTime" to 10, "lastMessage" to message)),
        "messages" to mapOf("100" to listOf(message)),
        "time" to 1700L,
    )

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
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

    private fun MaxClient.stored() = store.state.value.messagesOf(100).single { it.id == 5L }.reactionInfo

    @Test
    fun setAndRemoveUpdateTheStore() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val conn = factory.lastConnection!!
        assertEquals(listOf(ReactionCounter("👍", 1)), c.stored()!!.counters)

        val add = async { c.setReaction(100, 5, "🔥") }
        runCurrent()
        val sent = conn.answer(
            Opcode.MSG_REACTION,
            mapOf("reactionInfo" to mapOf("counters" to listOf(mapOf("reaction" to "👍", "count" to 1), mapOf("reaction" to "🔥", "count" to 1)), "totalCount" to 2, "yourReaction" to "🔥")),
        )!!
        assertEquals(mapOf("reactionType" to "EMOJI", "id" to "🔥"), sent["reaction"])
        assertEquals(5L, (sent["messageId"] as Number).toLong())
        assertFalse("postId" in sent)
        assertEquals("🔥", add.await()!!.yourReaction)
        assertEquals("🔥", c.stored()!!.yourReaction)
        assertEquals(2, c.store.state.value.chats.getValue(100).lastMessage!!.reactionInfo!!.totalCount)

        // a removal whose reply has no reactions drops only the own counter
        val remove = async { c.setReaction(100, 5, null) }
        runCurrent()
        val cancel = conn.answer(Opcode.MSG_CANCEL_REACTION, emptyMap<String, Any?>())!!
        assertEquals(setOf<Any?>("chatId", "messageId"), cancel.keys.toSet())
        assertNull(remove.await())
        assertEquals(listOf(ReactionCounter("👍", 1)), c.stored()!!.counters)
        assertNull(c.stored()!!.yourReaction)

        // comments carry the post and leave the chat messages alone
        val comment = async { c.setReaction(100, 77, "❤️", postId = 5) }
        runCurrent()
        val commentSent = conn.answer(Opcode.MSG_REACTION, mapOf("reactionInfo" to mapOf("counters" to listOf(mapOf("reaction" to "❤️", "count" to 1)), "totalCount" to 1, "yourReaction" to "❤️")))!!
        assertEquals(5L, (commentSent["postId"] as Number).toLong())
        assertEquals("❤️", comment.await()!!.yourReaction)
        assertEquals(listOf(ReactionCounter("👍", 1)), c.stored()!!.counters)
    }

    @Test
    fun reloadStoresWhatTheServerReturns() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val conn = factory.lastConnection!!
        val load = async { c.loadReactions(100, listOf(5, 5, 6)) }
        runCurrent()
        val sent = conn.answer(
            Opcode.MSG_GET_REACTIONS,
            mapOf("messagesReactions" to mapOf("5" to mapOf("counters" to emptyList<Any?>(), "totalCount" to 0), "6" to mapOf("counters" to listOf(mapOf("reaction" to "😍", "count" to 3)), "totalCount" to 3))),
        )!!
        assertEquals(listOf(5L, 6L), (sent["messageIds"] as List<*>).map { (it as Number).toLong() })
        val found = load.await()
        assertEquals(setOf(5L, 6L), found.keys)
        // message 5 has no reactions left; 6 is not in the store and is only returned
        assertNull(c.stored())
        assertEquals(emptyMap(), c.loadReactions(100, emptyList()))
    }

    @Test
    fun catalogIsAskedOnceAndUsersAreNamed() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val conn = factory.lastConnection!!
        val first = async { c.reactionCatalog() }
        runCurrent()
        conn.answer(Opcode.ASSETS_UPDATE, mapOf("animojiUpdates" to mapOf("1" to 1, "2" to 1)))
        runCurrent()
        conn.answer(Opcode.ASSETS_GET_BY_IDS, mapOf("animojis" to listOf(mapOf("id" to 1, "emoji" to "👍"), mapOf("id" to 2, "emoji" to "❤️"))))
        assertEquals(listOf("👍", "❤️"), first.await().map { it.emoji })
        val second = async { c.reactionCatalog() }
        runCurrent()
        assertTrue(second.isCompleted)
        assertEquals(listOf("👍", "❤️"), second.await().map { it.emoji })

        val users = async { c.loadReactionUsers(100, 5) }
        runCurrent()
        val sent = conn.answer(Opcode.MSG_GET_DETAILED_REACTIONS, mapOf("reactions" to listOf(mapOf("userId" to 9, "reaction" to "👍"), mapOf("userId" to 77, "reaction" to "❤️"))))!!
        assertEquals(100L, (sent["count"] as Number).toLong())
        runCurrent()
        val info = conn.answer(Opcode.CONTACT_INFO, mapOf("contacts" to listOf(mapOf("id" to 77, "names" to listOf(mapOf("firstName" to "Аня"))))))!!
        assertEquals(listOf(77L), (info["contactIds"] as List<*>).map { (it as Number).toLong() })
        assertEquals(listOf(ReactionUser(9, "👍"), ReactionUser(77, "❤️")), users.await())
        assertEquals("Аня", c.store.state.value.users.getValue(77).displayName)
    }
}
