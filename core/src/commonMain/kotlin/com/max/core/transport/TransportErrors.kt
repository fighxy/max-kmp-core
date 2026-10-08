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
 *
 * The error body is `{error, message, localizedMessage, title?, description?}` (Android app
 * `n81.j`); every key is optional and all codes are strings. The server's own texts for the user
 * are kept as they came ([localizedText], [title], [description]); [displayText] picks the one the
 * Android app shows.
 *
 * @property errorKey the `error` code, e.g. `login.token`, `attachment.not.ready`.
 * @property rawMessage the `message` value (a technical text, sometimes a code like `FAIL_LOGIN_TOKEN`).
 * @property localizedText the `localizedMessage` value: a ready text for the user. Not named
 *   `localizedMessage` because `Throwable` already has a member of that name.
 * @property title the `title` value (a short heading for the user), when the server sent one.
 * @property description the `description` value (longer text under [title]), when sent.
 */
class ServerErrorException(
    message: String,
    val errorKey: String?,
    val rawMessage: String?,
    val packet: TransportPacket,
    val localizedText: String? = null,
    val title: String? = null,
    val description: String? = null,
) : TransportException(message) {
    /**
     * The login token is no longer valid: `error` `login.token` (Android app), or
     * `FAIL_LOGIN_TOKEN` / `FAIL_LOGOUT_ALL` in `error` or `message` (kolibri / PyMax spelling,
     * kept as a fallback). `login.blocked` and `login.flood` are login rejections too but are
     * not reported here; see `com.max.core.auth.LoginRejection`.
     */
    val isSessionExpired: Boolean
        get() = errorKey == "login.token" || errorKey in LEGACY_EXPIRED || rawMessage in LEGACY_EXPIRED

    /**
     * The text the Android app shows for this error (`y00` / `y9m.d`): [title], else
     * [localizedText]; `null` when the server sent neither (the app then falls back to its own
     * generic text).
     */
    val displayText: String? get() = title ?: localizedText

    companion object {
        private val LEGACY_EXPIRED = setOf("FAIL_LOGIN_TOKEN", "FAIL_LOGOUT_ALL")

        /** Builds the exception from an ERROR reply. */
        fun from(packet: TransportPacket): ServerErrorException {
            val map = packet.payload as? Map<*, *>
            fun str(key: String): String? = (map?.get(key) as? String)
            fun text(key: String): String? = str(key)?.trim()?.takeIf { it.isNotEmpty() }
            val message = listOf("localizedMessage", "message", "title")
                .firstNotNullOfOrNull { key -> text(key) }
                ?: "unknown error"
            return ServerErrorException(
                message, str("error"), str("message"), packet,
                localizedText = text("localizedMessage"),
                title = text("title"),
                description = text("description"),
            )
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
