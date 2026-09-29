package com.max.core.events

import com.max.core.state.MaxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Routes typed events to a [MaxStore] and to handlers, like PyMax's `Dispatcher`
 * (`src/pymax/dispatch/dispatcher.py`: `on_message`, `on_typing`, ..., `on_raw`, error handlers).
 *
 * One collector reads [events] in order; for each event it first applies it to [store] (so a
 * handler already sees the updated state), then calls every matching handler in registration
 * order. A handler that throws does not stop the loop: the error goes to [onError] handlers
 * (PyMax `ErrorScope`), or is dropped if there are none. Handlers run on the collector coroutine;
 * a slow handler delays the following events (launch work elsewhere if needed). A handler may call
 * [stop] (directly or through e.g. `MaxClient.logout`); see there.
 *
 * ```
 * val router = EventRouter(MaxEvents(session).all, store)
 * router.on<MaxEvent.NewMessage> { println(it.message.text) }
 * router.start(scope)   // before session.connect(), the push flow does not replay
 * ```
 */
class EventRouter(
    private val events: Flow<MaxEvent>,
    val store: MaxStore? = null,
) {
    /** A registered handler; [cancel] removes it. */
    inner class Subscription internal constructor(internal val type: KClass<out MaxEvent>, internal val block: suspend (MaxEvent) -> Unit) {
        fun cancel() {
            handlers.update { list -> list.filterNot { it === this } }
        }
    }

    // copy-on-write lists: registration may happen from any thread while the collector runs
    private val handlers = MutableStateFlow<List<Subscription>>(emptyList())
    private val errorHandlers = MutableStateFlow<List<(MaxEvent, Throwable) -> Unit>>(emptyList())
    private var job: Job? = null

    /** Registers [handler] for events of [type] (and its subtypes; `MaxEvent::class` = every event). */
    fun <T : MaxEvent> on(type: KClass<T>, handler: suspend (T) -> Unit): Subscription {
        @Suppress("UNCHECKED_CAST")
        val sub = Subscription(type) { handler(it as T) }
        handlers.update { it + sub }
        return sub
    }

    inline fun <reified T : MaxEvent> on(noinline handler: suspend (T) -> Unit): Subscription = on(T::class, handler)

    /** Called with the event and the error when a handler throws. */
    fun onError(handler: (MaxEvent, Throwable) -> Unit) {
        errorHandlers.update { it + handler }
    }

    /** `true` while the collector runs. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts collecting in [scope] (idempotent while running). The collector is subscribed when
     * this returns, so pushes that arrive afterwards are not missed.
     */
    fun start(scope: CoroutineScope): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch(Collector(this), start = CoroutineStart.UNDISPATCHED) { events.collect { dispatch(it) } }.also { job = it }
    }

    /**
     * Stops collecting and waits until an in-flight [dispatch] finishes. Handlers stay registered
     * and [start] may be called again.
     *
     * Called from a handler (or a coroutine the handler runs inside), the collector is only
     * cancelled, not joined: the caller is part of it and would otherwise wait for itself. The
     * caller keeps running until its next suspension point; code that must finish there (cleanup)
     * belongs in `withContext(NonCancellable)`.
     */
    suspend fun stop() {
        val running = job ?: return
        job = null
        if (currentCoroutineContext()[Collector]?.router === this) running.cancel() else running.cancelAndJoin()
    }

    /** Marks the collector coroutine (and everything it runs) of one router. */
    private class Collector(val router: EventRouter) : AbstractCoroutineContextElement(Collector) {
        companion object Key : CoroutineContext.Key<Collector>
    }

    /** Applies [event] to the store and runs the handlers (what the collector does per event). */
    suspend fun dispatch(event: MaxEvent) {
        store?.apply(event)
        for (sub in handlers.value) {
            if (!sub.type.isInstance(event)) continue
            // a handler may have stopped the router (e.g. logout): skip the rest
            currentCoroutineContext().ensureActive()
            try {
                sub.block(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorHandlers.value.forEach { h -> runCatching { h(event, e) } }
            }
        }
    }
}
