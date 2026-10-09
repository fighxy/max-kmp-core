package com.maxly.core.transport

import com.maxly.core.protocol.decodePayloadPacket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketReassemblerTest {

    @Test
    fun frameSplitAcrossChunks() {
        val frame = ok(5, 64, mapOf("text" to "hello world"))
        val r = PacketReassembler()
        assertTrue(r.feed(frame.copyOfRange(0, 3)).isEmpty()) // inside the header
        assertTrue(r.feed(frame.copyOfRange(3, 12)).isEmpty()) // header done, body partial
        val out = r.feed(frame.copyOfRange(12, frame.size))
        assertEquals(1, out.size)
        assertContentEquals(frame, out[0])
        assertEquals(0, r.bufferedBytes)
    }

    @Test
    fun byteByByte() {
        val frame = push(128, mapOf("a" to 1))
        val r = PacketReassembler()
        val got = frame.indices.flatMap { r.feed(frame, it, 1) }
        assertEquals(1, got.size)
        assertContentEquals(frame, got[0])
    }

    @Test
    fun severalFramesInOneChunkPlusPartialTail() {
        val a = ok(1, 1)
        val b = push(129, listOf(1, 2, 3))
        val c = ok(2, 49, mapOf("k" to "v"))
        val r = PacketReassembler()
        val out = r.feed(a + b + c.copyOfRange(0, 11))
        assertEquals(2, out.size)
        assertContentEquals(a, out[0])
        assertContentEquals(b, out[1])
        assertEquals(11, r.bufferedBytes)
        val rest = r.feed(c.copyOfRange(11, c.size))
        assertContentEquals(c, rest.single())
        assertEquals(mapOf("k" to "v"), decodePayloadPacket(rest.single()).second)
    }

    @Test
    fun overflowThrowsAndResets() {
        val r = PacketReassembler(maxBufferSize = 16)
        assertFailsWith<IllegalStateException> { r.feed(ByteArray(17)) }
        assertEquals(0, r.bufferedBytes)
    }
}
