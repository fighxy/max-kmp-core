package com.maxly.core.protocol

/**
 * MessagePack codec for packet bodies (the bytes after the 10-byte header, once decompressed).
 *
 * The interface works on plain Kotlin values ([DefaultMessagePackCodec] documents the exact type
 * mapping). Both directions are nullable because `nil` is a regular MessagePack value.
 */
interface MessagePackCodec {
    /** Serializes [value] (see [DefaultMessagePackCodec] for supported types) to MessagePack. */
    fun encode(value: Any?): ByteArray

    /** Parses one MessagePack value from [bytes]. */
    fun decode(bytes: ByteArray): Any?
}

/** Thrown when bytes are not valid MessagePack or a value cannot be encoded. */
class MessagePackException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * A MessagePack extension value (`fixext` / `ext 8/16/32`) that the codec does not unwrap.
 *
 * [type] is the signed ext type byte (`-128..127`; negative codes are reserved by the spec, e.g.
 * `-1` is the timestamp extension, which is returned as a raw [MsgPackExt] too). Equality compares
 * [data] by content.
 */
class MsgPackExt(val type: Byte, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is MsgPackExt && other.type == type && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * type + data.contentHashCode()

    override fun toString(): String = "MsgPackExt(type=$type, size=${data.size})"
}

/**
 * Self-contained, pure-Kotlin MessagePack codec (https://github.com/msgpack/msgpack/blob/master/spec.md).
 * It lives in commonMain, has no dependencies and no platform `actual`s.
 *
 * Why not a library:
 * - `rmp` is a Rust crate (crates.io `rmp`, repo 3Hren/msgpack-rust), not a Kotlin library;
 *   `github.com/msgpack/msgpack-kotlin` does not exist.
 * - `org.msgpack:msgpack-core` (msgpack-java, latest 0.9.12) is a plain JVM jar: Maven Central has
 *   no Gradle module metadata and no Kotlin/Native (iOS) variants for it, so it cannot be used from
 *   commonMain.
 * - `io.github.firasrabah:kotlinx-serialization-msgpack` is not on Maven Central. The real
 *   kotlinx.serialization format is `com.ensarsarajcic.kotlinx:serialization-msgpack`. It has
 *   iosArm64/iosSimulatorArm64/jvm variants and a dynamic serializer, but its latest release (0.6.1)
 *   ships Java 21 bytecode (class file 65) in the JVM variant, which does not load on the project's
 *   JDK 17. 0.5.7 (Java 8 bytecode) throws NullPointerException when encoding `null`, including a
 *   `null` inside a list. Both versions also reject uint64 values above `Long.MAX_VALUE` and return
 *   `Byte`/`Short` depending on the wire format, so the result would need a second pass anyway.
 *
 * Encoding (Kotlin → MessagePack):
 * - `null` → nil; [Boolean] → true/false;
 * - [Byte], [Short], [Int], [Long], [UByte], [UShort], [UInt], [ULong] → the smallest integer format
 *   (non-negative: positive fixint / uint 8/16/32/64; negative: negative fixint / int 8/16/32/64),
 *   the same choice msgpack-python (PyMax) makes;
 * - [Float] → float 32; [Double] → float 64;
 * - [String] → fixstr / str 8/16/32 (UTF-8);
 * - [ByteArray] → bin 8/16/32 (no `{"$bin": ...}` wrapper);
 * - [Map] → fixmap / map 16/32 in iteration order (keys may be any supported value, e.g. [Int]);
 * - [List], any other [Collection], [Array] → fixarray / array 16/32;
 * - [MsgPackExt] → fixext / ext 8/16/32.
 *
 * Anything else throws [MessagePackException].
 *
 * Decoding (MessagePack → Kotlin):
 * - nil → `null`; bool → [Boolean];
 * - any integer → [Int] if it fits in `Int`, otherwise [Long]; a uint 64 above `Long.MAX_VALUE`
 *   → [ULong]. The wire format does not affect the result type (`cc 05` and `05` both give `5`);
 * - float 32 and float 64 → [Double] (float 32 is widened exactly);
 * - str → [String] (invalid UTF-8 throws); bin → [ByteArray];
 * - array → [List] (an `ArrayList`); map → [LinkedHashMap] keeping wire order; keys keep their
 *   decoded type, so integer keys stay [Int]/[Long] (PyMax turns them into strings later, in
 *   `TcpPayloadDecoder._normalize_keys`; that is a caller decision, not a codec one);
 * - ext → [MsgPackExt], except ext type **1** when [unwrapWrappedValues] is set (the default): its
 *   data is decoded recursively as a nested MessagePack value and returned in its place, as PyMax's
 *   `MsgpackPayloadCodec._decode_ext` does (docs/protocol.md §F.3). Encoding never produces ext 1
 *   on its own; pass a [MsgPackExt] explicitly to send one.
 *
 * Framing of the input:
 * - an empty input throws (the payload helpers in PayloadPacket.kt map an empty body to `null`);
 * - with [ignoreTrailingData] (the default) bytes after the first complete value are ignored, like
 *   PyMax, which returns the first object on `ExtraData`; otherwise they throw;
 * - truncated input, the reserved byte `0xc1`, lengths running past the input and nesting deeper
 *   than [maxDepth] throw [MessagePackException].
 */
