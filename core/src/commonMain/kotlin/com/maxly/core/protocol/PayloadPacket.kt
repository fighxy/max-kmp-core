package com.maxly.core.protocol

/*
 * Packet helpers that combine framing (Framing.kt), compression (Compression.kt) and the
 * MessagePack codec (MessagePack.kt): payload value <-> full packet bytes.
 *
 * An absent payload is an empty body, following PyMax (`MsgpackPayloadCodec.encode(None)` returns
 * `b""`) and kolibri (`empty_payload_packet`, e.g. PING): `payload = null` encodes to a
 * zero-length body, and a zero-length body decodes to `null`. A MessagePack `nil` body (`0xc0`)
 * also decodes to `null`.
 */

/**
 * Serializes [payload] with [codec] and builds a packet through [encodePacketCompressed].
 *
 * [format] defaults to [CompressionFormat.NONE] (PyMax never compresses outgoing bodies). Pass
 * [CompressionFormat.LZ4_BLOCK] to get the kolibri behaviour (bodies of [COMPRESSION_THRESHOLD]
 * bytes or more are LZ4-compressed when that makes them smaller); [com.maxly.core.transport.MaxTransport] does so.
 *
 * @throws MessagePackException if [payload] contains an unsupported type.
 * @throws IllegalArgumentException if the header fields are out of range or the body is longer
 * than [MAX_BODY_LENGTH].
 */
fun encodePayloadPacket(
    version: Byte,
    cmd: Byte,
    seq: Int,
    opcode: Short,
    payload: Any?,
    codec: MessagePackCodec = DefaultMessagePackCodec,
    format: CompressionFormat = CompressionFormat.NONE,
): ByteArray {
    val rawBody = if (payload == null) ByteArray(0) else codec.encode(payload)
    return encodePacketCompressed(version, cmd, seq, opcode, rawBody, format)
}

/**
 * Splits and decompresses one complete packet with [decodePacketBody] and decodes its body with
 * [codec]. An empty body gives `null`.
 *
 * @throws IllegalArgumentException on a malformed packet, an unknown compression flag or a
 * malformed compressed body ([CompressionException]).
 * @throws MessagePackException if the body is not valid MessagePack.
 */
fun decodePayloadPacket(
    bytes: ByteArray,
    codec: MessagePackCodec = DefaultMessagePackCodec,
): Pair<PacketHeader, Any?> {
    val (header, body) = decodePacketBody(bytes)
    return header to (if (body.isEmpty()) null else codec.decode(body))
}
