@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.events

import com.max.core.api.MaxApi
import com.max.core.api.ReactionCounter
import com.max.core.auth.AuthApi
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.session.DeviceInfo
import com.max.core.session.SessionConfig
import com.max.core.session.SessionMachine
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.TransportPacket
import com.max.core.transport.ok
import com.max.core.transport.push
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * The push packets below are complete frames produced by PyMax's own encoder
 * (`TcpProtocol.encode(OutboundFrame(ver=10, cmd=0, seq=0, opcode, payload))`) from the payloads
 * of PyMax's dispatcher tests (`tests/dispatch/test_dispatcher.py`); the comment is the event type
 * PyMax's `EventResolver` gives each frame.
 */
class MaxEventsTest {

    private val frames = mapOf(
        "newMessage" to "0a00000000800000003e82a663686174496464a76d65737361676585a2696401a663686174496464a474696d65ce0001e240a474797065a455534552a474657874a62f7374617274", // PyMax: message_new
        "edited" to "0a00000000800000008f82a6636861744964ce0e3fdfbea76d6573736167658aa26964cf019ebdac6da10f98a6636861744964c0a474696d65ce0001e240a474797065a455534552a474657874a6656469746564a6737461747573a6454449544544a673656e646572ce0f330071aa75706461746554696d65cf0000019ebdac7cb0a3636964d3fffffe6142539275a8617474616368657390", // PyMax: message_edit
        "editPush67" to "0a00000000430000005882a6636861744964ce0e3fdfbea76d65737361676586a26964cf019ebdac6da10f98a6636861744964c0a474696d65ce0001e240a474797065a455534552a474657874a6656469746564a6737461747573a6454449544544", // PyMax: message_edit
        "removed" to "0a00000000800000007c84a663686174496400a76d65737361676586a26964b2313136373338373632383837373534323837a474696d65cf0000019ebd494d71a474797065a455534552a6737461747573a752454d4f564544a474657874a764656c65746564a8617474616368657390a6756e7265616400a46d61726bcf0000019ebd494d71", // PyMax: message_delete
        "deleted" to "0a000000008e0000004582a46368617485a2696405a474797065a443484154a6737461747573a6414354495645a56f776e657201a57469746c65a6436861742035aa6d657373616765496473920102", // PyMax: message_delete
        "chat" to "0a00000000870000007181a46368617486a2696405a474797065a443484154a6737461747573a6414354495645a56f776e657201a57469746c65a6436861742035ad70696e6e65644d65737361676585a2696409a663686174496405a474696d65ce0001e240a474797065a455534552a474657874a568656c6c6f", // PyMax: chat_update
        "typing" to "0a00000000810000001982a6636861744964ce0e3fdfbea6757365724964ce010cdfcf", // PyMax: typing
        "reactions" to "0a000000009b0000005684a96d6573736167654964b2313136373339313331313434373435323934a6636861744964ce0e3fdfbea8636f756e746572739182a5636f756e7401a87265616374696f6ea4f09f918daa746f74616c436f756e7401", // PyMax: reaction_update
        "mark" to "0a00000000820000003484ab7365744173556e72656164c2a6636861744964ce0e3fdfbea6757365724964ce010cdfcfa46d61726bcf0000019ec101143d", // PyMax: message_read
        "presence" to "0a00000000840000002982a870726573656e636582a47365656ece6a2d5023a673746174757301a6757365724964ce010cdfcf", // PyMax: presence
        "presencePartial" to "0a00000000840000002182a870726573656e636581a47365656ece6a2d65afa6757365724964ce010cdfcf", // PyMax: presence
        "attach" to "0a00000000880000000981a666696c65496463", // PyMax: file_ready
        "config" to "0a00000000860000001081a6636f6e66696781a468617368a178", // PyMax: None
    )

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun packet(name: String): TransportPacket = decodePayloadPacket(hex(frames.getValue(name))).let { (h, p) -> TransportPacket(h, p) }
    private fun event(name: String): MaxEvent = EventParser.parse(packet(name))

    @Test
    fun newMessage() {
        val e = assertIs<MaxEvent.NewMessage>(event("newMessage"))
        assertEquals(128, e.opcode)
        assertEquals(1L, e.message.id)
        assertEquals(100L, e.message.chatId)
        assertEquals("/start", e.message.text)
        assertEquals("USER", e.message.type)
    }

