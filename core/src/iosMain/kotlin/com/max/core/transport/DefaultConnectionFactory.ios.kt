package com.max.core.transport

/**
 * iOS: **not implemented.** Every `open` throws [NotImplementedError].
 *
 * Why: in Ktor 3.1.1 (gradle/libs.versions.toml) `ktor-network` has working raw TCP sockets on
 * iosArm64 / iosSimulatorArm64, but `ktor-network-tls` on Native only has a stub —
 * `openTLSSession` throws "TLS sessions are not supported on Native platform." — and the Ktor HTTP
 * engines (Darwin included) do not expose a raw TLS byte stream.
 *
 * TODO: iOS TLS socket. Planned route: Network.framework (`platform.Network`, available to
 * Kotlin/Native without a custom cinterop): `nw_connection` with TLS parameters, SNI = target host,
 * a `sec_protocol_options_set_verify_block` that evaluates the chain with `SecTrust` against the
 * system roots plus [MincifryCa.derCertificates] (`SecTrustSetAnchorCertificates` +
 * `SecTrustSetAnchorCertificatesOnly(false)`), and trust-all for [TlsOptions.insecure]. Proxies:
 * a plain `nw_connection` to the proxy for [performProxyHandshake], then TLS via
 * `nw_framer`/`nw_protocol_stack` or a manual upgrade — to be investigated.
 */
actual fun defaultConnectionFactory(): ConnectionFactory = ConnectionFactory { host, port, _, _ ->
    throw NotImplementedError("TODO: iOS TLS socket (connect to $host:$port); see DefaultConnectionFactory.ios.kt")
}
