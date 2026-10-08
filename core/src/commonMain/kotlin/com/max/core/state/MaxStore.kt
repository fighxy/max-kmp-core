package com.max.core.state

import com.max.core.api.Chat
import com.max.core.api.ChatFolders
import com.max.core.api.ChatHistory
import com.max.core.api.FolderList
import com.max.core.api.FolderUpdate
import com.max.core.api.MaxDraft
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.PhoneContact
import com.max.core.api.PresenceInfo
import com.max.core.api.ReactionInfo
import com.max.core.auth.LoginResult
import com.max.core.epochMillis
import com.max.core.events.MaxEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Thread-safe, in-memory holder of [MaxState]. Every mutation is an atomic
 * `MutableStateFlow.update` with a [StateReducer] function, so pushes and API results can be
 * applied from any coroutine.
 *
 * Feed it with [applyLogin] (or `TokenLogin.result`), [putChats] / [putHistory] after API calls,
 * and [apply] for each [MaxEvent] — `com.max.core.events.EventRouter` does the latter for a
 * session. Nothing is persisted.
 *
 * @param messageLimit messages kept per chat (oldest dropped first); `0` keeps everything.
 * @param clock local wall clock in ms, used for typing timestamps and presence refresh times.
 */
class MaxStore(
    val messageLimit: Int = DEFAULT_MESSAGE_LIMIT,
    private val clock: () -> Long = ::epochMillis,
    initial: MaxState = MaxState(),
) {
    private val _state = MutableStateFlow(initial)

    /** The current snapshot; collect it to observe every change. */
    val state: StateFlow<MaxState> = _state.asStateFlow()

    /** Chat list, latest activity first ([MaxState.chatList]). */
    val chatList: Flow<List<Chat>> get() = state.map { it.chatList }.distinctUntilChanged()

    /** Pinned chat ids, top first; `null` while unknown ([MaxState.pinnedChatIds]). */
    val pinnedChats: Flow<List<Long>?> get() = state.map { it.pinnedChatIds }.distinctUntilChanged()

    /** Chat folders; `null` while unknown ([MaxState.chatFolders]). */
    val chatFolders: Flow<ChatFolders?> get() = state.map { it.chatFolders }.distinctUntilChanged()

    /** Messages of one chat. */
    fun messages(chatId: Long): Flow<List<MaxMessage>> = state.map { it.messagesOf(chatId) }.distinctUntilChanged()

    /** Presence of one user (`null` until known). */
    fun presence(userId: Long): Flow<PresenceInfo?> = state.map { it.presence[userId] }.distinctUntilChanged()

    /** [MaxState.presenceAt] at [clock]: an "online" older than [ttlMs] reads as offline. */
    fun presenceAt(userId: Long, ttlMs: Long): PresenceInfo? = _state.value.presenceAt(userId, clock(), ttlMs)

    /** Presence entries from the server (`CONTACT_PRESENCE` 35, `CHAT_MEMBERS`) at [clock] ([StateReducer.putPresence]). */
    fun putPresence(entries: Map<Long, PresenceInfo>) = _state.update { StateReducer.putPresence(it, entries, clock()) }

    /**
     * Degrades `ONLINE` entries not refreshed for more than [ttlMs] at [clock]
     * ([StateReducer.expirePresence]). Returns the users that changed.
     */
    fun expirePresence(ttlMs: Long): List<Long> {
        val now = clock()
        var changed: List<Long> = emptyList()
        _state.update { s ->
            val next = StateReducer.expirePresence(s, now, ttlMs)
            changed = if (next === s) emptyList() else next.presence.filter { (id, p) -> s.presence[id] != p }.keys.toList()
            next
        }
        return changed
    }

    /** Sets the name rule ([MaxState.preferAddressBookNames]). */
    fun setPreferAddressBookNames(prefer: Boolean) = _state.update { StateReducer.setPreferAddressBookNames(it, prefer) }

    /**
     * Removes and returns the draft of [chatId] in one atomic step (`null` when there is none), so
     * of two concurrent callers only one gets it. The chat gets a discard mark at the draft's
     * `updateTime` (the time its `DRAFT_DISCARD` 177 carries).
     */
    fun takeDraft(chatId: Long): MaxDraft? {
        var taken: MaxDraft? = null
        _state.update { s ->
            taken = s.drafts[chatId]
            taken?.let { StateReducer.removeDraft(s, chatId, it.updateTime) } ?: s
        }
        return taken
    }

    /** Applies one push ([StateReducer.reduce]). */
    fun apply(event: MaxEvent) = _state.update { StateReducer.reduce(it, event, clock(), messageLimit) }

    /** Seeds from a `LOGIN` reply ([StateReducer.login], presence refresh times at [clock]). */
    fun applyLogin(result: LoginResult) {
        val now = clock()
        _state.update { StateReducer.login(it, result, messageLimit, now) }
    }

    fun putChats(chats: List<Chat>) = _state.update { StateReducer.putChats(it, chats) }

    /** A `FOLDERS_GET` reply ([StateReducer.putFolders]). */
    fun putFolders(list: FolderList) = _state.update { StateReducer.putFolders(it, list) }

    /** A `FOLDERS_UPDATE` reply for new pins ([StateReducer.putPinnedUpdate]). */
    fun putPinnedUpdate(update: FolderUpdate, pinned: List<Long>) = _state.update { StateReducer.putPinnedUpdate(it, update, pinned) }

    /** A `FOLDERS_UPDATE` reply for a created or changed folder ([StateReducer.putFolderUpdate]). */
    fun putFolderUpdate(update: FolderUpdate) = _state.update { StateReducer.putFolderUpdate(it, update) }

    /** Accepted `FOLDERS_DELETE` of [ids] ([StateReducer.removeFolders]). */
    fun removeFolders(ids: List<String>, update: FolderUpdate) = _state.update { StateReducer.removeFolders(it, ids, update) }

    /** Accepted `FOLDERS_REORDER` to [order] ([StateReducer.reorderFolders]). */
    fun reorderFolders(order: List<String>, update: FolderUpdate) = _state.update { StateReducer.reorderFolders(it, order, update) }

    fun putUsers(users: List<MaxUser>) = _state.update { StateReducer.putUsers(it, users) }

    /** Contact-list users ([StateReducer.putContacts]). */
    fun putContacts(contacts: List<MaxUser>) = _state.update { StateReducer.putContacts(it, contacts) }

    /** A removed contact ([StateReducer.removeContact]). */
    fun removeContact(userId: Long, reply: MaxUser? = null) = _state.update { StateReducer.removeContact(it, userId, reply) }

    /** Replaces the device address book ([StateReducer.setAddressBook]). */
    fun setAddressBook(entries: List<PhoneContact>) = _state.update { StateReducer.setAddressBook(it, entries) }

    /** Address-book name of one user ([StateReducer.setLocalName]); `null` clears it. */
    fun setLocalName(userId: Long, name: String?) = _state.update { StateReducer.setLocalName(it, userId, name) }

    /** An own edit confirmed by the server ([StateReducer.putEditedMessage]). */
    fun putEditedMessage(chatId: Long, message: MaxMessage) =
        _state.update { StateReducer.putEditedMessage(it, chatId, message, messageLimit) }

    /** A confirmed draft save ([StateReducer.putDraft]). */
    fun putDraft(draft: MaxDraft) = _state.update { StateReducer.putDraft(it, draft) }

    /**
     * A discarded or sent draft ([StateReducer.removeDraft]); with [time] (the discard's time)
     * the chat also gets a discard mark.
     */
    fun removeDraft(chatId: Long, time: Long? = null) = _state.update { StateReducer.removeDraft(it, chatId, time) }

    /** The resolved name of one user ([MaxState.displayName]). */
    fun displayName(userId: Long): Flow<String?> = state.map { it.displayName(userId) }.distinctUntilChanged()

    /** Inserts / replaces messages; never closes a history hole (use [putHistory] for pages). */
    fun putMessages(chatId: Long, messages: List<MaxMessage>) = _state.update { StateReducer.putMessages(it, chatId, messages, messageLimit) }

    /** A message this client sent ([StateReducer.putSentMessage]): also the chat's last message, unread unchanged. */
    fun putSentMessage(chatId: Long, message: MaxMessage) = _state.update { StateReducer.putSentMessage(it, chatId, message, messageLimit) }

    /** A `CHAT_HISTORY` result: its messages and, when requested with `getChat`, its chat. */
    fun putHistory(chatId: Long, history: ChatHistory) = _state.update { s ->
        val withChat = history.chat?.let { StateReducer.putChat(s, it) } ?: s
        StateReducer.putHistoryPage(withChat, chatId, history.messages, messageLimit)
    }

    /** Drops the history hole for [chatId]. Used when the server returns an empty page. */
    fun closeHistoryGap(chatId: Long) = _state.update { s ->
        if (chatId !in s.gapAnchors) s else s.copy(gapAnchors = s.gapAnchors - chatId)
    }

    fun removeChat(chatId: Long) = _state.update { StateReducer.removeChat(it, chatId) }

    /** Reactions of one stored message after an own change or a reload ([StateReducer.putReactions]). */
    fun putReactions(chatId: Long, messageId: Long, info: ReactionInfo?) =
        _state.update { StateReducer.putReactions(it, chatId, messageId, info) }

    /** Drops everything (e.g. on logout) except the name rule ([MaxState.preferAddressBookNames]). */
    fun clear() {
        _state.update { MaxState(preferAddressBookNames = it.preferAddressBookNames) }
    }

    companion object {
        const val DEFAULT_MESSAGE_LIMIT: Int = 500
    }
}
