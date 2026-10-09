package com.max.core.events

import com.max.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `NOTIF_ATTACH` 136 body `{fileId, audioId, error, videoId}`: a non-empty `error` wins over an id. */
class AttachmentPushTest {
    private fun parse(body: Map<String, Any?>): MaxEvent = EventParser.parse(Opcode.NOTIF_ATTACH.value, 0, body)

    @Test
    fun idWithErrorIsFailedAndNamesTheUpload() {
        val file = assertIs<MaxEvent.AttachmentFailed>(parse(mapOf("fileId" to 4L, "error" to "upload.failed")))
        assertEquals("upload.failed", file.error)
        assertEquals(MaxEvent.AttachmentReady.Kind.FILE, file.kind)
        assertEquals(4L, file.id)
        assertEquals(136, file.opcode)

        val video = assertIs<MaxEvent.AttachmentFailed>(parse(mapOf("videoId" to 20, "error" to "e")))
        assertEquals(MaxEvent.AttachmentReady.Kind.VIDEO to 20L, video.kind to video.id)

        val audio = assertIs<MaxEvent.AttachmentFailed>(parse(mapOf("audioId" to 40L, "error" to "e")))
        assertEquals(MaxEvent.AttachmentReady.Kind.AUDIO to 40L, audio.kind to audio.id)

        // Same priority as AttachmentReady: file, then video, then audio.
        val both = assertIs<MaxEvent.AttachmentFailed>(parse(mapOf("audioId" to 1L, "videoId" to 2L, "error" to "e")))
        assertEquals(MaxEvent.AttachmentReady.Kind.VIDEO to 2L, both.kind to both.id)
    }

    @Test
    fun errorWithoutIdIsFailedWithNullId() {
        val failed = assertIs<MaxEvent.AttachmentFailed>(parse(mapOf("error" to "upload.failed")))
        assertEquals("upload.failed", failed.error)
        assertNull(failed.kind)
        assertNull(failed.id)
    }

    @Test
    fun idWithoutErrorIsReady() {
        assertEquals(
            MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.FILE, 30, 136, mapOf("fileId" to 30L)),
            parse(mapOf("fileId" to 30L)),
        )
        assertEquals(MaxEvent.AttachmentReady.Kind.AUDIO, assertIs<MaxEvent.AttachmentReady>(parse(mapOf("audioId" to 7L, "error" to null))).kind)
    }

    @Test
    fun emptyErrorWithIdIsReady() {
        val ready = assertIs<MaxEvent.AttachmentReady>(parse(mapOf("videoId" to 9L, "error" to "")))
        assertEquals(MaxEvent.AttachmentReady.Kind.VIDEO, ready.kind)
        assertEquals(9L, ready.id)
    }

    @Test
    fun noIdAndNoErrorIsNoTypedEvent() {
        // The parser yields no attachment event; EventParser.parse wraps that as Unknown.
        assertIs<MaxEvent.Unknown>(parse(emptyMap()))
        assertIs<MaxEvent.Unknown>(parse(mapOf("error" to "")))
        assertIs<MaxEvent.Unknown>(parse(mapOf("error" to null, "other" to 1)))
    }

    @Test
    fun oldPositionalConstructorStillWorks() {
        val event = MaxEvent.AttachmentFailed("e", 136, null)
        assertNull(event.kind)
        assertNull(event.id)
    }
}
