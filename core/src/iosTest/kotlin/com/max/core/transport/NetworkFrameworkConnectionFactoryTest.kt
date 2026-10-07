@file:OptIn(ExperimentalForeignApi::class)

package com.max.core.transport

import com.max.core.protocol.Opcode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFRelease
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_endpoint_create_host
import platform.darwin.dispatch_queue_create
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * iOS-only tests (simulator: `gradle :core:iosSimulatorArm64Test`). The offline ones run in CI;
 * the ones that need the Max servers are `@Ignore`d, remove the annotation to run them locally.
 */
class NetworkFrameworkConnectionFactoryTest {

    @Test
    fun defaultFactoryIsNetworkFramework() {
        assertIs<NetworkFrameworkConnectionFactory>(defaultConnectionFactory())
    }

    @Test
    fun mincifryAnchorsBuildFromDer() {
        val array = assertNotNull(NetworkFrameworkConnectionFactory.createCertificateArray(MincifryCa.derCertificates))
        try {
            assertEquals(2L, CFArrayGetCount(array))
        } finally {
            CFRelease(array)
        }
    }

    @Test
    fun invalidDerIsRejected() {
        assertNull(NetworkFrameworkConnectionFactory.createCertificateArray(listOf(byteArrayOf(0x30, 0x03, 0x01, 0x02, 0x03))))
    }

    @Test
    fun parametersBuildInEveryTrustMode() {
        listOf(TlsOptions(), TlsOptions(trustMincifryCa = false), TlsOptions(insecure = true)).forEach { tls ->
            assertNotNull(NetworkFrameworkConnectionFactory.secureTcpParameters("api.oneme.ru", tls), "$tls")
        }
    }

    @Test
    fun proxyGateMatchesOsVersion() {
        val major = NetworkFrameworkConnectionFactory.currentIosMajorVersion()
        assertTrue(major >= 12, "iOS $major")
        assertEquals(major >= IOS_PROXY_MIN_MAJOR_VERSION, NetworkFrameworkConnectionFactory.isProxySupported())
    }

    @Test
    fun proxyBelowIos17FailsClearly() = runBlocking {
        if (NetworkFrameworkConnectionFactory.isProxySupported()) return@runBlocking // only meaningful on < 17
        val e = assertFailsWith<ProxyException> {
            NetworkFrameworkConnectionFactory().open("api.oneme.ru", 443, TlsOptions(), ProxyConfig.parse("socks5://127.0.0.1:1080"))
        }
        assertTrue("iOS 17+" in (e.message ?: ""), e.message)
    }

    @Test
    fun refusedConnectionFailsFast() = runBlocking {
        // nothing listens on port 1: the connection goes to failed/waiting and open() throws
        val e = assertFailsWith<TransportException> {
            withTimeout(20.seconds) {
                NetworkFrameworkConnectionFactory().open("127.0.0.1", 1, TlsOptions(connectTimeout = 5.seconds), null)
            }
        }
        assertTrue(e.message?.contains("127.0.0.1:1") == true, e.message)
    }

    @Test
    fun describesMissingError() {
        assertEquals("no error details", describeNwError(null))
    }

    @Test
    fun queuedWriteUsesNativeContextWithoutStartingNetwork(): Unit = runBlocking {
        val queue = assertNotNull(dispatch_queue_create("com.max.core.test.queued-write", null))
        val endpoint = assertNotNull(nw_endpoint_create_host("127.0.0.1", "9"))
        val parameters = assertNotNull(NetworkFrameworkConnectionFactory.secureTcpParameters("localhost", TlsOptions()))
        val connection = assertNotNull(nw_connection_create(endpoint, parameters))
        nw_connection_set_queue(connection, queue)
        val raw = NwRawConnection(connection, queue, "offline-test")
        try {
            // Network.framework permits a send before start and queues it. This exercises
            // the actual dispatch_data/context/send bindings without connecting to a server.
            // The old default stream sentinel aborted the process here instead of timing out.
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) { raw.write(byteArrayOf(1, 2, 3)) }
            }
            assertFailsWith<ConnectionClosedException> { raw.write(byteArrayOf(4)) }
        } finally {
            raw.close()
        }
    }

    @Test
    fun cancelledReadDoesNotBridgeContext(): Unit = runBlocking {
        val queue = assertNotNull(dispatch_queue_create("com.max.core.test.cancelled-read", null))
        val endpoint = assertNotNull(nw_endpoint_create_host("127.0.0.1", "9"))
        val parameters = assertNotNull(NetworkFrameworkConnectionFactory.secureTcpParameters("localhost", TlsOptions()))
        val connection = assertNotNull(nw_connection_create(endpoint, parameters))
        nw_connection_set_queue(connection, queue)
        val raw = NwRawConnection(connection, queue, "offline-read")
        // A receive queued before start completes only when the timeout cancels the connection:
        // its completion then runs with whatever context Network.framework hands back.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1.seconds) { raw.read(ByteArray(16), 0, 16) }
        }
        assertFailsWith<ConnectionClosedException> { raw.read(ByteArray(16), 0, 16) }
        // Let the cancelled receive's completion run before the test ends.
        delay(300.milliseconds)
    }

    // ── integration (network, Max servers) ─────────────────────────

    @Ignore
    @Test
    fun integrationPingOverTls() = runBlocking {
        val transport = MaxTransport(TransportConfig("api.oneme.ru", pingInterval = kotlin.time.Duration.INFINITE, autoReconnect = false))
        try {
            withTimeout(30.seconds) {
                transport.connect()
                // Without the session handshake the server may answer with an error, which still
                // proves a full TLS round trip with a framed reply for our seq.
                try {
                    val reply = transport.request(Opcode.PING, mapOf("interactive" to true))
                    assertEquals(Opcode.PING.value, reply.opcode)
                } catch (e: ServerErrorException) {
                    assertEquals(Opcode.PING.value, e.packet.opcode)
                }
            }
        } finally {
            transport.close()
        }
    }

    @Ignore
    @Test
    fun integrationInsecureModeConnects() = runBlocking<Unit> {
        val conn = withTimeout(20.seconds) {
            NetworkFrameworkConnectionFactory().open("api.oneme.ru", 443, TlsOptions(insecure = true), null)
        }
        conn.close()
        conn.close() // idempotent
        assertFailsWith<ConnectionClosedException> { conn.write(byteArrayOf(1)) }
    }

    @Ignore
    @Test
    fun integrationMincifryTrustOnApi2() = runBlocking {
        // api2.oneme.ru chains to the Russian Trusted CA, absent from the iOS trust store:
        // accepted with trustMincifryCa, rejected by the default evaluation.
        val ok = withTimeout(20.seconds) {
            NetworkFrameworkConnectionFactory().open("api2.oneme.ru", 443, TlsOptions(trustMincifryCa = true), null)
        }
        ok.close()
        try {
            val conn = withTimeout(20.seconds) {
                NetworkFrameworkConnectionFactory().open("api2.oneme.ru", 443, TlsOptions(trustMincifryCa = false), null)
            }
            conn.close()
            fail("default trust accepted api2.oneme.ru; is the Russian Trusted Root installed on this device?")
        } catch (e: TransportException) {
            assertTrue("TLS" in (e.message ?: ""), e.message)
        }
    }
}
