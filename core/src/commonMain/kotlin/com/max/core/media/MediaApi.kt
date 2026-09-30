package com.max.core.media

import com.max.core.api.ClientIdGenerator
import com.max.core.api.MaxMessage
import com.max.core.api.MessagesApi
import com.max.core.api.replyMap
import com.max.core.auth.RequestSink
import com.max.core.epochMillis
import com.max.core.events.MaxEvent
import com.max.core.events.MaxEvents
import com.max.core.protocol.Opcode
import com.max.core.session.SessionMachine
import com.max.core.session.UserAgentInfo
import com.max.core.session.randomHexId
import com.max.core.transport.ServerErrorException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
 * Every upload takes an optional [UploadProgress] (kolibri `ProgressFn`). Videos can also go in
 * parallel resumable chunks ([uploadVideoParallel], kolibri `upload_video`). Stickers:
 * `STICKER_UPLOAD` 81 exists in both opcode tables but neither reference sends it or defines its
 * payload, so there is no sticker upload here.
 *
 * @param http CDN client; defaults to the platform one ([defaultMediaHttp]: OkHttp on JVM /
 *   Android, `NSURLSession` on iOS).
 * @param events typed pushes used for readiness waits (for a session: `MaxEvents(session).all`).
 *   Without it uploads return right after the POST and [sendMessage] does not retry.
 * @param userAgent HTTP User-Agent, normally `UserAgentInfo.httpUserAgent` of the session's
 *   device (sent percent-encoded); defaults to the default Android profile's.
 * @param readyTimeout PyMax waits 60 s for readiness signals.
 * @param boundary multipart boundary generator (kolibri uses a time-based `----KolibriBoundary…`).
 */
