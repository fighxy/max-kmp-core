package com.max.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Zstd decoder tests against frames produced by the reference zstd library (libzstd 1.5.7 through
 * python-zstandard 0.25.0) plus round-trips through the non-compressing [Zstd.compress].
 *
 * Generator (Python; [sampleText] is mirrored below):
 * ```
 * t = sample(3000)
 * l1  = ZstdCompressor(level=1).compress(t)
 * l3  = ZstdCompressor(level=3).compress(t)
 * l19 = ZstdCompressor(level=19, write_checksum=True).compress(t)
 * co = ZstdCompressor(level=3, write_checksum=True).compressobj()   # multi-block: flush per 2000 B
 * for chunk in chunks(sample(6000), 2000): co.compress(chunk); co.flush(COMPRESSOBJ_FLUSH_BLOCK)
 * co.flush()
 * zeros = ZstdCompressor(level=1, write_checksum=True).compress(b"\0" * 300000)
 * pymax = ZstdCompressor().compress(<PyMax test_tcp_payload_decoder_decompresses_zstd body>)
 * multi = ZstdCompressor(level=1).compress(b"hello ") + <skippable frame "skip"> +
 *         ZstdCompressor(level=1, write_content_size=False).compress(b"world")
 * ```
 */
class ZstdTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Compressed block: Huffman literals (FSE-compressed weights, 4 streams), FSE sequence tables. */
    private val level1 = hex(
            "28b52ffd60b80ad51500b65c471c80376903806ddb7cfc8f0dbf0891cb04108522bbbb77baf48d4bd5034f003d003700aae5" +
            "3002a41c46a0ca3219e634cc88c4280a108a83dbbb3bca610013cd48026261187959454a22510c5afa1385313838d66b13c3" +
            "4900907258204ae1403007a102c39c06a1c2510e03248088e1240265a5bcade22ff77e69cf725dfd7ff727ada3dd76f6914c" +
            "8948346d54dbd6ff893194734c62c926a77194e3ac6d520e8421707ef7701a0b72388dc5af023ebaa5abb5db6ab54eb33cda" +
            "9dcfb0cc2c7d5ba6abb7cdb4112619b1e9d6bfa65559a6c73af4151a59bd5ca564af5573e5511d7affb23e8af65c2b27d529" +
            "6df3e29a62e561a21d1ee2551e5a0d49ae166afab84746f312fad7bd53a55e69e5a1e19dd91d1ecb2e295fa7ea1f59d4812e" +
            "a83141321524ed773186884c20d90311943cd64524a2a0b8581803473f169e3155e946a3fa590a8f3ca51a0c370109947415" +
            "5d2e83fb15c193c705741d8e1f15713df0722c60e1a0e82212e18fead8d9d1ceb248c2535aefbfb18b15c7a4d3cfa2c563f3" +
            "d7ae6ce11847bafaeaf5cfd37c77d14cb9bc17a118e9a17fdc49d1f2c42633e4c9bcdb10c24a7c88d7698f39b057c147f827" +
            "ed701b5e0003410aeecd96002b7f15ea8b5d63ca15ef0d55b2dcdf89c766762b24d9d5a194cd67db3d28fcd2bfc63599efa7" +
            "5e5f7e8f3df4a6d0374fbea87f5ec7041880918f4eaa0e225eae4edfa7a60bf06324d2c11fd539ef98b5c7e3d78d5562ce5c" +
            "fe64479ed161c179de5f07250f354f2f6f076407afaf77df55c3c3028a96beb5428ca594f30166a790e43b98984e1e0fabb7" +
            "db0bfd818741071068156601a0c304eafca4c05e9897c6e9daa4a05e260f5dd39549093d265e615638e8d88a7d861df17a40" +
            "c523dd98642c6d89036104898e062d7a11eb436ab2593cf68ed6011a390c9d1a167d47b5c80369048aca2d0e50627d0b336b" +
            "10726aa5081b5601",
    )

    /** Same content at level 3: Huffman (4 streams) + FSE sequences. */
    private val level3 = hex(
            "28b52ffd60b80a251400f69a421c80296903c0b1ef40dfa07cb68d4f28aa4885eceede295dfac6b182014b00380032000b09" +
            "ae23162339047ebaa39a95e3a04071805059557646541a140987c2d5fdc5582c0c041eed7270182024d96c0b025900142391" +
            "a0300e09c3919439281cc84299436224064ac01404b20845bf78a644735d96adc8f7de58db99f6e06de452b5b6e95c8bd71a" +
            "b69445dbe5401808722c72da822c24468264db62248a83a0e7cc9240025339d5935ba4678fe86c54d5223b115d2b4f9868ca" +
            "bbd2d85009f376e2ff3ba1ed65ad1b0dfdfc1f87fc44eaafe62d4bfb0156f225f9bdf93dd7529a13ed5776466556bfb222d9" +
            "25def0ac8c9a63b531cbf3c33a5e9159155195d94e75737e21d69586ad8111a831415383a4fd337146231348b30d1120144f" +
            "a52466829202431a03b2d927de8c09f8d006ba6aa552f9259ac7f9f2488274e77aff86850f7cb0ef1710d6ef1c83b229e39f" +
            "b8633107bf410c9d1e3b7728d3d43736ead4714af34fbc1913f0d1d1cc7b2f3fbff3ddecdc0509f869d8dc8514a9fa9ca6d8" +
            "18847e29315851d8e1ae2212b6ddc47f41e2263c7e9f573045e8a67bcf3c6080934dafeaa2821a927738565e2149cde322c5" +
            "043dc2a7911847d2023a16d0ea65c3bcf68ddec6f1250698b7eb81cd20f90f263d6b1c4ff21770327b771cece037c1b19988" +
            "cfc389095ef285c218fafc03794c3afa0cd33152ea75d8e311f496bed042564a7dcf53ec3db54bbe40af290ef1003843fd80" +
            "af0094f6f3c2fd04d0d94f0b7603706e375fa85fa049cbbc04fd73254477db6821c7735a8a1fef8d2bc6d3ad115b441e7262" +
            "0b24500b86059040cd4292119c6b815aabd78680745d7d929cd0781024c92b74de723ee81b2b5236999b28d2e2c221979c50" +
            "08db5401",
    )

    /** Same content at level 19 with a content checksum. */
    private val level19Checksum = hex(
            "28b52ffd64b80a6d1200161c4418704dda6415c4e124a23db2ffbfd497dddd3b2554bdffefe14d003a003500859e33950324" +
            "e2701d51591a66f0d31dd54c61180805a35159557646a44c140ee67175bfb22c168b018fb6c260128d269b2d40590e0ec522" +
            "68244ca31cc1838144942368960341091cc50059047ed12f9e29d15c97652bf2bd37d676a6eb833fae51d654d8dbae74cc5a" +
            "78553543a21ee58fb615c6b04020cc224fab4022cdd240b23dcad22898d83a95a7fa24b748cf1ed1d9a8aa45f64374ad1366" +
            "99f2ac74ec0e95306f27e1ff3ba1ed65ad1b1dfaf93f1ef213a93f57e72d371d68e44b7aefdce75a6a9af3a0fdea8ccaccea" +
            "576645b24bbce1d596756a877aac7696cfb18ebf222bab22aa32dba9ee9c5f8875a50580cfa86140da0b92f6cf0027109974" +
            "071130188b23e18e91f0825463383de72b852f9d6ff4ac5b9f0a2c432f2358defcb35f79f99b6299f80fc7b6eb57f339823f" +
            "4b2c46fc37b1cb837c61cc3bf06a9572093709c826a318620b11a418f5088ad1f49ec42ae43584d586ff5a9a7764bcf8ed56" +
            "b9af7237f495f12e042c3bfca95746fcc1b02cf9a7626df1cff30f338e9fda5f8e2fb13e9157855e86afdcfc2d6079f91b61" +
            "1dcf74db3122c9ca8007770c1eef219621af149675feeaae16fa67badaf59f5b19f407b8b2e68f7265cb1f7465893fa99585" +
            "9fc043d57d9601903d25aa51a81fc1c800a36fa8d18c2c60a31033f9d857cd8f4e8db38cb1ec73cde3357ebb01b48563d8ae" +
            "485a68b1c46ccd8784acb45836d6aa3d806d31b1a0fd5d94b43c300c2076341e2d30e8687c581062ed09e3a9ee8b08a70a66" +
            "f59b43",
    )

    /**
     * Four blocks in one frame with checksum: Huffman + FSE, then two blocks with treeless
     * (repeat-table) single-stream literals and matches reaching into previous blocks, then an
     * empty raw last block.
     */
    private val multiBlock = hex(
            "28b52ffd0458640f0036d5381c80a76903801bf9f3e34d5458a7403b03501d577677f77ea94dc50a06380033002a0007d999" +
            "998c31a0ab88957018ce6173efca208829f8d8ac0ac7e020f5fbaf022100650cc3618884705215e27050528548c618a88052" +
            "0542023ff6d9ccea1406a18374f90b25c924d4ffcba4e3107caddb4089a10c94182c8fd9a42350261d813bdc3d1c94e055eb" +
            "140a8e42775cd0ae197f9be59eda6677b63baf61b35a6ea769a2edfd8c6f66dd6685cf14ed98bf6979d99ff7570e86480827" +
            "95fd8cd75fc435c5cc27ea21e23efb909b0d7bf758b4e091fbcc9aef6e0fd9f4529fa758f0d62e80bba82145311526ed7730" +
            "231151e00d116440588aa3404161218d01c264e5e239d5837c78671f2f3cb91a74ee127880e53449ada68a512a02ebddeecb" +
            "27ec11cf33b2627c9bc3837887e44e57132545f5953e5ef89cfed1e64befcf3fda80e97df9e74927cdc587fcb5bb5705a988" +
            "af0cdfd88ef1fbf8448d982f2e1f12366fcf2b9b872f4eefbc0e7e5f8f6c1edaa080f40915922fb5383f7f70aa46fefec054" +
            "9df8cb875035f0f7839e7c02b82f19b4f3e9c39e36e7c52606f592352b9942a1ffc59bc804c31b9cfd98ab9c47a4b1cb68cf" +
            "3d3844f9d367f485cfd10bf41358097cbf20345620f3fef11283d51ffd2bb38df2a71fb062b777075ef17939b326e63e02f8" +
            "2a8c0a00d30d1c0315b08d665d2e5bb9b197af19b3ed5d5efa8dcdb6c77cc6a79c4fd656e45d3be63ce33ebc73b12cfca2ef" +
            "352ebcd7bb88ac9bbbb8bb7ab46db4fb963b2bfb63ab35eb6dea2aa66adea69e51b7f9945753f15df466dc69e596f3781355" +
            "331133d5f430b7ac5d32dfb2959f25bbe40f0380a5a890521f50b342ec11d8405062462a90a0b42d1c740f30c4d4e25893d0" +
            "2bfac181f7ae80ee6b3ac8a768c1626ba4fa529cbea3c50cf61319564998facd985f15a66c1203b4065e12ea1985037b6aa9" +
            "457a65cdd4a54b7278f15579996016dd7bc37d4d87f7ac6198ce8287fffcf6e35b13378237d1eb07b629ca863d05aa2acae4" +
            "01746b9e12c35bf360887994d2e502ff9a1880ef0a7ef14ebfc71754ff8eb77c8ef55cf0c0858ed4778ec3342963df9d8329" +
            "4d7385e85a80382af0bea7f3eff266c3b407f59f9205d3068d551ff98ae966f4cac410a28a8bfb4340bc0b0003c70da577dc" +
            "a33ef38adf3bdb4bbfcb0defcef059f47897b513ffbbd9af20a6a2de1aff5dfebfdccaaf1ccbcbcbcbb79ccb4b979fbb9bfb" +
            "f00180b8a810db0310e3a9911e11b8942a5520c1d09024690c22465901a65ee909d8fcb294808d1db90418dc8c12b0544197" +
            "80a5156202629b76d67804cd032c24e0d211901da00829daa304eb008025e8b2bd4ac0ebbf8c2b0948faf7751b0149fda1dd" +
            "3f80443a510738f0f01d20c17fe800d666f30e90f5f7e284420f6cf417c3f8c0edffa4113bf01c00c20335ed40daf8970f08" +
            "e70788859cd40c20c883493fd026e3f0003b28a60668ffefdc57ba7b0190fe0806e465e00bf4ea97e9728112fcbadc2f5072" +
            "5fd6050325eb9366d802cdf14ebac06cb5c026dd26f434fc0c343b6ea289dbd8801afe5e03a6a151ab82460c74eba98e570a" +
            "5c8bc701eb47604de9099a23d036a7a51202f450bdc510b099d55b0801c51ce9c0d37f1d40ea413069dd8436113d0889369a" +
            "cf490f187308f2fe39c6a48b77d142633a33010100000ac9059a",
    )

    /** 300000 zero bytes: compressed block with raw literals and predefined tables, then two RLE blocks; checksum. */
    private val zeros = hex("28b52ffda4e09304005400001000000100fbff39c00202001000039f04002d28de26")

    /** PyMax `test_tcp_payload_decoder_decompresses_zstd`: one raw block. */
    private val pyMaxFrame = hex(
            "28b52ffd202e71010082a56572726f72b04641494c5f4c4f47494e5f544f4b454ea76d657373616765ad546f6b656e206578" +
            "7069726564",
    )

    /** Two frames with a skippable frame between them; the second has no content size. */
    private val multiFrame = hex(
            "28b52ffd200631000068656c6c6f20502a4d1804000000736b697028b52ffd0000290000776f726c64",
    )

    @Test
    fun decodesCompressedBlocksAtLevels1And3And19() {
        val expected = sampleText(3000)
        assertContentEquals(expected, Zstd.decompress(level1))
        assertContentEquals(expected, Zstd.decompress(level3))
        assertContentEquals(expected, Zstd.decompress(level19Checksum))
    }

    @Test
    fun decodesMultiBlockFrameWithRepeatTablesAndChecksum() {
        assertContentEquals(sampleText(6000), Zstd.decompress(multiBlock))
    }

    @Test
    fun decodesRleBlocksAcrossTheBlockSizeLimit() {
        assertContentEquals(ByteArray(300000), Zstd.decompress(zeros))
    }

    @Test
    fun decodesRawBlockAndMultipleFramesWithSkippableFrame() {
        val body = Zstd.decompress(pyMaxFrame)
        assertEquals(mapOf("error" to "FAIL_LOGIN_TOKEN", "message" to "Token expired"), DefaultMessagePackCodec.decode(body))
        assertContentEquals("hello world".encodeToByteArray(), Zstd.decompress(multiFrame))
    }

    @Test
    fun zstdFlaggedPacketIsDecompressed() {
        val header = PacketHeader(PROTOCOL_VERSION, 0, 0, 128, pyMaxFrame.size, compressed = true, compressionFlag = 0xFF)
        val (_, payload) = decodePayloadPacket(encodePacket(header, pyMaxFrame))
        assertEquals(mapOf("error" to "FAIL_LOGIN_TOKEN", "message" to "Token expired"), payload)
        // kolibri sniffs the magic number, so a Zstd body under an LZ4 flag decodes as well
        val lz4Flagged = header.copy(compressionFlag = 3)
        assertEquals(payload, decodePayloadPacket(encodePacket(lz4Flagged, pyMaxFrame)).second)
    }

    @Test
    fun rejectsCorruptFrames() {
        val badChecksum = level19Checksum.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<CompressionException> { Zstd.decompress(badChecksum) }
        assertFailsWith<CompressionException> { Zstd.decompress(level3.copyOf(level3.size - 10)) }
        assertFailsWith<CompressionException> { Zstd.decompress(ByteArray(0)) }
        assertFailsWith<CompressionException> { Zstd.decompress(hex("28b52ffe0000")) } // bad magic
        // reserved block type 3
        assertFailsWith<CompressionException> { Zstd.decompress(hex("28b52ffd2000070000")) }
        // dictionary ID present
        assertFailsWith<CompressionException> { Zstd.decompress(hex("28b52ffd210105010000")) }
        // flipping bytes inside a compressed block must never escape as another exception type
        for (i in 6 until level1.size step 7) {
            val corrupted = level1.copyOf().also { it[i] = (it[i].toInt() xor 0x5A).toByte() }
            try {
                Zstd.decompress(corrupted)
            } catch (e: CompressionException) {
                // expected for most positions
            }
        }
    }

    @Test
    fun sizeGuardLimitsOutput() {
        // PyMax test_zstd_decompression_rejects_oversized_output: b"x" * 128 with a 64-byte limit
        val frame = hex("28b52ffd208045000010787801003b0558")
        assertContentEquals(ByteArray(128) { 'x'.code.toByte() }, Zstd.decompress(frame))
        assertFailsWith<CompressionException> { Zstd.decompress(frame, maxSize = 64) }
        assertFailsWith<CompressionException> { Zstd.decompress(zeros, maxSize = 200000) }
        assertFailsWith<CompressionException> { Compression.decompress(zeros, CompressionFormat.ZSTD, maxSize = 1000) }
    }

    @Test
    fun nonCompressingEncoderRoundTrips() {
        val inputs = listOf(
            ByteArray(0),
            byteArrayOf(42),
            sampleText(1000),
            ByteArray(200000) { 7 },
            ByteArray(300000) { (it * 31 + (it ushr 9)).toByte() },
        )
        for (input in inputs) {
            val frame = Zstd.compress(input)
            assertTrue(Zstd.hasMagic(frame))
            assertContentEquals(input, Zstd.decompress(frame))
            assertContentEquals(input, Compression.decompress(Compression.compress(input, CompressionFormat.ZSTD), CompressionFormat.ZSTD))
        }
        // runs of one byte become RLE blocks: 200000 B -> header + two tiny blocks + checksum
        assertTrue(Zstd.compress(ByteArray(200000) { 7 }).size < 32)
    }

    @Test
    fun xxHashMatchesReferenceValues() {
        // XXH64("", 0) and XXH32("", 0) from the xxHash specification test vectors
        assertEquals(-0x10b924c8ae271667L /* 0xEF46DB3751D8E999 */, XxHash.xxh64(ByteArray(0)))
        assertEquals(0x02CC5D05, XxHash.xxh32(ByteArray(0)))
    }
}

private val SAMPLE_WORDS = listOf("alpha", "beta", "gamma", "delta", "chat", "message", "user", "token", "push", "reply", "status", "online")

/** Deterministic text mirrored by the Python vector generator (`sample(n)`). */
internal fun sampleText(n: Int): ByteArray {
    val sb = StringBuilder()
    var i = 0
    while (sb.length < n) {
        sb.append("msg $i: chat=${i % 17} user=${(i * 7919) % 1000} text=${SAMPLE_WORDS[(i * 31) % SAMPLE_WORDS.size]} ${SAMPLE_WORDS[(i * 7) % SAMPLE_WORDS.size]}\n")
        i++
    }
    return sb.toString().encodeToByteArray().copyOf(n)
}
