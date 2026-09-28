package com.max.core.transport

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Delay before reconnect attempt number [attempt] (0-based):
 * `(2 * 2^min(attempt, 3)).coerceIn(2, 15)` seconds = 2, 4, 8, 15, 15, ... (kolibri
 * `reconnect_delay`, docs/protocol.md §C.4). The attempt counter restarts at 0 after every
 * successful connection.
 */
fun reconnectDelay(attempt: Int): Duration {
    val shift = attempt.coerceIn(0, 3)
    return (2L shl shift).coerceIn(2L, 15L).seconds
}
