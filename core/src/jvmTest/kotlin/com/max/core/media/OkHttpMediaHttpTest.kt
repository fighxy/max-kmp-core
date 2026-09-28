package com.max.core.media

import com.max.core.auth.RequestSink
import com.max.core.events.MaxEvent
import com.max.core.protocol.CmdType
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.transport.ProxyConfig
import com.max.core.transport.TransportPacket
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The real OkHttp [MediaHttp] through [MediaApi] against a local `com.sun.net.httpserver` CDN
 * stand-in (never the Max servers): photo multipart, file / voice / video-note single POSTs,
 * parallel chunked video with a GET resume offset, progress, HTTP errors and cancellation.
 */
class OkHttpMediaHttpTest {

    private class Req(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: ByteArray)

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests = Collections.synchronizedList(ArrayList<Req>())
    private val events = MutableSharedFlow<MaxEvent>(extraBufferCapacity = 64)

    // parallel video CDN state
    @Volatile private var videoData: ByteArray = ByteArray(0)
    @Volatile private var resumeOffset = 0
    private val chunks = ConcurrentHashMap<String, ByteArray>()
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    private val slowArrived = CountDownLatch(1)
    private val slowRelease = CountDownLatch(1)

    private val ua = "OKMessages/26.25.0 (Android 14; Pixel 8; 428dpi 428dpi 1080x2400)"
    private val uaEncoded = "OKMessages%2F26.25.0%20(Android%2014%3B%20Pixel%208%3B%20428dpi%20428dpi%201080x2400)"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stop() {
        slowRelease.countDown()
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes()
        val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
        val path = ex.requestURI.path
        requests += Req(ex.requestMethod, path, ex.requestURI.query, headers, body)
        fun reply(status: Int, text: String = "") {
            val bytes = text.encodeToByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        when (path) {
            "/photo" -> {
                val id = ex.requestURI.query.split('&').first { it.startsWith("photoIds=") }.substringAfter('=')
                reply(200, """{"photos":{"$id":{"token":"tok-${body.size}"}}}""")
            }
            "/file" -> { events.tryEmit(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.FILE, 30, 136, null)); reply(200, "ok") }
            "/voice" -> reply(200, "")
            "/note" -> reply(200, """{"thumbhash":"AQID"}""")
            "/fail" -> reply(413, "too large")
            "/slow" -> { slowArrived.countDown(); slowRelease.await(10, TimeUnit.SECONDS); reply(200) }
            "/video" -> if (ex.requestMethod == "GET") {
                reply(200, resumeOffset.toString())
            } else {
                val n = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                Thread.sleep(20)
                inFlight.decrementAndGet()
                chunks[headers.getValue("content-range")] = body
                val done = chunks.values.sumOf { it.size } + resumeOffset
                if (done == videoData.size) events.tryEmit(MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.VIDEO, 20, 136, null))
                reply(200)
            }
            else -> reply(404)
        }
    }

    private class FakeSink(vararg replies: Any?) : RequestSink {
        val script = ArrayDeque(replies.toList())
        val sent = ArrayList<Pair<Opcode, Any?>>()
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
            sent += opcode to payload
            return TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false), script.removeFirst())
        }
    }

    private fun slot(path: String, idKey: String, id: Long, token: String = "t") =
        mapOf("info" to listOf(mapOf("url" to "$base$path?id=$id", idKey to id, "token" to token)))

    private fun run(block: suspend () -> Unit) = runBlocking(Dispatchers.Default) { withTimeout(20_000) { block() } }

    private fun reassemble(): ByteArray {
        val out = videoData.copyOf()
        java.util.Arrays.fill(out, resumeOffset, out.size, 0)
        for ((range, bytes) in chunks) {
            val start = range.removePrefix("bytes ").substringBefore('-').toInt()
            bytes.copyInto(out, start)
        }
        return out
    }

    @Test
    fun defaultClientIsSharedOkHttp() {
        assertSame(assertIs<OkHttpMediaHttp>(defaultMediaHttp()), defaultMediaHttp())
        assertIs<OkHttpMediaHttp>(defaultMediaHttp(MediaHttpConfig(trustMincifryCa = false)))
        // every trust / proxy mode builds; SOCKS5 credentials are unsupported
        OkHttpMediaHttp(MediaHttpConfig(insecure = true))
        OkHttpMediaHttp(MediaHttpConfig(proxy = ProxyConfig.parse("http://u:p@127.0.0.1:3128")))
        OkHttpMediaHttp(MediaHttpConfig(proxy = ProxyConfig.parse("socks5h://127.0.0.1:1080")))
        assertFailsWith<IllegalArgumentException> { OkHttpMediaHttp(MediaHttpConfig(proxy = ProxyConfig.parse("socks5://u:p@127.0.0.1:1080"))) }
    }

    @Test
    fun photoMultipartThroughDefaultClient() = run {
        val sink = FakeSink(mapOf("url" to "$base/photo?photoIds=P%3D1&x=1"))
        // no http argument: MediaApi uses defaultMediaHttp() (OkHttp)
        val api = MediaApi(sink, userAgent = ua, boundary = { "----B" })
        val image = Random(1).nextBytes(100_000)
        val seen = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
        val photo = api.uploadPhoto(image, "image.png", progress = { s, t -> seen += s to t })
        val req = requests.single()
        assertEquals("POST", req.method)
        assertEquals("tok-${req.body.size}", photo.photoToken)
        val expected = UploadRequests.multipartBody("----B", "image.png", "image/png", image)
        assertContentEquals(expected, req.body)
        assertEquals("multipart/form-data; boundary=----B", req.headers["content-type"])
        assertEquals(expected.size.toString(), req.headers["content-length"])
        assertEquals(uaEncoded, req.headers["user-agent"])
        assertEquals("keep-alive", req.headers["connection"])
        assertTrue(req.headers.getValue("host").startsWith("127.0.0.1:"))
        assertMonotonicTo(seen, expected.size.toLong())
    }

    @Test
    fun fileSinglePostWaitsForReadiness() = run {
        val sink = FakeSink(slot("/file", "fileId", 30))
        val api = MediaApi(sink, OkHttpMediaHttp(), ua, events)
        val data = Random(2).nextBytes(300_000)
        val seen = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
        assertEquals(OutgoingAttachment.File(30), api.uploadFile(data, "report 1.pdf") { s, t -> seen += s to t })
        val req = requests.single()
        assertContentEquals(data, req.body)
        assertEquals("application/x-binary; charset=x-user-defined", req.headers["content-type"])
        assertEquals("attachment; filename=report%201.pdf", req.headers["content-disposition"])
        assertEquals("bytes 0-299999/300000", req.headers["content-range"])
        assertEquals("300000", req.headers["content-length"])
        assertEquals(uaEncoded, req.headers["user-agent"])
        assertMonotonicTo(seen, 300_000)
        assertTrue(seen.size >= 4, "several 64 KiB progress steps: $seen")
    }

    @Test
    fun voiceAndVideoNote() = run {
        val sink = FakeSink(slot("/voice", "videoId", 40, "voice-token"), slot("/note", "videoId", 20, "vn-token"))
        val api = MediaApi(sink, OkHttpMediaHttp(), ua)
        val ogg = Random(3).nextBytes(5_000)
        assertEquals(OutgoingAttachment.Voice(40, "voice-token", 3500), api.uploadVoice(ogg, "voice.ogg", 3500))
        val note = api.uploadVideoNote(Random(4).nextBytes(8_000), "note.mp4", 2000)
        assertEquals(OutgoingAttachment.VideoNote(20, "vn-token", 2000, byteArrayOf(1, 2, 3)), note)
        assertEquals(listOf("/voice", "/note"), requests.map { it.path })
        assertContentEquals(ogg, requests[0].body)
        assertEquals("bytes 0-7999/8000", requests[1].headers["content-range"])
    }

    @Test
    fun parallelChunkedVideoReassembles() = run {
        videoData = Random(5).nextBytes(1_000_003)
        val sink = FakeSink(slot("/video", "videoId", 20, "video-token"))
        val api = MediaApi(sink, OkHttpMediaHttp(), ua, events)
        val seen = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
        val video = api.uploadVideoParallel(videoData, chunkSize = 100_000, concurrency = 4) { s, t -> seen += s to t }
        assertEquals(OutgoingAttachment.Video(20, "video-token"), video)
        val get = requests.first()
        assertEquals("GET", get.method)
        assertEquals("parallel", get.headers["x-uploading-mode"])
        assertEquals("close", get.headers["connection"])
        assertNull(get.headers["content-range"])
        val posts = requests.drop(1)
        assertEquals(11, posts.size)
        assertTrue(posts.all { it.method == "POST" && it.headers["x-uploading-mode"] == "parallel" })
        val disposition = get.headers.getValue("content-disposition")
        assertTrue(disposition.matches(Regex("attachment; fileName=\"\\d+\"")), disposition)
        assertTrue(posts.all { it.headers["content-disposition"] == disposition })
        assertTrue(chunks.containsKey("bytes 1000000-1000002/1000003"))
        assertContentEquals(videoData, reassemble())
        assertTrue(maxInFlight.get() in 2..4, "workers in flight: ${maxInFlight.get()}")
        assertMonotonicTo(seen, videoData.size.toLong())
    }

    @Test
    fun parallelChunkedVideoResumes() = run {
        videoData = Random(6).nextBytes(500_000)
        resumeOffset = 250_000
        val api = MediaApi(FakeSink(), OkHttpMediaHttp(), ua)
        val seen = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
        assertEquals(250_000L, api.uploadChunked("$base/video", videoData, chunkSize = 100_000, concurrency = 2, progress = { s, t -> seen += s to t }))
        assertEquals(setOf("bytes 250000-349999/500000", "bytes 350000-449999/500000", "bytes 450000-499999/500000"), chunks.keys)
        assertContentEquals(videoData, reassemble())
        assertTrue(seen.first().first > 250_000)
        assertMonotonicTo(seen, 500_000)
    }

    @Test
    fun non200IsUploadException() = run {
        val api = MediaApi(FakeSink(slot("/fail", "fileId", 1), mapOf("url" to "$base/fail?photoIds=1")), OkHttpMediaHttp(), ua)
        assertEquals(413, assertFailsWith<UploadException> { api.uploadFile(ByteArray(10), "f") }.status)
        assertEquals(413, assertFailsWith<UploadException> { api.uploadPhoto(ByteArray(10)) }.status)
        assertEquals(404, assertFailsWith<UploadException> { api.uploadChunked("$base/missing", ByteArray(10)) }.status)
        // connection refused -> UploadException wrapping the IOException
        val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val refusedSlot = mapOf("info" to listOf(mapOf("url" to "http://127.0.0.1:$closed/x", "fileId" to 1, "token" to "t")))
        val io = assertFailsWith<UploadException> { MediaApi(FakeSink(refusedSlot), OkHttpMediaHttp(), ua).uploadFile(ByteArray(1), "f") }
        assertIs<java.io.IOException>(io.cause)
    }

    @Test
    fun cancellationCancelsTheCall(): Unit = runBlocking(Dispatchers.Default) {
        val http = OkHttpMediaHttp()
        val job = launch(start = CoroutineStart.DEFAULT) { http.post("$base/slow", emptyList(), ByteArray(10)) }
        assertTrue(slowArrived.await(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertTrue(job.isCancelled)
        // the OkHttp call is gone
        withTimeout(2_000) { while (http.client.dispatcher.runningCallsCount() != 0) kotlinx.coroutines.delay(10) }
    }

    private fun assertMonotonicTo(seen: List<Pair<Long, Long>>, total: Long) {
        val list = synchronized(seen) { seen.toList() }
        assertTrue(list.isNotEmpty())
        assertTrue(list.zipWithNext().all { (a, b) -> a.first <= b.first }, "not monotonic: $list")
        assertTrue(list.all { it.second == total })
        assertEquals(total, list.last().first)
    }
}
