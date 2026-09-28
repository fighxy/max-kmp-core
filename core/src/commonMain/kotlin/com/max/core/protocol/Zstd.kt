package com.max.core.protocol

/**
 * Zstandard codec, pure Kotlin, written from RFC 8878.
 *
 * Decoder ([decompress]): full frame format — raw, RLE and compressed blocks; raw, RLE, Huffman
 * and treeless (repeat-table) literals with 1 or 4 streams; FSE-compressed Huffman weights;
 * predefined, RLE, FSE-compressed and repeat sequence tables; repeat offsets; any number of
 * concatenated frames and skippable frames; the optional content checksum is verified (low 32
 * bits of XXH64). Dictionaries are not supported (a non-zero dictionary ID is rejected). Output is
 * capped by a size guard ([MAX_DECOMPRESSED_SIZE] by default).
 *
 * Encoder ([compress]): **non-compressing**. It emits valid single-segment frames made of raw
 * blocks, or RLE blocks for runs of one byte, with content size and checksum. It exists for
 * round-trip tests and completeness; outgoing packets use LZ4 block (kolibri).
 */
object Zstd {
    private const val MAGIC = -0x02d04ad8 // 0xFD2FB528
    private const val SKIPPABLE_MASK = -0x10 // 0xFFFFFFF0
    private const val SKIPPABLE_MAGIC = 0x184D2A50
    private const val MAX_BLOCK_SIZE = 128 * 1024

    /** `true` if [data] starts with the Zstd frame magic `28 B5 2F FD`. */
    fun hasMagic(data: ByteArray): Boolean = data.size >= 4 && le32(data, 0) == MAGIC

    // ── Encoder ─────────────────────────────────────────────────────

    /**
     * Wraps [src] in one Zstd frame of raw / RLE blocks (≤ 128 KiB each), with frame content size
     * and XXH64 checksum. Does not compress; the output is 3 bytes per block plus a small header
     * larger than [src], except for single-byte runs, which become RLE blocks.
     */
    fun compress(src: ByteArray): ByteArray {
        val n = src.size
        val out = ByteSink(Int.MAX_VALUE, n + 32 + 3 * (n / MAX_BLOCK_SIZE + 1))
        writeLe32(out, MAGIC)
        // single segment, checksum; FCS field size by value
        val fcsFlag = when {
            n < 256 -> 0
            n < 65536 + 256 -> 1
            else -> 2
        }
        out.write((fcsFlag shl 6) or 0x20 or 0x04)
        when (fcsFlag) {
            0 -> out.write(n)
            1 -> {
                val v = n - 256
                out.write(v and 0xFF)
                out.write(v ushr 8)
            }
            else -> writeLe32(out, n)
        }
        var p = 0
        do {
            val len = minOf(MAX_BLOCK_SIZE, n - p)
            val last = p + len == n
            val rle = len > 1 && (1 until len).all { src[p + it] == src[p] }
            val header = (len shl 3) or ((if (rle) 1 else 0) shl 1) or (if (last) 1 else 0)
            out.write(header and 0xFF)
            out.write((header ushr 8) and 0xFF)
            out.write((header ushr 16) and 0xFF)
            if (rle) out.write(src[p].toInt() and 0xFF) else out.write(src, p, len)
            p += len
        } while (p < n)
        writeLe32(out, XxHash.xxh64(src).toInt())
        return out.toByteArray()
    }

    // ── Decoder ─────────────────────────────────────────────────────

    /**
     * Decompresses every frame in [src] (concatenated Zstd frames and skippable frames).
     *
     * @throws CompressionException on malformed input, a checksum or content-size mismatch, a
     * dictionary ID, or output larger than [maxSize].
     */
    fun decompress(src: ByteArray, maxSize: Int = MAX_DECOMPRESSED_SIZE): ByteArray {
        if (src.isEmpty()) throw CompressionException("Zstd: empty input")
        val out = ByteSink(maxSize, minOf(maxSize, maxOf(64, src.size * 4)))
        var pos = 0
        try {
            while (pos < src.size) {
                need(src, pos, 4, "magic")
                val magic = le32(src, pos)
                pos += 4
                if ((magic and SKIPPABLE_MASK) == SKIPPABLE_MAGIC) {
                    need(src, pos, 4, "skippable frame size")
                    val skip = le32(src, pos).toLong() and 0xFFFFFFFFL
                    pos += 4
                    if (skip > src.size - pos) corrupt("truncated skippable frame")
                    pos += skip.toInt()
                    continue
                }
                if (magic != MAGIC) corrupt("bad magic number")
                pos = FrameDecoder(src, out, maxSize).decode(pos)
            }
        } catch (e: IndexOutOfBoundsException) {
            throw CompressionException("Zstd: corrupted input", e)
        }
        return out.toByteArray()
    }

