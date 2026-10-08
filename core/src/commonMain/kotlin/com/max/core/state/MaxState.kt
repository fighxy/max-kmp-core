package com.max.core.state

import com.max.core.api.Chat
import com.max.core.api.ChatFolders
import com.max.core.api.FolderList
import com.max.core.api.FolderUpdate
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.PresenceInfo
import com.max.core.api.ReactionInfo
import com.max.core.api.TypingType
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
 * @property contactIds ids of the account's contact list (`contacts` of the `LOGIN` reply,
 *   without the own profile). Their profiles are in [users].
 * @property presence last presence per user id.
 * @property typing per chat id: user id → local clock time (ms) of the last `NOTIF_TYPING`.
 *   The protocol has no "stopped typing" push; see [typingUsers].
 * @property readMarks per chat id: user id → last read `mark` (`NOTIF_MARK` 130).
 * @property gapAnchors chat id → newest local message id when a hole was noticed (`lastMessage`
 *   moved ahead of the loaded tail). Cleared only when a history page contains that id
 *   ([StateReducer.putHistoryPage]), or when the server has nothing older
 *   ([MaxStore.closeHistoryGap]); edits, pushes and single inserts of the anchor keep it.
 * @property chatFolders the account's chat folders (with the pinned chats), `null` until a `LOGIN`
 *   config, a `FOLDERS_GET` reply or a `NOTIF_FOLDERS` push brought them.
 * @property typingTypes per chat id: user id → effective `type` of the last `NOTIF_TYPING`
 *   ([MaxEvent.Typing.effectiveType]: a `com.max.core.api.TypingType` value, `TEXT` when the push
 *   had none or an unrecognised one). Kept beside [typing] (same keys) so [typing] keeps its shape;
 *   read it through [typingUsersWithType] or [typingType], which apply the same TTL as [typingUsers].
 */
