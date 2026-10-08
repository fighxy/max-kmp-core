package com.max.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun roundTripPacket() {
        val body = bytes(0x81, 0xA1, 0x61, 0x01)
        val header = PacketHeader(
            version = PROTOCOL_VERSION,
            cmd = CmdType.REQUEST.value,
            seq = 0xFFFF,
            opcode = Opcode.MSG_SEND.value.toShort(),
            length = body.size,
            compressed = false,
        )
        val (decoded, decodedBody) = decodePacket(encodePacket(header, body))
        assertEquals(header, decoded)
        assertContentEquals(body, decodedBody)
        assertEquals(Opcode.MSG_SEND, Opcode.fromValue(decoded.opcode))
    }

    @Test
    fun roundTripKeepsFullFlagByteAndLargeValues() {
        val header = PacketHeader(
            version = PROTOCOL_VERSION,
            cmd = 3,
            seq = 40000,
            opcode = 0xFFFF.toShort(),
            length = MAX_BODY_LENGTH,
            compressed = true,
            compressionFlag = 0xFF,
        )
        val decoded = decodeHeader(encodeHeader(header))
        assertEquals(header, decoded)
        assertEquals(0xFFFF, decoded.opcodeValue)
    }

    /** kolibri `tests/vectors.rs` `header_layout_matches_dart_wire_format`: LOGIN, seq 5, body {"a":1}. */
    @Test
    fun matchesKolibriVector() {
        val body = bytes(0x81, 0xA1, 0x61, 0x01)
        val header = PacketHeader(PROTOCOL_VERSION, CmdType.REQUEST.value, 5, Opcode.LOGIN.value.toShort(), body.size, false)
        val expected = bytes(10, 0, 0, 5, 0, 19, 0, 0, 0, 4, 0x81, 0xA1, 0x61, 0x01)
        assertContentEquals(expected, encodePacket(header, body))
    }

    /** PyMax `tests/protocol/test_protocols.py` `test_tcp_framer_uses_expected_header_layout`. */
    @Test
    fun matchesPyMaxVector() {
        val wire = bytes(0x0A, 0x01, 0x01, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00, 0x03)
        val header = decodeHeader(wire)
        assertEquals(10, header.version.toInt())
        assertEquals(listOf(CmdType.OK), CmdType.fromValue(header.cmd))
        assertEquals(0x0100, header.seq)
        assertEquals(Opcode.PING, Opcode.fromValue(header.opcode))
        assertEquals(3, header.length)
        assertTrue(header.compressed)
        assertEquals(2, header.compressionFlag)
        assertContentEquals(wire, encodeHeader(header))
        assertEquals(HEADER_SIZE + 3, packetTotalLength(wire))
    }

    @Test
    fun cmdTwoHasBothReadings() {
        assertEquals(listOf(CmdType.NOT_FOUND, CmdType.EVENT), CmdType.fromValue(2))
        assertEquals(CmdType.NOT_FOUND, CmdType.fromValueOrNull(2))
        assertEquals(CmdType.EVENT, CmdType.fromValueOrNull(2, preferPyMax = true))
        assertNull(CmdType.fromValueOrNull(9))
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { decodeHeader(ByteArray(9)) }
        assertFailsWith<IllegalArgumentException> { decodePacket(ByteArray(3)) }
        val header = PacketHeader(PROTOCOL_VERSION, 0, 1, 1, 4, false)
        val packet = encodePacket(header, ByteArray(4))
        assertFailsWith<IllegalArgumentException> { decodePacket(packet.copyOf(packet.size - 1)) }
        assertFailsWith<IllegalArgumentException> { decodePacket(packet + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { encodePacket(header, ByteArray(3)) }
        assertFailsWith<IllegalArgumentException> { PacketHeader(PROTOCOL_VERSION, 0, 0x10000, 1, 0, false) }
    }

    @Test
    fun opcodeTableIsConsistent() {
        assertEquals(182, Opcode.entries.size)
        assertEquals(Opcode.entries.size, Opcode.entries.map { it.value }.toSet().size)
        assertEquals(Opcode.STORIES_LIST, Opcode.fromValue(208))
        assertNull(Opcode.fromValue(4))
        assertEquals("UNKNOWN(4)", Opcode.nameOf(4))
    }
}
