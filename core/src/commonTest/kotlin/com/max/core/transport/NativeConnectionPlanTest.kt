package com.max.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NativeConnectionPlanTest {

    @Test
    fun noProxyIsDirectOnAnyVersion() {
        assertEquals(NativeProxyPlan.Direct, planNativeProxy(null, osMajorVersion = 12))
        assertEquals(NativeProxyPlan.Direct, planNativeProxy(null, osMajorVersion = 18))
    }

    @Test
    fun httpAndSocksMapOnIos17() {
        val http = ProxyConfig.parse("http://bob:secret@10.0.0.1:8080")
        assertEquals(NativeProxyPlan.HttpConnect(http), planNativeProxy(http, osMajorVersion = 17))
        val socks = ProxyConfig.parse("socks5://127.0.0.1:1080")
        assertEquals(NativeProxyPlan.Socks5(socks), planNativeProxy(socks, osMajorVersion = 18))
        // socks5h also goes to the OS SOCKS5 proxy: the host name is passed to the proxy either way
        val socksH = ProxyConfig.parse("socks5h://u:p@proxy.local:1080")
        assertEquals(NativeProxyPlan.Socks5(socksH), planNativeProxy(socksH, osMajorVersion = 17))
    }

    @Test
    fun proxyBelowMinimumIsUnsupported() {
        val plan = planNativeProxy(ProxyConfig.parse("socks5://127.0.0.1:1080"), osMajorVersion = 16)
        assertIs<NativeProxyPlan.Unsupported>(plan)
        assertTrue("iOS 17+" in plan.reason, plan.reason)
        assertTrue("proxy not supported" in plan.reason, plan.reason)
        assertEquals(17, IOS_PROXY_MIN_MAJOR_VERSION)
    }

    @Test
    fun customMinimumAndOsName() {
        val proxy = ProxyConfig.parse("http://proxy.local:3128")
        assertIs<NativeProxyPlan.HttpConnect>(planNativeProxy(proxy, osMajorVersion = 14, minOsMajorVersion = 14, osName = "macOS"))
        val plan = planNativeProxy(proxy, osMajorVersion = 13, minOsMajorVersion = 14, osName = "macOS")
        assertIs<NativeProxyPlan.Unsupported>(plan)
        assertTrue("macOS 14+" in plan.reason, plan.reason)
    }

    @Test
    fun unsupportedReasonDoesNotLeakPassword() {
        val plan = planNativeProxy(ProxyConfig.parse("http://bob:s3cr3t@10.0.0.1:8080"), osMajorVersion = 15)
        assertIs<NativeProxyPlan.Unsupported>(plan)
        assertTrue("s3cr3t" !in plan.reason)
    }

    @Test
    fun trustModeFollowsTlsOptions() {
        assertEquals(TlsTrustMode.SYSTEM_AND_MINCIFRY, TlsOptions().trustMode())
        assertEquals(TlsTrustMode.SYSTEM, TlsOptions(trustMincifryCa = false).trustMode())
        assertEquals(TlsTrustMode.INSECURE, TlsOptions(insecure = true).trustMode())
        assertEquals(TlsTrustMode.INSECURE, TlsOptions(insecure = true, trustMincifryCa = false).trustMode())
    }

    @Test
    fun trustModeFromTransportConfigDefaults() {
        val cfg = TransportConfig("api.oneme.ru")
        assertEquals(TlsTrustMode.SYSTEM_AND_MINCIFRY, TlsOptions(cfg.insecureTls, cfg.trustMincifryCa, cfg.connectTimeout).trustMode())
    }

    @Test
    fun receiveMaxIsBoundedByBufferAnd64KiB() {
        assertEquals(1, nativeReceiveMax(0))
        assertEquals(1, nativeReceiveMax(1))
        assertEquals(4096, nativeReceiveMax(4096))
        assertEquals(65536, nativeReceiveMax(65536))
        assertEquals(65536, nativeReceiveMax(1_000_000))
        assertEquals(64 * 1024, NATIVE_RECEIVE_MAX)
    }

    @Test
    fun connectTimeoutSecondsRoundsUp() {
        assertEquals(15, nativeConnectTimeoutSeconds(15.seconds))
        assertEquals(2, nativeConnectTimeoutSeconds(1500.milliseconds))
        assertEquals(1, nativeConnectTimeoutSeconds(1.milliseconds))
        assertEquals(1, nativeConnectTimeoutSeconds(Duration.ZERO))
        assertEquals(0, nativeConnectTimeoutSeconds(Duration.INFINITE))
    }
}
