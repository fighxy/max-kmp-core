package com.max.core.protocol

/*
 * xxHash32 / xxHash64 (seeded), written from the public xxHash specification
 * (https://github.com/Cyan4973/xxHash/blob/dev/doc/xxhash_spec.md). Used to verify the optional
 * content checksum of Zstd frames (low 32 bits of XXH64, seed 0) and the header / block / content
 * checksums of LZ4 frames (XXH32, seed 0). Pure Kotlin, relies on two's-complement wrap-around of
 * Int / Long arithmetic, which matches the unsigned arithmetic of the spec.
 */
internal object XxHash {
    private const val P32_1: Int = -0x61c8864f // 0x9E3779B1
    private const val P32_2: Int = -0x7a143589 // 0x85EBCA77
    private const val P32_3: Int = -0x3d4d51c3 // 0xC2B2AE3D
    private const val P32_4: Int = 0x27D4EB2F
    private const val P32_5: Int = 0x165667B1

    private const val P64_1: Long = -0x61c8864e7a143579L // 0x9E3779B185EBCA87
    private const val P64_2: Long = -0x3d4d51c2d82b14b1L // 0xC2B2AE3D27D4EB4F
    private const val P64_3: Long = 0x165667B19E3779F9L
    private const val P64_4: Long = -0x7a1435883d4d519dL // 0x85EBCA77C2B2AE63
    private const val P64_5: Long = 0x27D4EB2F165667C5L

    private fun rotl32(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))
    private fun rotl64(x: Long, r: Int): Long = (x shl r) or (x ushr (64 - r))

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, i: Int): Long =
        (le32(b, i).toLong() and 0xFFFFFFFFL) or (le32(b, i + 4).toLong() shl 32)

    /** XXH32 of `data[offset until offset + length]`. */
    fun xxh32(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, seed: Int = 0): Int {
        val end = offset + length
        var p = offset
        var h: Int
        if (length >= 16) {
            var v1 = seed + P32_1 + P32_2
            var v2 = seed + P32_2
            var v3 = seed
            var v4 = seed - P32_1
            val limit = end - 16
            while (p <= limit) {
                v1 = rotl32(v1 + le32(data, p) * P32_2, 13) * P32_1
                v2 = rotl32(v2 + le32(data, p + 4) * P32_2, 13) * P32_1
                v3 = rotl32(v3 + le32(data, p + 8) * P32_2, 13) * P32_1
                v4 = rotl32(v4 + le32(data, p + 12) * P32_2, 13) * P32_1
                p += 16
            }
            h = rotl32(v1, 1) + rotl32(v2, 7) + rotl32(v3, 12) + rotl32(v4, 18)
        } else {
            h = seed + P32_5
        }
        h += length
        while (p + 4 <= end) {
            h = rotl32(h + le32(data, p) * P32_3, 17) * P32_4
            p += 4
        }
        while (p < end) {
            h = rotl32(h + (data[p].toInt() and 0xFF) * P32_5, 11) * P32_1
            p++
        }
        h = h xor (h ushr 15)
        h *= P32_2
        h = h xor (h ushr 13)
        h *= P32_3
        h = h xor (h ushr 16)
        return h
    }

    private fun round64(acc: Long, lane: Long): Long = rotl64(acc + lane * P64_2, 31) * P64_1

    private fun merge64(acc: Long, v: Long): Long = (acc xor round64(0, v)) * P64_1 + P64_4

    /** XXH64 of `data[offset until offset + length]`. */
    fun xxh64(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, seed: Long = 0): Long {
        val end = offset + length
        var p = offset
        var h: Long
        if (length >= 32) {
            var v1 = seed + P64_1 + P64_2
            var v2 = seed + P64_2
            var v3 = seed
            var v4 = seed - P64_1
            val limit = end - 32
            while (p <= limit) {
                v1 = round64(v1, le64(data, p))
                v2 = round64(v2, le64(data, p + 8))
                v3 = round64(v3, le64(data, p + 16))
                v4 = round64(v4, le64(data, p + 24))
                p += 32
            }
            h = rotl64(v1, 1) + rotl64(v2, 7) + rotl64(v3, 12) + rotl64(v4, 18)
            h = merge64(h, v1)
            h = merge64(h, v2)
            h = merge64(h, v3)
            h = merge64(h, v4)
        } else {
            h = seed + P64_5
        }
        h += length.toLong()
        while (p + 8 <= end) {
            h = rotl64(h xor round64(0, le64(data, p)), 27) * P64_1 + P64_4
            p += 8
        }
        if (p + 4 <= end) {
            h = rotl64(h xor ((le32(data, p).toLong() and 0xFFFFFFFFL) * P64_1), 23) * P64_2 + P64_3
            p += 4
        }
        while (p < end) {
            h = rotl64(h xor ((data[p].toLong() and 0xFF) * P64_5), 11) * P64_1
            p++
        }
        h = h xor (h ushr 33)
        h *= P64_2
        h = h xor (h ushr 29)
        h *= P64_3
        h = h xor (h ushr 32)
        return h
    }
}
