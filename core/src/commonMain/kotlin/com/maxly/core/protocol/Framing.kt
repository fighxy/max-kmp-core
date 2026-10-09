package com.maxly.core.protocol

/*
 * Binary TCP framing of the Max (OneMe) protocol.
 *
 * Wire layout (docs/protocol.md §B.1 / §B.5; verified against kolibri
 * `kolibri-net/src/protocol/codec.rs` and PyMax `protocol/tcp/framing.py`, struct ">BBHHI"),
 * 10 bytes, big-endian:
 *
 * ```
 * offset  size  field
 * [0]     1     ver        protocol version, 10 for TCP
 * [1]     1     cmd        command type, see CmdType
 * [2..4]  2     seq        u16 sequence number
 * [4..6]  2     opcode     u16, see Opcode
 * [6..10] 4     packedLen  bits 31..24 = compression flag byte, bits 23..0 = body length
 * [10..]        body       MessagePack, optionally compressed
 * ```
 *
 * The compression flag is a whole byte, not a single bit: kolibri sends `(rawLen / compLen) + 1`
 * for LZ4-block bodies, and PyMax treats `0x01..0x7F` as LZ4 block and `0xFF` as Zstd. Zero means
 * "not compressed". Compression itself lives in Compression.kt; [decodePacketBody] and
 * [encodePacketCompressed] call [Compression] for the body.
 */

/** Size of the fixed wire header in bytes. */
const val HEADER_SIZE: Int = 10

/** `ver` byte used by the binary TCP transport (the WebSocket JSON variant uses 11). */
const val PROTOCOL_VERSION: Byte = 10

/** Largest body length that fits in the 24-bit length part of `packedLen`. */
const val MAX_BODY_LENGTH: Int = 0x00FF_FFFF

/** Largest `seq` value (the wire field is an unsigned 16-bit integer). */
const val MAX_SEQ: Int = 0xFFFF

/**
 * Decoded 10-byte packet header.
 *
 * Field types versus wire widths:
 * - [version]: 1 byte on the wire, `Byte` holds it exactly.
 * - [cmd]: 1 byte on the wire, `Byte` holds it exactly. Interpret it with [CmdType.fromValue].
 * - [seq]: **u16** on the wire. Stored as `Int` and must be in `0..65535`.
 * - [opcode]: **u16** on the wire. Stored as `Short` holding the same 16 bits, so codes above
 *   32767 appear negative; use [opcodeValue] for the unsigned value. All known opcodes are ≤ 306.
 * - [length]: the low **24 bits** of `packedLen`, i.e. the length of the body as it is on the wire
 *   (after compression, if any). Must be in `0..MAX_BODY_LENGTH`.
 * - [compressed]: `true` when the flag byte (high byte of `packedLen`) is non-zero.
 * - [compressionFlag]: the raw flag byte, `0..255`. A `Boolean` alone cannot carry it, and the
 *   exact value matters on the wire (kolibri's ratio hint, PyMax's `0xFF` = Zstd), so it is kept
 *   next to [compressed]. It defaults to `1` when `compressed = true` and to `0` otherwise, and it
 *   must agree with [compressed].
 */
data class PacketHeader(
    val version: Byte,
    val cmd: Byte,
    val seq: Int,
    val opcode: Short,
    val length: Int,
    val compressed: Boolean,
    val compressionFlag: Int = if (compressed) 1 else 0,
) {
    init {
        require(seq in 0..MAX_SEQ) { "seq out of u16 range: $seq" }
        require(length in 0..MAX_BODY_LENGTH) { "length out of 24-bit range: $length" }
        require(compressionFlag in 0..0xFF) { "compressionFlag out of byte range: $compressionFlag" }
        require(compressed == (compressionFlag != 0)) {
            "compressed=$compressed contradicts compressionFlag=$compressionFlag"
        }
    }

    /** [opcode] as an unsigned value `0..65535`. */
    val opcodeValue: Int get() = opcode.toInt() and 0xFFFF

    /** [cmd] as an unsigned value `0..255`. */
    val cmdValue: Int get() = cmd.toInt() and 0xFF

    /** Body compression format from [compressionFlag], or `null` for an unknown flag value. */
    val compressionFormat: CompressionFormat? get() = CompressionFormat.fromFlag(compressionFlag)
}

