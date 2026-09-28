package com.max.core.protocol

/**
 * MessagePack codec for packet bodies (the bytes after the 10-byte header, once decompressed).
 *
 * The concrete library is not chosen yet. Candidates:
 * - kotlinx-serialization-msgpack (a kotlinx.serialization format; pure Kotlin, multiplatform);
 * - msgpack-kotlin (a Kotlin MessagePack port);
 * - rmp / rmpv (Rust), used through the native core instead of a Kotlin implementation.
 *
 * Requirements from docs/protocol.md §F.3: binary values, ext types (PyMax unwraps ext code 1)
 * and integer map keys must survive a round trip.
 */
interface MessagePackCodec {
    fun encode(value: Any): ByteArray
    fun decode(bytes: ByteArray): Any
}

/** Placeholder until a MessagePack library is chosen; every call throws. */
class UnimplementedMessagePackCodec : MessagePackCodec {
    override fun encode(value: Any): ByteArray =
        throw UnsupportedOperationException("TODO: choose MessagePack library")

    override fun decode(bytes: ByteArray): Any =
        throw UnsupportedOperationException("TODO: choose MessagePack library")
}
