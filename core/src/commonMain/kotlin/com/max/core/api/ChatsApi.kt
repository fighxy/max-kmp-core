package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.epochMillis
import com.max.core.protocol.Opcode

/**
 * Chat requests over a [RequestSink], following PyMax `src/pymax/api/chats/service.py`
 * (`ChatService`) and `payloads.py`; kolibri only has the opcode numbers. PyMax's chat cache is
 * not reproduced: every call goes to the server. Group management (create, members, admins,
 * settings, profile, join links, join requests, channel comments) follows the same service.
 *
 * Errors as in [MessagesApi].
 */
class ChatsApi(
    private val sink: RequestSink,
    private val clock: () -> Long = ::epochMillis,
    private val messages: MessagesApi = MessagesApi(sink, clock),
) {
    /**
     * Chats by id (`CHAT_INFO`, 48; PyMax `get_chats`, `GetChatInfoPayload`): `{chatIds}`.
     * Reply: `chats` (missing = empty); chats the server did not return are simply absent.
     */
    suspend fun getChats(chatIds: List<Long>): List<Chat> {
        require(chatIds.isNotEmpty()) { "chatIds must not be empty" }
        return chatList(Opcode.CHAT_INFO, linkedMapOf("chatIds" to chatIds))
    }

    /** One chat via [getChats]; throws [ApiException] if the reply does not contain it (PyMax: "Chat not found"). */
    suspend fun getChat(chatId: Long): Chat =
        getChats(listOf(chatId)).firstOrNull { it.id == chatId } ?: throw ApiException("chat $chatId not found in CHAT_INFO reply")

    /**
     * A page of the chat list (`CHATS_LIST`, 53; PyMax `fetch_chats`, `FetchChatsPayload`):
     * `{marker}`, `marker` = now by default. Reply: `chats`.
     */
    suspend fun fetchChats(marker: Long? = null): List<Chat> = chatList(Opcode.CHATS_LIST, linkedMapOf("marker" to (marker ?: clock())))

    /**
     * Members of a group/channel (`CHAT_MEMBERS`, 59; PyMax `get_chat_members`,
     * `GetChatMembersPayload`): `{type: "MEMBER", chatId, marker, count}` (marker `0` for the
     * first page, count `50`). Reply: `members` and the next `marker`.
     */
    suspend fun getChatMembers(chatId: Long, marker: Long = 0, count: Int = 50): ChatMembersPage {
        val payload = linkedMapOf<String, Any?>("type" to "MEMBER", "chatId" to chatId, "marker" to marker, "count" to count)
        val map = replyMap(sink.request(Opcode.CHAT_MEMBERS, payload), Opcode.CHAT_MEMBERS)
        return ChatMembersPage(members(map), map["marker"].asLong() ?: 0, map)
    }

    /** Leaves a group or channel (`CHAT_LEAVE`, 58; PyMax `leave_group`, `LeaveChatPayload`): `{chatId}`. Reply raw. */
    suspend fun leaveChat(chatId: Long): Map<*, *> = rawMap(sink.request(Opcode.CHAT_LEAVE, linkedMapOf("chatId" to chatId)))

    /**
     * Deletes a chat (`CHAT_DELETE`, 52; PyMax `delete_chat`, `DeleteChatPayload`):
     * `{chatId, lastEventTime, forAll}`, `lastEventTime` = now and `forAll = true` by default.
     * Reply raw.
     */
    suspend fun deleteChat(chatId: Long, lastEventTime: Long? = null, forAll: Boolean = true): Map<*, *> =
        rawMap(sink.request(Opcode.CHAT_DELETE, linkedMapOf("chatId" to chatId, "lastEventTime" to (lastEventTime ?: clock()), "forAll" to forAll)))

    // ---- groups and channels (PyMax ChatService) -----------------------------------------------

    /**
     * Creates a group (PyMax `create_group`): `MSG_SEND` 64 with a control attachment
     * `{message: {cid, attaches: [{_type: "CONTROL", event: "new", chatType: "CHAT", title,
     * userIds}]}, notify}`. Reply: the new `chat` and the service message; `null` if the reply
     * has no chat (as PyMax).
     */
    suspend fun createGroup(title: String, userIds: List<Long> = emptyList(), notify: Boolean = true): CreatedGroup? {
        val attach = linkedMapOf<String, Any?>("_type" to "CONTROL", "event" to "new", "chatType" to "CHAT", "title" to title, "userIds" to userIds)
        val payload = linkedMapOf<String, Any?>("message" to linkedMapOf("cid" to messages.nextCid(), "attaches" to listOf(attach)), "notify" to notify)
        val map = replyMap(sink.request(Opcode.MSG_SEND, payload), Opcode.MSG_SEND)
        val chat = Chat.from(map["chat"]) ?: return null
        val message = MaxMessage.from(map, chat.id) ?: throw MalformedReplyException(Opcode.MSG_SEND, "no valid message", map)
        return CreatedGroup(chat, message)
    }

    /**
     * Adds members (`CHAT_MEMBERS_UPDATE` 77, PyMax `invite_users_to_group`, also used for
     * channels): `{chatId, userIds, showHistory, operation: "add"}`. Reply `chat` (optional).
     */
    suspend fun addMembers(chatId: Long, userIds: List<Long>, showHistory: Boolean = true): Chat? = membersUpdate(
        linkedMapOf("chatId" to chatId, "userIds" to userIds, "showHistory" to showHistory, "operation" to "add"),
    )

    /**
     * Removes members (`CHAT_MEMBERS_UPDATE` 77, PyMax `remove_users_from_group`):
     * `{chatId, userIds, operation: "remove", cleanMsgPeriod}`; [cleanMsgPeriod] is how far back
     * their messages are deleted (`0` = keep, PyMax passes it through).
     */
    suspend fun removeMembers(chatId: Long, userIds: List<Long>, cleanMsgPeriod: Long = 0): Chat? = membersUpdate(
        linkedMapOf("chatId" to chatId, "userIds" to userIds, "operation" to "remove", "cleanMsgPeriod" to cleanMsgPeriod),
    )

    /**
     * Makes [userId] an admin (`CHAT_MEMBERS_UPDATE` 77, PyMax `add_admin`):
     * `{chatId, userIds: [userId], type: "ADMIN", operation: "add", permissions}` with the
     * [ChatPermission] bits OR-ed.
     */
    suspend fun addAdmin(chatId: Long, userId: Long, permissions: Set<ChatPermission>): Chat? {
        require(permissions.isNotEmpty()) { "permissions must not be empty" }
        return membersUpdate(
            linkedMapOf(
                "chatId" to chatId, "userIds" to listOf(userId), "type" to "ADMIN", "operation" to "add",
                "permissions" to permissions.fold(0) { acc, p -> acc or p.bit },
            ),
        )
    }

    /** Pending join requests (`CHAT_MEMBERS` 59, `{chatId, type: "JOIN_REQUEST", count}`). */
    suspend fun getJoinRequests(chatId: Long, count: Int = 100): List<ChatMember> {
        val map = replyMap(sink.request(Opcode.CHAT_MEMBERS, linkedMapOf("chatId" to chatId, "type" to "JOIN_REQUEST", "count" to count)), Opcode.CHAT_MEMBERS)
        return members(map)
    }

    /** Accepts join requests (`CHAT_MEMBERS_UPDATE` 77, `{chatId, userIds, type: "JOIN_REQUEST", showHistory, operation: "add"}`). */
    suspend fun confirmJoinRequests(chatId: Long, userIds: List<Long>, showHistory: Boolean = true): Chat? = membersUpdate(
        linkedMapOf("chatId" to chatId, "userIds" to userIds, "type" to "JOIN_REQUEST", "showHistory" to showHistory, "operation" to "add"),
    )

    /** Declines join requests (`CHAT_MEMBERS_UPDATE` 77, `{chatId, userIds, type: "JOIN_REQUEST", operation: "remove"}`). */
    suspend fun declineJoinRequests(chatId: Long, userIds: List<Long>): Chat? = membersUpdate(
        linkedMapOf("chatId" to chatId, "userIds" to userIds, "type" to "JOIN_REQUEST", "operation" to "remove"),
    )

    /**
     * Blocks comment authors of a channel post (`CHAT_MEMBERS_UPDATE` 77, PyMax
     * `block_comment_author`): `{chatId, postId, userIds, messageId, type: "COMMENTS_BLACKLIST",
     * operation: "add", cleanMsgPeriod}`.
     */
    suspend fun blockCommentAuthors(chatId: Long, postId: Long, userIds: List<Long>, messageId: Long, cleanMsgPeriod: Long = 0): Chat? = membersUpdate(
        linkedMapOf(
            "chatId" to chatId, "postId" to postId, "userIds" to userIds, "messageId" to messageId,
            "type" to "COMMENTS_BLACKLIST", "operation" to "add", "cleanMsgPeriod" to cleanMsgPeriod,
        ),
    )

    /** Group options (`CHAT_UPDATE` 55, PyMax `change_group_settings`): `{chatId, options}` with only the given keys. */
    suspend fun updateSettings(chatId: Long, settings: GroupSettings): Chat? {
        val options = settings.toPayload()
        require(options.isNotEmpty()) { "no group setting given" }
        return chatUpdate(linkedMapOf("chatId" to chatId, "options" to options))
    }

    /** Turns channel comments on or off (`CHAT_UPDATE` 55, `{chatId, options: {COMMENTS}}`). */
    suspend fun setChannelComments(chatId: Long, enabled: Boolean): Chat? =
        chatUpdate(linkedMapOf("chatId" to chatId, "options" to linkedMapOf("COMMENTS" to enabled)))

    /**
     * Title / description / photo (`CHAT_UPDATE` 55, PyMax `change_group_profile`:
     * `{chatId, theme?, description?, photoToken?}`, `null`s left out). Upload a new photo with
     * `MediaApi.uploadPhoto` first and pass its token.
     */
    suspend fun updateProfile(chatId: Long, title: String? = null, description: String? = null, photoToken: String? = null): Chat? {
        val payload = linkedMapOf<String, Any?>("chatId" to chatId)
        if (title != null) payload["theme"] = title
        if (description != null) payload["description"] = description
        if (photoToken != null) payload["photoToken"] = photoToken
        return chatUpdate(payload)
    }

    /** New private invite link (`CHAT_UPDATE` 55, `{revokePrivateLink: true, chatId}`); reply `chat` is required. */
    suspend fun revokeInviteLink(chatId: Long): Chat =
        chatUpdate(linkedMapOf("revokePrivateLink" to true, "chatId" to chatId))
            ?: throw MalformedReplyException(Opcode.CHAT_UPDATE, "no chat in reply", null)

    /**
     * Joins by link (`CHAT_JOIN` 57, `{link}`); the `join/...` part of [link] is sent when present
     * (PyMax `join_group` requires it, `join_channel` falls back to the whole link, like here).
     */
    suspend fun join(link: String): Chat {
        val map = replyMap(sink.request(Opcode.CHAT_JOIN, linkedMapOf("link" to (joinPath(link) ?: link))), Opcode.CHAT_JOIN)
        return Chat.from(map["chat"]) ?: throw MalformedReplyException(Opcode.CHAT_JOIN, "no valid chat", map)
    }

    /** Resolves an invite link without joining (`LINK_INFO` 89, `{link: "join/..."}`); `null` if no chat. */
    suspend fun resolveLink(link: String): Chat? {
        val path = joinPath(link) ?: throw IllegalArgumentException("not an invite link: $link")
        return Chat.from(rawMap(sink.request(Opcode.LINK_INFO, linkedMapOf("link" to path)))["chat"])
    }

    private suspend fun membersUpdate(payload: Map<String, Any?>): Chat? =
        Chat.from(rawMap(sink.request(Opcode.CHAT_MEMBERS_UPDATE, payload))["chat"])

    private suspend fun chatUpdate(payload: Map<String, Any?>): Chat? =
        Chat.from(rawMap(sink.request(Opcode.CHAT_UPDATE, payload))["chat"])

    private fun members(map: Map<*, *>): List<ChatMember> {
        val items = map["members"] as? List<*> ?: emptyList<Any?>()
        return items.map { item ->
            val m = item as? Map<*, *> ?: throw MalformedReplyException(Opcode.CHAT_MEMBERS, "member is not a map", map)
            val contact = m["contact"] as? Map<*, *> ?: throw MalformedReplyException(Opcode.CHAT_MEMBERS, "member without contact", map)
            ChatMember(contact["id"].asLong(), contact, m["presence"] as? Map<*, *>, m)
        }
    }

    private suspend fun chatList(opcode: Opcode, payload: Map<String, Any?>): List<Chat> {
        val map = replyMap(sink.request(opcode, payload), opcode)
        val items = map["chats"] ?: return emptyList()
        val list = items as? List<*> ?: throw MalformedReplyException(opcode, "chats is not a list", map)
        return list.map { Chat.from(it) ?: throw MalformedReplyException(opcode, "invalid chat in chats", map) }
    }
}

