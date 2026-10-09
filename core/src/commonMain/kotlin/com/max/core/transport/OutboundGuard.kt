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
 * A request [OutboundGuard] or [RefusedOpcodes] stopped before it was written: no frame went out,
 * nothing reached the server. Not a connection failure.
 *
 * [errorKey] is set only for [RefusedOpcodes] (e.g. `pinned.unsupported`); `toMaxError()` then
 * reports `ErrorKind.SERVER` with that key. A guard block (ghost mode) has none.
 */
class OutboundBlockedException(val opcode: Int, val reason: String, val errorKey: String? = null) :
    IllegalStateException("${Opcode.nameOf(opcode)} not sent: $reason")

/**
 * Opcodes the core never sends, checked by [MaxTransport] for every request before the guard.
 *
 * 240 `GET_PINNED_MESSAGE_STATES` and 241 `PINNED_MESSAGES_GET`: the mobile server does not know
 * them; it answers an unknown-opcode error and drops the connection. The official app never
 * builds these requests (their bodies came from the web client). Pin state comes from the chat
 * and push 243; changes go through 242.
 */
object RefusedOpcodes {
    /** `error` key of a refused pins request (240, 241). */
    const val PINNED_UNSUPPORTED: String = "pinned.unsupported"

    private val keys: Map<Int, String> = mapOf(
        Opcode.GET_PINNED_MESSAGE_STATES.value to PINNED_UNSUPPORTED,
        Opcode.PINNED_MESSAGES_GET.value to PINNED_UNSUPPORTED,
    )

    /** Whether [opcode] is refused. */
    fun isRefused(opcode: Int): Boolean = opcode in keys

    /** The exception for a refused [opcode]; nothing is written. */
    fun exception(opcode: Int): OutboundBlockedException =
        OutboundBlockedException(opcode, "not supported by the mobile server", keys[opcode])

    /** Throws [exception] when [opcode] is refused. */
    fun check(opcode: Int) {
        if (isRefused(opcode)) throw exception(opcode)
    }
}
