package com.maxly.core.state

import com.maxly.core.api.Chat
import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals

/** `NOTIF_MARK` 130: the later (larger) read mark wins; only "mark as unread" moves it back. */
class ReadMarkOrderTest {
    private fun mark(chatId: Long, userId: Long, mark: Long, unread: Boolean = false) =
        MaxEvent.MessageRead(chatId, userId, mark, unread, Opcode.NOTIF_MARK.value, null)

    @Test
    fun aLateOlderPushDoesNotRollTheMarkBack() {
        val store = MaxStore(initial = MaxState(me = 1))
        store.apply(mark(100, 5, 2_000))
        store.apply(mark(100, 5, 1_500)) // arrives late
        assertEquals(2_000L, store.state.value.readMarks[100]?.get(5))
        store.apply(mark(100, 5, 2_500))
        assertEquals(2_500L, store.state.value.readMarks[100]?.get(5))
        store.apply(mark(100, 5, 2_500))
        assertEquals(2_500L, store.state.value.readMarks[100]?.get(5))
    }

    @Test
    fun ownLateMarkKeepsTheUnreadCounterButMarkAsUnreadMovesBack() {
        val chat = Chat.from(mapOf("id" to 100L, "type" to "CHAT", "newMessages" to 3, "lastMessage" to mapOf("id" to 9L, "time" to 3_000L, "type" to "USER", "sender" to 5L)))!!
        val store = MaxStore(initial = StateReducer.putChats(MaxState(me = 1), listOf(chat)))
        store.apply(mark(100, 1, 3_000))
        assertEquals(0, store.state.value.chats.getValue(100).newMessages)
        // an older own mark arriving late changes nothing
        store.apply(mark(100, 1, 1_000))
        assertEquals(3_000L, store.state.value.readMarks[100]?.get(1))
        assertEquals(0, store.state.value.chats.getValue(100).newMessages)
        // "mark as unread" is the one way back
        store.apply(mark(100, 1, 2_999, unread = true))
        assertEquals(2_999L, store.state.value.readMarks[100]?.get(1))
        assertEquals(1, store.state.value.chats.getValue(100).newMessages)
    }
}
