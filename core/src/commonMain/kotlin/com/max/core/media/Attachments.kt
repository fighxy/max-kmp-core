package com.max.core.media

import com.max.core.api.MaxMessage
import com.max.core.api.asLong

/**
 * An attachment to send in `MSG_SEND` `message.attaches`, produced by an upload (see
 * [MediaApi]). [toPayload] follows PyMax `src/pymax/api/uploads/payloads.py` (`CamelModel`
 * with `_type`, `None` fields left out).
 */
sealed interface OutgoingAttachment {
    fun toPayload(): Map<String, Any?>

    /** `{_type: "PHOTO", photoToken}` (PyMax `AttachPhotoPayload`). */
    data class Photo(val photoToken: String) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf("_type" to "PHOTO", "photoToken" to photoToken)
    }

    /** `{_type: "FILE", fileId}` (PyMax `AttachFilePayload`). */
    data class File(val fileId: Long) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf("_type" to "FILE", "fileId" to fileId)
    }

    /** `{_type: "VIDEO", videoId, token, videoType: 0}` (PyMax `VideoAttachPayload`, regular video). */
    data class Video(val videoId: Long, val token: String) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> =
            linkedMapOf("_type" to "VIDEO", "videoId" to videoId, "token" to token, "videoType" to 0)
    }

    /**
     * Voice message: `{_type: "AUDIO", token, duration, wave}` (PyMax `VoiceAttachPayload`; its
     * serializer drops `videoId` / `videoType` for AUDIO). [uploadId] is the `videoId` of the
     * upload slot — not sent, used to match the `NOTIF_ATTACH {audioId}` signal. PyMax sends
     * `wave` as 80 zero bytes ([SILENT_WAVE]); [durationMs] in milliseconds.
     */
    data class Voice(val uploadId: Long, val token: String, val durationMs: Long, val wave: ByteArray = SILENT_WAVE) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> =
            linkedMapOf("_type" to "AUDIO", "token" to token, "duration" to durationMs, "wave" to wave)

        override fun equals(other: Any?): Boolean =
            other is Voice && uploadId == other.uploadId && token == other.token && durationMs == other.durationMs && wave.contentEquals(other.wave)

        override fun hashCode(): Int = (uploadId.hashCode() * 31 + token.hashCode()) * 31 + durationMs.hashCode()

        companion object {
            /** PyMax `upload_voice`: `wave=b"\x00" * 80`. */
            val SILENT_WAVE: ByteArray get() = ByteArray(80)
        }
    }
}

/**
 * An attachment of a received message (`attaches[]`, keyed by `_type`; PyMax
 * `src/pymax/types/domain/attachments/`). Only the ids are required here; every model keeps [raw].
 */
sealed interface Attachment {
    val raw: Map<*, *>

    /** PyMax `PhotoAttachment`: `photoId`, `photoToken`, `baseUrl`, `width`, `height`. */
    data class Photo(val photoId: Long, val photoToken: String?, val baseUrl: String?, val width: Int?, val height: Int?, override val raw: Map<*, *>) : Attachment

    /** PyMax `VideoAttachment`: `videoId`, `token`, `width`, `height`, `duration`, `videoType`, `thumbnail`. */
    data class Video(
        val videoId: Long,
        val token: String?,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val videoType: Int?,
        val thumbnail: String?,
        override val raw: Map<*, *>,
    ) : Attachment

    /** PyMax `FileAttachment`: `fileId`, `name`, `size`, `token`. */
    data class File(val fileId: Long, val name: String?, val size: Long?, val token: String?, override val raw: Map<*, *>) : Attachment

    /** PyMax `AudioAttachment` (all optional there): `audioId`, `duration`, `url`, `token`, `wave`, `transcriptionStatus`. */
    data class Audio(
        val audioId: Long?,
        val durationMs: Long?,
        val url: String?,
        val token: String?,
        val transcriptionStatus: String?,
        override val raw: Map<*, *>,
    ) : Attachment

    /** Any other `_type` (STICKER, CONTROL, CONTACT, CALL, SHARE, INLINE_KEYBOARD, POLL, ...) or a malformed entry. */
    data class Unknown(val type: String?, override val raw: Map<*, *>) : Attachment

    companion object {
        fun from(value: Any?): Attachment {
            val m = value as? Map<*, *> ?: return Unknown(null, emptyMap<Any?, Any?>())
            val type = m["_type"] as? String
            fun int(key: String) = m[key].asLong()?.toInt()
            return when (type) {
                "PHOTO" -> m["photoId"].asLong()?.let { Photo(it, m["photoToken"] as? String, m["baseUrl"] as? String, int("width"), int("height"), m) }
                "VIDEO" -> m["videoId"].asLong()?.let {
                    Video(it, m["token"] as? String, int("width"), int("height"), m["duration"].asLong(), int("videoType"), m["thumbnail"] as? String, m)
                }
                "FILE" -> m["fileId"].asLong()?.let { File(it, m["name"] as? String, m["size"].asLong(), m["token"] as? String, m) }
                "AUDIO" -> Audio(m["audioId"].asLong(), m["duration"].asLong(), m["url"] as? String, m["token"] as? String, m["transcriptionStatus"] as? String, m)
                else -> null
            } ?: Unknown(type, m)
        }
    }
}

/** [MaxMessage.attaches] parsed as [Attachment]s. */
val MaxMessage.attachments: List<Attachment> get() = attaches.map(Attachment::from)
