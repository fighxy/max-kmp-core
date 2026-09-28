package com.max.shared

import com.max.core.session.HandshakeInfo
import kotlinx.coroutines.flow.Flow

/**
 * Low-level cross-platform session: raw opcodes with MessagePack bodies. [MaxClient] implements
 * it; prefer the typed APIs of [MaxClient] (`api`, `media`, `auth`, `store`) where they exist.
 *
 * - [connect] — TLS + handshake (+ `LOGIN` when a token is stored) → Online
 * - [request] — send [opcode] with a MessagePack [payload] (empty = no body), await the OK reply
 *   body (MessagePack; empty when none). ERROR replies throw `ServerErrorException`.
 * - [pushes] — every server push
 */
interface Session {
    suspend fun connect(): SessionInfo
    suspend fun request(opcode: Int, payload: ByteArray): ByteArray
    val pushes: Flow<Push>
    suspend fun close()
}

/** Handshake reply of the current connection. [raw] is the whole reply map. */
data class SessionInfo(val callsSeed: Long?, val deviceName: String?, val raw: Map<*, *>) {
    companion object {
        fun from(h: HandshakeInfo): SessionInfo = SessionInfo(h.callsSeed, h.deviceName, h.payload ?: emptyMap<Any?, Any?>())
    }
}

/** A server push: [opcode], header [cmd], MessagePack [payload] (empty when none). */
class Push(val opcode: Int, val cmd: Int, val payload: ByteArray)

/** A [MaxClient] with the default settings for [host]; see [MaxClient] for the full API. */
fun openSession(host: String, port: Int = 443): MaxClient = MaxClient(MaxClientConfig(host = host, port = port))
