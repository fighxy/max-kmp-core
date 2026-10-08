package com.max.core.api

import com.max.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRolesTest {
    private val chat = Chat.from(
        mapOf(
            "id" to -70L, "type" to "CHAT", "owner" to 1L,
            "admins" to listOf(2L, "3"),
            "adminParticipants" to mapOf("3" to mapOf("permissions" to 24, "alias" to " Модератор "), 4L to mapOf("permissions" to 2)),
        ),
    )!!

    @Test
    fun rolesComeFromTheChatObject() {
        val roles = ChatRoles.of(chat)
        assertEquals(1L, roles.owner)
        assertEquals(ChatMemberRole.OWNER, roles.roleOf(1))
        assertEquals(ChatMemberRole.ADMIN, roles.roleOf(2))
        assertEquals(ChatMemberRole.ADMIN, roles.roleOf(3))
        assertEquals(ChatMemberRole.ADMIN, roles.roleOf(4))
        assertEquals(ChatMemberRole.MEMBER, roles.roleOf(5))
        // `admins` alone carries no bits; `adminParticipants` has them and the alias
        assertNull(roles.admins.getValue(2).permissions)
        assertEquals(ChatAdmin(3, 24, "Модератор"), roles.admins[3])
        assertTrue(roles.can(3, ChatPermission.CHANGE_CHAT_INFO))
        assertTrue(roles.can(3, ChatPermission.PIN_MESSAGE))
        assertFalse(roles.can(3, ChatPermission.ADD_ADMIN))
        assertFalse(roles.can(2, ChatPermission.PIN_MESSAGE))
        assertTrue(roles.can(4, ChatPermission.ADD_REMOVE_MEMBER))
        assertTrue(roles.can(1, ChatPermission.DELETE_MESSAGE))
        assertFalse(roles.can(5, ChatPermission.PIN_MESSAGE))
        assertEquals(ChatRoles.NONE, ChatRoles.of(null as Chat?))
        assertEquals(ChatMemberRole.MEMBER, ChatRoles.NONE.roleOf(1))
    }

    @Test
    fun membersPageWithRolesAndNextMarker() = runTest {
        fun member(id: Long, name: String, status: Int? = null) = mapOf(
            "contact" to mapOf("id" to id, "names" to listOf(mapOf("name" to name, "type" to "ONEME"))),
            "presence" to (if (status == null) mapOf("seen" to 1_700_000_000L) else mapOf("seen" to 1_700_000_100L, "status" to status)),
        )
        val sink = ScriptSink(
            mapOf("members" to listOf(member(1, "Owner"), member(3, "Admin", 1), member(5, "Member")), "marker" to 50L),
            mapOf("members" to listOf(member(6, "Last")), "marker" to 50L),
            mapOf("members" to emptyList<Any?>()),
        )
        val api = ChatsApi(sink)
        val roles = ChatRoles.of(chat)
        val first = ChatMembersResult.of(api.getChatMembers(-70), 0, roles)
        assertEquals(mapOf("type" to "MEMBER", "chatId" to -70L, "marker" to 0L, "count" to 50), sink.sent[0].second)
        assertEquals(listOf(ChatMemberRole.OWNER, ChatMemberRole.ADMIN, ChatMemberRole.MEMBER), first.members.map { it.role })
        assertEquals("Admin", first.members[1].user?.displayName)
        assertEquals("Модератор", first.members[1].admin?.alias)
        assertNull(first.members[0].admin)
        assertEquals(PresenceInfo(1_700_000_100L, 1), first.members[1].member.presenceInfo)
        assertEquals(PresenceInfo(1_700_000_000L, null), first.members[0].member.presenceInfo)
        assertEquals(50L, first.nextMarker)
        // a repeated marker ends the list, so does a page without one
        val second = ChatMembersResult.of(api.getChatMembers(-70, marker = 50), 50, roles)
        assertEquals(50L, (sink.sent[1].second as Map<*, *>)["marker"])
        assertNull(second.nextMarker)
        assertNull(ChatMembersResult.of(api.getChatMembers(-70, marker = 7), 7, roles).nextMarker)
        assertEquals(List(3) { Opcode.CHAT_MEMBERS }, sink.opcodes)
    }
}
