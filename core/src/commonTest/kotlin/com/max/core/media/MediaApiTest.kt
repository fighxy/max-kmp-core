@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.media

import com.max.core.api.MaxMessage
import com.max.core.api.MalformedReplyException
import com.max.core.auth.AuthApi
import com.max.core.auth.RequestSink
import com.max.core.events.EventParser
import com.max.core.events.MaxEvent
import com.max.core.protocol.CmdType
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.protocol.decodePayloadPacket
import com.max.core.session.DeviceInfo
import com.max.core.session.SessionConfig
import com.max.core.session.SessionMachine
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportConfig
import com.max.core.transport.TransportPacket
import com.max.core.transport.ok
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private class Post(val url: String, val headers: List<Pair<String, String>>, val body: ByteArray)

    private class FakeHttp(var status: Int = 200, var reply: String = "", var failure: Exception? = null) : MediaHttp {
        val posts = ArrayList<Post>()
        var onPost: suspend () -> Unit = {}
        override suspend fun post(url: String, headers: List<Pair<String, String>>, body: ByteArray): HttpResponse {
            posts += Post(url, headers, body)
            failure?.let { throw it }
            onPost()
            return HttpResponse(status, reply.encodeToByteArray())
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

    @Test
    fun photoUploadErrors() = runTest {
        // slot url without photoIds
        assertFailsWith<MalformedReplyException> {
            MediaApi(FakeSink(mapOf("url" to "https://iu.test/upload.do?x=1")), FakeHttp(), ua).uploadPhoto(byteArrayOf(1))
        }
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
        // not JSON / no token for this photoId
        assertFailsWith<UploadException> { MediaApi(FakeSink(mapOf("url" to photoUrl)), FakeHttp(reply = "<html>"), ua).uploadPhoto(byteArrayOf(1)) }
        assertFailsWith<UploadException> {
            MediaApi(FakeSink(mapOf("url" to photoUrl)), FakeHttp(reply = """{"photos":{"other":{"token":"t"}}}"""), ua).uploadPhoto(byteArrayOf(1))
        }
        // server error on the slot request stays a ServerErrorException
        val se = assertFailsWith<ServerErrorException> {
            MediaApi(FakeSink(serverError(Opcode.PHOTO_UPLOAD, "upload.denied")), FakeHttp(), ua).uploadPhoto(byteArrayOf(1))
        }
        assertEquals("upload.denied", se.errorKey)
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
    fun videoUploadWaitsForReadinessAndTimesOut() = runTest {
        val events = MutableSharedFlow<MaxEvent>()
        val slot = mapOf("info" to listOf(mapOf("url" to "https://vu.test/v", "videoId" to 20, "token" to "video-token")))
        val http = FakeHttp()
        http.onPost = { events.emit(frameEvent("readyVideo")) }
        val sink = FakeSink(slot, slot)
        val api = MediaApi(sink, http, ua, events, clock)
        assertEquals(OutgoingAttachment.Video(20, "video-token"), api.uploadVideo(byteArrayOf(9), "v.mp4"))
        assertEquals(Opcode.VIDEO_UPLOAD, sink.sent[0].first)
        assertEquals(pymax["uploadDefault"], bytes(sink.sent[0].second))

        http.onPost = {}
        val waiting = async { runCatching { api.uploadVideo(byteArrayOf(9), "v.mp4") } }
        advanceTimeBy(59_000)
        runCurrent()
        assertTrue(waiting.isActive)
        advanceTimeBy(2_000)
        assertIs<UploadException>(waiting.await().exceptionOrNull())
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

        // no signal: UploadException after the timeout
        val sink2 = FakeSink(serverError(Opcode.MSG_SEND, "attachment.not.ready"))
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
        val sticker = mapOf("_type" to "STICKER", "stickerId" to 1)
        val m = MaxMessage.from(mapOf("id" to 1, "chatId" to 100, "time" to 1, "type" to "USER", "attaches" to listOf(photo, video, file, audio, sticker, mapOf("_type" to "PHOTO"), 5)))!!
        val a = m.attachments
        assertEquals(Attachment.Photo(11, "pt", "https://i/p", 640, 480, photo), a[0])
        assertEquals(Attachment.Video(20, "vt", 1280, 720, 5000, 0, "https://i/t", video), a[1])
        assertEquals(Attachment.File(30, "a.pdf", 1024, "ft", file), a[2])
        assertEquals(Attachment.Audio(40, 3500, "https://a/x", null, "SUCCESS", audio), a[3])
        assertEquals(Attachment.Unknown("STICKER", sticker), a[4])
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
