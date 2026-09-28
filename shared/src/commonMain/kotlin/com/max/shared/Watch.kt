package com.max.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Handle of a [watch] subscription (Swift / Java friendly: no coroutine types). */
class Watcher internal constructor(private val job: Job) {
    val isActive: Boolean get() = job.isActive
    fun cancel() = job.cancel()
}

/**
 * Collects [this] in [scope] and calls [onEach] for every value — a callback bridge for hosts
 * that cannot collect a `Flow` (Swift, Java). Errors of the flow go to [onError].
 */
fun <T> Flow<T>.watch(scope: CoroutineScope, onError: (Throwable) -> Unit = {}, onEach: (T) -> Unit): Watcher =
    Watcher(scope.launch { onEach { onEach(it) }.catch { onError(it) }.collect {} })
