package com.max.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CompressionTest {
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
    fun noneIsPassThroughAndOthersAreStubs() {
        val data = byteArrayOf(1, 2, 3)
        assertContentEquals(data, Compression.compress(data, CompressionFormat.NONE))
        assertContentEquals(data, Compression.decompress(data, CompressionFormat.NONE))
        assertFailsWith<UnsupportedOperationException> { Compression.compress(data, CompressionFormat.ZSTD) }
        assertFailsWith<UnsupportedOperationException> { Compression.decompress(data, CompressionFormat.LZ4_BLOCK) }
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
}
