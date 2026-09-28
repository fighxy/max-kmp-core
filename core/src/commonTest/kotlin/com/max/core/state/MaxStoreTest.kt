package com.max.core.state

import com.max.core.api.Chat
import com.max.core.api.ChatHistory
import com.max.core.api.MaxMessage
import com.max.core.api.PresenceInfo
import com.max.core.api.ReactionCounter
import com.max.core.auth.LoginResult
import com.max.core.events.EventParser
import com.max.core.events.MaxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxStoreTest {
    private val me = 10L
    private var now = 1_000_000L
    private fun store(limit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT) = MaxStore(limit, clock = { now })

    private fun msg(id: Long, time: Long, sender: Long = 20, chatId: Long = 1, text: String = "m$id", status: String? = null) =
        linkedMapOf<String, Any?>("id" to id, "chatId" to chatId, "sender" to sender, "time" to time, "type" to "USER", "text" to text)
            .also { if (status != null) it["status"] = status }

    private fun chat(id: Long, lastEventTime: Long = 0, newMessages: Int = 0, last: Map<String, Any?>? = null) =
        linkedMapOf<String, Any?>("id" to id, "type" to "DIALOG", "status" to "ACTIVE", "owner" to me, "lastEventTime" to lastEventTime, "newMessages" to newMessages)
            .also { if (last != null) it["lastMessage"] = last }

    /** Envelope as in `NOTIF_MESSAGE` 128. */
    private fun push(opcode: Int, payload: Any?) = EventParser.parse(opcode, 0, payload)
    private fun newMessage(m: Map<String, Any?>) = push(128, mapOf("chatId" to m["chatId"], "message" to m - "chatId"))

    private fun loggedIn(): MaxStore = store().also {
        it.applyLogin(
            LoginResult.from(
                mapOf(
                    "profile" to mapOf("contact" to mapOf("id" to me, "names" to listOf(mapOf("name" to "Me")))),
                    "chats" to listOf(chat(1, 100, 0, msg(5, 100)), chat(2, 200)),
                    "contacts" to listOf(mapOf("id" to 20, "names" to listOf(mapOf("firstName" to "Ann", "lastName" to "Lee")), "phone" to 79990000000)),
                    "messages" to mapOf("1" to listOf(msg(4, 90), msg(5, 100))),
                ),
            ),
        )
    }

    @Test
    fun loginSeedsChatsUsersAndMessages() {
        val s = loggedIn().state.value
        assertEquals(me, s.me)
        assertEquals(setOf(1L, 2L), s.chats.keys)
        assertEquals(listOf(4L, 5L), s.messagesOf(1).map { it.id })
        assertEquals("Ann Lee", s.users.getValue(20).displayName)
        assertEquals(79990000000, s.users.getValue(20).phone)
        assertEquals("Me", s.users.getValue(me).displayName)
        // chat 2 has the later activity
        assertEquals(listOf(2L, 1L), s.chatList.map { it.id })
    }

    @Test
    fun newMessageUpdatesChatAndCounters() {
        val st = loggedIn()
        st.apply(push(129, mapOf("chatId" to 1, "userId" to 20)))
        assertEquals(setOf(20L), st.state.value.typingUsers(1, now))
        st.apply(newMessage(msg(6, 300)))
        var s = st.state.value
        assertEquals(listOf(4L, 5L, 6L), s.messagesOf(1).map { it.id })
        val c = s.chats.getValue(1)
        assertEquals(6L, c.lastMessage!!.id)
        assertEquals(300L, c.lastEventTime)
        assertEquals(1, c.newMessages)
        // the sender stopped typing; chat 1 moved to the top
        assertTrue(s.typingUsers(1, now).isEmpty())
        assertEquals(listOf(1L, 2L), s.chatList.map { it.id })
        // own message and a duplicate do not count as unread
        st.apply(newMessage(msg(7, 310, sender = me)))
        st.apply(newMessage(msg(7, 310, sender = me)))
        s = st.state.value
        assertEquals(1, s.chats.getValue(1).newMessages)
        assertEquals(listOf(4L, 5L, 6L, 7L), s.messagesOf(1).map { it.id })
        // an older message is stored but does not become lastMessage
        st.apply(newMessage(msg(3, 50)))
        s = st.state.value
        assertEquals(3L, s.messagesOf(1).first().id)
        assertEquals(7L, s.chats.getValue(1).lastMessage!!.id)
        // message for an unknown chat is kept, no chat invented
        st.apply(newMessage(msg(1, 1, chatId = 99)))
        assertEquals(1, st.state.value.messagesOf(99).size)
        assertNull(st.state.value.chats[99])
    }

    @Test
    fun editDeleteAndReactions() {
        val st = loggedIn()
        st.apply(push(128, mapOf("chatId" to 1, "message" to msg(5, 100, text = "edited", status = "EDITED") - "chatId")))
        var s = st.state.value
        assertEquals("edited", s.messagesOf(1).last().text)
        assertEquals("edited", s.chats.getValue(1).lastMessage!!.text)

        st.apply(push(155, mapOf("chatId" to 1, "messageId" to "5", "counters" to listOf(mapOf("reaction" to "👍", "count" to 2)), "totalCount" to 2)))
        s = st.state.value
        assertEquals(listOf(ReactionCounter("👍", 2)), s.messagesOf(1).last().reactionInfo!!.counters)
        assertEquals(2, s.chats.getValue(1).lastMessage!!.reactionInfo!!.totalCount)
        // reactions for an unknown message: no change
        val before = st.state.value
        st.apply(push(155, mapOf("chatId" to 1, "messageId" to "404", "totalCount" to 1)))
        assertEquals(before, st.state.value)

        // NOTIF_MSG_DELETE 142 with a chat: lastMessage falls back to the newest remaining one
        st.apply(push(142, mapOf("chat" to chat(1, 400, 0), "messageIds" to listOf(5))))
        s = st.state.value
        assertEquals(listOf(4L), s.messagesOf(1).map { it.id })
        assertEquals(4L, s.chats.getValue(1).lastMessage!!.id)
        assertEquals(400L, s.chats.getValue(1).lastEventTime)
        // REMOVED status via 128
        st.apply(push(128, mapOf("chatId" to 1, "message" to msg(4, 90, status = "REMOVED") - "chatId")))
        s = st.state.value
        assertTrue(s.messagesOf(1).isEmpty())
        assertNull(s.chats.getValue(1).lastMessage)
    }

    @Test
    fun readMarksPresenceAndChatUpdates() {
        val st = loggedIn()
        st.apply(newMessage(msg(6, 300)))
        st.apply(newMessage(msg(7, 400)))
        assertEquals(2, st.state.value.chats.getValue(1).newMessages)
        // own read up to 300: one message from others is newer
        st.apply(push(130, mapOf("setAsUnread" to false, "chatId" to 1, "userId" to me, "mark" to 300)))
        assertEquals(1, st.state.value.chats.getValue(1).newMessages)
        st.apply(push(130, mapOf("setAsUnread" to false, "chatId" to 1, "userId" to me, "mark" to 400)))
        assertEquals(0, st.state.value.chats.getValue(1).newMessages)
        st.apply(push(130, mapOf("setAsUnread" to true, "chatId" to 1, "userId" to me, "mark" to 400)))
        assertEquals(1, st.state.value.chats.getValue(1).newMessages)
        // someone else's mark only records it
        st.apply(push(130, mapOf("setAsUnread" to false, "chatId" to 1, "userId" to 20, "mark" to 350)))
        assertEquals(mapOf(me to 400L, 20L to 350L), st.state.value.readMarks[1])
        assertEquals(1, st.state.value.chats.getValue(1).newMessages)

        st.apply(push(132, mapOf("userId" to 20, "presence" to mapOf("seen" to 5, "status" to 1))))
        assertEquals(PresenceInfo(5, 1), st.state.value.presence[20])
        st.apply(push(132, mapOf("userId" to 20, "presence" to mapOf("seen" to 9))))
        assertEquals(PresenceInfo(9, null), st.state.value.presence[20])

        // chat push without lastMessage keeps the stored one
        st.apply(push(135, mapOf("chat" to chat(1, 500, 3) + ("title" to "Renamed"))))
        val c = st.state.value.chats.getValue(1)
        assertEquals("Renamed", c.title)
        assertEquals(7L, c.lastMessage!!.id)
        assertEquals(3, c.newMessages)
    }

    @Test
    fun typingExpiresAndOtherEventsAreNoOps() {
        val st = loggedIn()
        st.apply(push(129, mapOf("chatId" to 2, "userId" to 20)))
        now += MaxState.DEFAULT_TYPING_TTL_MS + 1
        assertTrue(st.state.value.typingUsers(2, now).isEmpty())
        assertEquals(setOf(20L), st.state.value.typingUsers(2, now, ttlMs = Long.MAX_VALUE))
        val before = st.state.value
        st.apply(push(136, mapOf("fileId" to 1)))
        st.apply(push(134, mapOf("config" to mapOf("hash" to "x"))))
        st.apply(MaxEvent.Unknown(128, 1, null))
        assertEquals(before, st.state.value)
    }

    @Test
    fun historyLimitAndRemoval() {
        val st = store(limit = 3)
        st.putChats(listOfNotNull(Chat.from(chat(1))))
        val page = (1L..5L).map { MaxMessage.from(msg(it, it * 10))!! }.reversed()
        st.putHistory(1, ChatHistory(page, Chat.from(chat(1, 77)), emptyMap<Any?, Any?>()))
        var s = st.state.value
        assertEquals(listOf(3L, 4L, 5L), s.messagesOf(1).map { it.id })
        assertEquals(77L, s.chats.getValue(1).lastEventTime)
        st.putMessages(1, listOf(MaxMessage.from(msg(6, 5))!!))
        // sorted by time: id 6 (time 5) is the oldest and falls out
        assertEquals(listOf(3L, 4L, 5L), st.state.value.messagesOf(1).map { it.id })
        st.apply(push(129, mapOf("chatId" to 1, "userId" to 20)))
        st.removeChat(1)
        s = st.state.value
        assertNull(s.chats[1])
        assertTrue(s.messagesOf(1).isEmpty())
        assertTrue(s.typing.isEmpty())
        st.clear()
        assertEquals(MaxState(), st.state.value)
    }
}
