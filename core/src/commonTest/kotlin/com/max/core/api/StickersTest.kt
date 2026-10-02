package com.max.core.api

import com.max.core.media.OutgoingAttachment
import com.max.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StickersTest {
    @Test
    fun sectionsPutFavoritesFirstPageByMarkerAndReadRecents() = runTest {
        val sink = ScriptSink(
            mapOf("sections" to listOf(mapOf("id" to "FAVORITE_STICKER_SETS", "stickerSets" to listOf(5L, 9L)))),
            mapOf(
                "sections" to listOf(
                    mapOf("id" to "NEW_STICKER_SETS", "stickerSets" to listOf(1L, 5L), "marker" to 2L),
                    mapOf("type" to "RECENTS", "recentsList" to listOf(
                        mapOf("type" to "STICKER", "stickerId" to 31L),
                        mapOf("type" to "ANIMOJI", "id" to 4L),
                        mapOf("type" to "STICKER", "id" to 32L),
                    )),
                ),
            ),
            mapOf("stickerSets" to listOf(2L, 3L), "marker" to 0L),
        )
        val sections = MaxApi(sink).assets.stickerSections()
        assertEquals(listOf(Opcode.ASSETS_UPDATE, Opcode.ASSETS_UPDATE, Opcode.ASSETS_GET), sink.opcodes)
        assertEquals(msgpackHex(linkedMapOf("type" to "FAVORITE_STICKER", "sync" to 0)), sink.hex(0))
        assertEquals(msgpackHex(linkedMapOf("type" to "STICKER", "sync" to 0)), sink.hex(1))
        assertEquals(msgpackHex(linkedMapOf("sectionId" to "NEW_STICKER_SETS", "from" to 2L, "count" to 100)), sink.hex(2))
        assertEquals(listOf(5L, 9L, 1L, 2L, 3L), sections.setIds)
        assertEquals(listOf(5L, 9L), sections.favoriteSetIds)
        assertEquals(listOf(31L, 32L), sections.recentStickerIds)
    }

    @Test
    fun stickersKeepTheAskedOrderAndSkipBrokenEntries() = runTest {
        val sink = ScriptSink(
            mapOf("stickers" to listOf(
                mapOf("id" to 2L, "url" to "https://s/2.webp", "width" to 512, "height" to 512, "tags" to listOf("👋", "")),
                mapOf("id" to 1L, "url" to "https://s/1.webp", "lottieUrl" to "https://s/1.json", "setId" to 7L),
                mapOf("url" to "no id"),
            )),
        )
        val stickers = MaxApi(sink).assets.stickers(listOf(1L, 2L, 3L))
        assertEquals(msgpackHex(linkedMapOf("type" to "STICKER", "ids" to listOf(1L, 2L, 3L))), sink.hex(0))
        assertEquals(listOf(1L, 2L), stickers.map { it.id })
        assertEquals("https://s/1.json", stickers[0].lottieUrl)
        assertEquals(7L, stickers[0].setId)
        assertNull(stickers[1].lottieUrl)
        assertEquals(listOf("👋"), stickers[1].tags)
    }

    @Test
    fun setsParseIconAndStickerIds() = runTest {
        val sink = ScriptSink(mapOf("stickerSets" to listOf(mapOf("id" to 7L, "name" to "Кот", "iconUrl" to "", "stickers" to listOf(1L, 2L)))))
        val set = MaxApi(sink).assets.stickerSets(listOf(7L)).single()
        assertEquals("Кот", set.name)
        assertNull(set.iconUrl)
        assertEquals(listOf(1L, 2L), set.stickerIds)
    }

    @Test
    fun stickerAttachmentPayload() {
        assertEquals(linkedMapOf<String, Any?>("_type" to "STICKER", "stickerId" to 42L), OutgoingAttachment.Sticker(42).toPayload())
    }
}
