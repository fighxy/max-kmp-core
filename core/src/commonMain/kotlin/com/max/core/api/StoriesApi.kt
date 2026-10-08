package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode

/**
 * Stories over a [RequestSink], in the shape KometTeam/Komet's current client sends them
 * (`feature/FullStack`, `StoriesModule` and `models/story.dart`; only the schema is taken).
 *
 * - Feed: `STORIES_LIST` 208 `{cursor: "", count}` → `storiesPreviews` ([StoryPreview], one ring
 *   per owner).
 * - An owner's stories: `STORIES_GET_BY_OWNER_ID` 210 `{owners: [{ownerId, type}]}` →
 *   `storiesPreviews` and `peerStories: [{owner, stories}]` ([OwnerStories]).
 * - Seen: `STORIES_MARK` 214 `{owner, storyId}`.
 * - Publish: `STORIES_SEND` 215 `{stories: [{cid, settings, media, expiration}]}` → the owner's
 *   `storiesPreview` and the new `stories`. [StoryAudience] is `settings`: 1 everyone, 2 contacts.
 *   Photo media is `{_type: PHOTO, photoToken}`, video `{_type: VIDEO, videoType: 2, token,
 *   duration}`; the tokens come from story upload slots (`PHOTO_UPLOAD` / `VIDEO_UPLOAD` with
 *   `type` 1 / 3, see `MediaApi.uploadStoryPhoto` / `uploadStoryVideo`).
 * - Delete own: `STORIES_DELETE` 218 `{storyIds}`.
 * - Push: `NOTIF_STORIES_UPDATE` 216 `{storiesPreview}` (`MaxEvent.StoriesUpdated`); an empty
 *   preview (`totalCount` 0) means the owner has no stories left.
 *
 * Reactions (213), stats (211, 212), edit (217) and by-id (220) are not used by Komet; they stay
 * out until a schema is known.
 */
class StoriesApi(private val sink: RequestSink, private val clock: () -> Long) {

    /** First page of the feed (rings), newest first as the server orders them. */
    suspend fun feed(count: Int = 20): List<StoryPreview> {
        val map = rawMap(sink.request(Opcode.STORIES_LIST, linkedMapOf("cursor" to "", "count" to count)))
        return (map["storiesPreviews"] as? List<*>).orEmpty().mapNotNull(StoryPreview::from)
    }

    /**
     * The full stories of [owners] and their current rings. An owner missing from the reply has none.
     * Owners without a positive id (a group or channel chat id is negative) are not sent: the server
     * answers them with a validation error and drops the session.
     */
    suspend fun byOwners(owners: List<StoryOwner>): OwnerStories {
        val valid = owners.filter { it.ownerId > 0 }
        if (valid.isEmpty()) return OwnerStories(emptyList(), emptyList())
        val map = rawMap(sink.request(Opcode.STORIES_GET_BY_OWNER, linkedMapOf("owners" to valid.map { it.toPayload() })))
        return OwnerStories.from(map)
    }

    /** Marks [storyId] of [owner] seen. A server error is thrown. */
    suspend fun mark(owner: StoryOwner, storyId: Long) {
        sink.request(Opcode.STORIES_MARK, linkedMapOf("owner" to owner.toPayload(), "storyId" to storyId))
    }

    /** Publishes a photo story from an uploaded [photoToken]. */
    suspend fun publishPhoto(photoToken: String, audience: StoryAudience = StoryAudience.EVERYONE, expirationMs: Long = DAY_MS): PublishedStory =
        publish(linkedMapOf("_type" to "PHOTO", "photoToken" to photoToken), audience, expirationMs)

    /** Publishes a video story from an uploaded [videoToken]; [durationMs] goes out when known. */
    suspend fun publishVideo(
        videoToken: String,
        durationMs: Long? = null,
        audience: StoryAudience = StoryAudience.EVERYONE,
        expirationMs: Long = DAY_MS,
    ): PublishedStory {
        val media = linkedMapOf<String, Any?>("_type" to "VIDEO", "videoType" to 2, "token" to videoToken)
        if (durationMs != null && durationMs > 0) media["duration"] = durationMs
        return publish(media, audience, expirationMs)
    }

