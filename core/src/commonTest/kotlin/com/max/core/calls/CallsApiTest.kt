package com.max.core.calls

import com.max.core.api.MalformedReplyException
import com.max.core.api.MaxApi
import com.max.core.auth.RequestSink
import com.max.core.protocol.CmdType
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CallsApiTest {

    private class FakeSink(vararg replies: Any?) : RequestSink {
        val script = ArrayDeque(replies.toList())
        val sent = ArrayList<Pair<Opcode, Any?>>()
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
            sent += opcode to payload
            val next = if (script.isEmpty()) emptyMap<String, Any?>() else script.removeFirst()
            if (next is Throwable) throw next
            return TransportPacket(
                PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false),
                next,
            )
        }
    }

    @Test
    fun requestCallsTokenSendsEmptyMapAndParsesReply() = runTest {
        val reply = mapOf(
            "token" to "\$call-token",
            "token_lifetime_ts" to 1782490727456L,
            "token_refresh_ts" to 1782369767456L,
        )
        val sink = FakeSink(reply)
        val token = MaxApi(sink).calls.requestCallsToken()
        assertEquals(Opcode.OK_TOKEN, sink.sent[0].first)
        assertEquals(158, Opcode.OK_TOKEN.value)
        assertEquals(emptyMap<String, Any?>(), sink.sent[0].second)
        assertEquals(
            "80",
            DefaultMessagePackCodec.encode(sink.sent[0].second).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            },
        )
        assertEquals("\$call-token", token.token)
        assertEquals(1782490727456L, token.tokenLifetimeTs)
        assertEquals(1782369767456L, token.tokenRefreshTs)
        assertEquals(reply, token.raw)
    }

    @Test
    fun requestCallsTokenAllowsMissingLifetimeFields() = runTest {
        val token = CallsApi(FakeSink(mapOf("token" to "t", "extra" to 1))).requestCallsToken()
        assertEquals("t", token.token)
        assertNull(token.tokenLifetimeTs)
        assertNull(token.tokenRefreshTs)
        assertEquals(1, token.raw["extra"])
    }

    @Test
    fun requestCallsTokenRejectsMalformedAndPropagatesServerError() = runTest {
        assertFailsWith<MalformedReplyException> { CallsApi(FakeSink(null)).requestCallsToken() }
        assertFailsWith<MalformedReplyException> { CallsApi(FakeSink(mapOf("x" to 1))).requestCallsToken() }
        assertFailsWith<MalformedReplyException> { CallsApi(FakeSink(mapOf("token" to ""))).requestCallsToken() }
        val err = ServerErrorException.from(
            TransportPacket(
                PacketHeader(PROTOCOL_VERSION, CmdType.ERROR.value, 1, Opcode.OK_TOKEN.value.toShort(), 0, false),
                mapOf("error" to "error.auth", "message" to "denied"),
            ),
        )
        assertSame(err, assertFailsWith<ServerErrorException> { CallsApi(FakeSink(err)).requestCallsToken() })
    }

    private fun call(id: Long, sender: Long, time: Long, attach: Map<String, Any?>?, chatId: Long? = null): Map<String, Any?> = buildMap {
        chatId?.let { put("chatId", it) }
        put("message", mapOf("id" to id, "sender" to sender, "time" to time, "attaches" to listOfNotNull(attach)))
    }

    @Test
    fun historySendsEmptyMapAndParsesCallAttaches() = runTest {
        val me = 10L
        val sink = FakeSink(
            mapOf(
                "history" to listOf(
                    // outgoing answered video call to 20
                    call(1, me, 1000, mapOf("_type" to "CALL", "contactIds" to listOf(20), "duration" to 65000, "hangupType" to "HUNGUP", "callType" to "VIDEO"), chatId = 7),
                    // incoming missed audio call from 30
                    call(2, 30, 900, mapOf("_type" to "CALL", "hangupType" to "MISSED", "callType" to "AUDIO")),
                    // not a call: skipped
                    call(3, 30, 800, mapOf("_type" to "PHOTO")),
                    mapOf("message" to null),
                ),
            ),
        )
        val calls = CallsApi(sink).history()
        assertEquals<List<Pair<Opcode, Any?>>>(listOf(Opcode.VIDEO_CHAT_HISTORY to emptyMap<String, Any?>()), sink.sent)
        assertEquals(listOf(1L, 2L), calls.map { it.messageId })
        val out = calls[0]
        assertEquals(7L, out.chatId)
        assertEquals(20L, out.peerId(me))
        assertEquals(true, out.isVideo)
        assertEquals(false, out.isMissed(me))
        assertEquals(65000L, out.duration)
        val missed = calls[1]
        assertNull(missed.chatId)
        assertEquals(30L, missed.peerId(me))
        assertEquals(true, missed.isMissed(me))
        assertEquals(false, missed.isVideo)
    }

    @Test
    fun historyWithoutListIsEmptyAndRejectsNonMap() = runTest {
        assertEquals(emptyList<CallLogEntry>(), CallsApi(FakeSink(mapOf("x" to 1))).history())
        assertFailsWith<MalformedReplyException> { CallsApi(FakeSink(null)).history() }
    }
}
