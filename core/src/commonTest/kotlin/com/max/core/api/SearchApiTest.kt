package com.max.core.api

import com.max.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SearchApiTest {
    @Test
    fun publicSearchSendsQueryPageAndType() = runTest {
        val sink = ScriptSink(
            mapOf(
                "result" to listOf(
                    mapOf("chat" to mapOf("id" to 7L, "type" to "CHANNEL", "title" to "Новости", "baseIconUrl" to "https://i/7", "link" to "@news")),
                    mapOf("user" to mapOf("id" to 9L, "names" to listOf(mapOf("name" to "Анна")), "link" to "anna")),
                    mapOf("other" to 1),
                    "junk",
                ),
            ),
        )
        val hits = SearchApi(sink).searchPublic("  новости ", from = 5, count = 10)
        assertEquals(Opcode.PUBLIC_SEARCH, sink.sent.single().first)
        assertEquals(mapOf<String, Any>("query" to "новости", "from" to 5, "count" to 10, "type" to "ALL"), sink.sent.single().second)
        assertEquals(2, hits.size)
        assertEquals(7L, hits[0].chat!!.id)
        assertEquals("https://i/7", hits[0].iconUrl)
        assertEquals("news", hits[0].link)
        assertNull(hits[0].user)
        assertEquals(9L, hits[1].user!!.id)
        assertEquals("anna", hits[1].link)
    }

    @Test
    fun blankQuerySendsNothing() = runTest {
        val sink = ScriptSink()
        val api = SearchApi(sink)
        assertTrue(api.searchPublic("   ").isEmpty())
        assertTrue(api.searchMessages("").isEmpty())
        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun messageSearchReadsChatAndMessage() = runTest {
        val sink = ScriptSink(
            mapOf(
                "result" to listOf(
                    mapOf("chatId" to 42L, "message" to mapOf("id" to 100L, "text" to "привет", "time" to 1_700_000_000_000L, "sender" to 5L, "type" to "USER")),
                    // No type and time: still a hit.
                    mapOf("chatId" to 43L, "message" to mapOf("id" to 101L, "text" to "ещё")),
                    mapOf("chatId" to 0L, "message" to mapOf("id" to 102L, "text" to "без чата")),
                    mapOf("chatId" to 44L),
                ),
            ),
        )
        val hits = SearchApi(sink).searchMessages("прив", count = 30)
        assertEquals(Opcode.CHAT_SEARCH, sink.sent.single().first)
        assertEquals(mapOf<String, Any>("query" to "прив", "count" to 30), sink.sent.single().second)
        assertEquals(listOf(42L, 43L), hits.map { it.chatId })
        assertEquals("привет", hits[0].message.text)
        assertEquals(5L, hits[0].message.sender)
        assertEquals(1_700_000_000_000L, hits[0].message.time)
        assertEquals(0L, hits[1].message.time)
        assertEquals(43L, hits[1].message.chatId)
    }

    @Test
    fun missingResultIsEmptyAndWrongShapeFails() = runTest {
        assertTrue(SearchApi(ScriptSink(emptyMap<String, Any?>())).searchPublic("a").isEmpty())
        assertFailsWith<MalformedReplyException> { SearchApi(ScriptSink(mapOf("result" to "x"))).searchMessages("a") }
    }

    @Test
    fun maxApiExposesSearch() = runTest {
        val sink = ScriptSink()
        MaxApi(sink).search.searchPublic("x")
        assertEquals(listOf(Opcode.PUBLIC_SEARCH), sink.opcodes)
    }
}
