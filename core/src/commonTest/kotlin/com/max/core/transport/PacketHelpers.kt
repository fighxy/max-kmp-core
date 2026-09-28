package com.max.core.transport

import com.max.core.protocol.CmdType
import com.max.core.protocol.CompressionFormat
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.encodePacketCompressed

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
