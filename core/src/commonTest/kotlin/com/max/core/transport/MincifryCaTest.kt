package com.max.core.transport

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
    fun defaultConfigTrustsMincifry() {
        assertTrue(TransportConfig("api.oneme.ru").trustMincifryCa)
    }
}