class MediaApi(
    private val sink: RequestSink,
    private val http: MediaHttp = defaultMediaHttp(),
    private val userAgent: String = UserAgentInfo().httpUserAgent,
    private val events: Flow<MaxEvent>? = null,
    private val clock: () -> Long = ::epochMillis,
    private val readyTimeout: Duration = 60.seconds,
    private val boundary: () -> String = { "----MaxBoundary" + randomHexId() },
    private val messages: MessagesApi = MessagesApi(sink, clock),
) {
    /**
     * Over [session]: its `request`, its pushes for readiness and
     * `session.config.device.userAgent.httpUserAgent`. Collecting starts per upload, before the
     * POST, so the session must be connected. Use the overload with `cids` to share the session's
     * `MaxApi.cids`, so media and text messages draw `cid`s from one generator.
     */
    constructor(session: SessionMachine, http: MediaHttp = defaultMediaHttp(), clock: () -> Long = ::epochMillis) :
        this(session, http, ClientIdGenerator(clock), clock)

    /** Like the constructor above, drawing `cid`s from [cids] (the session's `MaxApi.cids`). */
    constructor(session: SessionMachine, http: MediaHttp, cids: ClientIdGenerator, clock: () -> Long = ::epochMillis) : this(
        sink = RequestSink { opcode, payload -> session.request(opcode, payload) },
        http = http,
        userAgent = session.config.device.userAgent.httpUserAgent,
        events = MaxEvents(session).all,
        clock = clock,
        messages = MessagesApi(RequestSink { opcode, payload -> session.request(opcode, payload) }, clock, cids),
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

    /** Video-note slot: `VIDEO_UPLOAD` 82 with `type = 1, uploaderType = 1` (PyMax `upload_video` for `VideoNote`). */
    suspend fun requestVideoNoteUpload(): UploadSlot =
        UploadSlot.from(
            replyMap(sink.request(Opcode.VIDEO_UPLOAD, uploadPayload(type = 1, uploaderType = 1)), Opcode.VIDEO_UPLOAD),
            Opcode.VIDEO_UPLOAD,
            "videoId",
        )

    // ---- uploads ------------------------------------------------------------------------------
    //
    // Every upload has a ByteArray overload and an UploadSource overload (bodies are streamed from
    // the source; see [UploadSource] and [fileUploadSource]) and a path overload that opens and
    // closes the file.

    /**
     * Uploads a photo: slot → multipart POST (`file` part, [fileName], content type by extension)
     * → CDN JSON `{photos: {<photoId>: {token}}}` → [OutgoingAttachment.Photo]. PyMax names the
     * part `image.<ext>`; pass such a name to match it. [progress] counts multipart body bytes
     * (kolibri `upload_photo` reports against the whole body).
     */
    suspend fun uploadPhoto(
        bytes: ByteArray,
        fileName: String = "image.jpg",
        profile: Boolean = false,
        progress: UploadProgress? = null,
    ): OutgoingAttachment.Photo = uploadPhoto(ByteArrayUploadSource(bytes), fileName, profile, progress)

    /** [uploadPhoto] with the image streamed from [source]. */
    suspend fun uploadPhoto(
        source: UploadSource,
        fileName: String = "image.jpg",
        profile: Boolean = false,
        progress: UploadProgress? = null,
    ): OutgoingAttachment.Photo {
        val slot = requestPhotoUpload(profile)
        val b = boundary()
        val body = UploadRequests.multipartBody(b, fileName, UploadRequests.imageContentType(fileName), source)
        val response = post(slot.url, UploadRequests.multipartHeaders(b, body.contentLength, userAgent), body, "photo", progress)
        val token = ((jsonReply(response, "photo")["photos"] as? Map<*, *>)?.get(slot.photoId) as? Map<*, *>)?.get("token") as? String
            ?: throw UploadException("photo upload reply has no token for photoId=${slot.photoId}", response.status)
        return OutgoingAttachment.Photo(token)
    }

    /** [uploadPhoto] from the file at [path] (part name = the file name). */
    suspend fun uploadPhoto(path: String, profile: Boolean = false, progress: UploadProgress? = null): OutgoingAttachment.Photo =
        fileUploadSource(path).use { uploadPhoto(it, fileNameOf(path), profile, progress) }

    /** Uploads a file: slot → single POST → waits for `NOTIF_ATTACH {fileId}` → [OutgoingAttachment.File]. */
    suspend fun uploadFile(bytes: ByteArray, fileName: String, progress: UploadProgress? = null): OutgoingAttachment.File =
        uploadFile(ByteArrayUploadSource(bytes), fileName, progress)

    /** [uploadFile] streamed from [source]. */
    suspend fun uploadFile(source: UploadSource, fileName: String, progress: UploadProgress? = null): OutgoingAttachment.File {
        val slot = requestFileUpload()
        awaitReady(slot.id, MaxEvent.AttachmentReady.Kind.FILE, "file") {
            post(slot.url, UploadRequests.singlePostHeaders(fileName, source.size, userAgent), UploadBody.of(source), "file", progress)
        }
        return OutgoingAttachment.File(slot.id)
    }

    /** [uploadFile] from the file at [path], streamed from disk. */
    suspend fun uploadFile(path: String, progress: UploadProgress? = null): OutgoingAttachment.File =
        fileUploadSource(path).use { uploadFile(it, fileNameOf(path), progress) }

    /**
     * Uploads a video in one POST (PyMax `upload_video`): slot → single POST → waits for
     * `NOTIF_ATTACH {videoId}` → [OutgoingAttachment.Video]. See [uploadVideoParallel] for the
     * chunked, resumable variant.
     */
    suspend fun uploadVideo(bytes: ByteArray, fileName: String, progress: UploadProgress? = null): OutgoingAttachment.Video =
        uploadVideo(ByteArrayUploadSource(bytes), fileName, progress)

    /** [uploadVideo] streamed from [source]. */
    suspend fun uploadVideo(source: UploadSource, fileName: String, progress: UploadProgress? = null): OutgoingAttachment.Video {
        val slot = requestVideoUpload()
        awaitReady(slot.id, MaxEvent.AttachmentReady.Kind.VIDEO, "video") {
            post(slot.url, UploadRequests.singlePostHeaders(fileName, source.size, userAgent), UploadBody.of(source), "video", progress)
        }
        return OutgoingAttachment.Video(slot.id, slot.token)
    }

    /** [uploadVideo] from the file at [path], streamed from disk. */
    suspend fun uploadVideo(path: String, progress: UploadProgress? = null): OutgoingAttachment.Video =
        fileUploadSource(path).use { uploadVideo(it, fileNameOf(path), progress) }

    /**
     * Uploads a video in parallel chunks with resume: `VIDEO_UPLOAD` slot (PyMax) → kolibri
     * `upload_video` against the slot URL ([uploadChunked]) → waits for `NOTIF_ATTACH {videoId}`
     * → [OutgoingAttachment.Video]. Defaults are kolibri-py's (`chunk_size = 2 MiB`,
     * `concurrency = 4`, `kolibri-py/src/lib.rs`). kolibri only has the HTTP half (it takes a
     * ready URL); that the `VIDEO_UPLOAD` URL accepts this mode is its documented use
     * ("resumable parallel-chunk video", `kolibri-net/src/media/mod.rs`), not verified live.
     */
    suspend fun uploadVideoParallel(
        bytes: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        concurrency: Int = DEFAULT_CONCURRENCY,
        progress: UploadProgress? = null,
    ): OutgoingAttachment.Video = uploadVideoParallel(ByteArrayUploadSource(bytes), chunkSize, concurrency, progress)

    /** [uploadVideoParallel] reading each chunk from [source] (at most `concurrency × chunkSize` in memory). */
    suspend fun uploadVideoParallel(
        source: UploadSource,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        concurrency: Int = DEFAULT_CONCURRENCY,
        progress: UploadProgress? = null,
    ): OutgoingAttachment.Video {
        val slot = requestVideoUpload()
        awaitReady(slot.id, MaxEvent.AttachmentReady.Kind.VIDEO, "video") {
            uploadChunked(slot.url, source, chunkSize, concurrency, progress)
        }
        return OutgoingAttachment.Video(slot.id, slot.token)
    }

    /** [uploadVideoParallel] from the file at [path]. */
    suspend fun uploadVideoParallel(
        path: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        concurrency: Int = DEFAULT_CONCURRENCY,
        progress: UploadProgress? = null,
    ): OutgoingAttachment.Video = fileUploadSource(path).use { uploadVideoParallel(it, chunkSize, concurrency, progress) }

    /** [uploadChunked] over an in-memory array. */
    suspend fun uploadChunked(
        url: String,
        bytes: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        concurrency: Int = DEFAULT_CONCURRENCY,
        progress: UploadProgress? = null,
        uploadName: String = defaultUploadName(),
    ): Long = uploadChunked(url, ByteArrayUploadSource(bytes), chunkSize, concurrency, progress, uploadName)

    /**
     * Parallel resumable chunk upload to [url] (kolibri `kolibri-net/src/media/upload.rs`
     * `upload_video` + `ok_cdn_request`):
     * 1. `GET` with [UploadRequests.parallelChunkHeaders] (empty body, no range); status must be
     *    200, the trimmed body parsed as a number is the resume offset (used when `<= size`).
     * 2. The rest is split into [chunkSize] ranges; up to [concurrency] workers `POST` them with
     *    `Content-Range: bytes <start>-<end-1>/<size>`; 200 and 201 are accepted. Each chunk body
     *    is streamed from [source].
     * 3. [progress] gets `(offset + bytes done, size)` after every chunk, in increasing order
     *    (kolibri reports from each worker without ordering).
     *
     * The same [uploadName] (kolibri: current microseconds `& 0x7FFFFFFF`) goes into every
     * request's `Content-Disposition`. kolibri returns `false` on failure; this throws
     * [UploadException] (empty data, handshake status, chunk status or I/O error) and cancels the
     * other workers. Returns the resume offset the server reported.
     */
    suspend fun uploadChunked(
        url: String,
        source: UploadSource,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        concurrency: Int = DEFAULT_CONCURRENCY,
        progress: UploadProgress? = null,
        uploadName: String = defaultUploadName(),
    ): Long {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val total = source.size
        if (total == 0L) throw UploadException("nothing to upload")
        val handshake = send("GET", url, UploadRequests.parallelChunkHeaders(uploadName, 0, null), UploadBody.EMPTY, null, "video")
        if (handshake.status != 200) throw UploadException("video upload handshake failed with status ${handshake.status}", handshake.status)
        val resumed = handshake.text.trim().toLongOrNull()?.takeIf { it in 0..total } ?: 0L
        val ranges = ArrayList<Pair<Long, Long>>()
        var o = resumed
        while (o < total) {
            val end = minOf(o + chunkSize, total)
            ranges += o to end
            o = end
        }
        if (ranges.isEmpty()) return resumed
        val lock = Mutex()
        var next = 0
        var sent = resumed
        coroutineScope {
            repeat(concurrency.coerceAtLeast(1).coerceAtMost(ranges.size)) {
                launch {
                    while (true) {
                        val i = lock.withLock { next++ }
                        if (i >= ranges.size) break
                        val (start, end) = ranges[i]
                        val range = "bytes $start-${end - 1}/$total"
                        val length = (end - start).toInt()
                        val body = UploadBody.range(source, start, length.toLong())
                        val resp = send("POST", url, UploadRequests.parallelChunkHeaders(uploadName, length, range), body, null, "video")
                        if (resp.status != 200 && resp.status != 201) {
                            throw UploadException("video chunk $range failed with status ${resp.status}", resp.status)
                        }
                        // reported under the lock so values arrive in increasing order across workers
                        lock.withLock {
                            sent += length
                            progress?.onProgress(sent, total)
                        }
                    }
                }
            }
        }
        return resumed
    }

    /**
     * Uploads a voice / audio message: voice slot → single POST → [OutgoingAttachment.Voice]
     * right away (PyMax does not wait here; [sendMessage] waits for `NOTIF_ATTACH {audioId}` on
     * `attachment.not.ready`). [durationMs] in milliseconds. This is the only audio path in the
     * references (`AUDIO_PLAY` 301 is declared but unused in both).
     */
    suspend fun uploadVoice(bytes: ByteArray, fileName: String, durationMs: Long, progress: UploadProgress? = null): OutgoingAttachment.Voice =
        uploadVoice(ByteArrayUploadSource(bytes), fileName, durationMs, progress)

    /** [uploadVoice] streamed from [source]. */
    suspend fun uploadVoice(source: UploadSource, fileName: String, durationMs: Long, progress: UploadProgress? = null): OutgoingAttachment.Voice {
        val slot = requestVoiceUpload()
        val response = post(slot.url, UploadRequests.recordingHeaders(defaultUploadName(), source.size, userAgent), UploadBody.of(source), "voice", progress)
        rejectCdnError(response, "voice")
        return OutgoingAttachment.Voice(slot.id, slot.token, durationMs)
    }

    /**
     * Uploads a round video note (PyMax `upload_video` with `VideoNote`): video-note slot
     * (`type = 1, uploaderType = 1`) → single POST → CDN JSON; its optional `thumbhash` (base64,
     * padded with `=` as PyMax does) goes into [OutgoingAttachment.VideoNote]. No readiness wait
     * here (PyMax registers no waiter for notes); [sendMessage] waits for
     * `NOTIF_ATTACH {videoId}` on `attachment.not.ready`. [durationMs] as PyMax `get_duration()`.
     */
    suspend fun uploadVideoNote(
        bytes: ByteArray,
        fileName: String,
        durationMs: Long? = null,
        progress: UploadProgress? = null,
    ): OutgoingAttachment.VideoNote = uploadVideoNote(ByteArrayUploadSource(bytes), fileName, durationMs, progress)

    /** [uploadVideoNote] streamed from [source]. */
    suspend fun uploadVideoNote(
        source: UploadSource,
        fileName: String,
        durationMs: Long? = null,
        progress: UploadProgress? = null,
    ): OutgoingAttachment.VideoNote {
        val slot = requestVideoNoteUpload()
        val response = post(slot.url, UploadRequests.recordingHeaders(defaultUploadName(), source.size, userAgent), UploadBody.of(source), "video note", progress)
        rejectCdnError(response, "video note")
        val thumb = (if (response.body.isEmpty()) emptyMap<Any?, Any?>() else jsonReply(response, "video note"))["thumbhash"]
        val thumbhash = when (thumb) {
            null -> null
            is String -> if (thumb.isEmpty()) null else decodeThumbhash(thumb, response.status)
            else -> throw UploadException("video note thumbhash is not a string", response.status)
        }
        return OutgoingAttachment.VideoNote(slot.id, slot.token, durationMs, thumbhash)
    }

    // ---- several files --------------------------------------------------------------------------

    /** Uploads [source] with the slot of [kind] ([uploadPhoto], [uploadVideo] or [uploadFile]). */
    suspend fun upload(kind: OutgoingMedia.Kind, source: UploadSource, fileName: String, progress: UploadProgress? = null): OutgoingAttachment =
        when (kind) {
            OutgoingMedia.Kind.PHOTO -> uploadPhoto(source, fileName, progress = progress)
            OutgoingMedia.Kind.VIDEO -> uploadVideo(source, fileName, progress)
            OutgoingMedia.Kind.FILE -> uploadFile(source, fileName, progress)
        }

    /**
     * Uploads [items] one after another, in order, and returns their attachments in the same
     * order. [progress] gets `(bytes done, bytes in all)` over the whole batch: the file sizes
     * are summed first, and each upload's own progress is scaled to its file's share (a photo
     * reports its multipart body, a little larger than the file). The first failure stops the
     * batch and is rethrown; cancelling the caller stops the current upload. [open] opens a path
     * ([fileUploadSource] by default); every opened source is closed at the end.
     */
    suspend fun uploadAll(
        items: List<OutgoingMedia>,
        progress: UploadProgress? = null,
        open: (String) -> UploadSource = ::fileUploadSource,
    ): List<OutgoingAttachment> {
        val sources = ArrayList<UploadSource>(items.size)
        try {
            items.forEach { sources += open(it.path) }
            val sizes = sources.map { it.size.coerceAtLeast(0) }
            val total = sizes.sum()
            var done = 0L
            return items.mapIndexed { i, item ->
                val size = sizes[i]
                val base = done
                val scaled = progress?.let { p ->
                    UploadProgress { sent, all ->
                        val part = if (all > 0) (sent.coerceIn(0, all).toDouble() / all * size).toLong() else 0L
                        p.onProgress(base + part, total)
                    }
                }
                val attachment = upload(item.kind, sources[i], item.fileName?.takeIf { it.isNotBlank() } ?: fileNameOf(item.path), scaled)
                done += size
                progress?.onProgress(done, total)
                attachment
            }
        } finally {
            sources.forEach { runCatching { it.close() } }
        }
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
     * out when `null`. On the `attachment.not.ready` error PyMax (`_process_attachment_error`)
     * waits for the readiness signal of the first voice or video-note attachment — voice:
     * `NOTIF_ATTACH {audioId}`, video note: `{videoId}` — and resends the same frame once; so does
     * this method (signals are collected from before the first send). Without such an attachment
     * or without [events] the error is rethrown; a missing signal throws [UploadException].
     *
     * Photos, files and videos have no signal to wait for here. With [notReadyAttempts] above 1
     * the same frame is sent again every [notReadyDelay] while the server answers
     * `attachment.not.ready`, at most [notReadyAttempts] times in all (Komet retries photos and
     * files 20 times and videos 30 times, a second apart); the last error is rethrown. The
     * default 1 keeps PyMax's behaviour.
     */
    suspend fun sendMessage(
        chatId: Long,
        attachments: List<OutgoingAttachment>,
        text: String? = null,
        replyTo: Long? = null,
        notify: Boolean = true,
        elements: List<Map<String, Any?>> = emptyList(),
        notReadyAttempts: Int = 1,
        notReadyDelay: Duration = 1.seconds,
    ): MaxMessage {
        val payload = messages.sendMessagePayload(chatId, text, messages.nextCid(), replyTo, notify, elements, attaches(attachments))
        val pending = attachments.firstNotNullOfOrNull {
            when (it) {
                is OutgoingAttachment.Voice -> MaxEvent.AttachmentReady.Kind.AUDIO to it.uploadId
                is OutgoingAttachment.VideoNote -> MaxEvent.AttachmentReady.Kind.VIDEO to it.uploadId
                else -> null
            }
        }
        val events = events
        if (pending == null || events == null) return sendUntilReady(chatId, payload, notReadyAttempts, notReadyDelay)
        return coroutineScope {
            val seen = MutableStateFlow(emptySet<Pair<MaxEvent.AttachmentReady.Kind, Long>>())
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                events.filterIsInstance<MaxEvent.AttachmentReady>().collect { e -> seen.value = seen.value + (e.kind to e.id) }
            }
            try {
                sendWhenReady(chatId, payload, pending, seen, notReadyDelay)
            } finally {
                collector.cancel()
            }
        }
    }

    /**
     * `MSG_SEND` of a voice or note [payload]. While the server answers `attachment.not.ready`
     * (or `errors.process.attachment.video.not.ready`, its key for voice and notes) the frame
     * goes again after the readiness push for [pending] or [notReadyDelay], whichever comes
     * first, for [readyTimeout] at most; then [UploadException].
     */
    private suspend fun sendWhenReady(
        chatId: Long,
        payload: Map<String, Any?>,
        pending: Pair<MaxEvent.AttachmentReady.Kind, Long>,
        seen: kotlinx.coroutines.flow.StateFlow<Set<Pair<MaxEvent.AttachmentReady.Kind, Long>>>,
        notReadyDelay: Duration = 1.seconds,
    ): MaxMessage {
        val attempts = maxOf(1, (readyTimeout / notReadyDelay).toInt())
        var attempt = 1
        while (true) {
            try {
                return messages.sendPrepared(chatId, payload)
            } catch (e: ServerErrorException) {
                if (!isNotReady(e.errorKey)) throw e
                if (attempt >= attempts) {
                    throw UploadException("timed out waiting for attachment processing id=${pending.second}", cause = e)
                }
            }
            attempt++
            withTimeoutOrNull(notReadyDelay) { seen.first { pending in it } }
        }
    }

    /** `MSG_SEND` of [payload], repeated after [pause] on `attachment.not.ready`, [attempts] times at most. */
    private suspend fun sendUntilReady(chatId: Long, payload: Map<String, Any?>, attempts: Int, pause: Duration): MaxMessage {
        var attempt = 1
        while (true) {
            try {
                return messages.sendPrepared(chatId, payload)
            } catch (e: ServerErrorException) {
                if (!isNotReady(e.errorKey) || attempt >= attempts) throw e
            }
            attempt++
            delay(pause)
        }
    }

    /** A request through [http]; I/O failures become [UploadException]. */
    private suspend fun send(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: UploadBody,
        progress: UploadProgress?,
        what: String,
    ): HttpResponse = try {
        http.upload(method, url, headers, body, progress)
    } catch (e: CancellationException) {
        throw e
    } catch (e: UploadException) {
        throw e
    } catch (e: Exception) {
        throw UploadException("HTTP error during $what upload: ${e.message}", cause = e)
    }

    /**
     * Single POST that must answer 200 (PyMax `HTTPStatus.OK`). [progress] is handed to [http];
     * if it never reported the full size, `(size, size)` is reported after the 200.
     */
    private suspend fun post(url: String, headers: List<Pair<String, String>>, body: UploadBody, what: String, progress: UploadProgress?): HttpResponse {
        var last = -1L
        val tracking = progress?.let { p -> UploadProgress { sent, total -> last = sent; p.onProgress(sent, total) } }
        val response = send("POST", url, headers, body, tracking, what)
        if (response.status != 200) throw UploadException("$what upload failed with status ${response.status}", response.status)
        if (progress != null && last < body.contentLength) progress.onProgress(body.contentLength, body.contentLength)
        return response
    }

    /**
     * The CDN answers 200 even when it rejects an upload and puts `error_code` / `error_msg`
     * into the body (Komet checks the same); such a reply becomes an [UploadException] carrying
     * the start of the body, so the reason reaches the logs.
     */
    private fun rejectCdnError(response: HttpResponse, what: String) {
        val text = response.text
        if ("error_code" in text || "error_msg" in text) {
            throw UploadException("$what upload rejected by CDN: ${text.take(300)}", response.status)
        }
    }

    private fun jsonReply(response: HttpResponse, what: String): Map<*, *> {
        val json = try {
            MiniJson.parse(response.text)
        } catch (e: IllegalArgumentException) {
            throw UploadException("$what upload reply is not JSON", response.status, e)
        }
        return json as? Map<*, *> ?: throw UploadException("$what upload reply is not a JSON object", response.status)
    }

    /**
     * Subscribes to `NOTIF_ATTACH` for [id] before [upload] (like PyMax's waiter future) and waits
     * [readyTimeout] after it. Without [events] only runs [upload].
     */
    private suspend fun awaitReady(id: Long, kind: MaxEvent.AttachmentReady.Kind, what: String, upload: suspend () -> Unit) {
        val events = events
        if (events == null) {
            upload()
            return
        }
        coroutineScope {
            val ready = async(start = CoroutineStart.UNDISPATCHED) {
                events.filterIsInstance<MaxEvent.AttachmentReady>().first { it.kind == kind && it.id == id }
            }
            try {
                upload()
                withTimeoutOrNull(readyTimeout) { ready.await() }
                    ?: throw UploadException("timed out waiting for $what processing id=$id")
            } finally {
                ready.cancel()
            }
        }
    }

    private fun fileNameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "file" }

    /** kolibri `now_micros()`: current microseconds `& 0x7FFFFFFF`, as a decimal string. */
    private fun defaultUploadName(): String = ((clock() * 1000) and 0x7FFF_FFFFL).toString()

    companion object {
        /** kolibri-py `upload_video` default `chunk_size` (2 MiB). */
        const val DEFAULT_CHUNK_SIZE: Int = 2 * 1024 * 1024

        /** kolibri-py `upload_video` default `concurrency`. */
        const val DEFAULT_CONCURRENCY: Int = 4

        /** `MSG_SEND` error key for an attachment still being processed (PyMax). */
        const val ATTACHMENT_NOT_READY: String = "attachment.not.ready"

        /**
         * A "still processing" error: `attachment.not.ready`, and the longer keys the server uses
         * for some kinds (`errors.process.attachment.video.not.ready` for voice and video notes).
         * Komet treats every key with `not.ready` this way.
         */
        fun isNotReady(errorKey: String?): Boolean = errorKey != null && errorKey.contains("not.ready")

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

/** PyMax: `thumbhash += "=" * (-len(thumbhash) % 4); base64.b64decode(thumbhash)`. */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeThumbhash(value: String, status: Int? = null): ByteArray {
    val padded = value + "=".repeat((4 - value.length % 4) % 4)
    return try {
        Base64.Default.decode(padded)
    } catch (e: IllegalArgumentException) {
        throw UploadException("video note thumbhash is not base64", status, e)
    }
}
