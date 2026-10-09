package com.maxly.core.api

import com.maxly.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CommonChatsTest {
    @Test
    fun commonChatsSendUserIdsAndSkipEmptyIds() = runTest {
        val sink = ScriptSink(
            mapOf(
                "commonChats" to listOf(
                    mapOf(
                        "id" to 11L,
                        "type" to "CHAT",
                        "title" to "Дача",
                        "baseIconUrl" to "https://i/11",
                        "participantsCount" to 4,
                        "participants" to mapOf("5" to 0, "8" to 0),
                    ),
                    mapOf("id" to 0L, "title" to "пусто"),
                    mapOf("title" to "без id"),
                    "junk",
                ),
            ),
        )
        val chats = ChatsApi(sink).commonChats(5)
        assertEquals(Opcode.CHAT_SEARCH_COMMON_PARTICIPANTS, sink.sent.single().first)
        assertEquals(mapOf<String, Any>("userIds" to listOf(5L)), sink.sent.single().second)
        assertEquals(1, chats.size)
        assertEquals(11L, chats[0].id)
        assertEquals("CHAT", chats[0].type)
        assertEquals("Дача", chats[0].title)
        assertEquals("https://i/11", chats[0].iconUrl)
        assertEquals(4, chats[0].participantsCount)
        assertEquals(listOf(5L, 8L), chats[0].participantIds)
    }

    @Test
    fun missingCommonChatsAreEmpty() = runTest {
        assertTrue(ChatsApi(ScriptSink(emptyMap<String, Any?>())).commonChats(1).isEmpty())
        assertNull(CommonChat.from("нет"))
        val bare = CommonChat.from(mapOf("id" to 3L))!!
        assertEquals("CHAT", bare.type)
        assertEquals("", bare.title)
        assertEquals(0, bare.participantsCount)
    }
}
