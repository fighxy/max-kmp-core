package com.maxly.core.state

import com.maxly.core.api.Chat
import com.maxly.core.api.LocalRead
import com.maxly.core.api.MaxMessage
import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Local read marks (hidden read receipts) on top of the server's read state. */
class LocalReadsStateTest {
    private val me = 1L

    private fun chat(newMessages: Int, lastId: Long, lastTime: Long, myMark: Long? = null) = Chat.from(
        buildMap {
            put("id", 100L); put("type", "CHAT"); put("newMessages", newMessages)
            put("lastMessage", mapOf("id" to lastId, "time" to lastTime, "type" to "USER", "sender" to 5L))
            if (myMark != null) put("participants", mapOf("$me" to myMark, "5" to lastTime))
        },
    )!!

    private fun store(vararg chats: Chat) = MaxStore(initial = StateReducer.putChats(MaxState(me = me), chats.toList()))

    @Test
    fun aLocalReadClearsTheCounterAndOutlivesServerUpdates() {
        val s = store(chat(3, 9, 3_000, myMark = 1_000))
        s.putLocalRead(100, LocalRead(9, 3_000))
        assertEquals(0, s.state.value.chats.getValue(100).newMessages)
        // a chat sync with the server's old counter does not raise it again
        s.putChats(listOf(chat(3, 9, 3_000, myMark = 1_000)))
        assertEquals(0, s.state.value.chats.getValue(100).newMessages)
        // an older local read never moves the mark back
        s.putLocalRead(100, LocalRead(8, 2_000))
        assertEquals(LocalRead(9, 3_000), s.state.value.localReads[100])
    }

    @Test
    fun theServerMarkReachingTheLocalOneDropsIt() {
        val s = store(chat(3, 9, 3_000, myMark = 1_000))
        s.putLocalRead(100, LocalRead(9, 3_000))
        s.apply(MaxEvent.MessageRead(100, me, 3_000, false, Opcode.NOTIF_MARK.value, null))
        assertTrue(s.state.value.localReads.isEmpty())
        assertEquals(0, s.state.value.chats.getValue(100).newMessages)
        // a local read the server already covers is not stored at all
        s.putLocalRead(100, LocalRead(9, 2_500))
        assertTrue(s.state.value.localReads.isEmpty())
    }

    @Test
    fun laterMessagesCountWhenTheStoreHoldsThem() {
        val s = store(chat(2, 9, 3_000))
        s.putLocalRead(100, LocalRead(9, 3_000))
        s.apply(MaxEvent.NewMessage(MaxMessage.from(mapOf("id" to 10L, "time" to 3_100L, "type" to "USER", "sender" to 5L), 100L)!!, Opcode.NOTIF_MESSAGE.value, null))
        assertEquals(1, s.state.value.chats.getValue(100).newMessages)
    }

    @Test
    fun loadedReadsApplyToChatsArrivingLaterAndGoWithTheAccount() {
        val s = MaxStore(initial = MaxState(me = me))
        s.setLocalReads(mapOf(100L to LocalRead(9, 3_000)))
        s.putChats(listOf(chat(4, 9, 3_000)))
        assertEquals(0, s.state.value.chats.getValue(100).newMessages)
        s.setGhostMode(true)
        s.setHideReadReceipts(true)
        s.clear()
        assertTrue(s.state.value.localReads.isEmpty())
        assertTrue(s.state.value.ghostMode)
        assertTrue(s.state.value.hideReadReceipts)
    }
}
