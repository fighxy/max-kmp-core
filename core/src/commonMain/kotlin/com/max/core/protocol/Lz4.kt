package com.max.core.protocol

/**
 * LZ4 block and frame codecs, pure Kotlin, written from the LZ4 block / frame format
 * specifications (https://github.com/lz4/lz4/blob/dev/doc/).
 *
 * Block format as used on the wire (kolibri `compress_lz4_block` = `lz4_flex::block::compress`,
 * PyMax `Lz4BlockCompression`): a bare sequence stream with **no uncompressed-size prefix**, so the
 * decoder grows its output and stops at the end of the input, capped by a max-size guard
 * (kolibri: 32 MiB, see [MAX_DECOMPRESSED_SIZE]). Like both references the decoder accepts a
 * block that ends right after a literal run or right after a match.
 *
 * Every sequence: token (high nibble literal length, low nibble match length - 4, 15 = extended
 * by 255-continuation bytes), literals, 2-byte little-endian offset (1..65535), extended match
 * length. The last sequence carries literals only.
 */
object Lz4 {
    private const val MIN_MATCH = 4
    private const val MAX_OFFSET = 65535

    /** The last 5 bytes are always literals (LZ4 end-of-block rule). */
    private const val LAST_LITERALS = 5

    /** The last match must start at least 12 bytes before the end of the block. */
    private const val MF_LIMIT = 12

    private const val HASH_LOG = 16

    private const val FRAME_MAGIC = 0x184D2204
    private const val FRAME_BLOCK_SIZE = 64 * 1024

    /** `true` if [data] starts with the LZ4 frame magic `04 22 4D 18`. */
    fun hasFrameMagic(data: ByteArray): Boolean = data.size >= 4 && readLe32(data, 0) == FRAME_MAGIC

    // ── Block ───────────────────────────────────────────────────────

    /**
     * Compresses [src] into one LZ4 block (no size prefix). Greedy single-probe hash table over a
     * 64 KiB window; follows the end-of-block rules so any conforming decoder accepts the output.
     */
    fun compressBlock(src: ByteArray): ByteArray {
        val n = src.size
        val out = ByteSink(Int.MAX_VALUE, n + n / 255 + 16)
        var anchor = 0
        if (n >= MF_LIMIT + 1) {
            val table = IntArray(1 shl HASH_LOG) { -1 }
            val lastMatchStart = n - MF_LIMIT
            val matchEndLimit = n - LAST_LITERALS
            var ip = 0
            while (ip <= lastMatchStart) {
                val seq = readLe32(src, ip)
                val h = hash(seq)
                var ref = table[h]
                table[h] = ip
                if (ref < 0 || ip - ref > MAX_OFFSET || readLe32(src, ref) != seq) {
                    ip++
                    continue
                }
                var start = ip
                while (start > anchor && ref > 0 && src[start - 1] == src[ref - 1]) {
                    start--
                    ref--
                }
                var end = ip + MIN_MATCH
                var r = ref + (end - start)
                while (end < matchEndLimit && src[end] == src[r]) {
                    end++
                    r++
                }
                writeSequence(out, src, anchor, start - anchor, start - ref, end - start)
                ip = end
                anchor = end
                if (ip - 2 in 0..lastMatchStart) table[hash(readLe32(src, ip - 2))] = ip - 2
            }
        }
        writeLastLiterals(out, src, anchor, n - anchor)
        return out.toByteArray()
    }

    /**
     * Decompresses one LZ4 block (no size prefix) with a [maxSize] output guard.
     *
     * @throws CompressionException on truncated input, a zero offset, an offset before the start
     * of the output, or output larger than [maxSize].
     */
    fun decompressBlock(src: ByteArray, maxSize: Int = MAX_DECOMPRESSED_SIZE): ByteArray {
        val out = ByteSink(maxSize, minOf(maxSize, maxOf(64, src.size * 4)))
        decodeBlockInto(src, 0, src.size, out, 0, maxSize)
        return out.toByteArray()
    }

