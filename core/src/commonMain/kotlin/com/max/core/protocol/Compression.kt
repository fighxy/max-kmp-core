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
 * The codecs are not implemented yet. Candidates: an LZ4/Zstd binding through the native core
 * (e.g. the Rust `lz4_flex` / `zstd` crates) or a pure-Kotlin multiplatform port.
 */

/**
 * Bodies shorter than this many bytes are sent uncompressed (kolibri `COMPRESSION_THRESHOLD`).
 * PyMax never compresses outgoing bodies.
 */
const val COMPRESSION_THRESHOLD: Int = 32

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

/** Compresses and decompresses packet bodies. Every real format is a TODO stub for now. */
object Compression {
    /** `true` if a body of [size] bytes should be compressed before sending (kolibri rule). */
    fun shouldCompress(size: Int): Boolean = size >= COMPRESSION_THRESHOLD

    /** Compresses [data] with [format]. [CompressionFormat.NONE] returns a copy of [data]. */
    fun compress(data: ByteArray, format: CompressionFormat): ByteArray = when (format) {
        CompressionFormat.NONE -> data.copyOf()
        else -> throw UnsupportedOperationException("TODO: choose $format compression library")
    }

    /** Decompresses [data] with [format]. [CompressionFormat.NONE] returns a copy of [data]. */
    fun decompress(data: ByteArray, format: CompressionFormat): ByteArray = when (format) {
        CompressionFormat.NONE -> data.copyOf()
        else -> throw UnsupportedOperationException("TODO: choose $format decompression library")
    }
}