    private class FrameDecoder(val src: ByteArray, val out: ByteSink, val maxSize: Int) {
        val frameStart = out.size
        val rep = intArrayOf(1, 4, 8)
        var huffman: HuffmanTable? = null
        var llTable: FseTable? = null
        var ofTable: FseTable? = null
        var mlTable: FseTable? = null
        var blockMax = MAX_BLOCK_SIZE

        fun decode(start: Int): Int {
            var pos = start
            need(src, pos, 1, "frame header")
            val fhd = src[pos++].toInt() and 0xFF
            val fcsFlag = fhd ushr 6
            val singleSegment = fhd and 0x20 != 0
            if (fhd and 0x08 != 0) corrupt("reserved frame header bit set")
            val hasChecksum = fhd and 0x04 != 0
            val dictIdSize = intArrayOf(0, 1, 2, 4)[fhd and 3]
            val fcsSize = when (fcsFlag) {
                0 -> if (singleSegment) 1 else 0
                1 -> 2
                2 -> 4
                else -> 8
            }
            var windowSize = -1L
            if (!singleSegment) {
                need(src, pos, 1, "window descriptor")
                val wd = src[pos++].toInt() and 0xFF
                val windowLog = 10 + (wd ushr 3)
                val base = 1L shl windowLog
                windowSize = base + (base / 8) * (wd and 7)
            }
            need(src, pos, dictIdSize + fcsSize, "frame header")
            var dictId = 0L
            for (i in 0 until dictIdSize) dictId = dictId or ((src[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += dictIdSize
            if (dictId != 0L) throw CompressionException("Zstd: dictionaries are not supported")
            var contentSize = -1L
            if (fcsSize > 0) {
                var v = 0L
                for (i in 0 until fcsSize) v = v or ((src[pos + i].toLong() and 0xFF) shl (8 * i))
                if (fcsSize == 2) v += 256
                contentSize = v
                pos += fcsSize
                if (contentSize < 0 || contentSize > maxSize - frameStart) {
                    throw CompressionException("decompressed size exceeds limit ($maxSize B)")
                }
            }
            if (singleSegment) windowSize = contentSize
            blockMax = minOf(MAX_BLOCK_SIZE.toLong(), windowSize).toInt()

            while (true) {
                need(src, pos, 3, "block header")
                val bh = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8) or
                    ((src[pos + 2].toInt() and 0xFF) shl 16)
                pos += 3
                val last = bh and 1 != 0
                val type = (bh ushr 1) and 3
                val size = bh ushr 3
                when (type) {
                    0 -> {
                        if (size > blockMax) corrupt("block larger than maximum block size")
                        need(src, pos, size, "raw block")
                        out.write(src, pos, size)
                        pos += size
                    }
                    1 -> {
                        if (size > blockMax) corrupt("block larger than maximum block size")
                        need(src, pos, 1, "RLE block")
                        out.fill(src[pos], size)
                        pos += 1
                    }
                    2 -> {
                        if (size > blockMax) corrupt("block larger than maximum block size")
                        need(src, pos, size, "compressed block")
                        decodeCompressedBlock(pos, pos + size)
                        pos += size
                    }
                    else -> corrupt("reserved block type")
                }
                if (last) break
            }
            val produced = out.size - frameStart
            if (contentSize >= 0 && contentSize != produced.toLong()) corrupt("frame content size mismatch")
            if (hasChecksum) {
                need(src, pos, 4, "content checksum")
                val expected = le32(src, pos)
                if (XxHash.xxh64(out.buf, frameStart, produced).toInt() != expected) {
                    corrupt("content checksum mismatch")
                }
                pos += 4
            }
            return pos
        }

        // ── Compressed block ────────────────────────────────────────

        fun decodeCompressedBlock(start: Int, end: Int) {
            val blockOutStart = out.size
            var pos = start
            // Literals section
            need(src, pos, 1, "literals header", end)
            val b0 = src[pos].toInt() and 0xFF
            val litType = b0 and 3
            val sizeFormat = (b0 ushr 2) and 3
            val literals: ByteArray
            when (litType) {
                0, 1 -> {
                    val regen: Int
                    when (sizeFormat) {
                        0, 2 -> {
                            regen = b0 ushr 3
                            pos += 1
                        }
                        1 -> {
                            need(src, pos, 2, "literals header", end)
                            regen = (b0 ushr 4) + ((src[pos + 1].toInt() and 0xFF) shl 4)
                            pos += 2
                        }
                        else -> {
                            need(src, pos, 3, "literals header", end)
                            regen = (b0 ushr 4) + ((src[pos + 1].toInt() and 0xFF) shl 4) +
                                ((src[pos + 2].toInt() and 0xFF) shl 12)
                            pos += 3
                        }
                    }
                    if (regen > blockMax) corrupt("literals larger than block")
                    if (litType == 0) {
                        need(src, pos, regen, "raw literals", end)
                        literals = src.copyOfRange(pos, pos + regen)
                        pos += regen
                    } else {
                        need(src, pos, 1, "RLE literals", end)
                        literals = ByteArray(regen) { src[pos] }
                        pos += 1
                    }
                }
                else -> {
                    val headerSize: Int
                    val regen: Int
                    val compSize: Int
                    val streams: Int
                    when (sizeFormat) {
                        0, 1 -> {
                            headerSize = 3
                            need(src, pos, 3, "literals header", end)
                            val h = le24(src, pos)
                            regen = (h ushr 4) and 0x3FF
                            compSize = (h ushr 14) and 0x3FF
                            streams = if (sizeFormat == 0) 1 else 4
                        }
                        2 -> {
                            headerSize = 4
                            need(src, pos, 4, "literals header", end)
                            val h = le32(src, pos)
                            regen = (h ushr 4) and 0x3FFF
                            compSize = (h ushr 18) and 0x3FFF
                            streams = 4
                        }
                        else -> {
                            headerSize = 5
                            need(src, pos, 5, "literals header", end)
                            val h = (le32(src, pos).toLong() and 0xFFFFFFFFL) or ((src[pos + 4].toLong() and 0xFF) shl 32)
                            regen = ((h ushr 4) and 0x3FFFF).toInt()
                            compSize = ((h ushr 22) and 0x3FFFF).toInt()
                            streams = 4
                        }
                    }
                    pos += headerSize
                    if (regen > blockMax) corrupt("literals larger than block")
                    need(src, pos, compSize, "compressed literals", end)
                    var streamStart = pos
                    if (litType == 2) {
                        val (tree, used) = HuffmanTable.read(src, pos, pos + compSize)
                        huffman = tree
                        streamStart = pos + used
                    }
                    val table = huffman ?: corrupt("treeless literals without a previous Huffman table")
                    literals = table.decodeStreams(src, streamStart, pos + compSize, regen, streams)
                    pos += compSize
                }
            }

            // Sequences section
            need(src, pos, 1, "sequences header", end)
            var nbSeq = src[pos].toInt() and 0xFF
            pos += 1
            if (nbSeq == 0) {
                if (pos != end) corrupt("trailing bytes after sequences header")
                out.write(literals, 0, literals.size)
                return
            }
            if (nbSeq in 128..254) {
                need(src, pos, 1, "sequences header", end)
                nbSeq = ((nbSeq - 128) shl 8) + (src[pos].toInt() and 0xFF)
                pos += 1
            } else if (nbSeq == 255) {
                need(src, pos, 2, "sequences header", end)
                nbSeq = (src[pos].toInt() and 0xFF) + ((src[pos + 1].toInt() and 0xFF) shl 8) + 0x7F00
                pos += 2
            }
            need(src, pos, 1, "symbol compression modes", end)
            val modes = src[pos].toInt() and 0xFF
            pos += 1
            if (modes and 3 != 0) corrupt("reserved sequence mode bits set")
            var r = readSeqTable(modes ushr 6, pos, end, llTable, LL_DEFAULT, 9, 35)
            llTable = r.first
            pos = r.second
            r = readSeqTable((modes ushr 4) and 3, pos, end, ofTable, OF_DEFAULT, 8, 31)
            ofTable = r.first
            pos = r.second
            r = readSeqTable((modes ushr 2) and 3, pos, end, mlTable, ML_DEFAULT, 9, 52)
            mlTable = r.first
            pos = r.second
            executeSequences(pos, end, nbSeq, literals, llTable!!, ofTable!!, mlTable!!)
            if (out.size - blockOutStart > blockMax) corrupt("block output larger than maximum block size")
        }

        fun readSeqTable(
            mode: Int, start: Int, end: Int, previous: FseTable?,
            default: FseTable, maxLog: Int, maxSymbol: Int,
        ): Pair<FseTable, Int> {
            return when (mode) {
                0 -> default to start
                1 -> {
                    need(src, start, 1, "RLE sequence table", end)
                    val sym = src[start].toInt() and 0xFF
                    if (sym > maxSymbol) corrupt("RLE sequence symbol out of range")
                    FseTable.rle(sym) to start + 1
                }
                2 -> {
                    val (table, used) = FseTable.read(src, start, end, maxLog, maxSymbol)
                    table to start + used
                }
                else -> (previous ?: corrupt("repeat sequence table without a previous table")) to start
            }
        }

        fun executeSequences(
            start: Int, end: Int, nbSeq: Int, literals: ByteArray,
            ll: FseTable, of: FseTable, ml: FseTable,
        ) {
            val bits = BackwardBitReader(src, start, end)
            var llState = bits.read(ll.accuracyLog).toInt()
            var ofState = bits.read(of.accuracyLog).toInt()
            var mlState = bits.read(ml.accuracyLog).toInt()
            var litPos = 0
            for (i in 0 until nbSeq) {
                val ofCode = of.symbol[ofState].toInt()
                val llCode = ll.symbol[llState].toInt()
                val mlCode = ml.symbol[mlState].toInt()
                if (ofCode > 31) corrupt("offset code out of range")
                val offsetValue = (1L shl ofCode) + bits.read(ofCode)
                val matchLen = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode]).toInt()
                val litLen = LL_BASE[llCode] + bits.read(LL_BITS[llCode]).toInt()
                if (i != nbSeq - 1) {
                    llState = ll.base[llState] + bits.read(ll.bits[llState].toInt()).toInt()
                    mlState = ml.base[mlState] + bits.read(ml.bits[mlState].toInt()).toInt()
                    ofState = of.base[ofState] + bits.read(of.bits[ofState].toInt()).toInt()
                }
                if (bits.overflowed) corrupt("sequence bitstream overread")

                val offset: Long
                if (offsetValue > 3) {
                    offset = offsetValue - 3
                    rep[2] = rep[1]
                    rep[1] = rep[0]
                    rep[0] = offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else {
                    var idx = offsetValue.toInt() - 1
                    if (litLen == 0) idx++
                    if (idx == 0) {
                        offset = rep[0].toLong()
                    } else {
                        offset = if (idx < 3) rep[idx].toLong() else rep[0].toLong() - 1
                        if (idx > 1) rep[2] = rep[1]
                        rep[1] = rep[0]
                        rep[0] = offset.toInt()
                    }
                }

                if (litLen > literals.size - litPos) corrupt("literal length past end of literals")
                out.write(literals, litPos, litLen)
                litPos += litLen
                if (offset <= 0 || offset > out.size - frameStart) corrupt("match offset out of range")
                out.copyMatch(offset.toInt(), matchLen)
            }
            if (!bits.finished) corrupt("sequence bitstream not fully consumed")
            out.write(literals, litPos, literals.size - litPos)
        }
    }

