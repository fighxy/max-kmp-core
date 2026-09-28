package com.max.core.transport

/** Base class of the transport errors. */
open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Not connected, closed by [TlsTransport.close], or the connection dropped while waiting. */
open class ConnectionClosedException(message: String = "connection closed", cause: Throwable? = null) :
    TransportException(message, cause)

/** TCP + proxy handshake + TLS did not finish within `connectTimeout`. */
class ConnectTimeoutException(message: String) : TransportException(message)

/** No reply with the request's `seq` within `requestTimeout`. */
class RequestTimeoutException(val opcode: Int, val seq: Int, message: String) : TransportException(message)

/** The proxy refused or broke the tunnel (HTTP CONNECT status, SOCKS5 reply). */
class ProxyException(message: String) : TransportException(message)

/**
 * Reply with `cmd = ERROR`. [message] is taken from `localizedMessage` / `message` / `title` of the
 * payload map (kolibri `error_from_payload`), [errorKey] from `error`.
 */
class ServerErrorException(
    message: String,
    val errorKey: String?,
    val rawMessage: String?,
    val packet: TransportPacket,
) : TransportException(message) {
    /** kolibri maps `message == "FAIL_LOGIN_TOKEN"` to `SessionExpired`. */
    val isSessionExpired: Boolean get() = rawMessage == "FAIL_LOGIN_TOKEN"

    companion object {
        /** Builds the exception from an ERROR reply. */
        fun from(packet: TransportPacket): ServerErrorException {
            val map = packet.payload as? Map<*, *>
            fun str(key: String): String? = (map?.get(key) as? String)
            val message = listOf("localizedMessage", "message", "title")
                .firstNotNullOfOrNull { key -> str(key)?.trim()?.takeIf { it.isNotEmpty() } }
                ?: "unknown error"
            return ServerErrorException(message, str("error"), str("message"), packet)
        }
    }
}

/**
 * Reply with `cmd = NOT_FOUND` (2). kolibri returns such a reply as a successful result and leaves
 * the check to the caller; here it is an exception so it cannot be mistaken for OK. The packet is
 * kept in [packet].
 */
class NotFoundException(val packet: TransportPacket) :
    TransportException("not found (opcode ${packet.opcode}, seq ${packet.seq})")
