package com.max.core.state

import com.max.core.api.Chat
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.PresenceInfo
import com.max.core.api.ReactionInfo
import com.max.core.api.asLong
import com.max.core.auth.LoginResult
import com.max.core.events.MaxEvent

/**
 * Immutable snapshot of the client-side state built from the `LOGIN` reply, API results and
 * pushes. PyMax keeps the same things as mutable fields of its `App` (`me`, `chats`, `users`,
 * `contacts`, `messages`, filled from `LoginResponse` in `App.start`); it does not apply pushes
 * to them. Applying events here is the client's own policy, see [reduce].
 *
 * @property me own user id (`profile.contact.id` of the `LOGIN` reply).
 * @property chats chats by id.
 * @property messages known messages per chat id, ascending by (`time`, `id`), no duplicate ids.
 * @property users users / contacts by id.
 * @property presence last presence per user id.
 * @property typing per chat id: user id → local clock time (ms) of the last `NOTIF_TYPING`.
 *   The protocol has no "stopped typing" push; see [typingUsers].
 * @property readMarks per chat id: user id → last read `mark` (`NOTIF_MARK` 130).
 */
data class MaxState(
    val me: Long? = null,
    val chats: Map<Long, Chat> = emptyMap(),
    val messages: Map<Long, List<MaxMessage>> = emptyMap(),
    val users: Map<Long, MaxUser> = emptyMap(),
    val presence: Map<Long, PresenceInfo> = emptyMap(),
    val typing: Map<Long, Map<Long, Long>> = emptyMap(),
    val readMarks: Map<Long, Map<Long, Long>> = emptyMap(),
) {
    /** Chats ordered like a chat list: latest activity first (`lastEventTime`, then last message time). */
    val chatList: List<Chat>
        get() = chats.values.sortedWith(compareByDescending<Chat> { activity(it) }.thenByDescending { it.id })

    /** Messages of [chatId] (empty when unknown). */
    fun messagesOf(chatId: Long): List<MaxMessage> = messages[chatId].orEmpty()

    /**
     * Users typing in [chatId] whose last `NOTIF_TYPING` is at most [ttlMs] old at [now]. The TTL is
     * a client-side heuristic (neither reference defines one); the default is 6 s.
     */
    fun typingUsers(chatId: Long, now: Long, ttlMs: Long = DEFAULT_TYPING_TTL_MS): Set<Long> =
        typing[chatId].orEmpty().filterValues { now - it <= ttlMs }.keys

    /**
     * Chats whose loaded messages end before the chat's `lastMessage` (e.g. after a reconnect the
     * `LOGIN` reply moved `lastMessage` on, but the messages in between were never pushed). Only
     * chats with loaded messages are listed: their history should be re-fetched
     * (`MessagesApi.getChatHistory` → `MaxStore.putHistory`).
     */
    fun historyGaps(): List<Long> = chats.values.filter { chat ->
        val last = chat.lastMessage ?: return@filter false
        val local = messages[chat.id]
        !local.isNullOrEmpty() && local.none { it.id == last.id } && last.time >= local.last().time
    }.map { it.id }

    companion object {
        const val DEFAULT_TYPING_TTL_MS: Long = 6_000
        private fun activity(c: Chat): Long = maxOf(c.lastEventTime, c.lastMessage?.time ?: 0)
    }
}

/**
 * Pure state transitions used by [MaxStore]. Every function returns a new [MaxState]; unknown
 * chats or messages are handled without throwing.
 *
 * Event rules ([reduce]):
 * - [MaxEvent.NewMessage] — inserted into its chat (replacing a message with the same id);
 *   a known chat gets `lastMessage` / `lastEventTime` updated when the message is not older, and
 *   `newMessages + 1` when it is newer than the previous last message and not sent by [MaxState.me].
 *   The sender stops "typing" in that chat. A message without `chatId` is ignored.
 * - [MaxEvent.MessageEdited] — replaces the stored message (inserted if unknown) and the chat's
 *   `lastMessage` if it is the same id.
 * - [MaxEvent.MessagesDeleted] — removes the ids; a chat sent along (`NOTIF_MSG_DELETE` 142)
 *   replaces the stored one; if the chat's `lastMessage` was deleted it falls back to the newest
 *   remaining stored message (or `null`).
 * - [MaxEvent.ChatUpdated] — replaces the chat, keeping the previous `lastMessage` when the push
 *   has none.
 * - [MaxEvent.Typing] — records `now` for (chat, user).
 * - [MaxEvent.MessageRead] — records the mark. For [MaxState.me]: `setAsUnread = false` recounts
 *   `newMessages` as the stored messages from others newer than the mark (0 if the last message is
 *   not newer); `setAsUnread = true` makes it at least 1.
 * - [MaxEvent.Presence] — replaces the user's presence (a push without `status` clears it).
 * - [MaxEvent.ReactionsChanged] — replaces counters / total of the stored message, keeping
 *   `yourReaction`.
 * - everything else (attachment signals, calls, unknown pushes) — no change.
 */
