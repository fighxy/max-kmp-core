package com.max.core.media

import com.max.core.transport.ProxyConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * iOS-only tests (simulator: `gradle :core:iosSimulatorArm64Test`). The offline ones run in CI;
 * the network one is `@Ignore`d (the simulator test task has no local HTTP server to talk to),
 * remove the annotation to run it locally. End-to-end upload flows are covered on the JVM
 * against a local server (`OkHttpMediaHttpTest`).
 */
class UrlSessionMediaHttpTest {

    @Test
    fun defaultIsUrlSessionAndShared() {
        val http = assertIs<UrlSessionMediaHttp>(defaultMediaHttp())
        assertSame(http, defaultMediaHttp())
        assertIs<UrlSessionMediaHttp>(defaultMediaHttp(MediaHttpConfig(trustMincifryCa = false)))
    }

    @Test
    fun proxyIsRejected() {
        assertFailsWith<IllegalArgumentException> { UrlSessionMediaHttp(MediaHttpConfig(proxy = ProxyConfig.parse("http://127.0.0.1:8080"))) }
    }

    @Test
    fun nsDataRoundTrip() {
        val bytes = ByteArray(70_000) { it.toByte() }
        assertContentEquals(bytes, bytes.toNSData().toByteArray())
        assertEquals(0, ByteArray(0).toNSData().toByteArray().size)
    }

    @Test
    fun invalidUrlFails() = runBlocking {
        assertFailsWith<IllegalArgumentException> { UrlSessionMediaHttp().request("GET", "not a url", emptyList(), ByteArray(0), null) }
        Unit
    }

    @Ignore
    @Test
    fun postToRealEndpoint() = runBlocking {
        val sent = ArrayList<Long>()
        val body = ByteArray(200_000) { 0x58 }
        val r = UrlSessionMediaHttp().request(
            "POST", "https://httpbin.org/post",
            UploadRequests.singlePostHeaders("clip.bin", body.size, "OKMessages/26.25.0 (Android 14; Pixel 8; 428dpi 428dpi 1080x2400)"),
            body,
        ) { s, _ -> sent += s }
        assertEquals(200, r.status)
        assertEquals(body.size.toLong(), sent.last())
        assertTrue(sent.zipWithNext().all { (a, b) -> a <= b })
    }
}
