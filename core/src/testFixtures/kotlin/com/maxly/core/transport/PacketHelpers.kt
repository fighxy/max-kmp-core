package com.maxly.core.transport

import com.maxly.core.protocol.CmdType
import com.maxly.core.protocol.Compression
import com.maxly.core.protocol.CompressionFormat
import com.maxly.core.protocol.DefaultMessagePackCodec
import com.maxly.core.protocol.PROTOCOL_VERSION
import com.maxly.core.protocol.PacketHeader
import com.maxly.core.protocol.encodePacket
import com.maxly.core.protocol.encodePacketCompressed

/** Builds a framed packet with MessagePack body (no compression). */
fun packet(cmd: CmdType, seq: Int, opcode: Int, payload: Any?): ByteArray =
    encodePacketCompressed(
        PROTOCOL_VERSION, cmd.value, seq, opcode.toShort(),
        if (payload == null) ByteArray(0) else DefaultMessagePackCodec.encode(payload),
        CompressionFormat.NONE,
    )

fun ok(seq: Int, opcode: Int, payload: Any? = null): ByteArray = packet(CmdType.OK, seq, opcode, payload)
fun errorReply(seq: Int, opcode: Int, payload: Any?): ByteArray = packet(CmdType.ERROR, seq, opcode, payload)
fun notFound(seq: Int, opcode: Int, payload: Any? = null): ByteArray = packet(CmdType.NOT_FOUND, seq, opcode, payload)
fun push(opcode: Int, payload: Any?): ByteArray = packet(CmdType.PUSH, 0, opcode, payload)

/**
 * Builds a packet whose MessagePack body is always compressed with [format] (no threshold), with
 * the flag a server would send: the kolibri ratio hint for LZ4 block, `0xFF` for Zstd.
 */
fun compressedPacket(cmd: CmdType, seq: Int, opcode: Int, payload: Any?, format: CompressionFormat): ByteArray {
    val raw = DefaultMessagePackCodec.encode(payload)
    val body = Compression.compress(raw, format)
    val flag = if (format == CompressionFormat.ZSTD) 0xFF else (raw.size / body.size + 1).coerceIn(1, 0x7F)
    val header = PacketHeader(PROTOCOL_VERSION, cmd.value, seq, opcode.toShort(), body.size, compressed = true, compressionFlag = flag)
    return encodePacket(header, body)
}
