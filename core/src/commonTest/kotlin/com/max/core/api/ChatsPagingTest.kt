package com.max.core.api

import com.max.core.auth.Login2Result
import com.max.core.protocol.Opcode
import com.max.core.state.MaxState
import com.max.core.state.StateReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ChatsPagingTest {
    private fun chat(id: Long) = mapOf("id" to id, "type" to "CHAT", "title" to "c$id")

    @Test
    fun fetchChatsPageSendsMarkerAndCountAndReadsNextMarker() = runTest {
        val sink = ScriptSink(
            mapOf("chats" to listOf(chat(1), chat(2)), "marker" to 900L),
            mapOf("chats" to emptyList<Any?>()),
        )
        val api = ChatsApi(sink)
        val page = api.fetchChatsPage(1000L, count = 2)
        assertEquals(Opcode.CHATS_LIST, sink.sent[0].first)
        // count is an Int on the wire, the marker a Long.
        assertEquals(mapOf<String, Any>("marker" to 1000L, "count" to 2.toInt()), sink.sent[0].second)
        assertEquals(listOf(1L, 2L), page.chats.map { it.id })
        assertEquals(900L, page.nextMarker)
        val last = api.fetchChatsPage(900L)
        assertEquals(emptyList<Chat>(), last.chats)
        assertNull(last.nextMarker)
    }

    @Test
    fun login2ContactsAcceptBothKeys() {
        assertEquals(1, Login2Result.from(mapOf("contactInfos" to listOf(mapOf("id" to 2)))).contacts.size)
        assertEquals(1, Login2Result.from(mapOf("contacts" to listOf(mapOf("id" to 3)))).contacts.size)
    }

    @Test
    fun putContactsAddsToTheContactListWithoutSelf() {
        val users = listOf(mapOf("id" to 5), mapOf("id" to 10)).mapNotNull(MaxUser::from)
        val state = StateReducer.putContacts(MaxState(me = 10L, contactIds = setOf(4L)), users)
        assertEquals(setOf(4L, 5L), state.contactIds)
        assertEquals(setOf(5L, 10L), state.users.keys)
    }
}
