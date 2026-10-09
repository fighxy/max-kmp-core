package com.maxly.core.media

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadBodyTest {
    private val data = ByteArray(200_000) { (it * 31).toByte() }

    /** A file-like source that records every read. */
    private class Tracked(val bytes: ByteArray, override val filePath: String? = "/tmp/x.bin") : UploadSource {
        val reads = ArrayList<Pair<Long, Int>>()
        var closed = false
        override val size: Long get() = bytes.size.toLong()
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            reads += position to length
            return ByteArrayUploadSource(bytes).read(position, buffer, offset, length)
        }
        override fun close() { closed = true }
    }

    @Test
    fun byteArraySource() {
        val src = ByteArrayUploadSource(byteArrayOf(1, 2, 3, 4, 5))
        val buf = ByteArray(10)
        assertEquals(3, src.read(2, buf, 1, 9))
        assertContentEquals(byteArrayOf(0, 3, 4, 5), buf.copyOfRange(0, 4))
        assertEquals(-1, src.read(5, buf, 0, 1))
        assertContentEquals(byteArrayOf(2, 3), src.readFully(1, 2))
        assertFailsWith<UploadException> { src.readFully(4, 2) }
        assertNull(src.filePath)
    }

    @Test
    fun partsAreWrittenInSegments() {
        val src = Tracked(data)
        val body = UploadBody(listOf(UploadBody.Part.Bytes("head".encodeToByteArray()), UploadBody.Part.Range(src, 1000, 150_000), UploadBody.Part.Bytes("tail".encodeToByteArray())))
        assertEquals(150_008L, body.contentLength)
        val totals = ArrayList<Long>()
        val out = ArrayList<Byte>()
        body.writeTo(segment = 65_536, written = { totals += it }) { b, off, n -> for (i in off until off + n) out += b[i] }
        assertContentEquals("head".encodeToByteArray() + data.copyOfRange(1000, 151_000) + "tail".encodeToByteArray(), out.toByteArray())
        assertEquals(listOf(4L, 65_540L, 131_076L, 150_004L, 150_008L), totals)
        assertEquals(listOf(1000L to 65_536, 66_536L to 65_536, 132_072L to 18_928), src.reads)
        assertContentEquals(out.toByteArray(), body.toByteArray())
        assertNull(body.wholeFilePath)
    }

    @Test
    fun wholeFileAndValidation() {
        val src = Tracked(data)
        assertEquals("/tmp/x.bin", UploadBody.of(src).wholeFilePath)
        assertNull(UploadBody.range(src, 1, data.size - 1L).wholeFilePath)
        assertNull(UploadBody.of(data).wholeFilePath)
        assertFailsWith<IllegalArgumentException> { UploadBody.range(src, 100, data.size.toLong()) }
        assertEquals(0L, UploadBody.EMPTY.contentLength)
        // a source shorter than it claims
        val short = object : UploadSource {
            override val size = 10L
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = -1
        }
        assertFailsWith<UploadException> { UploadBody.of(short).toByteArray() }
    }

    @Test
    fun streamingMediaHttpGetsSourceBodies() = runTest {
        // a client overriding upload() sees the source ranges, never a full copy
        val bodies = ArrayList<UploadBody>()
        val http = object : MediaHttp {
            override suspend fun request(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray, progress: UploadProgress?) =
                error("request() must not be used")
            override suspend fun upload(method: String, url: String, headers: List<Pair<String, String>>, body: UploadBody, progress: UploadProgress?): HttpResponse {
                bodies += body
                return HttpResponse(200, if (method == "GET") "0".encodeToByteArray() else ByteArray(0))
            }
        }
        val src = Tracked(data)
        val api = MediaApi(com.maxly.core.auth.RequestSink { _, _ -> error("no socket") }, http)
        assertEquals(0L, api.uploadChunked("https://cdn/x", src, chunkSize = 64_000, concurrency = 2, uploadName = "n"))
        assertEquals(0L, bodies.first().contentLength)
        val chunks = bodies.drop(1).map { it.parts.single() as UploadBody.Part.Range }
        assertEquals(listOf(0L, 64_000L, 128_000L, 192_000L), chunks.map { it.start }.sorted())
        assertTrue(chunks.all { it.source === src })
        assertTrue(src.reads.isEmpty(), "the client decides when to read")
    }
}
