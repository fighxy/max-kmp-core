package com.maxly.core.api

import com.maxly.core.auth.RequestSink
import com.maxly.core.events.EventParser
import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.CmdType
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.PROTOCOL_VERSION
import com.maxly.core.protocol.PacketHeader
import com.maxly.core.transport.TransportPacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ScheduledPollsTest {
    @Test
    fun editAndCancelScheduledMessages() = runTest {
        val message = mapOf(
            "id" to 9L, "time" to 3L, "type" to "USER", "text" to "later", "chatId" to 7L,
            "delayedAttributes" to mapOf("timeToFire" to 50L, "notifySender" to false),
        )
        val sink = FakeSink(mapOf("message" to message))
        val edited = MessagesApi(sink).editScheduledMessage(7L, 9L, "later", 50L, notifySender = false)
        val payload = sink.sent.single().second as Map<*, *>
        assertEquals(Opcode.MSG_EDIT, sink.sent.single().first)
        assertEquals(setOf("chatId", "messageId", "text", "delayedAttributes"), payload.keys)
        assertEquals(mapOf("timeToFire" to 50L, "notifySender" to false), payload["delayedAttributes"])
        assertEquals(50L, edited.fireAt)
        assertEquals(false, edited.notifySenderOnFire)

        val cancel = FakeSink(mapOf("messageIds" to listOf(9L)))
        val result = MessagesApi(cancel).cancelScheduledMessages(7L, listOf(9L))
        val body = cancel.sent.single().second as Map<*, *>
        assertEquals(Opcode.MSG_DELETE, cancel.sent.single().first)
        assertEquals("DELAYED", body["itemType"])
        assertEquals(false, body["forMe"])
        assertEquals(listOf(9L), result.deleted)

        val listed = FakeSink(mapOf("messages" to listOf(message)))
        assertEquals(listOf(9L), MessagesApi(listed).scheduledMessages(7L).messages.map { it.id })
        assertEquals("DELAYED", (listed.sent.single().second as Map<*, *>)["itemType"])
    }

    @Test
    fun delayedPushTypes() {
        val event = EventParser.parse(
            Opcode.NOTIF_MSG_DELAYED.value,
            0,
            mapOf(
                "chatId" to 7L,
                "userId" to 0,
                "updateTypeId" to 3,
                "messageIds" to listOf(9L, 10L),
                "lastDelayedUpdateTime" to 80L,
            ),
        )
        val delayed = assertIs<MaxEvent.DelayedUpdated>(event)
        assertEquals(DelayedUpdate.FIRE_SUCCESS, delayed.updateType)
        assertNull(delayed.userId)
        assertEquals(listOf(9L, 10L), delayed.messageIds)
        assertEquals(80L, delayed.lastDelayedUpdateTime)

        val edited = EventParser.parse(
            Opcode.NOTIF_MSG_DELAYED.value,
            0,
            mapOf("chatId" to 7L, "updateTypeId" to 1, "message" to mapOf("id" to 4L, "time" to 1L, "type" to "USER")),
        )
        assertEquals(DelayedUpdate.EDITED, assertIs<MaxEvent.DelayedUpdated>(edited).updateType)
        assertNull(assertIs<MaxEvent.DelayedUpdated>(EventParser.parse(Opcode.NOTIF_MSG_DELAYED.value, 0, mapOf("chatId" to 7L, "updateTypeId" to 9))).updateType)
        assertIs<MaxEvent.Unknown>(EventParser.parse(Opcode.NOTIF_MSG_DELAYED.value, 0, emptyMap<String, Any?>()))
    }

    @Test
    fun pollRefreshAndVote() = runTest {
        val sink = FakeSink(
            mapOf(
                "polls" to listOf(
                    mapOf(
                        "pollId" to 5L,
                        "title" to "Pick",
                        "version" to 2,
                        "settings" to 1,
                        "answers" to listOf(mapOf("text" to "A", "answerId" to 1L), mapOf("text" to "B", "answerId" to 2L)),
                        "state" to mapOf("total" to 3, "result" to listOf(mapOf("answerId" to 1L, "voteCount" to 3))),
                    ),
                ),
            ),
        )
        val polls = MessagesApi(sink).pollUpdates(7L, listOf(PollRef(9L, 5L)))
        val payload = sink.sent.single().second as Map<*, *>
        assertEquals(Opcode.GET_POLL_UPDATES, sink.sent.single().first)
        assertEquals(listOf(mapOf("messageId" to 9L, "pollId" to 5L)), payload["polls"])
        val poll = polls.single()
        assertEquals("Pick", poll.title)
        assertEquals(setOf(PollFlag.ANONYMOUS), poll.flags)
        assertEquals(3, poll.state?.total)
        assertFailsWith<IllegalArgumentException> { MessagesApi(FakeSink()).pollUpdates(7L, emptyList()) }

        val vote = FakeSink(mapOf("state" to mapOf("total" to 1, "result" to listOf(mapOf("answerId" to 1, "voteCount" to 1)))))
        val state = MessagesApi(vote).votePoll(7L, 9L, 5L, listOf(1L))
        assertEquals(Opcode.SEND_VOTE, vote.sent.single().first)
        assertEquals(1, state.results.single().voteCount)
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
