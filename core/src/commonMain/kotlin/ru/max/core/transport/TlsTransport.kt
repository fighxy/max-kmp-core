package ru.max.core.transport

/**
 * Persistent TLS socket + request/response multiplexing by seq.
 * Host / port / proxy supplied by [TransportConfig].
 *
 * TODO: implement with Ktor sockets / engine per target (see expect/actual).
 */
interface TlsTransport {
    suspend fun connect()
    suspend fun send(bytes: ByteArray)
    suspend fun close()
}

data class TransportConfig(
    val host: String,
    val port: Int = 443,
    val proxyUrl: String? = null,
)
