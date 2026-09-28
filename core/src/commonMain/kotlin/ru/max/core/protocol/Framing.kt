package ru.max.core.protocol

/**
 * 10-byte big-endian wire header (Max / OneMe protocol), same layout as kolibri-net.
 *
 * ```
 * [0]      ver
 * [1]      cmd
 * [2..4]   seq
 * [4..6]   opcode
 * [6..10]  packedLen (high byte = compression flag)
 * [10..]   MessagePack payload
 * ```
 *
 * TODO: encode / decode implementation.
 */
object Framing {
    const val HEADER_SIZE: Int = 10
    const val PROTOCOL_VERSION: UByte = 10u

    fun encode(/* TODO */): ByteArray = error("TODO: Framing.encode")
    fun decode(/* TODO */): Packet = error("TODO: Framing.decode")
}

/** Decoded packet after decompression. */
data class Packet(
    val ver: UByte,
    val cmd: UByte,
    val seq: UShort,
    val opcode: UShort,
    val payload: ByteArray,
)