    /**
     * Decodes the block in `src[from until to]`, appending to [out]. Matches may reach back to
     * `out` index [windowStart] (the frame start for linked blocks, the block start otherwise).
     * Each loop iteration consumes at least one input byte, so it always terminates.
     */
    private fun decodeBlockInto(src: ByteArray, from: Int, to: Int, out: ByteSink, windowStart: Int, maxSize: Int) {
        var pos = from
        while (pos < to) {
            val token = src[pos++].toInt() and 0xFF
            var litLen = token ushr 4
            if (litLen == 15) {
                val r = readExtendedLength(src, pos, to, litLen, maxSize)
                litLen = r.first
                pos = r.second
            }
            if (litLen > 0) {
                if (litLen > to - pos) throw CompressionException("LZ4 block: literal run past end of input")
                out.write(src, pos, litLen)
                pos += litLen
            }
            if (pos >= to) break
            if (to - pos < 2) throw CompressionException("LZ4 block: truncated match offset")
            val offset = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            if (offset == 0) throw CompressionException("LZ4 block: zero match offset")
            var matchLen = (token and 0x0F) + MIN_MATCH
            if ((token and 0x0F) == 15) {
                val r = readExtendedLength(src, pos, to, matchLen, maxSize)
                matchLen = r.first
                pos = r.second
            }
            if (offset > out.size - windowStart) {
                throw CompressionException("LZ4 block: match offset $offset before start of output")
            }
            out.copyMatch(offset, matchLen)
        }
    }

    /** Reads 255-continuation bytes after a nibble of 15; returns (length, new position). */
    private fun readExtendedLength(src: ByteArray, start: Int, to: Int, base: Int, maxSize: Int): Pair<Int, Int> {
        var pos = start
        var len = base
        while (true) {
            if (pos >= to) throw CompressionException("LZ4 block: truncated length")
            val b = src[pos++].toInt() and 0xFF
            len += b
            if (len > maxSize) throw CompressionException("decompressed size exceeds limit ($maxSize B)")
            if (b != 255) return len to pos
        }
    }

    private fun hash(v: Int): Int = (v * -0x61c8864f) ushr (32 - HASH_LOG) // * 2654435761

    private fun writeLength(out: ByteSink, extra: Int) {
        var rem = extra
        while (rem >= 255) {
            out.write(255)
            rem -= 255
        }
        out.write(rem)
    }

    private fun writeSequence(out: ByteSink, src: ByteArray, litStart: Int, litLen: Int, offset: Int, matchLen: Int) {
        val ml = matchLen - MIN_MATCH
        out.write((minOf(litLen, 15) shl 4) or minOf(ml, 15))
        if (litLen >= 15) writeLength(out, litLen - 15)
        out.write(src, litStart, litLen)
        out.write(offset and 0xFF)
        out.write(offset ushr 8)
        if (ml >= 15) writeLength(out, ml - 15)
    }

    private fun writeLastLiterals(out: ByteSink, src: ByteArray, litStart: Int, litLen: Int) {
        out.write(minOf(litLen, 15) shl 4)
        if (litLen >= 15) writeLength(out, litLen - 15)
        out.write(src, litStart, litLen)
    }

    // ── Frame ───────────────────────────────────────────────────────

    /**
     * Compresses [src] into one LZ4 frame: independent 64 KiB blocks (stored uncompressed when
     * compression does not help), content size and content checksum (XXH32) present.
     */
    fun compressFrame(src: ByteArray): ByteArray {
        val out = ByteSink(Int.MAX_VALUE, src.size + 64)
        writeLe32(out, FRAME_MAGIC)
        val descriptor = ByteArray(10)
        descriptor[0] = 0x6C.toByte() // version 01, B.Indep, C.Size, C.Checksum
        descriptor[1] = 0x40 // block max size 64 KiB
        for (i in 0 until 8) descriptor[2 + i] = (src.size.toLong() ushr (8 * i)).toByte()
        out.write(descriptor, 0, descriptor.size)
        out.write((XxHash.xxh32(descriptor) ushr 8) and 0xFF)
        var p = 0
        while (p < src.size) {
            val len = minOf(FRAME_BLOCK_SIZE, src.size - p)
            val chunk = src.copyOfRange(p, p + len)
            val comp = compressBlock(chunk)
            if (comp.size < len) {
                writeLe32(out, comp.size)
                out.write(comp, 0, comp.size)
            } else {
                writeLe32(out, len or Int.MIN_VALUE)
                out.write(chunk, 0, len)
            }
            p += len
        }
        writeLe32(out, 0)
        writeLe32(out, XxHash.xxh32(src))
        return out.toByteArray()
    }

