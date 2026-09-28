@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.api

import com.max.core.auth.AuthApi
import com.max.core.auth.RequestSink
import com.max.core.protocol.CmdType
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.protocol.decodePayloadPacket
import com.max.core.session.DeviceInfo
import com.max.core.session.SessionConfig
import com.max.core.session.SessionMachine
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportConfig
import com.max.core.transport.TransportPacket
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Expected request bytes were produced by PyMax's own payload models
 * (`pymax.api.messages.payloads`, `pymax.api.chats.payloads`, `.to_payload()`) serialized with
 * msgpack-python, with the fixed inputs used below.
 */
class MaxApiTest {

    private val now = 1759100000000L
    private val clock = { now }

    private val pymax = mapOf(
        "send" to "83a663686174496464a76d65737361676584a474657874a568656c6c6fa3636964cf000001999287d701a8656c656d656e747390a8617474616368657390a66e6f74696679c3",
        "sendReply" to "83a6636861744964d3ffffc2278427bfffa76d65737361676585a474657874a27265a3636964cf000001999287d702a8656c656d656e747390a8617474616368657390a46c696e6b82a474797065a55245504c59a96d6573736167654964cf019ebdac6da10f98a66e6f74696679c2",
        "forward" to "83a6636861744964ccc8a76d65737361676583a3636964d3fffffe666d7828ffa46c696e6b83a474797065a7464f5257415244a96d6573736167654964b2313136373432383837343530323336303833a663686174496464a8617474616368657390a66e6f74696679c3",
        "getMessages" to "82a6636861744964ce0e3fdfbeaa6d65737361676549647392cf019ebdac6da10f98cf019ebdac6da10f99",
        "edit" to "85a6636861744964ce0e3fdfbea96d6573736167654964cf019ebdac6da10f98a474657874a6656469746564a8656c656d656e747390ab6174746163686d656e747390",
        "delete" to "83a663686174496464aa6d657373616765496473920102a5666f724d65c3",
        "history" to "8aa663686174496464a7666f727761726400a86261636b7761726428ac6261636b7761726454696d6500ab666f727761726454696d6500a767657443686174c2a466726f6dcf000001999287d700a86974656d54797065a7524547554c4152ab6765744d65737361676573c3ab696e746572616374697665c2",
        "read" to "84a474797065ac524541445f4d455353414745a663686174496464a96d6573736167654964cf019ebdac6da10f98a46d61726bcf000001999287d700",
        "pin" to "83a663686174496464a96e6f7469667950696ec2ac70696e4d657373616765496402",
        "addReaction" to "83a663686174496464a96d65737361676549640aa87265616374696f6e82ac7265616374696f6e54797065a5454d4f4a49a26964a4f09f918d",
        "removeReaction" to "82a663686174496464a96d65737361676549640a",
        "getReactions" to "82a663686174496464aa6d657373616765496473920a0b",
        "chatInfo" to "81a7636861744964739264d3ffffc2278427bfff",
        "chatsList" to "81a66d61726b6572cf000001999287d700",
        "members" to "84a474797065a64d454d424552a663686174496464a66d61726b657200a5636f756e7432",
        "leave" to "81a663686174496464",
        "deleteChat" to "83a663686174496464ad6c6173744576656e7454696d65cf000001999287d700a6666f72416c6cc3",
    )

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun bytes(payload: Any?) = DefaultMessagePackCodec.encode(payload).hex()