    @Test
    fun editedMessageFromNotifAndFromPushedMsgEdit() {
        val e = assertIs<MaxEvent.MessageEdited>(event("edited"))
        assertEquals(116739188629507992, e.message.id)
        assertEquals(239067070L, e.message.chatId)
        assertEquals(255000689L, e.message.sender)
        assertEquals(-1781298654603, e.message.cid)
        assertEquals("edited", e.message.text)
        val e67 = assertIs<MaxEvent.MessageEdited>(event("editPush67"))
        assertEquals(67, e67.opcode)
        assertEquals(239067070L, e67.message.chatId)
    }

    @Test
    fun deletions() {
        val removed = assertIs<MaxEvent.MessagesDeleted>(event("removed"))
        assertEquals(0L, removed.chatId)
        assertEquals(listOf(116738762887754287), removed.messageIds)
        assertEquals(116738762887754287, removed.message!!.id)
        assertNull(removed.chat)
        assertEquals(false, removed.ttl)

        val deleted = assertIs<MaxEvent.MessagesDeleted>(event("deleted"))
        assertEquals(142, deleted.opcode)
        assertEquals(5L, deleted.chatId)
        assertEquals(listOf(1L, 2L), deleted.messageIds)
        assertEquals("Chat 5", deleted.chat!!.title)
        assertNull(deleted.message)
    }

    @Test
    fun chatTypingReadPresenceReactions() {
        val chat = assertIs<MaxEvent.ChatUpdated>(event("chat"))
        assertEquals(5L, chat.chat.id)
        assertEquals(1L, chat.chat.owner)
        assertEquals(9L, (chat.chat.raw["pinnedMessage"] as Map<*, *>)["id"].let { (it as Number).toLong() })

        assertEquals(MaxEvent.Typing(239067070, 17620943, 129, packet("typing").payload), event("typing"))
        assertEquals(MaxEvent.MessageRead(239067070, 17620943, 1781354533949, false, 130, packet("mark").payload), event("mark"))
        assertEquals(MaxEvent.Presence(17620943, 1781354531, 1, 132, packet("presence").payload), event("presence"))
        assertEquals(MaxEvent.Presence(17620943, 1781360047, null, 132, packet("presencePartial").payload), event("presencePartial"))

        val r = assertIs<MaxEvent.ReactionsChanged>(event("reactions"))
        assertEquals("116739131144745294", r.messageId)
        assertEquals(239067070L, r.chatId)
        assertEquals(1, r.totalCount)
        assertEquals(listOf(ReactionCounter("👍", 1)), r.counters)
    }

    @Test
    fun typingType() {
        // the PyMax frame has no `type`: raw null, effective TEXT
        val bare = assertIs<MaxEvent.Typing>(event("typing"))
        assertNull(bare.type)
        assertEquals("TEXT", bare.effectiveType)
        val sticker = mapOf("chatId" to 7, "userId" to 8, "type" to "STICKER")
        assertEquals(MaxEvent.Typing(7, 8, 129, sticker, "STICKER"), EventParser.parse(129, 0, sticker))
        val file = assertIs<MaxEvent.Typing>(EventParser.parse(129, 0, mapOf("chatId" to 7L, "userId" to 8L, "type" to "FILE")))
        assertEquals("FILE", file.type)
        assertEquals("FILE", file.effectiveType)
        for (t in listOf("TEXT", "AUDIO", "VIDEO_MSG", "PHOTO", "VIDEO", "FILE", "STICKER")) {
            val e = assertIs<MaxEvent.Typing>(EventParser.parse(129, 0, mapOf("chatId" to 7, "userId" to 8, "type" to t)))
            assertEquals(t, e.type)
            assertEquals(t, e.effectiveType)
        }
        // unrecognised strings are kept raw but mean TEXT; a non-string `type` counts as absent
        val odd = assertIs<MaxEvent.Typing>(EventParser.parse(129, 0, mapOf("chatId" to 7, "userId" to 8, "type" to "SOMETHING_NEW")))
        assertEquals("SOMETHING_NEW", odd.type)
        assertEquals("TEXT", odd.effectiveType)
        assertEquals("TEXT", assertIs<MaxEvent.Typing>(EventParser.parse(129, 0, mapOf("chatId" to 7, "userId" to 8, "type" to ""))).effectiveType)
        assertNull(assertIs<MaxEvent.Typing>(EventParser.parse(129, 0, mapOf("chatId" to 7, "userId" to 8, "type" to 3))).type)
        assertNull(assertIs<MaxEvent.Typing>(EventParser.parse(129, 0, mapOf("chatId" to 7, "userId" to 8, "type" to null))).type)
        // four positional arguments still build an event without type
        assertNull(MaxEvent.Typing(7, 8, 129, null).type)
    }

