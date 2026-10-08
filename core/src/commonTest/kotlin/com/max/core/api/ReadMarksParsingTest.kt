package com.max.core.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Read marks in chat objects (`participants`), `CHAT_MEMBERS` entries and `max-readmarks`. */
class ReadMarksParsingTest {
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

    private fun chat(
        type: String = "CHAT",
        count: Int? = null,
        participants: Map<Any, Any?> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ): Chat = Chat.from(chatMap(type = type, count = count, participants = participants, extra = extra))!!

    // ---- parsing ----------------------------------------------------------------------------------

    @Test
    fun participantsAreParsedAsReadMarks() {
        val c = chat(participants = mapOf(5L to 1_000L, "6" to 2_000, 7 to "3000", "x" to 4_000L, 8L to null, 9L to "soon"))
        assertEquals(mapOf(5L to 1_000L, 6L to 2_000L, 7L to 3_000L), c.participants)
        assertEquals(emptyMap(), Chat.from(mapOf("id" to 1, "type" to "CHAT"))!!.participants)
        assertEquals(emptyMap(), Chat.from(mapOf("id" to 1, "type" to "CHAT", "participants" to listOf(1, 2)))!!.participants)
        // copies keep the marks
        assertEquals(c.participants, c.copy(newMessages = 3).participants)
    }

    @Test
    fun aUserListedTwiceKeepsTheLargerMark() {
        // the same id as a number and as a string, in both orders
        assertEquals(mapOf(5L to 2_000L), chat(participants = linkedMapOf(5L to 2_000L, "5" to 1_000L)).participants)
        assertEquals(mapOf(5L to 2_000L), chat(participants = linkedMapOf("5" to 1_000L, 5 to 2_000L)).participants)
        assertEquals(mapOf(5L to 3_000L, 6L to 1L), Chat.readMarks(linkedMapOf("6" to 1L, 5L to "3000", "5" to 2_999L)))
        // an invalid duplicate does not drop the valid mark
        assertEquals(mapOf(5L to 1_000L), Chat.readMarks(linkedMapOf(5L to 1_000L, "5" to "soon")))
    }

    @Test
    fun chatMembersCarryTheirReadMark() = runTest {
        val sink = ScriptSink(
            mapOf(
                "members" to listOf(
                    mapOf("contact" to mapOf("id" to 5L), "readMark" to 1_700L),
                    mapOf("contact" to mapOf("id" to 6L), "readMark" to "1800"),
                    mapOf("contact" to mapOf("id" to 7L)),
                ),
                "marker" to 0,
            ),
        )
        val page = MaxApi(sink).chats.getChatMembers(100)
        assertEquals(listOf(1_700L, 1_800L, null), page.members.map { it.readMark })
        assertEquals(listOf(5L, 6L, 7L), page.members.map { it.userId })
    }

    @Test
    fun maxReadmarksComesFromTheServerConfig() {
        fun config(value: Any?) = AccountConfig.fromLoginReply(mapOf("config" to mapOf("server" to mapOf("max-readmarks" to value))))!!
        assertEquals(100, AccountConfig.DEFAULT_MAX_READMARKS)
        assertEquals(100, AccountConfig().maxReadmarks)
        assertEquals(250, config(250).maxReadmarks)
        assertEquals(30, config("30").maxReadmarks)
        assertEquals(100, config(null).maxReadmarks)
        assertEquals(100, config("many").maxReadmarks)
        assertEquals(100, config(0).maxReadmarks)
        assertEquals(100, config(-5).maxReadmarks)
    }
}
