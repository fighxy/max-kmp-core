package com.maxly.core.calls

import com.maxly.core.auth.RequestSink
import com.maxly.core.events.EventParser
import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.CmdType
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.PROTOCOL_VERSION
import com.maxly.core.protocol.PacketHeader
import com.maxly.core.transport.TransportPacket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallLogTest {
    @Test
    fun rejectSendsTheAppFieldsAndReadsError() = runTest {
        val sink = FakeSink(mapOf("error" to "call.not.found"))
        val error = CallsApi(sink).rejectIncomingCall("conv-1", peerId = null)
        val payload = sink.sent.single().second as Map<*, *>
        assertEquals(Opcode.VIDEO_CHAT_HANGUP, sink.sent.single().first)
        assertEquals("conv-1", payload["conversationId"])
        assertEquals("REJECTED", payload["reason"])
        assertEquals("", payload["internalParams"])
        assertNull(payload["peerId"])
        assertEquals("call.not.found", error)

        val ok = FakeSink(emptyMap<String, Any?>())
        assertNull(CallsApi(ok).rejectIncomingCall("conv-2", peerId = "peer", reason = CallHangupReason.BUSY))
        val sent = ok.sent.single().second as Map<*, *>
        assertEquals("BUSY", sent["reason"])
        assertEquals("peer", sent["peerId"])
        assertFailsWith<IllegalArgumentException> { CallsApi(FakeSink()).rejectIncomingCall("") }
    }

    @Test
    fun syncedLogKeepsOpcode79() = runTest {
        val item = mapOf(
            "historyId" to 4L,
            "callId" to "c1",
            "callName" to "Ann",
            "callerId" to 8L,
            "chatId" to 0,
            "callType" to "VIDEO",
            "hangupType" to "MISSED",
            "time" to 90L,
            "groupCallType" to 1,
            "durationMs" to 0,
        )
        val sink = FakeSink(mapOf("callHistoryItems" to listOf(item, mapOf("callId" to "x")), "callHistorySync" to 15L, "reset" to true))
        val page = CallsApi(sink).callHistory(0)
        assertEquals(Opcode.CALL_HISTORY, sink.sent.single().first)
        assertEquals(mapOf("callHistorySync" to 0L), sink.sent.single().second)
        assertTrue(page.reset)
        assertEquals(15L, page.sync)
        val row = page.items.single()
        assertEquals(CallMedia.VIDEO, row.callType)
        assertEquals(CallEnd.MISSED, row.hangupType)
        assertEquals(GroupCallKind.CHAT, row.groupCallType)
        assertEquals(0L, row.durationMs)
        assertNull(row.messageId)

        val old = FakeSink(mapOf("history" to emptyList<Any?>()))
        assertEquals(emptyList(), CallsApi(old).history())
        assertEquals(Opcode.VIDEO_CHAT_HISTORY, old.sent.single().first)
        assertEquals(emptyMap<String, Any?>(), old.sent.single().second)
    }

    @Test
    fun callHistoryPush() {
        val event = EventParser.parse(
            Opcode.NOTIF_CALL_HISTORY.value,
            0,
            mapOf(
                "callHistorySync" to 20L,
                "prevCallHistorySync" to 15L,
                "action" to "REMOVE",
                "historyIds" to listOf(4L),
            ),
        )
        val changed = assertIs<MaxEvent.CallHistoryChanged>(event)
        assertEquals(CallHistoryAction.REMOVE, changed.action)
        assertEquals(listOf(4L), changed.historyIds)
        assertEquals(20L, changed.sync)
        assertEquals(15L, changed.prevSync)
        assertIs<MaxEvent.Unknown>(EventParser.parse(Opcode.NOTIF_CALL_HISTORY.value, 0, emptyMap<String, Any?>()))
    }

    private class FakeSink(vararg replies: Any?) : RequestSink {
        val script = ArrayDeque(replies.toList())
        val sent = ArrayList<Pair<Opcode, Any?>>()
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
            sent += opcode to payload
            val next = if (script.isEmpty()) emptyMap<String, Any?>() else script.removeFirst()
            return TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false), next)
        }
    }
}