    /** Records requests; answers with scripted payloads, or throws a scripted exception. */
    private class FakeSink(vararg replies: Any?) : RequestSink {
        val script = ArrayDeque(replies.toList())
        val sent = ArrayList<Pair<Opcode, Any?>>()
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
            sent += opcode to payload
            val next = if (script.isEmpty()) emptyMap<String, Any?>() else script.removeFirst()
            if (next is Throwable) throw next
            return TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false), next)
        }
    }

    private fun serverError(opcode: Opcode, error: String, message: String) = ServerErrorException.from(
        TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.ERROR.value, 1, opcode.value.toShort(), 0, false), mapOf("error" to error, "message" to message)),
    )

    private fun api(sink: FakeSink) = MaxApi(sink, clock)

    private fun msg(id: Long, chatId: Long?, text: String) =
        buildMap<String, Any?> { put("id", id); if (chatId != null) put("chatId", chatId); put("time", 123456); put("type", "USER"); put("text", text) }

    // --- messages ---

    @Test
    fun sendMessagePayloadAndEnvelopeReply() = runTest {
        val sink = FakeSink(
            mapOf("chatId" to 100, "message" to mapOf("id" to 55, "time" to now, "type" to "USER", "text" to "hello", "cid" to now + 1, "sender" to 7), "unread" to 0, "mark" to now),
            msg(56, null, "re"),
        )
        val a = api(sink)
        val sent = a.messages.sendMessage(100, "hello")
        assertEquals(Opcode.MSG_SEND, sink.sent[0].first)
        assertEquals(64, Opcode.MSG_SEND.value)
        assertEquals(pymax["send"], bytes(sink.sent[0].second))
        assertEquals(55L, sent.id)
        assertEquals(100L, sent.chatId)
        assertEquals(7L, sent.sender)
        assertEquals(now + 1, sent.cid)
        assertEquals(0, sent.unread)
        assertEquals(now, sent.mark)
        assertEquals("hello", sent.text)

        // reply to a message, notify off, negative chat id; flat reply without chatId -> requested id
        val reply = a.messages.sendMessage(-68000000000001, "re", replyTo = 116739188629507992, notify = false)
        assertEquals(pymax["sendReply"], bytes(sink.sent[1].second))
        assertEquals(-68000000000001, reply.chatId)
        assertEquals(56L, reply.id)
    }

    @Test
    fun clientIdsAreMonotonicLikePyMax() {
        var t = 10_000L
        val gen = ClientIdGenerator { t }
        assertEquals(10_001, gen.next())
        assertEquals(10_002, gen.next())
        t = 20_000
        assertEquals(20_000, gen.next())
        assertEquals(20_001, gen.next())
    }

    @Test
    fun forwardMessagePayload() = runTest {
        val sink = FakeSink(msg(57, 200, "forwarded"))
        val m = api(sink).messages.forwardMessage(200, 116742887450236083, sourceChatId = 100)
        assertEquals(pymax["forward"], bytes(sink.sent[0].second))
        assertEquals(57L, m.id)
        // default source = target chat
        val sink2 = FakeSink(msg(1, 200, "f"))
        api(sink2).messages.forwardMessage(200, 55)
        assertEquals(200L, ((((sink2.sent[0].second as Map<*, *>)["message"] as Map<*, *>)["link"]) as Map<*, *>)["chatId"])
    }

    @Test
    fun getEditDeleteMessages() = runTest {
        val sink = FakeSink(
            mapOf("messages" to listOf(msg(116739188629507992, null, "one"), msg(116739188629507993, null, "two"))),
            mapOf("message" to msg(116739188629507992, null, "edited") + ("status" to "EDITED")),
            emptyMap<String, Any?>(),
            mapOf("other" to 1),
        )
        val a = api(sink)
        val got = a.messages.getMessages(239067070, listOf(116739188629507992, 116739188629507993))
        assertEquals(Opcode.MSG_GET, sink.sent[0].first)
        assertEquals(pymax["getMessages"], bytes(sink.sent[0].second))
        assertEquals(listOf(116739188629507992, 116739188629507993), got.map { it.id })
        assertTrue(got.all { it.chatId == 239067070L })

        val edited = a.messages.editMessage(239067070, 116739188629507992, "edited")
        assertEquals(Opcode.MSG_EDIT, sink.sent[1].first)
        assertEquals(pymax["edit"], bytes(sink.sent[1].second))
        assertEquals("EDITED", edited.status)
        assertEquals(239067070L, edited.chatId)

        assertEquals(emptyMap<Any?, Any?>(), a.messages.deleteMessages(100, listOf(1, 2), forMe = true))
        assertEquals(Opcode.MSG_DELETE, sink.sent[2].first)
        assertEquals(pymax["delete"], bytes(sink.sent[2].second))
        // missing `messages` -> empty (PyMax `or []`)
        assertEquals(emptyList(), a.messages.getMessages(1, listOf(1)))
    }

    @Test
    fun historyPayloadAndReply() = runTest {
        val sink = FakeSink(
            mapOf("messages" to listOf(msg(1, 100, "one"), msg(2, null, "two"))),
            mapOf("messages" to emptyList<Any?>(), "chat" to mapOf("id" to 100, "type" to "DIALOG", "status" to "ACTIVE", "owner" to 5)),
        )
        val a = api(sink)
        val h = a.messages.getChatHistory(100)
        assertEquals(Opcode.CHAT_HISTORY, sink.sent[0].first)
        assertEquals(49, Opcode.CHAT_HISTORY.value)
        assertEquals(pymax["history"], bytes(sink.sent[0].second))
        assertEquals(listOf(1L, 2L), h.messages.map { it.id })
        assertEquals(listOf("one", "two"), h.messages.map { it.text })
        assertTrue(h.messages.all { it.chatId == 100L })
        assertNull(h.chat)

        val h2 = a.messages.getChatHistory(100, from = 123, backward = 2, getChat = true, interactive = true, itemType = HistoryItemType.DELAYED)
        val p = sink.sent[1].second as Map<*, *>
        assertEquals(123L, p["from"])
        assertEquals(2, p["backward"])
        assertEquals("DELAYED", p["itemType"])
        assertEquals(true, p["getChat"])
        assertEquals(true, p["interactive"])
        assertEquals(Chat(100, "DIALOG", "ACTIVE", 5, null, 0, 0, 0, null, h2.chat!!.raw), h2.chat)
    }

    @Test
    fun markReadPinAndReactions() = runTest {
        val info = mapOf("totalCount" to 1, "counters" to listOf(mapOf("count" to 1, "reaction" to "👍")), "yourReaction" to "👍")
        val sink = FakeSink(
            mapOf("unread" to 0, "mark" to now),
            emptyMap<String, Any?>(),
            mapOf("reactionInfo" to info),
            emptyMap<String, Any?>(),
            mapOf("messagesReactions" to mapOf("10" to info, "11" to mapOf("totalCount" to 0, "counters" to emptyList<Any?>()))),
            emptyMap<String, Any?>(),
        )
        val a = api(sink)
        assertEquals(ReadState(0, now, mapOf("unread" to 0, "mark" to now)), a.messages.markRead(100, 116739188629507992))
        assertEquals(Opcode.CHAT_MARK, sink.sent[0].first)
        assertEquals(pymax["read"], bytes(sink.sent[0].second))

        a.messages.pinMessage(100, 2, notifyPin = false)
        assertEquals(Opcode.CHAT_UPDATE, sink.sent[1].first)
        assertEquals(pymax["pin"], bytes(sink.sent[1].second))

        val added = a.messages.addReaction(100, 10, "👍")!!
        assertEquals(Opcode.MSG_REACTION, sink.sent[2].first)
        assertEquals(pymax["addReaction"], bytes(sink.sent[2].second))
        assertEquals(1, added.totalCount)
        assertEquals(listOf(ReactionCounter("👍", 1)), added.counters)
        assertEquals("👍", added.yourReaction)

        assertNull(a.messages.removeReaction(100, 10))
        assertEquals(Opcode.MSG_CANCEL_REACTION, sink.sent[3].first)
        assertEquals(pymax["removeReaction"], bytes(sink.sent[3].second))

        val all = a.messages.getReactions(100, listOf(10, 11))!!
        assertEquals(Opcode.MSG_GET_REACTIONS, sink.sent[4].first)
        assertEquals(pymax["getReactions"], bytes(sink.sent[4].second))
        assertEquals(setOf("10", "11"), all.keys)
        assertEquals(0, all["11"]!!.totalCount)
        assertNull(a.messages.getReactions(100, listOf(1)))
    }

    @Test
    fun messageErrorsAndMalformedReplies() = runTest {
        val a = api(FakeSink())
        assertFailsWith<IllegalArgumentException> { a.messages.sendMessage(1, "") }
        assertFailsWith<IllegalArgumentException> { a.messages.editMessage(1, 2, "") }
        assertFailsWith<IllegalArgumentException> { a.messages.deleteMessages(1, emptyList()) }

        val denied = serverError(Opcode.MSG_SEND, "chat.denied", "Chat is closed")
        assertSame(denied, assertFailsWith<ServerErrorException> { api(FakeSink(denied)).messages.sendMessage(1, "x") })

        // MSG_SEND without payload / without a message
        assertIs<MalformedReplyException>(runCatching { api(FakeSink(null)).messages.sendMessage(1, "x") }.exceptionOrNull())
        val e = assertFailsWith<MalformedReplyException> { api(FakeSink(mapOf("chatId" to 1))).messages.sendMessage(1, "x") }
        assertEquals(Opcode.MSG_SEND, e.opcode)
        assertFailsWith<MalformedReplyException> { api(FakeSink(mapOf("x" to 1))).messages.editMessage(1, 2, "x") }
        assertFailsWith<MalformedReplyException> { api(FakeSink(mapOf("messages" to "nope"))).messages.getChatHistory(1) }
        assertFailsWith<MalformedReplyException> { api(FakeSink(mapOf("messages" to listOf(mapOf("id" to 1))))).messages.getChatHistory(1) }
        assertFailsWith<MalformedReplyException> { api(FakeSink(mapOf("unread" to 1))).messages.markRead(1, 2) }
        assertFailsWith<MalformedReplyException> { api(FakeSink("text")).messages.getMessages(1, listOf(1)) }
    }

    @Test
    fun messageParsingUnwrapsEnvelopes() {
        val m = MaxMessage.from(
            mapOf("chatId" to 9, "prevMessageId" to "8", "message" to mapOf("id" to "116641336752745888", "time" to 1, "type" to "CHANNEL", "attaches" to listOf(mapOf("_type" to "PHOTO")))),
        )!!
        assertEquals(116641336752745888, m.id)
        assertEquals(9L, m.chatId)
        assertEquals(8L, m.prevMessageId)
        assertEquals("", m.text)
        assertEquals(1, m.attaches.size)
        assertNull(MaxMessage.from(mapOf("id" to 1, "time" to 1)))
        assertNull(MaxMessage.from("x"))
    }

    // --- chats ---

    @Test
    fun chatRequests() = runTest {
        val chat = mapOf("id" to 100, "type" to "CHAT", "status" to "ACTIVE", "owner" to 5, "title" to "Team", "participantsCount" to 3,
            "lastMessage" to msg(9, null, "last"), "lastEventTime" to now, "newMessages" to 2)
        val sink = FakeSink(
            mapOf("chats" to listOf(chat, mapOf("id" to -68000000000001, "type" to "CHANNEL"))),
            mapOf("chats" to listOf(chat)),
            mapOf("members" to listOf(mapOf("contact" to mapOf("id" to 77, "names" to emptyList<Any?>()), "presence" to mapOf("seen" to 1))), "marker" to 123),
            emptyMap<String, Any?>(),
            emptyMap<String, Any?>(),
            mapOf("members" to emptyList<Any?>()),
        )
        val a = api(sink)
        val chats = a.chats.getChats(listOf(100, -68000000000001))
        assertEquals(Opcode.CHAT_INFO, sink.sent[0].first)
        assertEquals(pymax["chatInfo"], bytes(sink.sent[0].second))
        val c = chats[0]
        assertEquals(listOf(100L, -68000000000001L), chats.map { it.id })
        assertEquals("Team", c.title)
        assertEquals(3, c.participantsCount)
        assertEquals(2, c.newMessages)
        assertEquals(now, c.lastEventTime)
        assertEquals(100L, c.lastMessage!!.chatId)
        assertNull(chats[1].owner)

        assertEquals(listOf(100L), a.chats.fetchChats().map { it.id })
        assertEquals(Opcode.CHATS_LIST, sink.sent[1].first)
        assertEquals(pymax["chatsList"], bytes(sink.sent[1].second))

        val page = a.chats.getChatMembers(100)
        assertEquals(Opcode.CHAT_MEMBERS, sink.sent[2].first)
        assertEquals(pymax["members"], bytes(sink.sent[2].second))
        assertEquals(listOf(77L), page.members.map { it.userId })
        assertEquals(mapOf("seen" to 1), page.members[0].presence)
        assertEquals(123L, page.marker)

        a.chats.leaveChat(100)
        assertEquals(Opcode.CHAT_LEAVE, sink.sent[3].first)
        assertEquals(pymax["leave"], bytes(sink.sent[3].second))
        a.chats.deleteChat(100)
        assertEquals(Opcode.CHAT_DELETE, sink.sent[4].first)
        assertEquals(pymax["deleteChat"], bytes(sink.sent[4].second))
        assertEquals(0L, a.chats.getChatMembers(100, marker = 123, count = 10).marker)
        assertEquals(mapOf("type" to "MEMBER", "chatId" to 100L, "marker" to 123L, "count" to 10), sink.sent[5].second)
    }

    @Test
    fun chatErrors() = runTest {
        assertFailsWith<IllegalArgumentException> { api(FakeSink()).chats.getChats(emptyList()) }
        assertFailsWith<ApiException> { api(FakeSink(mapOf("chats" to emptyList<Any?>()))).chats.getChat(1) }
        assertEquals(1L, api(FakeSink(mapOf("chats" to listOf(mapOf("id" to 1, "type" to "DIALOG"))))).chats.getChat(1).id)
        assertFailsWith<MalformedReplyException> { api(FakeSink(mapOf("chats" to listOf(mapOf("title" to "x"))))).chats.fetchChats() }
        assertFailsWith<MalformedReplyException> { api(FakeSink(mapOf("members" to listOf(mapOf("presence" to 1))))).chats.getChatMembers(1) }
        val err = serverError(Opcode.CHAT_MEMBERS, "chat.not.found", "Chat not found")
        assertSame(err, assertFailsWith<ServerErrorException> { api(FakeSink(err)).chats.getChatMembers(1) })
    }

    // --- over SessionMachine + TokenLogin ---

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Any? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue)
        feed(ok(header.seq, opcode.value, reply))
        return payload
    }

    @Test
    fun sendAndFetchHistoryOverLoggedInSession() = runTest {
        val device = DeviceInfo(deviceId = "d1e9c0de00000001", instanceId = "a1b2c3d4e5f60718", clientSessionId = 17)
        val factory = ScriptedConnectionFactory()
        val login = AuthApi.tokenLoginHook("stored-token", device)
        val config = SessionConfig(TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false), device)
        val m = SessionMachine(config, factory, scope = backgroundScope, afterHandshake = login.hook)
        val connecting = async { m.connect() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1234567890123L))
        runCurrent()
        val loginPayload = conn.answer(Opcode.LOGIN, mapOf("profile" to mapOf("contact" to mapOf("id" to 5)))) as Map<*, *>
        connecting.await()
        assertEquals("ANDROID", (loginPayload["userAgent"] as Map<*, *>)["deviceType"])
        assertEquals(96, (loginPayload["chatCacheFingerprint"] as ByteArray).size)

        val api = MaxApi(m, clock)
        val sending = async { api.messages.sendMessage(100, "hello") }
        runCurrent()
        val sendPayload = conn.answer(Opcode.MSG_SEND, mapOf("chatId" to 100, "message" to mapOf("id" to 55, "time" to now, "type" to "USER", "text" to "hello", "cid" to now + 1)))
        assertEquals(pymax["send"], bytes(sendPayload))
        assertEquals(55L, sending.await().id)

        val history = async { api.messages.getChatHistory(100) }
        runCurrent()
        val historyPayload = conn.answer(Opcode.CHAT_HISTORY, mapOf("messages" to listOf(msg(54, 100, "earlier"), msg(55, 100, "hello"))))
        assertEquals(pymax["history"], bytes(historyPayload))
        assertEquals(listOf(54L, 55L), history.await().messages.map { it.id })

        // a server error on the session surfaces as ServerErrorException
        val failing = async { runCatching { api.messages.getMessages(100, listOf(1)) } }
        runCurrent()
        val (h, _) = decodePayloadPacket(conn.takeWritten()!!)
        conn.feed(errorReply(h.seq, Opcode.MSG_GET.value, mapOf("error" to "not.found", "message" to "Not found")))
        assertEquals("not.found", (failing.await().exceptionOrNull() as ServerErrorException).errorKey)
        m.disconnect()
    }
}
