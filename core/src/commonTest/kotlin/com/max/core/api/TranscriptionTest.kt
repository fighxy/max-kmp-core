package com.max.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptionTest {
    @Test
    fun readyReply() {
        val t = Transcription.from(mapOf("transcriptionStatus" to 1, "transcription" to "Привет"))!!
        assertEquals(1, t.status)
        assertEquals("Привет", t.text)
        assertNull(t.messageId)
    }

    @Test
    fun inProgressReplyHasNoText() {
        val t = Transcription.from(mapOf("transcriptionStatus" to 0))!!
        assertEquals(0, t.status)
        assertNull(t.text)
    }

    @Test
    fun pushNestedInMessageWithoutStatusIsReady() {
        val t = Transcription.from(mapOf("message" to mapOf("messageId" to "117", "chatId" to 5L, "transcription" to "Текст")))!!
        assertEquals(1, t.status)
        assertEquals(117L, t.messageId)
        assertEquals(5L, t.chatId)
        assertEquals("Текст", t.text)
    }

    @Test
    fun missingEverythingIsUnknown() {
        assertEquals(-1, Transcription.from(emptyMap<String, Any?>())!!.status)
        assertNull(Transcription.from("x"))
    }
}