/**
 * Values of the `cmd` header byte.
 *
 * Conflict on `cmd = 2` (docs/protocol.md §B.2–B.3, open question K1): kolibri calls it
 * `NOT_FOUND` and treats it as a reply matched to a pending request by `seq`; PyMax calls it
 * `EVENT` and never resolves a pending request with it. Both readings are kept as separate
 * entries with the same [value]; [fromValue] returns every entry for a byte, and callers pick the
 * interpretation. Likewise `0` is a request when sent by the client and a push when received
 * (kolibri `REQUEST` / `PUSH`).
 */
enum class CmdType(val value: Byte) {
    /** Outgoing request (both sources). */
    REQUEST(0),

    /** Incoming server push; same byte as [REQUEST], told apart by direction (kolibri `PUSH`). */
    PUSH(0),

    /** Successful reply. kolibri `OK`; PyMax calls the same value `RESPONSE`. */
    OK(1),

    /** kolibri reading of `2`: "not found" reply to a pending request. */
    NOT_FOUND(2),

    /** PyMax reading of `2`: a separate event frame, not a reply. */
    EVENT(2),

    /** Error reply (both sources). */
    ERROR(3);

    companion object {
        /** Every entry sharing the wire byte [value] (e.g. `[NOT_FOUND, EVENT]` for 2); empty if unknown. */
        fun fromValue(value: Byte): List<CmdType> = entries.filter { it.value == value }

        /**
         * One entry for [value]. For the ambiguous bytes this returns the kolibri reading
         * ([REQUEST] for 0, [NOT_FOUND] for 2) unless [preferPyMax] is set, in which case 2 maps to
         * [EVENT]. Returns `null` for unknown bytes.
         */
        fun fromValueOrNull(value: Byte, preferPyMax: Boolean = false): CmdType? = when (value.toInt()) {
            0 -> REQUEST
            1 -> OK
            2 -> if (preferPyMax) EVENT else NOT_FOUND
            3 -> ERROR
            else -> null
        }
    }
}

/** Encodes [header] into exactly [HEADER_SIZE] big-endian bytes. */
fun encodeHeader(header: PacketHeader): ByteArray {
    val out = ByteArray(HEADER_SIZE)
    writeHeader(header, out)
    return out
}

/**
 * Decodes the first [HEADER_SIZE] bytes of [bytes]. Extra trailing bytes are ignored.
 *
 * @throws IllegalArgumentException if [bytes] is shorter than [HEADER_SIZE].
 */
fun decodeHeader(bytes: ByteArray): PacketHeader {
    require(bytes.size >= HEADER_SIZE) {
        "header needs $HEADER_SIZE bytes, got ${bytes.size}"
    }
    val seq = readU16(bytes, 2)
    val opcode = readU16(bytes, 4).toShort()
    val packedLen = readU32(bytes, 6)
    val flag = (packedLen ushr 24) and 0xFF
    val length = packedLen and MAX_BODY_LENGTH
    return PacketHeader(
        version = bytes[0],
        cmd = bytes[1],
        seq = seq,
        opcode = opcode,
        length = length,
        compressed = flag != 0,
        compressionFlag = flag,
    )
}

/**
 * Builds a full packet: the encoded [header] followed by [body].
 *
 * @throws IllegalArgumentException if `header.length != body.size`.
 */
fun encodePacket(header: PacketHeader, body: ByteArray): ByteArray {
    require(header.length == body.size) {
        "header.length=${header.length} does not match body size ${body.size}"
    }
    val out = ByteArray(HEADER_SIZE + body.size)
    writeHeader(header, out)
    body.copyInto(out, destinationOffset = HEADER_SIZE)
    return out
}

/**
 * Splits one complete packet into its header and body (still compressed if the flag is set).
 *
 * @throws IllegalArgumentException if [bytes] is shorter than [HEADER_SIZE] or its size is not
 * exactly `HEADER_SIZE + header.length`.
 */
