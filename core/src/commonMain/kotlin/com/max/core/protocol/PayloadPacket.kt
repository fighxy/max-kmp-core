package com.max.core.protocol

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
 * [format] defaults to [CompressionFormat.NONE]: PyMax never compresses outgoing bodies, and the
 * LZ4/Zstd codecs in [Compression] are still stubs. Pass [CompressionFormat.LZ4_BLOCK] to get the
 * kolibri behaviour (bodies of [COMPRESSION_THRESHOLD] bytes or more are compressed) once LZ4 is
 * implemented.
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
 * @throws IllegalArgumentException on a malformed packet or unknown compression flag.
 * @throws MessagePackException if the body is not valid MessagePack.
 * @throws UnsupportedOperationException while the codec for the flagged compression is a stub.
 */
fun decodePayloadPacket(
    bytes: ByteArray,
    codec: MessagePackCodec = DefaultMessagePackCodec,
): Pair<PacketHeader, Any?> {
    val (header, body) = decodePacketBody(bytes)
    return header to (if (body.isEmpty()) null else codec.decode(body))
}
