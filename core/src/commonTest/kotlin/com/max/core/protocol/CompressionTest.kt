package com.max.core.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressionTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Raw LZ4 block from PyMax `test_tcp_payload_decoder_decompresses_lz4_for_compression_factor_four` (flag 4). */
    private val pyMaxLz4Block = hex(
        "f40a84a6707265666978a27878a464617461b0664a73436c4b437508008f" +
            "a47461696cd92a79010016dfa6726570656174d9684142434404004c5044" +
            "41424344",
    )

    /** `lz4.block.compress(sampleText(2000), store_size=False)` (python-lz4 4.4.5, reference liblz4). */
    private val liblz4Block = hex(
            "f1116d736720303a20636861743d3020757365723d3020746578743d616c706861200600100a260013312600123126003239" +
            "3139280061746f6b656e20060001280013322800123228003238333828006167616d6d612006000128001333280012332800" +
            "323735372800617265706c7920060001280013342800123428003236373628004163686174b8000126001335260012352600" +
            "323539352600726f6e6c696e65200700012a0013362a0013362a002231342a00417573657213000126001337260011371300" +
            "423d343333260091626574612062657461260013382600123826003233353226009170757368207075736826001339260012" +
            "3926003232373126006164656c74612006000128001431890113318a012331398c0172737461747573200700022c00058f01" +
            "123156002331309001836d657373616765200800022e00049501133196011332950101df0108e50104960113319701233934" +
            "970101e10107e701143198011331990123383699010ce90114319c0113319d012337389d0101e5011120060002a700049c01" +
            "13319d012337309d010aed0114319e010328012336329e0102e70108ee011431a301032701233534a3010aef011431a40103" +
            "2001233436a4010af0011432a201031e01233338a1010af10114329d01031b012332399c0101ec0107f20114329801031a01" +
            "233231980102ea0108f10114329a01031b01233133990103e80109f00114329d0112371d0213359b010cef0114329b0103c1" +
            "022339379a010cee0114329a0103c40223383999010ced0114329b0104c4022338319c0101e7011120060001ed0115329a01" +
            "039b012337339b010aed0114329b0104bc022336359c010eee011433a00104bf02233537a1010aef011433a10104bd022334" +
            "38a2010af0011433a00104bb02233430a1010af10114339d0104b9022333329e010cf20114339a0103bb022332349b010ef2" +
            "0114339d010320012331369d010ff201011433a10103c1021338a0010cf2011433a00104c202028b040cf00114339d0103c2" +
            "022339329c010cdd0314339e0103c2022338349d0101e90107ef0114349b0103c0022337369a010aee0114349a0103ba0223" +
            "363799010eed0114349d0103bd022335399c010aec0114349c0103bb022335319b010aeb011434990113319a012334339a01" +
            "0aeb011534960103970123333597010ceb011434930104b90223323795010eec011434970104b90223313937030fed010114" +
            "349e0113319f01a031313220746578743d61",
    )

    /** `lz4.frame.compress(sampleText(500), block_checksum=True, content_checksum=True, store_size=True)`. */
    private val liblz4Frame = hex(
            "04224d187c40f401000000000000b847010000f1116d736720303a20636861743d3020757365723d3020746578743d616c70" +
            "6861200600100a2600133126001231260032393139280061746f6b656e200600012800133228001232280032383338280061" +
            "67616d6d612006000128001333280012332800323735372800617265706c7920060001280013342800123428003236373628" +
            "0000b30001b8000126001335260012352600323539352600726f6e6c696e65200700012a0013362a0013362a002231342a00" +
            "00fc000113000126001337260011371300423d34333326005062657461200500012600133826001238260032333532260050" +
            "7075736820050001260013392600123926003232373126006164656c74612006000128001431890113318a012331398c0172" +
            "737461747573200700022c00058f010390012331309001836d657373616765200800022e00049501503132207573e8c5ed7f" +
            "000000004679e418",
    )

    @Test
    fun flagMapping() {
        assertEquals(CompressionFormat.NONE, CompressionFormat.fromFlag(0))
        assertEquals(CompressionFormat.LZ4_BLOCK, CompressionFormat.fromFlag(1))
        assertEquals(CompressionFormat.LZ4_BLOCK, CompressionFormat.fromFlag(0x7F))
        assertEquals(CompressionFormat.ZSTD, CompressionFormat.fromFlag(0xFF))
        assertNull(CompressionFormat.fromFlag(0x80))
        assertNull(CompressionFormat.toFlag(CompressionFormat.LZ4_FRAME))
    }

    @Test
    fun noneIsPassThrough() {
        val data = byteArrayOf(1, 2, 3)
        assertContentEquals(data, Compression.compress(data, CompressionFormat.NONE))
        assertContentEquals(data, Compression.decompress(data, CompressionFormat.NONE))
    }

    @Test
    fun smallBodyGoesUncompressedThroughFraming() {
        val body = byteArrayOf(0x81.toByte(), 0xA1.toByte(), 0x61, 0x01)
        val packet = encodePacketCompressed(PROTOCOL_VERSION, 0, 5, 19, body)
        val (header, decoded) = decodePacketBody(packet)
        assertEquals(false, header.compressed)
        assertEquals(CompressionFormat.NONE, header.compressionFormat)
        assertContentEquals(body, decoded)
    }

    // ── LZ4 block: fixed vectors ────────────────────────────────────

    @Test
    fun decodesHandBuiltLz4Blocks() {
        // PyMax: literal-only block
        assertContentEquals("hello".encodeToByteArray(), Lz4.decompressBlock(hex("50") + "hello".encodeToByteArray()))
        // token 0x22: 2 literals "ab", match offset 2 length 6 (overlapping copy), then token 0x10: literal "c"
        assertContentEquals("ababababc".encodeToByteArray(), Lz4.decompressBlock(hex("22616202001063")))
        // extended lengths: 15 + 3 = 18 literals "x", match offset 1 length 4 + 15 + 255 + 6 = 280, last literals "!"
        val block = hex("ff03") + ByteArray(18) { 'x'.code.toByte() } + hex("0100ff06") + hex("1021")
        assertContentEquals(ByteArray(18 + 280) { 'x'.code.toByte() } + "!".encodeToByteArray(), Lz4.decompressBlock(block))
        // single zero token: empty output (what LZ4 emits for empty input)
        assertContentEquals(ByteArray(0), Lz4.decompressBlock(hex("00")))
        assertContentEquals(ByteArray(0), Lz4.decompressBlock(ByteArray(0)))
    }

    @Test
    fun decodesReferenceLz4Vectors() {
        val expectedPyMax = DefaultMessagePackCodec.encode(
            linkedMapOf("prefix" to "xx", "data" to "fJsClKCufJsClKCu", "tail" to "y".repeat(42), "repeat" to "ABCD".repeat(26)),
        )
        assertContentEquals(expectedPyMax, Lz4.decompressBlock(pyMaxLz4Block))
        val header = PacketHeader(PROTOCOL_VERSION, 0, 0, 128, pyMaxLz4Block.size, compressed = true, compressionFlag = 4)
        assertContentEquals(expectedPyMax, decodePacketBody(encodePacket(header, pyMaxLz4Block)).second)

        assertContentEquals(sampleText(2000), Lz4.decompressBlock(liblz4Block))
        assertContentEquals(sampleText(500), Lz4.decompressFrame(liblz4Frame))
        // kolibri sniff: an LZ4 frame under an LZ4-block flag is recognised by its magic number
        assertContentEquals(sampleText(500), Compression.decompress(liblz4Frame, CompressionFormat.LZ4_BLOCK))
    }

    // ── LZ4 block: round trips ──────────────────────────────────────

    private fun assertLz4RoundTrip(data: ByteArray) {
        val block = Lz4.compressBlock(data)
        assertContentEquals(data, Lz4.decompressBlock(block), "block round trip of ${data.size} bytes")
        assertContentEquals(data, Compression.decompress(Compression.compress(data, CompressionFormat.LZ4_BLOCK), CompressionFormat.LZ4_BLOCK))
        assertContentEquals(data, Lz4.decompressFrame(Lz4.compressFrame(data)), "frame round trip of ${data.size} bytes")
    }

    @Test
    fun lz4RoundTripsEmptyAndSmall() {
        assertLz4RoundTrip(ByteArray(0))
        for (n in 1..40) assertLz4RoundTrip(ByteArray(n) { (it % 3).toByte() })
        assertLz4RoundTrip("hello, world".encodeToByteArray())
    }

    @Test
    fun lz4RoundTripsHighlyRepetitive() {
        val zeros = ByteArray(100_000)
        assertLz4RoundTrip(zeros)
        assertTrue(Lz4.compressBlock(zeros).size < 500)
        val text = sampleText(50_000)
        assertLz4RoundTrip(text)
        assertTrue(Lz4.compressBlock(text).size < text.size / 3)
    }

    @Test
    fun lz4RoundTripsRandomAndLargerThan64K() {
        val rnd = Random(7)
        assertLz4RoundTrip(rnd.nextBytes(1000))
        val random = rnd.nextBytes(200_000)
        assertLz4RoundTrip(random)
        // incompressible input grows by at most len/255 + 16
        assertTrue(Lz4.compressBlock(random).size <= random.size + random.size / 255 + 16)
        // > 64 KiB with repeats farther apart than the 64 KiB window
        val big = ByteArray(300_000)
        val chunk = rnd.nextBytes(70_000)
        for (i in big.indices) big[i] = chunk[i % chunk.size]
        assertLz4RoundTrip(big)
        assertLz4RoundTrip(sampleText(1_000_000))
    }

    // ── LZ4: malformed input ────────────────────────────────────────

    @Test
    fun lz4RejectsMalformedBlocks() {
        val bad = listOf(
            "010000", // zero offset (PyMax test)
            "5068656c", // literal run past end of input
            "000500", // offset before start of output
            "1061", // literal then nothing: valid, see below
            "106101", // truncated offset
            "f0", // truncated literal length
            "f0ffff", // truncated literal length continuation
            "1f610100ff", // truncated match length continuation
        )
        for (h in bad) {
            if (h == "1061") {
                assertContentEquals("a".encodeToByteArray(), Lz4.decompressBlock(hex(h)))
                continue
            }
            assertFailsWith<CompressionException>("input $h") { Lz4.decompressBlock(hex(h)) }
        }
        // size guard: 18 literals + a 280-byte match exceeds a 100-byte limit
        val block = hex("ff03") + ByteArray(18) + hex("0100ff06")
        assertFailsWith<CompressionException> { Lz4.decompressBlock(block, maxSize = 100) }
        assertFailsWith<CompressionException> { Compression.decompress(block, CompressionFormat.LZ4_BLOCK, maxSize = 100) }
        // a huge length made of 255-continuation bytes is rejected by the guard, not by overflow
        assertFailsWith<CompressionException> { Lz4.decompressBlock(hex("f0") + ByteArray(1000) { -1 } + hex("00"), maxSize = 10_000) }
        // random garbage never escapes as another exception type
        val rnd = Random(3)
        repeat(500) {
            try {
                Lz4.decompressBlock(rnd.nextBytes(rnd.nextInt(1, 64)), maxSize = 1 shl 16)
            } catch (e: CompressionException) {
                // expected
            }
        }
    }

    @Test
    fun lz4FrameRejectsCorruption() {
        val badContent = liblz4Frame.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<CompressionException> { Lz4.decompressFrame(badContent) }
        val badHeader = liblz4Frame.copyOf().also { it[4] = (it[4].toInt() xor 0x04).toByte() }
        assertFailsWith<CompressionException> { Lz4.decompressFrame(badHeader) }
        assertFailsWith<CompressionException> { Lz4.decompressFrame(liblz4Frame.copyOf(liblz4Frame.size - 3)) }
    }

    // ── Outgoing packets (kolibri rule) ─────────────────────────────

    @Test
    fun largeBodyIsLz4CompressedWithRatioHint() {
        val body = DefaultMessagePackCodec.encode(mapOf("text" to "hello ".repeat(100)))
        val packet = encodePacketCompressed(PROTOCOL_VERSION, 0, 7, 64, body)
        val (header, compressedBody) = decodePacket(packet)
        assertTrue(header.compressed)
        assertEquals(body.size / compressedBody.size + 1, header.compressionFlag)
        assertEquals(CompressionFormat.LZ4_BLOCK, header.compressionFormat)
        assertContentEquals(body, Lz4.decompressBlock(compressedBody))
        assertContentEquals(body, decodePacketBody(packet).second)
    }

    @Test
    fun ratioHintIsClampedTo7F() {
        val body = ByteArray(1_000_000)
        val (header, _) = decodePacket(encodePacketCompressed(PROTOCOL_VERSION, 0, 1, 64, body))
        assertEquals(0x7F, header.compressionFlag)
        assertContentEquals(body, decodePacketBody(encodePacketCompressed(PROTOCOL_VERSION, 0, 1, 64, body)).second)
    }

    @Test
    fun incompressibleOrZstdBodyGoesUncompressed() {
        val random = Random(11).nextBytes(500)
        val (h1, b1) = decodePacket(encodePacketCompressed(PROTOCOL_VERSION, 0, 1, 64, random))
        assertEquals(0, h1.compressionFlag)
        assertContentEquals(random, b1)
        // the Zstd encoder never shrinks non-repetitive data, so the body stays uncompressed
        val text = sampleText(500)
        val (h2, b2) = decodePacket(encodePacketCompressed(PROTOCOL_VERSION, 0, 1, 64, text, CompressionFormat.ZSTD))
        assertEquals(0, h2.compressionFlag)
        assertContentEquals(text, b2)
    }
}
