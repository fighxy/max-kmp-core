package com.max.core.media

import com.max.core.api.MaxMessage
import com.max.core.api.MessagesApi
import com.max.core.api.replyMap
import com.max.core.auth.RequestSink
import com.max.core.epochMillis
import com.max.core.events.MaxEvent
import com.max.core.events.MaxEvents
import com.max.core.protocol.Opcode
import com.max.core.session.SessionMachine
import com.max.core.session.randomHexId
import com.max.core.transport.ServerErrorException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Media uploads, download links and messages with attachments.
 *
 * Control-plane requests follow PyMax `src/pymax/api/uploads/service.py` (`UploadService`) and
 * `api/messages/service.py`; the CDN request shapes follow kolibri
 * `kolibri-net/src/media/upload.rs` (see [UploadRequests]). kolibri has no control-plane media
 * code, only the same opcode numbers.
 *
 * Upload flow (PyMax): request a slot over the socket → POST the bytes to the slot URL through
 * [http] → for files and videos wait for `NOTIF_ATTACH` 136 ([MaxEvent.AttachmentReady]) → put
 * the resulting [OutgoingAttachment] into [sendMessage].
 *
 * @param events typed pushes used for readiness waits (for a session: `MaxEvents(session).all`).
 *   Without it uploads return right after the POST and [sendMessage] does not retry.
 * @param userAgent HTTP User-Agent, normally `UserAgentInfo.httpUserAgent` (sent percent-encoded).
 * @param readyTimeout PyMax waits 60 s for readiness signals.
 * @param boundary multipart boundary generator (kolibri uses a time-based `----KolibriBoundary…`).
 */
