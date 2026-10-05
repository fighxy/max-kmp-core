package com.max.core.api

import com.max.core.events.EventParser
import com.max.core.events.MaxEvent
import com.max.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoriesApiTest {
    private val now = 1_759_100_000_000L

    private fun owner(id: Long, type: Int = 0) = mapOf("ownerId" to id, "type" to type)

    private fun preview(id: Long, total: Int, read: Int, type: Int = 0) =
        mapOf("owner" to owner(id, type), "updateTime" to 1_759_000_000L, "totalCount" to total, "readCount" to read, "lastStoryExpirationTime" to now + 1000)

    @Test
    fun feedSendsKometSchemaAndReadsRings() = runTest {
        val sink = ScriptSink(mapOf("storiesPreviews" to listOf(preview(7, 3, 1), preview(9, 1, 1, type = 2), mapOf("owner" to owner(0)), "junk")))
        val rings = StoriesApi(sink) { now }.feed()
        assertEquals(Opcode.STORIES_LIST, sink.opcodes.single())
        assertEquals(mapOf("cursor" to "", "count" to 20), sink.sent.single().second)
        assertEquals(listOf(7L, 9L), rings.map { it.owner.ownerId })
        assertEquals(StoryOwner.Type.CHANNEL, rings[1].owner.type)
        assertEquals(2, rings[0].unreadCount)
        // Seconds are scaled to milliseconds.
        assertEquals(1_759_000_000_000L, rings[0].updateTime)
        assertTrue(!rings[1].isEmpty && rings[1].unreadCount == 0)
    }

    @Test
    fun ownerStoriesParsePhotoAndVideo() = runTest {
        val photo = mapOf(
            "id" to 101L, "cid" to 5L, "owner" to owner(7), "settings" to 1, "time" to now, "expiration" to now + 86_400_000,
            "media" to mapOf("_type" to "PHOTO", "baseUrl" to "https://i/1", "width" to 1080, "height" to 1920),
        )
        val video = mapOf(
            "id" to 102L, "owner" to owner(7), "time" to now,
            "media" to mapOf("_type" to "VIDEO", "MP4_1080" to "https://v/2.mp4", "thumbnail" to "https://v/2.jpg", "duration" to 15000),
        )
        val unknown = mapOf("id" to 103L, "owner" to owner(7), "media" to mapOf("_type" to "STICKER"))
        val sink = ScriptSink(mapOf("storiesPreviews" to listOf(preview(7, 3, 0)), "peerStories" to listOf(mapOf("owner" to owner(7), "stories" to listOf(photo, video, unknown)))))
        val result = StoriesApi(sink) { now }.byOwners(listOf(StoryOwner(7)))
        assertEquals(Opcode.STORIES_GET_BY_OWNER, sink.opcodes.single())
        assertEquals(mapOf("owners" to listOf(mapOf<String, Any?>("ownerId" to 7L, "type" to 0))), sink.sent.single().second)
        val stories = result.storiesOf(StoryOwner(7))!!
        assertEquals(listOf(101L, 102L, 103L), stories.map { it.id })
        assertEquals(StoryMedia(false, "https://i/1", null, 1080, 1920, null), stories[0].media)
        assertEquals(StoryMedia(true, "https://v/2.mp4", "https://v/2.jpg", null, null, 15000), stories[1].media)
        assertNull(stories[2].media)
        assertNull(result.storiesOf(StoryOwner(8)))
        assertEquals(3, result.previews.single().totalCount)
    }

    @Test
    fun markPublishAndDeletePayloads() = runTest {
        val published = mapOf(
            "storiesPreview" to preview(5, 1, 0),
            "stories" to listOf(mapOf("id" to 201L, "owner" to owner(5), "media" to mapOf("_type" to "PHOTO", "photoUrl" to "https://i/9"))),
        )
        val sink = ScriptSink(emptyMap<String, Any?>(), published, emptyMap<String, Any?>(), emptyMap<String, Any?>())
        val api = StoriesApi(sink) { now }
        api.mark(StoryOwner(7), 101)
        val result = api.publishPhoto("tok", StoryAudience.CONTACTS)
        api.publishVideo("vtok", durationMs = 12_000)
        api.delete(listOf(201L))
        api.delete(emptyList())
        assertEquals(listOf(Opcode.STORIES_MARK, Opcode.STORIES_SEND, Opcode.STORIES_SEND, Opcode.STORIES_DELETE), sink.opcodes)
        // Typed as Any? so the literal type 0 stays an Int next to the Long ids.
        assertEquals(mapOf<String, Any?>("owner" to mapOf<String, Any?>("ownerId" to 7L, "type" to 0), "storyId" to 101L), sink.sent[0].second)
        assertEquals(
            mapOf("stories" to listOf(mapOf<String, Any?>("cid" to now, "settings" to 2, "media" to mapOf("_type" to "PHOTO", "photoToken" to "tok"), "expiration" to 86_400_000L))),
            sink.sent[1].second,
        )
        assertEquals(
            mapOf<String, Any?>("_type" to "VIDEO", "videoType" to 2, "token" to "vtok", "duration" to 12_000L),
            ((sink.sent[2].second as Map<*, *>)["stories"] as List<*>).filterIsInstance<Map<*, *>>().single()["media"],
        )
        assertEquals(mapOf("storyIds" to listOf(201L)), sink.sent[3].second)
        assertEquals(5L, result.preview?.owner?.ownerId)
        assertEquals("https://i/9", result.stories.single().media?.url)
    }

    @Test
    fun ringPushBecomesAnEvent() {
        val event = EventParser.parse(Opcode.NOTIF_STORIES_UPDATE.value, 0, mapOf("storiesPreview" to preview(7, 0, 0)))
        val update = event as MaxEvent.StoriesUpdated
        assertEquals(7L, update.preview.owner.ownerId)
        assertTrue(update.preview.isEmpty)
        assertTrue(EventParser.parse(Opcode.NOTIF_STORIES_UPDATE.value, 0, emptyMap<String, Any?>()) is MaxEvent.Unknown)
    }
}