    private suspend fun publish(media: Map<String, Any?>, audience: StoryAudience, expirationMs: Long): PublishedStory {
        val story = linkedMapOf<String, Any?>("cid" to clock(), "settings" to audience.code, "media" to media, "expiration" to expirationMs)
        val map = replyMap(sink.request(Opcode.STORIES_SEND, linkedMapOf("stories" to listOf(story))), Opcode.STORIES_SEND)
        return PublishedStory(
            StoryPreview.from(map["storiesPreview"]),
            (map["stories"] as? List<*>).orEmpty().mapNotNull(Story::from),
        )
    }

    /**
     * The account's own story archive (`STORIES_HISTORY_GET_BY_OWNER_ID` 219).
     * The app always sends `count` 30 and sends `marker` only when it is not zero.
     * There is no owner id: this is the signed-in account. [marker] `null` or `0` is the first page.
     * A reply marker of `0` or a missing marker is [StoryArchivePage.marker] `null` (no next page).
     * Items are the same story objects as the feed; `version`, `layers`, `reaction`, `viewsCount`
     * and `reactionsCount` are on the wire and not kept.
     */
    suspend fun ownArchive(marker: Long? = null): StoryArchivePage {
        val payload = linkedMapOf<String, Any?>("count" to OWN_ARCHIVE_PAGE)
        if (marker != null && marker != 0L) payload["marker"] = marker
        val map = replyMap(sink.request(Opcode.STORIES_HISTORY_GET_BY_OWNER, payload), Opcode.STORIES_HISTORY_GET_BY_OWNER)
        val items = map["stories"]
        val stories = when (items) {
            null -> emptyList()
            is List<*> -> items.mapNotNull(Story::from)
            else -> throw MalformedReplyException(Opcode.STORIES_HISTORY_GET_BY_OWNER, "stories is not a list", map)
        }
        return StoryArchivePage(stories, map["marker"].asLong()?.takeIf { it != 0L })
    }

    /** Deletes the account's own stories. */
    suspend fun delete(storyIds: List<Long>) {
        if (storyIds.isEmpty()) return
        sink.request(Opcode.STORIES_DELETE, linkedMapOf("storyIds" to storyIds))
    }

    companion object {
        /** Komet's story lifetime: a day. */
        const val DAY_MS: Long = 86_400_000L

        /** Page size the app hardcodes for opcode 219. */
        const val OWN_ARCHIVE_PAGE: Int = 30
    }
}

/** One page of [StoriesApi.ownArchive]. [marker] `null` means there is no next page. */
data class StoryArchivePage(val stories: List<Story>, val marker: Long?)

/** Who sees a published story (`settings`). */
enum class StoryAudience(val code: Int) { EVERYONE(1), CONTACTS(2) }

/** Whose story: a person (`type` 0), a group (1) or a channel (2). The feed groups by owner. */
data class StoryOwner(val ownerId: Long, val type: Type = Type.USER) {
    enum class Type(val code: Int) { USER(0), CHAT(1), CHANNEL(2) }

    fun toPayload(): Map<String, Any?> = linkedMapOf("ownerId" to ownerId, "type" to type.code)

    companion object {
        /** `{ownerId, type}`; `null` without a non-zero id. */
        fun from(value: Any?): StoryOwner? {
            val m = value as? Map<*, *> ?: return null
            val id = m["ownerId"].asLong()?.takeIf { it != 0L } ?: return null
            val type = when (m["type"].asLong()?.toInt()) {
                1 -> Type.CHAT
                2 -> Type.CHANNEL
                else -> Type.USER
            }
            return StoryOwner(id, type)
        }
    }
}

/**
 * An owner's ring: how many stories it has and how many the account has seen. Times are
 * milliseconds (the server sends seconds or milliseconds; seconds are scaled).
 */
