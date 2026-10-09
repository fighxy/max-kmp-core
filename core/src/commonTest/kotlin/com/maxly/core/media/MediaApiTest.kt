@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.core.media

import com.maxly.core.ErrorKind
import com.maxly.core.api.MaxMessage
import com.maxly.core.api.MalformedReplyException
import com.maxly.core.auth.AuthApi
import com.maxly.core.auth.RequestSink
import com.maxly.core.events.EventParser
import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.CmdType
import com.maxly.core.protocol.DefaultMessagePackCodec
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.PROTOCOL_VERSION
import com.maxly.core.protocol.PacketHeader
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.session.DeviceInfo
import com.maxly.core.session.SessionConfig
import com.maxly.core.session.SessionMachine
import com.maxly.core.transport.FakeRawConnection
import com.maxly.core.transport.ScriptedConnectionFactory
import com.maxly.core.transport.ServerErrorException
import com.maxly.core.transport.TransportConfig
import com.maxly.core.transport.TransportPacket
import com.maxly.core.transport.ok
import com.maxly.core.toMaxError
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Expected request bytes were produced by PyMax's own models (`pymax.api.uploads.payloads`:
 * `UploadPayload`, `AttachPhotoPayload`, `AttachFilePayload`, `VideoAttachPayload`,
 * `VoiceAttachPayload`; `pymax.api.messages.payloads`: `GetVideoPayload`, `GetFilePayload`,
 * `SendMessagePayload`) with `.to_payload()` and msgpack-python; the push frames were checked
 * with PyMax's `EventResolver` (`file_ready`, `video_ready`, `voice_ready`).
 */
class MediaApiTest {

    private val now = 1759100000000L
    private val clock = { now }
    private val ua = "OKMessages/26.25.0 (Android 14; Pixel 8; 428dpi 428dpi 1080x2400)"
    private val uaEncoded = "OKMessages%2F26.25.0%20(Android%2014%3B%20Pixel%208%3B%20428dpi%20428dpi%201080x2400)"

    private val pymax = mapOf(
        "uploadDefault" to "84a5636f756e7401a47479706500ac75706c6f616465725479706500a770726f66696c65c2",
        "uploadVoice" to "84a5636f756e7401a47479706502ac75706c6f616465725479706501a770726f66696c65c2",
        "attachPhoto" to "82a55f74797065a550484f544faa70686f746f546f6b656ea870682d746f6b656e",
        "attachFile" to "82a55f74797065a446494c45a666696c6549641e",
        "attachVideo" to "84a55f74797065a5564944454fa7766964656f496414a5746f6b656eab766964656f2d746f6b656ea9766964656f5479706500",
        "attachVoice" to "84a55f74797065a5415544494fa5746f6b656eab766f6963652d746f6b656ea86475726174696f6ecd0daca477617665c450" + "00".repeat(80),
        "getVideo" to "83a663686174496464a96d65737361676549640aa7766964656f496414",
        "getFile" to "83a663686174496464a96d65737361676549640aa666696c6549641e",
        "sendPhoto" to "83a663686174496464a76d65737361676583a3636964cf000001999287d701a8656c656d656e747390a861747461636865739182a55f74797065a550484f544faa70686f746f546f6b656ea870682d746f6b656ea66e6f74696679c3",
        "uploadVideoNote" to "84a5636f756e7401a47479706501ac75706c6f616465725479706501a770726f66696c65c2",
        "videoNote" to "85a55f74797065a5564944454fa5746f6b656ea8766e2d746f6b656ea9766964656f5479706501a97468756d6268617368c4020102a86475726174696f6ecd0dac",
        "videoNoteNoThumb" to "84a55f74797065a5564944454fa5746f6b656ea8766e2d746f6b656ea9766964656f5479706501a86475726174696f6ecd0dac",
        "videoNoteNoDuration" to "84a55f74797065a5564944454fa5746f6b656ea8766e2d746f6b656ea9766964656f5479706501a97468756d6268617368c4020102",
        "sendPhotoText" to "83a663686174496464a76d65737361676584a474657874a46c6f6f6ba3636964cf000001999287d701a8656c656d656e747390a861747461636865739282a55f74797065a550484f544faa70686f746f546f6b656ea870682d746f6b656e82a55f74797065a446494c45a666696c6549641ea66e6f74696679c3",
    )

    private val frames = mapOf(
        "readyFile" to "0a00000000880000000981a666696c6549641e",
        "readyVideo" to "0a00000000880000000a81a7766964656f496414",
        "readyAudio" to "0a00000000880000000a81a7617564696f496428",
    )

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bytes(payload: Any?) = DefaultMessagePackCodec.encode(payload).hex()
    private fun frameEvent(name: String): MaxEvent =
        decodePayloadPacket(hex(frames.getValue(name))).let { (h, p) -> EventParser.parse(TransportPacket(h, p)) }