data class MaxState(
    val me: Long? = null,
    val chats: Map<Long, Chat> = emptyMap(),
    val messages: Map<Long, List<MaxMessage>> = emptyMap(),
    val users: Map<Long, MaxUser> = emptyMap(),
    val contactIds: Set<Long> = emptySet(),
    val presence: Map<Long, PresenceInfo> = emptyMap(),
    val typing: Map<Long, Map<Long, Long>> = emptyMap(),
    val readMarks: Map<Long, Map<Long, Long>> = emptyMap(),
    val gapAnchors: Map<Long, Long> = emptyMap(),
    val chatFolders: ChatFolders? = null,
    val typingTypes: Map<Long, Map<Long, String>> = emptyMap(),
) {
    /**
     * Pinned chat ids, top first, as the server keeps them (`favorites` of the "all chats" folder,
     * see [ChatFolders]). `null` while unknown: no folder list yet, or no "all chats" folder in it.
     * May name chats that are not in [chats] yet.
     */
    val pinnedChatIds: List<Long>?
        get() = chatFolders?.pinnedChatIds

    /**
     * Chats ordered like a chat list: pinned chats first in the server's order ([pinnedChatIds]),
     * then latest activity first (`lastEventTime`, then last message time).
     */
    val chatList: List<Chat>
        get() {
            val pins = pinnedChatIds.orEmpty().withIndex().associate { (i, id) -> id to i }
            return chats.values.sortedWith(
                compareBy<Chat> { pins[it.id] ?: Int.MAX_VALUE }.thenByDescending { activity(it) }.thenByDescending { it.id },
            )
        }

    /** Messages of [chatId] (empty when unknown). */
    fun messagesOf(chatId: Long): List<MaxMessage> = messages[chatId].orEmpty()

    /**
     * Users typing in [chatId] whose last `NOTIF_TYPING` is at most [ttlMs] old at [now]. The TTL is
     * a client-side heuristic (the protocol has no "stopped typing" push); the default is 8 s
     * ([DEFAULT_TYPING_TTL_MS]), above the 6 s at which clients repeat `MSG_TYPING`.
     */
    fun typingUsers(chatId: Long, now: Long, ttlMs: Long = DEFAULT_TYPING_TTL_MS): Set<Long> =
        typing[chatId].orEmpty().filterValues { now - it <= ttlMs }.keys

    /**
     * The users of [typingUsers] (same [ttlMs] rule) with the effective `type` of their last
     * `NOTIF_TYPING` (`TEXT` when it had none).
     */
    fun typingUsersWithType(chatId: Long, now: Long, ttlMs: Long = DEFAULT_TYPING_TTL_MS): Map<Long, String> {
        val types = typingTypes[chatId].orEmpty()
        return typing[chatId].orEmpty().filterValues { now - it <= ttlMs }.mapValues { (user, _) -> types[user] ?: TypingType.TEXT }
    }

    /**
     * Effective `type` of [userId]'s last `NOTIF_TYPING` in [chatId] while the user still counts
     * as typing ([typingUsers]); `null` when the user is not typing.
     */
    fun typingType(chatId: Long, userId: Long, now: Long, ttlMs: Long = DEFAULT_TYPING_TTL_MS): String? {
        val at = typing[chatId]?.get(userId) ?: return null
        return if (now - at <= ttlMs) typingTypes[chatId]?.get(userId) ?: TypingType.TEXT else null
    }

    /**
     * Chats with an open history hole. The hole stays open until a `CHAT_HISTORY` page contains
     * the anchor id, or the server returns an empty page. Chats that had no loaded messages when
     * `lastMessage` moved on are not listed.
     */
    fun historyGaps(): List<Long> = gapAnchors.keys.toList()

    companion object {
        const val DEFAULT_TYPING_TTL_MS: Long = 8_000
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
 * - [MaxEvent.Typing] — records `now` for (chat, user), and its effective `type`
 *   ([MaxEvent.Typing.effectiveType]) in [MaxState.typingTypes].
 * - [MaxEvent.MessageRead] — records the mark. For [MaxState.me]: `setAsUnread = false` gives 0
 *   if the last message is not newer than the mark; otherwise it recounts `newMessages` as the
 *   stored messages from others newer than the mark, but only when the stored messages cover
 *   everything after the mark (no hole, last message stored, oldest stored message not newer than
 *   the mark). With an incomplete cache the server's counter is kept (at least the stored count).
 *   `setAsUnread = true` makes it at least 1.
 * - [MaxEvent.Presence] — replaces the user's presence (a push without `status` clears it).
 * - [MaxEvent.ReactionsChanged] — replaces counters / total of the stored message. The own
 *   `yourReaction` is taken from the push when it has one, otherwise the stored one is kept while
 *   its counter is still in the push.
 * - [MaxEvent.FoldersChanged] — merged into [MaxState.chatFolders] ([ChatFolders.merge]); this is
 *   how pins made on another device arrive.
 * - everything else (attachment signals, calls, unknown pushes) — no change.
 */
object StateReducer {

    fun reduce(state: MaxState, event: MaxEvent, now: Long, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState = when (event) {
        is MaxEvent.NewMessage -> newMessage(state, event.message, messageLimit)
        is MaxEvent.MessageEdited -> edited(state, event.message, messageLimit)
        is MaxEvent.MessagesDeleted -> deleted(state, event)
        is MaxEvent.ChatUpdated -> putChat(state, event.chat)
        is MaxEvent.Typing -> state.copy(
            typing = state.typing.put2(event.chatId, event.userId, now),
            typingTypes = state.typingTypes.set2(event.chatId, event.userId, event.effectiveType),
        )
        is MaxEvent.MessageRead -> read(state, event)
        is MaxEvent.Presence -> state.copy(presence = state.presence + (event.userId to PresenceInfo(event.seen, event.status)))
        is MaxEvent.ReactionsChanged -> reactions(state, event)
        is MaxEvent.FoldersChanged -> state.copy(
            chatFolders = (state.chatFolders ?: ChatFolders(emptyList())).merge(event.folders, event.foldersOrder, event.folderSync),
        )
        is MaxEvent.AttachmentReady, is MaxEvent.CallStart, is MaxEvent.StoriesUpdated, is MaxEvent.Unknown -> state
    }

    /**
     * Seeds the state from a `LOGIN` reply (PyMax `App.start`): `me`, `chats`, `contacts`
     * (→ [MaxState.users], including the own profile contact, and [MaxState.contactIds]) and `messages` (`{chatId: [message]}`,
     * keys may be integers or decimal strings). A different user replaces the snapshot; the same
     * user is merged, then holes against `lastMessage` are recorded. `config.chatFolders` replaces
     * [MaxState.chatFolders] when present ([ChatFolders.fromLoginConfig]); a reply without it keeps
     * the known folders.
     */
    fun login(state: MaxState, result: LoginResult, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState {
        val base = if (result.userId != null && state.me != null && result.userId != state.me) MaxState() else state
        var s = base.copy(me = result.userId ?: base.me)
        ChatFolders.fromLoginConfig(result.raw)?.let { s = s.copy(chatFolders = it) }
        s = putChats(s, result.chats.mapNotNull(Chat::from))
        val contacts = (result.raw["contacts"] as? List<*>)?.mapNotNull(MaxUser::from)
        val users = contacts.orEmpty() + listOfNotNull(MaxUser.from(result.profile?.get("contact")))
        s = putUsers(s, users)
        // A delta LOGIN may omit `contacts`; then the known list stays.
        if (contacts != null) s = s.copy(contactIds = contacts.map { it.id }.filter { it != s.me }.toSet())
        val byChat = result.raw["messages"] as? Map<*, *>
        byChat?.forEach { (k, v) ->
            val chatId = k.asLong() ?: return@forEach
            val list = (v as? List<*>).orEmpty().mapNotNull { MaxMessage.from(it, chatId) }
            s = putMessages(s, chatId, list, messageLimit)
        }
        return openHistoryGaps(s)
    }

    /**
     * Adds / replaces chats (a chat without `lastMessage` keeps the stored one) in one pass: one
     * copy of the chat map, and the hole check runs only for the chats in [chats] (the messages
     * do not change here, so no other chat can gain a hole). The result is the same as putting
     * the chats one by one with [putChat].
     */
    fun putChats(state: MaxState, chats: List<Chat>): MaxState {
        if (chats.isEmpty()) return state
        val merged = LinkedHashMap(state.chats)
        var anchors = state.gapAnchors
        for (chat in chats) {
            val old = merged[chat.id]
            val next = if (chat.lastMessage == null && old?.lastMessage != null) chat.copy(lastMessage = old.lastMessage) else chat
            merged[chat.id] = next
            if (chat.id !in anchors) {
                holeAnchor(next, state.messages[chat.id])?.let { anchors = anchors + (chat.id to it) }
            }
        }
        return state.copy(chats = merged, gapAnchors = anchors)
    }

    fun putChat(state: MaxState, chat: Chat): MaxState = putChats(state, listOf(chat))

    /** A full folder list (`FOLDERS_GET` reply): replaces [MaxState.chatFolders]. */
    fun putFolders(state: MaxState, list: FolderList): MaxState = state.copy(chatFolders = ChatFolders.from(list))

    /**
     * A `FOLDERS_UPDATE` reply that set the pinned chats to [pinned]: its `folder` is merged in
     * ([ChatFolders.merge]). A reply without `folder` still means the server accepted the request,
     * so the "all chats" folder then gets [pinned] locally.
     */
    fun putPinnedUpdate(state: MaxState, update: FolderUpdate, pinned: List<Long>): MaxState {
        val current = state.chatFolders ?: ChatFolders(emptyList())
        val order = update.foldersOrder.takeIf { "foldersOrder" in update.raw }
        val sync = update.folderSync.takeIf { "folderSync" in update.raw }
        val next = update.folder?.let { current.merge(listOf(it), order, sync) }
            ?: current.withPinned(pinned).merge(emptyList(), order, sync)
        return state.copy(chatFolders = next)
    }

    /** A `FOLDERS_UPDATE` reply: its `folder` (and `foldersOrder`, `folderSync` when sent) merged in. */
    fun putFolderUpdate(state: MaxState, update: FolderUpdate): MaxState {
        val current = state.chatFolders ?: ChatFolders(emptyList())
        val order = update.foldersOrder.takeIf { "foldersOrder" in update.raw }
        val sync = update.folderSync.takeIf { "folderSync" in update.raw }
        return state.copy(chatFolders = current.merge(listOfNotNull(update.folder), order, sync))
    }

    /** An accepted `FOLDERS_DELETE`: the folders [ids] are gone, whatever the reply says about the rest. */
    fun removeFolders(state: MaxState, ids: List<String>, update: FolderUpdate): MaxState {
        val current = (state.chatFolders ?: return state).without(ids)
        val order = update.foldersOrder.takeIf { "foldersOrder" in update.raw }?.filter { it !in ids }
        val sync = update.folderSync.takeIf { "folderSync" in update.raw }
        return state.copy(chatFolders = current.merge(emptyList(), order, sync))
    }

    /** An accepted `FOLDERS_REORDER`: the folders sorted by [order] (the reply's order wins when sent). */
    fun reorderFolders(state: MaxState, order: List<String>, update: FolderUpdate): MaxState {
        val current = state.chatFolders ?: return state
        val newOrder = update.foldersOrder.takeIf { "foldersOrder" in update.raw && it.isNotEmpty() } ?: order
        val sync = update.folderSync.takeIf { "folderSync" in update.raw }
        val known = current.folders.map { it.id }.toSet()
        // folders the order does not name stay (at the end): a reorder never deletes
        val full = newOrder + known.filter { it !in newOrder }
        return state.copy(chatFolders = current.merge(emptyList(), full, sync))
    }

    /** Removes a chat and its messages, typing, read marks and history hole (after leaving / deleting it). */
    fun removeChat(state: MaxState, chatId: Long): MaxState = state.copy(
        chats = state.chats - chatId,
        messages = state.messages - chatId,
        typing = state.typing - chatId,
        typingTypes = state.typingTypes - chatId,
        readMarks = state.readMarks - chatId,
        gapAnchors = state.gapAnchors - chatId,
    )

    fun putUsers(state: MaxState, users: List<MaxUser>): MaxState =
        if (users.isEmpty()) state else state.copy(users = state.users + users.associateBy { it.id })

    /** Users that belong to the contact list (the opcode-8 reply): profiles plus [MaxState.contactIds]. */
    fun putContacts(state: MaxState, contacts: List<MaxUser>): MaxState {
        if (contacts.isEmpty()) return state
        val withUsers = putUsers(state, contacts)
        return withUsers.copy(contactIds = withUsers.contactIds + contacts.map { it.id }.filter { it != withUsers.me })
    }

    /**
     * Merges [list] into the chat's messages (insert or replace by id). It never closes a history
     * hole: an edit, a replayed push or a single inserted message that happens to be the anchor
     * says nothing about the messages in between. Only [putHistoryPage] does.
     */
    fun putMessages(state: MaxState, chatId: Long, list: List<MaxMessage>, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState {
        if (list.isEmpty()) return state
        val byId = LinkedHashMap<Long, MaxMessage>()
        state.messagesOf(chatId).forEach { byId[it.id] = it }
        list.forEach { byId[it.id] = it }
        return state.copy(messages = state.messages + (chatId to sortAndTrim(byId.values, messageLimit)))
    }

    /**
     * A `CHAT_HISTORY` page: [putMessages], and the page closes the chat's hole when it contains
     * the gap anchor, i.e. it is contiguous history that overlaps the loaded tail. The check uses
     * [page], not the trimmed tail.
     */
    fun putHistoryPage(state: MaxState, chatId: Long, page: List<MaxMessage>, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState {
        val s = putMessages(state, chatId, page, messageLimit)
        val anchor = s.gapAnchors[chatId] ?: return s
        return if (page.any { it.id == anchor }) s.copy(gapAnchors = s.gapAnchors - chatId) else s
    }

    /**
     * A message this client sent, as confirmed by the server (`MSG_SEND` reply). Own sends are not
     * pushed back, so besides [putMessages] the chat's `lastMessage` / `lastEventTime` move to it
     * when it is not older than the current last message; `newMessages` is left unchanged.
     */
    fun putSentMessage(state: MaxState, chatId: Long, m: MaxMessage, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState {
        val s = putMessages(state, chatId, listOf(m), messageLimit)
        val chat = s.chats[chatId] ?: return s
        val prev = chat.lastMessage
        if (prev != null && order(m, prev) < 0) return s
        return s.copy(chats = s.chats + (chatId to chat.copy(lastMessage = m, lastEventTime = maxOf(chat.lastEventTime, m.time))))
    }

    /**
     * Records a hole for each chat whose `lastMessage` is ahead of the loaded tail and is not
     * itself loaded. An existing anchor stays: it is the tail id from when the hole was noticed.
     * Chats with no loaded messages are skipped.
     */
    private fun openHistoryGaps(state: MaxState): MaxState {
        var anchors = state.gapAnchors
        var changed = false
        for (chat in state.chats.values) {
            if (chat.id in anchors) continue
            val anchor = holeAnchor(chat, state.messages[chat.id]) ?: continue
            anchors = anchors + (chat.id to anchor)
            changed = true
        }
        return if (changed) state.copy(gapAnchors = anchors) else state
    }

    /**
     * The anchor (local tail id) when [chat]'s `lastMessage` is ahead of the stored [local]
     * messages and not stored itself, else `null`. [local] is sorted by (`time`, `id`), so a stored
     * copy of the last message normally sits in the tail entries not older than it: those are
     * checked first and the full scan runs only when that finds nothing (a hole, or a stored copy
     * with another time).
     */
    private fun holeAnchor(chat: Chat, local: List<MaxMessage>?): Long? {
        val last = chat.lastMessage ?: return null
        if (local.isNullOrEmpty()) return null
        val tail = local.last()
        if (last.time < tail.time) return null
        for (i in local.indices.reversed()) {
            val m = local[i]
            if (m.time < last.time) break
            if (m.id == last.id) return null
        }
        return if (local.any { it.id == last.id }) null else tail.id
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
        if (sender != null && s.typingTypes[chatId]?.containsKey(sender) == true) {
            s = s.copy(typingTypes = s.typingTypes.set2(chatId, sender, null))
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
        val hadAnchor = e.chatId in state.gapAnchors
        e.chat?.let { s = putChat(s, it) }
        val chat = s.chats[e.chatId]
        if (chat != null && chat.lastMessage?.id in ids) {
            s = s.copy(chats = s.chats + (e.chatId to chat.copy(lastMessage = remaining.lastOrNull())))
        }
        // putChat may have opened a hole against the chat in the push; the fallback last message
        // can already be in the remaining list, and that hole is not real.
        if (!hadAnchor && e.chatId in s.gapAnchors && !holeStillOpen(s, e.chatId)) {
            s = s.copy(gapAnchors = s.gapAnchors - e.chatId)
        }
        return s
    }

    private fun holeStillOpen(state: MaxState, chatId: Long): Boolean {
        val last = state.chats[chatId]?.lastMessage ?: return false
        val local = state.messages[chatId]
        if (local.isNullOrEmpty()) return false
        return local.none { it.id == last.id } && last.time >= local.last().time
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
                val cached = s.messagesOf(e.chatId)
                val counted = cached.count { it.time > e.mark && (it.sender == null || it.sender != s.me) }
                // a recount is exact only if the cache holds every message after the mark; otherwise
                // keep the server's counter (the cache is bounded by messageLimit and may be empty)
                if (coversSince(s, e.chatId, cached, e.mark)) counted else maxOf(chat.newMessages, counted)
            }
            s = s.copy(chats = s.chats + (e.chatId to chat.copy(newMessages = unread)))
        }
        return s
    }

    /**
     * `true` when [cached] (the chat's stored messages) holds every message newer than [mark]:
     * no open hole, the chat's `lastMessage` is stored, and the oldest stored message is not
     * newer than [mark] (nothing after the mark was trimmed or never loaded).
     */
    private fun coversSince(state: MaxState, chatId: Long, cached: List<MaxMessage>, mark: Long): Boolean {
        if (cached.isEmpty() || chatId in state.gapAnchors) return false
        val last = state.chats[chatId]?.lastMessage ?: return false
        if (cached.last().id != last.id && cached.none { it.id == last.id }) return false
        return cached.first().time <= mark
    }

    private fun reactions(state: MaxState, e: MaxEvent.ReactionsChanged): MaxState {
        val id = e.messageId.toLongOrNull() ?: return state
        val old = state.messages[e.chatId]?.firstOrNull { it.id == id } ?: return state
        // The push carries only counters: keep the own reaction while its counter is still there.
        val mine = e.yourReaction ?: old.reactionInfo?.yourReaction?.takeIf { own -> e.counters.any { it.reaction == own && it.count > 0 } }
        val raw = LinkedHashMap<Any?, Any?>((e.raw as? Map<*, *>) ?: emptyMap<Any?, Any?>())
        if (mine != null) raw["yourReaction"] = mine
        return putReactions(state, e.chatId, id, ReactionInfo(e.totalCount, e.counters, mine, raw))
    }

    /**
     * Replaces the reactions of a stored message (and of the chat's `lastMessage` when it is that
     * message). `null` [info] means the message has no reactions left. An unknown message: no change.
     */
    fun putReactions(state: MaxState, chatId: Long, messageId: Long, info: ReactionInfo?): MaxState {
        val list = state.messages[chatId] ?: return state
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return state
        val kept = info?.takeIf { it.counters.isNotEmpty() }
        val updated = list.toMutableList().also { it[idx] = it[idx].copy(reactionInfo = kept) }
        var s = state.copy(messages = state.messages + (chatId to updated))
        val chat = s.chats[chatId]
        if (chat?.lastMessage?.id == messageId) s = s.copy(chats = s.chats + (chatId to chat.copy(lastMessage = updated[idx])))
        return s
    }

    private fun order(a: MaxMessage, b: MaxMessage): Int = compareValuesBy(a, b, { it.time }, { it.id })

    private fun sortAndTrim(values: Collection<MaxMessage>, limit: Int): List<MaxMessage> {
        val sorted = values.sortedWith(compareBy<MaxMessage>({ it.time }, { it.id }))
        return if (limit > 0 && sorted.size > limit) sorted.subList(sorted.size - limit, sorted.size).toList() else sorted
    }

    private fun Map<Long, Map<Long, Long>>.put2(a: Long, b: Long, v: Long): Map<Long, Map<Long, Long>> =
        this + (a to (this[a].orEmpty() + (b to v)))

    /** Sets (a, b) to [v], or removes it for `null` (dropping an emptied inner map). */
    private fun Map<Long, Map<Long, String>>.set2(a: Long, b: Long, v: String?): Map<Long, Map<Long, String>> {
        val inner = this[a].orEmpty()
        if (v == null) {
            if (b !in inner) return this
            val left = inner - b
            return if (left.isEmpty()) this - a else this + (a to left)
        }
        return this + (a to (inner + (b to v)))
    }
}
