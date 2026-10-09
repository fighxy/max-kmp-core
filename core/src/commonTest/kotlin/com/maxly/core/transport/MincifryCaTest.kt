package com.maxly.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MincifryCaTest {
    @Test
    fun twoDerCertificates() {
        val ders = MincifryCa.derCertificates
        assertEquals(2, ders.size)
        ders.forEach { der ->
            assertEquals(0x30, der[0].toInt() and 0xFF) // DER SEQUENCE
            assertTrue(der.size > 1000)
            assertTrue(der.decodeToString(throwOnInvalidSequence = false).contains("Russian Trusted"))
        }
        assertFailsWith<IllegalArgumentException> { MincifryCa.pemToDer("nothing here") }
    }

    @Test
    fun pemToDerHandlesLineWrappingAndSurroundingText() {
        // the Root PEM is wrapped at 64 columns, the Sub PEM at 76: both decode to one DER blob
        val root = MincifryCa.pemToDer(MincifryCa.ROOT_CA_PEM)
        val crlf = MincifryCa.pemToDer("junk before\r\n" + MincifryCa.ROOT_CA_PEM.replace("\n", "\r\n") + "\r\ntrailer")
        assertTrue(root.contentEquals(crlf))
        // DER length prefix: 0x30 0x82 <len hi> <len lo> covers exactly the rest of the blob
        MincifryCa.derCertificates.forEach { der ->
            assertEquals(0x82, der[1].toInt() and 0xFF)
            val len = ((der[2].toInt() and 0xFF) shl 8) or (der[3].toInt() and 0xFF)
            assertEquals(der.size, len + 4)
        }
    }

    @Test
    fun pemToDerRejectsTruncatedBlock() {
        assertFailsWith<IllegalArgumentException> {
            MincifryCa.pemToDer(MincifryCa.ROOT_CA_PEM.substringBefore("-----END"))
        }
    }

    @Test
    fun defaultConfigTrustsMincifry() {
        assertTrue(TransportConfig("api.oneme.ru").trustMincifryCa)
    }
}