    // ── FSE ─────────────────────────────────────────────────────────

    /** FSE decoding table: per state the symbol, the number of bits to read and the next-state base. */
    private class FseTable(val accuracyLog: Int, val symbol: ByteArray, val bits: ByteArray, val base: IntArray) {
        companion object {
            fun rle(sym: Int): FseTable = FseTable(0, byteArrayOf(sym.toByte()), byteArrayOf(0), intArrayOf(0))

            /** Reads an FSE table description at [start]; returns the table and the bytes consumed. */
            fun read(src: ByteArray, start: Int, end: Int, maxLog: Int, maxSymbol: Int): Pair<FseTable, Int> {
                val reader = ForwardBitReader(src, start, end)
                val accuracyLog = reader.read(4) + 5
                if (accuracyLog > maxLog) corrupt("FSE accuracy log too large")
                var remaining = 1 shl accuracyLog
                val freqs = IntArray(maxSymbol + 1)
                var symbol = 0
                while (remaining > 0) {
                    if (symbol > maxSymbol) corrupt("too many FSE symbols")
                    val nbBits = highBit(remaining + 1) + 1
                    var value = reader.read(nbBits)
                    val lowerMask = (1 shl (nbBits - 1)) - 1
                    val threshold = (1 shl nbBits) - 1 - (remaining + 1)
                    if ((value and lowerMask) < threshold) {
                        reader.rewind(1)
                        value = value and lowerMask
                    } else if (value > lowerMask) {
                        value -= threshold
                    }
                    val proba = value - 1
                    remaining -= if (proba < 0) -proba else proba
                    freqs[symbol++] = proba
                    if (proba == 0) {
                        while (true) {
                            val repeat = reader.read(2)
                            for (k in 0 until repeat) {
                                if (symbol > maxSymbol) corrupt("too many FSE symbols")
                                freqs[symbol++] = 0
                            }
                            if (repeat != 3) break
                        }
                    }
                }
                if (remaining != 0) corrupt("invalid FSE distribution")
                return build(freqs, symbol, accuracyLog) to reader.bytesConsumed()
            }

            /** Builds the decoding table from normalized counts (`-1` = "less than 1"). */
            fun build(freqs: IntArray, count: Int, accuracyLog: Int): FseTable {
                val size = 1 shl accuracyLog
                val symbols = ByteArray(size)
                val next = IntArray(count)
                var high = size - 1
                for (s in 0 until count) {
                    if (freqs[s] == -1) {
                        if (high < 0) corrupt("invalid FSE distribution")
                        symbols[high--] = s.toByte()
                        next[s] = 1
                    }
                }
                val step = (size ushr 1) + (size ushr 3) + 3
                val mask = size - 1
                var position = 0
                for (s in 0 until count) {
                    val f = freqs[s]
                    if (f <= 0) continue
                    next[s] = f
                    for (i in 0 until f) {
                        symbols[position] = s.toByte()
                        do {
                            position = (position + step) and mask
                        } while (position > high)
                    }
                }
                if (position != 0) corrupt("invalid FSE distribution")
                val bits = ByteArray(size)
                val base = IntArray(size)
                for (i in 0 until size) {
                    val s = symbols[i].toInt() and 0xFF
                    val nextState = next[s]++
                    val nb = accuracyLog - highBit(nextState)
                    bits[i] = nb.toByte()
                    base[i] = (nextState shl nb) - size
                }
                return FseTable(accuracyLog, symbols, bits, base)
            }

            fun predefined(freqs: IntArray, accuracyLog: Int): FseTable = build(freqs, freqs.size, accuracyLog)
        }
    }

