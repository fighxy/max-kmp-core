package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.events.EventParser
import com.max.core.events.MaxEvent
import com.max.core.protocol.CmdType
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.transport.TransportPacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinsApiTest {
    @Test
    fun statesRequestAndReply() = runTest {
        val sink = FakeSink(
            mapOf(
                "pinnedMessagesStates" to listOf(
                    mapOf(
                        "chatId" to 7L,
                        "lastPinnedUpdateTime" to 100L,
                        "prevPinnedUpdateTime" to 90L,
                        "totalPinnedMessagesCount" to 2,
                        "changedPinnedMessageId" to 55L,
                        "changedPinnedMessageType" to 3,
                        "lastAction" to 0,
                        "lastPinnedMessageId" to 55L,
                    ),
                ),
            ),
        )
        val states = MessagesApi(sink).pinnedStates(listOf(7L, 8L))
        assertEquals(Opcode.GET_PINNED_MESSAGE_STATES, sink.sent.single().first)
        assertEquals(mapOf("chatIds" to listOf(7L, 8L)), sink.sent.single().second)
        val state = states.single()
        assertEquals(7L, state.chatId)
        assertEquals(2, state.totalPinnedCount)
        assertEquals(55L, state.changedMessageId)
        assertEquals(PinAction.PIN, state.lastAction)
        assertTrue(state.forAll)
        assertTrue(state.forMe)
        assertEquals(emptyList(), MessagesApi(FakeSink()).pinnedStates(emptyList()))
    }

    @Test
    fun pinnedMessagesAreOrdinaryMessages() = runTest {
        val sink = FakeSink(
            mapOf(
                "pinnedMessages" to listOf(
                    mapOf("id" to 9L, "time" to 3L, "type" to "USER", "text" to "hi", "chatId" to 7L),
                    mapOf("id" to "nope"),
                ),
            ),
        )
        val page = MessagesApi(sink).pinnedMessages(7L, from = 9L, backward = 20)
        val payload = sink.sent.single().second as Map<*, *>
        assertEquals(Opcode.PINNED_MESSAGES_GET, sink.sent.single().first)
        assertEquals(7L, payload["chatId"])
        assertEquals(9L, payload["from"])
        assertEquals(20, payload["backward"])
        assertEquals(listOf(9L), page.map { it.id })
        assertEquals("hi", page.single().text)
    }

    @Test
    fun updateOmitsOptionalFieldsTheWayTheAppDoes() = runTest {
        val state = mapOf(
            "chatId" to 7L,
            "lastAction" to 2,
            "lastPinnedMessageId" to 0,
            "changedMessageId" to 4L,
        )
        val sink = FakeSink(mapOf("pinnedMessagesState" to state))
        val updated = MessagesApi(sink).updatePinnedMessages(7L, PinAction.UNPIN_ALL)
        val payload = sink.sent.single().second as Map<*, *>
        assertEquals(Opcode.PINNED_MESSAGE_UPDATE, sink.sent.single().first)
        assertEquals(setOf("chatId", "action"), payload.keys)
        assertEquals(2, payload["action"])
        assertEquals(PinAction.UNPIN_ALL, updated.lastAction)
        assertNull(updated.lastPinnedMessageId)
        assertEquals(4L, updated.changedMessageId)

        val full = FakeSink(mapOf("pinnedMessagesState" to state))
        MessagesApi(full).updatePinnedMessages(7L, PinAction.PIN, listOf(4L, 5L), forMe = true, notify = false)
        val sent = full.sent.single().second as Map<*, *>
        assertEquals(listOf(4L, 5L), sent["messageIds"])
        assertEquals(true, sent["forMe"])
        assertEquals(false, sent["notify"])
        assertFailsWith<MalformedReplyException> {
            MessagesApi(FakeSink(emptyMap<String, Any?>())).updatePinnedMessages(7L, PinAction.PIN, listOf(1L))
        }
    }

    @Test
    fun pinPushAndThePushesTheCoreUsedToDrop() {
        val pins = EventParser.parse(
            Opcode.NOTIF_CHAT_MESSAGE_PINNED.value,
            0,
            mapOf(
                "chatId" to 7L,
                "pinnedMessagesState" to mapOf("chatId" to 7L, "lastAction" to 1, "totalPinnedMessagesCount" to 0),
            ),
        )
        val pin = assertIs<MaxEvent.PinsChanged>(pins)
        assertEquals(7L, pin.chatId)
        assertEquals(PinAction.UNPIN, pin.state.lastAction)
        assertEquals(0, pin.state.totalPinnedCount)

        val mine = EventParser.parse(
            Opcode.NOTIF_MSG_YOU_REACTED.value,
            0,
            mapOf(
                "chatId" to 7L,
                "messageId" to 9L,
                "postId" to 3L,
                "reactionInfo" to mapOf("totalCount" to 1, "counters" to listOf(mapOf("reaction" to "👍", "count" to 1)), "yourReaction" to "👍"),
            ),
        )
        val reacted = assertIs<MaxEvent.YouReacted>(mine)
        assertEquals("👍", reacted.reaction.yourReaction)
        assertEquals(3L, reacted.postId)

        val profile = EventParser.parse(
            Opcode.NOTIF_PROFILE.value,
            0,
            mapOf("profile" to mapOf("contact" to mapOf("id" to 11L, "names" to listOf(mapOf("name" to "Ann"))), "profileOptions" to listOf(1))),
        )
        assertEquals(11L, assertIs<MaxEvent.ProfileUpdated>(profile).profile.contact.id)

        val voice = EventParser.parse(
            Opcode.TRANSCRIPTION_RESULT.value,
            0,
            mapOf("messageId" to 9L, "chatId" to 7L, "transcription" to "hello", "transcriptionStatus" to 1),
        )
        val ready = assertIs<MaxEvent.TranscriptionReady>(voice)
        assertEquals("hello", ready.transcription.text)
        assertEquals(9L, ready.transcription.messageId)

        val failed = EventParser.parse(Opcode.NOTIF_ATTACH.value, 0, mapOf("error" to "upload.failed"))
        assertEquals("upload.failed", assertIs<MaxEvent.AttachmentFailed>(failed).error)
        val readyFile = EventParser.parse(Opcode.NOTIF_ATTACH.value, 0, mapOf("fileId" to 4L, "error" to "ignored"))
        assertIs<MaxEvent.AttachmentReady>(readyFile)
    }

    private class FakeSink(vararg replies: Any?) : RequestSink {
        val script = ArrayDeque(replies.toList())
        val sent = ArrayList<Pair<Opcode, Any?>>()
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
            sent += opcode to payload
            val next = if (script.isEmpty()) emptyMap<String, Any?>() else script.removeFirst()
            return TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false), next)
        }
    }
}
