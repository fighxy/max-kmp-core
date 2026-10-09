package com.maxly.core.state

import com.maxly.core.api.MaxApi
import com.maxly.core.api.MaxMessage
import com.maxly.core.api.ScriptSink
import com.maxly.core.events.EventParser
import com.maxly.core.events.MaxEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `updateTime`, the edit time of a message: parsing in every source and the store on edits. */
class MessageEditTimeTest {
    private fun msg(id: Long, time: Long, updateTime: Any? = null, status: String? = null, text: String = "m$id") =
        linkedMapOf<String, Any?>("id" to id, "sender" to 20L, "time" to time, "type" to "USER", "text" to text).also {
            if (updateTime != null) it["updateTime"] = updateTime
            if (status != null) it["status"] = status
        }

    private fun push(opcode: Int, chatId: Long, message: Map<String, Any?>) =
        EventParser.parse(opcode, 0, mapOf("chatId" to chatId, "message" to message))

    @Test
    fun updateTimeIsParsedOnlyWhenSet() {
        assertEquals(1_700_000_000_500L, MaxMessage.from(msg(1, 100, 1_700_000_000_500L))!!.updateTime)
        assertEquals(1_700_000_000_500L, MaxMessage.from(msg(1, 100, "1700000000500"))!!.updateTime)
        assertNull(MaxMessage.from(msg(1, 100))!!.updateTime)
        assertNull(MaxMessage.from(msg(1, 100, 0))!!.updateTime)
        assertNull(MaxMessage.from(msg(1, 100, "never"))!!.updateTime)
        // the envelope of pushes and MSG_SEND replies
        assertEquals(555L, MaxMessage.from(mapOf("chatId" to 7L, "message" to msg(1, 100, 555)))!!.updateTime)
        // a copy keeps it
        assertEquals(555L, MaxMessage.from(msg(1, 100, 555))!!.copy(text = "x").updateTime)
    }

    @Test
    fun historyMsgGetAndEditRepliesCarryIt() = runTest {
        val sink = ScriptSink(
            mapOf("messages" to listOf(msg(1, 100, 150), msg(2, 200))),
            mapOf("messages" to listOf(msg(1, 100, 150))),
            mapOf("message" to msg(1, 100, 300, status = "EDITED", text = "new")),
        )
        val api = MaxApi(sink).messages
        assertEquals(listOf(150L, null), api.getChatHistory(7).messages.map { it.updateTime })
        assertEquals(150L, api.getMessages(7, listOf(1)).single().updateTime)
        assertEquals(300L, api.editMessage(7, 1, "new").updateTime)
    }

    @Test
    fun pushesCarryItAndTheStoreFollowsEdits() {
        val store = MaxStore()
        store.putMessages(7, listOf(MaxMessage.from(msg(1, 100), 7)!!))
        assertNull(store.state.value.messagesOf(7).single().updateTime)

        val first = assertIs<MaxEvent.MessageEdited>(push(128, 7, msg(1, 100, 150, status = "EDITED", text = "v2")))
        assertEquals(150L, first.message.updateTime)
        store.apply(first)
        assertEquals(150L, store.state.value.messagesOf(7).single().updateTime)

        // a later edit moves it on
        store.apply(push(128, 7, msg(1, 100, 260, status = "EDITED", text = "v3")))
        assertEquals(260L, store.state.value.messagesOf(7).single().updateTime)
        assertEquals("v3", store.state.value.messagesOf(7).single().text)

        // an edit push without the field keeps the known edit time
        store.apply(push(67, 7, msg(1, 100, status = "EDITED", text = "v4")))
        assertEquals(260L, store.state.value.messagesOf(7).single().updateTime)
        assertEquals("v4", store.state.value.messagesOf(7).single().text)

        // a new message push with the field (e.g. a replayed edited message) keeps it too
        val incoming = assertIs<MaxEvent.NewMessage>(push(128, 7, msg(2, 200, 210)))
        store.apply(incoming)
        assertEquals(210L, store.state.value.messagesOf(7).last().updateTime)
    }
}
