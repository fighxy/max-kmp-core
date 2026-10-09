package com.maxly.core.transport

import com.maxly.core.protocol.MAX_SEQ
import kotlinx.coroutines.CompletableDeferred

/**
 * `seq` generator: pre-increment, wrapping at 2^16, so the first value is 1 and 65535 is followed
 * by **0**. This is exactly kolibri's `fetch_add(1).wrapping_add(1)` on an `AtomicU16` starting at 0
 * (docs/protocol.md §B.7). Like kolibri it does not skip values that are still pending; a reused
 * `seq` replaces the old waiter, which fails (see [PendingRequests.register]).
 *
 * Not thread-safe; [MaxTransport] guards it with a mutex. A new counter (restart at 1) is used for
 * every connection, as kolibri creates a new `Client` per connection.
 */
class SeqCounter(start: Int = 0) {
    private var last: Int = start and MAX_SEQ

    /** The last value handed out (0 before the first call). */
    val current: Int get() = last

    fun next(): Int {
        last = (last + 1) and MAX_SEQ
        return last
    }

    fun reset() {
        last = 0
    }
}

/**
 * Waiters for replies, keyed by `seq` (kolibri `Dispatcher.pending`). Not thread-safe; guarded by
 * [MaxTransport]'s mutex.
 */
class PendingRequests {
    private val waiters = HashMap<Int, CompletableDeferred<TransportPacket>>()

    val size: Int get() = waiters.size

    operator fun contains(seq: Int): Boolean = seq in waiters

    /**
     * Registers a waiter for [seq]. If [seq] is still pending (the counter wrapped around), the old
     * waiter fails with [ConnectionClosedException], as in kolibri.
     */
    fun register(seq: Int): CompletableDeferred<TransportPacket> {
        val deferred = CompletableDeferred<TransportPacket>()
        waiters.put(seq, deferred)?.completeExceptionally(
            ConnectionClosedException("seq $seq reused before its reply arrived"),
        )
        return deferred
    }

    /** Removes and returns the waiter for [seq], or `null` if nobody waits for it. */
    fun take(seq: Int): CompletableDeferred<TransportPacket>? = waiters.remove(seq)

    /** Removes the waiter for [seq] only if it is [deferred] (after a timeout / cancellation). */
    fun remove(seq: Int, deferred: CompletableDeferred<TransportPacket>) {
        if (waiters[seq] === deferred) waiters.remove(seq)
    }

    /** Fails every waiter with [cause] (on disconnect). */
    fun failAll(cause: Throwable) {
        val all = waiters.values.toList()
        waiters.clear()
        all.forEach { it.completeExceptionally(cause) }
    }
}
