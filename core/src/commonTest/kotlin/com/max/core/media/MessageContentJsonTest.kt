package com.max.core.media

import com.max.core.api.MaxMessage
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
}
