package com.max.core.state

import com.max.core.api.Chat
import com.max.core.api.ChatFolders
import com.max.core.api.ContactNames
import com.max.core.api.Drafts
import com.max.core.api.DraftsSnapshot
import com.max.core.api.MaxDraft
import com.max.core.api.FolderList
import com.max.core.api.FolderUpdate
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.LocalRead
import com.max.core.api.MessageReaders
import com.max.core.api.PhoneContact
import com.max.core.api.PhoneNumbers
import com.max.core.api.PresenceInfo
import com.max.core.api.PresenceStatus
import com.max.core.api.Presences
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
 * @property presence last presence per user id (`seen` as sent, Unix seconds; `status` codes in
 *   [PresenceStatus]). A push without `seen` keeps the stored time. "Online" is not trusted
 *   forever: [StateReducer.expirePresence] turns an online entry not refreshed within the
 *   server's `presence-ttl` into offline, and every `LOGIN` (new session, reconnect) does that
 *   for all online entries before the reply's own `presence` is applied. Read through
 *   [presenceAt] to get the TTL applied between two sweeps.
 * @property presenceTimes local clock time (ms) at which each [presence] entry last came from the
 *   server (`LOGIN`, push 132, `CONTACT_PRESENCE` 35, `CHAT_MEMBERS`). An entry without a time
 *   (put by older code paths) is never expired by the TTL, only by a new session.
 * @property typing per chat id: user id → local clock time (ms) of the last `NOTIF_TYPING`.
 *   The protocol has no "stopped typing" push; see [typingUsers].
 * @property readMarks per chat id: user id → last read `mark` (`NOTIF_MARK` 130). Merged with the
 *   chat's `participants` marks by [chatReadMarks].
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
 * @property addressBook names from the device address book, by phone number normalized with
 *   [PhoneNumbers.normalize] (`+79131234567`). Supplied by the client
 *   ([StateReducer.setAddressBook]) and matched on the device; never sent to the server.
 * @property localNames address-book names by user id, for users the client matched itself
 *   ([StateReducer.setLocalName]).
 *   Both maps are device data: a `LOGIN` of another account keeps them, [MaxStore.clear] drops them.
 * @property drafts server drafts by chat id (`LOGIN` `drafts`, confirmed `DRAFT_SAVE` /
 *   `DRAFT_DISCARD`, pushes 152 / 153); a `LOGIN` of another account drops them.
 * @property draftDiscards discard marks by chat id: the server time (ms) of the latest known
 *   discard of the chat's draft (`LOGIN` `discarded`, push 153, an empty push 152, an own
 *   `DRAFT_DISCARD` 177 or a send that consumed the draft). A mark stays until a strictly later
 *   draft replaces it; a chat has a draft or a mark, never both. Dropped like [drafts].
 * @property preferAddressBookNames name rule of [displayName]: `true` (default) the address book
 *   wins over the contact name this account set, `false` the other way round
 *   ([ContactNames.resolve]). A client setting: it survives a `LOGIN` of another account and
 *   [MaxStore.clear].
 * @property ghostMode ghost mode ([com.max.core.api.GhostMode]): nothing tells the server that
 *   the user is online or typing. A client setting like [preferAddressBookNames].
 * @property hideReadReceipts hidden read receipts ([com.max.core.api.GhostMode]): read marks are
 *   not sent, chats are read locally ([localReads]). A client setting like [ghostMode].
 * @property localReads chats read while read receipts were hidden, by chat id ([LocalRead]): the unread
 *   counter of such a chat only counts messages after the local mark, on top of the server's
 *   counter ([StateReducer.applyLocalReads], applied after every change of the store). An entry
 *   goes once the server's own read mark of [me] reaches its time. Account data: a `LOGIN` of
 *   another account and [MaxStore.clear] drop it.
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
    val addressBook: Map<String, String> = emptyMap(),
    val localNames: Map<Long, String> = emptyMap(),
    val drafts: Map<Long, MaxDraft> = emptyMap(),
    val draftDiscards: Map<Long, Long> = emptyMap(),
    val presenceTimes: Map<Long, Long> = emptyMap(),
    val preferAddressBookNames: Boolean = true,
    val ghostMode: Boolean = false,
    val hideReadReceipts: Boolean = false,
    val localReads: Map<Long, LocalRead> = emptyMap(),
) {
    /**
     * The own read mark of [chatId] the server knows: the later of the chat's `participants`
     * entry and a `NOTIF_MARK` / `CHAT_MARK` reply for [me]; `null` when neither is known.
     */
    fun ownServerReadMark(chatId: Long): Long? {
        val me = me ?: return null
        val a = chats[chatId]?.participants?.get(me)
        val b = readMarks[chatId]?.get(me)
        return if (a == null) b else if (b == null) a else maxOf(a, b)
    }

    /**
     * Presence of [userId] at local time [now] (ms): the stored entry, but an `ONLINE` one that has
     * not been refreshed for more than [ttlMs] reads as offline with the last known time
     * ([Presences.degrade]). `null` when nothing is known.
     */
    fun presenceAt(userId: Long, now: Long, ttlMs: Long = DEFAULT_PRESENCE_TTL_MS): PresenceInfo? {
        val p = presence[userId] ?: return null
        if (p.status != PresenceStatus.ONLINE) return p
        val at = presenceTimes[userId] ?: return p
        return if (now - at > ttlMs) Presences.degrade(p, at) else p
    }

    /** [PresenceStatus.of] the [presenceAt] of [userId]: `-1` unknown, else `0`..`3`. */
    fun presenceStatus(userId: Long, now: Long, ttlMs: Long = DEFAULT_PRESENCE_TTL_MS): Int =
        PresenceStatus.of(presenceAt(userId, now, ttlMs))

    /** The server draft of [chatId], or `null`. */
    fun draftOf(chatId: Long): MaxDraft? = drafts[chatId]

    /** The discard mark of [chatId] (server time, ms; [draftDiscards]), or `null`. */
    fun draftDiscardedAt(chatId: Long): Long? = draftDiscards[chatId]

    /**
     * The newest draft time kept here, drafts and discard marks (`-1` without either): what
     * `draftsSync` may send.
     */
    val draftsSyncTime: Long
        get() = maxOf(drafts.values.maxOfOrNull { it.updateTime } ?: -1, draftDiscards.values.maxOrNull() ?: -1)

    /** The address-book name of [userId]: [localNames], else [addressBook] by the user's normalized `phone`. */
    fun addressBookName(userId: Long): String? =
        localNames[userId] ?: users[userId]?.phone?.let(PhoneNumbers::normalize)?.let { addressBook[it] }

    /**
     * The name to show for [userId] ([ContactNames.resolve]): address-book name > the contact
     * name this account set (`CUSTOM`) > the user's own name (`ONEME`) > the first entry of
     * `names` > the phone; with [preferAddressBookNames] `false` the first two swap. `null` when
     * none is known; [displayLabel] adds the last fallback.
     */
    fun displayName(userId: Long): String? = ContactNames.resolve(users[userId], addressBookName(userId), preferAddressBookNames)

    /** [displayName], or [ContactNames.FALLBACK] ("Участник") when nothing is known. */
    fun displayLabel(userId: Long): String = displayName(userId) ?: ContactNames.FALLBACK

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
     * Read marks of [chatId] (user id → time of the last message read, ms): the [server] marks,
     * by default `participants` of the stored chat ([Chat.participants]), merged with the
     * `NOTIF_MARK` pushes in [readMarks]; for a user in both the later mark wins
     * ([MessageReaders.mergeMarks]).
     */
    fun chatReadMarks(chatId: Long, server: Map<Long, Long> = chats[chatId]?.participants.orEmpty()): Map<Long, Long> =
        MessageReaders.mergeMarks(server, readMarks[chatId])

    /**
     * Chats with an open history hole. The hole stays open until a `CHAT_HISTORY` page contains
     * the anchor id, or the server returns an empty page. Chats that had no loaded messages when
     * `lastMessage` moved on are not listed.
     */
    fun historyGaps(): List<Long> = gapAnchors.keys.toList()

    companion object {
        const val DEFAULT_TYPING_TTL_MS: Long = 8_000

        /** `presence-ttl` of the server config (300 s) when the config has none. */
        const val DEFAULT_PRESENCE_TTL_MS: Long = 300_000
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
 *   `lastMessage` if it is the same id. Its `updateTime` (edit time) comes from the push; a push
 *   without one keeps the stored edit time.
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
 * - [MaxEvent.Presence] — replaces the user's presence ([Presences.merge]: a push without `seen`
 *   keeps the stored time, a push without `status` reads as offline) and records `now` in
 *   [MaxState.presenceTimes].
 * - [MaxEvent.DraftSaved] (152) — the draft of its chat ([MaxEvent.DraftSaved.targetChatId]) by
 *   [Drafts.mergeRemote] (only a later time replaces ours), and only when it is strictly later
 *   than the chat's discard mark ([MaxState.draftDiscards]; it then clears the mark). An empty
 *   draft ([MaxDraft.isEmpty]) is read as a discard at its time.
 * - [MaxEvent.DraftDiscarded] (153) — [discardDraft]: removes the chat's draft and sets the
 *   discard mark unless the stored draft is newer than the discard. Both are ignored for a
 *   dialog while [MaxState.me] is unknown.
 * - [MaxEvent.ReactionsChanged] — replaces counters / total of the stored message. The own
 *   `yourReaction` is taken from the push when it has one, otherwise the stored one is kept while
 *   its counter is still in the push.
 * - [MaxEvent.FoldersChanged] — merged into [MaxState.chatFolders] ([ChatFolders.merge]); this is
 *   how pins made on another device arrive.
 * - everything else (attachment signals, calls, config pushes, unknown pushes) — no change; the
 *   account config (chat mutes) lives in `MaxClient.accountConfig`, not here.
 */
object StateReducer {

    fun reduce(state: MaxState, event: MaxEvent, now: Long, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState = when (event) {
        is MaxEvent.NewMessage -> newMessage(state, event.message, messageLimit)
        is MaxEvent.MessageEdited -> edited(state, event.message, messageLimit)
        is MaxEvent.MessagesDeleted -> deleted(state, event)
        is MaxEvent.ChatUpdated -> putChat(state, event.chat)
        is MaxEvent.ContactUpdated -> contactUpdated(state, event.user)
        is MaxEvent.Typing -> state.copy(
            typing = state.typing.put2(event.chatId, event.userId, now),
            typingTypes = state.typingTypes.set2(event.chatId, event.userId, event.effectiveType),
        )
        is MaxEvent.MessageRead -> read(state, event)
        is MaxEvent.Presence -> putPresence(state, mapOf(event.userId to PresenceInfo(event.seen, event.status)), now)
        is MaxEvent.DraftSaved -> {
            val chatId = event.targetChatId(state.me)
            val draft = chatId?.let { event.toDraft(it) }
            when {
                draft == null -> state
                draft.isEmpty -> discardDraft(state, draft.chatId, draft.updateTime)
                else -> putRemoteDraft(state, draft, Drafts::mergeRemote)
            }
        }
        is MaxEvent.DraftDiscarded -> {
            val chatId = event.targetChatId(state.me)
            if (chatId == null) state else discardDraft(state, chatId, event.time)
        }
        is MaxEvent.ReactionsChanged -> reactions(state, event)
        is MaxEvent.FoldersChanged -> state.copy(
            chatFolders = (state.chatFolders ?: ChatFolders(emptyList())).merge(event.folders, event.foldersOrder, event.folderSync),
        )
        is MaxEvent.AttachmentReady, is MaxEvent.CallStart, is MaxEvent.StoriesUpdated, is MaxEvent.ConfigUpdated, is MaxEvent.Unknown -> state
    }

    /**
     * Seeds the state from a `LOGIN` reply (PyMax `App.start`): `me`, `chats`, `contacts`
     * (→ [MaxState.users], including the own profile contact, and [MaxState.contactIds]) and `messages` (`{chatId: [message]}`,
     * keys may be integers or decimal strings). A different user replaces the snapshot; the same
     * user is merged, then holes against `lastMessage` are recorded. `config.chatFolders` replaces
     * [MaxState.chatFolders] when present ([ChatFolders.fromLoginConfig]); a reply without it keeps
     * the known folders.
     */
    fun login(state: MaxState, result: LoginResult, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState =
        login(state, result, messageLimit, NO_TIME)

    /**
     * [login] at local time [now] (ms), used for [MaxState.presenceTimes]. A new session does not
     * trust any stored "online": every `ONLINE` entry first becomes offline with its last known
     * time ([invalidateOnline]); then the reply's `presence` map (`{userId: {seen, status}}`,
     * KometTeam/Komet `PresenceFetch.primeAll`) is applied ([putPresence]). With [now] [NO_TIME]
     * the applied entries get no refresh time and are not expired by the TTL.
     */
    fun login(state: MaxState, result: LoginResult, messageLimit: Int, now: Long): MaxState {
        // Another account: a fresh snapshot, but the device address book and the name rule stay.
        val base = if (result.userId != null && state.me != null && result.userId != state.me) {
            MaxState(
                addressBook = state.addressBook,
                localNames = state.localNames,
                preferAddressBookNames = state.preferAddressBookNames,
                ghostMode = state.ghostMode,
                hideReadReceipts = state.hideReadReceipts,
            )
        } else {
            state
        }
        var s = invalidateOnline(base.copy(me = result.userId ?: base.me))
        Presences.parseMap(result.raw["presence"])?.let { s = putPresence(s, it, now) }
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
        s = putDrafts(s, Drafts.fromLogin(result.raw, s.me))
        return openHistoryGaps(s)
    }

    /**
     * Server drafts of a `LOGIN` reply: each saved draft by the store rule ([Drafts.merge]: newer
     * or equal replaces, older is ignored) when it is strictly later than the chat's discard mark;
     * then each discarded entry by [discardDraft] (removes the stored draft and sets the mark
     * unless the stored draft is newer than the discard).
     */
    fun putDrafts(state: MaxState, snapshot: DraftsSnapshot): MaxState {
        if (snapshot.saved.isEmpty() && snapshot.discarded.isEmpty()) return state
        var s = state
        for (d in snapshot.saved.values) s = putRemoteDraft(s, d, Drafts::merge)
        for ((chatId, time) in snapshot.discarded) s = discardDraft(s, chatId, time)
        return s
    }

    /**
     * A non-empty draft from the server (`LOGIN`, push 152): ignored unless strictly later than
     * the chat's discard mark (equal time: the discard wins, fixture `discard-equal-clears`);
     * otherwise merged with the stored draft by [rule], and the mark is cleared once a draft
     * newer than it is stored.
     */
    private fun putRemoteDraft(state: MaxState, draft: MaxDraft, rule: (MaxDraft?, MaxDraft) -> MaxDraft): MaxState {
        val mark = state.draftDiscards[draft.chatId]
        if (mark != null && draft.updateTime <= mark) return state
        val merged = rule(state.drafts[draft.chatId], draft)
        return state.copy(
            drafts = state.drafts + (draft.chatId to merged),
            draftDiscards = if (mark == null) state.draftDiscards else state.draftDiscards - draft.chatId,
        )
    }

    /** Local time meaning "unknown" for [login] / [putPresence]: no refresh time is recorded. */
    const val NO_TIME: Long = Long.MIN_VALUE

    /**
     * Presence entries from the server (`LOGIN`, push 132, `CONTACT_PRESENCE` 35, `CHAT_MEMBERS`):
     * each replaces the stored one ([Presences.merge], a missing `seen` keeps the stored time)
     * and records [now] in [MaxState.presenceTimes] (not with [NO_TIME]).
     */
    fun putPresence(state: MaxState, entries: Map<Long, PresenceInfo>, now: Long): MaxState {
        if (entries.isEmpty()) return state
        val presence = LinkedHashMap(state.presence)
        val times = LinkedHashMap(state.presenceTimes)
        for ((id, p) in entries) {
            presence[id] = Presences.merge(presence[id], p)
            if (now != NO_TIME) times[id] = now else times.remove(id)
        }
        return state.copy(presence = presence, presenceTimes = times)
    }

    /**
     * Turns every `ONLINE` entry not refreshed for more than [ttlMs] at [now] into offline with
     * its last known time ([Presences.degrade]). Entries without a refresh time are kept.
     */
    fun expirePresence(state: MaxState, now: Long, ttlMs: Long): MaxState {
        var changed: LinkedHashMap<Long, PresenceInfo>? = null
        for ((id, p) in state.presence) {
            if (p.status != PresenceStatus.ONLINE) continue
            val at = state.presenceTimes[id] ?: continue
            if (now - at <= ttlMs) continue
            if (changed == null) changed = LinkedHashMap(state.presence)
            changed[id] = Presences.degrade(p, at)
        }
        return if (changed == null) state else state.copy(presence = changed)
    }

    /**
     * Every `ONLINE` entry becomes offline with its last known time ([Presences.degrade]): a new
     * session or a reconnect, until fresh presence arrives.
     */
    fun invalidateOnline(state: MaxState): MaxState {
        if (state.presence.values.none { it.status == PresenceStatus.ONLINE }) return state
        return state.copy(
            presence = state.presence.mapValues { (id, p) ->
                if (p.status == PresenceStatus.ONLINE) Presences.degrade(p, state.presenceTimes[id]) else p
            },
        )
    }

    /** Sets [MaxState.ghostMode]. */
    fun setGhostMode(state: MaxState, on: Boolean): MaxState =
        if (state.ghostMode == on) state else state.copy(ghostMode = on)

    /** Sets [MaxState.hideReadReceipts]; the local reads stay either way. */
    fun setHideReadReceipts(state: MaxState, on: Boolean): MaxState =
        if (state.hideReadReceipts == on) state else state.copy(hideReadReceipts = on)

    /**
     * Records a local read of [chatId] up to [read] (hidden read receipts). A mark only moves forward: an
     * older one than the stored mark is ignored, and so is one the server's own read mark already
     * covers. Then [applyLocalReads].
     */
    fun putLocalRead(state: MaxState, chatId: Long, read: LocalRead): MaxState {
        val known = state.localReads[chatId]
        if (known != null && known.time >= read.time) return applyLocalReads(state)
        val server = state.ownServerReadMark(chatId)
        if (server != null && server >= read.time) return applyLocalReads(state)
        return applyLocalReads(state.copy(localReads = state.localReads + (chatId to read)))
    }

    /** Drops the local read of [chatId] (an explicit "mark unread"). The counter stays as it is. */
    fun dropLocalRead(state: MaxState, chatId: Long): MaxState =
        if (chatId !in state.localReads) state else state.copy(localReads = state.localReads - chatId)

    /** Replaces [MaxState.localReads] (loaded from the device), then [applyLocalReads]. */
    fun setLocalReads(state: MaxState, reads: Map<Long, LocalRead>): MaxState =
        applyLocalReads(if (state.localReads == reads) state else state.copy(localReads = reads))

    /**
     * Puts [MaxState.localReads] on top of the server's read state: an entry the server's own
     * mark ([MaxState.ownServerReadMark]) reached is dropped; for the others the chat's
     * `newMessages` becomes the messages after the local mark when that is fewer: `0` when the
     * last message is not newer than the mark, else the stored messages of other users after it
     * when the store holds all of them, else the server's counter stays. A chat not in the store
     * keeps its entry for later.
     */
    fun applyLocalReads(state: MaxState): MaxState {
        if (state.localReads.isEmpty()) return state
        var reads = state.localReads
        var chats = state.chats
        for ((chatId, read) in state.localReads) {
            val server = state.ownServerReadMark(chatId)
            if (server != null && server >= read.time) {
                reads = reads - chatId
                continue
            }
            val chat = chats[chatId] ?: continue
            val unread = localUnread(state, chatId, chat, read.time)
            if (unread < chat.newMessages) chats = chats + (chatId to chat.copy(newMessages = unread))
        }
        return if (reads === state.localReads && chats === state.chats) state else state.copy(localReads = reads, chats = chats)
    }

    private fun localUnread(state: MaxState, chatId: Long, chat: Chat, mark: Long): Int {
        if ((chat.lastMessage?.time ?: Long.MIN_VALUE) <= mark) return 0
        val cached = state.messagesOf(chatId)
        if (!coversSince(state, chatId, cached, mark)) return chat.newMessages
        return cached.count { it.time > mark && (it.sender == null || it.sender != state.me) }
    }

    /** Sets [MaxState.preferAddressBookNames]. */
    fun setPreferAddressBookNames(state: MaxState, prefer: Boolean): MaxState =
        if (state.preferAddressBookNames == prefer) state else state.copy(preferAddressBookNames = prefer)

    /**
     * A discard of [chatId]'s draft at [time] from the server (push 153, an empty push 152, a
     * `LOGIN` `discarded` entry). A stored draft strictly newer than [time] stays and no mark is
     * set (that draft already replaced the discard). Otherwise the stored draft (time <= [time]:
     * on an equal time the discard wins) is removed and the discard mark becomes the later of
     * the stored mark and [time].
     */
    fun discardDraft(state: MaxState, chatId: Long, time: Long): MaxState {
        val stored = state.drafts[chatId]
        if (stored != null && stored.updateTime > time) return state
        return markDiscarded(state, chatId, time)
    }

    /**
     * One draft (a confirmed `DRAFT_SAVE`) by the store rule ([Drafts.merge]). An own confirmed
     * save is the latest word on the chat: it clears the discard mark.
     */
    fun putDraft(state: MaxState, draft: MaxDraft): MaxState =
        state.copy(
            drafts = state.drafts + (draft.chatId to Drafts.merge(state.drafts[draft.chatId], draft)),
            draftDiscards = state.draftDiscards - draft.chatId,
        )

    /**
     * Removes the draft of [chatId] (a confirmed `DRAFT_DISCARD`, or the draft was sent). With a
     * [time] (the `time` sent in `DRAFT_DISCARD` 177) the discard mark becomes the later of the
     * stored mark and [time]; the stored draft goes regardless of its time (our own discard).
     */
    fun removeDraft(state: MaxState, chatId: Long, time: Long? = null): MaxState = when {
        time != null -> markDiscarded(state, chatId, time)
        chatId in state.drafts -> state.copy(drafts = state.drafts - chatId)
        else -> state
    }

    private fun markDiscarded(state: MaxState, chatId: Long, time: Long): MaxState {
        val mark = maxOf(state.draftDiscards[chatId] ?: time, time)
        if (chatId !in state.drafts && state.draftDiscards[chatId] == mark) return state
        return state.copy(drafts = state.drafts - chatId, draftDiscards = state.draftDiscards + (chatId to mark))
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
     * A contact removed on the server (`CONTACT_UPDATE` `REMOVE`): it leaves [MaxState.contactIds]
     * and the stored user ([reply], the reply's `contact`, when there is one) loses the `CUSTOM`
     * name this account had given it.
     */
    fun removeContact(state: MaxState, userId: Long, reply: MaxUser? = null): MaxState {
        val user = reply?.takeIf { it.id == userId } ?: state.users[userId]
        val users = if (user == null) state.users else state.users + (userId to ContactNames.withoutCustom(user))
        return state.copy(contactIds = state.contactIds - userId, users = users)
    }

    /**
     * Replaces the device address book with [entries] (number via [PhoneNumbers.normalize] →
     * [PhoneContact.fullName]). Entries without a valid number or a name are skipped; for a
     * repeated number the first entry with a name wins. [MaxState.localNames] are kept.
     */
    fun setAddressBook(state: MaxState, entries: List<PhoneContact>): MaxState {
        val book = LinkedHashMap<String, String>()
        for (e in entries) {
            val number = PhoneNumbers.normalize(e.phone) ?: continue
            if (number in book) continue
            book[number] = e.fullName ?: continue
        }
        return state.copy(addressBook = book)
    }

    /**
     * A contact pushed by the server (`NOTIF_CONTACT` 131 `{contact}`, a rename or change made on
     * another session): replaces the stored user unless the stored one has a newer `updateTime`.
     */
    fun contactUpdated(state: MaxState, user: MaxUser): MaxState {
        val stored = state.users[user.id]
        val storedTime = stored?.raw?.get("updateTime").asLong()
        val time = user.raw["updateTime"].asLong()
        if (storedTime != null && time != null && time < storedTime) return state
        return putUsers(state, listOf(user))
    }

    /** Sets (or with a blank / `null` [name] clears) the address-book name of [userId]. */
    fun setLocalName(state: MaxState, userId: Long, name: String?): MaxState {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        return state.copy(localNames = if (clean == null) state.localNames - userId else state.localNames + (userId to clean))
    }

    /**
     * An edit this client made (`MSG_EDIT` reply): like an edit push, and reactions the reply
     * left out are kept from the stored message.
     */
    fun putEditedMessage(state: MaxState, chatId: Long, m: MaxMessage, messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT): MaxState {
        val stored = state.messages[chatId]?.firstOrNull { it.id == m.id }
        val withChat = if (m.chatId == null) m.copy(chatId = chatId) else m
        val merged = if (withChat.reactionInfo == null && stored?.reactionInfo != null) withChat.copy(reactionInfo = stored.reactionInfo) else withChat
        return edited(state, merged, messageLimit)
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

    private fun edited(state: MaxState, pushed: MaxMessage, limit: Int): MaxState {
        val chatId = pushed.chatId ?: return state
        val m = if (pushed.updateTime != null) pushed else {
            // an edit cannot make a message unedited: keep the known edit time
            val known = state.messages[chatId]?.firstOrNull { it.id == pushed.id }?.updateTime
                ?: state.chats[chatId]?.lastMessage?.takeIf { it.id == pushed.id }?.updateTime
            if (known != null) pushed.copy(updateTime = known) else pushed
        }
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
        // A read mark only moves forward: a late, older push must not roll it back. Only an
        // explicit "mark as unread" (setAsUnread) may move the own mark back.
        val known = state.readMarks[e.chatId]?.get(e.userId)
        if (known != null && e.mark < known && !e.setAsUnread) return state
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
