package com.max.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLogTest {
    @Test
    fun aDraftPushKeepsItsShapeButNotItsText() {
        val payload = mapOf(
            "chatId" to -70L,
            "userId" to "20",
            "draft" to mapOf(
                "text" to "привет, это секрет",
                "updateTime" to 1_700_000_000_123L,
                "replyTo" to "555",
                "elements" to listOf(
                    mapOf("type" to "STRONG", "from" to 0, "length" to 6),
                    mapOf("type" to "USER_MENTION", "from" to 8, "length" to 3, "entityId" to 42L, "entityName" to "Иван"),
                    mapOf("type" to "LINK", "from" to 1, "length" to 2, "attributes" to mapOf("url" to "https://example.org/x")),
                ),
                "attaches" to listOf(mapOf("_type" to "PHOTO", "photoId" to 9L, "baseUrl" to "https://cdn/x", "photoToken" to "tok")),
                "silent" to true,
            ),
        )
        val line = DiagnosticLog.draftPush(EventParser.parse(152, 0, payload))
        assertEquals(
            "push 152 -> DraftSaved | {\"chatId\":-70,\"userId\":\"20\",\"draft\":{\"text\":\"пр…(len=18)\",\"updateTime\":1700000000123," +
                "\"replyTo\":\"555\",\"elements\":[{\"type\":\"STRONG\",\"from\":0,\"length\":6}," +
                "{\"type\":\"USER_MENTION\",\"from\":8,\"length\":3,\"entityId\":42,\"entityName\":\"<str len=4>\"}," +
                "{\"type\":\"LINK\",\"from\":1,\"length\":2,\"attributes\":{\"url\":\"<str len=21>\"}}]," +
                "\"attaches\":[{\"_type\":\"PHOTO\",\"photoId\":9,\"baseUrl\":\"<str len=13>\",\"photoToken\":\"<str len=3>\"}],\"silent\":true}}",
            line,
        )
        assertFalse("секрет" in line)
    }

    @Test
    fun discardsAndUnparsedPushesAreLoggedToo() {
        assertEquals(
            "push 153 -> DraftDiscarded | {\"chatId\":-70,\"time\":5}",
            DiagnosticLog.draftPush(EventParser.parse(153, 0, mapOf("chatId" to -70L, "time" to 5L))),
        )
        val unknown = EventParser.parse(152, 0, mapOf("weird" to mapOf("text" to "ab", "blob" to byteArrayOf(1, 2, 3))))
        assertEquals("push 152 -> Unknown | {\"weird\":{\"text\":\"ab(len=2)\",\"blob\":\"<bytes 3>\"}}", DiagnosticLog.draftPush(unknown))
        assertTrue(DiagnosticLog.isDraftPush(unknown))
        assertFalse(DiagnosticLog.isDraftPush(EventParser.parse(132, 0, mapOf("userId" to 1L))))
    }

    @Test
    fun textPreviewNeverSplitsASurrogatePairAndHandlesShortText() {
        assertEquals("\"\uD83D\uDE00…(len=3)\"", DiagnosticLog.redact(mapOf("text" to "\uD83D\uDE00x")).substringAfter(':').dropLast(1))
        assertEquals("\"a\u2026(len=3)\"", DiagnosticLog.redact(mapOf("text" to "a\uD83D\uDE00")).substringAfter(':').dropLast(1))
        assertEquals("{\"text\":\"(len=0)\"}", DiagnosticLog.redact(mapOf("text" to "")))
        assertEquals("{\"text\":\"\\\"x(len=2)\"}", DiagnosticLog.redact(mapOf("text" to "\"x")))
    }

    @Test
    fun aLineIsCapped() {
        val big = mapOf("chatId" to 1L, "draft" to mapOf("time" to 1L, "elements" to List(500) { mapOf("type" to "STRONG", "from" to it, "length" to 1) }))
        val line = DiagnosticLog.draftPush(EventParser.parse(152, 0, big))
        assertTrue(line.length <= DiagnosticLog.MAX_ENTRY_CHARS, "${line.length}")
        assertTrue(line.contains("…(+"))
    }
}