data class StoryPreview(
    val owner: StoryOwner,
    val updateTime: Long,
    val totalCount: Int,
    val readCount: Int,
    val lastStoryExpirationTime: Long,
) {
    val unreadCount: Int get() = (totalCount - readCount).coerceAtLeast(0)
    val isEmpty: Boolean get() = totalCount <= 0

    companion object {
        fun from(value: Any?): StoryPreview? {
            val m = value as? Map<*, *> ?: return null
            val owner = StoryOwner.from(m["owner"]) ?: return null
            return StoryPreview(
                owner = owner,
                updateTime = millis(m["updateTime"].asLong()),
                totalCount = m["totalCount"].asLong()?.toInt() ?: 0,
                readCount = m["readCount"].asLong()?.toInt() ?: 0,
                lastStoryExpirationTime = millis(m["lastStoryExpirationTime"].asLong()),
            )
        }
    }
}

/** One story. [media] is `null` when the server sent none the client understands. */
data class Story(
    val id: Long,
    val cid: Long,
    val owner: StoryOwner,
    val settings: Int,
    val time: Long,
    val updateTime: Long,
    val expiration: Long,
    val media: StoryMedia?,
) {
    companion object {
        fun from(value: Any?): Story? {
            val m = value as? Map<*, *> ?: return null
            val owner = StoryOwner.from(m["owner"]) ?: return null
            return Story(
                id = m["id"].asLong() ?: 0,
                cid = m["cid"].asLong() ?: 0,
                owner = owner,
                settings = m["settings"].asLong()?.toInt() ?: 0,
                time = millis(m["time"].asLong()),
                updateTime = millis(m["updateTime"].asLong()),
                expiration = millis(m["expiration"].asLong()),
                media = StoryMedia.from(m["media"]),
            )
        }
    }
}

/**
 * A story's photo or video. Photo address: `photoUrl`, `baseUrl` or `url`; video: `mp4Url`,
 * `videoUrl`, `MP4_1080` or `baseUrl`, cover in `thumbnail`, length in `duration` (ms).
 */
data class StoryMedia(
    val isVideo: Boolean,
    val url: String?,
    val thumbnailUrl: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
) {
    companion object {
        fun from(value: Any?): StoryMedia? {
            val m = value as? Map<*, *> ?: return null
            fun text(vararg keys: String) = keys.firstNotNullOfOrNull { k -> (m[k] as? String)?.takeIf { it.isNotBlank() } }
            val width = m["width"].asLong()?.toInt()?.takeIf { it > 0 }
            val height = m["height"].asLong()?.toInt()?.takeIf { it > 0 }
            return when ((m["_type"] as? String)?.uppercase()) {
                "PHOTO" -> StoryMedia(false, text("photoUrl", "baseUrl", "url"), null, width, height, null)
                "VIDEO" -> StoryMedia(
                    true,
                    text("mp4Url", "videoUrl", "MP4_1080", "baseUrl"),
                    text("thumbnail"),
                    width,
                    height,
                    m["duration"].asLong()?.takeIf { it > 0 },
                )
                else -> null
            }
        }
    }
}

/** [StoriesApi.byOwners]: the owners' rings and their stories. */
data class OwnerStories(val previews: List<StoryPreview>, val stories: List<Pair<StoryOwner, List<Story>>>) {
    /** Stories of [owner], or `null` when the reply did not list it. */
    fun storiesOf(owner: StoryOwner): List<Story>? = stories.firstOrNull { it.first.ownerId == owner.ownerId }?.second

    companion object {
        fun from(map: Map<*, *>): OwnerStories {
            val previews = (map["storiesPreviews"] as? List<*>).orEmpty().mapNotNull(StoryPreview::from)
            val peers = (map["peerStories"] as? List<*>).orEmpty().mapNotNull { raw ->
                val peer = raw as? Map<*, *> ?: return@mapNotNull null
                val owner = StoryOwner.from(peer["owner"]) ?: return@mapNotNull null
                owner to (peer["stories"] as? List<*>).orEmpty().mapNotNull(Story::from)
            }
            return OwnerStories(previews, peers)
        }
    }
}

/** [StoriesApi.publishPhoto] / [StoriesApi.publishVideo]: the owner's new ring and the new stories. */
data class PublishedStory(val preview: StoryPreview?, val stories: List<Story>)

/** Seconds or milliseconds → milliseconds; missing or non-positive → `0`. */
private fun millis(value: Long?): Long = when {
    value == null || value <= 0 -> 0
    value < 1_000_000_000_000L -> value * 1000
    else -> value
}
