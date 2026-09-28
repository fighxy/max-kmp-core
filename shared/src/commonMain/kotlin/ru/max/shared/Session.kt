package ru.max.shared

import kotlinx.coroutines.flow.Flow

/**
 * Public cross-platform API.
 *
 * - [connect] — TLS + handshake → Online
 * - [request] — send opcode + MessagePack payload, await response
 * - [pushes] — stream of server pushes
 *
 * TODO: back with ru.max.core.session.SessionMachine.
 */
interface Session {
    suspend fun connect(): SessionInfo
    suspend fun request(opcode: UShort, payload: ByteArray): ByteArray
    val pushes: Flow<Push>
    suspend fun close()
}

data class SessionInfo(
    val raw: ByteArray = byteArrayOf(),
)

data class Push(
    val opcode: UShort,
    val payload: ByteArray,
)

/** Factory. TODO: real implementation. */
fun openSession(host: String, port: Int = 443): Session = error("TODO: openSession")
