package com.maxly.core.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * In-memory [RawConnection] for tests: data written by the transport goes to [outbound], data put
 * into [inbound] is returned by [read]. Closing either side ends the stream.
 */
class FakeRawConnection : RawConnection {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val readLock = Mutex()
    private var leftover = ByteArray(0)
    private var closed = false

    /** Every chunk the transport has written, in order. */
    val written: Channel<ByteArray> get() = outbound

    /** Feeds [bytes] to the next [read] (as if they arrived from the peer). */
    suspend fun feed(bytes: ByteArray) {
        if (closed) error("connection closed")
        inbound.send(bytes)
    }

    /** Takes the next chunk written by the transport, or `null` after [timeout]. */
    suspend fun takeWritten(timeout: Duration = 5.seconds): ByteArray? =
        withTimeoutOrNull(timeout) { outbound.receive() }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = readLock.withLock {
        if (closed && leftover.isEmpty()) return -1
        while (leftover.isEmpty()) {
            val next = try {
                inbound.receive()
            } catch (e: ClosedReceiveChannelException) {
                return -1
            }
            leftover = next
        }
        val n = minOf(length, leftover.size)
        leftover.copyInto(buffer, offset, 0, n)
        leftover = leftover.copyOfRange(n, leftover.size)
        return n
    }

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw ConnectionClosedException("write on closed connection")
        outbound.send(bytes.copyOf())
    }

    override suspend fun close() {
        closed = true
        inbound.close()
        outbound.close()
    }
}

/**
 * [ConnectionFactory] that hands out a prepared [FakeRawConnection] (or builds one through
 * [builder]) and records the last open arguments for assertions.
 */
class ScriptedConnectionFactory(
    private val builder: () -> FakeRawConnection = { FakeRawConnection() },
) : ConnectionFactory {
    var lastHost: String? = null
        private set
    var lastPort: Int? = null
        private set
    var lastTls: TlsOptions? = null
        private set
    var lastProxy: ProxyConfig? = null
        private set
    var openCount: Int = 0
        private set

    /** The connection handed out by the last [open]. */
    var lastConnection: FakeRawConnection? = null
        private set

    override suspend fun open(host: String, port: Int, tls: TlsOptions, proxy: ProxyConfig?): RawConnection {
        lastHost = host
        lastPort = port
        lastTls = tls
        lastProxy = proxy
        openCount++
        return builder().also { lastConnection = it }
    }
}
