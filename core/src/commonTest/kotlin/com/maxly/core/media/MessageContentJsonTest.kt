package com.maxly.core.media

import com.maxly.core.api.MaxMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageContentJsonTest {
    @Test
    fun copiesPhotoVoiceAndFileWithoutInventingAnAddress() {
        val photo = mapOf(
            "_type" to "PHOTO",
            "photoId" to 15,
            "baseUrl" to "https://cdn.example/p.jpg",
            "width" to 800,
            "height" to 600,
        )
        val audio = mapOf(
            "_type" to "AUDIO",
            "audioId" to 3,
            "url" to "https://cdn.example/a.ogg",
            "duration" to 3200,
            "wave" to listOf(10, 200),
        )
        val file = mapOf(
            "_type" to "FILE",
            "fileId" to 9,
            "name" to "отчёт.pdf",
            "size" to 2048L,
            "baseUrl" to "https://cdn.example/f.pdf",
        )
        val tokenOnly = mapOf("_type" to "FILE", "fileId" to 10, "name" to "secret.bin", "token" to "ft")
        val message = MaxMessage.from(
            mapOf(
                "id" to 1L,
                "time" to 2L,
                "type" to "USER",
                "text" to "",
                "attaches" to listOf(photo, audio, file, tokenOnly),
            ),
        )!!
        val json = messageContentJson(message)
        assertTrue(json.contains("PHOTO"))
        assertTrue(json.contains("https://cdn.example/p.jpg"))
        assertTrue(json.contains("AUDIO"))
        assertTrue(json.contains("https://cdn.example/a.ogg"))
        assertTrue(json.contains("3200"))
        assertTrue(json.contains("200"))
        assertTrue(json.contains("FILE"))
        assertTrue(json.contains("https://cdn.example/f.pdf"))
        assertTrue(json.contains("отчёт.pdf") || json.contains("\\u043e\\u0442\\u0447"))
        assertTrue(json.contains("secret.bin"))
        assertTrue(!json.contains("fn="))
        // The file token stays a token. It is not turned into a fourth address.
        assertEquals(3, json.split("https://").size - 1)
    }

    @Test
    fun textOnlyMessageStaysEmpty() {
        val message = MaxMessage.from(mapOf("id" to 1L, "time" to 2L, "type" to "USER", "text" to "привет"))!!
        assertEquals("", messageContentJson(message))
    }

    @Test
    fun reactionsAndCommentCountAreKept() {
        val message = MaxMessage.from(
            mapOf(
                "id" to 1L,
                "time" to 2L,
                "type" to "USER",
                "reactionInfo" to mapOf(
                    "totalCount" to 1,
                    "counters" to listOf(mapOf("reaction" to "❤️", "count" to 1)),
                ),
                "commentsCount" to 4,
            ),
        )!!
        val json = messageContentJson(message)
        assertTrue(json.contains("reactionInfo"))
        assertTrue(json.contains("commentsCount"))
        assertTrue(json.contains("4"))
    }

    @Test
    fun namesTheQuotedSenderWhenKnown() {
        val message = MaxMessage.from(
            mapOf(
                "id" to 5L,
                "time" to 6L,
                "type" to "USER",
                "text" to "да",
                "link" to mapOf(
                    "type" to "REPLY",
                    "messageId" to 4L,
                    "message" to mapOf("id" to 4L, "sender" to 77L, "text" to "вопрос"),
                ),
            ),
        )!!
        val named = messageContentJson(message) { id -> if (id == 77L) "Анна" else null }
        assertTrue(named.contains("\"senderName\":\"Анна\""), named)
        val unknown = messageContentJson(message)
        assertTrue(!unknown.contains("senderName"), unknown)
    }

    @Test
    fun keepsTextFormatting() {
        val message = MaxMessage.from(
            mapOf(
                "id" to 7L,
                "time" to 8L,
                "type" to "USER",
                "text" to "жирный текст",
                "elements" to listOf(mapOf("type" to "STRONG", "from" to 0, "length" to 6)),
            ),
        )!!
        val json = messageContentJson(message)
        assertTrue(json.contains("\"elements\":[{\"type\":\"STRONG\",\"from\":0,\"length\":6}]"), json)
    }

    @Test
    fun marksEditedMessages() {
        val message = MaxMessage.from(mapOf("id" to 9L, "time" to 10L, "type" to "USER", "text" to "новый", "status" to "EDITED"))!!
        assertTrue(messageContentJson(message).contains("\"edited\":true"))
        val plain = MaxMessage.from(mapOf("id" to 9L, "time" to 10L, "type" to "USER", "text" to "новый"))!!
        assertEquals("", messageContentJson(plain))
    }
}
