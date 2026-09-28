package com.max.core.transport

import kotlin.time.Duration

/*
 * Pure decisions used by platform connection factories that delegate TLS and proxies to the OS
 * (the iOS Network.framework factory). Kept in common code so they are unit-tested on the JVM.
 */

/** How a factory backed by an OS networking stack should reach the target for a given proxy. */
sealed interface NativeProxyPlan {
    /** No proxy: connect to the target directly. */
    data object Direct : NativeProxyPlan

    /** Let the OS stack open an HTTP CONNECT tunnel through [proxy]. */
    data class HttpConnect(val proxy: ProxyConfig) : NativeProxyPlan

    /**
     * Let the OS stack open a SOCKS5 tunnel through [proxy]. Used for both `socks5` and
     * `socks5h`: the OS stack sends the target host name to the proxy (resolved there), which is
     * the same DNS behaviour as the common [performProxyHandshake] (see [ProxyConfig]).
     */
    data class Socks5(val proxy: ProxyConfig) : NativeProxyPlan

    /** A proxy is configured but the running OS cannot tunnel it; [reason] is user facing. */
    data class Unsupported(val reason: String) : NativeProxyPlan
}

/**
 * Chooses the [NativeProxyPlan] for [proxy] on an OS whose major version is [osMajorVersion].
 * OS-level proxies need at least [minOsMajorVersion] (iOS 17 for Network.framework's
 * `nw_proxy_config_*`); below that a proxy yields [NativeProxyPlan.Unsupported] instead of a
 * silent direct connection.
 */
fun planNativeProxy(
    proxy: ProxyConfig?,
    osMajorVersion: Int,
    minOsMajorVersion: Int = IOS_PROXY_MIN_MAJOR_VERSION,
    osName: String = "iOS",
): NativeProxyPlan {
    if (proxy == null) return NativeProxyPlan.Direct
    if (osMajorVersion < minOsMajorVersion) {
        return NativeProxyPlan.Unsupported(
            "proxy not supported on $osName $osMajorVersion: needs $osName $minOsMajorVersion+ " +
                "(Network.framework proxy configuration)",
        )
    }
    return when (proxy.kind) {
        ProxyKind.HTTP -> NativeProxyPlan.HttpConnect(proxy)
        ProxyKind.SOCKS5, ProxyKind.SOCKS5H -> NativeProxyPlan.Socks5(proxy)
    }
}

/** First iOS major version with Network.framework proxy configuration (`nw_proxy_config_t`). */
const val IOS_PROXY_MIN_MAJOR_VERSION: Int = 17

/** How the server certificate is checked, derived from [TlsOptions]. */
enum class TlsTrustMode {
    /** Accept any certificate, no host name check ([TlsOptions.insecure]). Debug only. */
    INSECURE,

    /** System roots plus the [MincifryCa] certificates, with the host name check. */
    SYSTEM_AND_MINCIFRY,

    /** Platform default evaluation (system roots, host name check). */
    SYSTEM,
}

/** [TlsOptions.insecure] wins over [TlsOptions.trustMincifryCa]. */
fun TlsOptions.trustMode(): TlsTrustMode = when {
    insecure -> TlsTrustMode.INSECURE
    trustMincifryCa -> TlsTrustMode.SYSTEM_AND_MINCIFRY
    else -> TlsTrustMode.SYSTEM
}

/** Largest single receive requested from the OS stack (64 KiB). */
const val NATIVE_RECEIVE_MAX: Int = 64 * 1024

/**
 * Maximum byte count for one OS receive into a caller buffer of [length] bytes: never more than
 * the caller can take (so no bytes need to be buffered between reads), at most [NATIVE_RECEIVE_MAX].
 */
fun nativeReceiveMax(length: Int): Int = length.coerceIn(1, NATIVE_RECEIVE_MAX)

/**
 * [timeout] as whole seconds for an OS-level TCP connect timeout (e.g.
 * `nw_tcp_options_set_connection_timeout`): rounded up, at least 1, [Duration.INFINITE] gives 0
 * (the OS default, no explicit limit).
 */
fun nativeConnectTimeoutSeconds(timeout: Duration): Int {
    if (timeout.isInfinite()) return 0
    val ms = timeout.inWholeMilliseconds
    if (ms <= 0) return 1
    return ((ms + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
