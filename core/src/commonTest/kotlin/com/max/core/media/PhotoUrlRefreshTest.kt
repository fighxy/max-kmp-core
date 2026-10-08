package com.max.core.media

import com.max.core.api.AccountConfig
import com.max.core.api.MalformedReplyException
import com.max.core.auth.RequestSink
import com.max.core.protocol.CmdType
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.transport.TransportPacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoUrlRefreshTest {
    @Test
    fun refreshSendsMediaAndReadsPhotoAttaches() = runTest {
        val sink = FakeSink(
            mapOf(
                "media" to listOf(
                    mapOf(
                        "_type" to "PHOTO",
                        "photoId" to 7,
                        "baseUrl" to "https://cdn/p?expires=9",
                        "mp4Url" to "https://cdn/p.mp4",
                        "photoToken" to "tok",
                        "width" to 100,
                        "height" to 80,
                        "previewUrl" to "https://cdn/prev",
                        "gif" to true,
                    ),
                    mapOf("_type" to "VIDEO", "videoId" to 3, "photoId" to 8),
                    mapOf("not" to "a photo"),
                    "skip",
                ),
            ),
        )
        val api = MediaApi(sink)
        val got = api.refreshPhotoUrls(listOf(PhotoUrlMedia(10, 20, listOf(7, 8))))
        assertEquals(Opcode.PHOTO_URL_REFRESH, sink.sent.single().first)
        assertEquals(
            mapOf(
                "media" to listOf(
                    mapOf("chatId" to 10L, "messageId" to 20L, "photoIds" to listOf(7L, 8L)),
                ),
            ),
            sink.sent.single().second,
        )
        assertEquals(1, got.size)
        val photo = got.single()
        assertEquals(7L, photo.photoId)
        assertEquals("https://cdn/p?expires=9", photo.baseUrl)
        assertEquals("https://cdn/p.mp4", photo.mp4Url)
        assertEquals("tok", photo.photoToken)
        assertEquals(100, photo.width)
        assertEquals(80, photo.height)
        assertEquals("https://cdn/prev", photo.previewUrl)
        assertTrue(photo.gif)
    }

    @Test
    fun chunksByTheServerMaxAndSkipsAnEmptyList() = runTest {
        val sink = FakeSink(mapOf("media" to emptyList<Any?>()), mapOf("media" to emptyList<Any?>()))
        val api = MediaApi(sink)
        val items = (1..101).map { PhotoUrlMedia(1, it.toLong(), listOf(it.toLong())) }
        api.refreshPhotoUrls(items, maxPerRequest = 100)
        assertEquals(2, sink.sent.size)
        assertEquals(100, (sink.sent[0].second as Map<*, *>)["media"].let { (it as List<*>).size })
        assertEquals(1, (sink.sent[1].second as Map<*, *>)["media"].let { (it as List<*>).size })
        sink.sent.clear()
        assertEquals(emptyList(), api.refreshPhotoUrls(emptyList()))
        assertEquals(0, sink.sent.size)
    }

    @Test
    fun aNonListMediaIsMalformed() = runTest {
        val api = MediaApi(FakeSink(mapOf("media" to "nope")))
        assertFailsWith<MalformedReplyException> {
            api.refreshPhotoUrls(listOf(PhotoUrlMedia(1, 2, listOf(3))))
        }
    }

    @Test
    fun serverFlagAndBatchComeFromConfig() {
        assertFalse(AccountConfig().photoUrlRefresh)
        assertEquals(100, AccountConfig().photoUrlRefreshMaxMedia)
        val on = AccountConfig(server = mapOf("photo-url-refresh" to true, "photo-url-refresh-max-media-per-request" to "40"))
        assertTrue(on.photoUrlRefresh)
        assertEquals(40, on.photoUrlRefreshMaxMedia)
        val off = AccountConfig(server = mapOf("photo-url-refresh" to "OFF", "photo-url-refresh-max-media-per-request" to 0))
        assertFalse(off.photoUrlRefresh)
        assertEquals(100, off.photoUrlRefreshMaxMedia)
    }

    private class FakeSink(vararg replies: Any?) : RequestSink {
        val script = ArrayDeque(replies.toList())
        val sent = ArrayList<Pair<Opcode, Any?>>()
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
            sent += opcode to payload
            val next = if (script.isEmpty()) emptyMap<String, Any?>() else script.removeFirst()
            return TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false), next)
        }
    }
}
