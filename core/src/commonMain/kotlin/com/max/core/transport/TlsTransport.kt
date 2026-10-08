package com.max.core.transport

import com.max.core.protocol.Opcode
import com.max.core.protocol.PacketHeader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Persistent TLS socket to the Max server with request/response multiplexing by `seq`
 * (docs/protocol.md §B.6–B.8, §C.1, §C.3, §C.4). The implementation is [MaxTransport].
 *
 * - [connect] opens TCP (optionally through a proxy), upgrades to TLS and starts the reader and
 *   the ping loop;
 * - [request] sends a `cmd = 0` packet with the next `seq` and awaits the reply with the same
 *   `seq` (`cmd` OK / NOT_FOUND / ERROR);
 * - [pushes] carries every other incoming packet (server pushes, `cmd = 0`);
 * - [receive] exposes the raw decrypted byte chunks as they are read from the socket;
 * - [state] follows [ConnectionState]; with [TransportConfig.autoReconnect] the transport
 *   reconnects on its own after a drop.
 */
interface TlsTransport {
    /** Current connection state. */
    val state: StateFlow<ConnectionState>

    /**
     * Server pushes (every packet that is not a reply to a request). Hot flow with a 256-packet
     * buffer; a slow collector loses the oldest packets (like kolibri's broadcast channel).
     */
    val pushes: SharedFlow<TransportPacket>

    /**
     * Connects with [config] (or the config the transport was created with / last used). Returns
     * once the TLS session is up (and the `onConnected` hook of [MaxTransport] has run). No-op if
     * already connected.
     *
     * @throws ConnectTimeoutException if TCP + proxy + TLS take longer than
     *   [TransportConfig.connectTimeout].
     * @throws TransportException (or a platform I/O exception) if the connection fails.
     */
    suspend fun connect(config: TransportConfig? = null)

    /** Writes raw, already framed bytes to the socket. */
    suspend fun send(bytes: ByteArray)

    /**
     * Raw byte chunks as read from the TLS socket (before reassembly). Hot flow with a 256-chunk
     * buffer; chunks read while nobody collects are dropped.
     */
    fun receive(): Flow<ByteArray>

    /**
     * Sends [opcode] with a MessagePack-encoded [payload] and awaits the reply.
     *
     * @return the reply for `cmd = OK`.
     * @throws ServerErrorException for `cmd = ERROR`.
     * @throws NotFoundException for `cmd = NOT_FOUND` (kolibri reading of `cmd = 2`).
     * @throws RequestTimeoutException after [TransportConfig.requestTimeout].
     * @throws ConnectionClosedException if not connected or the connection drops while waiting.
     */
    suspend fun request(opcode: Opcode, payload: Any?): TransportPacket

    /** Closes the connection, stops reconnecting and fails pending requests. */
    suspend fun close()
}

/**
 * Transport settings. [host] is also the TLS server name (SNI and certificate check).
 *
 * @property proxyUrl `http://`, `socks5://` or `socks5h://[user:pass@]host:port`, see
 *   [ProxyConfig.parse].
 * @property insecureTls accept any server certificate and skip the host name check. Debug only.
 * @property trustMincifryCa trust the Russian Trusted Root/Sub CA ([MincifryCa]) in addition to
 *   the system roots. On by default: Max endpoints chain to these CAs, which are missing from
 *   most system stores (kolibri has the same switch, off by default; PyMax always adds the root).
 * @property pingInterval keepalive period, 29 s like the Android app (`c4e`); the first PING goes
 *   out right after connect (after the `onConnected` handshake / `LOGIN`), the next ones every
 *   interval. [Duration.INFINITE] disables the ping loop.
 * @property autoReconnect reconnect after a drop with the 2/4/8/15 s backoff ([reconnectDelay]).
 * @property redirectDomains hosts a server `RECONNECT` (opcode 3) may send the transport to: these
 *   domains and their subdomains ([ServerRedirect]). Empty: redirects to another host are ignored.
 */
data class TransportConfig(
    val host: String,
    val port: Int = 443,
    val proxyUrl: String? = null,
    val insecureTls: Boolean = false,
    val trustMincifryCa: Boolean = true,
    val connectTimeout: Duration = 15.seconds,
    val requestTimeout: Duration = 30.seconds,
    val pingInterval: Duration = 29.seconds,
    val pingInteractive: Boolean = true,
    val autoReconnect: Boolean = true,
    val redirectDomains: Set<String> = ServerRedirect.DEFAULT_DOMAINS,
)

/**
 * Transport-level connection state (kolibri `SessionState` without `Online`, which belongs to the
 * session layer after the opcode 6 handshake).
 */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
}

/** One decoded incoming packet: its header and the MessagePack-decoded body (`null` if empty). */
data class TransportPacket(
    val header: PacketHeader,
    val payload: Any?,
) {
    val cmd: Int get() = header.cmdValue
    val seq: Int get() = header.seq
    val opcode: Int get() = header.opcodeValue
}
