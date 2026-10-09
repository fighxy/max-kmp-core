package com.maxly.core.transport

import com.maxly.core.protocol.HEADER_SIZE
import com.maxly.core.protocol.packetTotalLength

/**
 * Cuts the TLS byte stream into whole packets using the length in the 10-byte header
 * ([packetTotalLength]). Chunks may end in the middle of a packet or contain several packets; the
 * incomplete tail stays buffered (kolibri `PacketReceiver`).
 *
 * Not thread-safe: one reader feeds it.
 */
class PacketReassembler(private val maxBufferSize: Int = MAX_BUFFER_SIZE) {
    private var buffer = ByteArray(0)
    private var size = 0

    /** Number of buffered bytes that do not form a complete packet yet. */
    val bufferedBytes: Int get() = size

    /**
     * Appends [chunk] and returns every complete packet (header + body) now available.
     *
     * @throws IllegalStateException if the buffer would exceed [maxBufferSize]; the buffer is
     *   cleared (kolibri treats this as a fatal stream error).
     */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<ByteArray> {
        if (size + length > maxBufferSize) {
            val overflow = size + length
            reset()
            throw IllegalStateException("packet buffer overflow ($overflow B > $maxBufferSize B)")
        }
        if (size + length > buffer.size) {
            buffer = buffer.copyOf(maxOf(size + length, buffer.size * 2))
        }
        chunk.copyInto(buffer, size, offset, offset + length)
        size += length

        val packets = ArrayList<ByteArray>()
        var consumed = 0
        while (true) {
            // packetTotalLength looks at buffer.size, which may include unused capacity
            if (size - consumed < HEADER_SIZE) break
            val total = packetTotalLength(buffer, consumed) ?: break
            if (size - consumed < total) break
            packets += buffer.copyOfRange(consumed, consumed + total)
            consumed += total
        }
        if (consumed > 0) {
            buffer.copyInto(buffer, 0, consumed, size)
            size -= consumed
        }
        return packets
    }

    /** Drops any buffered bytes (after a reconnect). */
    fun reset() {
        buffer = ByteArray(0)
        size = 0
    }

    companion object {
        /** kolibri `MAX_BUFFER_SIZE`: 16 MiB. */
        const val MAX_BUFFER_SIZE: Int = 16 * 1024 * 1024
    }
}
