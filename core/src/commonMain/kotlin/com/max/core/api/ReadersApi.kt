package com.max.core.api

import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of [ReadersApi.loadMessageReaders].
 *
 * @property chat the fresh `CHAT_INFO` chat (its `participants` marks), for the caller's store.
 * @property available [MessageReaders.isAvailable] for [chat]; when `false`, [readers] is empty
 *   and nothing but `CHAT_INFO` was asked.
 * @property message the message the readers are for (from the caller, the chat's `lastMessage` or
 *   `MSG_GET`); `null` when the chat does not show readers.
 */
data class MessageReadersResult(
    val chat: Chat,
    val available: Boolean,
    val message: MaxMessage?,
    val readers: List<MessageReader>,
)

/**
 * "Who read this message" ([MessageReaders]) over the chat and message requests. There is no
 * dedicated request; one call asks:
 * 1. `CHAT_INFO` 48 for fresh `participants` read marks (opening the readers screen always
 *    refreshes them);
 * 2. `MSG_GET` 71 for the message time and author, unless the caller passed the message or it is
 *    the chat's `lastMessage`;
 * 3. `CHAT_MEMBERS` 59 pages only when `participants` lists fewer users than
 *    `participantsCount`, for the missing members' `readMark`;
 * 4. `MSG_GET_DETAILED_REACTIONS` 181 `{chatId, messageId, count: 100}`.
 *
 * Steps 3 and 4 are best effort: a failure leaves the readers known so far (for 181: readers
 * without reactions) and no error. Cancellation is always passed on.
 */
class ReadersApi(private val chats: ChatsApi, private val messages: MessagesApi) {
    /**
     * Readers of [messageId] in [chatId] ([MessageReaders.build]) without [me] and the author.
     * For a chat that does not show readers ([MessageReaders.isAvailable] with [maxReadmarks])
     * the list is empty.
     *
     * @param liveMarks read marks of the chat from `NOTIF_MARK` pushes (`MaxState.readMarks`),
     *   merged with the server's marks; the later mark wins.
     * @param message the message when the caller already has it (its `time` and `sender` are
     *   used); one with another id is ignored.
     * @throws ApiException when the chat is not in the `CHAT_INFO` reply, or the message is not
     *   found in an available chat.
     */
    suspend fun loadMessageReaders(
        chatId: Long,
        messageId: Long,
        me: Long?,
        maxReadmarks: Int = AccountConfig.DEFAULT_MAX_READMARKS,
        liveMarks: Map<Long, Long> = emptyMap(),
        message: MaxMessage? = null,
    ): MessageReadersResult {
        val chat = chats.getChat(chatId)
        if (!MessageReaders.isAvailable(chat, maxReadmarks)) return MessageReadersResult(chat, false, null, emptyList())
        val target = message?.takeIf { it.id == messageId }
            ?: chat.lastMessage?.takeIf { it.id == messageId }
            ?: messages.getMessages(chatId, listOf(messageId)).firstOrNull { it.id == messageId }
            ?: throw ApiException("message $messageId not found in chat $chatId")
        var marks = MessageReaders.mergeMarks(chat.participants, liveMarks)
        if (chat.participantsCount > chat.participants.size) marks = MessageReaders.mergeMarks(marks, memberMarks(chat))
        val reactions = bestEffort(emptyList()) { messages.getDetailedReactions(chatId, messageId, MessageReaders.REACTIONS_COUNT) }
        val readers = MessageReaders.build(target.time, target.sender, me, marks, reactions)
        return MessageReadersResult(chat, true, target, readers)
    }

    /**
     * `readMark` of the members on `CHAT_MEMBERS` pages, walked until every member was seen, a
     * page is empty, the next marker is `0` or repeats, or the page cap. A failed page keeps the
     * marks of the pages before it.
     */
    private suspend fun memberMarks(chat: Chat): Map<Long, Long> {
        val out = LinkedHashMap<Long, Long>()
        val seen = HashSet<Long>()
        var marker = 0L
        val pages = (chat.participantsCount + MEMBERS_PAGE - 1) / MEMBERS_PAGE + 1
        repeat(pages) {
            val page = bestEffort(null) { chats.getChatMembers(chat.id, marker, MEMBERS_PAGE) } ?: return out
            for (member in page.members) {
                val user = member.userId ?: continue
                seen += user
                member.readMark?.let { out[user] = it }
            }
            if (page.members.isEmpty() || seen.size >= chat.participantsCount || page.marker == 0L || page.marker == marker) return out
            marker = page.marker
        }
        return out
    }

    private suspend fun <T> bestEffort(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    private companion object {
        /** Members per `CHAT_MEMBERS` page (the PyMax default). */
        const val MEMBERS_PAGE = 50
    }
}
