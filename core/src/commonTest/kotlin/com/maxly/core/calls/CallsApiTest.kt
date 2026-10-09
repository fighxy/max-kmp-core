package com.maxly.core.calls

import com.maxly.core.api.MalformedReplyException
import com.maxly.core.api.MaxApi
import com.maxly.core.auth.RequestSink
import com.maxly.core.protocol.CmdType
import com.maxly.core.protocol.DefaultMessagePackCodec
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.PROTOCOL_VERSION
import com.maxly.core.protocol.PacketHeader
import com.maxly.core.transport.ServerErrorException
import com.maxly.core.transport.TransportPacket
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

    @Test
    fun initiateCallSendsKometPayloadAndReadsCallerEndpoint() = runTest {
        val endpoint = """{"endpoint":"wss://call","id":{"internal":9,"external":"20"}}"""
        val sink = FakeSink(mapOf("conversationId" to "conv-1", "internalCallerParams" to endpoint))
        val call = CallsApi(sink) { "conv-1" }.initiateCall(20, isVideo = true, deviceId = "dev")
        assertEquals(Opcode.VIDEO_CHAT_START_ACTIVE, sink.sent.single().first)
        assertEquals(
            mapOf(
                "conversationId" to "conv-1",
                "calleeIds" to listOf(20L),
                "internalParams" to CallsApi.internalParams("dev"),
                "isVideo" to true,
            ),
            sink.sent.single().second,
        )
        assertEquals(
            """{"platform":"ANDROID","sdkVersion":"0.2.1.3","clientAppKey":"CGPGAGLGDIHBABABA","deviceId":"dev","protocolVersion":5,"onlyAdminCanRecord":false,"isWaitForAdminEnabled":false,"hexCapability":"3c02f"}""",
            CallsApi.internalParams("dev"),
        )
        assertEquals("conv-1", call.conversationId)
        assertEquals("wss://call", call.endpoint)
        assertEquals(9L, call.callsUserId)
        assertEquals(20L, call.peerExternalId)
        assertEquals(true, call.isVideo)
    }

    @Test
    fun createConferenceUsesReplyLinkOrAsksForOne() = runTest {
        val ready = FakeSink(mapOf("conversationId" to "c", "joinLink" to "join/abc", "callName" to "  ", "chatId" to 4L))
        val created = CallsApi(ready) { "c" }.createConference()
        assertEquals(Opcode.VIDEO_CHAT_START, ready.sent.single().first)
        assertEquals(mapOf<String, Any?>("conversationId" to "c"), ready.sent.single().second)
        assertEquals("join/abc", created.joinLink)
        assertNull(created.callName)
        assertEquals(4L, created.chatId)

        val asked = FakeSink(mapOf("conversationId" to "c"), mapOf("joinLink" to "join/next"))
        val second = CallsApi(asked) { "c" }.createConference()
        assertEquals(listOf(Opcode.VIDEO_CHAT_START, Opcode.VIDEO_CHAT_CREATE_JOIN_LINK), asked.sent.map { it.first })
        assertEquals("join/next", second.joinLink)
        assertFailsWith<MalformedReplyException> { CallsApi(FakeSink(mapOf("conversationId" to "c"), emptyMap<String, Any?>())) { "c" }.createConference() }
    }

    @Test
    fun joinByLinkReadsEitherParamsField() = runTest {
        val endpoint = """{"endpoint":"wss://join","id":{"internal":3}}"""
        val sink = FakeSink(mapOf("conversationId" to "j", "internalParams" to endpoint))
        val call = CallsApi(sink).joinByLink("token", deviceId = "")
        assertEquals(Opcode.VIDEO_CHAT_JOIN_BY_LINK, sink.sent.single().first)
        assertEquals(
            mapOf("joinLink" to "token", "internalParams" to CallsApi.internalParams(""), "isVideo" to false),
            sink.sent.single().second,
        )
        assertEquals("wss://join", call.endpoint)
        assertEquals(3L, call.callsUserId)
        assertEquals(0L, call.peerExternalId)
        assertFailsWith<MalformedReplyException> { CallsApi(FakeSink(emptyMap<String, Any?>())).joinByLink("token") }
        assertFailsWith<IllegalArgumentException> { CallsApi(FakeSink()).joinByLink("") }
    }

    @Test
    fun joinByLinkSendsTheTokenOfAShareLink() = runTest {
        val endpoint = """{"endpoint":"wss://join","id":{"internal":3}}"""
        val sink = FakeSink(mapOf("internalCallerParams" to endpoint))
        CallsApi(sink).joinByLink(" https://max.ru/joincall/AbC_1-x ", isVideo = true)
        assertEquals("AbC_1-x", (sink.sent.single().second as Map<*, *>)["joinLink"])
        assertEquals(true, (sink.sent.single().second as Map<*, *>)["isVideo"])
    }

    @Test
    fun deleteHistorySendsMessageIdsAndSkipsAnEmptyList() = runTest {
        val sink = FakeSink()
        val api = CallsApi(sink)
        api.deleteHistory(emptyList())
        assertEquals(0, sink.sent.size)
        api.deleteHistory(listOf(11L, 12L))
        assertEquals(Opcode.VIDEO_CHAT_DELETE_HISTORY, sink.sent.single().first)
        assertEquals(164, Opcode.VIDEO_CHAT_DELETE_HISTORY.value)
        assertEquals(mapOf("historyIds" to listOf(11L, 12L)), sink.sent.single().second)
    }

    @Test
    fun linkInfoAsksWithTheJoincallPath() = runTest {
        val sink = FakeSink(
            mapOf("videoConference" to mapOf("conferenceId" to 77L, "callName" to " Планёрка ", "participantsCount" to 3, "callType" to "VIDEO")),
        )
        val info = CallsApi(sink).linkInfo("https://web.max.ru/joincall/tok")
        assertEquals(Opcode.LINK_INFO, sink.sent.single().first)
        assertEquals(mapOf("link" to "joincall/tok"), sink.sent.single().second)
        assertEquals(CallLinkInfo("tok", "77", "Планёрка", 3, true), info)
        assertNull(CallsApi(FakeSink(emptyMap<String, Any?>())).linkInfo("tok"))
        val none = FakeSink()
        assertNull(CallsApi(none).linkInfo("https://example.com/x y"))
        assertEquals(0, none.sent.size)
    }

    @Test
    fun inboundCallsAndCallMembersKeepTheirReferenceCodes() {
        // 103 and 195 have no CallsApi method: no reference builds them (docs/opcodes.md).
        // The codes and names still match kolibri and PyMax, so logs show the right name.
        assertEquals(Opcode.GET_INBOUND_CALLS, Opcode.fromValue(103))
        assertEquals(Opcode.VIDEO_CHAT_MEMBERS, Opcode.fromValue(195))
        assertEquals("GET_INBOUND_CALLS", Opcode.nameOf(103))
        assertEquals("VIDEO_CHAT_MEMBERS", Opcode.nameOf(195))
    }

    @Test
    fun callLinksNormalizeToTheToken() {
        assertEquals("abc", CallLink.token("https://max.ru/joincall/abc"))
        assertEquals("abc", CallLink.token("HTTP://web.MAX.ru/joincall/abc?x=1"))
        assertEquals("abc", CallLink.token("joincall/abc"))
        assertEquals("a_b-C9", CallLink.token(" a_b-C9 "))
        assertNull(CallLink.token("https://max.ru/join/abc"))
        assertNull(CallLink.token(""))
        assertEquals("https://max.ru/joincall/abc", CallLink.url("abc"))
        assertEquals("joincall/abc", CallLink.path("abc"))
    }
}
