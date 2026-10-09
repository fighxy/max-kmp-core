package com.maxly.core.api

import com.maxly.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ReadersApi.loadMessageReaders]: which requests go out and what a failure leaves. */
class ReadersApiTest {
    private val me = 1L
    private val author = 2L

    private fun chatMap(
        id: Long = 100,
        type: String = "CHAT",
        count: Int? = null,
        participants: Map<Any, Any?> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to id, "type" to type, "status" to "ACTIVE", "participants" to participants)
        if (count != null) m["participantsCount"] = count
        m.putAll(extra)
        return m
    }

    private fun message(id: Long, time: Long, sender: Long = author) =
        mapOf("id" to id, "time" to time, "type" to "USER", "sender" to sender, "text" to "hi")

    // ---- loader -----------------------------------------------------------------------------------

    @Test
    fun loaderAsksChatInfoMessageAndReactions() = runTest {
        val sink = ScriptSink(
            mapOf("chats" to listOf(chatMap(count = 4, participants = mapOf(me to 5_000L, author to 5_000L, "5" to 1_000L, 6L to 900L), extra = mapOf("lastMessage" to message(99, 4_000))))),
            mapOf("messages" to listOf(message(42, 1_000))),
            mapOf("reactions" to listOf(mapOf("userId" to 7L, "reaction" to "👍"))),
        )
        val result = MaxApi(sink).readers.loadMessageReaders(100, 42, me, liveMarks = mapOf(6L to 1_200L))
        assertEquals(listOf(Opcode.CHAT_INFO, Opcode.MSG_GET, Opcode.MSG_GET_DETAILED_REACTIONS), sink.opcodes)
        assertEquals(msgpackHex(linkedMapOf("chatIds" to listOf(100L))), sink.hex(0))
        assertEquals(msgpackHex(linkedMapOf("chatId" to 100L, "messageIds" to listOf(42L))), sink.hex(1))
        assertEquals(msgpackHex(linkedMapOf("chatId" to 100L, "messageId" to 42L, "count" to 100)), sink.hex(2))
        assertTrue(result.available)
        assertEquals(42L, result.message!!.id)
        assertEquals(1_000L, result.chat.participants[5L])
        // 6 read the message after the CHAT_INFO snapshot: the push mark wins
        assertEquals(listOf(MessageReader(7, null, "👍"), MessageReader(6, 1_200, null), MessageReader(5, 1_000, null)), result.readers)
    }

    @Test
    fun knownMessageOrLastMessageSkipsMsgGetAndReactionFailureKeepsReaders() = runTest {
        val info = mapOf("chats" to listOf(chatMap(count = 2, participants = mapOf(5L to 2_000L, 6L to 3_000L), extra = mapOf("lastMessage" to message(99, 2_000)))))
        val known = MaxMessage.from(message(42, 1_500), 100)!!
        val sink = ScriptSink(info, serverError(Opcode.MSG_GET_DETAILED_REACTIONS, "not.found"), info, IllegalStateException("broken"))
        val api = MaxApi(sink).readers
        val first = api.loadMessageReaders(100, 42, me, message = known)
        assertEquals(listOf(MessageReader(6, 3_000, null), MessageReader(5, 2_000, null)), first.readers)
        // the chat's last message is enough too
        val last = api.loadMessageReaders(100, 99, me)
        assertEquals(listOf(MessageReader(6, 3_000, null), MessageReader(5, 2_000, null)), last.readers)
        assertEquals(listOf(Opcode.CHAT_INFO, Opcode.MSG_GET_DETAILED_REACTIONS, Opcode.CHAT_INFO, Opcode.MSG_GET_DETAILED_REACTIONS), sink.opcodes)
    }

    @Test
    fun unavailableChatsAskOnlyChatInfo() = runTest {
        val sink = ScriptSink(
            mapOf("chats" to listOf(chatMap(type = "DIALOG", participants = mapOf(me to 1L, 5L to 9_000L)))),
            mapOf("chats" to listOf(chatMap(count = 101))),
            mapOf("chats" to listOf(chatMap(count = 5))),
        )
        val api = MaxApi(sink).readers
        val dialog = api.loadMessageReaders(100, 42, me)
        assertFalse(dialog.available)
        assertEquals(emptyList(), dialog.readers)
        assertNull(dialog.message)
        assertEquals(emptyList(), api.loadMessageReaders(100, 42, me).readers)
        assertEquals(emptyList(), api.loadMessageReaders(100, 42, me, maxReadmarks = 4).readers)
        assertEquals(listOf(Opcode.CHAT_INFO, Opcode.CHAT_INFO, Opcode.CHAT_INFO), sink.opcodes)
    }

    @Test
    fun membersMissingFromParticipantsAreLoaded() = runTest {
        val sink = ScriptSink(
            mapOf("chats" to listOf(chatMap(count = 5, participants = mapOf(5L to 2_000L)))),
            mapOf("messages" to listOf(message(42, 1_000))),
            mapOf(
                "members" to listOf(
                    mapOf("contact" to mapOf("id" to 5L), "readMark" to 1_500L), // older than participants: ignored
                    mapOf("contact" to mapOf("id" to 6L), "readMark" to 3_000L),
                ),
                "marker" to 77L,
            ),
            mapOf(
                "members" to listOf(
                    mapOf("contact" to mapOf("id" to 7L), "readMark" to 500L),
                    mapOf("contact" to mapOf("id" to 8L)),
                ),
                "marker" to 0,
            ),
            mapOf("reactions" to emptyList<Any?>()),
        )
        val result = MaxApi(sink).readers.loadMessageReaders(100, 42, me)
        assertEquals(
            listOf(Opcode.CHAT_INFO, Opcode.MSG_GET, Opcode.CHAT_MEMBERS, Opcode.CHAT_MEMBERS, Opcode.MSG_GET_DETAILED_REACTIONS),
            sink.opcodes,
        )
        assertEquals(msgpackHex(linkedMapOf("type" to "MEMBER", "chatId" to 100L, "marker" to 0L, "count" to 50)), sink.hex(2))
        assertEquals(77L, ((sink.sent[3].second as Map<*, *>)["marker"] as Number).toLong())
        assertEquals(listOf(MessageReader(6, 3_000, null), MessageReader(5, 2_000, null)), result.readers)
    }

    @Test
    fun failingMembersPageKeepsTheKnownMarks() = runTest {
        val sink = ScriptSink(
            mapOf("chats" to listOf(chatMap(count = 9, participants = mapOf(5L to 2_000L)))),
            mapOf("messages" to listOf(message(42, 1_000))),
            serverError(Opcode.CHAT_MEMBERS, "proto.payload"),
            mapOf("reactions" to listOf(mapOf("userId" to 6L, "reaction" to "👍"))),
        )
        val result = MaxApi(sink).readers.loadMessageReaders(100, 42, me)
        assertEquals(listOf(MessageReader(6, null, "👍"), MessageReader(5, 2_000, null)), result.readers)
    }

    @Test
    fun missingMessageOrChatIsAnError() = runTest {
        val sink = ScriptSink(
            mapOf("chats" to listOf(chatMap(count = 3))),
            mapOf("messages" to emptyList<Any?>()),
            mapOf("chats" to emptyList<Any?>()),
        )
        val api = MaxApi(sink).readers
        assertFailsWith<ApiException> { api.loadMessageReaders(100, 42, me) }
        assertFailsWith<ApiException> { api.loadMessageReaders(100, 42, me) }
        assertEquals(listOf(Opcode.CHAT_INFO, Opcode.MSG_GET, Opcode.CHAT_INFO), sink.opcodes)
    }
}
