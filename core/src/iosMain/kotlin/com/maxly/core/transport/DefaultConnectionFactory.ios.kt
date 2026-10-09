package com.maxly.core.transport

/**
 * iOS: Apple Network.framework ([NetworkFrameworkConnectionFactory]): `nw_connection` with TLS
 * parameters, system roots plus [MincifryCa], HTTP CONNECT / SOCKS5 proxies on iOS 17+.
 *
 * Why not Ktor: in Ktor 3.1.1 `ktor-network-tls` on Native is a stub ("TLS sessions are not
 * supported on Native platform") and the Darwin HTTP engine does not expose a raw TLS stream.
 */
actual fun defaultConnectionFactory(): ConnectionFactory = NetworkFrameworkConnectionFactory()