    // ── Huffman ─────────────────────────────────────────────────────

    private class HuffmanTable(val maxBits: Int, val symbols: ByteArray, val numBits: ByteArray) {
        /** Decodes [streams] (1 or 4) Huffman streams in `src[start until end]` into [regen] bytes. */
        fun decodeStreams(src: ByteArray, start: Int, end: Int, regen: Int, streams: Int): ByteArray {
            val out = ByteArray(regen)
            if (streams == 1) {
                decodeStream(src, start, end, out, 0, regen)
                return out
            }
            if (end - start < 6) corrupt("truncated Huffman jump table")
            val s1 = le16(src, start)
            val s2 = le16(src, start + 2)
            val s3 = le16(src, start + 4)
            val dataStart = start + 6
            val s4 = end - dataStart - s1 - s2 - s3
            if (s4 < 1) corrupt("invalid Huffman jump table")
            val segment = (regen + 3) / 4
            if (3 * segment > regen) corrupt("too few literals for 4 Huffman streams")
            var p = dataStart
            decodeStream(src, p, p + s1, out, 0, segment)
            p += s1
            decodeStream(src, p, p + s2, out, segment, segment)
            p += s2
            decodeStream(src, p, p + s3, out, 2 * segment, segment)
            p += s3
            decodeStream(src, p, end, out, 3 * segment, regen - 3 * segment)
            return out
        }

