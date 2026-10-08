package com.max.core.transport

import com.max.core.protocol.Opcode

/**
 * Looks at every request right before [MaxTransport] encodes and writes it (replies awaited or
 * not, the PING ticks included) and decides: send it as is, send a changed payload, or send
 * nothing. Runs on the caller's coroutine, so it must be quick and must not suspend or throw.
 *
 * Only requests (`cmd` 0) pass the guard. The transport's acknowledgement of a server PING
 * (`cmd` 1, the server's `seq`, empty body) is not a request and is written without it.
 */
fun interface OutboundGuard {
    fun check(opcode: Int, payload: Any?): OutboundDecision
}

/** What [OutboundGuard.check] decided for one request. */
sealed class OutboundDecision {
    /** Send the request unchanged. */
    object Pass : OutboundDecision()

    /** Send [payload] instead of the original one. */
    class Rewrite(val payload: Any?) : OutboundDecision()

    /** Send nothing; the caller gets [OutboundBlockedException] with [reason]. */
    class Block(val reason: String) : OutboundDecision()
}

/**
 * A request [OutboundGuard] stopped before it was written: no frame went out, nothing reached
 * the server. Not a connection failure.
 */
class OutboundBlockedException(val opcode: Int, val reason: String) :
    IllegalStateException("${Opcode.nameOf(opcode)} not sent: $reason")
