package ru.max.core.protocol

/**
 * MessagePack encode/decode for wire payloads.
 *
 * TODO: pick a KMP MessagePack library (or custom kotlinx-serialization format).
 * PyMax uses `msgpack`; kolibri uses `rmpv`.
 */
object MessagePackCodec {
    fun encode(value: Any?): ByteArray = error("TODO: MessagePackCodec.encode")
    fun decode(bytes: ByteArray): Any? = error("TODO: MessagePackCodec.decode")
}