        private fun decodeStream(src: ByteArray, start: Int, end: Int, out: ByteArray, outPos: Int, count: Int) {
            val bits = BackwardBitReader(src, start, end)
            val mask = (1 shl maxBits) - 1
            var state = bits.read(maxBits).toInt()
            for (i in 0 until count) {
                val nb = numBits[state].toInt()
                out[outPos + i] = symbols[state]
                state = ((state shl nb) and mask) or bits.read(nb).toInt()
            }
            // the stream must end exactly: maxBits "virtual" bits of the last state were read past the start
            if (bits.position != -maxBits) corrupt("Huffman stream size mismatch")
        }

        companion object {
            private const val MAX_BITS = 11

            /** Reads a Huffman tree description at [start]; returns the table and the bytes consumed. */
            fun read(src: ByteArray, start: Int, end: Int): Pair<HuffmanTable, Int> {
                if (start >= end) corrupt("truncated Huffman tree description")
                val header = src[start].toInt() and 0xFF
                val weights = IntArray(256)
                val numWeights: Int
                val consumed: Int
                if (header >= 128) {
                    numWeights = header - 127
                    val bytes = (numWeights + 1) / 2
                    if (bytes > end - start - 1) corrupt("truncated Huffman weights")
                    for (i in 0 until numWeights) {
                        val b = src[start + 1 + i / 2].toInt() and 0xFF
                        weights[i] = if (i % 2 == 0) b ushr 4 else b and 0xF
                    }
                    consumed = 1 + bytes
                } else {
                    if (header == 0 || header > end - start - 1) corrupt("truncated Huffman weights")
                    numWeights = decodeFseWeights(src, start + 1, start + 1 + header, weights)
                    consumed = 1 + header
                }
                return build(weights, numWeights) to consumed
            }

            /** FSE-compressed weights: two interleaved states over one backward bitstream. */
            private fun decodeFseWeights(src: ByteArray, start: Int, end: Int, weights: IntArray): Int {
                val (table, used) = FseTable.read(src, start, end, 6, 255)
                val bits = BackwardBitReader(src, start + used, end)
                var s1 = bits.read(table.accuracyLog).toInt()
                var s2 = bits.read(table.accuracyLog).toInt()
                var n = 0
                while (true) {
                    if (n >= 255) corrupt("too many Huffman weights")
                    weights[n++] = table.symbol[s1].toInt()
                    s1 = table.base[s1] + bits.read(table.bits[s1].toInt()).toInt()
                    if (bits.overflowed) {
                        weights[n++] = table.symbol[s2].toInt()
                        break
                    }
                    if (n >= 255) corrupt("too many Huffman weights")
                    weights[n++] = table.symbol[s2].toInt()
                    s2 = table.base[s2] + bits.read(table.bits[s2].toInt()).toInt()
                    if (bits.overflowed) {
                        if (n >= 255) corrupt("too many Huffman weights")
                        weights[n++] = table.symbol[s1].toInt()
                        break
                    }
                }
                return n
            }

            private fun build(weights: IntArray, numWeights: Int): HuffmanTable {
                var sum = 0
                for (i in 0 until numWeights) {
                    val w = weights[i]
                    if (w > MAX_BITS) corrupt("Huffman weight too large")
                    if (w > 0) sum += 1 shl (w - 1)
                }
                if (sum == 0) corrupt("all Huffman weights are zero")
                val maxBits = highBit(sum) + 1
                if (maxBits > MAX_BITS) corrupt("Huffman table too deep")
                val rest = (1 shl maxBits) - sum
                if (rest and (rest - 1) != 0) corrupt("invalid Huffman weights")
                val numSymbols = numWeights + 1
                if (numSymbols > 256) corrupt("too many Huffman symbols")
                weights[numWeights] = highBit(rest) + 1

                val bitsOf = IntArray(numSymbols)
                val rankCount = IntArray(maxBits + 2)
                for (s in 0 until numSymbols) {
                    val w = weights[s]
                    bitsOf[s] = if (w > 0) maxBits + 1 - w else 0
                    rankCount[bitsOf[s]]++
                }
                val size = 1 shl maxBits
                val symbols = ByteArray(size)
                val numBits = ByteArray(size)
                val rankIdx = IntArray(maxBits + 2)
                rankIdx[maxBits] = 0
                for (b in maxBits downTo 1) {
                    rankIdx[b - 1] = rankIdx[b] + rankCount[b] * (1 shl (maxBits - b))
                    numBits.fill(b.toByte(), rankIdx[b], rankIdx[b - 1])
                }
                if (rankIdx[0] != size) corrupt("invalid Huffman weights")
                for (s in 0 until numSymbols) {
                    val b = bitsOf[s]
                    if (b == 0) continue
                    val code = rankIdx[b]
                    val len = 1 shl (maxBits - b)
                    symbols.fill(s.toByte(), code, code + len)
                    rankIdx[b] += len
                }
                return HuffmanTable(maxBits, symbols, numBits)
            }
        }
    }

