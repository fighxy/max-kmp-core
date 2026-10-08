package com.max.core.api

/** `type` of a `CHAT_MEMBERS` 59 request: which list of the chat is asked. */
object MemberListType {
    const val MEMBER = "MEMBER"
    const val ADMIN = "ADMIN"
    const val BLOCKED_MEMBER = "BLOCKED_MEMBER"
    const val JOIN_REQUEST = "JOIN_REQUEST"
    const val COMMENTS_BLACKLIST = "COMMENTS_BLACKLIST"
}

/** Role of a member in a group or channel ([ChatRoles.roleOf]). */
enum class ChatMemberRole { OWNER, ADMIN, MEMBER }

/**
 * An admin of a chat: an entry of the chat's `adminParticipants` (`{<userId>: {permissions,
 * alias, inviterId}}`) or an id of its `admins` list. [permissions] are the [ChatPermission] bits, `null`
 * when the chat object does not carry them; [alias] is the admin's title ("должность").
 */
data class ChatAdmin(val userId: Long, val permissions: Int?, val alias: String?, val inviterId: Long? = null) {
    /** Whether [permissions] has the bit of [permission]; `false` while the bits are unknown. */
    fun can(permission: ChatPermission): Boolean = permissions?.let { it and permission.bit != 0 } ?: false
}

/**
 * Who runs a chat, read from the chat object (`CHAT_INFO` 48, `LOGIN`, `NOTIF_CHAT` 135): the
 * `CHAT_MEMBERS` 59 page itself carries no roles. Fields as KometTeam/Komet reads them
 * (`ChatInfo`, `chat_parsing.dart`): `owner`, `admins` (list of ids) and `adminParticipants`
 * (id → `{permissions, alias}`); PyMax types the same `owner`, `admins` and
 * `admin_participants` on `Chat`. Ids may arrive as numbers or decimal strings; both admin
 * sources are merged.
 */
data class ChatRoles(val owner: Long?, val admins: Map<Long, ChatAdmin>) {
    fun roleOf(userId: Long): ChatMemberRole = when {
        owner != null && userId == owner -> ChatMemberRole.OWNER
        userId in admins -> ChatMemberRole.ADMIN
        else -> ChatMemberRole.MEMBER
    }

    /** The owner can do everything; an admin what its bits allow; a member nothing here. */
    fun can(userId: Long, permission: ChatPermission): Boolean =
        roleOf(userId) == ChatMemberRole.OWNER || admins[userId]?.can(permission) == true

    companion object {
        val NONE = ChatRoles(null, emptyMap())

        fun of(chat: Chat?): ChatRoles = chat?.let { of(it.raw) } ?: NONE

        fun of(raw: Map<*, *>): ChatRoles {
            val admins = LinkedHashMap<Long, ChatAdmin>()
            (raw["admins"] as? List<*>).orEmpty().forEach { id ->
                id.asLong()?.let { admins[it] = ChatAdmin(it, null, null) }
            }
            (raw["adminParticipants"] as? Map<*, *>).orEmpty().forEach { (key, value) ->
                val id = key.asLong() ?: return@forEach
                val entry = value as? Map<*, *>
                admins[id] = ChatAdmin(
                    userId = id,
                    permissions = entry?.get("permissions").asLong()?.toInt(),
                    alias = (entry?.get("alias") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                    inviterId = entry?.get("inviterId").asLong(),
                )
            }
            return ChatRoles(raw["owner"].asLong(), admins)
        }
    }
}

/**
 * A member of a [ChatMembersPage] with its [role] in the chat ([ChatRoles]) and, for an admin,
 * its [admin] entry. [user] is the parsed `contact`.
 */
data class ChatMemberEntry(val member: ChatMember, val user: MaxUser?, val role: ChatMemberRole, val admin: ChatAdmin?) {
    val userId: Long? get() = member.userId

    companion object {
        fun of(member: ChatMember, roles: ChatRoles): ChatMemberEntry {
            val id = member.userId
            return ChatMemberEntry(
                member = member,
                user = MaxUser.from(member.contact),
                role = id?.let(roles::roleOf) ?: ChatMemberRole.MEMBER,
                admin = id?.let { roles.admins[it] },
            )
        }
    }
}

/**
 * A page of chat members with their roles. [nextMarker] is the `marker` to ask for the next page,
 * `null` at the end: the reply had no marker (or `0`), repeated the requested one, or no members.
 */
data class ChatMembersResult(val members: List<ChatMemberEntry>, val nextMarker: Long?) {
    companion object {
        fun of(page: ChatMembersPage, requestedMarker: Long, roles: ChatRoles): ChatMembersResult = ChatMembersResult(
            members = page.members.map { ChatMemberEntry.of(it, roles) },
            nextMarker = page.marker?.takeIf { it != 0L && it != requestedMarker && page.members.isNotEmpty() },
        )
    }
}
