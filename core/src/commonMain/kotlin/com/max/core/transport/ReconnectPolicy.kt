package com.max.core.transport

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Delay before reconnect attempt number [attempt] (0-based), like the Android app's
 * `ConnectionBackoff` (`yr4` in the release client, `os0.a` for the delay): `base * 2^attempt`,
 * capped at [RECONNECT_MAX_DELAY], then a jitter of ±[RECONNECT_JITTER] (uniform, the app's
 * `random(-0.1, 0.1)`). With the app's release parameters [RECONNECT_BASE_DELAY] is 3 s and [RECONNECT_MAX_DELAY] is
 * 96 s, so attempt 0 is about 3 s and the delay stops growing at attempt 5 (96 s).
 *
 * The attempt counter restarts at 0 after every successful connection (the app's
 * `onConnectionSuccessful` resets its counter too).
 *
 * A server `RECONNECT` (opcode 3) does not use this: the transport reconnects at once.
 *
 * @param random the jitter source; tests pass a fixed one.
 */
fun reconnectDelay(attempt: Int, random: Random = Random.Default): Duration {
    val exponent = attempt.coerceAtLeast(0).coerceAtMost(RECONNECT_MAX_EXPONENT)
    var delay = RECONNECT_BASE_DELAY.inWholeMilliseconds
    repeat(exponent) { delay = (delay * 2).coerceAtMost(RECONNECT_MAX_DELAY.inWholeMilliseconds) }
    delay = delay.coerceAtMost(RECONNECT_MAX_DELAY.inWholeMilliseconds)
    val jitter = delay * RECONNECT_JITTER * (2.0 * random.nextDouble() - 1.0)
    return (delay + jitter).toLong().coerceAtLeast(0).milliseconds
}

/** `ConnectionBackoff` base delay in the release client (3 s). */
val RECONNECT_BASE_DELAY: Duration = 3_000.milliseconds

/** `ConnectionBackoff` cap in the release client (96 s). */
val RECONNECT_MAX_DELAY: Duration = 96_000.milliseconds

/** `os0.a`: the random factor is `1 + random(-0.1, 0.1)`. */
const val RECONNECT_JITTER: Double = 0.1

/** 96 s is reached at attempt 5 (`3 * 2^5`). */
private const val RECONNECT_MAX_EXPONENT: Int = 5
