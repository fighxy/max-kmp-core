package com.max.core.media

import com.max.core.api.MalformedReplyException
import com.max.core.api.asLong
import com.max.core.protocol.Opcode

/**
 * One entry of a `PHOTO_URL_REFRESH` (203) request: the photos of one message.
 *
 * The app sends `{media: [{chatId, messageId, photoIds}]}` (`uuf`). `photoIds` is the set of
 * photo ids on that message. The web client also sends an optional `postId`; the app's request
 * builder does not, so this type does not either.
 */
data class PhotoUrlMedia(
    val chatId: Long,
    val messageId: Long,
    val photoIds: List<Long>,
)

/**
 * One photo from the reply's `media` array. The app parses that array with the same attachment
 * parser as a message photo (`f80` / `eyd`) and keeps only photos. Fields here are the ones that
 * parser stores on a photo and that a refresh is for: [photoId], [baseUrl], [mp4Url] (a live
 * photo), [photoToken], [width], [height], [previewUrl], [gif]. [raw] is the whole map.
 */
data class RefreshedPhoto(
    val photoId: Long,
    val baseUrl: String?,
    val mp4Url: String?,
    val photoToken: String?,
    val width: Int?,
    val height: Int?,
    val previewUrl: String?,
    val gif: Boolean,
    val raw: Map<*, *>,
) {
    companion object {
        /** A photo item, or `null` when it has no `photoId` or its `_type` is not `PHOTO`. */
        fun from(item: Map<*, *>): RefreshedPhoto? {
            val type = item["_type"] as? String
            if (type != null && type != "PHOTO") return null
            val id = item["photoId"].asLong() ?: return null
            return RefreshedPhoto(
                photoId = id,
                baseUrl = item["baseUrl"] as? String,
                mp4Url = item["mp4Url"] as? String,
                photoToken = item["photoToken"] as? String,
                width = item["width"].asLong()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
                height = item["height"].asLong()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
                previewUrl = item["previewUrl"] as? String,
                gif = item["gif"] as? Boolean ?: false,
                raw = item,
            )
        }

        /** Reply `{media: [photo, ...]}`. A missing `media` is an empty list; a non-list is malformed. */
        fun listFrom(reply: Map<*, *>): List<RefreshedPhoto> {
            val media = reply["media"] ?: return emptyList()
            val list = media as? List<*> ?: throw MalformedReplyException(Opcode.PHOTO_URL_REFRESH, "media is not a list", reply)
            return list.mapNotNull { (it as? Map<*, *>)?.let(::from) }
        }
    }
}