    @Test
    fun unknownFallback() {
        // an upload signal is typed now; NOTIF_ATTACH without a known id and an opcode without a typed event stay raw
        assertEquals(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.FILE, 99, 136, mapOf("fileId" to 99)), event("attach"))
        assertIs<MaxEvent.Unknown>(EventParser.parse(136, 0, mapOf("photoId" to 1)))
        assertEquals(134, assertIs<MaxEvent.Unknown>(event("config")).opcode)
        // empty payload, missing required field, non-push cmd, unparseable message, not a map
        assertIs<MaxEvent.Unknown>(EventParser.parse(128, 0, emptyMap<String, Any?>()))
        assertIs<MaxEvent.Unknown>(EventParser.parse(128, 0, null))
        assertIs<MaxEvent.Unknown>(EventParser.parse(129, 0, mapOf("chatId" to 1)))
        assertIs<MaxEvent.Unknown>(EventParser.parse(130, 0, mapOf("chatId" to 1, "userId" to 2, "mark" to 3)))
        assertIs<MaxEvent.Unknown>(EventParser.parse(129, 1, mapOf("chatId" to 1, "userId" to 2)))
        assertIs<MaxEvent.Unknown>(EventParser.parse(128, 0, mapOf("chatId" to 1, "message" to mapOf("id" to 1))))
        assertIs<MaxEvent.Unknown>(EventParser.parse(142, 0, mapOf("chat" to mapOf("id" to 5), "messageIds" to listOf("x"))))
        assertIs<MaxEvent.Unknown>(EventParser.parse(135, 0, "text"))
        assertIs<MaxEvent.Unknown>(EventParser.parse(9999, 0, mapOf("a" to 1)))
    }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Any? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue)
        feed(ok(header.seq, opcode.value, reply))
        return payload
    }

    @Test
    fun pushesReachTheFlowWhileRequestsStillWork() = runTest {
        val device = DeviceInfo(deviceId = "d1e9c0de00000001", instanceId = "a1b2c3d4e5f60718", clientSessionId = 17)
        val factory = ScriptedConnectionFactory()
        val login = AuthApi.tokenLoginHook("stored-token", device)
        val config = SessionConfig(TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false), device)
        val m = SessionMachine(config, factory, scope = backgroundScope, afterHandshake = login.hook)
        val events = MaxEvents(m)
        val seen = ArrayList<MaxEvent>()
        val typing = ArrayList<MaxEvent.Typing>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.all.toList(seen) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.typing.toList(typing) }

        val connecting = async { m.connect() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1234567890123L))
        runCurrent()
        conn.answer(Opcode.LOGIN, mapOf("profile" to mapOf("contact" to mapOf("id" to 5))))
        connecting.await()

        // a request is in flight while pushes arrive; the reply still reaches the caller
        val api = MaxApi(m) { 1759100000000L }
        val sending = async { api.messages.sendMessage(100, "hi") }
        runCurrent()
        val (sendHeader, _) = decodePayloadPacket(conn.takeWritten()!!)
        conn.feed(hex(frames.getValue("newMessage")))
        conn.feed(hex(frames.getValue("typing")))
        conn.feed(ok(sendHeader.seq, Opcode.MSG_SEND.value, mapOf("chatId" to 100, "message" to mapOf("id" to 77, "time" to 1, "type" to "USER", "text" to "hi"))))
        conn.feed(push(Opcode.NOTIF_CONFIG.value, mapOf("config" to mapOf("hash" to "h"))))
        conn.feed(hex(frames.getValue("presence")))
        runCurrent()
        assertEquals(77L, sending.await().id)

        assertEquals(listOf(128, 129, 134, 132), seen.map { it.opcode })
        assertEquals(1L, assertIs<MaxEvent.NewMessage>(seen[0]).message.id)
        assertIs<MaxEvent.Unknown>(seen[2])
        assertEquals(listOf(17620943L), typing.map { it.userId })
        m.disconnect()
    }
}