object StateReducer {

    fun reduce(state: MaxState, event: MaxEvent, now: Long, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState = when (event) {
        is MaxEvent.NewMessage -> newMessage(state, event.message, messageLimit)
        is MaxEvent.MessageEdited -> edited(state, event.message, messageLimit)
        is MaxEvent.MessagesDeleted -> deleted(state, event)
        is MaxEvent.ChatUpdated -> putChat(state, event.chat)
        is MaxEvent.Typing -> state.copy(typing = state.typing.put2(event.chatId, event.userId, now))
        is MaxEvent.MessageRead -> read(state, event)
        is MaxEvent.Presence -> state.copy(presence = state.presence + (event.userId to PresenceInfo(event.seen, event.status)))
        is MaxEvent.ReactionsChanged -> reactions(state, event)
        is MaxEvent.AttachmentReady, is MaxEvent.CallStart, is MaxEvent.Unknown -> state
    }

    /**
     * Seeds the state from a `LOGIN` reply (PyMax `App.start`): `me`, `chats`, `contacts`
     * (→ [MaxState.users], including the own profile contact) and `messages` (`{chatId: [message]}`,
     * keys may be integers or decimal strings).
     */
    fun login(state: MaxState, result: LoginResult, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState {
        var s = state.copy(me = result.userId ?: state.me)
        s = putChats(s, result.chats.mapNotNull(Chat::from))
        val users = (result.raw["contacts"] as? List<*>).orEmpty().mapNotNull(MaxUser::from) +
            listOfNotNull(MaxUser.from(result.profile?.get("contact")))
        s = putUsers(s, users)
        val byChat = result.raw["messages"] as? Map<*, *>
        byChat?.forEach { (k, v) ->
            val chatId = k.asLong() ?: return@forEach
            val list = (v as? List<*>).orEmpty().mapNotNull { MaxMessage.from(it, chatId) }
            s = putMessages(s, chatId, list, messageLimit)
        }
        return s
    }

    /** Adds / replaces chats (a chat without `lastMessage` keeps the stored one). */
    fun putChats(state: MaxState, chats: List<Chat>): MaxState = chats.fold(state, ::putChat)

    fun putChat(state: MaxState, chat: Chat): MaxState {
        val old = state.chats[chat.id]
        val merged = if (chat.lastMessage == null && old?.lastMessage != null) chat.copy(lastMessage = old.lastMessage) else chat
        return state.copy(chats = state.chats + (chat.id to merged))
    }

    /** Removes a chat and its messages, typing and read marks (after leaving / deleting it). */
    fun removeChat(state: MaxState, chatId: Long): MaxState = state.copy(
        chats = state.chats - chatId,
        messages = state.messages - chatId,
        typing = state.typing - chatId,
        readMarks = state.readMarks - chatId,
    )

    fun putUsers(state: MaxState, users: List<MaxUser>): MaxState =
        if (users.isEmpty()) state else state.copy(users = state.users + users.associateBy { it.id })

    /** Merges [list] (e.g. a `CHAT_HISTORY` page) into the chat's messages. */
    fun putMessages(state: MaxState, chatId: Long, list: List<MaxMessage>, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState {
        if (list.isEmpty()) return state
        val byId = LinkedHashMap<Long, MaxMessage>()
        state.messagesOf(chatId).forEach { byId[it.id] = it }
        list.forEach { byId[it.id] = it }
        return state.copy(messages = state.messages + (chatId to sortAndTrim(byId.values, messageLimit)))
    }

    private fun newMessage(state: MaxState, m: MaxMessage, limit: Int): MaxState {
        val chatId = m.chatId ?: return state
        var s = putMessages(state, chatId, listOf(m), limit)
        val chat = s.chats[chatId]
        if (chat != null) {
            val prev = chat.lastMessage
            val newer = prev == null || order(m, prev) > 0
            val notOlder = prev == null || order(m, prev) >= 0
            if (notOlder) {
                val fromOther = m.sender == null || m.sender != s.me
                s = s.copy(
                    chats = s.chats + (
                        chatId to chat.copy(
                            lastMessage = m,
                            lastEventTime = maxOf(chat.lastEventTime, m.time),
                            newMessages = if (newer && prev?.id != m.id && fromOther) chat.newMessages + 1 else chat.newMessages,
                        )
                    ),
                )
            }
        }
        val sender = m.sender
        if (sender != null && s.typing[chatId]?.containsKey(sender) == true) {
            val left = s.typing.getValue(chatId) - sender
            s = s.copy(typing = if (left.isEmpty()) s.typing - chatId else s.typing + (chatId to left))
        }
        return s
    }

    private fun edited(state: MaxState, m: MaxMessage, limit: Int): MaxState {
        val chatId = m.chatId ?: return state
        var s = putMessages(state, chatId, listOf(m), limit)
        val chat = s.chats[chatId]
        if (chat?.lastMessage?.id == m.id) s = s.copy(chats = s.chats + (chatId to chat.copy(lastMessage = m)))
        return s
    }

    private fun deleted(state: MaxState, e: MaxEvent.MessagesDeleted): MaxState {
        val ids = e.messageIds.toSet()
        val remaining = state.messagesOf(e.chatId).filterNot { it.id in ids }
        var s = state.copy(messages = if (remaining.isEmpty()) state.messages - e.chatId else state.messages + (e.chatId to remaining))
        e.chat?.let { s = putChat(s, it) }
        val chat = s.chats[e.chatId]
        if (chat != null && chat.lastMessage?.id in ids) {
            s = s.copy(chats = s.chats + (e.chatId to chat.copy(lastMessage = remaining.lastOrNull())))
        }
        return s
    }

    private fun read(state: MaxState, e: MaxEvent.MessageRead): MaxState {
        var s = state.copy(readMarks = state.readMarks.put2(e.chatId, e.userId, e.mark))
        val chat = s.chats[e.chatId]
        if (chat != null && e.userId == s.me) {
            val unread = if (e.setAsUnread) {
                maxOf(chat.newMessages, 1)
            } else if ((chat.lastMessage?.time ?: Long.MIN_VALUE) <= e.mark) {
                0
            } else {
                s.messagesOf(e.chatId).count { it.time > e.mark && (it.sender == null || it.sender != s.me) }
            }
            s = s.copy(chats = s.chats + (e.chatId to chat.copy(newMessages = unread)))
        }
        return s
    }

    private fun reactions(state: MaxState, e: MaxEvent.ReactionsChanged): MaxState {
        val id = e.messageId.toLongOrNull() ?: return state
        val list = state.messages[e.chatId] ?: return state
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return state
        val old = list[idx]
        val info = ReactionInfo(e.totalCount, e.counters, old.reactionInfo?.yourReaction, (e.raw as? Map<*, *>) ?: emptyMap<Any?, Any?>())
        val updated = list.toMutableList().also { it[idx] = old.copy(reactionInfo = info) }
        var s = state.copy(messages = state.messages + (e.chatId to updated))
        val chat = s.chats[e.chatId]
        if (chat?.lastMessage?.id == id) s = s.copy(chats = s.chats + (e.chatId to chat.copy(lastMessage = updated[idx])))
        return s
    }

    private fun order(a: MaxMessage, b: MaxMessage): Int = compareValuesBy(a, b, { it.time }, { it.id })

    private fun sortAndTrim(values: Collection<MaxMessage>, limit: Int): List<MaxMessage> {
        val sorted = values.sortedWith(compareBy<MaxMessage>({ it.time }, { it.id }))
        return if (limit > 0 && sorted.size > limit) sorted.subList(sorted.size - limit, sorted.size).toList() else sorted
    }

    private fun Map<Long, Map<Long, Long>>.put2(a: Long, b: Long, v: Long): Map<Long, Map<Long, Long>> =
        this + (a to (this[a].orEmpty() + (b to v)))
}