class DefaultMessagePackCodec(
    val unwrapWrappedValues: Boolean = true,
    val ignoreTrailingData: Boolean = true,
    val maxDepth: Int = 512,
) : MessagePackCodec {

    /** Shared instance with the default settings. */
    companion object Default : MessagePackCodec by DefaultMessagePackCodec() {
        /** Ext type that PyMax unwraps as a nested MessagePack value. */
        const val WRAPPED_VALUE_EXT_TYPE: Byte = 1
    }

    override fun encode(value: Any?): ByteArray {
        val out = ByteSink()
        writeValue(out, value, 0)
        return out.toByteArray()
    }

    override fun decode(bytes: ByteArray): Any? {
        if (bytes.isEmpty()) throw MessagePackException("empty input")
        val reader = Reader(bytes)
        val value = readValue(reader, 0)
        if (!ignoreTrailingData && reader.pos != bytes.size) {
            throw MessagePackException("${bytes.size - reader.pos} trailing bytes after value")
        }
        return value
    }

    // ── Encoder ─────────────────────────────────────────────────────

    private fun writeValue(out: ByteSink, value: Any?, depth: Int) {
        if (depth > maxDepth) throw MessagePackException("nesting deeper than $maxDepth")
        when (value) {
            null -> out.u8(0xC0)
            is Boolean -> out.u8(if (value) 0xC3 else 0xC2)
            is Int -> writeLong(out, value.toLong())
            is Long -> writeLong(out, value)
            is Short -> writeLong(out, value.toLong())
            is Byte -> writeLong(out, value.toLong())
            is UByte -> writeLong(out, value.toLong())
            is UShort -> writeLong(out, value.toLong())
            is UInt -> writeLong(out, value.toLong())
            is ULong -> if (value.toLong() >= 0) writeLong(out, value.toLong()) else {
                out.u8(0xCF)
                out.u64(value.toLong())
            }
            is Double -> {
                out.u8(0xCB)
                out.u64(value.toRawBits())
            }
            is Float -> {
                out.u8(0xCA)
                out.u32(value.toRawBits())
            }
            is String -> writeString(out, value)
            is ByteArray -> writeBinary(out, value)
            is Map<*, *> -> {
                writeContainerHeader(out, value.size, 0x80, 0xDE, 0xDF)
                for ((k, v) in value) {
                    writeValue(out, k, depth + 1)
                    writeValue(out, v, depth + 1)
                }
            }
            is Collection<*> -> {
                writeContainerHeader(out, value.size, 0x90, 0xDC, 0xDD)
                for (item in value) writeValue(out, item, depth + 1)
            }
            is Array<*> -> {
                writeContainerHeader(out, value.size, 0x90, 0xDC, 0xDD)
                for (item in value) writeValue(out, item, depth + 1)
            }
            is MsgPackExt -> writeExt(out, value)
            else -> throw MessagePackException("cannot encode ${value::class.simpleName ?: "value"}")
        }
    }

    private fun writeLong(out: ByteSink, v: Long) {
        when {
            v >= 0 -> when {
                v <= 0x7F -> out.u8(v.toInt())
                v <= 0xFF -> { out.u8(0xCC); out.u8(v.toInt()) }
                v <= 0xFFFF -> { out.u8(0xCD); out.u16(v.toInt()) }
                v <= 0xFFFF_FFFFL -> { out.u8(0xCE); out.u32(v.toInt()) }
                else -> { out.u8(0xCF); out.u64(v) }
            }
            v >= -32 -> out.u8(v.toInt() and 0xFF)
            v >= Byte.MIN_VALUE -> { out.u8(0xD0); out.u8(v.toInt()) }
            v >= Short.MIN_VALUE -> { out.u8(0xD1); out.u16(v.toInt()) }
            v >= Int.MIN_VALUE -> { out.u8(0xD2); out.u32(v.toInt()) }
            else -> { out.u8(0xD3); out.u64(v) }
        }
    }

    private fun writeString(out: ByteSink, value: String) {
        val utf8 = value.encodeToByteArray()
        val n = utf8.size
        when {
            n <= 31 -> out.u8(0xA0 or n)
            n <= 0xFF -> { out.u8(0xD9); out.u8(n) }
            n <= 0xFFFF -> { out.u8(0xDA); out.u16(n) }
            else -> { out.u8(0xDB); out.u32(n) }
        }
        out.bytes(utf8)
    }

    private fun writeBinary(out: ByteSink, value: ByteArray) {
        val n = value.size
        when {
            n <= 0xFF -> { out.u8(0xC4); out.u8(n) }
            n <= 0xFFFF -> { out.u8(0xC5); out.u16(n) }
            else -> { out.u8(0xC6); out.u32(n) }
        }
        out.bytes(value)
    }

    private fun writeContainerHeader(out: ByteSink, size: Int, fix: Int, code16: Int, code32: Int) {
        when {
            size <= 15 -> out.u8(fix or size)
            size <= 0xFFFF -> { out.u8(code16); out.u16(size) }
            else -> { out.u8(code32); out.u32(size) }
        }
    }

    private fun writeExt(out: ByteSink, ext: MsgPackExt) {
        val n = ext.data.size
        when (n) {
            1 -> out.u8(0xD4)
            2 -> out.u8(0xD5)
            4 -> out.u8(0xD6)
            8 -> out.u8(0xD7)
            16 -> out.u8(0xD8)
            else -> when {
                n <= 0xFF -> { out.u8(0xC7); out.u8(n) }
                n <= 0xFFFF -> { out.u8(0xC8); out.u16(n) }
                else -> { out.u8(0xC9); out.u32(n) }
            }
        }
        out.u8(ext.type.toInt())
        out.bytes(ext.data)
    }

    // ── Decoder ─────────────────────────────────────────────────────

    private fun readValue(r: Reader, depth: Int): Any? {
        if (depth > maxDepth) throw MessagePackException("nesting deeper than $maxDepth")
        val b = r.u8()
        return when {
            b <= 0x7F -> b
            b <= 0x8F -> readMap(r, b and 0x0F, depth)
            b <= 0x9F -> readArray(r, b and 0x0F, depth)
            b <= 0xBF -> readString(r, b and 0x1F)
            b >= 0xE0 -> b - 0x100
            else -> when (b) {
                0xC0 -> null
                0xC2 -> false
                0xC3 -> true
                0xC4 -> r.bytes(r.u8())
                0xC5 -> r.bytes(r.u16())
                0xC6 -> r.bytes(r.length32())
                0xC7 -> readExt(r, r.u8(), depth)
                0xC8 -> readExt(r, r.u16(), depth)
                0xC9 -> readExt(r, r.length32(), depth)
                0xCA -> Float.fromBits(r.s32()).toDouble()
                0xCB -> Double.fromBits(r.s64())
                0xCC -> r.u8()
                0xCD -> r.u16()
                0xCE -> narrow(r.s32().toLong() and 0xFFFF_FFFFL)
                0xCF -> r.s64().let { if (it < 0) it.toULong() else narrow(it) }
                0xD0 -> r.u8().toByte().toInt()
                0xD1 -> r.u16().toShort().toInt()
                0xD2 -> r.s32()
                0xD3 -> narrow(r.s64())
                0xD4 -> readExt(r, 1, depth)
                0xD5 -> readExt(r, 2, depth)
                0xD6 -> readExt(r, 4, depth)
                0xD7 -> readExt(r, 8, depth)
                0xD8 -> readExt(r, 16, depth)
                0xD9 -> readString(r, r.u8())
                0xDA -> readString(r, r.u16())
                0xDB -> readString(r, r.length32())
                0xDC -> readArray(r, r.u16(), depth)
                0xDD -> readArray(r, r.length32(), depth)
                0xDE -> readMap(r, r.u16(), depth)
                0xDF -> readMap(r, r.length32(), depth)
                else -> throw MessagePackException("reserved byte 0x${b.toString(16)} at offset ${r.pos - 1}")
            }
        }
    }

    private fun narrow(v: Long): Any = if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v

    private fun readString(r: Reader, length: Int): String {
        val start = r.pos
        val raw = r.bytes(length)
        return try {
            raw.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw MessagePackException("invalid UTF-8 in str at offset $start", e)
        }
    }

    private fun readArray(r: Reader, size: Int, depth: Int): List<Any?> {
        r.requireItems(size.toLong())
        val list = ArrayList<Any?>(size)
        repeat(size) { list.add(readValue(r, depth + 1)) }
        return list
    }

    private fun readMap(r: Reader, size: Int, depth: Int): LinkedHashMap<Any?, Any?> {
        r.requireItems(size * 2L)
        val map = LinkedHashMap<Any?, Any?>(if (size < 1024) size else 1024)
        repeat(size) {
            val key = readValue(r, depth + 1)
            map[key] = readValue(r, depth + 1)
        }
        return map
    }

    private fun readExt(r: Reader, length: Int, depth: Int): Any? {
        val type = r.u8().toByte()
        val data = r.bytes(length)
        if (unwrapWrappedValues && type == WRAPPED_VALUE_EXT_TYPE) {
            if (data.isEmpty()) throw MessagePackException("empty wrapped value (ext 1)")
            val inner = Reader(data)
            val value = readValue(inner, depth + 1)
            if (inner.pos != data.size) {
                throw MessagePackException("${data.size - inner.pos} trailing bytes inside ext 1")
            }
            return value
        }
        return MsgPackExt(type, data)
    }

    private class Reader(private val buf: ByteArray) {
        var pos: Int = 0
            private set

        private fun need(n: Int) {
            if (n > buf.size - pos) {
                throw MessagePackException("truncated input: need $n bytes at offset $pos, have ${buf.size - pos}")
            }
        }

        /** Every array element / map key or value takes at least one byte. */
        fun requireItems(count: Long) {
            if (count > buf.size - pos) {
                throw MessagePackException("container claims $count items at offset $pos, only ${buf.size - pos} bytes left")
            }
        }

        fun u8(): Int {
            need(1)
            return buf[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            need(2)
            val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        fun s32(): Int {
            need(4)
            var v = 0
            repeat(4) { v = (v shl 8) or (buf[pos + it].toInt() and 0xFF) }
            pos += 4
            return v
        }

        fun s64(): Long {
            need(8)
            var v = 0L
            repeat(8) { v = (v shl 8) or (buf[pos + it].toLong() and 0xFF) }
            pos += 8
            return v
        }

        /** A 32-bit length; values above `Int.MAX_VALUE` cannot fit in any ByteArray. */
        fun length32(): Int {
            val v = s32()
            if (v < 0) throw MessagePackException("length ${v.toLong() and 0xFFFF_FFFFL} too large")
            return v
        }

        fun bytes(n: Int): ByteArray {
            need(n)
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    private class ByteSink {
        private var buf = ByteArray(64)
        private var size = 0

        private fun ensure(extra: Int) {
            val required = size + extra
            if (required < 0) throw MessagePackException("encoded value too large")
            if (required > buf.size) {
                var cap = buf.size
                while (cap < required) cap = if (cap > Int.MAX_VALUE / 2) required else cap * 2
                buf = buf.copyOf(cap)
            }
        }

        fun u8(v: Int) {
            ensure(1)
            buf[size++] = v.toByte()
        }

        fun u16(v: Int) {
            ensure(2)
            buf[size++] = (v ushr 8).toByte()
            buf[size++] = v.toByte()
        }

        fun u32(v: Int) {
            ensure(4)
            for (shift in 24 downTo 0 step 8) buf[size++] = (v ushr shift).toByte()
        }

        fun u64(v: Long) {
            ensure(8)
            for (shift in 56 downTo 0 step 8) buf[size++] = (v ushr shift).toByte()
        }

        fun bytes(src: ByteArray) {
            ensure(src.size)
            src.copyInto(buf, size)
            size += src.size
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }
}