    /**
     * Decompresses one or more concatenated LZ4 frames (skippable frames are skipped), verifying
     * the header checksum and the optional block / content checksums and content size.
     *
     * @throws CompressionException on malformed input, a checksum mismatch, a dictionary ID
     * (unsupported), or output larger than [maxSize].
     */
    fun decompressFrame(src: ByteArray, maxSize: Int = MAX_DECOMPRESSED_SIZE): ByteArray {
        val out = ByteSink(maxSize, minOf(maxSize, maxOf(64, src.size * 4)))
        var pos = 0
        if (src.isEmpty()) throw CompressionException("LZ4 frame: empty input")
        while (pos < src.size) {
            need(src, pos, 4, "magic")
            val magic = readLe32(src, pos)
            pos += 4
            if ((magic and -0x10) == 0x184D2A50) {
                need(src, pos, 4, "skippable frame size")
                val skip = readLe32(src, pos).toLong() and 0xFFFFFFFFL
                pos += 4
                if (skip > src.size - pos) throw CompressionException("LZ4 frame: truncated skippable frame")
                pos += skip.toInt()
                continue
            }
            if (magic != FRAME_MAGIC) throw CompressionException("LZ4 frame: bad magic")
            pos = decodeFrame(src, pos, out, maxSize)
        }
        return out.toByteArray()
    }

    private fun decodeFrame(src: ByteArray, start: Int, out: ByteSink, maxSize: Int): Int {
        var pos = start
        need(src, pos, 2, "frame descriptor")
        val flg = src[pos].toInt() and 0xFF
        val bd = src[pos + 1].toInt() and 0xFF
        if (flg ushr 6 != 1) throw CompressionException("LZ4 frame: unsupported version")
        if (flg and 0x02 != 0 || bd and 0x8F != 0) throw CompressionException("LZ4 frame: reserved bits set")
        val independent = flg and 0x20 != 0
        val blockChecksum = flg and 0x10 != 0
        val hasContentSize = flg and 0x08 != 0
        val contentChecksum = flg and 0x04 != 0
        if (flg and 0x01 != 0) throw CompressionException("LZ4 frame: dictionaries are not supported")
        val blockMax = when (bd ushr 4) {
            4 -> 64 * 1024
            5 -> 256 * 1024
            6 -> 1024 * 1024
            7 -> 4 * 1024 * 1024
            else -> throw CompressionException("LZ4 frame: invalid block max size")
        }
        val descLen = 2 + (if (hasContentSize) 8 else 0)
        need(src, pos, descLen + 1, "frame descriptor")
        val hc = src[pos + descLen].toInt() and 0xFF
        if (hc != (XxHash.xxh32(src, pos, descLen) ushr 8) and 0xFF) {
            throw CompressionException("LZ4 frame: header checksum mismatch")
        }
        var contentSize = -1L
        if (hasContentSize) {
            contentSize = (readLe32(src, pos + 2).toLong() and 0xFFFFFFFFL) or (readLe32(src, pos + 6).toLong() shl 32)
            if (contentSize < 0 || contentSize > maxSize) {
                throw CompressionException("decompressed size exceeds limit ($maxSize B)")
            }
        }
        pos += descLen + 1
        val frameStart = out.size
        while (true) {
            need(src, pos, 4, "block size")
            val word = readLe32(src, pos)
            pos += 4
            if (word == 0) break
            val uncompressed = word and Int.MIN_VALUE != 0
            val size = word and Int.MAX_VALUE
            if (size > blockMax) throw CompressionException("LZ4 frame: block larger than block max size")
            need(src, pos, size, "block data")
            val blockStart = out.size
            if (uncompressed) {
                out.write(src, pos, size)
            } else {
                decodeBlockInto(src, pos, pos + size, out, if (independent) blockStart else frameStart, maxSize)
                if (out.size - blockStart > blockMax) throw CompressionException("LZ4 frame: block output too large")
            }
            if (blockChecksum) {
                need(src, pos + size, 4, "block checksum")
                if (readLe32(src, pos + size) != XxHash.xxh32(src, pos, size)) {
                    throw CompressionException("LZ4 frame: block checksum mismatch")
                }
                pos += 4
            }
            pos += size
        }
        val produced = out.size - frameStart
        if (contentSize >= 0 && contentSize != produced.toLong()) {
            throw CompressionException("LZ4 frame: content size mismatch")
        }
        if (contentChecksum) {
            need(src, pos, 4, "content checksum")
            if (readLe32(src, pos) != XxHash.xxh32(out.buf, frameStart, produced)) {
                throw CompressionException("LZ4 frame: content checksum mismatch")
            }
            pos += 4
        }
        return pos
    }

    private fun need(src: ByteArray, pos: Int, count: Int, what: String) {
        if (count < 0 || pos > src.size || count > src.size - pos) {
            throw CompressionException("LZ4 frame: truncated $what")
        }
    }

    private fun writeLe32(out: ByteSink, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write(v ushr 24)
    }

    internal fun readLe32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
}
