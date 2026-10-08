package com.max.core.transport

import com.max.core.protocol.CmdType
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.MessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.decodeHeader
import com.max.core.protocol.decodePayloadPacket
import com.max.core.protocol.CompressionFormat
import com.max.core.protocol.encodePacketCompressed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * [TlsTransport] over a [RawConnection] from [factory]. All protocol logic is common code; the
 * platform part is only the factory (see [defaultConnectionFactory]).
 *
 * Behaviour follows kolibri `transport/client.rs`, `dispatcher.rs` and `session/manager.rs`:
 * - `seq`: [SeqCounter], 1, 2, ... 65535, 0, 1, ..., restarting at 1 on every connection;
 * - replies (`cmd` 1 OK, 2 NOT_FOUND, 3 ERROR) resolve the waiter with the same `seq`; replies
 *   nobody waits for are dropped; everything else goes to [pushes];
 * - requests time out after [TransportConfig.requestTimeout], counted from before the write (wait
 *   for the write lock, the write, the reply); a write still running at the deadline closes the
 *   connection so a blocked socket cannot hold the caller or later writers, and so does a timeout
 *   during which not a byte came from the server (a dead socket, as after iOS suspended the app).
 *   The connect (TCP + proxy + TLS) times out after [TransportConfig.connectTimeout];
 * - PING (opcode 1, `{"interactive": <pingInteractive>}`) every [TransportConfig.pingInterval],
 *   first one after one interval, fire-and-forget (the reply is dropped); the flag starts as
 *   [TransportConfig.pingInteractive] and [setPingInteractive] switches it on a live connection;
 * - on a drop every pending request fails with [ConnectionClosedException]; with
 *   [TransportConfig.autoReconnect] the transport reconnects after [reconnectDelay] (2, 4, 8,
 *   15, 15 s ...), resetting the attempt counter after each successful connection.
 *
 * - like kolibri's supervisor, a failed first [connect] is thrown to the caller and, with
 *   [TransportConfig.autoReconnect], retried in the background on the same schedule (an app
 *   started without network comes online by itself once the network is back).
 *
 * Difference from kolibri: reconnect lives here and not in the session, so the session layer
 * re-runs the opcode 6 handshake through [onConnected] on every (re)connect.
 *
 * @param onConnected runs after every successful TLS connect, before [state] becomes
 *   [ConnectionState.Connected]; [request] already works inside it (use it for the handshake). If
 *   it throws, the connection is dropped (and, on reconnect, retried).
 * @param scope where the reader, ping and reconnect coroutines run. Tests pass a `TestScope`'s
 *   `backgroundScope` to get virtual time.
 */
