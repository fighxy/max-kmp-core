package com.max.core.transport

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSocketConnectionFactoryTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

    @Test
    fun mincifryCertificatesParseWithExpectedFingerprints() {
        val certs = JavaSocketConnectionFactory.mincifryCertificates()
        assertEquals(2, certs.size)
        assertTrue(certs[0].subjectX500Principal.name.contains("CN=Russian Trusted Root CA"))
        assertTrue(certs[1].subjectX500Principal.name.contains("CN=Russian Trusted Sub CA"))
        assertEquals(certs[0].subjectX500Principal, certs[1].issuerX500Principal)
        certs[1].verify(certs[0].publicKey) // Sub is signed by Root
        assertEquals(
            "D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31",
            sha256(certs[0].encoded),
        )
        assertEquals(
            "21:55:78:50:36:C9:00:DB:B5:F1:BB:2A:15:69:C8:0C:55:59:5B:D6:BF:94:86:7A:29:BB:DD:BC:7D:88:A3:F2",
            sha256(certs[1].encoded),
        )
    }

    @Test
    fun compositeTrustIncludesSystemRootsAndMincifry() {
        val system = JavaSocketConnectionFactory.systemTrustManager()
        val composite = CompositeTrustManager(listOf(system, JavaSocketConnectionFactory.mincifryTrustManager()))
        val issuers = composite.acceptedIssuers.map { it.subjectX500Principal.name }
        assertEquals(system.acceptedIssuers.size + 2, issuers.size)
        assertTrue(issuers.any { "Russian Trusted Root CA" in it })
        assertTrue(issuers.any { "Russian Trusted Sub CA" in it })
    }

    @Test
    fun sslContextBuildsInEveryMode() {
        listOf(TlsOptions(), TlsOptions(trustMincifryCa = false), TlsOptions(insecure = true)).forEach {
            JavaSocketConnectionFactory.sslContext(it).socketFactory
        }
    }

    @Test
    fun defaultFactoryIsJavaSockets() {
        assertTrue(defaultConnectionFactory() is JavaSocketConnectionFactory)
    }
}
