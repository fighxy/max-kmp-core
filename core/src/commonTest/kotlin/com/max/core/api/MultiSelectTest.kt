package com.max.core.api

import com.max.core.protocol.Opcode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Multi-select: one `MSG_DELETE` for the selection, one `MSG_SEND` forward per message. */
class MultiSelectTest {
    private fun sent(id: Long, chatId: Long) =
        mapOf("chatId" to chatId, "message" to mapOf("id" to id, "time" to id * 10, "type" to "USER", "text" to ""))

    @Test
    fun deleteSendsTheWholeSelectionOnce() = runTest {
        val sink = ScriptSink()
        MessagesApi(sink).deleteMessages(100, listOf(3, 1, 2), forMe = false)
        assertEquals(listOf(Opcode.MSG_DELETE), sink.opcodes)
        val payload = sink.sent[0].second as Map<*, *>
        assertEquals(listOf("chatId", "messageIds", "forMe"), payload.keys.toList())
        assertEquals(mapOf("chatId" to 100L, "messageIds" to listOf(3L, 1L, 2L), "forMe" to false), payload)
    }

    @Test
    fun deleteAddsTheItemTypeOnlyWhenAsked() = runTest {
        val sink = ScriptSink()
        val api = MessagesApi(sink)
        api.deleteMessages(100, listOf(5), forMe = true, itemType = HistoryItemType.DELAYED)
        val payload = sink.sent[0].second as Map<*, *>
        assertEquals(listOf("chatId", "messageIds", "forMe", "itemType"), payload.keys.toList())
        assertEquals("DELAYED", payload["itemType"])
        assertEquals(
            mapOf("chatId" to 1L, "messageIds" to listOf(2L), "forMe" to true, "itemType" to "REGULAR"),
            api.deletePayload(1, listOf(2), true, HistoryItemType.REGULAR),
        )
    }

    @Test
    fun forwardSendsOneFramePerMessageInOrder() = runTest {
        val sink = ScriptSink(sent(901, 200), sent(902, 200), sent(903, 200))
        val batch = MessagesApi(sink, clock = { 1_000L }).forwardMessages(200, 100, listOf(11, 12, 13))
        assertTrue(batch.complete)
        assertNull(batch.error)
        assertEquals(listOf(901L, 902L, 903L), batch.sent.map { it.id })
        assertEquals(List(3) { Opcode.MSG_SEND }, sink.opcodes)
        val links = sink.sent.map { ((it.second as Map<*, *>)["message"] as Map<*, *>)["link"] as Map<*, *> }
        assertEquals(listOf("11", "12", "13"), links.map { it["messageId"] })
        assertTrue(links.all { it["type"] == "FORWARD" && it["chatId"] == 100L })
        // every frame has its own negative cid
        val cids = sink.sent.map { (((it.second as Map<*, *>)["message"] as Map<*, *>)["cid"] as Long) }
        assertEquals(3, cids.toSet().size)
        assertTrue(cids.all { it < 0 })
    }

    @Test
    fun forwardStopsAtTheFirstFailure() = runTest {
        val error = serverError(Opcode.MSG_SEND, "message.not.found")
        val sink = ScriptSink(sent(901, 200), error, sent(903, 200))
        val batch = MessagesApi(sink).forwardMessages(200, 100, listOf(11, 12, 13))
        assertEquals(1, batch.failedIndex)
        assertSame(error, batch.error)
        assertEquals(listOf(901L), batch.sent.map { it.id })
        // the third message was not tried
        assertEquals(2, sink.sent.size)
    }

    @Test
    fun forwardPassesCancellationOnAndRejectsAnEmptySelection() = runTest {
        val sink = ScriptSink(CancellationException("stop"))
        assertFailsWith<CancellationException> { MessagesApi(sink).forwardMessages(200, 100, listOf(1)) }
        assertFailsWith<IllegalArgumentException> { MessagesApi(ScriptSink()).forwardMessages(200, 100, emptyList()) }
        assertFailsWith<IllegalArgumentException> { MessagesApi(ScriptSink()).deleteMessages(200, emptyList()) }
    }
}