class MaxTransport(
    config: TransportConfig,
    private val factory: ConnectionFactory = defaultConnectionFactory(),
    private val codec: MessagePackCodec = DefaultMessagePackCodec,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onConnected: (suspend (MaxTransport) -> Unit)? = null,
) : TlsTransport {

    /** Current configuration; replaced by [connect] with a non-null argument. */
    var config: TransportConfig = config
        private set

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _pushes = MutableSharedFlow<TransportPacket>(
        extraBufferCapacity = PUSH_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val pushes: SharedFlow<TransportPacket> = _pushes.asSharedFlow()

    /** Queues of the [reliablePushes] collectors (copy-on-write). */
    private val reliableQueues = MutableStateFlow<List<Channel<TransportPacket>>>(emptyList())

    /**
     * Every push, without loss: each collector gets its own unbounded queue, registered when the
     * collection starts and removed when it ends, and sees every push that arrives in between,
     * across reconnects. Unlike [pushes] nothing is dropped for a slow collector, so collect it
     * with fast, non-blocking code (the router applies events to its store here and hands them
     * to user handlers through its own queue). The socket reader never waits for a collector.
     */
    fun reliablePushes(): Flow<TransportPacket> = flow {
        val queue = Channel<TransportPacket>(Channel.UNLIMITED)
        reliableQueues.update { it + queue }
        try {
            for (packet in queue) emit(packet)
        } finally {
            reliableQueues.update { list -> list.filterNot { it === queue } }
            queue.close()
        }
    }

    private val rawChunks = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = PUSH_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Serializes connect / close. */
    private val lifecycleLock = Mutex()

    /** Guards [seq], [pending], [connection], [readerJob], [pingJob]. */
    private val stateLock = Mutex()

    /** Serializes socket writes so packets are not interleaved. */
    private val writeLock = Mutex()

    private val seq = SeqCounter()
    private val pending = PendingRequests()
    private var connection: RawConnection? = null

    /** Chunks read from the server so far (any connection): a request that heard none timed out on a dead socket. */
    @kotlin.concurrent.Volatile
    private var inboundChunks = 0L
    private var readerJob: Job? = null
    private var pingJob: Job? = null

    /**
     * The `interactive` flag of every PING: `true` while the user is looking at the app (the MAX web
     * client sends "not idle"), `false` in the background. Starts as [TransportConfig.pingInteractive].
     */
    @kotlin.concurrent.Volatile
    var pingInteractive: Boolean = config.pingInteractive
        private set

    /**
     * Checks every outgoing request right before it is encoded ([OutboundGuard]); `null` sends
     * everything as is. A blocked request throws [OutboundBlockedException] without writing.
     */
    @kotlin.concurrent.Volatile
    var outboundGuard: OutboundGuard? = null

    /** [payload] after [outboundGuard]; throws [OutboundBlockedException] for a blocked request. */
    private fun guarded(opcode: Int, payload: Any?): Any? = when (val d = outboundGuard?.check(opcode, payload) ?: OutboundDecision.Pass) {
        OutboundDecision.Pass -> payload
        is OutboundDecision.Rewrite -> d.payload
        is OutboundDecision.Block -> throw OutboundBlockedException(opcode, d.reason)
    }
    private var supervisorJob: Job? = null

    override fun receive(): Flow<ByteArray> = rawChunks.asSharedFlow()

    override suspend fun connect(config: TransportConfig?) {
        lifecycleLock.withLock {
            if (config != null) this.config = config
            if (supervisorJob?.isActive == true) return
            val reader = try {
                establish()
            } catch (e: Throwable) {
                // kolibri `supervise`: the first error goes to the caller, the retries go on
                if (this.config.autoReconnect && (e !is CancellationException || e is TimeoutCancellationException)) {
                    supervisorJob = scope.launch { supervise(null) }
                }
                throw e
            }
            supervisorJob = scope.launch { supervise(reader) }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        val conn = stateLock.withLock { connection } ?: throw ConnectionClosedException("not connected")
        writeWithin(conn, bytes, -1, -1)
    }

    override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket =
        request(opcode.value, payload)

    /** [request] for a raw opcode value (codes missing from [Opcode]). */
    suspend fun request(opcode: Int, payload: Any?): TransportPacket {
        val reply = requestRaw(opcode, payload)
        return when (reply.header.cmd) {
            CmdType.OK.value -> reply
            CmdType.ERROR.value -> throw ServerErrorException.from(reply)
            else -> throw NotFoundException(reply)
        }
    }

    /**
     * Like [request], but returns the reply for any `cmd` (OK, NOT_FOUND or ERROR) instead of
     * mapping ERROR / NOT_FOUND to exceptions (kolibri `request_raw`). Only a lost connection, a
     * timeout or [outboundGuard] ([OutboundBlockedException], nothing written) throws.
     */
    suspend fun requestRaw(opcode: Int, payload: Any?): TransportPacket {
        val body = encodeBody(guarded(opcode, payload))
        val (conn, seqValue, waiter) = stateLock.withLock {
            val conn = connection ?: throw ConnectionClosedException("not connected")
            val s = seq.next()
            Triple(conn, s, pending.register(s))
        }
        val heard = inboundChunks
        try {
            // one deadline for the write lock, the write itself and the reply
            return withTimeout(config.requestTimeout) {
                timeoutWins {
                    write(conn, encodeRequest(seqValue, opcode, body))
                    waiter.await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Not a byte from the server for the whole timeout (no reply, no push, no ping reply):
            // the socket is dead without knowing it, as after iOS suspended the app. Closing it lets
            // the reader end and the supervisor reconnect; otherwise every next request on it would
            // wait out its own timeout too.
            if (inboundChunks == heard) withContext(NonCancellable) { closeIfCurrent(conn) }
            throw RequestTimeoutException(
                opcode, seqValue,
                "no reply to ${Opcode.nameOf(opcode)} (seq $seqValue) within ${config.requestTimeout}",
            )
        } finally {
            withContext(NonCancellable) { stateLock.withLock { pending.remove(seqValue, waiter) } }
        }
    }

    /**
     * Runs [block] inside `withTimeout`: if it fails because the deadline closed the connection
     * (the write then throws a connection error), report the timeout instead of that error.
     */
    private suspend inline fun <T> CoroutineScope.timeoutWins(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (e !is CancellationException) ensureActive()
        throw e
    }

    /** Waiters still registered (tests). */
    internal suspend fun pendingCount(): Int = stateLock.withLock { pending.size }

    /**
     * Fire-and-forget request (typing, ping): sends [opcode] with the next `seq` and does not wait
     * for the reply. Returns the `seq`. Like [requestRaw] it passes [outboundGuard] first.
     */
    suspend fun sendRequest(opcode: Int, payload: Any?): Int {
        val body = encodeBody(guarded(opcode, payload))
        val (conn, seqValue) = stateLock.withLock {
            val conn = connection ?: throw ConnectionClosedException("not connected")
            conn to seq.next()
        }
        writeWithin(conn, encodeRequest(seqValue, opcode, body), opcode, seqValue)
        return seqValue
    }

    /**
     * Sets [pingInteractive] without sending anything: the value the next PINGs carry. For a
     * client that knows its state before the first connection (e.g. a saved ghost mode).
     */
    fun presetPingInteractive(interactive: Boolean) {
        pingInteractive = interactive
    }

    /**
     * Switches the PING `interactive` flag (kolibri `set_ping_interactive`): every later PING carries
     * [interactive], and when the flag changed and a connection is up one PING with it goes out at
     * once (fire-and-forget), so the server learns about it without waiting for the next tick.
     * Returns `true` when that PING was written. Never throws for a send error.
     */
    suspend fun setPingInteractive(interactive: Boolean): Boolean {
        val changed = pingInteractive != interactive
        pingInteractive = interactive
        if (!changed) return false
        return try {
            sendRequest(Opcode.PING.value, mapOf("interactive" to interactive))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    override suspend fun close() {
        lifecycleLock.withLock {
            val supervisor = supervisorJob
            supervisorJob = null
            // closing the socket first unblocks a platform read, then the jobs can finish
            dropConnection(ConnectionClosedException("transport closed"))
            supervisor?.cancelAndJoin()
            dropConnection(ConnectionClosedException("transport closed"))
            _state.value = ConnectionState.Disconnected
        }
    }

    // ── connection lifecycle ────────────────────────────────────────

    /** Opens one connection, starts reader + ping, runs [onConnected]. Returns the reader job. */
    private suspend fun establish(): Job {
        val cfg = config
        _state.value = ConnectionState.Connecting
        val conn = try {
            val proxy = cfg.proxyUrl?.let(ProxyConfig::parse)
            val tls = TlsOptions(cfg.insecureTls, cfg.trustMincifryCa, cfg.connectTimeout)
            withTimeout(cfg.connectTimeout) { factory.open(cfg.host, cfg.port, tls, proxy) }
        } catch (e: TimeoutCancellationException) {
            _state.value = ConnectionState.Disconnected
            throw ConnectTimeoutException("connect to ${cfg.host}:${cfg.port} timed out after ${cfg.connectTimeout}")
        } catch (e: Throwable) {
            _state.value = ConnectionState.Disconnected
            throw e
        }

        val reader = stateLock.withLock {
            connection = conn
            seq.reset()
            scope.launch { readLoop(conn) }.also { readerJob = it }
        }
        try {
            onConnected?.invoke(this)
        } catch (e: Throwable) {
            dropConnection(ConnectionClosedException("onConnected failed", e))
            _state.value = ConnectionState.Disconnected
            throw e
        }
        stateLock.withLock {
            if (connection === conn && reader.isActive) {
                pingJob = scope.launch { pingLoop(cfg.pingInterval) }
            }
        }
        if (reader.isActive) _state.value = ConnectionState.Connected
        return reader
    }

    /**
     * Waits for the connection to drop, then reconnects with backoff while allowed. Without
     * [firstReader] (the first connect failed) it starts with the reconnect attempts.
     */
    private suspend fun supervise(firstReader: Job?) {
        var reader = firstReader
        while (true) {
            if (reader != null) {
                reader.join()
                dropConnection(ConnectionClosedException("connection lost"))
                _state.value = ConnectionState.Disconnected
                if (!config.autoReconnect) return
            }
            var attempt = 0
            while (true) {
                delay(reconnectDelay(attempt))
                attempt++
                try {
                    reader = establish()
                    break
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) continue
                    throw e
                } catch (e: Throwable) {
                    // stay Disconnected and try again after the next delay
                }
            }
        }
    }

    /** Closes the current connection, stops reader/ping, fails pending requests. */
    /** Closes [conn] if it is still the live connection; the reader then sees the drop. */
    private suspend fun closeIfCurrent(conn: RawConnection) {
        if (stateLock.withLock { connection === conn }) runCatching { conn.close() }
    }

    private suspend fun dropConnection(cause: Throwable) {
        val (conn, reader, ping) = stateLock.withLock {
            val snapshot = Triple(connection, readerJob, pingJob)
            connection = null
            readerJob = null
            pingJob = null
            pending.failAll(cause)
            snapshot
        }
        ping?.cancel()
        runCatching { conn?.close() }
        reader?.cancel()
    }

    private suspend fun readLoop(conn: RawConnection) {
        val reassembler = PacketReassembler()
        val buffer = ByteArray(READ_CHUNK)
        try {
            while (true) {
                val n = conn.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) continue
                inboundChunks++
                rawChunks.tryEmit(buffer.copyOf(n))
                for (frame in reassembler.feed(buffer, 0, n)) dispatch(frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // I/O error or framing overflow: treat as a drop, like kolibri's reader task
        }
    }

    /** Routes one complete packet: a reply to its waiter, anything else to [pushes]. */
    private suspend fun dispatch(frame: ByteArray) {
        val header = try {
            decodeHeader(frame)
        } catch (e: IllegalArgumentException) {
            return
        }
        val decoded = runCatching { decodePayloadPacket(frame, codec) }
        if (isReply(header.cmd)) {
            val waiter = stateLock.withLock { pending.take(header.seq) } ?: return
            decoded.fold(
                onSuccess = { (h, payload) -> waiter.complete(TransportPacket(h, payload)) },
                onFailure = { waiter.completeExceptionally(TransportException("cannot decode reply seq ${header.seq}", it)) },
            )
        } else {
            // compressed pushes are decompressed by decodePayloadPacket (LZ4 block / LZ4 frame / Zstd);
            // undecodable ones (unknown flag, corrupt body, bad MessagePack) are skipped, like kolibri
            decoded.onSuccess { (h, payload) ->
                val packet = TransportPacket(h, payload)
                reliableQueues.value.forEach { it.trySend(packet) }
                _pushes.tryEmit(packet)
            }
        }
    }

    private suspend fun pingLoop(interval: Duration) {
        if (interval == Duration.INFINITE || !interval.isPositive()) return
        while (true) {
            delay(interval)
            try {
                sendRequest(Opcode.PING.value, mapOf("interactive" to pingInteractive))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutboundBlockedException) {
                // a guard may hold a tick back; the loop goes on
            } catch (e: Throwable) {
                return
            }
        }
    }

    /** [write] bounded by [TransportConfig.requestTimeout] (lock wait included). */
    private suspend fun writeWithin(conn: RawConnection, bytes: ByteArray, opcode: Int, seqValue: Int) {
        try {
            withTimeout(config.requestTimeout) { timeoutWins { write(conn, bytes) } }
        } catch (e: TimeoutCancellationException) {
            val what = if (opcode < 0) "raw bytes" else "${Opcode.nameOf(opcode)} (seq $seqValue)"
            throw RequestTimeoutException(opcode, seqValue, "could not write $what within ${config.requestTimeout}")
        }
    }

    /**
     * Writes under [writeLock]. The caller's deadline covers the wait for the lock and the write.
     * A platform write may block and ignore cancellation (a JVM `OutputStream.write` is not bounded
     * by the read timeout), so when the caller is cancelled (timeout) while the write is still
     * running, [conn] is closed: that unblocks the write, and a half-written frame would corrupt
     * the stream anyway. The reader then sees the drop and the transport reconnects as usual.
     */
    private suspend fun write(conn: RawConnection, bytes: ByteArray) {
        try {
            writeLock.withLock { abortableWrite(conn, bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: Throwable) {
            throw ConnectionClosedException("write failed: ${e.message}", e)
        }
    }

    private suspend fun abortableWrite(conn: RawConnection, bytes: ByteArray) = coroutineScope {
        var finished = false
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished) withContext(NonCancellable) { runCatching { conn.close() } }
            }
        }
        try {
            conn.write(bytes)
        } finally {
            finished = true
            guard.cancel()
        }
    }

    /**
     * Frames a request like kolibri: bodies of [com.max.core.protocol.COMPRESSION_THRESHOLD] bytes
     * or more go out as LZ4 block with the ratio-hint flag, unless compression would not shrink them.
     */
    private fun encodeRequest(seq: Int, opcode: Int, body: ByteArray): ByteArray =
        encodePacketCompressed(PROTOCOL_VERSION, CmdType.REQUEST.value, seq, opcode.toShort(), body, CompressionFormat.LZ4_BLOCK)

    /** Same body rule as [com.max.core.protocol.encodePayloadPacket]: `null` gives an empty body. */
    private fun encodeBody(payload: Any?): ByteArray = if (payload == null) ByteArray(0) else codec.encode(payload)

    companion object {
        /** kolibri `PUSH_CHANNEL_CAPACITY`. */
        const val PUSH_BUFFER_CAPACITY: Int = 256

        /** kolibri `READ_CHUNK`: 64 KiB. */
        const val READ_CHUNK: Int = 64 * 1024

        private fun isReply(cmd: Byte): Boolean =
            cmd == CmdType.OK.value || cmd == CmdType.NOT_FOUND.value || cmd == CmdType.ERROR.value
    }
}
