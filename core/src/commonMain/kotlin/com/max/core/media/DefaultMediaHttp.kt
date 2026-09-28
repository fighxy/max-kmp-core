package com.max.core.media

import com.max.core.transport.MincifryCa
import com.max.core.transport.ProxyConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Settings of the platform [MediaHttp] ([defaultMediaHttp]).
 *
 * @property connectTimeout TCP + TLS connect timeout.
 * @property requestTimeout whole-request timeout (connect, body, response). kolibri uses 300 s
 *   for single-POST uploads and 120 s for photos and video chunks (`kolibri-net/src/media/upload.rs`).
 * @property trustMincifryCa trust the [MincifryCa] roots in addition to the system roots, like
 *   the socket transport (`TransportConfig.trustMincifryCa`).
 * @property insecure accept any certificate and host name (testing only; wins over
 *   [trustMincifryCa]).
 * @property proxy HTTP CONNECT or SOCKS5 proxy for CDN requests (kolibri applies the session
 *   proxy to media as well). Supported by the OkHttp client only (SOCKS5 without credentials);
 *   the iOS client rejects it, see `defaultMediaHttp` on iOS.
 */
data class MediaHttpConfig(
    val connectTimeout: Duration = 30.seconds,
    val requestTimeout: Duration = 300.seconds,
    val trustMincifryCa: Boolean = true,
    val insecure: Boolean = false,
    val proxy: ProxyConfig? = null,
)

/**
 * The platform CDN client: OkHttp on JVM and Android, `NSURLSession` on iOS. [MediaApi] uses it
 * when no [MediaHttp] is passed. The default-config instance is shared.
 */
expect fun defaultMediaHttp(config: MediaHttpConfig = MediaHttpConfig()): MediaHttp
