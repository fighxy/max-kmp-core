package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.epochMillis
import com.max.core.protocol.Opcode

/**
 * Chat requests over a [RequestSink], following PyMax `src/pymax/api/chats/service.py`
 * (`ChatService`) and `payloads.py`; kolibri only has the opcode numbers. PyMax's chat cache is
 * not reproduced: every call goes to the server. Group management (create, invite, remove,
 * admins, settings, join links) is not covered.
 *
 * Errors as in [MessagesApi].
 */
class ChatsApi(
    private val sink: RequestSink,
    private val clock: () -> Long = ::epochMillis,
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
        val items = map["members"] as? List<*> ?: emptyList<Any?>()
        val members = items.map { item ->
            val m = item as? Map<*, *> ?: throw MalformedReplyException(Opcode.CHAT_MEMBERS, "member is not a map", map)
            val contact = m["contact"] as? Map<*, *> ?: throw MalformedReplyException(Opcode.CHAT_MEMBERS, "member without contact", map)
            ChatMember(contact["id"].asLong(), contact, m["presence"] as? Map<*, *>, m)
        }
        return ChatMembersPage(members, map["marker"].asLong() ?: 0, map)
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

    private suspend fun chatList(opcode: Opcode, payload: Map<String, Any?>): List<Chat> {
        val map = replyMap(sink.request(opcode, payload), opcode)
        val items = map["chats"] ?: return emptyList()
        val list = items as? List<*> ?: throw MalformedReplyException(opcode, "chats is not a list", map)
        return list.map { Chat.from(it) ?: throw MalformedReplyException(opcode, "invalid chat in chats", map) }
    }
}