    // ── Bit readers ─────────────────────────────────────────────────

    /** Little-endian forward bit reader (FSE table descriptions). */
    private class ForwardBitReader(val src: ByteArray, val start: Int, val end: Int) {
        private var bitPos = 0L

        fun read(n: Int): Int {
            var result = 0
            for (i in 0 until n) {
                val byteIdx = start + (bitPos ushr 3).toInt()
                if (byteIdx >= end) corrupt("truncated FSE table description")
                val bit = (src[byteIdx].toInt() ushr (bitPos and 7).toInt()) and 1
                result = result or (bit shl i)
                bitPos++
            }
            return result
        }

        fun rewind(n: Int) {
            bitPos -= n
        }

        fun bytesConsumed(): Int = ((bitPos + 7) ushr 3).toInt()
    }

    /**
     * Backward bit reader (Huffman streams, FSE weights, sequences). The stream is read from its
     * last byte towards its first; the highest set bit of the last byte marks the start. Reads past
     * the beginning yield zero bits and make [position] negative (see [overflowed]).
     */
    private class BackwardBitReader(val src: ByteArray, val start: Int, end: Int) {
        var position: Int

        init {
            if (end <= start) corrupt("empty bitstream")
            val last = src[end - 1].toInt() and 0xFF
            if (last == 0) corrupt("bitstream without end mark")
            position = (end - start) * 8 - (8 - highBit(last))
        }

