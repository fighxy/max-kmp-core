package com.max.core.session

import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.MessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.transport.ConnectionClosedException
import com.max.core.transport.ConnectionFactory
import com.max.core.transport.ConnectionState
import com.max.core.transport.MaxTransport
import com.max.core.transport.TransportPacket
import com.max.core.transport.defaultConnectionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Session states. Transitions:
 * - `Disconnected | Closed | Failed` --connect()--> [Connecting] --TLS up--> [Handshaking]
 *   --opcode 6 OK (+ afterHandshake)--> [Online];
 * - [Connecting] / [Handshaking] on the first connect --error, server error, timeout--> with
 *   auto-reconnect [Reconnecting] (the error goes to the caller, the transport keeps retrying with
 *   backoff, as kolibri's supervisor does), without it or for a [FatalSessionError] [Failed];
 * - [Online] --drop, auto-reconnect on--> [Reconnecting] --transport up--> [Handshaking] --> [Online]
 *   (a failed reconnect attempt goes back to [Reconnecting] and is retried with backoff);
 * - [Online] --drop, auto-reconnect off--> [Failed];
 * - any state --disconnect()--> [Closed].
 *
 * kolibri has `Disconnected / Connecting / Connected / Online`; here `Connected` is the explicit
 * [Handshaking] step, and a drop while online shows [Reconnecting] instead of `Disconnected`.
 */
sealed interface SessionState {
    /** Never connected. */
    data object Disconnected : SessionState

    /** First connect: TCP (+ proxy) + TLS in progress. */
    data object Connecting : SessionState

    /** Transport is up, the `SESSION_INIT` (opcode 6) request is in flight. */
    data object Handshaking : SessionState

    /** Handshake done (and the optional `afterHandshake` hook, e.g. token login, succeeded). */
    data class Online(val handshake: HandshakeInfo) : SessionState

    /**
     * The connection dropped (or the first connect failed) and the transport is reconnecting
     * with backoff (3 s, doubling up to 96 s, ±10% jitter). [attempt] counts drops and failed attempts since the last
     * Online; [lastError] is the last connect or handshake failure, if any.
     */
    data class Reconnecting(val attempt: Int, val lastError: Throwable? = null) : SessionState

    /** Stopped by [SessionMachine.disconnect]. */
    data object Closed : SessionState

    /**
     * Stopped by an error. With auto-reconnect only a [FatalSessionError] gets here; without it:
     * connect / TLS failure, `ConnectTimeoutException`, the handshake
     * rejected by the server ([com.max.core.transport.ServerErrorException] /
     * [com.max.core.transport.NotFoundException]), a handshake `RequestTimeoutException`, a failing
     * `afterHandshake` hook (on the first connect, or a [FatalSessionError] such as a revoked
     * login token on any connect), or a drop with auto-reconnect disabled
     * ([ConnectionClosedException]).
     */
    data class Failed(val cause: Throwable) : SessionState
}

/**
 * Marker for errors after which retrying cannot help (e.g. a revoked login token thrown by the
 * `afterHandshake` hook). Thrown during a reconnect, it ends the session in
 * [SessionState.Failed] instead of another [SessionState.Reconnecting] round.
 */
interface FatalSessionError

/** [SessionMachine.connect] was interrupted by [SessionMachine.disconnect]. */
class SessionClosedException(message: String = "session closed") : ConnectionClosedException(message)

/**
 * Session layer over [MaxTransport]: connect, `SESSION_INIT` (opcode 6) handshake, Online, and a
 * fresh handshake after every transport reconnect. Mirrors kolibri `session/manager.rs` (`Session`,
 * `supervise`, `connect_and_handshake`); ping and reconnect backoff are done by [MaxTransport].
 *
 * The handshake runs as the transport's `onConnected` hook, so it is the first request on every
 * connection, before any other traffic and before the transport reports `Connected`.
 *
 * Authentication lives in `com.max.core.auth`. PyMax goes on from the handshake to the
 * stored-token `LOGIN` (opcode 19, with a device fingerprint derived from `callsSeed`); kolibri's
 * session stops at the handshake. [afterHandshake] is the hook for such a step (see
 * `com.max.core.auth.TokenLogin.hook`): it runs after every successful
 * handshake (first connect and each reconnect) with the transport and the reply, and the state
 * becomes [SessionState.Online] only once it returns. If it throws, the attempt fails like a
 * rejected handshake.
 *
 * Differences from kolibri: a failed first [connect] ends in [SessionState.Failed] and is not
 * retried in the background (kolibri reports the error and keeps retrying); a drop with
 * auto-reconnect disabled ends in [SessionState.Failed] rather than `Disconnected`.
 *
 * Thread safety: every state change goes through one lock and is tagged with a generation number,
 * so a late result of an attempt that [disconnect] (or a newer [connect]) has superseded is
 * dropped instead of overwriting the newer state.
 */
class SessionMachine(
    val config: SessionConfig,
    connectionFactory: ConnectionFactory = defaultConnectionFactory(),
    codec: MessagePackCodec = DefaultMessagePackCodec,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val afterHandshake: (suspend (transport: MaxTransport, handshake: HandshakeInfo) -> Unit)? = null,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)

    /** Current state; see [SessionState]. */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** The underlying transport (one instance for the whole life of the machine). */
    val transport: MaxTransport = MaxTransport(
        config = config.transport,
        factory = connectionFactory,
        codec = codec,
        scope = scope,
        onConnected = { onTransportConnected(it) },
    )

    /** Server pushes of every connection (see [MaxTransport.pushes]). */
    val pushes: SharedFlow<TransportPacket> get() = transport.pushes

    /** Lossless pushes of every connection (see [MaxTransport.reliablePushes]). */
    val reliablePushes: Flow<TransportPacket> get() = transport.reliablePushes()

    /** Guards [_state] writes, [generation], [attempt], [connectJob], [watchJob], [reconnectAttempts]. */
    private val lock = Mutex()

    /**
     * Serializes [disconnect] (held for its whole duration, including closing the transport) with
     * the start of a [connect] attempt, so a connect right after a disconnect cannot be torn down
     * by the tail of that disconnect. Never taken by the handshake hook.
     */
    private val opLock = Mutex()
    private var generation = 0
    private var attempt: CompletableDeferred<HandshakeInfo>? = null
    private var connectJob: Job? = null
    private var watchJob: Job? = null

    /**
     * Connects and handshakes; returns the handshake reply once [SessionState.Online].
     *
     * Idempotent: when already Online it returns the current [HandshakeInfo]; while an attempt is
     * in progress it waits for that attempt; while [SessionState.Reconnecting] it waits for the
     * next Online (or the end of the session). From Disconnected, Closed or Failed it starts a
     * new attempt. Cancelling the caller does not stop the attempt; use [disconnect] for that.
     *
     * @throws SessionClosedException if [disconnect] interrupts the attempt.
     * @throws Throwable the cause of [SessionState.Failed] (transport error, server error,
     *   timeout, hook failure).
     */
    suspend fun connect(): HandshakeInfo {
        val pending: CompletableDeferred<HandshakeInfo> = opLock.withLock {
            lock.withLock {
                when (val s = _state.value) {
                    is SessionState.Online -> return s.handshake
                    else -> attempt?.takeIf { it.isActive }
                        // a reconnect in progress: wait for it instead of starting over
                        ?: if (s is SessionState.Reconnecting || s == SessionState.Handshaking) null else startAttempt()
                }
            }
        } ?: return awaitReconnected()
        return pending.await()
    }

    /** Must hold [lock]. */
    private fun startAttempt(): CompletableDeferred<HandshakeInfo> {
        val gen = ++generation
        watchJob?.cancel()
        watchJob = null
        val result = CompletableDeferred<HandshakeInfo>()
        attempt = result
        _state.value = SessionState.Connecting
        connectJob = scope.launch { runAttempt(gen, result) }
        return result
    }

    private suspend fun runAttempt(gen: Int, result: CompletableDeferred<HandshakeInfo>) {
        try {
            transport.connect()
        } catch (e: CancellationException) {
            result.completeExceptionally(SessionClosedException())
            throw e
        } catch (e: Throwable) {
            // kolibri: with auto-reconnect a failed first attempt is reported and retried in the
            // background (the transport keeps its reconnect loop); a fatal error still fails
            val outcome = lock.withLock {
                when {
                    gen != generation -> null
                    config.transport.autoReconnect && e !is FatalSessionError -> {
                        reconnectAttempts = 0
                        _state.value = SessionState.Reconnecting(1, e)
                        watchJob = scope.launch { watchTransport(gen) }
                        true
                    }
                    else -> {
                        _state.value = SessionState.Failed(e)
                        false
                    }
                }
            }
            if (outcome == false) withContext(NonCancellable) { transport.close() }
            result.completeExceptionally(if (outcome != null) e else SessionClosedException())
            return
        }
        val info = lock.withLock {
            val s = _state.value
            if (gen != generation || s !is SessionState.Online) {
                null
            } else {
                watchJob = scope.launch { watchTransport(gen) }
                s.handshake
            }
        }
        if (info == null) {
            result.completeExceptionally(SessionClosedException())
        } else {
            result.complete(info)
        }
    }

    /** Transport `onConnected` hook: runs the handshake on every (re)connection. */
    private suspend fun onTransportConnected(t: MaxTransport) {
        val gen = lock.withLock {
            when (_state.value) {
                SessionState.Closed, is SessionState.Failed, SessionState.Disconnected ->
                    throw SessionClosedException()
                else -> _state.value = SessionState.Handshaking
            }
            generation
        }
        try {
            val reply = t.request(Opcode.SESSION_INIT, HandshakePayload.build(config.device, random))
            val info = HandshakeInfo.from(reply.payload)
            afterHandshake?.invoke(t, info)
            lock.withLock {
                if (gen != generation || _state.value != SessionState.Handshaking) throw SessionClosedException()
                _state.value = SessionState.Online(info)
            }
        } catch (e: Throwable) {
            // during a reconnect the transport drops the connection and retries after the next delay,
            // unless the error is fatal: then the session fails and the transport is closed
            val closeTransport = lock.withLock {
                val s = _state.value
                val reconnecting = gen == generation && s == SessionState.Handshaking && watchJob?.isActive == true
                when {
                    !reconnecting -> false
                    e is FatalSessionError -> {
                        _state.value = SessionState.Failed(e)
                        true
                    }
                    else -> {
                        _state.value = SessionState.Reconnecting(reconnectAttempts, e)
                        false
                    }
                }
            }
            // not inline: this hook runs inside the transport's reconnect loop, which close() joins
            if (closeTransport) scope.launch { transport.close() }
            throw e
        }
    }

    private var reconnectAttempts = 0

    /** After the first Online: maps transport drops to Reconnecting / Failed. */
    private suspend fun watchTransport(gen: Int) {
        transport.state.collect { ts ->
            if (ts != ConnectionState.Disconnected) return@collect
            val stop = lock.withLock {
                if (gen != generation) return@collect
                when (val s = _state.value) {
                    SessionState.Closed, is SessionState.Failed -> return@collect
                    is SessionState.Online -> reconnectAttempts = 1
                    else -> reconnectAttempts++
                }
                if (config.transport.autoReconnect) {
                    val lastError = (_state.value as? SessionState.Reconnecting)?.lastError
                    _state.value = SessionState.Reconnecting(reconnectAttempts, lastError)
                    false
                } else {
                    _state.value = SessionState.Failed(ConnectionClosedException("connection lost"))
                    true
                }
            }
            if (stop) {
                transport.close()
                throw CancellationException("session failed")
            }
        }
    }

    private suspend fun awaitReconnected(): HandshakeInfo {
        val s = state.first {
            it is SessionState.Online || it is SessionState.Closed || it is SessionState.Failed ||
                it == SessionState.Disconnected
        }
        return when (s) {
            is SessionState.Online -> s.handshake
            is SessionState.Failed -> throw s.cause
            else -> throw SessionClosedException()
        }
    }

    /**
     * Stops the session from any state: aborts a connect / handshake in progress, closes the
     * connection, stops reconnecting, and moves to [SessionState.Closed]. A pending [connect]
     * fails with [SessionClosedException]. Safe to call repeatedly; [connect] may be called again
     * afterwards.
     */
    suspend fun disconnect(): Unit = opLock.withLock {
        val (job, watcher, pending) = lock.withLock {
            generation++
            _state.value = SessionState.Closed
            val snapshot = Triple(connectJob, watchJob, attempt)
            connectJob = null
            watchJob = null
            attempt = null
            snapshot
        }
        pending?.completeExceptionally(SessionClosedException())
        withContext(NonCancellable) {
            job?.cancelAndJoin()
            watcher?.cancelAndJoin()
            transport.close()
        }
    }

    /** [MaxTransport.request] on the current connection. */
    suspend fun request(opcode: Opcode, payload: Any?): TransportPacket = transport.request(opcode, payload)

    /**
     * [MaxTransport.sendRequest] on the current connection: writes [opcode] with [payload] and
     * returns its `seq` without waiting for the reply (which is dropped when it comes).
     *
     * @throws com.max.core.transport.ConnectionClosedException when not connected.
     */
    suspend fun sendWithoutReply(opcode: Opcode, payload: Any?): Int = transport.sendRequest(opcode.value, payload)
}