class MediaApi(
    private val sink: RequestSink,
    private val http: MediaHttp,
    private val userAgent: String,
    private val events: Flow<MaxEvent>? = null,
    clock: () -> Long = ::epochMillis,
    private val readyTimeout: Duration = 60.seconds,
    private val boundary: () -> String = { "----MaxBoundary" + randomHexId() },
    private val messages: MessagesApi = MessagesApi(sink, clock),
) {
    /**
     * Over [session]: its `request`, its pushes for readiness and
     * `session.config.device.userAgent.httpUserAgent`. Collecting starts per upload, before the
     * POST, so the session must be connected.
     */
    constructor(session: SessionMachine, http: MediaHttp, clock: () -> Long = ::epochMillis) : this(
        RequestSink { opcode, payload -> session.request(opcode, payload) },
        http,
        session.config.device.userAgent.httpUserAgent,
        MaxEvents(session).all,
        clock,
    )

    // ---- upload slots -------------------------------------------------------------------------

    /** `PHOTO_UPLOAD` 80 with [uploadPayload]`(profile = profile)`; reply `{url}` with `photoIds` in the query. */
    suspend fun requestPhotoUpload(profile: Boolean = false): PhotoUploadSlot =
        PhotoUploadSlot.from(replyMap(sink.request(Opcode.PHOTO_UPLOAD, uploadPayload(profile = profile)), Opcode.PHOTO_UPLOAD))

    /** `FILE_UPLOAD` 87 with the default [uploadPayload]; reply `info[0] {url, fileId, token}`. */
    suspend fun requestFileUpload(): UploadSlot =
        UploadSlot.from(replyMap(sink.request(Opcode.FILE_UPLOAD, uploadPayload()), Opcode.FILE_UPLOAD), Opcode.FILE_UPLOAD, "fileId")

    /** `VIDEO_UPLOAD` 82 with the default [uploadPayload]; reply `info[0] {url, videoId, token}`. */
    suspend fun requestVideoUpload(): UploadSlot =
        UploadSlot.from(replyMap(sink.request(Opcode.VIDEO_UPLOAD, uploadPayload()), Opcode.VIDEO_UPLOAD), Opcode.VIDEO_UPLOAD, "videoId")

    /** Voice slot: `VIDEO_UPLOAD` 82 with `type = 2, uploaderType = 1` (PyMax `upload_voice`). */
    suspend fun requestVoiceUpload(): UploadSlot =
        UploadSlot.from(
            replyMap(sink.request(Opcode.VIDEO_UPLOAD, uploadPayload(type = 2, uploaderType = 1)), Opcode.VIDEO_UPLOAD),
            Opcode.VIDEO_UPLOAD,
            "videoId",
        )

    // ---- uploads ------------------------------------------------------------------------------

    /**
     * Uploads a photo: slot → multipart POST (`file` part, [fileName], content type by extension)
     * → CDN JSON `{photos: {<photoId>: {token}}}` → [OutgoingAttachment.Photo]. PyMax names the
     * part `image.<ext>`; pass such a name to match it.
     */
    suspend fun uploadPhoto(bytes: ByteArray, fileName: String = "image.jpg", profile: Boolean = false): OutgoingAttachment.Photo {
        val slot = requestPhotoUpload(profile)
        val b = boundary()
        val body = UploadRequests.multipartBody(b, fileName, UploadRequests.imageContentType(fileName), bytes)
        val response = post(slot.url, UploadRequests.multipartHeaders(b, body.size, userAgent), body, "photo")
        val json = try {
            MiniJson.parse(response.text)
        } catch (e: IllegalArgumentException) {
            throw UploadException("photo upload reply is not JSON", response.status, e)
        }
        val token = (((json as? Map<*, *>)?.get("photos") as? Map<*, *>)?.get(slot.photoId) as? Map<*, *>)?.get("token") as? String
            ?: throw UploadException("photo upload reply has no token for photoId=${slot.photoId}", response.status)
        return OutgoingAttachment.Photo(token)
    }

    /** Uploads a file: slot → single POST → waits for `NOTIF_ATTACH {fileId}` → [OutgoingAttachment.File]. */
    suspend fun uploadFile(bytes: ByteArray, fileName: String): OutgoingAttachment.File {
        val slot = requestFileUpload()
        postAndAwait(slot, bytes, fileName, MaxEvent.AttachmentReady.Kind.FILE, "file")
        return OutgoingAttachment.File(slot.id)
    }

    /** Uploads a video: slot → single POST → waits for `NOTIF_ATTACH {videoId}` → [OutgoingAttachment.Video]. */
    suspend fun uploadVideo(bytes: ByteArray, fileName: String): OutgoingAttachment.Video {
        val slot = requestVideoUpload()
        postAndAwait(slot, bytes, fileName, MaxEvent.AttachmentReady.Kind.VIDEO, "video")
        return OutgoingAttachment.Video(slot.id, slot.token)
    }

    /**
     * Uploads a voice message: voice slot → single POST → [OutgoingAttachment.Voice] right away
     * (PyMax does not wait here; [sendMessage] waits for `NOTIF_ATTACH {audioId}` on
     * `attachment.not.ready`). [durationMs] in milliseconds.
     */
    suspend fun uploadVoice(bytes: ByteArray, fileName: String, durationMs: Long): OutgoingAttachment.Voice {
        val slot = requestVoiceUpload()
        post(slot.url, UploadRequests.singlePostHeaders(fileName, bytes.size, userAgent), bytes, "voice")
        return OutgoingAttachment.Voice(slot.id, slot.token, durationMs)
    }

    // ---- download links -----------------------------------------------------------------------

    /** `VIDEO_PLAY` 83 `{chatId, messageId, videoId}` (PyMax `get_video_by_id`). */
    suspend fun getVideoLink(chatId: Long, messageId: Long, videoId: Long): VideoLink =
        VideoLink.from(replyMap(sink.request(Opcode.VIDEO_PLAY, videoLinkPayload(chatId, messageId, videoId)), Opcode.VIDEO_PLAY))

    /** `FILE_DOWNLOAD` 88 `{chatId, messageId, fileId}` (PyMax `get_file_by_id`). */
    suspend fun getFileLink(chatId: Long, messageId: Long, fileId: Long): FileLink =
        FileLink.from(replyMap(sink.request(Opcode.FILE_DOWNLOAD, fileLinkPayload(chatId, messageId, fileId)), Opcode.FILE_DOWNLOAD))

    // ---- sending ------------------------------------------------------------------------------

    /**
     * `MSG_SEND` 64 with `attaches` (PyMax `send_message(..., attachments=...)`); [text] is left
     * out when `null`. On the `attachment.not.ready` error PyMax waits for the readiness signal
     * of the first voice (or video-note) attachment and resends the same frame once; so does
     * this method for [OutgoingAttachment.Voice] (signals are collected from before the first
     * send). Without a voice attachment or without [events] the error is rethrown; a missing
     * signal throws [UploadException].
     */
    suspend fun sendMessage(
        chatId: Long,
        attachments: List<OutgoingAttachment>,
        text: String? = null,
        replyTo: Long? = null,
        notify: Boolean = true,
        elements: List<Map<String, Any?>> = emptyList(),
    ): MaxMessage {
        val payload = messages.sendMessagePayload(chatId, text, messages.nextCid(), replyTo, notify, elements, attaches(attachments))
        val voice = attachments.firstOrNull { it is OutgoingAttachment.Voice } as OutgoingAttachment.Voice?
        val events = events
        if (voice == null || events == null) return messages.sendPrepared(chatId, payload)
        return coroutineScope {
            val seen = MutableStateFlow(emptySet<Long>())
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                events.filterIsInstance<MaxEvent.AttachmentReady>().collect { e ->
                    if (e.kind == MaxEvent.AttachmentReady.Kind.AUDIO) seen.value = seen.value + e.id
                }
            }
            try {
                try {
                    messages.sendPrepared(chatId, payload)
                } catch (e: ServerErrorException) {
                    if (e.errorKey != ATTACHMENT_NOT_READY) throw e
                    withTimeoutOrNull(readyTimeout) { seen.first { voice.uploadId in it } }
                        ?: throw UploadException("timed out waiting for voice processing id=${voice.uploadId}", cause = e)
                    messages.sendPrepared(chatId, payload)
                }
            } finally {
                collector.cancel()
            }
        }
    }

    private suspend fun post(url: String, headers: List<Pair<String, String>>, body: ByteArray, what: String): HttpResponse {
        val response = try {
            http.post(url, headers, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UploadException("HTTP error during $what upload: ${e.message}", cause = e)
        }
        if (response.status != 200) throw UploadException("$what upload failed with status ${response.status}", response.status)
        return response
    }

    /** Subscribes to readiness before the POST (like PyMax's waiter future), waits after it. */
    private suspend fun postAndAwait(slot: UploadSlot, bytes: ByteArray, fileName: String, kind: MaxEvent.AttachmentReady.Kind, what: String) {
        val events = events
        val headers = UploadRequests.singlePostHeaders(fileName, bytes.size, userAgent)
        if (events == null) {
            post(slot.url, headers, bytes, what)
            return
        }
        coroutineScope {
            val ready = async(start = CoroutineStart.UNDISPATCHED) {
                events.filterIsInstance<MaxEvent.AttachmentReady>().first { it.kind == kind && it.id == slot.id }
            }
            try {
                post(slot.url, headers, bytes, what)
                withTimeoutOrNull(readyTimeout) { ready.await() }
                    ?: throw UploadException("timed out waiting for $what processing id=${slot.id}")
            } finally {
                ready.cancel()
            }
        }
    }

    companion object {
        /** `MSG_SEND` error key for an attachment still being processed (PyMax). */
        const val ATTACHMENT_NOT_READY: String = "attachment.not.ready"

        /** PyMax `UploadPayload`: `{count, type, uploaderType, profile}` (defaults 1, 0, 0, false). */
        fun uploadPayload(type: Int = 0, uploaderType: Int = 0, profile: Boolean = false): Map<String, Any?> =
            linkedMapOf("count" to 1, "type" to type, "uploaderType" to uploaderType, "profile" to profile)

        /** PyMax `GetVideoPayload`. */
        fun videoLinkPayload(chatId: Long, messageId: Long, videoId: Long): Map<String, Any?> =
            linkedMapOf("chatId" to chatId, "messageId" to messageId, "videoId" to videoId)

        /** PyMax `GetFilePayload`. */
        fun fileLinkPayload(chatId: Long, messageId: Long, fileId: Long): Map<String, Any?> =
            linkedMapOf("chatId" to chatId, "messageId" to messageId, "fileId" to fileId)

        /** `message.attaches` for `MSG_SEND` (`MessagesApi.sendMessagePayload`). */
        fun attaches(attachments: List<OutgoingAttachment>): List<Map<String, Any?>> = attachments.map { it.toPayload() }
    }
}