        val overflowed: Boolean get() = position < 0
        val finished: Boolean get() = position == 0

        fun read(n: Int): Long {
            if (n == 0) return 0
            position -= n
            var off = position
            var count = n
            if (off < 0) {
                count += off
                off = 0
                if (count <= 0) return 0
            }
            var result = 0L
            var got = 0
            var byte = off ushr 3
            var shift = off and 7
            while (got < count) {
                val b = (src[start + byte].toInt() and 0xFF) ushr shift
                result = result or (b.toLong() shl got)
                got += 8 - shift
                shift = 0
                byte++
            }
            result = result and ((1L shl count) - 1)
            return if (position < 0) result shl -position else result
        }
    }

    // ── Tables (RFC 8878 §3.1.1.3.2.1.1 / §3.1.1.3.2.2) ─────────────

    private val LL_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
        8192, 16384, 32768, 65536,
    )
    private val LL_BITS = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
        13, 14, 15, 16,
    )
    private val ML_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
        19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
        35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
        4099, 8195, 16387, 32771, 65539,
    )
    private val ML_BITS = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
        12, 13, 14, 15, 16,
    )

    private const val LL_DEFAULT_LOG = 6
    private const val ML_DEFAULT_LOG = 6
    private const val OF_DEFAULT_LOG = 5

    private val LL_DEFAULT = FseTable.predefined(
        intArrayOf(
            4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
            -1, -1, -1, -1,
        ),
        LL_DEFAULT_LOG,
    )
    private val ML_DEFAULT = FseTable.predefined(
        intArrayOf(
            1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
            -1, -1, -1, -1, -1,
        ),
        ML_DEFAULT_LOG,
    )
    private val OF_DEFAULT = FseTable.predefined(
        intArrayOf(
            1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1,
        ),
        OF_DEFAULT_LOG,
    )

    // ── Helpers ─────────────────────────────────────────────────────

    private fun highBit(v: Int): Int = 31 - v.countLeadingZeroBits()

    private fun corrupt(what: String): Nothing = throw CompressionException("Zstd: $what")

    private fun need(src: ByteArray, pos: Int, count: Int, what: String, end: Int = src.size) {
        if (count < 0 || pos > end || count > end - pos) corrupt("truncated $what")
    }

    private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le24(b: ByteArray, i: Int): Int = le16(b, i) or ((b[i + 2].toInt() and 0xFF) shl 16)

    private fun le32(b: ByteArray, i: Int): Int = le24(b, i) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(out: ByteSink, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write(v ushr 24)
    }
}
