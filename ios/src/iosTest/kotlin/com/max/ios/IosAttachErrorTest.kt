package com.max.ios

import com.max.core.events.EventParser
import com.max.core.protocol.Opcode
import com.max.core.state.MaxState
import kotlin.test.Test
import kotlin.test.assertEquals

class IosAttachErrorTest {
    private fun attachError(body: Map<String, Any?>): IosEvent =
        flatten(EventParser.parse(Opcode.NOTIF_ATTACH.value, 0, body), MaxState()).single()

    @Test
    fun failedUploadCarriesItsIdAndKind() {
        val video = attachError(mapOf("videoId" to 77L, "error" to "upload.failed"))
        assertEquals("attachError", video.kind)
        assertEquals("upload.failed", video.text)
        assertEquals("77", video.messageId)
        assertEquals("video", video.title)

        assertEquals("file", attachError(mapOf("fileId" to 5L, "audioId" to 6L, "error" to "x")).title)
        assertEquals("audio", attachError(mapOf("audioId" to 6L, "error" to "x")).title)
    }

    @Test
    fun failureWithoutIdLeavesIdAndKindEmpty() {
        val event = attachError(mapOf("error" to "upload.failed"))
        assertEquals("attachError", event.kind)
        assertEquals("", event.messageId)
        assertEquals("", event.title)
    }
}
