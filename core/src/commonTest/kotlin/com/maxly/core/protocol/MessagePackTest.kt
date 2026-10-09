package com.maxly.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagePackTest {

    private val codec: MessagePackCodec = DefaultMessagePackCodec

    private fun hex(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun roundTrip(value: Any?): Any? = codec.decode(codec.encode(value))

    /**
     * Deep equality that compares ByteArrays by content (plain `==` on a ByteArray is identity).
     */
    private fun assertDeepEquals(expected: Any?, actual: Any?, path: String = "$") {
        when (expected) {
            is ByteArray -> {
                assertIs<ByteArray>(actual, path)
                assertContentEquals(expected, actual, path)
            }
            is Map<*, *> -> {
                assertIs<Map<*, *>>(actual, path)
                assertEquals(expected.keys.toList(), actual.keys.toList(), "$path keys/order")
                for ((k, v) in expected) assertDeepEquals(v, actual[k], "$path[$k]")
            }
            is List<*> -> {
                assertIs<List<*>>(actual, path)
                assertEquals(expected.size, actual.size, "$path size")
                expected.indices.forEach { assertDeepEquals(expected[it], actual[it], "$path[$it]") }
            }
            else -> {
                assertEquals(expected, actual, path)
                if (expected != null) assertEquals(expected::class, actual!!::class, "$path type")
            }
        }
    }

    // ── Hand-checked spec vectors ───────────────────────────────────

    /** Also the payload in kolibri `kolibri-net/tests/vectors.rs` (`MSGPACK_A1`). */
    @Test
    fun mapA1Vector() {
        val bytes = hex("81 A1 61 01")
        assertContentEquals(bytes, codec.encode(mapOf("a" to 1)))
        assertDeepEquals(mapOf("a" to 1), codec.decode(bytes))
    }

    @Test
    fun scalarVectors() {
        // Expected bytes checked by hand against the spec and with msgpack-python 1.2.2
        // (`packb(v, use_bin_type=True)`), the packer PyMax uses.
        val vectors = listOf<Pair<Any?, String>>(
            null to "c0",
            false to "c2",
            true to "c3",
            0 to "00",
            127 to "7f",
            128 to "cc80",
            255 to "ccff",
            256 to "cd0100",
            65535 to "cdffff",
            65536 to "ce00010000",
            -1 to "ff",
            -32 to "e0",
            -33 to "d0df",
            -128 to "d080",
            -129 to "d1ff7f",
            -32768 to "d18000",
            -32769 to "d2ffff7fff",
            Int.MIN_VALUE to "d280000000",
            Int.MAX_VALUE to "ce7fffffff",
            4294967295L to "ceffffffff",
            4294967296L to "cf0000000100000000",
            Long.MAX_VALUE to "cf7fffffffffffffff",
            Long.MIN_VALUE to "d38000000000000000",
            ULong.MAX_VALUE to "cfffffffffffffffff",
            1.5 to "cb3ff8000000000000",
            -0.0 to "cb8000000000000000",
            1.5f to "ca3fc00000",
            "" to "a0",
            "a".repeat(31) to "bf" + "61".repeat(31),
            "a".repeat(32) to "d920" + "61".repeat(32),
            "привет, мир 🌍" to "b9d0bfd180d0b8d0b2d0b5d1822c20d0bcd0b8d18020f09f8c8d",
            ByteArray(0) to "c400",
            byteArrayOf(0, -1) to "c40200ff",
        )
        for ((value, expected) in vectors) {
            val label = if (value is ByteArray) value.contentToString() else value.toString()
            assertContentEquals(hex(expected), codec.encode(value), "encode $label")
        }
    }

    @Test
    fun containerAndLengthBoundaries() {
        assertContentEquals(hex("90"), codec.encode(emptyList<Any>()))
        assertContentEquals(hex("80"), codec.encode(emptyMap<Any, Any>()))
        assertContentEquals(hex("9f"), codec.encode(List(15) { 0 }).copyOf(1))
        assertContentEquals(hex("dc0010"), codec.encode(List(16) { 0 }).copyOf(3))
        assertContentEquals(hex("dd00010000"), codec.encode(List(65536) { 0 }).copyOf(5))
        assertContentEquals(hex("de0010"), codec.encode((0 until 16).associateWith { it }).copyOf(3))
        assertContentEquals(hex("da0100"), codec.encode("x".repeat(256)).copyOf(3))
        assertContentEquals(hex("db00010000"), codec.encode("x".repeat(65536)).copyOf(5))
        assertContentEquals(hex("c50100"), codec.encode(ByteArray(256)).copyOf(3))
        assertContentEquals(hex("c600010000"), codec.encode(ByteArray(65536)).copyOf(5))
        assertEquals(65536, (roundTrip(List(65536) { it }) as List<*>).size)
        assertEquals("x".repeat(65536), roundTrip("x".repeat(65536)))
        assertContentEquals(ByteArray(70000) { it.toByte() }, roundTrip(ByteArray(70000) { it.toByte() }) as ByteArray)
    }

    @Test
    fun nestedVectorMatchesMsgpackPython() {
        // packb({"a": 1, 2: [True, None, b"\x01"], "m": {"k": -1.25}}, use_bin_type=True)
        val value = linkedMapOf<Any?, Any?>(
            "a" to 1,
            2 to listOf(true, null, byteArrayOf(1)),
            "m" to mapOf("k" to -1.25),
        )
        val bytes = hex("83a161010293c3c0c40101a16d81a16bcbbff4000000000000")
        assertContentEquals(bytes, codec.encode(value))
        assertDeepEquals(value, codec.decode(bytes))
    }

    // ── Round trips ─────────────────────────────────────────────────

    @Test
    fun nestedMapWithIntAndStringKeysRoundTrips() {
        val value = linkedMapOf<Any?, Any?>(
            "z" to "last-inserted-first",
            1 to "one",
            -5 to listOf(1, 2, 3),
            "nested" to linkedMapOf<Any?, Any?>(
                100000 to mapOf("deep" to listOf(mapOf(7 to null))),
                "bin" to byteArrayOf(9, 8, 7),
                "flag" to false,
            ),
            4294967296L to "long key",
            "a" to emptyMap<Any?, Any?>(),
        )
        val decoded = roundTrip(value)
        assertIs<LinkedHashMap<*, *>>(decoded)
        assertDeepEquals(value, decoded)
        assertEquals(listOf("z", 1, -5, "nested", 4294967296L, "a"), decoded.keys.toList())
    }

    @Test
    fun listsAndArraysRoundTrip() {
        val value = listOf(1, "two", 3.0, null, true, listOf<Any?>(), listOf(listOf(listOf("x"))))
        assertDeepEquals(value, roundTrip(value))
        assertDeepEquals(listOf(1, 2), roundTrip(arrayOf(1, 2)))
        assertDeepEquals(listOf("a", "b"), roundTrip(linkedSetOf("a", "b")))
    }

    @Test
    fun byteArrayIsBinAndComparesByContent() {
        val data = ByteArray(300) { (it * 7).toByte() }
        val encoded = codec.encode(data)
        assertEquals(0xC5, encoded[0].toInt() and 0xFF)
        val decoded = codec.decode(encoded)
        assertIs<ByteArray>(decoded)
        assertContentEquals(data, decoded)
    }

    @Test
    fun unicodeStringsRoundTrip() {
        for (s in listOf("", "ascii", "héllo", "Привет", "日本語テキスト", "emoji 👋🏽👨‍👩‍👧", "\u0000nul", "x".repeat(40))) {
            assertEquals(s, roundTrip(s))
        }
    }

    @Test
    fun integersDecodeAsIntWhenTheyFitAndLongOtherwise() {
        val ints = listOf(0, 1, 31, 127, 128, 255, 256, 65535, 65536, -1, -32, -33, -128, -129, -32768, -32769, Int.MIN_VALUE, Int.MAX_VALUE)
        for (v in ints) assertDeepEquals(v, roundTrip(v))
        // Narrower and wider Kotlin types come back as Int when the value fits.
        assertDeepEquals(5, roundTrip(5L))
        assertDeepEquals(-7, roundTrip((-7).toByte()))
        assertDeepEquals(1000, roundTrip(1000.toShort()))
        assertDeepEquals(200, roundTrip(200.toUByte()))
        val longs = listOf(Int.MAX_VALUE + 1L, Int.MIN_VALUE - 1L, 4294967295L, 4294967296L, Long.MAX_VALUE, Long.MIN_VALUE, 1783264954296L)
        for (v in longs) assertDeepEquals(v, roundTrip(v))
        assertDeepEquals(ULong.MAX_VALUE, roundTrip(ULong.MAX_VALUE))
        // Non-minimal wire formats give the same result type.
        assertDeepEquals(5, codec.decode(hex("cc05")))
        assertDeepEquals(5, codec.decode(hex("cf0000000000000005")))
        assertDeepEquals(-1, codec.decode(hex("d3ffffffffffffffff")))
        assertDeepEquals(Int.MAX_VALUE, codec.decode(hex("ce7fffffff")))
        assertDeepEquals(2147483648L, codec.decode(hex("ce80000000")))
    }

    @Test
    fun booleansNullAndDoubles() {
        assertEquals(true, roundTrip(true))
        assertEquals(false, roundTrip(false))
        assertNull(roundTrip(null))
        for (d in listOf(0.0, -0.0, 1.5, -1234.5678, Double.MAX_VALUE, Double.MIN_VALUE, Double.POSITIVE_INFINITY)) {
            assertDeepEquals(d, roundTrip(d))
        }
        assertTrue((roundTrip(Double.NaN) as Double).isNaN())
        // float 32 is widened to Double.
        assertDeepEquals(1.5, roundTrip(1.5f))
        assertDeepEquals(0.1f.toDouble(), roundTrip(0.1f))
    }

    // ── Ext types (docs/protocol.md §F.3) ───────────────────────────

    /** PyMax `test_msgpack_codec_decodes_wrapped_value_extension`: ext 1 is unwrapped recursively. */
    @Test
    fun pyMaxWrappedValueExtensionIsUnwrapped() {
        val bytes = hex(
            "83a9657870697265734174c70901cf0000019f32dfc7b8af706f6c6c696e67496e74657276616c" +
                "c70301cd1388a374746cc70501ce0001d4b8",
        )
        val expected = linkedMapOf<Any?, Any?>(
            "expiresAt" to 1783264954296L,
            "pollingInterval" to 5000,
            "ttl" to 119992,
        )
        assertDeepEquals(expected, codec.decode(bytes))
    }

    /** PyMax `test_tcp_payload_decoder_preserves_unknown_extensions`: other codes stay as ext. */
    @Test
    fun pyMaxUnknownExtensionIsPreserved() {
        val bytes = hex("81a9657874656e73696f6ec7072a756e6b6e6f776e")
        val ext = MsgPackExt(42, "unknown".encodeToByteArray())
        val decoded = codec.decode(bytes) as Map<*, *>
        assertEquals(ext, decoded["extension"])
        assertContentEquals(bytes, codec.encode(mapOf("extension" to ext)))
    }

    @Test
    fun extFormatsRoundTrip() {
        for ((size, head) in listOf(1 to "d405", 2 to "d505", 4 to "d605", 8 to "d705", 16 to "d805", 3 to "c70305", 256 to "c8010005", 65536 to "c90001000005")) {
            val ext = MsgPackExt(5, ByteArray(size) { it.toByte() })
            val encoded = codec.encode(ext)
            assertContentEquals(hex(head), encoded.copyOf(head.length / 2), "ext size $size")
            assertEquals(ext, codec.decode(encoded))
        }
        // Timestamp (-1) is not interpreted.
        assertEquals(MsgPackExt(-1, byteArrayOf(0, 0, 0, 1)), codec.decode(hex("d6ff00000001")))
        // With unwrapping disabled, ext 1 stays raw.
        val raw = DefaultMessagePackCodec(unwrapWrappedValues = false)
        assertEquals(MsgPackExt(1, hex("cd1388")), raw.decode(hex("c70301cd1388")))
        assertEquals(5000, codec.decode(hex("c70301cd1388")))
    }

    // ── Other PyMax vectors (tests/protocol/test_protocols.py) ──────

    /** Body of `test_msgpack_codec_serializes_enums_and_decoder_normalizes_keys` (enums → values). */
    @Test
    fun pyMaxIntAndBinaryKeysVector() {
        val value = linkedMapOf<Any?, Any?>(
            1 to mapOf("name".encodeToByteArray() to "DELAYED"),
            "list" to listOf("REGULAR"),
        )
        val bytes = hex("820181c4046e616d65a744454c41594544a46c69737491a7524547554c4152")
        assertContentEquals(bytes, codec.encode(value))
        val decoded = codec.decode(bytes) as Map<*, *>
        assertEquals(listOf(1, "list"), decoded.keys.toList())
        val inner = decoded[1] as Map<*, *>
        assertContentEquals("name".encodeToByteArray(), inner.keys.single() as ByteArray)
        assertEquals("DELAYED", inner.values.single())
    }

    /** Uncompressed body of `test_tcp_payload_decoder_decompresses_lz4_for_compression_factor_four`. */
    @Test
    fun pyMaxLz4TestBodyVector() {
        val value = linkedMapOf<Any?, Any?>(
            "prefix" to "xx",
            "data" to "fJsClKCufJsClKCu",
            "tail" to "y".repeat(42),
            "repeat" to "ABCD".repeat(26),
        )
        val bytes = hex(
            "84a6707265666978a27878a464617461b0664a73436c4b4375664a73436c4b4375a47461696cd92a" +
                "79".repeat(42) + "a6726570656174d968" + "41424344".repeat(26),
        )
        assertContentEquals(bytes, codec.encode(value))
        assertDeepEquals(value, codec.decode(bytes))
    }

    /** Body of `test_tcp_payload_decoder_decompresses_zstd` before compression. */
    @Test
    fun pyMaxZstdTestBodyVector() {
        val value = linkedMapOf<Any?, Any?>("error" to "FAIL_LOGIN_TOKEN", "message" to "Token expired")
        val bytes = hex("82a56572726f72b04641494c5f4c4f47494e5f544f4b454ea76d657373616765ad546f6b656e2065787069726564")
        assertContentEquals(bytes, codec.encode(value))
        assertDeepEquals(value, codec.decode(bytes))
    }

    /** `test_msgpack_codec_uses_first_dict_when_stream_has_extra_data`. */
    @Test
    fun trailingDataIsIgnoredByDefaultAndRejectedWhenStrict() {
        val bytes = hex("81a26f6bc391a769676e6f726564")
        assertDeepEquals(mapOf("ok" to true), codec.decode(bytes))
        assertFailsWith<MessagePackException> {
            DefaultMessagePackCodec(ignoreTrailingData = false).decode(bytes)
        }
    }

    // ── Errors ──────────────────────────────────────────────────────

    @Test
    fun malformedInputThrows() {
        val bad = listOf("", "c1", "a2 61", "cd 01", "92 01", "82 a1 61", "c4 05 00", "dd ff ff ff ff", "db 80 00 00 00", "a1 ff", "c70201cd")
        for (h in bad) {
            assertFailsWith<MessagePackException>("input '$h'") { codec.decode(hex(h)) }
        }
        assertFailsWith<MessagePackException> { codec.encode(Any()) }
        assertFailsWith<MessagePackException> { codec.encode(mapOf("k" to Unit)) }
        val deep = DefaultMessagePackCodec(maxDepth = 3)
        assertFailsWith<MessagePackException> { deep.decode(hex("91919191c0")) }
        assertFailsWith<MessagePackException> { deep.encode(listOf(listOf(listOf(listOf(listOf(1)))))) }
    }

    // ── Framing integration ─────────────────────────────────────────

    @Test
    fun payloadPacketRoundTrip() {
        val payload = linkedMapOf<Any?, Any?>(
            "chatId" to 1234567890123L,
            "message" to mapOf("text" to "привет", "cid" to -42, "attaches" to listOf<Any?>()),
            "notify" to true,
            7 to byteArrayOf(1, 2, 3),
        )
        val packet = encodePayloadPacket(PROTOCOL_VERSION, CmdType.REQUEST.value, 42, Opcode.MSG_SEND.value.toShort(), payload)
        val (header, decoded) = decodePayloadPacket(packet)
        assertEquals(42, header.seq)
        assertEquals(Opcode.MSG_SEND, Opcode.fromValue(header.opcode))
        assertEquals(false, header.compressed)
        assertEquals(packet.size - HEADER_SIZE, header.length)
        assertDeepEquals(payload, decoded)
    }

    /** kolibri `header_layout_matches_dart_wire_format`, built from the value instead of raw bytes. */
    @Test
    fun payloadPacketMatchesKolibriVector() {
        val packet = encodePayloadPacket(PROTOCOL_VERSION, CmdType.REQUEST.value, 5, Opcode.LOGIN.value.toShort(), mapOf("a" to 1))
        assertContentEquals(hex("0a 00 0005 0013 00000004 81a16101"), packet)
        assertDeepEquals(mapOf("a" to 1), decodePayloadPacket(packet).second)
    }

    @Test
    fun nullPayloadIsEmptyBody() {
        val packet = encodePayloadPacket(PROTOCOL_VERSION, CmdType.REQUEST.value, 1, Opcode.PING.value.toShort(), null)
        assertEquals(HEADER_SIZE, packet.size)
        val (header, decoded) = decodePayloadPacket(packet)
        assertEquals(0, header.length)
        assertNull(decoded)
    }
}
