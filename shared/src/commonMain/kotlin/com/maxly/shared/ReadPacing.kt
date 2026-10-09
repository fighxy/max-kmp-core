package com.maxly.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Joins identical reads that are already in flight: the second caller with the same key waits
 * for the first one's result (or failure) instead of sending the same request again. The key
 * names the request and its arguments (`"history:42:0:50"`). A finished call is not cached: the
 * next call after it goes to the server again.
 *
 * When the caller that runs the request is cancelled, a waiting caller runs it itself.
 */
class SingleFlight {
    private val lock = Mutex()
    private val inflight = HashMap<String, CompletableDeferred<Any?>>()

    /** Calls in flight now (for tests). */
    suspend fun size(): Int = lock.withLock { inflight.size }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> share(key: String, block: suspend () -> T): T {
        while (true) {
            val (deferred, leader) = lock.withLock {
                val existing = inflight[key]
                if (existing != null) {
                    existing to false
                } else {
                    val created = CompletableDeferred<Any?>()
                    inflight[key] = created
                    created to true
                }
            }
            if (!leader) {
                try {
                    return deferred.await() as T
                } catch (e: LeaderCancelled) {
                    continue
                }
            }
            val outcome = try {
                Result.success(block())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            withContext(NonCancellable) {
                lock.withLock { if (inflight[key] === deferred) inflight.remove(key) }
            }
            outcome.fold(
                onSuccess = { deferred.complete(it) },
                onFailure = { deferred.completeExceptionally(if (it is CancellationException) LeaderCancelled() else it) },
            )
            return outcome.getOrThrow()
        }
    }

    private class LeaderCancelled : Exception("the shared call was cancelled")
}

/**
 * Orders the reads of one session so that a burst of background reads does not spend the
 * server's request budget ahead of what the user is waiting for.
 *
 * - [background] reads (shared media pages, reactions, comment counters, the call log) run one
 *   at a time, at least [spacingMs] apart, and wait while a [priority] read is in flight.
 * - [priority] reads (comments, the open chat's history) go at once.
 *
 * [now] is a monotonic clock in milliseconds.
 */
class ReadPacer(
    private val spacingMs: Long = 350,
    private val now: () -> Long = monotonicMillis(),
) {
    private val gate = Mutex()
    private var lastFinished: Long? = null
    private val urgent = MutableStateFlow(0)

    suspend fun <T> background(block: suspend () -> T): T = gate.withLock {
        urgent.first { it == 0 }
        lastFinished?.let { last ->
            val wait = last + spacingMs - now()
            if (wait > 0) delay(wait)
        }
        urgent.first { it == 0 }
        try {
            block()
        } finally {
            lastFinished = now()
        }
    }

    suspend fun <T> priority(block: suspend () -> T): T {
        urgent.update { it + 1 }
        try {
            return block()
        } finally {
            urgent.update { it - 1 }
        }
    }
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