    private class FakeSink(vararg replies: Any?) : RequestSink {
        val script = ArrayDeque(replies.toList())
        val sent = ArrayList<Pair<Opcode, Any?>>()
        var onRequest: suspend (Opcode) -> Unit = {}
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
            sent += opcode to payload
            onRequest(opcode)
            val next = if (script.isEmpty()) emptyMap<String, Any?>() else script.removeFirst()
            if (next is Throwable) throw next
            return TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false), next)
        }
    }

    private class Post(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: ByteArray)

    private class FakeHttp(var status: Int = 200, var reply: String = "", var failure: Exception? = null) : MediaHttp {
        val posts = ArrayList<Post>()
        var onPost: suspend () -> Unit = {}
        var reportEvery: Int = 0
        override suspend fun request(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray, progress: UploadProgress?): HttpResponse {
            posts += Post(method, url, headers, body)
            failure?.let { throw it }
            if (reportEvery > 0 && progress != null) {
                var sent = 0
                while (sent < body.size) { sent = minOf(sent + reportEvery, body.size); progress.onProgress(sent.toLong(), body.size.toLong()) }
            }
            onPost()
            return HttpResponse(status, reply.encodeToByteArray())
        }
    }

    /** Fake CDN for the parallel video upload: answers the GET with [resume], records chunks. */
    private class ChunkCdn(val resume: String = "0", val handshakeStatus: Int = 200, val failRange: String? = null) : MediaHttp {
        val requests = ArrayList<Post>()
        val received = HashMap<String, ByteArray>()
        var maxInFlight = 0
        private var inFlight = 0
        override suspend fun request(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray, progress: UploadProgress?): HttpResponse {
            requests += Post(method, url, headers, body)
            if (method == "GET") return HttpResponse(handshakeStatus, resume.encodeToByteArray())
            val range = headers.toMap().getValue("Content-Range")
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            kotlinx.coroutines.yield()
            inFlight--
            if (range == failRange) return HttpResponse(500, ByteArray(0))
            received[range] = body
            return HttpResponse(if (received.size % 2 == 0) 201 else 200, ByteArray(0))
        }
    }

    private fun serverError(opcode: Opcode, error: String) = ServerErrorException.from(
        TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.ERROR.value, 1, opcode.value.toShort(), 0, false), mapOf("error" to error, "message" to error)),
    )

    private val photoUrl = "https://iu.test/upload.do?apiToken=abc&photoIds=Xy%3D1&id=1"
    private fun sentMessage(id: Long) = mapOf("chatId" to 100, "message" to mapOf("id" to id, "time" to now, "type" to "USER", "cid" to now + 1))

    // --- payload bytes ---

    @Test
    fun payloadBytesMatchPyMax() {
        assertEquals(pymax["uploadDefault"], bytes(MediaApi.uploadPayload()))
        assertEquals(pymax["uploadVoice"], bytes(MediaApi.uploadPayload(type = 2, uploaderType = 1)))
        assertEquals(pymax["attachPhoto"], bytes(OutgoingAttachment.Photo("ph-token").toPayload()))
        assertEquals(pymax["attachFile"], bytes(OutgoingAttachment.File(30).toPayload()))
        assertEquals(pymax["attachVideo"], bytes(OutgoingAttachment.Video(20, "video-token").toPayload()))
        assertEquals(pymax["attachVoice"], bytes(OutgoingAttachment.Voice(40, "voice-token", 3500).toPayload()))
        assertEquals(pymax["getVideo"], bytes(MediaApi.videoLinkPayload(100, 10, 20)))
        assertEquals(pymax["getFile"], bytes(MediaApi.fileLinkPayload(100, 10, 30)))
    }

    @Test
    fun readinessPushesAreTypedEvents() {
        assertEquals(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.FILE, 30, 136, mapOf("fileId" to 30)), frameEvent("readyFile"))
        assertEquals(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.VIDEO, 20, 136, mapOf("videoId" to 20)), frameEvent("readyVideo"))
        assertEquals(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.AUDIO, 40, 136, mapOf("audioId" to 40)), frameEvent("readyAudio"))
        // PyMax checks fileId before videoId
        assertEquals(MaxEvent.AttachmentReady.Kind.FILE, (EventParser.parse(136, 0, mapOf("videoId" to 1, "fileId" to 2)) as MaxEvent.AttachmentReady).kind)
    }

    // --- photo ---

    @Test
    fun photoUploadFlow() = runTest {
        val sink = FakeSink(mapOf("url" to photoUrl))
        val http = FakeHttp(reply = """{"photos": {"Xy=1": {"token": "ph-token"}, "other": {"token": "x"}}}""")
        val api = MediaApi(sink, http, ua, clock = clock, boundary = { "----B" })
        val photo = api.uploadPhoto(byteArrayOf(1, 2, 3), "image.png")
        assertEquals(OutgoingAttachment.Photo("ph-token"), photo)
        assertEquals(Opcode.PHOTO_UPLOAD, sink.sent.single().first)
        assertEquals(pymax["uploadDefault"], bytes(sink.sent.single().second))

        val post = http.posts.single()
        assertEquals(photoUrl, post.url)
        val expectedBody = "------B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n".encodeToByteArray() +
            byteArrayOf(1, 2, 3) + "\r\n------B--\r\n".encodeToByteArray()
        assertEquals(expectedBody.hex(), post.body.hex())
        assertEquals(
            listOf(
                "Content-Type" to "multipart/form-data; boundary=----B",
                "Content-Length" to expectedBody.size.toString(),
                "Connection" to "keep-alive",
                "User-Agent" to uaEncoded,
            ),
            post.headers,
        )
    }

    // --- stories ---

    @Test
    fun storyUploadsUseTheirSlotsAndReturnTokens() = runTest {
        val sink = FakeSink(
            mapOf("url" to photoUrl),
            mapOf("info" to listOf(mapOf("url" to "https://vu.test/upload", "videoId" to 77L, "token" to "slot-token"))),
            mapOf("info" to listOf(mapOf("url" to "https://vu.test/upload", "videoId" to 78L, "token" to "slot-token-2"))),
        )
        val http = FakeHttp(reply = """{"photos": {"Xy=1": {"token": "story-photo"}}}""")
        val api = MediaApi(sink, http, ua, clock = clock, boundary = { "----B" })
        assertEquals("story-photo", api.uploadStoryPhoto(ByteArrayUploadSource(byteArrayOf(1, 2))))
        http.reply = """[{"token": "cdn-video"}]"""
        assertEquals("cdn-video", api.uploadStoryVideo(ByteArrayUploadSource(byteArrayOf(3, 4))))
        // No token in the CDN reply: the slot's token.
        http.reply = ""
        assertEquals("slot-token-2", api.uploadStoryVideo(ByteArrayUploadSource(byteArrayOf(5))))
        assertEquals(listOf(Opcode.PHOTO_UPLOAD, Opcode.VIDEO_UPLOAD, Opcode.VIDEO_UPLOAD), sink.sent.map { it.first })
        assertEquals(1, (sink.sent[0].second as Map<*, *>)["type"])
        assertEquals(3, (sink.sent[1].second as Map<*, *>)["type"])
        assertEquals("https://vu.test/upload", http.posts[1].url)
    }

    @Test
    fun videoTokenShapes() {
        assertEquals("a", videoUploadToken("""{"videos": {"1": {"token": "a"}}}"""))
        assertEquals("b", videoUploadToken("""{"videoToken": "b"}"""))
        assertEquals(null, videoUploadToken("<html>"))
        assertEquals(null, videoUploadToken("""{"ok": true}"""))
    }

    @Test
    fun photoUploadErrors() = runTest {
        assertFailsWith<MalformedReplyException> { MediaApi(FakeSink(emptyMap<String, Any?>()), FakeHttp(), ua).requestPhotoUpload() }
        // non-200
        val e = assertFailsWith<UploadException> {
            MediaApi(FakeSink(mapOf("url" to photoUrl)), FakeHttp(status = 413), ua).uploadPhoto(byteArrayOf(1))
        }
        assertEquals(413, e.status)
        // HTTP failure
        val io = assertFailsWith<UploadException> {
            MediaApi(FakeSink(mapOf("url" to photoUrl)), FakeHttp(failure = IllegalStateException("reset")), ua).uploadPhoto(byteArrayOf(1))
        }
        assertIs<IllegalStateException>(io.cause)
        // not JSON / no token at all (a 200 with the CDN's error fields included)
        assertFailsWith<UploadException> { MediaApi(FakeSink(mapOf("url" to photoUrl)), FakeHttp(reply = "<html>"), ua).uploadPhoto(byteArrayOf(1)) }
        assertFailsWith<UploadException> {
            MediaApi(FakeSink(mapOf("url" to photoUrl)), FakeHttp(reply = """{"photos":{"other":{"token":""}}}"""), ua).uploadPhoto(byteArrayOf(1))
        }
        val cdn = assertFailsWith<UploadException> {
            MediaApi(FakeSink(mapOf("url" to photoUrl)), FakeHttp(reply = """{"error_code":3,"error_msg":"bad image"}"""), ua).uploadPhoto(byteArrayOf(1))
        }
        assertTrue(cdn.message!!.contains("bad image"))
        // server error on the slot request stays a ServerErrorException
        val se = assertFailsWith<ServerErrorException> {
            MediaApi(FakeSink(serverError(Opcode.PHOTO_UPLOAD, "upload.denied")), FakeHttp(), ua).uploadPhoto(byteArrayOf(1))
        }
        assertEquals("upload.denied", se.errorKey)
    }

    @Test
    fun photoUploadWithoutPhotoIdsTakesTheTokenFromTheUploadReply() = runTest {
        // Current PHOTO_UPLOAD replies give a bare upload URL: the token is only in the POST reply.
        val bare = "https://iu.test/uploadImage?apiToken=abc&id=1"
        val sink = FakeSink(mapOf("url" to bare))
        val http = FakeHttp(reply = """{"photos": {"srv-1": {"token": "ph-token"}}}""")
        val photo = MediaApi(sink, http, ua, clock = clock, boundary = { "----B" }).uploadPhoto(byteArrayOf(1, 2, 3), "image.jpg")
        assertEquals(OutgoingAttachment.Photo("ph-token"), photo)
        assertEquals(bare, http.posts.single().url)
        assertEquals(null, MediaApi(FakeSink(mapOf("url" to bare)), FakeHttp(), ua).requestPhotoUpload().photoId)
        // a top-level photoToken is accepted too
        val flat = MediaApi(FakeSink(mapOf("url" to bare)), FakeHttp(reply = """{"photoToken": "flat"}"""), ua).uploadPhoto(byteArrayOf(1))
        assertEquals(OutgoingAttachment.Photo("flat"), flat)
        // a named photo wins over other entries; an unknown name falls back to the reply's photo
        assertEquals("b", photoToken(mapOf("photos" to mapOf("a" to mapOf("token" to "a"), "Xy=1" to mapOf("token" to "b"))), "Xy=1"))
        assertEquals("a", photoToken(mapOf("photos" to mapOf("a" to mapOf("token" to "a"))), "Xy=1"))
        assertEquals(null, photoToken(mapOf("photos" to emptyMap<String, Any?>()), null))
    }

    // --- file / video / voice ---

    @Test
    fun fileUploadWaitsForReadiness() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val sink = FakeSink(mapOf("info" to listOf(mapOf("url" to "https://vu.test/f?id=30", "fileId" to 30, "token" to "ft"))))
        val http = FakeHttp()
        http.onPost = { events.emit(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.VIDEO, 30, 136, null)); events.emit(frameEvent("readyFile")) }
        val api = MediaApi(sink, http, ua, events, clock)
        assertEquals(OutgoingAttachment.File(30), api.uploadFile(ByteArray(5) { it.toByte() }, "report 1.pdf"))
        assertEquals(Opcode.FILE_UPLOAD, sink.sent.single().first)
        assertEquals(pymax["uploadDefault"], bytes(sink.sent.single().second))
        val post = http.posts.single()
        assertEquals("https://vu.test/f?id=30", post.url)
        assertEquals(
            listOf(
                "Content-Type" to "application/x-binary; charset=x-user-defined",
                "Content-Disposition" to "attachment; filename=report%201.pdf",
                "Connection" to "keep-alive",
                "User-Agent" to uaEncoded,
                "Content-Range" to "bytes 0-4/5",
                "Content-Length" to "5",
            ),
            post.headers,
        )
        assertEquals("0001020304", post.body.hex())
    }

    @Test
    fun videoUploadWaitsForReadinessButNotForever() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val slot = mapOf("info" to listOf(mapOf("url" to "https://vu.test/v", "videoId" to 20, "token" to "video-token")))
        val http = FakeHttp()
        http.onPost = { events.emit(frameEvent("readyVideo")) }
        val sink = FakeSink(slot, slot)
        val api = MediaApi(sink, http, ua, events, clock)
        assertEquals(OutgoingAttachment.Video(20, "video-token"), api.uploadVideo(byteArrayOf(9), "v.mp4"))
        assertEquals(Opcode.VIDEO_UPLOAD, sink.sent[0].first)
        assertEquals(pymax["uploadDefault"], bytes(sink.sent[0].second))

        // no NOTIF_ATTACH (lost with a reconnect): after 60 s the attachment is returned anyway,
        // and MSG_SEND retries on attachment.not.ready
        http.onPost = {}
        val waiting = async { api.uploadVideo(byteArrayOf(9), "v.mp4") }
        advanceTimeBy(59_000)
        runCurrent()
        assertTrue(waiting.isActive)
        advanceTimeBy(2_000)
        assertEquals(OutgoingAttachment.Video(20, "video-token"), waiting.await())
    }

    private fun attachFailed(kind: MaxEvent.AttachmentReady.Kind?, id: Long?, error: String = "upload.failed") =
        MaxEvent.AttachmentFailed(error, 136, null, kind, id)

    @Test
    fun uploadFailsFastOnMatchingAttachmentFailure() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val slot = mapOf("info" to listOf(mapOf("url" to "https://vu.test/f?id=30", "fileId" to 30, "token" to "ft")))
        val http = FakeHttp()
        http.onPost = { events.emit(attachFailed(MaxEvent.AttachmentReady.Kind.FILE, 30, "file.too.big")) }
        val api = MediaApi(FakeSink(slot, slot), http, ua, events, clock)
        val e = assertFailsWith<ServerErrorException> { api.uploadFile(byteArrayOf(1), "f.bin") }
        assertEquals("file.too.big", e.errorKey)
        assertEquals(Opcode.NOTIF_ATTACH.value, e.packet.opcode)
        assertEquals(0L, testScheduler.currentTime)
        val error = e.toMaxError()
        assertEquals(ErrorKind.SERVER, error.kind)
        assertEquals("file.too.big", error.errorKey)

        // the failure arrives while waiting for readiness after the POST
        http.onPost = {}
        val waiting = async { runCatching { api.uploadFile(byteArrayOf(1), "f.bin") } }
        runCurrent()
        advanceTimeBy(5_000)
        assertTrue(waiting.isActive)
        events.emit(attachFailed(MaxEvent.AttachmentReady.Kind.FILE, 30))
        runCurrent()
        assertEquals("upload.failed", assertIs<ServerErrorException>(waiting.await().exceptionOrNull()).errorKey)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun uploadIgnoresFailuresForOtherOrNoAttachment() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val slot = mapOf("info" to listOf(mapOf("url" to "https://vu.test/v", "videoId" to 20, "token" to "video-token")))
        val http = FakeHttp()
        http.onPost = {
            events.emit(attachFailed(MaxEvent.AttachmentReady.Kind.VIDEO, 21))
            events.emit(attachFailed(MaxEvent.AttachmentReady.Kind.FILE, 20))
            events.emit(attachFailed(null, null))
            events.emit(frameEvent("readyVideo"))
        }
        val api = MediaApi(FakeSink(slot, slot), http, ua, events, clock)
        assertEquals(OutgoingAttachment.Video(20, "video-token"), api.uploadVideo(byteArrayOf(9), "v.mp4"))

        // unmatched failures and no readiness: the 60 s timeout still returns the attachment
        http.onPost = {
            events.emit(attachFailed(MaxEvent.AttachmentReady.Kind.VIDEO, 21))
            events.emit(attachFailed(null, null))
        }
        val waiting = async { api.uploadVideo(byteArrayOf(9), "v.mp4") }
        advanceTimeBy(59_000)
        runCurrent()
        assertTrue(waiting.isActive)
        advanceTimeBy(2_000)
        assertEquals(OutgoingAttachment.Video(20, "video-token"), waiting.await())
    }

    @Test
    fun parallelVideoUploadFailsFastOnMatchingFailure() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val slot = mapOf("info" to listOf(mapOf("url" to "https://vu.test/v", "videoId" to 20, "token" to "video-token")))
        val waiting = async {
            runCatching { MediaApi(FakeSink(slot), ChunkCdn(), ua, events, clock).uploadVideoParallel(ByteArray(10), chunkSize = 4) }
        }
        runCurrent()
        events.emit(attachFailed(MaxEvent.AttachmentReady.Kind.VIDEO, 20, "video.broken"))
        runCurrent()
        assertEquals("video.broken", assertIs<ServerErrorException>(waiting.await().exceptionOrNull()).errorKey)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun voiceSendFailsFastOnMatchingFailure() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val note = OutgoingAttachment.VideoNote(20, "vn-token", 3500)
        val sink = FakeSink(*Array(80) { serverError(Opcode.MSG_SEND, "attachment.not.ready") })
        sink.onRequest = {
            if (sink.sent.size == 1) {
                events.emit(attachFailed(MaxEvent.AttachmentReady.Kind.VIDEO, 21))
                events.emit(attachFailed(null, null))
            }
            if (sink.sent.size == 3) events.emit(attachFailed(MaxEvent.AttachmentReady.Kind.VIDEO, 20, "video.broken"))
        }
        val e = assertFailsWith<ServerErrorException> { MediaApi(sink, FakeHttp(), ua, events, clock).sendMessage(100, listOf(note)) }
        assertEquals("video.broken", e.errorKey)
        assertEquals(3, sink.sent.size)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun uploadsWithoutEventsDoNotWait() = runTest {
        val sink = FakeSink(mapOf("info" to listOf(mapOf("url" to "u", "fileId" to 30, "token" to "t"))))
        assertEquals(OutgoingAttachment.File(30), MediaApi(sink, FakeHttp(), ua).uploadFile(byteArrayOf(), "e"))
        // malformed slot replies
        assertFailsWith<MalformedReplyException> { MediaApi(FakeSink(mapOf("info" to emptyList<Any?>())), FakeHttp(), ua).requestFileUpload() }
        assertFailsWith<MalformedReplyException> {
            MediaApi(FakeSink(mapOf("info" to listOf(mapOf("url" to "u", "token" to "t")))), FakeHttp(), ua).requestVideoUpload()
        }
        // non-200 on a single POST
        val e = assertFailsWith<UploadException> {
            MediaApi(FakeSink(mapOf("info" to listOf(mapOf("url" to "u", "fileId" to 1, "token" to "t")))), FakeHttp(status = 500), ua, MutableSharedFlow()).uploadFile(byteArrayOf(1), "f")
        }
        assertEquals(500, e.status)
    }

    @Test
    fun voiceUploadUsesVoiceSlot() = runTest {
        val sink = FakeSink(mapOf("info" to listOf(mapOf("url" to "https://vu.test/a", "videoId" to 40, "token" to "voice-token"))))
        val http = FakeHttp()
        val voice = MediaApi(sink, http, ua, MutableSharedFlow(), clock).uploadVoice(byteArrayOf(1, 2), "voice.ogg", 3500)
        assertEquals(OutgoingAttachment.Voice(40, "voice-token", 3500), voice)
        assertEquals(Opcode.VIDEO_UPLOAD, sink.sent.single().first)
        assertEquals(pymax["uploadVoice"], bytes(sink.sent.single().second))
        assertEquals(pymax["attachVoice"], bytes(voice.toPayload()))
        assertEquals("bytes 0-1/2", http.posts.single().headers.toMap()["Content-Range"])
        // As Komet: octet-stream, connection close, a numeric name without extension.
        val headers = http.posts.single().headers.toMap()
        assertEquals("application/octet-stream", headers["Content-Type"])
        assertEquals("close", headers["Connection"])
        assertTrue(Regex("attachment; filename=\\d+").matches(headers["Content-Disposition"].orEmpty()))
    }

    @Test
    fun recordingUploadRejectedInBodyFails() = runTest {
        val slot = mapOf("info" to listOf(mapOf("url" to "https://vu.test/a", "videoId" to 40, "token" to "t")))
        val http = FakeHttp(reply = """{"error_code":1,"error_msg":"bad file"}""")
        val voice = assertFailsWith<UploadException> {
            MediaApi(FakeSink(slot), http, ua, MutableSharedFlow(), clock).uploadVoice(byteArrayOf(1), "v.ogg", 1000)
        }
        assertTrue("bad file" in voice.message.orEmpty())
        assertFailsWith<UploadException> {
            MediaApi(FakeSink(slot), http, ua, MutableSharedFlow(), clock).uploadVideoNote(byteArrayOf(1), "n.mp4", 1000)
        }
    }

    // --- sending ---

    @Test
    fun sendMessageWithAttachments() = runTest {
        val sink = FakeSink(sentMessage(55), sentMessage(56))
        val api = MediaApi(sink, FakeHttp(), ua, clock = clock)
        assertEquals(55L, api.sendMessage(100, listOf(OutgoingAttachment.Photo("ph-token"))).id)
        assertEquals(Opcode.MSG_SEND, sink.sent[0].first)
        assertEquals(pymax["sendPhoto"], bytes(sink.sent[0].second))
        api.sendMessage(100, listOf(OutgoingAttachment.Photo("ph-token"), OutgoingAttachment.File(30)), text = "look")
        val second = (sink.sent[1].second as Map<*, *>)["message"] as Map<*, *>
        assertEquals(now + 2, second["cid"])
        // same bytes as PyMax apart from the cid
        assertEquals(pymax["sendPhotoText"], bytes((sink.sent[1].second as Map<*, *>).let { p -> p + ("message" to (second + ("cid" to now + 1))) }))
    }

    @Test
    fun attachmentNotReadyRetriesOnceAfterVoiceSignal() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val voice = OutgoingAttachment.Voice(40, "voice-token", 3500)
        val sink = FakeSink(serverError(Opcode.MSG_SEND, "attachment.not.ready"), sentMessage(57))
        sink.onRequest = { if (sink.sent.size == 1) events.emit(frameEvent("readyAudio")) }
        val api = MediaApi(sink, FakeHttp(), ua, events, clock)
        assertEquals(57L, api.sendMessage(100, listOf(voice)).id)
        assertEquals(2, sink.sent.size)
        assertEquals(bytes(sink.sent[0].second), bytes(sink.sent[1].second))

        // never ready: the frame goes again every second, UploadException after the timeout
        val sink2 = FakeSink(*Array(80) { serverError(Opcode.MSG_SEND, "attachment.not.ready") })
        val waiting = async { runCatching { MediaApi(sink2, FakeHttp(), ua, events, clock).sendMessage(100, listOf(voice)) } }
        advanceTimeBy(61_000)
        assertIs<UploadException>(waiting.await().exceptionOrNull())

        // not a voice attachment, or no events: rethrown; other errors: rethrown
        val e1 = assertFailsWith<ServerErrorException> {
            MediaApi(FakeSink(serverError(Opcode.MSG_SEND, "attachment.not.ready")), FakeHttp(), ua, events).sendMessage(100, listOf(OutgoingAttachment.File(1)))
        }
        assertEquals("attachment.not.ready", e1.errorKey)
        assertFailsWith<ServerErrorException> {
            MediaApi(FakeSink(serverError(Opcode.MSG_SEND, "attachment.not.ready")), FakeHttp(), ua).sendMessage(100, listOf(voice))
        }
        val e2 = assertFailsWith<ServerErrorException> {
            MediaApi(FakeSink(serverError(Opcode.MSG_SEND, "chat.denied")), FakeHttp(), ua, events).sendMessage(100, listOf(voice))
        }
        assertEquals("chat.denied", e2.errorKey)
    }

    @Test
    fun videoNotReadyKeyRetriesWithoutSignal() = runTest {
        // The server's key for voice and notes is longer; no readiness push comes at all.
        val events = MutableSharedFlow<MaxEvent>()
        val voice = OutgoingAttachment.Voice(41, "voice-token", 2600)
        val sink = FakeSink(
            serverError(Opcode.MSG_SEND, "errors.process.attachment.video.not.ready"),
            serverError(Opcode.MSG_SEND, "errors.process.attachment.video.not.ready"),
            sentMessage(58),
        )
        val sending = async { MediaApi(sink, FakeHttp(), ua, events, clock).sendMessage(100, listOf(voice)) }
        advanceTimeBy(2_500)
        assertEquals(58L, sending.await().id)
        assertEquals(3, sink.sent.size)
        assertTrue(MediaApi.isNotReady("attachment.not.ready"))
        assertTrue(MediaApi.isNotReady("errors.process.attachment.video.not.ready"))
        assertFalse(MediaApi.isNotReady("chat.denied"))
        assertFalse(MediaApi.isNotReady(null))
    }

    @Test
    fun contactCardPayload() {
        assertEquals(mapOf("_type" to "CONTACT", "contactId" to 7L), OutgoingAttachment.Contact(7).toPayload())
        assertEquals(listOf("_type", "contactId"), OutgoingAttachment.Contact(7).toPayload().keys.toList())
    }

    @Test
    fun notReadyIsRetriedWhenAskedFor() = runTest {
        val sink = FakeSink(serverError(Opcode.MSG_SEND, "attachment.not.ready"), serverError(Opcode.MSG_SEND, "attachment.not.ready"), sentMessage(61))
        val api = MediaApi(sink, FakeHttp(), ua, clock = clock)
        val start = testScheduler.currentTime
        assertEquals(61L, api.sendMessage(100, listOf(OutgoingAttachment.Photo("ph-token")), notReadyAttempts = 3).id)
        assertEquals(3, sink.sent.size)
        assertEquals(2_000L, testScheduler.currentTime - start)
        assertEquals(bytes(sink.sent[0].second), bytes(sink.sent[2].second))

        // attempts used up: the last error is rethrown
        val sink2 = FakeSink(serverError(Opcode.MSG_SEND, "attachment.not.ready"), serverError(Opcode.MSG_SEND, "attachment.not.ready"))
        val e = assertFailsWith<ServerErrorException> {
            MediaApi(sink2, FakeHttp(), ua, clock = clock).sendMessage(100, listOf(OutgoingAttachment.File(1)), notReadyAttempts = 2)
        }
        assertEquals("attachment.not.ready", e.errorKey)
        assertEquals(2, sink2.sent.size)
        // other errors are not retried
        val sink3 = FakeSink(serverError(Opcode.MSG_SEND, "chat.denied"), sentMessage(62))
        assertFailsWith<ServerErrorException> {
            MediaApi(sink3, FakeHttp(), ua, clock = clock).sendMessage(100, listOf(OutgoingAttachment.Contact(7)), notReadyAttempts = 5)
        }
        assertEquals(1, sink3.sent.size)
    }

    @Test
    fun uploadAllKeepsOrderNamesAndReportsBatchProgress() = runTest {
        val sink = FakeSink(
            mapOf("url" to photoUrl),
            mapOf("info" to listOf(mapOf("url" to "https://vu.test/f?id=30", "fileId" to 30, "token" to "ft"))),
        )
        val http = FakeHttp(reply = """{"photos": {"Xy=1": {"token": "ph-token"}}}""")
        http.reportEvery = 7
        val api = MediaApi(sink, http, ua, clock = clock, boundary = { "----B" })
        val files = mapOf("/tmp/a.jpg" to ByteArray(10), "/tmp/x/doc" to ByteArray(20))
        val opened = ArrayList<String>()
        val seen = ArrayList<Pair<Long, Long>>()
        val result = api.uploadAll(
            listOf(OutgoingMedia("/tmp/a.jpg", OutgoingMedia.Kind.PHOTO), OutgoingMedia("/tmp/x/doc", OutgoingMedia.Kind.FILE, "Отчёт.pdf")),
            progress = UploadProgress { sent, total -> seen += sent to total },
            open = { path -> opened += path; ByteArrayUploadSource(files.getValue(path)) },
        )
        assertEquals(listOf(OutgoingAttachment.Photo("ph-token"), OutgoingAttachment.File(30)), result)
        assertEquals(listOf("/tmp/a.jpg", "/tmp/x/doc"), opened)
        assertEquals(listOf(Opcode.PHOTO_UPLOAD, Opcode.FILE_UPLOAD), sink.sent.map { it.first })
        assertTrue(http.posts[0].body.decodeToString().contains("filename=\"a.jpg\""))
        assertEquals("attachment; filename=%D0%9E%D1%82%D1%87%D1%91%D1%82.pdf", http.posts[1].headers.toMap()["Content-Disposition"])
        // one total for the batch, never going back, ending at the sum of the file sizes
        assertTrue(seen.all { it.second == 30L })
        assertEquals(seen.map { it.first }.sorted(), seen.map { it.first })
        assertTrue(seen.any { it.first in 1L..9L })
        assertTrue(seen.any { it.first in 11L..29L })
        assertEquals(30L, seen.last().first)
    }

    @Test
    fun uploadAllStopsAtTheFirstFailure() = runTest {
        val sink = FakeSink(mapOf("url" to photoUrl), mapOf("url" to photoUrl))
        val api = MediaApi(sink, FakeHttp(status = 500), ua, clock = clock)
        assertFailsWith<UploadException> {
            api.uploadAll(
                listOf(OutgoingMedia("/a.jpg", OutgoingMedia.Kind.PHOTO), OutgoingMedia("/b.jpg", OutgoingMedia.Kind.PHOTO)),
                open = { ByteArrayUploadSource(ByteArray(3)) },
            )
        }
        assertEquals(1, sink.sent.size)
    }

    // --- links ---

    @Test
    fun downloadLinks() = runTest {
        val sink = FakeSink(
            mapOf("EXTERNAL" to false, "cache" to true, "MP4_480" to "https://v/480", "MP4_1080" to "https://v/1080", "MP4_x" to "bad", "HLS" to "https://v/hls"),
            mapOf("url" to "https://f/doc", "unsafe" to false),
            mapOf("dynamicUrl" to "https://v/dyn"),
            mapOf("EXTERNAL" to "https://yt/x"),
            mapOf("unsafe" to true),
        )
        val api = MediaApi(sink, FakeHttp(), ua)
        val video = api.getVideoLink(100, 10, 20)
        assertEquals("https://v/1080", video.url)
        assertEquals(true, video.cache)
        assertEquals(false, video.external)
        assertEquals(Opcode.VIDEO_PLAY, sink.sent[0].first)
        assertEquals(pymax["getVideo"], bytes(sink.sent[0].second))
        assertEquals(FileLink("https://f/doc", false, mapOf("url" to "https://f/doc", "unsafe" to false)), api.getFileLink(100, 10, 30))
        assertEquals(Opcode.FILE_DOWNLOAD, sink.sent[1].first)
        assertEquals(pymax["getFile"], bytes(sink.sent[1].second))
        assertEquals("https://v/dyn", api.getVideoLink(1, 2, 3).url)
        api.getVideoLink(1, 2, 3).let { assertNull(it.url); assertEquals("https://yt/x", it.external) }
        assertFailsWith<MalformedReplyException> { api.getFileLink(1, 2, 3) }
    }

    // --- incoming attachments ---

    @Test
    fun incomingAttachments() {
        val photo = mapOf("_type" to "PHOTO", "photoId" to 11, "photoToken" to "pt", "baseUrl" to "https://i/p", "width" to 640, "height" to 480)
        val video = mapOf("_type" to "VIDEO", "videoId" to 20, "token" to "vt", "width" to 1280, "height" to 720, "duration" to 5000, "videoType" to 0, "thumbnail" to "https://i/t")
        val file = mapOf("_type" to "FILE", "fileId" to 30, "name" to "a.pdf", "size" to 1024, "token" to "ft")
        val audio = mapOf("_type" to "AUDIO", "audioId" to 40, "duration" to 3500, "url" to "https://a/x", "transcriptionStatus" to "SUCCESS")
        val sticker = mapOf("_type" to "CONTACT", "contactId" to 1)
        val m = MaxMessage.from(mapOf("id" to 1, "chatId" to 100, "time" to 1, "type" to "USER", "attaches" to listOf(photo, video, file, audio, sticker, mapOf("_type" to "PHOTO"), 5)))!!
        val a = m.attachments
        assertEquals(Attachment.Photo(11, "pt", "https://i/p", 640, 480, photo), a[0])
        assertEquals(Attachment.Video(20, "vt", 1280, 720, 5000, 0, "https://i/t", video), a[1])
        assertEquals(Attachment.File(30, "a.pdf", 1024, "ft", file), a[2])
        assertEquals(Attachment.Audio(40, 3500, "https://a/x", null, "SUCCESS", audio), a[3])
        assertEquals(Attachment.Unknown("CONTACT", sticker), a[4])
        assertEquals("PHOTO", assertIs<Attachment.Unknown>(a[5]).type)
        assertNull(assertIs<Attachment.Unknown>(a[6]).type)
    }

    @Test
    fun helpers() {
        assertEquals("a-_.!~*'()%20%2F%3B%D1%8F", UploadRequests.percentEncode("a-_.!~*'() /;я"))
        assertEquals("image/jpeg", UploadRequests.imageContentType("x.JPG"))
        assertEquals("image/heic", UploadRequests.imageContentType("x.heif"))
        assertEquals("image/jpeg", UploadRequests.imageContentType("noext"))
        assertEquals("bytes 0-0/0", UploadRequests.singlePostHeaders("e", 0, ua).toMap()["Content-Range"])
        assertEquals("a b=c", queryParam("https://x/y?p=1&q=a+b%3Dc#f", "q"))
        assertNull(queryParam("https://x/y", "q"))
        assertEquals(mapOf("a" to listOf(1L, -2.5, true, null, "x\"\u00e9\n")), MiniJson.parse("""{ "a" : [1, -2.5, true, null, "x\"\u00e9\n"] }"""))
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("{\"a\":1} x") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("{\"a\":") }
    }

    // --- video notes ---

    @Test
    fun videoNotePayloadBytesMatchPyMax() {
        assertEquals(pymax["uploadVideoNote"], bytes(MediaApi.uploadPayload(type = 1, uploaderType = 1)))
        assertEquals(pymax["videoNote"], bytes(OutgoingAttachment.VideoNote(20, "vn-token", 3500, byteArrayOf(1, 2)).toPayload()))
        assertEquals(pymax["videoNoteNoThumb"], bytes(OutgoingAttachment.VideoNote(20, "vn-token", 3500).toPayload()))
        assertEquals(pymax["videoNoteNoDuration"], bytes(OutgoingAttachment.VideoNote(20, "vn-token", thumbhash = byteArrayOf(1, 2)).toPayload()))
    }

    @Test
    fun videoNoteUploadReadsThumbhashAndDoesNotWait() = runTest {
        val slot = mapOf("info" to listOf(mapOf("url" to "https://vu.test/n", "videoId" to 20, "token" to "vn-token")))
        val sink = FakeSink(slot, slot, slot, slot)
        // unpadded base64 "AQI" = 01 02 (PyMax pads with '=')
        val http = FakeHttp(reply = """{"thumbhash":"AQI"}""")
        val api = MediaApi(sink, http, ua, MutableSharedFlow(), clock)
        val note = api.uploadVideoNote(byteArrayOf(5, 6, 7), "note.mp4", 3500)
        assertEquals(OutgoingAttachment.VideoNote(20, "vn-token", 3500, byteArrayOf(1, 2)), note)
        assertEquals(pymax["videoNote"], bytes(note.toPayload()))
        assertEquals(Opcode.VIDEO_UPLOAD, sink.sent[0].first)
        assertEquals(pymax["uploadVideoNote"], bytes(sink.sent[0].second))
        assertEquals("bytes 0-2/3", http.posts.single().headers.toMap()["Content-Range"])

        http.reply = "{}"
        assertNull(api.uploadVideoNote(byteArrayOf(1), "n.mp4").thumbhash)
        http.reply = "not json"
        assertFailsWith<UploadException> { api.uploadVideoNote(byteArrayOf(1), "n.mp4") }
        http.reply = """{"thumbhash":"***"}"""
        assertFailsWith<UploadException> { api.uploadVideoNote(byteArrayOf(1), "n.mp4") }
    }

    @Test
    fun voiceAndVideoNoteAreDistinct() = runTest {
        val slot = mapOf("info" to listOf(mapOf("url" to "u", "videoId" to 20, "token" to "t")))
        val sink = FakeSink(slot, mapOf("info" to listOf(mapOf("url" to "u", "videoId" to 20, "token" to "t"))))
        val http = FakeHttp(reply = "{}")
        val api = MediaApi(sink, http, ua)
        val voice = api.uploadVoice(byteArrayOf(1), "v.ogg", 1000)
        val note = api.uploadVideoNote(byteArrayOf(1), "n.mp4", 1000)
        // same opcode, different slot type: voice type=2, note type=1
        assertEquals(listOf(Opcode.VIDEO_UPLOAD, Opcode.VIDEO_UPLOAD), sink.sent.map { it.first })
        assertEquals(pymax["uploadVoice"], bytes(sink.sent[0].second))
        assertEquals(pymax["uploadVideoNote"], bytes(sink.sent[1].second))
        assertEquals("AUDIO", voice.toPayload()["_type"])
        assertEquals("VIDEO", note.toPayload()["_type"])
        assertEquals(1, note.toPayload()["videoType"])
    }

    @Test
    fun attachmentNotReadyWaitsForVideoNoteSignal() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val note = OutgoingAttachment.VideoNote(20, "vn-token", 3500)
        val sink = FakeSink(serverError(Opcode.MSG_SEND, "attachment.not.ready"), sentMessage(60))
        sink.onRequest = {
            if (sink.sent.size == 1) {
                // an AUDIO signal with the same id must not count for a video note
                events.emit(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.AUDIO, 20, 136, null))
                events.emit(frameEvent("readyVideo"))
            }
        }
        assertEquals(60L, MediaApi(sink, FakeHttp(), ua, events, clock).sendMessage(100, listOf(note)).id)
        assertEquals(bytes(sink.sent[0].second), bytes(sink.sent[1].second))

        val sink2 = FakeSink(*Array(80) { serverError(Opcode.MSG_SEND, "attachment.not.ready") })
        sink2.onRequest = { events.emit(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.AUDIO, 20, 136, null)) }
        val waiting = async { runCatching { MediaApi(sink2, FakeHttp(), ua, events, clock).sendMessage(100, listOf(note)) } }
        advanceTimeBy(61_000)
        assertIs<UploadException>(waiting.await().exceptionOrNull())
    }

    // --- progress ---

    @Test
    fun progressIsReported() = runTest {
        val seen = ArrayList<Pair<Long, Long>>()
        val progress = UploadProgress { s, t -> seen += s to t }
        // transport reports while writing: its values are passed through, no duplicate completion
        val fileSlot = mapOf("info" to listOf(mapOf("url" to "u", "fileId" to 30, "token" to "t")))
        val http = FakeHttp().apply { reportEvery = 2 }
        MediaApi(FakeSink(fileSlot), http, ua).uploadFile(ByteArray(5), "f.bin", progress)
        assertEquals(listOf(2L to 5L, 4L to 5L, 5L to 5L), seen)

        // transport without progress: completion is reported after the 200
        seen.clear()
        val photoHttp = FakeHttp(reply = """{"photos":{"Xy=1":{"token":"t"}}}""")
        MediaApi(FakeSink(mapOf("url" to photoUrl)), photoHttp, ua, boundary = { "b" }).uploadPhoto(byteArrayOf(1, 2, 3), progress = progress)
        val size = photoHttp.posts.single().body.size.toLong()
        assertEquals(listOf(size to size), seen)

        // failed upload: no completion
        seen.clear()
        assertFailsWith<UploadException> { MediaApi(FakeSink(fileSlot), FakeHttp(status = 500), ua).uploadFile(ByteArray(3), "f", progress) }
        assertTrue(seen.isEmpty())
    }

    // --- parallel chunked video ---

    @Test
    fun chunkedUploadFollowsKolibri() = runTest {
        val cdn = ChunkCdn()
        val seen = ArrayList<Pair<Long, Long>>()
        val data = ByteArray(7) { (it + 1).toByte() }
        val api = MediaApi(FakeSink(), cdn, ua)
        assertEquals(0L, api.uploadChunked("https://vu.test/v", data, chunkSize = 2, concurrency = 2, progress = { s, t -> seen += s to t }, uploadName = "123"))

        val get = cdn.requests.first()
        assertEquals("GET", get.method)
        assertEquals("https://vu.test/v", get.url)
        assertEquals(
            listOf(
                "Content-Type" to "application/x-binary; charset=x-user-defined",
                "Content-Disposition" to "attachment; fileName=\"123\"",
                "Content-Length" to "0",
                "X-Uploading-Mode" to "parallel",
                "Connection" to "close",
            ),
            get.headers,
        )
        assertEquals(0, get.body.size)
        val posts = cdn.requests.drop(1)
        assertTrue(posts.all { it.method == "POST" })
        assertEquals(
            listOf(
                "Content-Type" to "application/x-binary; charset=x-user-defined",
                "Content-Disposition" to "attachment; fileName=\"123\"",
                "Content-Length" to "2",
                "X-Uploading-Mode" to "parallel",
                "Connection" to "close",
                "Content-Range" to "bytes 0-1/7",
            ),
            posts.first().headers,
        )
        assertEquals(setOf("bytes 0-1/7", "bytes 2-3/7", "bytes 4-5/7", "bytes 6-6/7"), cdn.received.keys)
        assertEquals("0102", cdn.received.getValue("bytes 0-1/7").hex())
        assertEquals("07", cdn.received.getValue("bytes 6-6/7").hex())
        assertEquals(2, cdn.maxInFlight)
        assertEquals(listOf(2L, 4L, 6L, 7L), seen.map { it.first })
        assertTrue(seen.all { it.second == 7L })
    }

    @Test
    fun chunkedUploadResumesAndFails() = runTest {
        val data = ByteArray(7)
        // resume offset from the GET body
        val resumed = ChunkCdn(resume = " 4\n")
        val seen = ArrayList<Long>()
        assertEquals(4L, MediaApi(FakeSink(), resumed, ua).uploadChunked("u", data, 2, 4, { s, _ -> seen += s }, "n"))
        assertEquals(setOf("bytes 4-5/7", "bytes 6-6/7"), resumed.received.keys)
        assertEquals(listOf(6L, 7L), seen)
        // offset beyond the size or not a number: from 0 (kolibri keeps 0)
        assertEquals(4, ChunkCdn(resume = "99").also { MediaApi(FakeSink(), it, ua).uploadChunked("u", data, 2, 1, uploadName = "n") }.received.size)
        assertEquals(4, ChunkCdn(resume = "ok").also { MediaApi(FakeSink(), it, ua).uploadChunked("u", data, 2, 1, uploadName = "n") }.received.size)
        // everything already there: no chunks
        val done = ChunkCdn(resume = "7")
        assertEquals(7L, MediaApi(FakeSink(), done, ua).uploadChunked("u", data, 2, 1, uploadName = "n"))
        assertEquals(1, done.requests.size)

        assertEquals(403, assertFailsWith<UploadException> { MediaApi(FakeSink(), ChunkCdn(handshakeStatus = 403), ua).uploadChunked("u", data, 2) }.status)
        assertEquals(500, assertFailsWith<UploadException> { MediaApi(FakeSink(), ChunkCdn(failRange = "bytes 2-3/7"), ua).uploadChunked("u", data, 2, 2) }.status)
        assertFailsWith<UploadException> { MediaApi(FakeSink(), ChunkCdn(), ua).uploadChunked("u", ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { MediaApi(FakeSink(), ChunkCdn(), ua).uploadChunked("u", data, 0) }
    }

    @Test
    fun parallelVideoUploadUsesSlotAndWaitsForReadiness() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val sink = FakeSink(mapOf("info" to listOf(mapOf("url" to "https://vu.test/p", "videoId" to 20, "token" to "video-token"))))
        val cdn = ChunkCdn()
        val http = MediaHttp { method, url, headers, body, progress ->
            val r = cdn.request(method, url, headers, body, progress)
            if (cdn.received.size == 3) events.emit(frameEvent("readyVideo"))
            r
        }
        val api = MediaApi(sink, http, ua, events, clock)
        assertEquals(OutgoingAttachment.Video(20, "video-token"), api.uploadVideoParallel(ByteArray(5), chunkSize = 2))
        assertEquals(pymax["uploadDefault"], bytes(sink.sent.single().second))
        // default upload name: kolibri now_micros() & 0x7FFFFFFF
        assertEquals("attachment; fileName=\"1654642688\"", cdn.requests.first().headers.toMap()["Content-Disposition"])
        assertTrue(cdn.requests.all { it.url == "https://vu.test/p" })
    }

    // --- stickers ---

    @Test
    fun incomingSticker() {
        val raw = mapOf(
            "_type" to "STICKER", "stickerId" to 777, "url" to "https://st/1.webp", "width" to 256, "height" to 256,
            "stickerType" to "STATIC", "audio" to false, "time" to 1700000000, "setId" to 55, "tags" to listOf("hi", 1),
            "lottieUrl" to "https://st/1.json", "authorType" to "SYSTEM",
        )
        assertEquals(
            Attachment.Sticker(777, "https://st/1.webp", 256, 256, "STATIC", false, 1700000000, 55, listOf("hi"), "https://st/1.json", "SYSTEM", raw),
            Attachment.from(raw),
        )
        val minimal = mapOf("_type" to "STICKER", "stickerId" to 1)
        assertEquals(Attachment.Sticker(1, null, null, null, null, null, null, null, null, null, null, minimal), Attachment.from(minimal))
        assertIs<Attachment.Unknown>(Attachment.from(mapOf("_type" to "STICKER")))
    }

    // --- over SessionMachine + TokenLogin ---

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Any? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue)
        feed(ok(header.seq, opcode.value, reply))
        return payload
    }

    @Test
    fun sendPhotoOverLoggedInSession() = runTest {
        val device = DeviceInfo(deviceId = "d1e9c0de00000001", instanceId = "a1b2c3d4e5f60718", clientSessionId = 17)
        val factory = ScriptedConnectionFactory()
        val login = AuthApi.tokenLoginHook("stored-token", device)
        val config = SessionConfig(TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false), device)
        val m = SessionMachine(config, factory, scope = backgroundScope, afterHandshake = login.hook)
        val connecting = async { m.connect() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1234567890123L))
        runCurrent()
        conn.answer(Opcode.LOGIN, mapOf("profile" to mapOf("contact" to mapOf("id" to 5))))
        connecting.await()

        val http = FakeHttp(reply = """{"photos":{"Xy=1":{"token":"ph-token"}}}""")
        val media = MediaApi(m, http, clock)
        val sending = async { media.sendMessage(100, listOf(media.uploadPhoto(byteArrayOf(7), "image.jpg"))) }
        runCurrent()
        assertEquals(pymax["uploadDefault"], bytes(conn.answer(Opcode.PHOTO_UPLOAD, mapOf("url" to photoUrl))))
        runCurrent()
        assertEquals(uaEncoded, http.posts.single().headers.toMap()["User-Agent"])
        assertTrue(http.posts.single().body.decodeToString().contains("Content-Type: image/jpeg"))
        assertEquals(pymax["sendPhoto"], bytes(conn.answer(Opcode.MSG_SEND, sentMessage(58))))
        assertEquals(58L, sending.await().id)
        m.disconnect()
    }
}
