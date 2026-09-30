package com.max.core.media

import com.max.core.api.MaxMessage
import com.max.core.api.PollAnswer
import com.max.core.api.PollFlag
import com.max.core.api.PollState
import com.max.core.api.asLong

/**
 * An attachment to send in `MSG_SEND` `message.attaches`, produced by an upload (see
 * [MediaApi]). [toPayload] follows PyMax `src/pymax/api/uploads/payloads.py` (`CamelModel`
 * with `_type`, `None` fields left out; `VideoAttachPayload.serialize_attachment` for video /
 * voice / video-note).
 *
 * Audio / voice messages are [Voice] (`_type: AUDIO`); there is no separate audio-upload path in
 * the references — PyMax `upload_voice` uses `VIDEO_UPLOAD` with `type=2, uploaderType=1`.
 * Stickers: incoming only ([Attachment.Sticker]); `STICKER_UPLOAD` 81 and an outgoing sticker
 * attach payload are not implemented in PyMax or kolibri.
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
     * Voice / audio message: `{_type: "AUDIO", token, duration, wave}` (PyMax `VoiceAttachPayload`;
     * its serializer drops `videoId` / `videoType` for AUDIO). [uploadId] is the `videoId` of the
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

    /**
     * Round video note (видеосообщение): PyMax `VideoNoteAttachPayload` (`video_type = 1`).
     * With a [token] the serializer drops `videoId` and keeps `{_type: VIDEO, token, videoType: 1,
     * thumbhash?, duration?}`; [uploadId] is the slot `videoId` (excluded from the payload), used
     * to match `NOTIF_ATTACH {videoId}` on `attachment.not.ready`. [thumbhash] comes from the CDN
     * JSON after the POST (PyMax base64-decodes with `=` padding).
     */
    data class VideoNote(
        val uploadId: Long,
        val token: String,
        val durationMs: Long? = null,
        val thumbhash: ByteArray? = null,
    ) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
            put("_type", "VIDEO")
            put("token", token)
            put("videoType", 1)
            if (thumbhash != null) put("thumbhash", thumbhash)
            if (durationMs != null) put("duration", durationMs)
        }

        override fun equals(other: Any?): Boolean =
            other is VideoNote && uploadId == other.uploadId && token == other.token &&
                durationMs == other.durationMs &&
                ((thumbhash == null && other.thumbhash == null) ||
                    (thumbhash != null && other.thumbhash != null && thumbhash.contentEquals(other.thumbhash)))

        override fun hashCode(): Int =
            ((uploadId.hashCode() * 31 + token.hashCode()) * 31 + (durationMs?.hashCode() ?: 0)) * 31 +
                (thumbhash?.contentHashCode() ?: 0)
    }

    /**
     * A contact card of a MAX user: `{_type: "CONTACT", contactId}` (Komet `sendContactMessage`;
     * PyMax has no outgoing contact). [contactId] is the user id; no upload is needed. The server
     * fills in the name, phone and photo of the received copy.
     */
    data class Contact(val contactId: Long) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf("_type" to "CONTACT", "contactId" to contactId)
    }

    /**
     * A poll (PyMax `Poll`): `{title, answers: [{text, answerId?}], settings: <PollFlag bits>,
     * _type: "POLL"}`; no upload needed.
     */
    data class Poll(val title: String, val answers: List<PollAnswer>, val flags: Set<PollFlag> = emptySet()) : OutgoingAttachment {
        init {
            require(title.isNotEmpty()) { "poll title must not be empty" }
            require(answers.size >= 2) { "a poll needs at least two answers" }
        }

        override fun toPayload(): Map<String, Any?> = linkedMapOf(
            "title" to title,
            "answers" to answers.map { a ->
                linkedMapOf<String, Any?>("text" to a.text).apply { if (a.answerId != null) put("answerId", a.answerId) }
            },
            "settings" to PollFlag.mask(flags),
            "_type" to "POLL",
        )
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

    /**
     * PyMax `StickerAttachment`: `stickerId`, `url`, `width`, `height`, `stickerType`, `audio`,
     * `time`, optional `setId` / `tags` / `lottieUrl` / `authorType`. Sending stickers and
     * `STICKER_UPLOAD` 81 are not sourced in the references.
     */
    data class Sticker(
        val stickerId: Long,
        val url: String?,
        val width: Int?,
        val height: Int?,
        val stickerType: String?,
        val audio: Boolean?,
        val time: Long?,
        val setId: Long?,
        val tags: List<String>?,
        val lottieUrl: String?,
        val authorType: String?,
        override val raw: Map<*, *>,
    ) : Attachment

    /** PyMax `PollAttachment`: `pollId`, `version`, `state`, `title`, `answers`, `settings` bits. */
    data class Poll(
        val pollId: Long,
        val version: Int?,
        val title: String?,
        val answers: List<PollAnswer>,
        val flags: Set<PollFlag>,
        val state: PollState?,
        override val raw: Map<*, *>,
    ) : Attachment

    /** Any other `_type` (CONTROL, CONTACT, CALL, SHARE, INLINE_KEYBOARD, ...) or a malformed entry. */
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
                "STICKER" -> m["stickerId"].asLong()?.let { id ->
                    val tags = (m["tags"] as? List<*>)?.mapNotNull { it as? String }
                    Sticker(
                        id, m["url"] as? String, int("width"), int("height"), m["stickerType"] as? String,
                        m["audio"] as? Boolean, m["time"].asLong(), m["setId"].asLong(), tags,
                        m["lottieUrl"] as? String, m["authorType"] as? String, m,
                    )
                }
                "POLL" -> m["pollId"].asLong()?.let { id ->
                    val answers = (m["answers"] as? List<*>).orEmpty().mapNotNull { a ->
                        val am = a as? Map<*, *> ?: return@mapNotNull null
                        PollAnswer(am["text"] as? String ?: return@mapNotNull null, am["answerId"].asLong())
                    }
                    Poll(id, int("version"), m["title"] as? String, answers, PollFlag.of(int("settings") ?: 0), PollState.from(m["state"]), m)
                }
                else -> null
            } ?: Unknown(type, m)
        }
    }
}

/** [MaxMessage.attaches] parsed as [Attachment]s. */
val MaxMessage.attachments: List<Attachment> get() = attaches.map(Attachment::from)
