package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.CmdType
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportPacket

/** Records requests; answers with scripted payloads (a Throwable is thrown), `{}` when empty. */
internal class ScriptSink(vararg replies: Any?) : RequestSink {
    val script = ArrayDeque(replies.toList())
    val sent = ArrayList<Pair<Opcode, Any?>>()
    override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
        sent += opcode to payload
        val next = if (script.isEmpty()) emptyMap<String, Any?>() else script.removeFirst()
        if (next is Throwable) throw next
        return TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false), next)
    }

    val opcodes: List<Opcode> get() = sent.map { it.first }

    /** msgpack hex of the [i]-th request payload. */
    fun hex(i: Int): String = msgpackHex(sent[i].second)
}

internal fun msgpackHex(payload: Any?): String =
    DefaultMessagePackCodec.encode(payload).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun serverError(opcode: Opcode, error: String, message: String = error) = ServerErrorException.from(
    TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.ERROR.value, 1, opcode.value.toShort(), 0, false), mapOf("error" to error, "message" to message)),
)