fun decodePacket(bytes: ByteArray): Pair<PacketHeader, ByteArray> {
    val header = decodeHeader(bytes)
    val expected = HEADER_SIZE + header.length
    require(bytes.size == expected) {
        "packet size ${bytes.size} does not match header (expected $expected = $HEADER_SIZE + ${header.length})"
    }
    return header to bytes.copyOfRange(HEADER_SIZE, expected)
}

/**
 * Splits one complete packet like [decodePacket] and decompresses the body with [Compression]
 * according to the header flag (a Zstd / LZ4-frame magic number overrides the flag, see
 * [Compression.decompress]).
 *
 * @throws IllegalArgumentException on a malformed packet or an unknown compression flag.
 * @throws CompressionException (an [IllegalArgumentException]) on a malformed or oversized
 * compressed body.
 */
fun decodePacketBody(bytes: ByteArray): Pair<PacketHeader, ByteArray> {
    val (header, body) = decodePacket(bytes)
    val format = requireNotNull(header.compressionFormat) {
        "unknown compression flag 0x${header.compressionFlag.toString(16)}"
    }
    return header to Compression.decompress(body, format)
}

/**
 * Builds a packet from an uncompressed [rawBody] following the kolibri rule: bodies of at least
 * [COMPRESSION_THRESHOLD] bytes are compressed with [format], and the flag byte is set to the
 * kolibri ratio hint `(rawLen / compLen) + 1` for LZ4 block (clamped to `1..0x7F`, see
 * [CompressionFormat.toFlag]; kolibri truncates it to a byte instead, which could wrap to `0` or
 * into the unknown `0x80..0xFE` range). Unlike kolibri, a body whose compressed form is not
 * smaller than the raw body is sent uncompressed (flag `0`); this always happens for
 * [CompressionFormat.ZSTD], whose encoder is non-compressing.
 *
 * @throws IllegalArgumentException if [format] has no known wire flag (LZ4 frame) and the body
 * would be sent compressed.
 */
fun encodePacketCompressed(
    version: Byte,
    cmd: Byte,
    seq: Int,
    opcode: Short,
    rawBody: ByteArray,
    format: CompressionFormat = CompressionFormat.LZ4_BLOCK,
): ByteArray {
    var effective = if (Compression.shouldCompress(rawBody.size)) format else CompressionFormat.NONE
    var body = Compression.compress(rawBody, effective)
    if (effective != CompressionFormat.NONE && body.size >= rawBody.size) {
        effective = CompressionFormat.NONE
        body = rawBody
    }
    val ratio = if (body.isEmpty()) 1 else rawBody.size / body.size + 1
    val flag = requireNotNull(CompressionFormat.toFlag(effective, ratio)) {
        "$effective has no known wire flag"
    }
    val header = PacketHeader(
        version = version,
        cmd = cmd,
        seq = seq,
        opcode = opcode,
        length = body.size,
        compressed = flag != 0,
        compressionFlag = flag,
    )
    return encodePacket(header, body)
}

/**
 * Total size (header + body) of the packet starting at [offset] in [buffer], or `null` if fewer
 * than [HEADER_SIZE] bytes are available there. Useful for stream reassembly.
 */
fun packetTotalLength(buffer: ByteArray, offset: Int = 0): Int? {
    if (buffer.size - offset < HEADER_SIZE) return null
    return HEADER_SIZE + (readU32(buffer, offset + 6) and MAX_BODY_LENGTH)
}

private fun writeHeader(header: PacketHeader, out: ByteArray) {
    out[0] = header.version
    out[1] = header.cmd
    writeU16(out, 2, header.seq)
    writeU16(out, 4, header.opcodeValue)
    writeU32(out, 6, (header.compressionFlag shl 24) or header.length)
}

private fun writeU16(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value ushr 8).toByte()
    out[offset + 1] = value.toByte()
}

private fun writeU32(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value ushr 24).toByte()
    out[offset + 1] = (value ushr 16).toByte()
    out[offset + 2] = (value ushr 8).toByte()
    out[offset + 3] = value.toByte()
}

private fun readU16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

private fun readU32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
