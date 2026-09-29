package com.max.core.state

import com.max.core.api.Chat
import com.max.core.api.MaxMessage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The one-pass [StateReducer.putChats] against the previous chat-by-chat version. */
class PutChatsBatchTest {
    private val templateMessage = MaxMessage.from(mapOf("id" to 1, "time" to 1, "type" to "USER", "sender" to 2, "text" to "m"), 1)!!
    private val templateChat = Chat.from(mapOf("id" to 1, "type" to "CHAT", "status" to "ACTIVE", "lastEventTime" to 0))!!

    private fun message(chatId: Long, id: Long, time: Long) = templateMessage.copy(id = id, chatId = chatId, time = time)
    private fun chat(id: Long, last: MaxMessage?, eventTime: Long = 0) = templateChat.copy(id = id, lastMessage = last, lastEventTime = eventTime)

    // ---- the previous implementation (reference) ----

    private fun oldPutChats(state: MaxState, chats: List<Chat>): MaxState = chats.fold(state, ::oldPutChat)

    private fun oldPutChat(state: MaxState, chat: Chat): MaxState {
        val old = state.chats[chat.id]
        val merged = if (chat.lastMessage == null && old?.lastMessage != null) chat.copy(lastMessage = old.lastMessage) else chat
        return oldOpenHistoryGaps(state.copy(chats = state.chats + (chat.id to merged)))
    }

    private fun oldOpenHistoryGaps(state: MaxState): MaxState {
        var anchors = state.gapAnchors
        var changed = false
        for (chat in state.chats.values) {
            if (chat.id in anchors) continue
            val last = chat.lastMessage ?: continue
            val local = state.messages[chat.id]
            if (local.isNullOrEmpty()) continue
            val tail = local.last()
            if (local.none { it.id == last.id } && last.time >= tail.time) {
                anchors = anchors + (chat.id to tail.id)
                changed = true
            }
        }
        return if (changed) state.copy(gapAnchors = anchors) else state
    }

    // ---- random samples ----

    private fun randomState(rnd: Random): MaxState {
        val chats = HashMap<Long, Chat>()
        val messages = HashMap<Long, List<MaxMessage>>()
        for (id in 1L..30L) {
            val count = rnd.nextInt(0, 8)
            val list = (0 until count).map { i -> message(id, id * 100 + i, 10L * i + rnd.nextInt(0, 3)) }
                .sortedWith(compareBy({ it.time }, { it.id }))
            if (list.isNotEmpty()) messages[id] = list
            if (rnd.nextInt(4) != 0) chats[id] = chat(id, list.lastOrNull()?.takeIf { rnd.nextBoolean() } ?: randomLast(rnd, id, list))
        }
        // a consistent starting point: every hole already has its anchor
        return oldOpenHistoryGaps(MaxState(chats = chats, messages = messages))
    }

    private fun randomLast(rnd: Random, chatId: Long, local: List<MaxMessage>): MaxMessage? = when (rnd.nextInt(5)) {
        0 -> null
        1 -> local.randomOrNull(rnd) // a stored message
        2 -> local.lastOrNull()?.let { it.copy(time = it.time + 5) } // stored id, other time
        3 -> message(chatId, 9_000 + rnd.nextLong(0, 50), rnd.nextLong(0, 120)) // unknown, older or newer
        else -> message(chatId, 9_500 + rnd.nextLong(0, 50), 200) // ahead of the tail: a hole
    }

    private fun randomBatch(rnd: Random, state: MaxState): List<Chat> = List(rnd.nextInt(1, 50)) {
        val id = rnd.nextLong(1, 36) // some chats are new
        chat(id, randomLast(rnd, id, state.messagesOf(id)), eventTime = rnd.nextLong(0, 300))
    }

    @Test
    fun batchGivesTheSameStateAsChatByChat() {
        val rnd = Random(20260929)
        repeat(500) { round ->
            val state = randomState(rnd)
            val batch = randomBatch(rnd, state)
            assertEquals(oldPutChats(state, batch), StateReducer.putChats(state, batch), "round $round")
        }
    }

    @Test
    fun largeBatchDoesNotRescanEveryHistory() {
        val chats = 3_000L
        val perChat = 100
        val messages = HashMap<Long, List<MaxMessage>>()
        val chatMap = HashMap<Long, Chat>()
        for (id in 1L..chats) {
            val list = (0 until perChat).map { i -> message(id, id * 1_000 + i, i.toLong()) }
            messages[id] = list
            chatMap[id] = chat(id, list.last())
        }
        val state = MaxState(chats = chatMap, messages = messages)
        // a chat-list page: every chat again (no hole) plus a newer last message for every tenth one
        val batch = (1L..chats).map { id ->
            val list = messages.getValue(id)
            if (id % 10 == 0L) chat(id, message(id, id * 1_000 + 999, 500), eventTime = 500) else chat(id, list.last(), eventTime = 1)
        }
        val mark = TimeSource.Monotonic.markNow()
        val result = StateReducer.putChats(state, batch)
        val elapsed = mark.elapsedNow()
        assertEquals(chats / 10, result.gapAnchors.size.toLong())
        assertEquals((perChat - 1).toLong() + 10 * 1_000, result.gapAnchors.getValue(10))
        // chat by chat this is ~ chats * chats * perChat = 9e8 message checks; one pass is ~ chats
        assertTrue(elapsed.inWholeMilliseconds < 5_000, "putChats took $elapsed")
    }
}
