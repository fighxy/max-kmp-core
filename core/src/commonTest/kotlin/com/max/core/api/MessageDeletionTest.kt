package com.max.core.api

import com.max.core.state.MaxState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageDeletionTest {
    private val me = 1L

    private fun chat(id: Long, type: String, extra: Map<String, Any?> = emptyMap()) = Chat.from(mapOf("id" to id, "type" to type) + extra)!!

    private fun msg(id: Long, chatId: Long, sender: Long, time: Long) =
        MaxMessage.from(mapOf("id" to id, "chatId" to chatId, "sender" to sender, "time" to time, "type" to "USER", "text" to "t"), chatId)!!

    @Test
    fun rightsFollowTheWebClientBits() {
        val group = chat(-10, "CHAT", mapOf("owner" to 9L, "adminParticipants" to mapOf("1" to mapOf("permissions" to 1L), "2" to mapOf("permissions" to 1024L), "3" to mapOf("permissions" to 8L)), "admins" to listOf(4L)))
        val roles = ChatRoles.of(group)
        assertTrue(roles.canDeleteAnyMessage(9, channel = false)) // owner
        assertTrue(roles.canDeleteAnyMessage(1, channel = false)) // bit 1 in a group
        assertFalse(roles.canDeleteAnyMessage(2, channel = false)) // 1024 is the channel bit
        assertTrue(roles.canDeleteAnyMessage(2, channel = true))
        assertTrue(roles.canDeleteAnyMessage(1, channel = true))
        assertFalse(roles.canDeleteAnyMessage(3, channel = true))
        assertFalse(roles.canDeleteAnyMessage(4, channel = false)) // admin without known bits
        assertFalse(roles.canDeleteAnyMessage(5, channel = false))

        val r = ChatRights.of(group, 1)
        assertEquals(ChatMemberRole.ADMIN, r.role)
        assertEquals(1, r.permissions)
        assertTrue(r.isAdmin && r.canDeleteAnyMessage)
        assertTrue(ChatRights.of(group, 9).isOwner)
        assertNull(ChatRights.of(group, 9).permissions)
        assertEquals(ChatRights.NONE, ChatRights.of(chat(5, "DIALOG", mapOf("owner" to 1L)), 1))
        assertEquals(ChatRights.NONE, ChatRights.of(null, 1))
    }

    @Test
    fun planReadsTheStore() {
        val now = 1_800_000_000_000L
        val group = chat(-10, "CHAT", mapOf("owner" to 9L))
        val channel = chat(-20, "CHANNEL", mapOf("adminParticipants" to mapOf("1" to mapOf("permissions" to 1024L))))
        val state = MaxState(
            me = me,
            chats = mapOf(-10L to group, -20L to channel, 5L to chat(5, "DIALOG")),
            messages = mapOf(
                -10L to listOf(msg(21, -10, 1, now - 1_000), msg(22, -10, 7, now - 1_000), msg(23, -10, 1, now - 7_200_000)),
                -20L to listOf(msg(31, -20, 8, now - 1_000)),
                5L to listOf(msg(11, 5, 1, now - 1_000)),
            ),
        )
        val g = MessageDeletion.plan(state, -10, listOf(21, 22, 23, 99, 21), editTimeoutSeconds = 3_600, nowMs = now)
        assertEquals(listOf(21L to DeleteScope.ALL, 22L to DeleteScope.SELF, 23L to DeleteScope.SELF, 99L to DeleteScope.SELF), g.scopes.toList())
        assertTrue(g.canDelete)
        assertFalse(g.showsForEveryone)
        assertTrue(MessageDeletion.plan(state, -10, listOf(21), 3_600, now).showsForEveryone)
        // no edit-timeout: own messages are not deletable for everyone
        assertEquals(DeleteScope.SELF, MessageDeletion.plan(state, -10, listOf(21), 0, now).scopes[21])
        // a channel admin with the delete bit
        val c = MessageDeletion.plan(state, -20, listOf(31), 0, now)
        assertEquals(DeleteScope.ALL, c.scopes[31])
        assertTrue(c.forcesForEveryone)
        assertFalse(c.showsForEveryone)
        // without the bit nothing
        val sub = state.copy(chats = state.chats + (-20L to chat(-20, "CHANNEL")))
        assertFalse(MessageDeletion.plan(sub, -20, listOf(31), 0, now).canDelete)
        // dialogs and Saved Messages
        assertEquals(DeleteScope.ALL, MessageDeletion.plan(state, 5, listOf(11), 60, now).scopes[11])
        assertEquals(DeleteScope.SELF, MessageDeletion.plan(state, 0, listOf(11), 60, now).scopes[11])
        // unknown chats: positive ids are dialogs, negative ones groups
        assertEquals(DeleteChatKind.DIALOG, DeleteChatKind.of(77, null))
        assertEquals(DeleteChatKind.GROUP, DeleteChatKind.of(-77, null))
    }

    @Test
    fun editTimeoutComesFromTheServerConfig() {
        assertEquals(0L, AccountConfig().editTimeoutSeconds)
        assertEquals(86_400L, AccountConfig(server = mapOf("edit-timeout" to 86_400L)).editTimeoutSeconds)
        assertEquals(0L, AccountConfig(server = mapOf("edit-timeout" to -5L)).editTimeoutSeconds)
        assertEquals(60L, AccountConfig(server = mapOf("edit-timeout" to "60")).editTimeoutSeconds)
    }

    @Test
    fun pagingStopsWithoutNewMembersAndOrdersByRole() {
        fun m(id: Long) = ChatMember(id, mapOf("id" to id), null, emptyMap<Any?, Any?>())
        val roles = ChatRoles.of(mapOf("owner" to 3L, "adminParticipants" to mapOf("2" to emptyMap<String, Any?>())))
        val first = ChatMembersResult.of(ChatMembersPage(listOf(m(1), m(2)), 2, emptyMap<Any?, Any?>()), 0, roles).appendTo(emptyList())
        assertEquals(2L, first.nextMarker)
        val again = ChatMembersResult.of(ChatMembersPage(listOf(m(2)), 4, emptyMap<Any?, Any?>()), 2, roles).appendTo(first.members)
        assertNull(again.nextMarker)
        val more = ChatMembersResult.of(ChatMembersPage(listOf(m(2), m(3)), 4, emptyMap<Any?, Any?>()), 2, roles).appendTo(first.members)
        assertEquals(listOf(1L, 2L, 3L), more.members.map { it.userId })
        assertEquals(listOf(3L, 2L, 1L), ChatMembersResult.byRole(more.members).map { it.userId })
    }
}
