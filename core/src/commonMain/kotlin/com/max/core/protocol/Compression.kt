package com.max.core.protocol

/**
 * Body compression of the Max (OneMe) binary protocol.
 *
 * The compression flag is the high byte of `packedLen` in the packet header (see Framing.kt):
 * - `0x00`: not compressed;
 * - `0x01..0x7F`: LZ4 block (kolibri sends `(rawLen / compLen) + 1` as a ratio hint;
 *   PyMax treats the whole range as LZ4 block);
 * - `0xFF`: Zstd (PyMax reading).
 *
 * No wire flag for LZ4 frame is known yet; [CompressionFormat.LZ4_FRAME] exists for completeness
 * and has no flag mapping.
 *
 * Codecs are pure Kotlin in commonMain (no dependency, no platform actuals, same decision as the
 * MessagePack codec): [Lz4] (block + frame) and [Zstd] (RFC 8878 decoder, non-compressing
 * encoder). Incoming bodies follow both references: kolibri sniffs the magic number of every
 * compressed body (`28 B5 2F FD` Zstd, `04 22 4D 18` LZ4 frame, anything else LZ4 block), PyMax
 * picks the codec by flag. [decompress] does both: a Zstd or LZ4-frame magic wins over the flag,
 * otherwise the flag decides. This is unambiguous, because a body starting with either magic can
 * never be a valid LZ4 block (its first match offset would point before the start of the output).
 * Outgoing bodies use LZ4 block like kolibri (see [encodePacketCompressed]).
 */

/**
 * Bodies shorter than this many bytes are sent uncompressed (kolibri `COMPRESSION_THRESHOLD`).
 * PyMax never compresses outgoing bodies.
 */
const val COMPRESSION_THRESHOLD: Int = 32

/**
 * Decompression-bomb ceiling for one body (kolibri `MAX_DECOMPRESSED_SIZE`, 32 MiB; PyMax uses
 * 5 MiB). The LZ4 block format carries no uncompressed size, so this is also the LZ4 output cap.
 */
const val MAX_DECOMPRESSED_SIZE: Int = 32 * 1024 * 1024

/**
 * Malformed, truncated or oversized compressed data. Extends [IllegalArgumentException] so callers
 * of [decodePacketBody] see one exception family for every malformed packet.
 */
class CompressionException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Compression formats that can appear on packet bodies. */
enum class CompressionFormat {
    NONE,
    LZ4_BLOCK,
    LZ4_FRAME,
    ZSTD;

    companion object {
        /**
         * Format for a header compression flag byte `0..255`, or `null` if the value is unknown
         * (`0x80..0xFE`).
         */
        fun fromFlag(flag: Int): CompressionFormat? = when (flag) {
            0 -> NONE
            in 0x01..0x7F -> LZ4_BLOCK
            0xFF -> ZSTD
            else -> null
        }

        /**
         * Default flag byte to write for [format]. For LZ4 block kolibri writes a ratio hint;
         * [lz4RatioHint] can pass it (clamped to `1..0x7F`). Returns `null` for [LZ4_FRAME],
         * which has no known flag.
         */
        fun toFlag(format: CompressionFormat, lz4RatioHint: Int = 1): Int? = when (format) {
            NONE -> 0
            LZ4_BLOCK -> lz4RatioHint.coerceIn(1, 0x7F)
            LZ4_FRAME -> null
            ZSTD -> 0xFF
        }
    }
}

/** Compresses and decompresses packet bodies. */
object Compression {
    /** `true` if a body of [size] bytes should be compressed before sending (kolibri rule). */
    fun shouldCompress(size: Int): Boolean = size >= COMPRESSION_THRESHOLD

    /**
     * Compresses [data] with [format]. [CompressionFormat.NONE] returns a copy of [data].
     * [CompressionFormat.ZSTD] produces valid frames made of raw / RLE blocks only (see
     * [Zstd.compress]); it never shrinks non-repetitive data.
     */
    fun compress(data: ByteArray, format: CompressionFormat): ByteArray = when (format) {
        CompressionFormat.NONE -> data.copyOf()
        CompressionFormat.LZ4_BLOCK -> Lz4.compressBlock(data)
        CompressionFormat.LZ4_FRAME -> Lz4.compressFrame(data)
        CompressionFormat.ZSTD -> Zstd.compress(data)
    }

    /**
     * Decompresses [data] flagged as [format]. [CompressionFormat.NONE] returns a copy of [data].
     * For every other format a Zstd or LZ4-frame magic number selects that codec (kolibri sniff);
     * otherwise [format] decides (PyMax).
     *
     * @throws CompressionException on malformed input or output larger than [maxSize].
     */
    fun decompress(
        data: ByteArray,
        format: CompressionFormat,
        maxSize: Int = MAX_DECOMPRESSED_SIZE,
    ): ByteArray {
        if (format == CompressionFormat.NONE) return data.copyOf()
        return when {
            Zstd.hasMagic(data) -> Zstd.decompress(data, maxSize)
            Lz4.hasFrameMagic(data) -> Lz4.decompressFrame(data, maxSize)
            else -> when (format) {
                CompressionFormat.LZ4_BLOCK -> Lz4.decompressBlock(data, maxSize)
                CompressionFormat.LZ4_FRAME -> Lz4.decompressFrame(data, maxSize)
                CompressionFormat.ZSTD -> Zstd.decompress(data, maxSize)
                CompressionFormat.NONE -> data.copyOf()
            }
        }
    }
}

/** Growable output buffer with a hard size cap, shared by the decoders. */
internal class ByteSink(private val maxSize: Int, initialCapacity: Int = 256) {
    var buf: ByteArray = ByteArray(initialCapacity.coerceIn(16, maxOf(16, maxSize)))
        private set
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (extra < 0 || extra > maxSize - size) {
            throw CompressionException("decompressed size exceeds limit ($maxSize B)")
        }
        val need = size + extra
        if (need > buf.size) {
            val doubled = if (buf.size > maxSize / 2) maxSize else buf.size * 2
            buf = buf.copyOf(maxOf(need, doubled))
        }
    }

    fun write(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun write(src: ByteArray, from: Int, length: Int) {
        ensure(length)
        src.copyInto(buf, size, from, from + length)
        size += length
    }

    fun fill(b: Byte, count: Int) {
        ensure(count)
        buf.fill(b, size, size + count)
        size += count
    }

    /** Appends [length] bytes starting [distance] bytes back; overlapping copies repeat the pattern. */
    fun copyMatch(distance: Int, length: Int) {
        ensure(length)
        var src = size - distance
        if (distance >= length) {
            buf.copyInto(buf, size, src, src + length)
            size += length
        } else {
            val end = size + length
            var dst = size
            while (dst < end) buf[dst++] = buf[src++]
            size = end
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
