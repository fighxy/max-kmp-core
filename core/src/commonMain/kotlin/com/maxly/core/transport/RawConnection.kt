package com.maxly.core.transport

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A bidirectional byte stream: a plain TCP socket (during the proxy handshake) or a TLS session.
 * Everything above this interface ([MaxTransport], the proxy handshakes in ProxyHandshake.kt) is
 * common code; only [ConnectionFactory] implementations are platform specific.
 *
 * [close] may be called while another coroutine is blocked in [read]; that read must then end
 * (return `-1` or throw).
 */
interface RawConnection {
    /**
     * Reads up to [length] bytes into [buffer] at [offset]. Suspends until at least one byte is
     * available. Returns the number of bytes read, or `-1` at end of stream.
     */
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int

    /** Writes all of [bytes] and flushes. */
    suspend fun write(bytes: ByteArray)

    /** Closes the stream. Idempotent. */
    suspend fun close()
}

/** TLS settings passed to a [ConnectionFactory]; see [TransportConfig]. */
data class TlsOptions(
    val insecure: Boolean = false,
    val trustMincifryCa: Boolean = true,
    val connectTimeout: Duration = 15.seconds,
)

/**
 * Opens a TLS connection to [host]:[port]. With a [proxy], TLS must run end-to-end to [host]
 * (server name and certificate check use [host]) through the tunnel: the JVM/Android factory
 * opens plain TCP to the proxy, runs [performProxyHandshake] over it and then starts TLS; the iOS
 * factory lets Network.framework build the tunnel (iOS 17+, see [planNativeProxy]).
 */
fun interface ConnectionFactory {
    suspend fun open(host: String, port: Int, tls: TlsOptions, proxy: ProxyConfig?): RawConnection
}

/**
 * The platform connection factory:
 * - JVM and Android: `java.net.Socket` + `javax.net.ssl` (`JavaSocketConnectionFactory`);
 * - iOS: Apple Network.framework (`NetworkFrameworkConnectionFactory`).
 */
expect fun defaultConnectionFactory(): ConnectionFactory

/**
 * Reads exactly [count] bytes.
 *
 * @throws ConnectionClosedException if the stream ends first.
 */
suspend fun RawConnection.readExactly(count: Int): ByteArray {
    val out = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val n = read(out, filled, count - filled)
        if (n < 0) throw ConnectionClosedException("stream ended after $filled of $count bytes")
        filled += n
    }
    return out
}
