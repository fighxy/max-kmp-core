package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode

/**
 * Server search across the account, following the request shapes the KometTeam/Komet client uses.
 *
 * - [searchPublic]: public chats, channels and people by name or link (`PUBLIC_SEARCH`, 60).
 * - [searchMessages]: messages in all of the user's chats (`CHAT_SEARCH`, 68, without `chatId`).
 * - [searchInChat]: messages of one chat (`MSG_SEARCH`, 73).
 *
 * An empty or blank query sends nothing and returns an empty list. Errors as in [MessagesApi],
 * except [searchInChat]: a missing or non-list `result` is an empty page, as Komet reads it.
 */
class SearchApi(private val sink: RequestSink) {
    /**
     * `PUBLIC_SEARCH` (60): `{query, from, count, type}` with `type = "ALL"`. Reply:
     * `result: [{chat: {...}} | {user: {...}} | {contact: {...}}]`; entries of another shape are skipped.
     * [from] pages through the results.
     */
    suspend fun searchPublic(query: String, from: Int = 0, count: Int = PUBLIC_PAGE_SIZE, type: String = "ALL"): List<PublicSearchHit> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val payload = linkedMapOf<String, Any?>("query" to term, "from" to from, "count" to count, "type" to type)
        val map = replyMap(sink.request(Opcode.PUBLIC_SEARCH, payload), Opcode.PUBLIC_SEARCH)
        return results(map, Opcode.PUBLIC_SEARCH).mapNotNull(PublicSearchHit::from)
    }

    /**
     * `CHAT_SEARCH` (68) over every chat: `{query, count}`. Reply: `result: [{chatId, message}]`;
     * entries without a chat id (or with `0`) or without a parseable message are skipped.
     */
    suspend fun searchMessages(query: String, count: Int = MESSAGES_PAGE_SIZE): List<MessageSearchHit> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val map = replyMap(sink.request(Opcode.CHAT_SEARCH, linkedMapOf<String, Any?>("query" to term, "count" to count)), Opcode.CHAT_SEARCH)
        return results(map, Opcode.CHAT_SEARCH).mapNotNull(MessageSearchHit::from)
    }

    /**
     * `MSG_SEARCH` (73) inside one chat, as KometTeam/Komet `MessagesModule.searchMessages`:
     * `{chatId, query, count}` (count [IN_CHAT_PAGE_SIZE]). Reply `result` is a list of maps.
     * A map is a hit `{chatId, message}` or a message map (`id` required; missing `type` / `time`
     * are filled). Anything else is skipped. A missing or non-list `result` is empty.
     */
    suspend fun searchInChat(chatId: Long, query: String, count: Int = IN_CHAT_PAGE_SIZE): List<MessageSearchHit> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        require(count > 0) { "count must be positive" }
        val payload = linkedMapOf<String, Any?>("chatId" to chatId, "query" to term, "count" to count)
        val map = replyMap(sink.request(Opcode.MSG_SEARCH, payload), Opcode.MSG_SEARCH)
        val items = map["result"] ?: return emptyList()
        val list = items as? List<*> ?: return emptyList()
        return list.mapNotNull { inChatHit(it, chatId) }
    }

    private fun inChatHit(value: Any?, chatId: Long): MessageSearchHit? {
        MessageSearchHit.from(value)?.let { return it }
        val body = value as? Map<*, *> ?: return null
        val id = body["chatId"].asLong()?.takeIf { it != 0L } ?: chatId
        return MessageSearchHit.from(linkedMapOf("chatId" to id, "message" to body))
    }

    private fun results(map: Map<*, *>, opcode: Opcode): List<Any?> {
        val items = map["result"] ?: return emptyList()
        return items as? List<*> ?: throw MalformedReplyException(opcode, "result is not a list", map)
    }

    companion object {
        /** First page of [searchPublic]; later pages usually ask for more. */
        const val PUBLIC_PAGE_SIZE = 20
        const val MESSAGES_PAGE_SIZE = 50

        /** First page of [searchInChat] (Komet `searchMessages` default). */
        const val IN_CHAT_PAGE_SIZE = 30
    }
}

/**
 * One [SearchApi.searchPublic] result: a chat or channel ([chat]) or a person ([user]).
 *
 * @property iconUrl the chat's `baseIconUrl`, if any.
 * @property link the public link name (without `@`), if any.
 */
data class PublicSearchHit(val chat: Chat?, val user: MaxUser?, val raw: Map<*, *>) {
    val iconUrl: String? get() = (chat?.raw?.get("baseIconUrl") as? String)?.takeIf { it.isNotBlank() }
    val link: String? get() = ((chat?.raw?.get("link") ?: user?.link) as? String)?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }

    companion object {
        fun from(value: Any?): PublicSearchHit? {
            val m = value as? Map<*, *> ?: return null
            val chat = Chat.from(m["chat"])
            val user = if (chat == null) MaxUser.from(m["user"] ?: m["contact"]) else null
            if (chat == null && user == null) return null
            return PublicSearchHit(chat, user, m)
        }
    }
}

/** One [SearchApi.searchMessages] result: the message (`type` defaults to `USER`, `time` to 0) and its chat. */
data class MessageSearchHit(val chatId: Long, val message: MaxMessage, val raw: Map<*, *>) {
    companion object {
        fun from(value: Any?): MessageSearchHit? {
            val m = value as? Map<*, *> ?: return null
            val chatId = m["chatId"].asLong()?.takeIf { it != 0L } ?: return null
            val body = m["message"] as? Map<*, *> ?: return null
            // Search replies may leave out `type` and `time`, which a full message requires.
            val filled = LinkedHashMap<Any?, Any?>(body).apply {
                if (this["type"] == null) put("type", "USER")
                if (this["time"] == null) put("time", 0L)
            }
            val message = MaxMessage.from(filled, chatId) ?: return null
            return MessageSearchHit(chatId, message, m)
        }
    }
}