/** Result of [ChatsApi.createGroup]: the chat and the service message that created it. */
data class CreatedGroup(val chat: Chat, val message: MaxMessage)

/** Admin permission bits (PyMax `ChannelPermissions`). */
enum class ChatPermission(val bit: Int) {
    ADD_REMOVE_MEMBER(2), ADD_ADMIN(4), CHANGE_CHAT_INFO(8), PIN_MESSAGE(16), POST_MESSAGE(256), EDIT_MESSAGE(512), DELETE_MESSAGE(1024)
}

/**
 * Group options for [ChatsApi.updateSettings] (PyMax `ChangeGroupSettingsOptions`); only non-null
 * values are sent, in this key order.
 */
data class GroupSettings(
    val onlyOwnerCanChangeIconTitle: Boolean? = null,
    val allCanPinMessage: Boolean? = null,
    val onlyAdminCanAddMember: Boolean? = null,
    val onlyAdminCanCall: Boolean? = null,
    val membersCanSeePrivateLink: Boolean? = null,
) {
    fun toPayload(): Map<String, Any?> = buildMap {
        onlyOwnerCanChangeIconTitle?.let { put("ONLY_OWNER_CAN_CHANGE_ICON_TITLE", it) }
        allCanPinMessage?.let { put("ALL_CAN_PIN_MESSAGE", it) }
        onlyAdminCanAddMember?.let { put("ONLY_ADMIN_CAN_ADD_MEMBER", it) }
        onlyAdminCanCall?.let { put("ONLY_ADMIN_CAN_CALL", it) }
        membersCanSeePrivateLink?.let { put("MEMBERS_CAN_SEE_PRIVATE_LINK", it) }
    }
}

/** The `join/...` tail of an invite link (PyMax `_process_chat_join_link`), or `null`. */
fun joinPath(link: String): String? = link.indexOf("join/").takeIf { it >= 0 }?.let { link.substring(it) }
