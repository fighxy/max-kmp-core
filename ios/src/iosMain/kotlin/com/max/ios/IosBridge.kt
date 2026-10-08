@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.ios

import com.max.core.ErrorKind
import com.max.core.api.AccountConfig
import com.max.core.api.EntryApp
import com.max.core.api.Transcription
import com.max.core.api.Chat
import com.max.core.api.ChatMemberEntry
import com.max.core.api.ContactNames
import com.max.core.api.PhoneContact
import com.max.core.api.PresenceInfo
import com.max.core.api.PresenceStatus
import com.max.core.api.Presences
import com.max.core.api.MaxDraft
import com.max.core.api.TextElement
import com.max.core.api.TextElementsJson
import com.max.core.api.TextElementType
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.ReactionInfo
import com.max.core.api.Story
import com.max.core.api.StoryAudience
import com.max.core.api.StoryOwner
import com.max.core.api.StoryPreview
import com.max.core.api.TypingType
import com.max.core.api.hasWebApp
import com.max.core.media.OutgoingMedia
import com.max.core.media.UploadProgress
import com.max.core.media.fileUploadSource
import com.max.core.media.messageContentJson
import com.max.core.media.reactionsJson
import com.max.core.calls.CallLink
import com.max.core.calls.CallLogEntry
import com.max.core.calls.CallSignaling
import com.max.core.calls.ConversationParams
import com.max.core.calls.Ws2ClientInfo
import com.max.core.calls.ws2UrlFromEndpoint
import com.max.core.session.UserAgentInfo
import com.max.core.state.MaxState
import com.max.core.auth.CodeRequestType
import com.max.core.auth.VerifyResult
import com.max.core.events.MaxEvent
import com.max.core.protocol.Opcode
import com.max.core.toMaxError
import com.max.shared.MaxClient
import com.max.shared.DeviceProfile
import com.max.shared.MaxClientConfig
import com.max.shared.ReadPacer
import com.max.shared.SingleFlight
import com.max.shared.Watcher
import com.max.shared.watch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import com.max.shared.ClientState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSLock

/**
 * Swift entry of the network core.
 *
 * The framework exports only the types in this file and in `IosSettings.kt`. [MaxClient] stays inside it: the device
 * profile is still the Android Pixel 8 profile, and the login token stays in the Keychain store
 * `com.max.kmp.<namespace>`. Callbacks run on the core dispatcher, not the main thread.
 *
 * Error boundary: nothing here throws into Swift. Every asynchronous operation calls its callback
 * exactly once, with a null error kind on success or an [com.max.core.ErrorKind] name (plus the
 * server error key, if any) on failure, including cancellation (`CANCELLED`) and calls made after
 * [close]. The [MaxClient] is created on first use, so a Keychain failure while loading the
 * device identity becomes an error kind of the failing call instead of an exception in the
 * initializer. The synchronous getters fall back to `failed` / empty / `false`. Exceptions thrown
 * by a callback itself are dropped and never abort the process.
 */
class MaxIosClient internal constructor(
    private val scope: CoroutineScope,
    /** How long a call waits for an authorized session that is reconnecting ([awaitSession]). */
    private val sessionWaitMs: Long = SESSION_WAIT_MS,
    private val factory: (CoroutineScope) -> MaxClient,
) {
    /**
     * The app keeps its own history, so the core does not page through every chat's history gap
     * after each reconnect ([MaxClientConfig.fillGapsOnReconnect]): that burst of `CHAT_HISTORY`
     * requests ran into `too.many.requests`. Open chats reload their history themselves.
     */
    constructor(namespace: String) : this(
        newScope(),
        factory = { scope -> MaxClient(MaxClientConfig(namespace = namespace, fillGapsOnReconnect = false), scope = scope) },
    )

    private val clientLock = NSLock()
    private var created: MaxClient? = null
    private val watches = mutableListOf<Watcher>()
    /** The user whose whole chat list was already paged in ([loadChats]). */
    private var pagedUser: Long? = null
    /** [MaxClient.logins] when the chat list was last served; a newer login already synced it. */
    private var listedLogins: Int = -1
    /** Identical reads in flight share one request (a chat card asked by the header and the profile at once). */
    private val flights = SingleFlight()
    /**
     * Background reads (shared media, reactions, comment counters, the call log) go one at a time
     * and wait for the reads the user is looking at (comments, history), so that they do not
     * spend the server's request budget first (`too.many.requests`).
     */
    private val pacer = ReadPacer()

    /** The client, created on the first call; throws what the constructor threw (retried next time). */
    private fun client(): MaxClient = clientLock.locked {
        created ?: factory(scope).also {
            created = it
            // e.g. the automatic DRAFT_DISCARD after a send: no caller to tell, so it is logged
            it.onBackgroundError = { _, t -> IosDiagnostics.reportFailure(classifyKind(t), t) }
        }
    }

    fun phaseName(): String = attempt("failed") { phaseOf(client().state.value) }

    fun currentUserId(): String = attempt("") { client().userId.value?.toString().orEmpty() }

    fun hasStoredToken(): Boolean = attempt(false) { client().hasStoredToken }

    /**
     * HTTP User-Agent of the session's Android profile (`OKMessages/…`). The CDN addresses from
     * [mediaLink] are issued for that client, so video and file requests send it too.
     */
    fun mediaUserAgent(): String = attempt(DeviceProfile.android.httpUserAgent) { client().config.userAgent.httpUserAgent }

    fun start(onResult: (String?, String?, String?) -> Unit) {
        perform(onResult, { null }, waitsForSession = false) { phaseOf(it.start()) }
    }

    fun requestCode(phone: String, resend: Boolean, onResult: (IosCodeRequest?, String?, String?) -> Unit) {
        perform(onResult, { null }, waitsForSession = false) { c ->
            val type = if (resend) CodeRequestType.RESEND else CodeRequestType.START_AUTH
            val code = c.requestCode(phone, type)
            IosCodeRequest(code.token, code.codeLength ?: 0)
        }
    }

    fun verifyCode(token: String, code: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }, waitsForSession = false) { c ->
            when (val result = c.verifyCode(token, code)) {
                is VerifyResult.LoggedIn -> loggedInStep(c)
                is VerifyResult.PasswordRequired -> IosAuthStep("password", result.trackId, result.hint.orEmpty(), "", "")
                is VerifyResult.RegistrationRequired -> IosAuthStep("register", "", "", result.registerToken, "")
            }
        }
    }

    fun checkPassword(trackId: String, password: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }, waitsForSession = false) { c ->
            c.checkPassword(trackId, password)
            loggedInStep(c)
        }
    }

    fun register(registerToken: String, firstName: String, lastName: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }, waitsForSession = false) { c ->
            c.register(registerToken, firstName, lastName.takeIf { it.isNotBlank() })
            loggedInStep(c)
        }
    }

    fun logout(onResult: (String?, String?) -> Unit) {
        runUnit(onResult, waitsForSession = false) {
            it.logout()
            // The store is empty now: the next login pages the whole chat list again.
            clientLock.locked {
                pagedUser = null
                listedLogins = -1
            }
        }
    }

    /**
     * The chat list. The first call after each login pages through the whole list
     * ([MaxClient.loadAllChats]) and resyncs the folders with the pinned chats
     * ([MaxClient.loadFolders], best effort: the `LOGIN` config normally has them already).
     * The first call after a re-login (reconnect) is answered from the store: the `LOGIN` reply
     * has just brought the changed chats. Later calls (polls) refresh only the newest page.
     */
    fun loadChats(onResult: (List<IosChat>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c -> flights.share("chats") { chatList(c) }.chats }
    }

    /**
     * Same as [loadChats], with [IosChatList.complete] `true` when this call paged the account's
     * whole chat list from the server (the first call after a login of this process). The core
     * store is not persisted, so such a list holds every chat the account still takes part in:
     * the app may drop the stored chats it lacks (left or deleted on another device).
     */
    fun loadChatList(onResult: (IosChatList?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c -> flights.share("chats") { chatList(c) } }
    }

    private suspend fun chatList(c: MaxClient): IosChatList {
        val user = c.userId.value
        val logins = c.logins.value
        var complete = false
        when {
            user != null && clientLock.locked { pagedUser } != user -> {
                c.loadAllChats()
                syncFolders(c)
                clientLock.locked {
                    pagedUser = user
                    listedLogins = logins
                }
                complete = true
            }
            user != null && logins > 0 && clientLock.locked { listedLogins } != logins ->
                clientLock.locked { listedLogins = logins }
            else -> c.loadChats()
        }
        val chats = c.store.state.value.chats.values
        // Dialog peers and the authors of groups' last messages, so rows can name them.
        resolveUsers(c, chats.mapNotNull { dialogPeer(it, c.userId.value) } + chats.filter { it.type == "CHAT" }.mapNotNull { it.lastMessage?.sender })
        val state = c.store.state.value
        val config = c.accountConfig.value
        return IosChatList(chats.map { chatSnapshot(it, state, config) }, complete)
    }

    fun loadChat(chatId: String, onResult: (IosChat?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            flights.share("chat:$chatId") {
                val chat = c.api.chats.getChat(parseId(chatId))
                resolveUsers(c, listOfNotNull(dialogPeer(chat, c.userId.value)))
                chatSnapshot(chat, c.store.state.value, c.accountConfig.value)
            }
        }
    }

    /** Mutes [chatId] for good or turns its sound back on ([MaxClient.setChatMuted]). */
    fun setChatMuted(chatId: String, muted: Boolean, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.setChatMuted(parseId(chatId), muted) }
    }

    /**
     * Mutes [chatId] until [untilMs] (Unix ms), for good with `-1`, or turns the sound back on with
     * `0` ([MaxClient.setChatMuteUntil]).
     */
    fun setChatMuteUntil(chatId: String, untilMs: Long, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.setChatMuteUntil(parseId(chatId), untilMs) }
    }

    /**
     * Whether [chatId] is muted now, with the same codes as [IosChat.muted]: `1` muted (for good
     * or until a time still ahead), `0` sound on, `-1` UNKNOWN (no account config yet: before the
     * first login, after logout; a config that does not know this chat; a malformed id). `-1`
     * never means "muted for good" (that raw `dontDisturbUntil` is [chatMuteUntil]): keep the
     * value shown before. Reads the core's config, no request.
     */
    fun isChatMuted(chatId: String): Int = attempt(-1) {
        val id = chatId.trim().toLongOrNull() ?: return@attempt -1
        muteCode(client().accountConfig.value, id, nowMs())
    }

    /**
     * [chatId]'s raw `dontDisturbUntil`: `0` sound on, `-1` muted for good, else the end of the
     * mute (Unix ms; a time in the past means the sound is on again). [Long.MIN_VALUE] while
     * unknown (the same cases as `-1` of [isChatMuted]).
     */
    fun chatMuteUntil(chatId: String): Long = attempt(Long.MIN_VALUE) {
        val id = chatId.trim().toLongOrNull() ?: return@attempt Long.MIN_VALUE
        client().chatMuteUntil(id) ?: Long.MIN_VALUE
    }

    /**
     * The card of a chat for the profile screen: a user or a bot for a dialog (`CONTACT_INFO` 32,
     * plus `BOT_INFO` 145 for bots), a group or a channel otherwise (`CHAT_INFO` 48). A dialog
     * that is not in the store yet (opened from contacts) is resolved as `chatId xor ownId`.
     */
    fun loadProfile(chatId: String, onResult: (IosProfile?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val id = parseId(chatId)
            // Saved Messages are chat 0: there is no CHAT_INFO for it, asking only spends requests.
            if (id == 0L) return@perform IosProfile(kind = "saved", chatId = chatId)
            flights.share("profile:$chatId") { profileOf(c, chatId, id) }
        }
    }

    private suspend fun profileOf(c: MaxClient, chatId: String, id: Long): IosProfile {
        val me = c.userId.value
        val stored = c.store.state.value.chats[id]
        val chat = if (stored == null || stored.type != "DIALOG") fetchChatOrNull(c, id) ?: stored else stored
        if (chat != null && chat.type != "DIALOG") return chatProfile(chat)
        val peer = chat?.let { dialogPeer(it, me) } ?: me?.let { id xor it }
        return if (peer == null || peer == me || peer == 0L) {
            IosProfile(kind = "saved", chatId = chatId)
        } else {
            userProfile(c, chatId, peer)
        }
    }

    /** The account's contact list from the last `LOGIN` reply, with the last known presence. */
    fun loadContacts(onResult: (List<IosContact>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val state = c.store.state.value
            listedContacts(state, c.presenceTtlMs)
        }
    }

    /**
     * The call log (`VIDEO_CHAT_HISTORY` 79), newest first as the server sends it. Unknown peers
     * are looked up with `CONTACT_INFO` once; a call whose peer stays unknown is a group call.
     */
    fun loadCallHistory(onResult: (List<IosCall>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            flights.share("calls") {
                pacer.background {
                    val me = c.userId.value
                    val entries = c.api.calls.history()
                    resolveUsers(c, entries.mapNotNull { it.peerId(me) })
                    val state = c.store.state.value
                    entries.map { callSnapshot(it, me, state) }
                }
            }
        }
    }

    fun loadHistory(chatId: String, beforeMs: Long, limit: Int, onResult: (List<IosMessage>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            flights.share("history:$chatId:$beforeMs:$limit") {
                pacer.priority {
                    val history = c.loadHistory(parseId(chatId), from = beforeMs.takeIf { it > 0 }, backward = limit.coerceIn(1, 100))
                    resolveUsers(c, history.messages.mapNotNull { it.sender })
                    val state = c.store.state.value
                    history.messages.map { messageSnapshot(it, chatId, state) }
                }
            }
        }
    }

    /**
     * A continuous history page around a point of the chat (`CHAT_HISTORY` 49 with `forward`),
     * oldest first, with the authors resolved: up to [forward] messages from [fromMs] on and up to
     * [backward] older ones. With [fromMs] `0` the point is message [messageId]: its time comes from
     * the store or `MSG_GET`; an unknown message gives an empty list. The app opens such a page to
     * jump to a reply, a pinned or a found message far above its newest history, and pages on from
     * the page's edges. The page is not put into the store: it is not the newest history.
     */
    fun loadHistoryAround(
        chatId: String,
        messageId: String,
        fromMs: Long,
        forward: Int,
        backward: Int,
        onResult: (List<IosMessage>, String?, String?) -> Unit,
    ) {
        perform(onResult, { emptyList() }) { c ->
            flights.share("around:$chatId:$messageId:$fromMs:$forward:$backward") {
                pacer.priority {
                    val chat = parseId(chatId)
                    val from = if (fromMs > 0) {
                        fromMs
                    } else {
                        val id = parseId(messageId)
                        c.store.state.value.messagesOf(chat).firstOrNull { it.id == id }?.time
                            ?: c.api.messages.getMessages(chat, listOf(id)).firstOrNull()?.time
                    }
                    if (from == null || from <= 0) {
                        emptyList()
                    } else {
                        val page = c.api.messages.getChatHistory(
                            chat,
                            from = from,
                            forward = forward.coerceIn(0, 100),
                            backward = backward.coerceIn(0, 100),
                        )
                        resolveUsers(c, page.messages.mapNotNull { it.sender })
                        val state = c.store.state.value
                        page.messages.sortedBy { it.time }.map { messageSnapshot(it, chatId, state) }
                    }
                }
            }
        }
    }

    /**
     * Shared media of a chat from the server (`CHAT_MEDIA` 51), not limited to the loaded history:
     * messages with [attachTypes] (`PHOTO`, `VIDEO`, `FILE`, `AUDIO`, `SHARE`) around [anchorId],
     * [forward] newer and [backward] older, with their authors resolved. The first page is
     * anchored at the chat's last message, the next ones at the oldest message received; pages
     * overlap, the app deduplicates them. The messages are not put into the store: they are not
     * a continuous history page and must not close or open history gaps.
     */
    fun loadSharedMedia(
        chatId: String,
        anchorId: String,
        attachTypes: List<String>,
        forward: Int,
        backward: Int,
        onResult: (List<IosMessage>, String?, String?) -> Unit,
    ) {
        perform(onResult, { emptyList() }) { c ->
            flights.share("media:$chatId:$anchorId:${attachTypes.joinToString(",")}:$forward:$backward") {
                pacer.background {
                    val page = c.api.messages.getChatMedia(
                        parseId(chatId),
                        parseId(anchorId),
                        attachTypes,
                        forward = forward.coerceIn(0, 100),
                        backward = backward.coerceIn(0, 100),
                    )
                    resolveUsers(c, page.messages.mapNotNull { it.sender })
                    val state = c.store.state.value
                    page.messages.map { messageSnapshot(it, chatId, state) }
                }
            }
        }
    }

    fun sendText(chatId: String, text: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        sendText(chatId, text, "", onResult)
    }

    /** Sends [text]; a non-empty [replyTo] (server message id) makes it a reply (`link` `REPLY`). */
    fun sendText(chatId: String, text: String, replyTo: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            messageSnapshot(c.sendText(parseId(chatId), text, reply), chatId, c.store.state.value)
        }
    }

    /**
     * Tells [chatId] that this account is typing (`MSG_TYPING` 65, [MaxClient.sendTyping]).
     * Fire-and-forget: no reply is awaited, a send error is only reported to [onResult], and the
     * call does not wait for a reconnecting session (a late typing signal is useless). There is
     * no throttling here: every call sends a frame. Repeat it while the user is still busy, at
     * most once per 6 s per chat; the other side drops the indicator after a few seconds.
     *
     * [type] is one of [IosTypingType] (`TEXT`, `AUDIO`, `VIDEO_MSG`, `PHOTO`, `VIDEO`, `FILE`,
     * `STICKER`); any other string is sent as given.
     * A non-empty [postId] marks typing a comment under that channel post. [onResult] gets a null
     * error kind once the frame is written; an id that is not a number or a frame that could not
     * be sent (not connected) gets an error kind.
     */
    fun sendTyping(chatId: String, type: String, postId: String, onResult: (String?, String?) -> Unit) {
        // Typing is sent every few seconds and is worthless once missed: a failure goes to the
        // callback only, never to the error log, so a stretch offline does not flood it.
        scope.launch(start = CoroutineStart.ATOMIC) {
            val (kind, key) = try {
                val chat = parseId(chatId)
                val post = postId.takeIf { it.isNotBlank() }?.let(::parseId)
                if (client().sendTyping(chat, type, post)) null to null else "NETWORK" to null
            } catch (t: Throwable) {
                classify(t)
            }
            guarded { onResult(kind, key) }
        }
    }

    /** [sendTyping] outside comments, without a callback (errors are dropped). */
    fun sendTyping(chatId: String, type: String) {
        sendTyping(chatId, type, "") { _, _ -> }
    }

    /**
     * Comments of channel post [postId] (`CHAT_HISTORY` with `postId`): up to [limit] comments
     * before [beforeMs] (the newest when `0`), oldest first, with their authors resolved.
     */
    fun loadComments(chatId: String, postId: String, beforeMs: Long, limit: Int, onResult: (List<IosMessage>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            flights.share("comments:$chatId:$postId:$beforeMs:$limit") {
                // Comments are what the user is waiting for: background reads wait for them.
                pacer.priority {
                    // The newest page is a page back from now: `from` is always a real time in
                    // milliseconds, as for the chat history, never -1.
                    val page = c.api.messages.getCommentHistory(
                        parseId(chatId),
                        parseId(postId),
                        from = if (beforeMs > 0) beforeMs else (NSDate().timeIntervalSince1970 * 1000).toLong(),
                        backward = limit.coerceIn(1, 100),
                    )
                    resolveUsers(c, page.mapNotNull { it.sender })
                    val state = c.store.state.value
                    page.filter { beforeMs <= 0 || it.time < beforeMs }
                        .sortedBy { it.time }
                        .map { messageSnapshot(it, chatId, state) }
                }
            }
        }
    }

    /**
     * Replaces the text of a sent message (`MSG_EDIT` 67); the edited message comes back. The
     * formatting of the stored message is kept where it still fits the new text (`MSG_EDIT`
     * always carries the full `elements` list, so leaving it out would clear it). A message the
     * store does not hold goes out with an empty list. To change the formatting pass it with the
     * `elementsJson` overload.
     */
    fun editMessage(chatId: String, messageId: String, text: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val chat = parseId(chatId)
            val id = parseId(messageId)
            val kept = c.store.state.value.messagesOf(chat).firstOrNull { it.id == id }?.textElements.orEmpty()
            val edited = c.editText(chat, id, text, kept.filter { it.fits(text.length) })
            // The edit reply may leave reactions out; the app keeps the ones it has.
            messageSnapshot(edited, chatId, c.store.state.value, withReactions = false)
        }
    }

    /**
     * Replaces the text and the whole formatting of a sent message (`MSG_EDIT` 67). [elementsJson]
     * is a JSON array of wire elements `{type, from, length, entityId?, attributes?}` with UTF-16
     * offsets ([IosMessage.elementsJson] has the same form); an empty string or `[]` clears the
     * formatting. Malformed JSON fails (`UNKNOWN`) before anything is sent.
     */
    fun editMessage(chatId: String, messageId: String, text: String, elementsJson: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val elements = TextElementsJson.parse(elementsJson, text.length)
            val chat = parseId(chatId)
            val id = parseId(messageId)
            val known = c.store.state.value.messagesOf(chat).any { it.id == id }
            val edited = c.editText(chat, id, text, elements)
            messageSnapshot(edited, chatId, c.store.state.value, withReactions = known)
        }
    }

    /**
     * Replaces the text and formatting of a sent message (`MSG_EDIT` 67 with `elements`).
     * [marks] as in [sendFormattedText]; an empty list clears the formatting. The result carries
     * the reactions the store keeps for the message; when the message is not in the store its
     * [IosMessage.reactionsJson] is empty (unknown), as for [editMessage].
     */
    fun editFormattedText(chatId: String, messageId: String, text: String, marks: List<IosTextMark>, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val chat = parseId(chatId)
            val id = parseId(messageId)
            val known = c.store.state.value.messagesOf(chat).any { it.id == id }
            val edited = c.editText(chat, id, text, textElementsOf(marks))
            messageSnapshot(edited, chatId, c.store.state.value, withReactions = known)
        }
    }

    /**
     * Deletes messages (`MSG_DELETE` 66), a whole selection in one request. [forEveryone]
     * `false` removes them only for this account (`forMe`), `true` for every participant. After
     * the server accepted, the store drops them (watchers see the chat's new last message).
     */
    fun deleteMessages(chatId: String, messageIds: List<String>, forEveryone: Boolean, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c ->
            val ids = messageIds.map(::parseId)
            require(ids.isNotEmpty()) { "no message ids" }
            c.deleteMessages(parseId(chatId), ids, forMe = !forEveryone)
        }
    }

    /**
     * Deletes a selection (`MSG_DELETE` 66, `{chatId, postId?, messageIds, forMe}`) and reports
     * which ids the server deleted and which it refused (`failedMessageIds`). A non-empty
     * [postId] deletes comments of that channel post. The store drops only the deleted ones.
     */
    fun deleteMessages(
        chatId: String,
        messageIds: List<String>,
        forEveryone: Boolean,
        postId: String,
        onResult: (IosDeleteResult?, String?, String?) -> Unit,
    ) {
        perform(onResult, { null }) { c ->
            val ids = messageIds.map(::parseId)
            require(ids.isNotEmpty()) { "no message ids" }
            val post = postId.trim().takeIf { it.isNotEmpty() }?.let(::parseId)
            val result = c.deleteMessages(parseId(chatId), ids, forMe = !forEveryone, postId = post)
            IosDeleteResult(result.deleted.map(Long::toString), result.failed.map(Long::toString))
        }
    }

    /**
     * Forwards message [messageId] of chat [fromChatId] to chat [toChatId] (`MSG_SEND` with a
     * `FORWARD` link). The result is the new message in the target chat.
     */
    fun forwardMessage(toChatId: String, fromChatId: String, messageId: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val sent = c.api.messages.forwardMessage(parseId(toChatId), parseId(messageId), sourceChatId = parseId(fromChatId))
            messageSnapshot(sent, toChatId, c.store.state.value)
        }
    }

    /**
     * Forwards several messages of [fromChatId] to [toChatId] (multi-select): one `MSG_SEND` with
     * a `FORWARD` link per message, in the order of [messageIds] (pass them oldest first). The
     * first failure stops the rest: [onResult] then gets the messages sent so far,
     * [IosForwardResult.failedAt] (index into [messageIds]) and the error kind and key of that
     * failure. On success `failedAt` is `-1` and the kind is `null`. A bad id or no ids fail
     * before anything is sent (`failedAt` 0, kind `UNKNOWN`).
     */
    fun forwardMessages(toChatId: String, fromChatId: String, messageIds: List<String>, onResult: (IosForwardResult, String?, String?) -> Unit) {
        perform<Pair<IosForwardResult, Throwable?>>(
            { value, kind, key ->
                val (result, error) = value
                if (kind != null || error == null) {
                    onResult(result, kind, key)
                } else {
                    val (k, e) = classify(error)
                    if (k != "CANCELLED") IosDiagnostics.reportFailure(k, error)
                    onResult(result, k, e)
                }
            },
            { IosForwardResult(emptyList(), 0) to null },
        ) { c ->
            val ids = messageIds.map(::parseId)
            require(ids.isNotEmpty()) { "no message ids" }
            val batch = c.forwardMessages(parseId(toChatId), parseId(fromChatId), ids)
            val state = c.store.state.value
            IosForwardResult(batch.sent.map { messageSnapshot(it, toChatId, state) }, batch.failedIndex ?: -1) to batch.error
        }
    }

    /**
     * Comment counters of channel posts (`MSG_GET_COMMENTS_INFO` 91). Posts the server does not
     * report are left out; an empty [postIds] asks nothing.
     */
    fun loadCommentCounts(chatId: String, postIds: List<String>, onResult: (List<IosCommentCount>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val ids = postIds.mapNotNull { it.toLongOrNull() }.distinct()
            if (ids.isEmpty()) return@perform emptyList()
            flights.share("counts:$chatId:${ids.sorted().joinToString(",")}") {
                pacer.background {
                    // An entry without `commentsInfo` reports no discussion for the post: no counter.
                    c.api.messages.getCommentsInfo(parseId(chatId), ids).mapNotNull { info ->
                        info.totalCount?.let { IosCommentCount(postId = info.postId.toString(), count = it) }
                    }
                }
            }
        }
    }

    /** Posts a comment under [postId]; [replyTo] (a comment id) is optional. */
    fun sendComment(chatId: String, postId: String, text: String, replyTo: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val sent = c.api.messages.sendComment(
                parseId(chatId),
                parseId(postId),
                text,
                replyTo = replyTo.takeIf { it.isNotBlank() }?.let(::parseId),
            )
            resolveUsers(c, listOfNotNull(sent.sender))
            messageSnapshot(sent, chatId, c.store.state.value)
        }
    }

    /**
     * Sets this account's reaction on message [messageId] to [reaction], or removes it when
     * [reaction] is empty (`MSG_REACTION` 178 / `MSG_CANCEL_REACTION` 179). A non-empty [postId]
     * marks a comment of that channel post. [onResult] gets the reactions the server sent back
     * in the [IosMessage.reactionsJson] format, or an empty string when the reply had none.
     */
    fun setReaction(chatId: String, messageId: String, postId: String, reaction: String, onResult: (String?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val info = c.setReaction(
                parseId(chatId),
                parseId(messageId),
                reaction.takeIf { it.isNotEmpty() },
                postId.takeIf { it.isNotBlank() }?.let(::parseId),
            )
            info?.let { reactionsJson(it) }.orEmpty()
        }
    }

    /**
     * Current reactions of several messages (`MSG_GET_REACTIONS` 180). Ids that are not numbers
     * are skipped; messages the server left out are missing from the list.
     */
    fun loadReactions(chatId: String, messageIds: List<String>, onResult: (List<IosReactions>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val ids = messageIds.mapNotNull { it.toLongOrNull() }.distinct()
            if (ids.isEmpty()) return@perform emptyList()
            flights.share("reactions:$chatId:${ids.sorted().joinToString(",")}") {
                ids.chunked(REACTIONS_PAGE).flatMap { page ->
                    pacer.background { c.loadReactions(parseId(chatId), page) }.map { (id, info) ->
                        // Без ключа `yourReaction` своя реакция неизвестна, а не «нет».
                        val mineKnown = info.yourReaction != null || info.raw.containsKey("yourReaction")
                        IosReactions(id.toString(), reactionsJson(info, mineKnown = mineKnown))
                    }
                }
            }
        }
    }

    /**
     * Sends [text] with animated emoji: each [animoji] mark becomes an `ANIMOJI` element
     * (`{type, from, length, entityId, attributes: {animojiLottieUrl}}`, KometTeam/Komet
     * `RichMessageController`) over the emoji at `from` (UTF-16 offsets).
     */
    fun sendText(
        chatId: String,
        text: String,
        replyTo: String,
        animoji: List<IosAnimojiMark>,
        onResult: (IosMessage?, String?, String?) -> Unit,
    ) {
        perform(onResult, { null }) { c ->
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            messageSnapshot(c.sendText(parseId(chatId), text, reply, animojiElements(text, animoji)), chatId, c.store.state.value)
        }
    }

    /** Text with animated emoji and `USER_MENTION` marks. Offsets are UTF-16 indexes into [text]. */
    fun sendRichText(
        chatId: String,
        text: String,
        replyTo: String,
        animoji: List<IosAnimojiMark>,
        mentions: List<IosMentionMark>,
        onResult: (IosMessage?, String?, String?) -> Unit,
    ) {
        perform(onResult, { null }) { c ->
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            val elements = animojiElements(text, animoji) + mentionElements(text, mentions)
            messageSnapshot(c.sendText(parseId(chatId), text, reply, elements), chatId, c.store.state.value)
        }
    }

    /**
     * Sends [text] with formatting [marks] (`elements` of `MSG_SEND`): bold, italic, underline,
     * strikethrough, monospace, heading, quote, link, mention and animated emoji ([IosTextMark],
     * types in [IosTextMarkType]). Offsets are UTF-16 indexes into [text]; a mark outside the
     * text, a link without url, a mention without a numeric user id or an animoji without id and
     * Lottie address is dropped. A non-empty [replyTo] makes it a reply.
     */
    fun sendFormattedText(chatId: String, text: String, replyTo: String, marks: List<IosTextMark>, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            messageSnapshot(c.sendFormattedText(parseId(chatId), text, textElementsOf(marks), reply), chatId, c.store.state.value)
        }
    }

    /**
     * Sends [text] with formatting given as JSON (see the `elementsJson` [editMessage]); an
     * element outside the text is dropped, malformed JSON fails (`UNKNOWN`) before sending.
     */
    fun sendFormattedText(chatId: String, text: String, elementsJson: String, replyTo: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val elements = TextElementsJson.parse(elementsJson, text.length)
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            messageSnapshot(c.sendFormattedText(parseId(chatId), text, elements, reply), chatId, c.store.state.value)
        }
    }

    /**
     * Saves the server draft of [chatId] (`DRAFT_SAVE` 176; dialogs and Saved Messages go by the
     * peer's user id). [elementsJson] as in [sendFormattedText], a non-empty [replyTo] is the
     * message the draft answers. [onResult] gets the server time of the draft (0 on failure).
     */
    fun saveDraft(chatId: String, text: String, elementsJson: String, replyTo: String, onResult: (Long, String?, String?) -> Unit) {
        perform(onResult, { 0L }) { c ->
            val elements = TextElementsJson.parse(elementsJson, text.length)
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            c.saveDraft(parseId(chatId), text, elements, reply).updateTime
        }
    }

    /**
     * Discards the server draft of [chatId] (`DRAFT_DISCARD` 177). [time] is the draft time to
     * discard; `0` takes the stored draft's. Without either nothing is sent and the local draft
     * is dropped.
     */
    fun discardDraft(chatId: String, time: Long, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c -> c.discardDraft(parseId(chatId), time.takeIf { it > 0 }) }
    }

    /**
     * The server drafts the core holds (from `LOGIN`, [saveDraft] and drafts changed on another
     * device, pushes 152 / 153), newest first. A successful send ([sendText], [sendFormattedText],
     * [sendMedia]) clears the chat's draft and discards it on the server by itself: the app need
     * not call [discardDraft] after sending. Changes come as `draft` events of [watchEvents].
     */
    fun drafts(): List<IosDraft> = attempt(emptyList()) {
        client().drafts.values.sortedByDescending { it.updateTime }.map(::draftSnapshot)
    }

    /** Sends sticker [stickerId] of the catalog; a non-empty [replyTo] makes it a reply. */
    fun sendSticker(chatId: String, stickerId: String, replyTo: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            messageSnapshot(c.sendSticker(parseId(chatId), parseId(stickerId), reply), chatId, c.store.state.value)
        }
    }

    /** Sticker sets in panel order (the account's first) with their metadata, and recent stickers. */
    fun loadStickerCatalog(onResult: (IosStickerCatalog?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val sections = c.stickerSections()
            val sets = c.stickerSets(sections.setIds).map { set ->
                IosStickerSet(
                    set.id.toString(), set.name, set.iconUrl.orEmpty(), set.stickerIds.map(Long::toString),
                    set.link.orEmpty(), set.id in sections.favoriteSetIds,
                )
            }
            IosStickerCatalog(sets, sections.recentStickerIds.map(Long::toString))
        }
    }

    /** Stickers by id in the given order; unknown ids are left out. */
    fun loadStickers(ids: List<String>, onResult: (List<IosSticker>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val wanted = ids.mapNotNull { it.toLongOrNull() }
            if (wanted.isEmpty()) return@perform emptyList()
            c.stickers(wanted).map { s ->
                IosSticker(
                    s.id.toString(), s.url, s.lottieUrl.orEmpty(), s.setId?.toString().orEmpty(),
                    s.width ?: 0, s.height ?: 0, s.tags,
                )
            }
        }
    }

    /** The animated emoji catalog (lottie per emoji), in server order; may be empty. */
    fun loadAnimojis(onResult: (List<IosAnimoji>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.reactionCatalog().map { IosAnimoji(it.id.toString(), it.emoji, it.iconUrl.orEmpty(), it.lottieUrl.orEmpty()) }
        }
    }

    /** Emoji the server offers for reactions (the animoji catalog), in its order; may be empty. */
    fun loadReactionCatalog(onResult: (List<String>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c -> c.reactionCatalog().map { it.emoji } }
    }

    /**
     * Speech to text of a voice message (`AUDIO_TRANSCRIPTION` 202). [audioId] is the
     * attachment's `audioId`. The result's status is `1` with the text (empty: no speech),
     * `0` while the server works on it (the text then comes as a `transcription` event) or
     * `-1`.
     */
    fun transcribeVoice(chatId: String, messageId: String, audioId: String, onResult: (IosTranscription, String?, String?) -> Unit) {
        perform(onResult, { IosTranscription(-1, "") }) { c ->
            val result = c.transcribe(parseId(chatId), parseId(messageId), parseId(audioId))
            IosTranscription(result.status, result.text.orEmpty())
        }
    }

    /** Who reacted to a message (`MSG_GET_DETAILED_REACTIONS` 181), with names from the store. */
    fun loadReactionUsers(chatId: String, messageId: String, onResult: (List<IosReactionUser>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val users = c.loadReactionUsers(parseId(chatId), parseId(messageId))
            val state = c.store.state.value
            users.map { entry ->
                val user = state.users[entry.userId]
                IosReactionUser(entry.userId.toString(), state.displayName(entry.userId).orEmpty(), user?.baseUrl.orEmpty(), entry.reaction)
            }
        }
    }

    /**
     * Who read a group message ([MaxClient.loadMessageReaders]): the users who reacted first, in
     * the server's order with their emoji, then the members whose read mark reaches the message,
     * latest mark first (equal marks by user id); never this account or the author. The list is
     * empty for chats that do not show readers (see [isReadersAvailable]). Every call refreshes
     * the chat's read marks from the server. Names come from the store, users missing there are
     * fetched first; [IosMessageReader.name] is `null` when the user is still unknown. When the
     * reactions cannot be loaded the list holds the readers only, without an error.
     */
    fun loadMessageReaders(chatId: String, messageId: String, onResult: (List<IosMessageReader>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val readers = c.loadMessageReaders(parseId(chatId), parseId(messageId))
            val state = c.store.state.value
            readers.map { reader ->
                IosMessageReader(
                    userId = reader.userId.toString(),
                    name = state.displayName(reader.userId)?.trim()?.takeIf { it.isNotEmpty() },
                    reaction = reader.reaction,
                    readMark = reader.readMark ?: 0L,
                )
            }
        }
    }

    /**
     * Whether [chatId] shows who read its messages ([MaxClient.isMessageReadersAvailable]): a
     * group (`CHAT`) without a running group call and with at most `max-readmarks` members (server
     * config, 100 by default). Never dialogs, Saved Messages, channels or comments. Answers from
     * the stored chat without a request; `false` for a malformed id or a chat not loaded yet.
     */
    fun isReadersAvailable(chatId: String): Boolean {
        val id = chatId.toLongOrNull() ?: return false
        return attempt(false) { client().isMessageReadersAvailable(id) }
    }

    fun markRead(chatId: String, messageId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.api.messages.markRead(parseId(chatId), parseId(messageId)) }
    }

    /**
     * Marks [chatId] read up to [messageId] (`CHAT_MARK` 50) with [mark], the server time of that
     * message in ms, as the read boundary. The device clock may lag behind the server: a boundary
     * taken from it would leave the newest messages unread on the server and for their sender.
     * For `0` or less the time is looked up in the store, and only an unknown message falls back
     * to the core's clock, as [markRead] does.
     *
     * The reply goes into the store at once (own read mark and unread counter), unless the store
     * already holds a newer own mark: replies to two marks in a row may arrive out of order. The
     * store also feeds chat events, so a stale counter there would bring the badge back. The
     * server's count replaces the store's only when it is lower and no message arrived since.
     * [onResult] gets the server's unread count and the mark it kept.
     */
    fun markReadAt(chatId: String, messageId: String, mark: Long, onResult: (IosReadMark, String?, String?) -> Unit) {
        perform(onResult, { IosReadMark(0, 0) }) { c ->
            val id = parseId(chatId)
            val message = parseId(messageId)
            val before = c.store.state.value
            val lastBefore = before.chats[id]?.lastMessage?.id
            val time = mark.takeIf { it > 0 }
                ?: before.messagesOf(id).firstOrNull { it.id == message }?.time
                ?: before.chats[id]?.lastMessage?.takeIf { it.id == message }?.time
            val reply = c.api.messages.markRead(id, message, time)
            val state = c.store.state.value
            val me = state.me
            if (me != null && reply.mark >= (state.readMarks[id]?.get(me) ?: 0L)) {
                c.store.apply(MaxEvent.MessageRead(id, me, reply.mark, false, Opcode.CHAT_MARK.value, null))
                val after = c.store.state.value.chats[id]
                if (after != null && after.lastMessage?.id == lastBefore && reply.unread in 0 until after.newMessages) {
                    c.store.putChats(listOf(after.copy(newMessages = reply.unread)))
                }
            }
            IosReadMark(reply.unread, reply.mark)
        }
    }

    /** Marks the chat unread from [mark] (message time, ms). [onResult] gets the server unread count. */
    fun markUnread(chatId: String, mark: Long, onResult: (Int, String?, String?) -> Unit) {
        perform(onResult, { 0 }) { it.markUnread(parseId(chatId), mark) }
    }

    /**
     * Sets the pinned chats to [chatIds] (decimal ids, top first): pin, unpin and reorder all send
     * the whole new list ([MaxClient.setPinnedChats], `FOLDERS_UPDATE` 274). [onResult] gets the
     * pinned ids the server confirmed; on failure the core keeps the previous pins and the app
     * rolls its optimistic change back.
     */
    fun setPinnedChats(chatIds: List<String>, onResult: (List<String>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.setPinnedChats(chatIds.map(::parseId)).map { it.toString() }
        }
    }

    /**
     * The server's pinned chats, top first, now and after every change: `LOGIN` config, folder
     * resync, own [setPinnedChats] and `NOTIF_FOLDERS` pushes from other devices. Nothing is
     * delivered while the pins are unknown (before the folders arrived, after logout), so an empty
     * list always means "nothing pinned".
     */
    fun watchPinnedChats(onEach: (List<String>) -> Unit): IosWatch = watch { c ->
        c.watchPinnedChats { ids -> if (ids != null) guarded { onEach(ids.map { it.toString() }) } }
    }

    // ---- settings -------------------------------------------------------------------------

    /** The own profile ([IosMyProfile]); `CONTACT_INFO` for it when the store has none. */
    fun loadMyProfile(onResult: (IosMyProfile?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val me = c.loadMe() ?: throw IllegalStateException("own profile not found")
            myProfileSnapshot(me, c.accountConfig.value)
        }
    }

    /** Changes the name and the "about" text (`PROFILE` 16); an empty [description] clears it. */
    fun updateProfile(firstName: String, lastName: String, description: String, onResult: (IosMyProfile?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val profile = c.updateProfile(firstName.trim(), lastName.trim(), description.trim())
            myProfileSnapshot(profile.contact, c.accountConfig.value)
        }
    }

    /** Uploads [image] (JPEG) as the new avatar ([MaxClient.uploadAvatar]). */
    fun uploadAvatar(image: NSData, onResult: (IosMyProfile?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val profile = c.uploadAvatar(image.toByteArray(), "avatar.jpg")
            myProfileSnapshot(profile.contact, c.accountConfig.value)
        }
    }

    /** Removes the avatar (`REMOVE_CONTACT_PHOTO` 43); a profile without one stays as it is. */
    fun removeAvatar(onResult: (IosMyProfile?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val contact = c.removeAvatar()?.contact ?: c.loadMe() ?: throw IllegalStateException("own profile not found")
            myProfileSnapshot(contact, c.accountConfig.value)
        }
    }

    /**
     * Public chats and channels by name or link (`PUBLIC_SEARCH` 60), page [from]..[from]+[count].
     * People in the reply are skipped: a found user has no chat to open yet. A blank query gives
     * an empty list without a request.
     */
    fun searchPublic(query: String, from: Int, count: Int, onResult: (List<IosSearchChat>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.api.search.searchPublic(query, from, count).mapNotNull { hit ->
                val chat = hit.chat ?: return@mapNotNull null
                IosSearchChat(
                    id = chat.id.toString(),
                    type = chat.type,
                    title = chat.title?.trim().orEmpty(),
                    subtitle = hit.link?.let { "@$it" } ?: chat.lastMessage?.text?.trim().orEmpty(),
                    avatarUrl = hit.iconUrl.orEmpty(),
                    participantsCount = chat.participantsCount,
                )
            }
        }
    }

    /** Messages in all of the user's chats (`CHAT_SEARCH` 68 without a chat), newest as the server orders them. */
    fun searchMessages(query: String, count: Int, onResult: (List<IosFoundMessage>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.api.search.searchMessages(query, count).map { hit ->
                IosFoundMessage(
                    chatId = hit.chatId.toString(),
                    messageId = hit.message.id.toString(),
                    text = hit.message.text.trim(),
                    timeMs = hit.message.time,
                    senderId = hit.message.sender?.toString().orEmpty(),
                )
            }
        }
    }

    /**
     * Schedules the account deletion (`PROFILE_DELETE` 199); [onResult] gets the deletion time in
     * Unix milliseconds (0 when the server did not say). The app logs out afterwards.
     */
    fun deleteAccount(onResult: (Long, String?, String?) -> Unit) {
        perform(onResult, { 0L }) { c ->
            val ts = c.api.account.requestProfileDeletion(true) ?: 0L
            if (ts in 1 until 100_000_000_000L) ts * 1000 else ts
        }
    }

    /** The current settings ([IosAccountSettings], `known = false` before the first config). */
    fun accountSettings(): IosAccountSettings = attempt(settingsSnapshot(null)) { settingsSnapshot(client().accountConfig.value) }

    /** [accountSettings] now and after every change (login, own change). */
    fun watchAccountSettings(onEach: (IosAccountSettings) -> Unit): IosWatch = watch { c ->
        c.watchAccountConfig { config -> guarded { onEach(settingsSnapshot(config)) } }
    }

    /** "Who sees my phone number": `ALL`, `CONTACTS` or `NOBODY` (`PHONE_NUMBER_PRIVACY`). */
    fun setPhonePrivacy(value: String, onResult: (IosAccountSettings?, String?, String?) -> Unit) {
        val wire = value.uppercase()
        updateSettings(onResult) {
            require(wire in setOf("ALL", "CONTACTS", "NOBODY")) { "bad phone privacy: $value" }
            mapOf<String, Any?>("PHONE_NUMBER_PRIVACY" to wire)
        }
    }

    /** Hides the online status from everybody (`HIDDEN: true`) or shows it to contacts (`false`). */
    fun setOnlineHidden(hidden: Boolean, onResult: (IosAccountSettings?, String?, String?) -> Unit) {
        updateSettings(onResult) { mapOf<String, Any?>("HIDDEN" to hidden) }
    }

    /**
     * Safe mode as Komet sets it: on sends `SAFE_MODE`, `SAFE_MODE_NO_PIN`, `CONTENT_LEVEL_ACCESS`
     * `true` and `SEARCH_BY_PHONE`, `INCOMING_CALL`, `CHATS_INVITE` `CONTACTS`; off sends only
     * `SAFE_MODE` and `SAFE_MODE_NO_PIN` `false`.
     */
    fun setSafeMode(enabled: Boolean, onResult: (IosAccountSettings?, String?, String?) -> Unit) {
        updateSettings(onResult) {
            if (enabled) {
                linkedMapOf<String, Any?>(
                    "INCOMING_CALL" to "CONTACTS", "SEARCH_BY_PHONE" to "CONTACTS", "SAFE_MODE_NO_PIN" to true,
                    "CONTENT_LEVEL_ACCESS" to true, "CHATS_INVITE" to "CONTACTS", "SAFE_MODE" to true,
                )
            } else {
                linkedMapOf<String, Any?>("SAFE_MODE_NO_PIN" to false, "SAFE_MODE" to false)
            }
        }
    }

    /** Deletes the account after this much inactivity: `1M`, `3M` or `6M` (`INACTIVE_TTL`). */
    fun setInactiveTtl(value: String, onResult: (IosAccountSettings?, String?, String?) -> Unit) {
        val wire = value.uppercase()
        updateSettings(onResult) {
            require(wire in INACTIVE_TTLS) { "bad inactive ttl: $value" }
            mapOf<String, Any?>("INACTIVE_TTL" to wire)
        }
    }

    /** Double-tap reaction (`DOUBLE_TAP_REACTION_VALUE`) and turns the feature on. */
    fun setQuickReaction(emoji: String, onResult: (IosAccountSettings?, String?, String?) -> Unit) {
        val clean = emoji.trim()
        updateSettings(onResult) {
            require(clean.isNotEmpty() && clean.length <= 32) { "bad quick reaction" }
            linkedMapOf<String, Any?>(
                "DOUBLE_TAP_REACTION_VALUE" to clean,
                "DOUBLE_TAP_REACTION_DISABLED" to false,
            )
        }
    }

    /** Active sessions (`SESSIONS_INFO` 96), the current one first. */
    fun loadSessions(onResult: (List<IosSession>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.loadSessions().map(::sessionSnapshot).sortedByDescending { it.current }
        }
    }

    /** Ends every other session (`SESSIONS_CLOSE` 97); the core keeps the new token. */
    fun closeOtherSessions(onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.closeOtherSessions() }
    }

    /** Approves a login on another device from its QR code ([MaxClient.approveQrLogin]). */
    fun approveQrLogin(qrLink: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.approveQrLogin(qrLink) }
    }

    /** The black list (`CONTACT_LIST` 36 pages of 100, at most [BLOCKED_PAGES] of them). */
    fun loadBlockedUsers(onResult: (List<IosBlockedUser>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val all = ArrayList<IosBlockedUser>()
            var from = 0
            repeat(BLOCKED_PAGES) pages@{
                if (from < 0) return@pages
                val users = c.api.users.blockedContacts(from, BLOCKED_PAGE_SIZE)
                all += users.map(::blockedSnapshot)
                // a short or empty page is the last one
                from = if (users.size < BLOCKED_PAGE_SIZE) -1 else from + users.size
            }
            all.distinctBy { it.id }
        }
    }

    /** Unblocks [userId] (`CONTACT_UPDATE` 34 `UNBLOCK`). */
    fun unblockUser(userId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.api.users.setBlocked(parseId(userId), false) }
    }

    /** Blocks [userId] (`CONTACT_UPDATE` 34 `BLOCK`): the user lands in the black list. */
    fun blockUser(userId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.api.users.setBlocked(parseId(userId), true) }
    }

    /**
     * Chats shared with [userId] (`CHAT_SEARCH_COMMON_PARTICIPANTS` 198, [ChatsApi.commonChats]).
     * Thin entries: id, type, title, icon and the participant count.
     */
    fun commonChats(userId: String, onResult: (List<IosCommonChat>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.api.chats.commonChats(parseId(userId)).map { chat ->
                IosCommonChat(
                    id = chat.id.toString(),
                    type = chat.type,
                    title = chat.title,
                    iconUrl = chat.iconUrl.orEmpty(),
                    participants = chat.participantsCount,
                )
            }
        }
    }

    /**
     * Complaint reasons for [typeId] (`COMPLAIN_REASONS_GET` 162, [ComplaintsApi.reasons]):
     * [ComplaintsApi.CHANNEL] `2` for a channel, [ComplaintsApi.USER] `6` for a person.
     */
    fun complaintReasons(typeId: Int, onResult: (List<IosComplaintReason>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.api.complaints.reasons()[typeId].orEmpty().map { IosComplaintReason(it.reasonId, it.reasonTitle) }
        }
    }

    /**
     * Sends a complaint (`COMPLAIN` 161, [ComplaintsApi.send]) about [ids] of [typeId]. The result
     * is `"ok"` when the server answered `success: true`, otherwise empty (a string, not a
     * boxed Boolean, for Swift).
     */
    fun sendComplaint(reasonId: Int, typeId: Int, ids: List<String>, onResult: (String, String?, String?) -> Unit) {
        perform(onResult, { "" }) { c ->
            if (c.api.complaints.send(reasonId, typeId, ids.map(::parseId))) "ok" else ""
        }
    }

    /**
     * The stories feed (`STORIES_LIST` 208, [StoriesApi.feed]): one ring per owner, as the server
     * orders them; empty rings are left out. A background read: it waits for the reads the user
     * is looking at. Names and avatars of person owners come from the store, missing ones are
     * asked first (best effort).
     */
    fun loadStoriesFeed(onResult: (List<IosStoryPreview>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val rings = flights.share("stories") { pacer.background { c.api.stories.feed() } }.filterNot { it.isEmpty }
            resolveUsers(c, rings.filter { it.owner.type == StoryOwner.Type.USER }.map { it.owner.ownerId })
            val state = c.store.state.value
            rings.map { storyPreviewSnapshot(it, state) }
        }
    }

    /**
     * Stories of one owner (`STORIES_GET_BY_OWNER_ID` 210, [StoriesApi.byOwners]): [ownerId]
     * decimal, [type] `0` person, `1` group, `2` channel. The user opened the ring, so this read
     * goes at once. [IosOwnerStories.preview] is `null` when the owner has no stories (left).
     */
    fun loadOwnerStories(ownerId: String, type: Int, onResult: (IosOwnerStories?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val owner = StoryOwner(parseId(ownerId), storyOwnerType(type))
            val reply = pacer.priority { c.api.stories.byOwners(listOf(owner)) }
            if (owner.type == StoryOwner.Type.USER) resolveUsers(c, listOf(owner.ownerId))
            val state = c.store.state.value
            IosOwnerStories(
                preview = reply.previews.firstOrNull { it.owner.ownerId == owner.ownerId && !it.isEmpty }?.let { storyPreviewSnapshot(it, state) },
                stories = reply.storiesOf(owner).orEmpty().map(::storySnapshot),
            )
        }
    }

    /** Marks story [storyId] of the owner seen (`STORIES_MARK` 214); paced like other background writes of the viewer. */
    fun markStorySeen(ownerId: String, type: Int, storyId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c ->
            val owner = StoryOwner(parseId(ownerId), storyOwnerType(type))
            pacer.background { c.api.stories.mark(owner, parseId(storyId)) }
        }
    }

    /**
     * Publishes the photo or video at [path] as the account's story for a day: [kind] `photo` or
     * `video`, [durationMs] the video's length (`0` unknown), [audience] `1` everyone, `2`
     * contacts. Story upload slot (`type` 1 photo / 3 video) → upload → `STORIES_SEND` 215.
     * Cancel with the task. The file must stay readable until [onResult].
     */
    fun publishStory(
        path: String,
        kind: String,
        durationMs: Long,
        audience: Int,
        onProgress: (IosUploadProgress) -> Unit,
        onResult: (IosPublishedStory?, String?, String?) -> Unit,
    ): IosTask = IosTask(
        perform(onResult, { null }) { c ->
            val who = if (audience == StoryAudience.CONTACTS.code) StoryAudience.CONTACTS else StoryAudience.EVERYONE
            val progress = percentProgress(onProgress)
            val published = when (kind) {
                "photo" -> c.api.stories.publishPhoto(c.media.uploadStoryPhoto(path, progress), who)
                "video" -> c.api.stories.publishVideo(c.media.uploadStoryVideo(path, progress), durationMs.takeIf { it > 0 }, who)
                else -> throw IllegalArgumentException("unknown story kind \"$kind\"")
            }
            val state = c.store.state.value
            IosPublishedStory(published.preview?.takeIf { !it.isEmpty }?.let { storyPreviewSnapshot(it, state) }, published.stories.map(::storySnapshot))
        },
    )

    /** Deletes the account's own stories [storyIds] (`STORIES_DELETE` 218). */
    fun deleteStories(storyIds: List<String>, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c -> c.api.stories.delete(storyIds.map(::parseId)) }
    }

    /** The whole contact list (opcode 8 `{contactsSync: 0}`) into the store, then as [loadContacts]. */
    fun syncContacts(onResult: (List<IosContact>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.syncContacts()
            val state = c.store.state.value
            listedContacts(state, c.presenceTtlMs)
        }
    }

    /**
     * A user by phone (`CONTACT_INFO_BY_PHONE` 46, `{phone}`). Does not add them to contacts.
     * [phone] is `+` plus digits. A reply without `contact` fails as `MALFORMED_REPLY`.
     */
    fun findByPhone(phone: String, onResult: (IosContact?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val user = c.api.users.findByPhone(phone)
            c.store.putUsers(listOf(user))
            contactSnapshot(user, c.store.state.value, c.presenceTtlMs)
        }
    }

    /**
     * Adds [userId] (`CONTACT_UPDATE` 34, `{contactId, action: "ADD"}`). A non-blank [firstName]
     * is sent; a blank one is left out, so the body stays `{contactId, action}`. Last name is not sent.
     */
    fun addContact(userId: String, firstName: String, onResult: (IosContact?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val name = firstName.trim()
            val user = c.api.users.addContact(parseId(userId), if (name.isEmpty()) null else name)
            c.store.putContacts(listOf(user))
            contactSnapshot(user, c.store.state.value, c.presenceTtlMs)
        }
    }

    /**
     * Renames contact [userId] for this account (`CONTACT_UPDATE` 34, `action: "UPDATE"` with
     * [firstName] and [lastName]; an empty [lastName] goes out as `null`, each name at most 64
     * characters). The name becomes the contact's `CUSTOM` name ([displayName]). An empty
     * [firstName] is allowed (web client): with a last name the server shows the person's own
     * first name, with both empty the original names come back. A name over 64 characters fails
     * (`UNKNOWN`).
     */
    fun renameContact(userId: String, firstName: String, lastName: String, onResult: (IosContact?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val user = c.renameContact(parseId(userId), firstName, lastName.takeIf { it.isNotBlank() })
            contactSnapshot(user, c.store.state.value, c.presenceTtlMs)
        }
    }

    /**
     * Removes contact [userId] (`CONTACT_UPDATE` 34, `action: "REMOVE"`). The store drops it from
     * the contact list and forgets its contact name; the chat with the user stays. [onResult]
     * gets the contact of the reply (`null` when the reply had none); undo is [addContact].
     */
    fun removeContact(userId: String, onResult: (IosContact?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val id = parseId(userId)
            val reply = c.removeContact(id)
            val state = c.store.state.value
            (reply ?: state.users[id])?.let { contactSnapshot(it, state, c.presenceTtlMs) }
        }
    }

    /**
     * Adds a contact by phone number (`CONTACT_ADD_BY_PHONE` 41, `{phone, firstName?, lastName?}`;
     * blank names are left out). [onResult] gets the contact and whether it is new.
     */
    fun addContactByPhone(phone: String, firstName: String, lastName: String, onResult: (IosContact?, Boolean, String?, String?) -> Unit) {
        perform<Pair<IosContact?, Boolean>>({ value, kind, key -> onResult(value.first, value.second, kind, key) }, { null to false }) { c ->
            val added = c.addContactByPhone(phone, firstName.takeIf { it.isNotBlank() }, lastName.takeIf { it.isNotBlank() })
            contactSnapshot(added.user, c.store.state.value, c.presenceTtlMs) to added.isNew
        }
    }

    /**
     * Hands the device address book to the core without a request: its names are matched to
     * users by phone for [displayName] and every name the bridge reports (chat titles, authors,
     * contacts). It replaces the previous book, survives a switch to another account and is
     * dropped on logout. Nothing goes to the server. Phones are normalized as the core does
     * ([com.max.core.api.PhoneNumbers]); for a number listed twice the first non-empty name wins.
     * Entries without a phone or a first name are skipped.
     */
    fun setAddressBook(entries: List<IosPhoneContact>) {
        attempt(Unit) { client().setAddressBook(phoneContactsOf(entries)) }
    }

    /**
     * Sets the address-book name of one user without a request (for a user matched by the app
     * itself); an empty [name] clears it. It counts like a phone-book name in [displayName].
     */
    fun setLocalName(userId: String, name: String) {
        val id = userId.toLongOrNull() ?: return
        attempt(Unit) { client().setLocalName(id, name.takeIf { it.isNotBlank() }) }
    }

    /**
     * The name to show for [userId] (the core resolver): the address-book name, else the contact
     * name this account set (`CUSTOM`), else the own profile name (`ONEME`), else the first name
     * entry, else the phone. Empty when nothing is known (the lists show "Участник" then).
     */
    fun displayName(userId: String): String {
        val id = userId.toLongOrNull() ?: return ""
        return attempt("") { client().displayName(id).orEmpty() }
    }

    /**
     * The name rule of [displayName] and of every name the bridge reports: `true` (default) the
     * address book wins over the contact name this account set, `false` the own contact name
     * (`CUSTOM`) wins over the address book. A device setting: it survives logout and account
     * switches, not app restarts (set it again at start).
     */
    fun setPreferAddressBookNames(prefer: Boolean) {
        attempt(Unit) { client().preferAddressBookNames = prefer }
    }

    /** [setPreferAddressBookNames]; `true` when the client cannot be created. */
    fun preferAddressBookNames(): Boolean = attempt(true) { client().preferAddressBookNames }

    // ---- Presence -------------------------------------------------------------------------------

    /**
     * The presence the core holds for [userId], with the server's `presence-ttl` applied: an
     * "online" not refreshed within it reads as offline. [IosPresence.status] `-1` when nothing
     * is known.
     */
    fun presenceOf(userId: String): IosPresence {
        val id = userId.toLongOrNull() ?: return IosPresence(userId, PresenceStatus.UNKNOWN, 0)
        return attempt(IosPresence(userId, PresenceStatus.UNKNOWN, 0)) {
            val c = client()
            presenceSnapshot(id, c.presenceOf(id))
        }
    }

    /**
     * Asks the presence of [userIds] (`CONTACT_PRESENCE` 35, batches of 100) and stores it: the
     * changes also come as `presence` events of [watchEvents]. [onResult] gets one entry per
     * distinct valid id, in order; an id the server leaves out is `3` (long ago). Empty or
     * non-numeric ids are skipped; nothing to ask gives an empty list without a request.
     */
    fun loadPresence(userIds: List<String>, onResult: (List<IosPresence>?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val ids = userIds.mapNotNull { it.trim().toLongOrNull() }.distinct()
            val got = c.loadPresence(ids)
            ids.map { id -> presenceSnapshot(id, got[id]) }
        }
    }

    /**
     * The app went to the foreground (`true`) or the background (`false`): the next `PING`
     * carries `interactive` accordingly, one goes out at once when the value changed, and a
     * reconnect `LOGIN` sends it too. The server decides the presence from it; there is no
     * explicit "going offline" request in the protocol. Fire-and-forget, never fails.
     */
    fun setAppActive(active: Boolean) {
        val c = attempt<MaxClient?>(null) { client() } ?: return
        scope.launch {
            try {
                c.setInteractive(active)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // nothing to report: the next PING carries the flag anyway
            }
        }
    }

    /**
     * Creates a group (`MSG_SEND` 64, CONTROL `event: new`, `chatType: CHAT`, [ChatsApi.createGroup]).
     * Empty [userIds] are allowed. The caller's own id is dropped. A reply without `chat` is a null
     * chat and no error kind.
     */
    fun createGroup(title: String, userIds: List<String>, onResult: (IosChat?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val name = title.trim()
            if (name.isEmpty()) throw IllegalArgumentException("empty title")
            val me = c.userId.value
            val ids = userIds.mapNotNull { it.toLongOrNull() }.filter { it != me }
            val created = c.api.chats.createGroup(name, ids, true)
            if (created == null) {
                null
            } else {
                c.store.putChats(listOf(created.chat))
                chatSnapshot(created.chat, c.store.state.value, c.accountConfig.value)
            }
        }
    }

    /**
     * Creates a channel with the same `MSG_SEND` 64 body as [createGroup], but `chatType` is
     * `CHANNEL` and `userIds` is empty. Opcode 63 is not used, and this is not a core API method:
     * the bridge sends the packet through [MaxClient.session]. A reply without `chat` is null.
     */
    fun createChannel(title: String, onResult: (IosChat?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val name = title.trim()
            if (name.isEmpty()) throw IllegalArgumentException("empty title")
            val attach = linkedMapOf<String, Any?>(
                "_type" to "CONTROL",
                "event" to "new",
                "chatType" to "CHANNEL",
                "title" to name,
                "userIds" to emptyList<Long>(),
            )
            val payload = linkedMapOf<String, Any?>(
                "message" to linkedMapOf("cid" to c.api.messages.nextCid(), "attaches" to listOf(attach)),
                "notify" to true,
            )
            val map = c.session.request(Opcode.MSG_SEND, payload).payload as? Map<*, *>
            val chat = map?.let { Chat.from(it["chat"]) }
            if (chat == null) {
                null
            } else {
                c.store.putChats(listOf(chat))
                chatSnapshot(chat, c.store.state.value, c.accountConfig.value)
            }
        }
    }

    /**
     * Deletes a chat (`CHAT_DELETE` 52, [ChatsApi.deleteChat]: `chatId`, `lastEventTime`, `forAll`).
     * [lastEventTimeMs] is the chat's last event when the caller has one, otherwise `0` and the
     * core clock fills it. The chat and its messages then leave the store.
     */
    fun deleteChat(chatId: String, lastEventTimeMs: Long, forAll: Boolean, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c ->
            val id = parseId(chatId)
            val stored = c.store.state.value.chats[id]?.lastEventTime?.takeIf { it > 0 }
            val time = stored ?: lastEventTimeMs.takeIf { it > 0 }
            c.api.chats.deleteChat(id, time, forAll)
            c.store.removeChat(id)
        }
    }

    /**
     * Leaves a group or unsubscribes from a channel (`CHAT_LEAVE` 58, [ChatsApi.leaveChat]:
     * `{chatId}`). The chat and its messages then leave the store.
     */
    fun leaveChat(chatId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c ->
            val id = parseId(chatId)
            c.api.chats.leaveChat(id)
            c.store.removeChat(id)
        }
    }

    /**
     * Clears a chat's history (`CHAT_CLEAR` 54). The body is the same three fields as delete.
     * The chat stays. Its messages, preview and unread count are dropped locally.
     */
    fun clearHistory(chatId: String, lastEventTimeMs: Long, forAll: Boolean, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c ->
            val id = parseId(chatId)
            val stored = c.store.state.value.chats[id]?.lastEventTime?.takeIf { it > 0 }
            val time = stored ?: lastEventTimeMs.takeIf { it > 0 } ?: (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()
            c.session.request(
                Opcode.CHAT_CLEAR,
                linkedMapOf("chatId" to id, "lastEventTime" to time, "forAll" to forAll),
            )
            forgetHistory(c, id)
        }
    }

    /** Pins [messageId], or unpins when it is `0` (`CHAT_UPDATE` 55). */
    fun pinMessage(chatId: String, messageId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c -> c.api.messages.pinMessage(parseId(chatId), parseId(messageId)) }
    }

    /** Schedules [text] for [sendAt] (epoch milliseconds). */
    fun scheduleMessage(chatId: String, text: String, sendAt: Long, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c -> c.api.messages.scheduleMessage(parseId(chatId), text.trim(), sendAt) }
    }

    /** Messages waiting to be sent (`CHAT_HISTORY`, `itemType = DELAYED`). */
    fun scheduledMessages(chatId: String, onResult: (List<IosFoundMessage>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val page = c.api.messages.getChatHistory(parseId(chatId), itemType = com.max.core.api.HistoryItemType.DELAYED)
            page.messages.map { message ->
                IosFoundMessage(chatId, message.id.toString(), message.text.trim(), message.time, message.sender?.toString().orEmpty())
            }
        }
    }

    /** Sends a poll. Fewer than two answers is an error. */
    fun sendPoll(chatId: String, title: String, answers: List<String>, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val options = answers.map { it.trim() }.filter { it.isNotEmpty() }
            if (options.size < 2) throw IllegalArgumentException("poll needs two answers")
            val id = parseId(chatId)
            val sent = c.api.messages.sendPoll(
                id,
                com.max.core.media.OutgoingAttachment.Poll(title.trim().ifEmpty { "Опрос" }, options.map { com.max.core.api.PollAnswer(it) }),
            )
            c.store.putSentMessage(id, sent)
            messageSnapshot(sent, chatId, c.store.state.value)
        }
    }

    /** One vote (`SEND_VOTE` 304). */
    fun votePoll(chatId: String, messageId: String, pollId: String, answerId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c ->
            c.api.messages.votePoll(parseId(chatId), parseId(messageId), parseId(pollId), listOf(parseId(answerId)))
        }
    }

    /** Messages inside one chat (`MSG_SEARCH` 73). A blank query does not hit the network. */
    fun searchInChat(chatId: String, query: String, onResult: (List<IosFoundMessage>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val id = parseId(chatId)
            val term = query.trim()
            if (term.isEmpty()) return@perform emptyList()
            val packet = c.session.request(Opcode.MSG_SEARCH, linkedMapOf("chatId" to id, "query" to term, "count" to 30))
            foundFromSearch(id, (packet.payload as? Map<*, *>)?.get("result"))
        }
    }

    /** First page of group or channel members ([loadChatMembers] pages them with roles). */
    fun chatMembers(chatId: String, onResult: (List<IosChatMember>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val members = c.api.chats.getChatMembers(parseId(chatId)).members
            val state = c.store.state.value
            members.mapNotNull { member ->
                val user = MaxUser.from(member.contact) ?: return@mapNotNull null
                IosChatMember(user.id.toString(), labelOf(user, state), user.baseUrl.orEmpty())
            }
        }
    }

    /**
     * One page of group or channel members with roles (`CHAT_MEMBERS` 59). Start with an empty
     * [marker] (or `"0"`) and pass [IosChatMembersPage.nextMarker] until it is empty. [count] is
     * the page size (50 when not positive). Roles come from the chat's owner and admins; the
     * first page of a chat the app has not loaded asks the chat first. Members go into the store,
     * so their names follow [displayName].
     */
    fun loadChatMembers(chatId: String, marker: String, count: Int, onResult: (IosChatMembersPage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val from = marker.trim().takeIf { it.isNotEmpty() }?.let(::parseId) ?: 0L
            val page = c.loadChatMembers(parseId(chatId), from, if (count > 0) count else 50)
            val state = c.store.state.value
            IosChatMembersPage(page.members.mapNotNull { groupMemberSnapshot(it, state, ttlMs = c.presenceTtlMs) }, page.nextMarker?.toString().orEmpty())
        }
    }

    /**
     * Searches the members of a group or channel by name (`CHAT_MEMBERS` 59, `{chatId, type:
     * "MEMBER", query}`), with roles as in [loadChatMembers]. A blank [query] fails (`UNKNOWN`).
     */
    fun searchChatMembers(chatId: String, query: String, onResult: (List<IosGroupMember>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val found = c.searchChatMembers(parseId(chatId), query.trim())
            val state = c.store.state.value
            found.mapNotNull { groupMemberSnapshot(it, state, ttlMs = c.presenceTtlMs) }
        }
    }

    /** Bot menu (`BOT_INFO` 145). Names keep the server's spelling, without a leading slash. */
    fun botCommands(botId: String, onResult: (List<IosBotCommand>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.api.bots.getBotInfo(parseId(botId)).commands.map { IosBotCommand(it.name, it.description.orEmpty()) }
        }
    }

    /**
     * Starts a 1:1 call ([com.max.core.calls.CallsApi.initiateCall], `VIDEO_CHAT_START_ACTIVE` 78).
     * The app opens the call signaling at [IosCallStart.ws2Url]: the reply endpoint with the
     * client params Komet adds (`Ws2Config.fromEndpoint`, [Ws2ClientInfo.forCalls]). ws2, WebRTC
     * and the ringing stay in the app.
     */
    fun startCall(calleeId: String, isVideo: Boolean, onResult: (IosCallStart?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val signal = c.api.calls.initiateCall(parseId(calleeId), isVideo, c.device.deviceId)
            callStart(signal, c.device.userAgent, joinLink = "")
        }
    }

    /**
     * Joins a group call by its link (`VIDEO_CHAT_JOIN_BY_LINK` 166). [link] may be
     * `https://max.ru/joincall/<token>`, `joincall/<token>` or the token; anything else fails
     * with `UNKNOWN` before a request.
     */
    fun joinCall(link: String, isVideo: Boolean, onResult: (IosCallStart?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val token = CallLink.token(link) ?: throw IllegalArgumentException("not a call link")
            val signal = c.api.calls.joinByLink(token, isVideo, c.device.deviceId)
            callStart(signal, c.device.userAgent, joinLink = CallLink.url(token))
        }
    }

    /**
     * A new group call with a link to share (`VIDEO_CHAT_START` 76, then
     * `VIDEO_CHAT_CREATE_JOIN_LINK` 84 when the reply has no link). The creator joins it with
     * [joinCall] like everybody else.
     */
    fun createCallLink(onResult: (IosCallLink?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val created = c.api.calls.createConference()
            val token = CallLink.token(created.joinLink) ?: throw IllegalStateException("no call link")
            IosCallLink(created.conversationId, CallLink.url(token), token, created.callName.orEmpty())
        }
    }

    /** What a call link leads to (`LINK_INFO` 89); `null` when the link names no call. */
    fun callLinkInfo(link: String, onResult: (IosCallLinkInfo?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            c.api.calls.linkInfo(link)?.let { IosCallLinkInfo(CallLink.url(it.token), it.callName.orEmpty(), it.participantsCount, it.isVideo) }
        }
    }

    /** Deletes calls of the log on the server (`VIDEO_CHAT_DELETE_HISTORY` 164); [ids] are [IosCall.id]s. */
    fun deleteCallHistory(ids: List<String>, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c -> c.api.calls.deleteHistory(ids.map(::parseId)) }
    }

    /**
     * Incoming calls (`NOTIF_CALL_START` 137) with a readable `vcp`, in arrival order. An unknown
     * caller waits up to [SENDER_WAIT_MS] for `CONTACT_INFO`, so the call shows a name. A push
     * whose `vcp` is missing or corrupt is dropped: such a call cannot be answered.
     */
    fun watchIncomingCalls(onEach: (IosIncomingCall) -> Unit): IosWatch = watch { c ->
        c.events.all
            .filterIsInstance<MaxEvent.CallStart>()
            .mapNotNull { event ->
                val params = event.params ?: return@mapNotNull null
                withTimeoutOrNull(SENDER_WAIT_MS) { resolveUsers(c, listOf(event.callerId)) }
                incomingCallSnapshot(event, params, c.store.state.value, c.device.userAgent)
            }
            .watch(scope) { call -> guarded { onEach(call) } }
    }

    /** Turns the cloud password on. A blank hint is omitted. */
    fun enablePassword(password: String, hint: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c -> c.api.twoFactor.enable(password, hint = hint.trim().takeIf { it.isNotEmpty() }) }
    }

    fun changePassword(oldPassword: String, newPassword: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c -> c.api.twoFactor.changePassword(oldPassword, newPassword) }
    }

    fun disablePassword(password: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c -> c.api.twoFactor.disable(password) }
    }

    /** Joins by an invite link (`CHAT_JOIN` 57, [ChatsApi.join]). */
    fun joinByLink(link: String, onResult: (IosChat?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val trimmed = link.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("empty link")
            val chat = c.api.chats.join(trimmed)
            c.store.putChats(listOf(chat))
            chatSnapshot(chat, c.store.state.value, c.accountConfig.value)
        }
    }

    /** The cloud password state (`AUTH_CREATE_TRACK` 112 → `AUTH_2FA_DETAILS` 104). */
    fun loadTwoFactor(onResult: (IosTwoFactor?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c -> twoFactorSnapshot(c.api.twoFactor.status()) }
    }

    /** Starts a recovery e-mail change: a track (112) and the current [password] (113); returns the track id. */
    fun startEmailChange(password: String, onResult: (String?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val track = c.api.twoFactor.createTrack()
            c.api.twoFactor.checkCurrentPassword(track, password)
            track
        }
    }

    /** Mails a code to [email] (109); returns the seconds until a new code may be requested. */
    fun sendEmailCode(trackId: String, email: String, onResult: (Int, String?, String?) -> Unit) {
        perform(onResult, { 0 }) { c -> c.api.twoFactor.sendEmailCode(trackId, email.trim()) }
    }

    /** Checks the mailed [code] (110) and saves the e-mail (111 `[4]`); returns the new state. */
    fun confirmEmail(trackId: String, code: String, onResult: (IosTwoFactor?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            c.api.twoFactor.confirmEmailCode(trackId, code.trim())
            c.api.twoFactor.commitEmail(trackId)
            twoFactorSnapshot(c.api.twoFactor.status())
        }
    }

    /**
     * Launch data of a settings mini app (`WEB_APP_INIT_DATA` 160): [app] is `sferum` or
     * `digitalId`; the bot comes from the server config ([AccountConfig.entryAppBotId]).
     */
    fun launchMiniApp(app: String, onResult: (IosMiniApp?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val entry = when (app) {
                "sferum" -> EntryApp.SFERUM
                "digitalId" -> EntryApp.DIGITAL_ID
                else -> throw IllegalArgumentException("unknown mini app: $app")
            }
            val botId = (c.accountConfig.value ?: AccountConfig()).entryAppBotId(entry)
            miniAppSnapshot(botId, c.api.bots.getWebAppInitData(botId))
        }
    }

    /**
     * A bot's mini app (`WEB_APP_INIT_DATA` 160): the "Open app" button of a bot chat or an
     * `OPEN_APP` inline button. [chatId] and [startParam] are left out of the request when empty.
     */
    fun launchBotApp(botId: String, chatId: String, startParam: String, onResult: (IosMiniApp?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val bot = parseId(botId)
            val chat = chatId.takeIf { it.isNotBlank() }?.let(::parseId)
            miniAppSnapshot(bot, c.api.bots.getWebAppInitData(bot, chat, startParam.takeIf { it.isNotBlank() }))
        }
    }

    /**
     * Presses a `CALLBACK` button of a bot's inline keyboard (`MSG_SEND_CALLBACK` 118, see
     * [com.max.core.api.BotsApi.pressButton]). The answer's text or address is empty when the bot
     * answers with a message instead (it then arrives as a push).
     */
    fun pressButton(
        chatId: String,
        messageId: String,
        callbackId: String,
        payload: String,
        onResult: (IosButtonAnswer?, String?, String?) -> Unit,
    ) {
        perform(onResult, { null }) { c ->
            val answer = c.api.bots.pressButton(parseId(chatId), parseId(messageId), callbackId, payload.takeIf { it.isNotEmpty() })
            IosButtonAnswer(answer.text.orEmpty(), answer.url.orEmpty())
        }
    }

    /**
     * The mini app to reopen after an external step returned to [url] (`externalCallback=1`):
     * `EXTERNAL_CALLBACK` 105, then 160 with the bot and start parameter it named.
     */
    fun miniAppCallback(url: String, onResult: (IosMiniApp?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val next = c.api.bots.externalCallback(url)
            miniAppSnapshot(next.botId, c.api.bots.getWebAppInitData(next.botId, startParam = next.startParam))
        }
    }

    /** The folders (`FOLDERS_GET` 272), in the server's order. */
    fun loadFolders(onResult: (List<IosFolder>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c -> folderSnapshots(c.loadFolders()) }
    }

    /** The folders now (when known) and after every change: own requests and `NOTIF_FOLDERS` pushes. */
    fun watchFolders(onEach: (List<IosFolder>) -> Unit): IosWatch = watch { c ->
        c.watchFolders { folders -> if (folders != null) guarded { onEach(folderSnapshots(folders)) } }
    }

    /** Creates a folder with [chatIds] and [filters] (codes as text, e.g. `"4"` for dialogs). */
    fun createFolder(title: String, chatIds: List<String>, filters: List<String>, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.createFolder(title.trim(), chatIds.map(::parseId), parseFilters(filters)) }
    }

    /** Renames [folderId]; its chats, filters and pinned chats stay. */
    fun renameFolder(folderId: String, title: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.editFolder(folderId, title = title.trim()) }
    }

    /** Sets the chats of [folderId]; its title, filters and pinned chats stay. */
    fun setFolderChats(folderId: String, chatIds: List<String>, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.editFolder(folderId, chatIds = chatIds.map(::parseId)) }
    }

    /** Deletes [folderId] (`FOLDERS_DELETE` 276). */
    fun deleteFolder(folderId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.deleteFolders(listOf(folderId)) }
    }

    /** Sets the folder order (`FOLDERS_REORDER` 275), the whole list of ids. */
    fun reorderFolders(order: List<String>, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.reorderFolders(order) }
    }

    private fun updateSettings(onResult: (IosAccountSettings?, String?, String?) -> Unit, values: () -> Map<String, Any?>) {
        perform(onResult, { null }) { c -> settingsSnapshot(c.updateUserSettings(values())) }
    }

    /** A dead [IosWatch] (no callbacks) when the client cannot be created. */
    fun watchState(onEach: (String) -> Unit): IosWatch = watch { c -> c.watchState { guarded { onEach(phaseOf(it)) } } }

    /**
     * Pushes in arrival order, each after the core applied it ([MaxClient.appliedEvents]), plus
     * `presence` and `draft` events for changes of the core's presence and drafts (pushes,
     * `LOGIN`, [loadPresence], members, the presence TTL, sends; see [IosEvent]). A message from a
     * sender the store does not know yet waits up to [SENDER_WAIT_MS] for `CONTACT_INFO`, so the
     * event carries the sender name and avatar.
     */
    fun watchEvents(onEach: (IosEvent) -> Unit): IosWatch = watch { c ->
        val pushes = c.appliedEvents
            .map { event ->
                val sender = when (event) {
                    is MaxEvent.NewMessage -> event.message.sender
                    is MaxEvent.MessageEdited -> event.message.sender
                    else -> null
                }
                if (sender != null) withTimeoutOrNull(SENDER_WAIT_MS) { resolveUsers(c, listOf(sender)) }
                IosEventSource.Push(event)
            }
        merge(pushes, configChanges(c.accountConfig), storeChanges(c.store.state))
            .watch(scope) { source ->
                guarded {
                    val events = when (source) {
                        is IosEventSource.Push -> flatten(source.event, c.store.state.value)
                        is IosEventSource.Config -> source.events
                        is IosEventSource.Store -> source.events
                    }
                    events.forEach(onEach)
                }
            }
    }

    /**
     * Direct address of a video (`VIDEO_PLAY` 83, the best MP4 quality) or a file
     * (`FILE_DOWNLOAD` 88) of a message. [kind] is `video` or `file`; [attachmentId] is the
     * `videoId` / `fileId` of the attachment. An external video without an MP4 address fails
     * with `NOT_FOUND`.
     */
    fun mediaLink(chatId: String, messageId: String, kind: String, attachmentId: String, onResult: (String?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val chat = parseId(chatId)
            val message = parseId(messageId)
            val id = parseId(attachmentId)
            when (kind) {
                "video" -> c.media.getVideoLink(chat, message, id).url
                    ?: throw IllegalStateException("video link not found")
                "file" -> c.media.getFileLink(chat, message, id).url
                else -> throw IllegalArgumentException("unknown media kind \"$kind\"")
            }
        }
    }

    /**
     * Uploads [items] in order and sends them as one message with [caption] (empty for none);
     * a non-empty [replyTo] makes it a reply ([MaxClient.sendMedia]). [onProgress] gets the
     * bytes of the whole batch, at most once per percent, on a background thread. The returned
     * [IosTask] cancels the upload: [onResult] then reports `CANCELLED` and nothing is sent.
     * Each item's file must stay readable until [onResult].
     */
    fun sendMedia(
        chatId: String,
        items: List<IosOutgoingMedia>,
        caption: String,
        replyTo: String,
        onProgress: (IosUploadProgress) -> Unit,
        onResult: (IosMessage?, String?, String?) -> Unit,
    ): IosTask = IosTask(
        perform(onResult, { null }) { c ->
            val chat = parseId(chatId)
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            val media = items.map { OutgoingMedia(it.path, mediaKind(it.kind), it.fileName.takeIf { name -> name.isNotBlank() }) }
            var lastPercent = -1L
            val progress = UploadProgress { sent, total ->
                val percent = if (total > 0) sent * 100 / total else 0
                if (percent > lastPercent || sent >= total) {
                    lastPercent = percent
                    guarded { onProgress(IosUploadProgress(sent, total)) }
                }
            }
            val message = c.sendMedia(chat, media, caption, reply, progress)
            messageSnapshot(message, chatId, c.store.state.value)
        },
    )

    /**
     * Records sent as a voice message: [path] is an Ogg/Opus file (the only format the CDN
     * finishes, as Komet found), [durationMs] its length, [waveHex] the level bars as hex bytes
     * (`0..255` each, empty for silence; PyMax sends 80 zero bytes). Voice slot → upload →
     * `MSG_SEND` with `_type AUDIO`; a non-empty [replyTo] makes it a reply. Cancel with the task.
     */
    fun sendVoice(
        chatId: String,
        path: String,
        durationMs: Long,
        waveHex: String,
        replyTo: String,
        onProgress: (IosUploadProgress) -> Unit,
        onResult: (IosMessage?, String?, String?) -> Unit,
    ): IosTask = IosTask(
        perform(onResult, { null }) { c ->
            val chat = parseId(chatId)
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            val source = fileUploadSource(path)
            val uploaded = try {
                c.media.uploadVoice(source, fileNameOf(path, "voice.ogg"), durationMs, percentProgress(onProgress))
            } finally {
                runCatching { source.close() }
            }
            val wave = hexBytes(waveHex)
            val voice = if (wave.isEmpty()) uploaded else uploaded.copy(wave = wave)
            messageSnapshot(c.sendAttachments(chat, listOf(voice), null, reply), chatId, c.store.state.value)
        },
    )

    /**
     * A round video message: [path] is a square MP4 (H.264/AAC), [durationMs] its length.
     * Video-note slot (`type 1`) → upload → `MSG_SEND` with `_type VIDEO, videoType 1`.
     */
    fun sendVideoNote(
        chatId: String,
        path: String,
        durationMs: Long,
        replyTo: String,
        onProgress: (IosUploadProgress) -> Unit,
        onResult: (IosMessage?, String?, String?) -> Unit,
    ): IosTask = IosTask(
        perform(onResult, { null }) { c ->
            val chat = parseId(chatId)
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            val source = fileUploadSource(path)
            val note = try {
                c.media.uploadVideoNote(source, fileNameOf(path, "note.mp4"), durationMs.takeIf { it > 0 }, percentProgress(onProgress))
            } finally {
                runCatching { source.close() }
            }
            messageSnapshot(c.sendAttachments(chat, listOf(note), null, reply), chatId, c.store.state.value)
        },
    )

    /** Sends the card of MAX user [contactId] ([MaxClient.sendContact]). */
    fun sendContact(chatId: String, contactId: String, replyTo: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val reply = replyTo.takeIf { it.isNotBlank() }?.let(::parseId)
            messageSnapshot(c.sendContact(parseId(chatId), parseId(contactId), reply), chatId, c.store.state.value)
        }
    }

    /** Disconnects and releases the client. It cannot be used afterwards; [onDone] is always called once. */
    fun close(onDone: () -> Unit) {
        clientLock.locked {
            watches.forEach { it.cancel() }
            watches.clear()
        }
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                clientLock.locked { created }?.close()
            } catch (t: Throwable) {
                // closing is best effort; the scope is cancelled below either way
            } finally {
                guarded(onDone)
                scope.cancel()
            }
        }
    }

    /** `FOLDERS_GET` into the store. A failure keeps the pins from the `LOGIN` config. */
    private suspend fun syncFolders(c: MaxClient) {
        try {
            c.loadFolders()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            IosDiagnostics.reportFailure(classifyKind(t), t)
        }
    }

    /** `CHAT_INFO` for [id]; `null` when the server does not know the chat (a new dialog). */
    private suspend fun fetchChatOrNull(c: MaxClient, id: Long): Chat? = try {
        c.api.chats.getChat(id)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (t: Throwable) {
        null
    }

    private suspend fun userProfile(c: MaxClient, chatId: String, peer: Long): IosProfile {
        val fresh = c.loadUsers(listOf(peer)).firstOrNull { it.id == peer }
        val user = fresh ?: c.store.state.value.users[peer] ?: throw IllegalStateException("user $peer not found")
        val isBot = "BOT" in user.options
        val bot = if (!isBot) null else try {
            c.api.bots.getBotInfo(peer)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            null // the card still shows the contact without the command list
        }
        val card = bot?.contact ?: user
        val seen = c.presenceOf(peer)
        return IosProfile(
            kind = if (isBot) "bot" else "user",
            chatId = chatId,
            peerId = peer.toString(),
            title = nameOf(user, c.store.state.value).orEmpty(),
            avatarUrl = user.baseUrl.orEmpty(),
            description = card.description?.trim().orEmpty(),
            link = card.link.orEmpty(),
            phone = user.phone?.takeIf { it > 0 }?.toString().orEmpty(),
            lastSeenMs = Presences.seenMs(seen?.seen),
            online = seen?.status == PresenceStatus.ONLINE,
            official = "OFFICIAL" in user.options,
            commands = bot?.commands.orEmpty().map { IosBotCommand(it.name, it.description.orEmpty()) },
            hasWebApp = isBot && hasWebApp(user.options + card.options),
        ).apply { this.presence = PresenceStatus.of(seen) }
    }

    /** Loads profiles of [ids] missing from the store. Best effort: names are cosmetic here. */
    private suspend fun resolveUsers(c: MaxClient, ids: List<Long>) {
        val known = c.store.state.value.users
        val missing = ids.filter { it != 0L && it !in known }.distinct()
        if (missing.isEmpty()) return
        try {
            missing.chunked(USERS_PAGE).forEach { c.loadUsers(it) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // the list still loads; such rows fall back to a generic title in the app
        }
    }

    private fun loggedInStep(c: MaxClient): IosAuthStep =
        IosAuthStep("loggedIn", "", "", "", c.userId.value?.toString().orEmpty())

    private fun watch(start: (MaxClient) -> Watcher): IosWatch {
        val watcher = try {
            start(client())
        } catch (t: Throwable) {
            return IosWatch(null)
        }
        clientLock.locked { watches += watcher }
        return IosWatch(watcher)
    }

    /**
     * Runs [body] and calls [onResult] exactly once: `(value, null, null)` on success,
     * `(fallback, kind, errorKey)` on any failure. ATOMIC start: the body runs (and reports
     * `CANCELLED`) even when the scope is already cancelled by [close].
     */
    private fun <T> perform(
        onResult: (T, String?, String?) -> Unit,
        fallback: () -> T,
        waitsForSession: Boolean = true,
        body: suspend (MaxClient) -> T,
    ): Job =
        scope.launch(start = CoroutineStart.ATOMIC) {
            val outcome = try {
                val c = client()
                if (waitsForSession) awaitSession(c)
                Result.success(body(c))
            } catch (t: Throwable) {
                Result.failure(t)
            }
            outcome.fold(
                onSuccess = { guarded { onResult(it, null, null) } },
                onFailure = { t ->
                    val (kind, key) = classify(t)
                    if (kind != "CANCELLED") IosDiagnostics.reportFailure(kind, t)
                    guarded { onResult(fallback(), kind, key) }
                },
            )
        }

    private fun runUnit(onResult: (String?, String?) -> Unit, waitsForSession: Boolean = true, body: suspend (MaxClient) -> Unit) {
        perform<Unit>({ _, kind, key -> onResult(kind, key) }, { }, waitsForSession) { body(it) }
    }

    /**
     * A call made while an authorized session reconnects (the app woke up, the network changed)
     * waits for it instead of failing at once with "not connected", or with `proto.state` when it
     * slipped onto the new socket before `LOGIN`. The app asks for the call log, stories and read
     * marks right when it comes to the foreground, which is exactly when iOS has dropped the
     * socket. Bounded by [sessionWaitMs]: then the call runs and fails as before.
     */
    private suspend fun awaitSession(c: MaxClient) {
        fun reconnecting(state: ClientState): Boolean =
            (state is ClientState.Reconnecting || state == ClientState.Connecting) &&
                (c.userId.value != null || c.hasStoredToken)
        if (!reconnecting(c.state.value)) return
        withTimeoutOrNull(sessionWaitMs) { c.state.first { !reconnecting(it) } }
    }
}

/** How long a call waits for a reconnecting session before it runs anyway. */
private const val SESSION_WAIT_MS = 15_000L

/** How long a push from an unknown sender waits for the sender's profile. */
private const val SENDER_WAIT_MS = 3_000L

/** Ids per `CONTACT_INFO` request when resolving names. */
private const val USERS_PAGE = 100

/** Message ids per `MSG_GET_REACTIONS` request. */
private const val REACTIONS_PAGE = 100

/** Black list page size and page cap of [MaxIosClient.loadBlockedUsers]. */
private const val BLOCKED_PAGE_SIZE = 100
private const val BLOCKED_PAGES = 20

/** Scope of a [MaxIosClient]: exceptions that escape anyway are dropped instead of aborting the app. */
private fun newScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

private inline fun <T> NSLock.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

private inline fun <T> attempt(fallback: T, block: () -> T): T = try {
    block()
} catch (t: Throwable) {
    IosDiagnostics.reportFailure(classifyKind(t), t)
    fallback
}

private fun classifyKind(t: Throwable): String = try {
    classify(t).first
} catch (e: Throwable) {
    "UNKNOWN"
}

/** Runs a Swift callback; whatever it throws stays here. */
private inline fun guarded(block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        // a callback must not break the core coroutine that calls it
    }
}

private fun animojiElements(text: String, animoji: List<IosAnimojiMark>): List<Map<String, Any?>> =
    animoji.mapNotNull { mark ->
        val id = mark.animojiId.toLongOrNull() ?: return@mapNotNull null
        if (mark.from < 0 || mark.length <= 0 || mark.from + mark.length > text.length) return@mapNotNull null
        linkedMapOf<String, Any?>(
            "type" to "ANIMOJI", "from" to mark.from, "length" to mark.length, "entityId" to id,
            "attributes" to linkedMapOf("animojiLottieUrl" to mark.lottieUrl),
        )
    }

private fun mentionElements(text: String, mentions: List<IosMentionMark>): List<Map<String, Any?>> =
    mentions.mapNotNull { mark ->
        val id = mark.userId.toLongOrNull() ?: return@mapNotNull null
        if (mark.from < 0 || mark.length <= 0 || mark.from + mark.length > text.length) return@mapNotNull null
        linkedMapOf("type" to "USER_MENTION", "from" to mark.from, "length" to mark.length, "entityId" to id)
    }

/** [marks] from Swift as core elements; marks that cannot be sent are dropped (see [MaxIosClient.sendFormattedText]). */
internal fun textElementsOf(marks: List<IosTextMark>): List<TextElement> = marks.mapNotNull { m ->
    when (val type = m.type.trim().uppercase()) {
        "" -> null
        TextElementType.LINK -> m.url.trim().takeIf { it.isNotEmpty() }?.let { TextElement.link(m.from, m.length, it) }
        TextElementType.USER_MENTION -> m.entityId.toLongOrNull()?.let { TextElement.mention(m.from, m.length, it) }
        TextElementType.ANIMOJI -> {
            val id = m.entityId.toLongOrNull()
            if (id == null || m.lottieUrl.isBlank()) null else TextElement.animoji(m.from, m.length, id, m.lottieUrl)
        }
        else -> TextElement(type, m.from, m.length)
    }
}

/** Formatting of [message] for Swift. */
internal fun textMarksOf(message: MaxMessage): List<IosTextMark> = message.textElements.map { e ->
    IosTextMark(e.type, e.from, e.length, e.url.orEmpty(), e.entityId?.toString().orEmpty(), e.animojiLottieUrl.orEmpty())
}

/** Address-book entries from Swift; entries without a phone or a first name are skipped. */
internal fun phoneContactsOf(entries: List<IosPhoneContact>): List<PhoneContact> = entries.mapNotNull { e ->
    val phone = e.phone.trim()
    val first = e.firstName.trim()
    if (phone.isEmpty() || first.isEmpty()) null else PhoneContact(phone, first, e.lastName.trim().takeIf { it.isNotEmpty() })
}

/** The name the app shows for [user] ([ContactNames.resolve] with the address book of [state]). */
internal fun nameOf(user: MaxUser, state: MaxState): String? =
    ContactNames.resolve(user, state.addressBookName(user.id), state.preferAddressBookNames)

/** [nameOf] or "Участник" ([ContactNames.label]). */
internal fun labelOf(user: MaxUser?, state: MaxState): String =
    ContactNames.label(user, user?.id?.let(state::addressBookName), state.preferAddressBookNames)

internal fun draftSnapshot(draft: MaxDraft): IosDraft = IosDraft(
    chatId = draft.chatId.toString(),
    text = draft.text,
    elementsJson = TextElementsJson.write(draft.elements),
    replyTo = draft.replyTo?.toString().orEmpty(),
    updateTime = draft.updateTime,
)


internal fun groupMemberSnapshot(
    entry: ChatMemberEntry,
    state: MaxState,
    nowMs: Long = nowMs(),
    ttlMs: Long = MaxState.DEFAULT_PRESENCE_TTL_MS,
): IosGroupMember? {
    val id = entry.userId ?: entry.user?.id ?: return null
    val user = state.users[id] ?: entry.user
    val seen = state.presenceAt(id, nowMs, ttlMs) ?: entry.member.presenceInfo
    return IosGroupMember(
        id = id.toString(),
        name = ContactNames.label(user, state.addressBookName(id), state.preferAddressBookNames),
        avatarUrl = user?.baseUrl.orEmpty(),
        role = entry.role.name.lowercase(),
        alias = entry.admin?.alias.orEmpty(),
        permissions = entry.admin?.permissions ?: -1,
        lastSeenMs = Presences.seenMs(seen?.seen),
        online = seen?.status == PresenceStatus.ONLINE,
    ).apply { this.presence = PresenceStatus.of(seen) }
}

private fun foundFromSearch(fallbackChatId: Long, result: Any?): List<IosFoundMessage> {
    val list = result as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val body = map["message"] as? Map<*, *> ?: map
        val id = longOf(body["id"]) ?: return@mapNotNull null
        val chatId = longOf(map["chatId"])?.takeIf { it != 0L } ?: longOf(body["chatId"])?.takeIf { it != 0L } ?: fallbackChatId
        IosFoundMessage(
            chatId.toString(),
            id.toString(),
            (body["text"] as? String)?.trim().orEmpty(),
            longOf(body["time"]) ?: 0L,
            longOf(body["sender"])?.toString().orEmpty(),
        )
    }
}

private fun longOf(value: Any?): Long? = when (value) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

/** Decimal id from Swift; a malformed one becomes an [IllegalArgumentException] (error kind `UNKNOWN`). */
private fun parseId(value: String): Long =
    value.toLongOrNull() ?: throw IllegalArgumentException("not a numeric id: \"$value\"")

/** Upload progress for Swift, at most once per percent. */
private fun percentProgress(onProgress: (IosUploadProgress) -> Unit): UploadProgress {
    var lastPercent = -1L
    return UploadProgress { sent, total ->
        val percent = if (total > 0) sent * 100 / total else 0
        if (percent > lastPercent || sent >= total) {
            lastPercent = percent
            guarded { onProgress(IosUploadProgress(sent, total)) }
        }
    }
}

/** [StoryOwner.Type] of a Swift type code; unknown codes are a person. */
private fun storyOwnerType(code: Int): StoryOwner.Type =
    StoryOwner.Type.entries.firstOrNull { it.code == code } ?: StoryOwner.Type.USER

/** A ring for Swift; a person owner's name and avatar come from [state] (empty when unknown). */
private fun storyPreviewSnapshot(preview: StoryPreview, state: MaxState): IosStoryPreview {
    val id = preview.owner.ownerId
    val user = if (preview.owner.type == StoryOwner.Type.USER) state.users[id] else null
    return IosStoryPreview(
        ownerId = id.toString(),
        ownerType = preview.owner.type.code,
        name = user?.let { nameOf(it, state) } ?: state.chats[id]?.title.orEmpty(),
        avatarUrl = user?.baseUrl.orEmpty(),
        updateTimeMs = preview.updateTime,
        totalCount = preview.totalCount,
        readCount = preview.readCount,
        expiresAtMs = preview.lastStoryExpirationTime,
    )
}

private fun storySnapshot(story: Story): IosStory {
    val media = story.media
    return IosStory(
        id = story.id.toString(),
        ownerId = story.owner.ownerId.toString(),
        ownerType = story.owner.type.code,
        audience = story.settings,
        timeMs = story.time,
        expiresAtMs = story.expiration,
        mediaKind = when {
            media == null || media.url == null -> ""
            media.isVideo -> "video"
            else -> "photo"
        },
        url = media?.url.orEmpty(),
        thumbnailUrl = media?.thumbnailUrl.orEmpty(),
        width = media?.width ?: 0,
        height = media?.height ?: 0,
        durationMs = media?.durationMs ?: 0,
    )
}

/** The last path component of [path], or [fallback] when it has none. */
private fun fileNameOf(path: String, fallback: String): String =
    path.substringAfterLast('/').ifEmpty { fallback }

/** Bytes of a hex string (`"00ff7a"`); malformed pairs are skipped. */
internal fun hexBytes(hex: String): ByteArray {
    val clean = hex.trim()
    val out = ArrayList<Byte>(clean.length / 2)
    var i = 0
    while (i + 1 < clean.length) {
        clean.substring(i, i + 2).toIntOrNull(16)?.let { out += it.toByte() }
        i += 2
    }
    return out.toByteArray()
}

/** `photo`, `video` or `file` of [IosOutgoingMedia.kind]. */
private fun mediaKind(kind: String): OutgoingMedia.Kind = when (kind) {
    "photo" -> OutgoingMedia.Kind.PHOTO
    "video" -> OutgoingMedia.Kind.VIDEO
    "file" -> OutgoingMedia.Kind.FILE
    else -> throw IllegalArgumentException("unknown media kind \"$kind\"")
}

/**
 * A local file for [MaxIosClient.sendMedia]. [kind] is `photo` (recompressed by the server),
 * `video` or `file` (sent as is). [fileName] is what the recipient sees for a file; empty for
 * the last path component. A photo's name should be ASCII (`image.jpg`): it goes into the
 * multipart header unencoded.
 */
class IosOutgoingMedia(val path: String, val kind: String, val fileName: String)

/** Upload progress of a [MaxIosClient.sendMedia] batch in bytes. */
class IosUploadProgress(val sent: Long, val total: Long)

/** A running request that can be cancelled, such as [MaxIosClient.sendMedia]. */
class IosTask internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

/** Cancels one `watch…` subscription of [MaxIosClient]. */
class IosWatch internal constructor(private val watcher: Watcher?) {
    fun cancel() {
        watcher?.cancel()
    }
}

/** `AUTH_REQUEST` reply. [codeLength] is 0 when the server omitted it. */
class IosCodeRequest(val token: String, val codeLength: Int)

/** A public chat or channel from [IosBridge.searchPublic]; `subtitle` is `@link` or the last message. */
class IosSearchChat(
    val id: String,
    val type: String,
    val title: String,
    val subtitle: String,
    val avatarUrl: String,
    val participantsCount: Int,
)

/** A message from [IosBridge.searchMessages]; `timeMs` is 0 when the server did not send it. */
class IosFoundMessage(val chatId: String, val messageId: String, val text: String, val timeMs: Long, val senderId: String)

/**
 * Next auth step. [kind] is `loggedIn`, `password` or `register`.
 * Unused strings are empty.
 */
class IosAuthStep(
    val kind: String,
    val trackId: String,
    val hint: String,
    val registerToken: String,
    val userId: String,
)

/**
 * One chat, ids as decimal strings. [updatedAtMs] and message times are Unix milliseconds.
 * A dialog has no server title: [title] and [avatarUrl] are the other participant's when known.
 * [avatarUrl] is empty without a picture.
 */
class IosChat(
    val id: String,
    val title: String,
    val type: String,
    val lastMessageId: String,
    val lastText: String,
    val updatedAtMs: Long,
    val unread: Int,
    val avatarUrl: String,
    val lastAuthorId: String,
    /** Kind of the first attachment of the last message ([attachmentKind]); empty for text only. */
    val lastMedia: String = "",
    /** Photo address or video cover of that attachment, empty when there is none. */
    val lastThumbUrl: String = "",
    /** Channel option `COMMENTS`: `1` on, `0` off, `-1` when the chat card does not say. */
    val comments: Int = -1,
    /** The account may post here: `1` yes, `0` no ([canWrite]). */
    val canWrite: Int = 1,
    /**
     * Notifications off (`config.chats[id].dontDisturbUntil`, read at the time of the snapshot):
     * `1` muted, `0` on, `-1` unknown (no config yet, or a config that does not know this chat;
     * never "muted for good"). Keep the shown value on `-1`; `chatMute` events of [MaxIosClient.watchEvents]
     * bring later changes. See `muteCode`.
     */
    val muted: Int = -1,
    /** Display name of the last message's author, empty when unknown. */
    val lastAuthorName: String = "",
    /** The last message is the account's own: `1` yes, `0` no, `-1` unknown. */
    val lastFromMe: Int = -1,
    /** The last message is a forward (`link.type` FORWARD): `1` yes, `0` no. Its text and
     *  attachment are then the forwarded message's. */
    val lastForwarded: Int = 0,
    /** Latest read mark of the other participants (`participants`: user id → read time, ms):
     *  own messages up to it are read. `0` for channels or when the card does not say. */
    val peerReadMs: Long = 0,
    /**
     * `1` for a live chat, `0` for one the account left or that was closed (`status` other than
     * `ACTIVE`). Komet keeps those out of the chat list: the server refuses to leave or delete
     * them and answers their history with a refusal.
     */
    val active: Int = 1,
    /** Server time of the last message (ms), `0` without one. Unlike [updatedAtMs] (the chat's
     *  last event, moved by edits and reactions too), it is what read marks compare with. */
    val lastTimeMs: Long = 0,
)

/**
 * A chat card for the profile screen. [kind] is `user`, `bot`, `group`, `channel` or `saved`.
 * Unknown strings are empty and unknown numbers 0. [phone] is digits without `+`; [link] is what
 * the server sent (a short name or a full URL); [participants] counts members or subscribers.
 */
class IosProfile(
    val kind: String,
    val chatId: String,
    val peerId: String = "",
    val title: String = "",
    val avatarUrl: String = "",
    val description: String = "",
    val link: String = "",
    val phone: String = "",
    val participants: Int = 0,
    val lastSeenMs: Long = 0,
    val online: Boolean = false,
    val official: Boolean = false,
    val isPublic: Boolean = false,
    val commands: List<IosBotCommand> = emptyList(),
    /** A bot with a mini app: the chat shows "Open app" ([com.max.core.api.WEB_APP_OPTIONS]). */
    val hasWebApp: Boolean = false,
    /** Channel option `COMMENTS`: `1` on, `0` off, `-1` when the card does not say. */
    val comments: Int = -1,
) {
    /** A user's presence code ([IosPresence.status]); `-1` unknown or not a user. */
    var presence: Int = -1
        internal set
}

/** [MaxIosClient.loadChatList]: the chats and whether they are the account's whole list. */
class IosChatList(val chats: List<IosChat>, val complete: Boolean)

/** Answer to a pressed inline button: a short [text] notice and/or a [url] to open; empty when absent. */
class IosButtonAnswer(val text: String, val url: String)

/** One bot menu command, [name] without the slash. */
class IosBotCommand(val name: String, val description: String)

/**
 * One contact of the account. [phone] is digits without `+`, empty when hidden.
 * [lastSeenMs] is the last presence time (0 when unknown); [online] is the current presence.
 */
class IosContact(
    val id: String,
    val firstName: String,
    val lastName: String,
    val phone: String,
    val avatarUrl: String,
    val lastSeenMs: Long,
    val online: Boolean,
    /** `accountStatus`. Missing or 0 means the account is alive. */
    val accountStatus: Int = 0,
    val isBot: Boolean = false,
    val isOfficial: Boolean = false,
    val isServiceAccount: Boolean = false,
) {
    /**
     * The name to show ([MaxIosClient.displayName]): the contact name, else the address-book
     * name, else the profile name. Read-only outside the initializer, so the Swift initializer
     * stays the same.
     */
    var displayName: String = ""
        internal set

    /**
     * The full presence code ([IosPresence.status]: `-1` unknown, `0` offline, `1` online, `2`
     * recently, `3` long ago); [online] is `presence == 1`, the TTL applied.
     */
    var presence: Int = -1
        internal set
}

/**
 * One call of the call log. [peerId] is empty for a group call ([isGroup]). [hangupType] is the
 * server value (`HUNGUP`, `CANCELED`, `REJECTED`, `MISSED`, ...), [duration] its raw length
 * (0 when nobody answered). [chatId] is empty when the server did not send it.
 */
class IosCall(
    val id: String,
    val chatId: String,
    val peerId: String,
    val title: String,
    val avatarUrl: String,
    val isGroup: Boolean,
    val outgoing: Boolean,
    val missed: Boolean,
    val video: Boolean,
    val hangupType: String,
    val duration: Long,
    val timeMs: Long,
)

/** Number of comments under channel post [postId]. */
class IosCommentCount(
    val postId: String,
    val count: Int,
)

/**
 * One message. [authorId] is empty when the server omitted the sender.
 * [contentJson] is empty when there are no attachments, reply, reactions or comments.
 * [reactionsJson] is the message's reactions, `{"counters":[{"reaction","count"}],"totalCount",
 * "yourReaction"}` ([reactionsJson]); `{"counters":[]…}` means none. It is empty when the
 * source may leave reactions out (an edit reply), so the app keeps what it has.
 * [authorName] and [authorAvatarUrl] stay empty when that user is not in the store.
 * [updateTime] is the time of the last edit in ms (`updateTime`), `0` for a message that was
 * never edited. It is a read-only property outside the initializer, so the Swift initializer
 * stays the same.
 */
class IosMessage(
    val id: String,
    val chatId: String,
    val authorId: String,
    val text: String,
    val timeMs: Long,
    val contentJson: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String = "",
    val reactionsJson: String = "",
) {
    var updateTime: Long = 0L
        internal set

    /** Text formatting ([IosTextMark]), in the order the server sent it; empty for plain text. */
    var marks: List<IosTextMark> = emptyList()
        internal set

    /** The same formatting as a JSON array of wire elements (`[]` for plain text). */
    var elementsJson: String = "[]"
        internal set
}

/** Reactions of one message ([MaxIosClient.loadReactions]); [json] as [IosMessage.reactionsJson]. */
class IosReactions(val messageId: String, val json: String)

/** An animated emoji in sent text ([MaxIosClient.sendText]): UTF-16 [from]/[length] of the emoji. */
class IosAnimojiMark(val from: Int, val length: Int, val animojiId: String, val lottieUrl: String)

/** A `USER_MENTION` over `@name` in the outgoing text. [userId] is decimal. */
class IosMentionMark(val from: Int, val length: Int, val userId: String)

class IosChatMember(val id: String, val name: String, val avatarUrl: String = "")

/**
 * A formatting mark of a message text: [type] is an [IosTextMarkType] value (an unknown server
 * type is passed as is), [from] / [length] are UTF-16 offsets. [url] is the target of a `LINK`,
 * [entityId] the user of a `USER_MENTION` or the animoji of an `ANIMOJI`, [lottieUrl] the
 * animation of an `ANIMOJI`; empty when not used.
 */
class IosTextMark(
    val type: String,
    val from: Int,
    val length: Int,
    val url: String = "",
    val entityId: String = "",
    val lottieUrl: String = "",
)

/** [IosTextMark.type] values: the server's element types. */
object IosTextMarkType {
    /** Bold. */
    const val STRONG: String = TextElementType.STRONG

    /** Italic. */
    const val EMPHASIZED: String = TextElementType.EMPHASIZED
    const val UNDERLINE: String = TextElementType.UNDERLINE
    const val STRIKETHROUGH: String = TextElementType.STRIKETHROUGH

    /** Inline monospace. */
    const val MONOSPACED: String = TextElementType.MONOSPACED

    /** A code block; received from some clients, the official ones send [MONOSPACED]. */
    const val CODE: String = TextElementType.CODE
    const val HEADING: String = TextElementType.HEADING
    const val QUOTE: String = TextElementType.QUOTE
    const val LINK: String = TextElementType.LINK
    const val USER_MENTION: String = TextElementType.USER_MENTION
    const val ANIMOJI: String = TextElementType.ANIMOJI
}

/**
 * Result of [MaxIosClient.forwardMessages]: the new messages in the target chat, in order, and
 * [failedAt], the index of the first message that was not sent (`-1` when all were).
 */
class IosForwardResult(val messages: List<IosMessage>, val failedAt: Int)

/**
 * A member of a group or channel ([MaxIosClient.loadChatMembers]). [role] is `owner`, `admin` or
 * `member`; [alias] is an admin's title (empty when none) and [permissions] an admin's permission
 * bits as the server sent them (`-1` when unknown or not an admin). [lastSeenMs] is 0 when
 * unknown; [online] is the presence the server reported.
 */
class IosGroupMember(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val role: String,
    val alias: String,
    val permissions: Int,
    val lastSeenMs: Long,
    val online: Boolean,
) {
    /**
     * The full presence code ([IosPresence.status]: `-1` unknown, `0` offline, `1` online, `2`
     * recently, `3` long ago); [online] is `presence == 1`. Read-only outside the initializer.
     */
    var presence: Int = -1
        internal set
}

/** One page of members; [nextMarker] is empty after the last page. */
class IosChatMembersPage(val members: List<IosGroupMember>, val nextMarker: String)

/** An address-book entry for [MaxIosClient.setAddressBook] (kept on the device). */
class IosPhoneContact(val phone: String, val firstName: String, val lastName: String = "")

/**
 * Result of the selection delete ([MaxIosClient.deleteMessages] with `postId`): the ids the
 * server deleted and the ones it refused (`failedMessageIds`).
 */
class IosDeleteResult(val deleted: List<String>, val failed: List<String>)

/**
 * A server draft. [elementsJson] is its formatting as a JSON array of wire elements (UTF-16
 * offsets), [replyTo] the answered message id (empty when none), [updateTime] the server time in
 * ms (pass it to [MaxIosClient.discardDraft]).
 */
class IosDraft(val chatId: String, val text: String, val elementsJson: String, val replyTo: String, val updateTime: Long)

/** A chat shared with a user ([MaxIosClient.commonChats]); [type] is `CHAT` or `CHANNEL`. */
class IosCommonChat(val id: String, val type: String, val title: String, val iconUrl: String, val participants: Int)

/** One complaint reason ([MaxIosClient.complaintReasons]). */
class IosComplaintReason(val id: Int, val title: String)

/**
 * Where to open a call the server accepted ([MaxIosClient.startCall], [MaxIosClient.joinCall]).
 * [ws2Url] is the signaling socket with every query param; [callsUserId] is the own id in the
 * call (participants are numbered by it); [joinLink] is the shareable link of a group call, empty
 * for a 1:1 call. ICE servers come later, in the ws2 `connection` notification.
 */
class IosCallStart(
    val conversationId: String,
    val ws2Url: String,
    val callsUserId: Long,
    val peerCallsUserId: Long,
    val joinLink: String,
    val isVideo: Boolean,
)

/** A new group call ([MaxIosClient.createCallLink]); [name] is empty when the server gave none. */
class IosCallLink(val conversationId: String, val url: String, val token: String, val name: String)

/** [MaxIosClient.callLinkInfo]: the call behind a link before joining. */
class IosCallLinkInfo(val url: String, val name: String, val participants: Int, val isVideo: Boolean)

/**
 * An incoming call ([MaxIosClient.watchIncomingCalls]). [ws2Url] answers or rejects it (Komet
 * `Ws2Config.fromVcp`); the ICE servers come from the push (`stne`, `trne`, `trnu`, `trnp`) and
 * may be replaced by the ws2 `connection` notification. [expiresAtMs] is 0 when unknown.
 * [callerName] and [callerAvatarUrl] are empty for a caller the store does not know.
 */
class IosIncomingCall(
    val conversationId: String,
    val callerId: String,
    val callerName: String,
    val callerAvatarUrl: String,
    val chatId: String,
    val isVideo: Boolean,
    val ws2Url: String,
    val callsUserId: Long,
    val stunUrls: List<String>,
    val turnUrls: List<String>,
    val turnUsername: String,
    val turnPassword: String,
    val expiresAtMs: Long,
)

/** [MaxIosClient.loadStickerCatalog]: sets in panel order and recent sticker ids. */
class IosStickerCatalog(val sets: List<IosStickerSet>, val recentStickerIds: List<String>)

/** A sticker set; [iconUrl] and [link] are empty when unknown. */
class IosStickerSet(
    val id: String,
    val name: String,
    val iconUrl: String,
    val stickerIds: List<String>,
    val link: String,
    val isFavorite: Boolean,
)

/** A sticker; [lottieUrl] empty for a still one, [setId] empty when unknown, sizes `0` when unknown. */
class IosSticker(
    val id: String,
    val url: String,
    val lottieUrl: String,
    val setId: String,
    val width: Int,
    val height: Int,
    val tags: List<String>,
)

/** An animated emoji of the catalog; [iconUrl] / [lottieUrl] empty when unknown. */
class IosAnimoji(val id: String, val emoji: String, val iconUrl: String, val lottieUrl: String)

/**
 * One owner's stories ring ([MaxIosClient.loadStoriesFeed]). [ownerType] `0` person, `1` group,
 * `2` channel; [name] and [avatarUrl] empty when unknown. Times are milliseconds. The ring is
 * unseen while [readCount] < [totalCount].
 */
class IosStoryPreview(
    val ownerId: String,
    val ownerType: Int,
    val name: String,
    val avatarUrl: String,
    val updateTimeMs: Long,
    val totalCount: Int,
    val readCount: Int,
    val expiresAtMs: Long,
)

/**
 * One story. [mediaKind] `photo`, `video` or empty (nothing the client can show); [url] the
 * photo or MP4 address, [thumbnailUrl] a video's cover; sizes and [durationMs] `0` when unknown.
 * [audience] `1` everyone, `2` contacts.
 */
class IosStory(
    val id: String,
    val ownerId: String,
    val ownerType: Int,
    val audience: Int,
    val timeMs: Long,
    val expiresAtMs: Long,
    val mediaKind: String,
    val url: String,
    val thumbnailUrl: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
)

/** [MaxIosClient.loadOwnerStories]: the owner's ring (`null` when none) and stories, oldest first. */
class IosOwnerStories(val preview: IosStoryPreview?, val stories: List<IosStory>)

/** [MaxIosClient.publishStory]: the account's new ring and the published stories. */
class IosPublishedStory(val preview: IosStoryPreview?, val stories: List<IosStory>)

/** [MaxIosClient.transcribeVoice]: [status] `1` ready, `0` in progress, `-1` failed. */
class IosTranscription(val status: Int, val text: String)

/** Reply to [MaxIosClient.markReadAt]: the server's unread count and the read mark it kept (ms). */
class IosReadMark(val unread: Int, val mark: Long)

/** One entry of [MaxIosClient.loadReactionUsers]; [name] and [avatarUrl] are empty for an unknown user. */
class IosReactionUser(val userId: String, val name: String, val avatarUrl: String, val reaction: String)

/**
 * One entry of [MaxIosClient.loadMessageReaders]. [name] is `null` while the user is unknown;
 * [reaction] is the user's emoji, `null` when the user did not react. [readMark] is the user's
 * read mark in ms: the time of the last message the user has read, not the moment of reading;
 * `0` for a user listed only for a reaction (the known mark is older than the message).
 */
class IosMessageReader(val userId: String, val name: String?, val reaction: String?, val readMark: Long)

/**
 * `type` strings of [MaxIosClient.sendTyping] and of `typing` [IosEvent]s
 * ([com.max.core.api.TypingType]). A `typing` event always carries one of these: a push without
 * a `type`, or with an unrecognised one, arrives as [TEXT].
 */
object IosTypingType {
    /** Typing text. */
    const val TEXT: String = TypingType.TEXT

    /** Recording a voice message. */
    const val AUDIO: String = TypingType.AUDIO

    /** Recording a video message (round video note). */
    const val VIDEO_MSG: String = TypingType.VIDEO_MSG

    /** Sending a photo. */
    const val PHOTO: String = TypingType.PHOTO

    /** Sending a video. */
    const val VIDEO: String = TypingType.VIDEO

    /** Sending a file. */
    const val FILE: String = TypingType.FILE

    /** Choosing a sticker. */
    const val STICKER: String = TypingType.STICKER
}

/**
 * A push the app stores or shows.
 *
 * [kind] is `message`, `edited`, `deleted`, `chat`, `typing` ([authorId] is typing; [text] is an
 * [IosTypingType] value, `TEXT` when the push had no or an unrecognised `type`), `read`, `reactions`,
 * `transcription` (the text of a voice message in [text], its status in [unread]), `stories`
 * (an owner's stories ring changed, see `storiesEvent`) or `contact` (`NOTIF_CONTACT` 131: a
 * contact was changed on another device; [authorId] is the user, [title] the name to show now).
 * [unread] is `-1` when this event does not change the unread counter.
 * [reactionsJson] as in [IosMessage]: set for `message` and `reactions`, empty for `edited` (an
 * edit keeps the reactions). For `reactions` it has no `yourReaction` key when the own reaction
 * is unknown (`NOTIF_MSG_REACTIONS_CHANGED` 155 carries only counters).
 * [updateTime] is the edit time (ms) of the message of a `message` or `edited` event, as in
 * [IosMessage.updateTime]; `0` when the message was never edited or the event carries none.
 * `chatMute`: the mute of [chatId] changed (on another device, `NOTIF_CONFIG` 134, after a
 * reconnect or by `setChatMuted`); [muted] is `1` / `0` / `-1` (unknown), [timeMs] the raw `dontDisturbUntil`
 * (`0` sound on, `-1` muted for good, else the end of the mute in ms, so a timed mute runs out
 * without another event). `config`: the account config became known or was dropped; reload the
 * mute states ([MaxIosClient.isChatMuted]). See `configEvents`.
 * `presence`: the presence of [authorId] changed (push 132, `LOGIN`, `loadPresence`, members, or
 * an "online" that ran out of the server's `presence-ttl`); [presence] is the status code
 * ([IosPresence.status]: `-1` unknown, `0` offline, `1` online, `2` recently, `3` long ago),
 * [timeMs] the last-seen time in ms (`0` unknown: never show "recently" for it).
 * `draft`: the server draft of [chatId] changed (saved or discarded on another device, pushes
 * 152 / 153, `LOGIN`, `saveDraft`, or cleared by a send); [draft] is the draft now, `null` when
 * it is gone; [text], [marks], [messageId] (the answered message, empty for none) and [timeMs]
 * (its update time, `0` when gone) repeat it.
 * Unknown pushes are not forwarded; incoming calls come from `watchIncomingCalls`.
 */
class IosEvent(
    val kind: String,
    val chatId: String,
    val messageId: String,
    val authorId: String,
    val text: String,
    val title: String,
    val chatType: String,
    val timeMs: Long,
    val unread: Int,
    val contentJson: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String = "",
    val reactionsJson: String = "",
) {
    var updateTime: Long = 0L
        internal set

    /** Text formatting of the message of a `message` or `edited` event; empty otherwise. */
    var marks: List<IosTextMark> = emptyList()
        internal set

    /**
     * For a `chatMute` event: `1` muted, `0` sound on, `-1` unknown (codes of [IosChat.muted]).
     * `-1` for every other kind.
     */
    var muted: Int = -1
        internal set

    /** For a `presence` event: the status code ([IosPresence.status]); `-1` for every other kind. */
    var presence: Int = -1
        internal set

    /** For a `draft` event: the draft now, `null` when it was discarded or sent; `null` for other kinds. */
    var draft: IosDraft? = null
        internal set
}

/**
 * Presence of a user. [status]: `-1` unknown (nothing known yet), `0` offline ([seenMs] is the
 * last time online), `1` online, `2` was online recently (time hidden), `3` long ago. [seenMs]
 * is `0` when unknown. An "online" older than the server's `presence-ttl` already reads as `0`.
 */
class IosPresence(val userId: String, val status: Int, val seenMs: Long)

private fun phaseOf(state: ClientState): String = when (state) {
    ClientState.Idle -> "idle"
    ClientState.Connecting -> "connecting"
    is ClientState.AwaitingAuth -> "awaitingAuth"
    is ClientState.Ready -> "ready"
    is ClientState.Reconnecting -> "reconnecting"
    is ClientState.TokenRejected -> "tokenRejected"
    is ClientState.Failed -> "failed"
}

private fun classify(t: Throwable): Pair<String, String?> {
    val error = t.toMaxError()
    val kind = if (error.kind == ErrorKind.UNKNOWN && error.message.contains("not found", ignoreCase = true)) {
        "NOT_FOUND"
    } else {
        error.kind.name
    }
    return kind to error.errorKey
}

private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * [IosChat.muted] / [MaxIosClient.isChatMuted] of [chatId] ([AccountConfig.chatMuteState]):
 * - `1` muted: `dontDisturbUntil` `-1` or an end time still ahead;
 * - `0` sound on: `0`, a timed mute that ran out, or no entry while the config holds the full
 *   `chats` section ([AccountConfig.chatsKnown]);
 * - `-1` unknown: no config, or a config without the full `chats` section and no entry for the chat.
 */
internal fun muteCode(config: AccountConfig?, chatId: Long, nowMs: Long): Int = muteCode(config?.chatMuteState(chatId, nowMs))

private fun muteCode(state: Boolean?): Int = when (state) {
    true -> 1
    false -> 0
    null -> -1
}

/** What [MaxIosClient.watchEvents] turns into [IosEvent]s: a push, or events of a config or store change. */
private sealed interface IosEventSource {
    class Push(val event: MaxEvent) : IosEventSource
    class Config(val events: List<IosEvent>) : IosEventSource
    class Store(val events: List<IosEvent>) : IosEventSource
}

/** [storeEvents] for every change of [state] after the value it has when collected. */
private fun storeChanges(state: StateFlow<MaxState>): Flow<IosEventSource> = flow {
    var prev: MaxState? = null
    state.collect { next ->
        val before = prev
        prev = next
        if (before != null) {
            val events = storeEvents(before, next)
            if (events.isNotEmpty()) emit(IosEventSource.Store(events))
        }
    }
}

/**
 * `presence` and `draft` events between two store snapshots: one per user whose stored presence
 * changed and one per chat whose draft changed (set, replaced or removed). Nothing on logout
 * ([MaxState.me] gone) or when another account replaced the snapshot; the first login of a
 * session reports its presence and drafts.
 */
internal fun storeEvents(prev: MaxState, next: MaxState): List<IosEvent> {
    if (next.me == null || (prev.me != null && prev.me != next.me)) return emptyList()
    val out = ArrayList<IosEvent>()
    if (prev.presence !== next.presence) {
        for ((id, p) in next.presence) {
            if (prev.presence[id] == p) continue
            out += presenceEvent(id, p)
        }
    }
    if (prev.drafts !== next.drafts) {
        for ((chatId, d) in next.drafts) {
            if (prev.drafts[chatId] == d) continue
            out += draftEvent(chatId, d)
        }
        for (chatId in prev.drafts.keys) if (chatId !in next.drafts) out += draftEvent(chatId, null)
    }
    return out
}

/** A `presence` event of [userId] ([IosEvent.presence], `seen` in [IosEvent.timeMs]). */
internal fun presenceEvent(userId: Long, info: PresenceInfo?): IosEvent =
    iosEvent(kind = "presence", authorId = userId.toString(), timeMs = Presences.seenMs(info?.seen)).apply {
        presence = PresenceStatus.of(info)
    }

/** A `draft` event of [chatId]: [IosEvent.draft] is the draft, `null` when it is gone. */
internal fun draftEvent(chatId: Long, draft: MaxDraft?): IosEvent =
    iosEvent(
        kind = "draft",
        chatId = chatId.toString(),
        messageId = draft?.replyTo?.toString().orEmpty(),
        text = draft?.text.orEmpty(),
        timeMs = draft?.updateTime ?: 0L,
    ).apply {
        this.draft = draft?.let(::draftSnapshot)
    }

/** [IosPresence] of [userId] from [info] (`null`: unknown). */
internal fun presenceSnapshot(userId: Long, info: PresenceInfo?): IosPresence =
    IosPresence(userId.toString(), PresenceStatus.of(info), Presences.seenMs(info?.seen))

/** [configEvents] for every change of [config] after the value it has when collected. */
private fun configChanges(config: StateFlow<AccountConfig?>): Flow<IosEventSource> = flow {
    var started = false
    var prev: AccountConfig? = null
    config.collect { next ->
        if (!started) {
            started = true
        } else {
            val events = configEvents(prev, next, nowMs())
            if (events.isNotEmpty()) emit(IosEventSource.Config(events))
        }
        prev = next
    }
}

/**
 * The [IosEvent]s of an account config change from [prev] to [next] (`MaxClient.accountConfig`:
 * `LOGIN`, the `NOTIF_CONFIG` 134 push, `setChatMuted`):
 * - `chatMute` for each chat whose mute changed: [IosEvent.chatId], [IosEvent.muted] `1` / `0` /
 *   `-1` (same codes as [IosChat.muted]), [IosEvent.timeMs] the raw `dontDisturbUntil` (`0` sound
 *   on, `-1` muted for good, else the end of the mute in ms; `0` when unknown);
 * - one `config` (no chat), after the `chatMute` ones, when the config became known or was
 *   dropped (login, logout) or [AccountConfig.chatsKnown] changed: the state of chats without an
 *   entry changed too, reload the mute states ([MaxIosClient.isChatMuted] or the chat list).
 */
internal fun configEvents(prev: AccountConfig?, next: AccountConfig?, nowMs: Long): List<IosEvent> {
    if (prev == next) return emptyList()
    val chats = AccountConfig.chatMuteChanges(prev ?: AccountConfig(), next ?: AccountConfig(), nowMs).map { change ->
        iosEvent(kind = "chatMute", chatId = change.chatId.toString(), timeMs = change.dontDisturbUntil ?: 0L).apply {
            muted = muteCode(change.muted)
        }
    }
    val bulk = prev == null || next == null || prev.chatsKnown != next.chatsKnown
    return if (bulk) chats + iosEvent(kind = "config") else chats
}

private fun chatSnapshot(chat: Chat, state: MaxState, config: AccountConfig? = null): IosChat {
    val last = chat.lastMessage
    val updated = when {
        chat.lastEventTime > 0 -> chat.lastEventTime
        last != null -> last.time
        else -> 0L
    }
    val peer = dialogPeer(chat, state.me)?.let { state.users[it] }
    val title = chat.title?.takeIf { it.isNotBlank() } ?: peer?.let { nameOf(it, state) }.orEmpty()
    val avatar = (chat.raw["baseIconUrl"] as? String)?.takeIf { it.isNotBlank() } ?: peer?.baseUrl.orEmpty()
    val forwarded = forwardedOf(last)
    val attaches = last?.attaches?.takeIf { it.isNotEmpty() } ?: forwarded?.get("attaches") as? List<*> ?: emptyList<Any?>()
    val me = state.me
    return IosChat(
        id = chat.id.toString(),
        title = title,
        type = chat.type,
        lastMessageId = last?.id?.toString().orEmpty(),
        lastText = last?.text?.takeIf { it.isNotEmpty() } ?: (forwarded?.get("text") as? String).orEmpty(),
        updatedAtMs = updated,
        unread = chat.newMessages,
        avatarUrl = avatar,
        lastAuthorId = last?.sender?.toString().orEmpty(),
        lastMedia = attachmentKind(attaches),
        lastThumbUrl = attachmentThumb(attaches),
        comments = when ((chat.raw["options"] as? Map<*, *>)?.get("COMMENTS")) {
            true -> 1
            false -> 0
            else -> -1
        },
        canWrite = if (canWrite(chat, state)) 1 else 0,
        muted = muteCode(config, chat.id, nowMs()),
        lastAuthorName = last?.sender?.let { state.displayName(it) }.orEmpty(),
        lastFromMe = when {
            last?.sender == null || me == null -> -1
            last.sender == me -> 1
            else -> 0
        },
        lastForwarded = if (forwarded != null) 1 else 0,
        peerReadMs = peerReadMark(chat, me),
        active = if (isActive(chat)) 1 else 0,
        lastTimeMs = last?.time ?: 0L,
    )
}

/**
 * The newest read mark of the participants other than [me]: `participants` maps a user id to
 * the time (ms) up to which they read the chat. In a dialog that is the peer, in a group anyone
 * else, as other clients show it. Channels report none.
 */
private fun peerReadMark(chat: Chat, me: Long?): Long {
    if (chat.type == "CHANNEL") return 0L
    val participants = chat.raw["participants"] as? Map<*, *> ?: return 0L
    var newest = 0L
    for ((key, value) in participants) {
        val id = when (key) {
            is Number -> key.toLong()
            is String -> key.toLongOrNull()
            else -> null
        } ?: continue
        if (id == me) continue
        val mark = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: continue
        if (mark > newest) newest = mark
    }
    return newest
}

/**
 * Whether the account may post to [chat], as Komet decides it: not in a chat it left or was
 * removed from (`status` other than `ACTIVE`), in a channel only as its owner or an admin
 * (`owner`, `admins` or the keys of `adminParticipants`), and not in a dialog with an official
 * service account (a peer with the `OFFICIAL` option that is not a `BOT`).
 */
/** The chat's `status` is empty or `ACTIVE`: the account takes part in it ([IosChat.active]). */
private fun isActive(chat: Chat): Boolean {
    val status = chat.raw["status"] as? String
    return status.isNullOrEmpty() || status == "ACTIVE"
}

private fun canWrite(chat: Chat, state: MaxState): Boolean {
    val status = chat.raw["status"] as? String
    if (!status.isNullOrEmpty() && status != "ACTIVE") return false
    val me = state.me
    if (chat.type == "CHANNEL") {
        if (me == null) return false
        val owner = (chat.raw["owner"] as? Number)?.toLong() ?: (chat.raw["owner"] as? String)?.toLongOrNull()
        if (owner == me) return true
        val admins = (chat.raw["admins"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() ?: it?.toString()?.toLongOrNull() } +
            (chat.raw["adminParticipants"] as? Map<*, *>).orEmpty().keys.mapNotNull { (it as? Number)?.toLong() ?: it?.toString()?.toLongOrNull() }
        return me in admins
    }
    val peer = dialogPeer(chat, me)?.let { state.users[it] } ?: return true
    return !("OFFICIAL" in peer.options && "BOT" !in peer.options)
}

/**
 * The first attachment of [message] as the chat list names it: `photo`, `video`, `videoMessage`
 * (`videoType` 1), `voice`, `file`, `sticker`, `contact`, `location`, `poll`, `call` or `gif`.
 * Empty when there is none or its `_type` is unknown (`CONTROL`, `SHARE`, keyboards).
 */
/** The forwarded message of a `FORWARD` link, or `null`. */
private fun forwardedOf(message: MaxMessage?): Map<*, *>? {
    val link = message?.link ?: return null
    if ((link["type"] as? String)?.uppercase() != "FORWARD") return null
    return link["message"] as? Map<*, *>
}

private fun attachmentKind(attaches: List<*>): String {
    val attach = attaches.firstOrNull() as? Map<*, *> ?: return ""
    return when ((attach["_type"] as? String)?.uppercase()) {
        "PHOTO" -> if ((attach["gif"] as? Boolean) == true) "gif" else "photo"
        "VIDEO" -> if ((attach["videoType"] as? Number)?.toInt() == 1) "videoMessage" else "video"
        "AUDIO" -> "voice"
        "FILE" -> "file"
        "STICKER" -> "sticker"
        "CONTACT" -> "contact"
        "LOCATION" -> "location"
        "POLL" -> "poll"
        // A group call carries a join link; the app labels it «Групповой звонок».
        "CALL" -> if ((attach["joinLink"] as? String).isNullOrBlank()) "call" else "groupCall"
        else -> ""
    }
}

private fun attachmentThumb(attaches: List<*>): String {
    val attach = attaches.firstOrNull() as? Map<*, *> ?: return ""
    return when ((attach["_type"] as? String)?.uppercase()) {
        "PHOTO" -> attach["baseUrl"] as? String
        "VIDEO" -> attach["thumbnail"] as? String
        else -> null
    }.orEmpty()
}

/**
 * The other participant of a `DIALOG` (keys of `participants`), or `null` for other chats. A
 * dialog with only the own id is "saved messages" and has no peer.
 */
private fun dialogPeer(chat: Chat, me: Long?): Long? {
    if (chat.type != "DIALOG") return null
    val ids = (chat.raw["participants"] as? Map<*, *>).orEmpty().keys.mapNotNull { key ->
        when (key) {
            is Number -> key.toLong()
            is String -> key.toLongOrNull()
            else -> null
        }
    }
    return ids.firstOrNull { it != me }
}

private fun chatProfile(chat: Chat): IosProfile {
    val options = chat.raw["options"] as? Map<*, *>
    return IosProfile(
        kind = if (chat.type == "CHANNEL") "channel" else "group",
        chatId = chat.id.toString(),
        title = chat.title.orEmpty(),
        avatarUrl = (chat.raw["baseIconUrl"] as? String).orEmpty(),
        description = (chat.raw["description"] as? String)?.trim().orEmpty(),
        link = (chat.raw["link"] as? String).orEmpty(),
        participants = chat.participantsCount,
        official = options?.get("OFFICIAL") == true,
        isPublic = chat.raw["access"] == "PUBLIC",
        comments = when (options?.get("COMMENTS")) {
            true -> 1
            false -> 0
            else -> -1
        },
    )
}

/** Contact list for the UI: a deleted account (`accountStatus != 0`) stays out, as in Komet. */
private fun listedContacts(state: MaxState, ttlMs: Long = MaxState.DEFAULT_PRESENCE_TTL_MS): List<IosContact> =
    state.contactIds.mapNotNull { id ->
        val user = state.users[id] ?: return@mapNotNull null
        if ((user.accountStatus ?: 0) != 0) return@mapNotNull null
        contactSnapshot(user, state, ttlMs)
    }

/**
 * Name for the list, as Komet stores it: the `CUSTOM` entry, else `ONEME`, else the first.
 * `firstName` and `lastName` win. A blank pair falls back to `name`.
 */
private fun contactSnapshot(user: MaxUser, state: MaxState, ttlMs: Long = MaxState.DEFAULT_PRESENCE_TTL_MS): IosContact {
    val names = user.names
    val chosen = names.firstOrNull { it.type == "CUSTOM" }
        ?: names.firstOrNull { it.type == "ONEME" }
        ?: names.firstOrNull()
    val givenFirst = chosen?.firstName?.trim().orEmpty()
    val givenLast = chosen?.lastName?.trim().orEmpty()
    val first: String
    val last: String
    if (givenFirst.isEmpty() && givenLast.isEmpty()) {
        first = chosen?.name?.trim().orEmpty()
        last = ""
    } else {
        first = givenFirst
        last = givenLast
    }
    val seen = state.presenceAt(user.id, nowMs(), ttlMs)
    val options = user.options
    return IosContact(
        id = user.id.toString(),
        firstName = first,
        lastName = last,
        phone = user.phone?.toString().orEmpty(),
        avatarUrl = user.baseUrl.orEmpty(),
        lastSeenMs = Presences.seenMs(seen?.seen),
        online = seen?.status == PresenceStatus.ONLINE,
        accountStatus = user.accountStatus ?: 0,
        isBot = "BOT" in options,
        isOfficial = "OFFICIAL" in options,
        isServiceAccount = "SERVICE_ACCOUNT" in options,
    ).apply {
        displayName = nameOf(user, state).orEmpty()
        this.presence = PresenceStatus.of(seen)
    }
}

/** Drops loaded messages and the chat preview. The chat itself stays. */
private fun forgetHistory(client: com.max.shared.MaxClient, chatId: Long) {
    val state = client.store.state.value
    val chat = state.chats[chatId]
    val ids = ArrayList(state.messagesOf(chatId).map { it.id })
    val last = chat?.lastMessage?.id
    if (last != null && last !in ids) ids.add(last)
    if (chat != null) client.store.putChats(listOf(chat.copy(newMessages = 0)))
    if (ids.isNotEmpty()) {
        client.store.apply(
            MaxEvent.MessagesDeleted(chatId, ids, null, null, false, Opcode.CHAT_CLEAR.value, null),
        )
    }
    client.store.closeHistoryGap(chatId)
}

internal fun callStart(signal: CallSignaling, userAgent: UserAgentInfo, joinLink: String): IosCallStart = IosCallStart(
    conversationId = signal.conversationId,
    ws2Url = ws2UrlFromEndpoint(signal.endpoint, Ws2ClientInfo.forCalls(userAgent)),
    callsUserId = signal.callsUserId,
    peerCallsUserId = signal.peerExternalId,
    joinLink = joinLink,
    isVideo = signal.isVideo,
)

internal fun incomingCallSnapshot(
    event: MaxEvent.CallStart,
    params: ConversationParams,
    state: MaxState,
    userAgent: UserAgentInfo,
): IosIncomingCall {
    val caller = state.users[event.callerId]
    return IosIncomingCall(
        conversationId = event.conversationId,
        callerId = event.callerId.toString(),
        callerName = caller?.let { nameOf(it, state) }.orEmpty(),
        callerAvatarUrl = caller?.baseUrl.orEmpty(),
        chatId = event.chatId?.toString().orEmpty(),
        isVideo = event.type == "VIDEO" || params.isVideo,
        ws2Url = params.ws2Url(event.conversationId, Ws2ClientInfo.forCalls(userAgent)),
        callsUserId = params.userId(),
        stunUrls = listOfNotNull(params.stun?.takeIf { it.isNotEmpty() }),
        turnUrls = params.turn,
        turnUsername = params.turnUser.orEmpty(),
        turnPassword = params.turnPassword.orEmpty(),
        expiresAtMs = (params.expiresAt ?: 0L) * 1000,
    )
}

private fun callSnapshot(entry: CallLogEntry, me: Long?, state: MaxState): IosCall {
    val peerId = entry.peerId(me)
    val peer = peerId?.let { state.users[it] }
    return IosCall(
        id = entry.messageId.toString(),
        chatId = entry.chatId?.toString().orEmpty(),
        peerId = peerId?.toString().orEmpty(),
        title = peer?.let { nameOf(it, state) }.orEmpty(),
        avatarUrl = peer?.baseUrl.orEmpty(),
        isGroup = peer == null && entry.contactIds.size > 1,
        outgoing = me != null && entry.senderId == me,
        missed = entry.isMissed(me),
        video = entry.isVideo,
        hangupType = entry.hangupType.orEmpty(),
        duration = entry.duration,
        timeMs = entry.time,
    )
}

internal fun messageSnapshot(message: MaxMessage, fallbackChatId: String, state: MaxState, withReactions: Boolean = true): IosMessage {
    val user = message.sender?.let { state.users[it] }
    return IosMessage(
        id = message.id.toString(),
        chatId = message.chatId?.toString() ?: fallbackChatId,
        authorId = message.sender?.toString().orEmpty(),
        text = message.text,
        timeMs = message.time,
        contentJson = messageContentJson(message) { id -> state.displayName(id) },
        authorName = user?.let { nameOf(it, state) }.orEmpty(),
        authorAvatarUrl = user?.baseUrl.orEmpty(),
        reactionsJson = if (withReactions) historyReactions(message, message.chatId ?: fallbackChatId.toLongOrNull(), state) else "",
    ).apply {
        updateTime = message.updateTime ?: 0L
        marks = textMarksOf(message)
        elementsJson = TextElementsJson.write(message.textElements)
    }
}

/**
 * Reactions of a history or push message for Swift. Channel posts come without `reactionInfo`
 * (their reactions are asked with `MSG_GET_REACTIONS`), so there a missing value is unknown
 * (empty string), not "no reactions"; elsewhere a missing value means none. The own reaction
 * is known only when the server's map has the `yourReaction` key: without it the app keeps
 * the one it knows instead of clearing it.
 */
private fun historyReactions(message: MaxMessage, chatId: Long?, state: MaxState): String {
    val info = message.reactionInfo
    if (info == null && chatId != null && state.chats[chatId]?.type == "CHANNEL") return ""
    val mineKnown = info == null || info.yourReaction != null || info.raw.containsKey("yourReaction")
    return reactionsJson(info, mineKnown = mineKnown)
}

private fun flatten(event: MaxEvent, state: MaxState): List<IosEvent> = when (event) {
    is MaxEvent.NewMessage -> listOf(messageEvent("message", event.message, state, withReactions = true))
    is MaxEvent.MessageEdited -> listOf(messageEvent("edited", event.message, state, withReactions = false))
    is MaxEvent.ReactionsChanged -> listOf(reactionsEvent(event, state))
    is MaxEvent.MessagesDeleted -> event.messageIds.map { id ->
        iosEvent(kind = "deleted", chatId = event.chatId.toString(), messageId = id.toString())
    }
    is MaxEvent.ChatUpdated -> listOf(chatEvent(event.chat, state))
    is MaxEvent.Typing -> listOf(typingEvent(event))
    is MaxEvent.MessageRead -> listOf(
        iosEvent(
            kind = "read",
            chatId = event.chatId.toString(),
            authorId = event.userId.toString(),
            timeMs = event.mark,
            unread = if (event.setAsUnread) 1 else 0,
        ),
    )
    is MaxEvent.StoriesUpdated -> listOf(storiesEvent(event.preview))
    is MaxEvent.ContactUpdated -> listOf(
        iosEvent(kind = "contact", authorId = event.user.id.toString(), title = state.displayLabel(event.user.id), timeMs = event.user.updateTime ?: 0L),
    )
    is MaxEvent.Unknown -> if (event.opcode == Opcode.TRANSCRIPTION_RESULT.value) transcriptionEvent(event.raw) else emptyList()
    else -> emptyList()
}

/**
 * `NOTIF_TYPING` push (129): [IosEvent.authorId] is typing in [IosEvent.chatId]; [IosEvent.text]
 * is the effective `type` ([MaxEvent.Typing.effectiveType], an [IosTypingType] value; `TEXT` when
 * the push had no or an unrecognised `type`).
 */
internal fun typingEvent(event: MaxEvent.Typing): IosEvent =
    iosEvent(kind = "typing", chatId = event.chatId.toString(), authorId = event.userId.toString(), text = event.effectiveType)

/**
 * `NOTIF_STORIES_UPDATE` push (216): an owner's ring changed. [IosEvent.chatId] is the owner id,
 * [IosEvent.chatType] its type (`0` person, `1` group, `2` channel), [IosEvent.unread] the unseen
 * count, [IosEvent.timeMs] the ring's update time, [IosEvent.text] the total count (`0`: the
 * owner has no stories left, drop the ring).
 */
private fun storiesEvent(preview: StoryPreview): IosEvent = iosEvent(
    kind = "stories",
    chatId = preview.owner.ownerId.toString(),
    chatType = preview.owner.type.code.toString(),
    timeMs = preview.updateTime,
    unread = preview.unreadCount,
    text = preview.totalCount.toString(),
)

/** `TRANSCRIPTION_RESULT` push (293): the text of a voice message the server finished. */
private fun transcriptionEvent(raw: Any?): List<IosEvent> {
    val result = Transcription.from(raw) ?: return emptyList()
    val messageId = result.messageId ?: return emptyList()
    return listOf(
        iosEvent(
            kind = "transcription",
            chatId = result.chatId?.toString().orEmpty(),
            messageId = messageId.toString(),
            text = result.text.orEmpty(),
            unread = result.status,
        ),
    )
}

/**
 * `reactions` event. The own reaction comes from the push if it has one, else from the stored
 * message while its counter is still in the push; otherwise it is left out as unknown.
 */
private fun reactionsEvent(event: MaxEvent.ReactionsChanged, state: MaxState): IosEvent {
    val stored = event.messageId.toLongOrNull()?.let { id -> state.messages[event.chatId]?.firstOrNull { it.id == id } }
    val mine = event.yourReaction
        ?: stored?.reactionInfo?.yourReaction?.takeIf { own -> event.counters.any { it.reaction == own && it.count > 0 } }
    val info = ReactionInfo(event.totalCount, event.counters, mine, emptyMap<Any?, Any?>())
    return iosEvent(
        kind = "reactions",
        chatId = event.chatId.toString(),
        messageId = event.messageId,
        reactionsJson = reactionsJson(info, mineKnown = mine != null),
    )
}

internal fun messageEvent(kind: String, message: MaxMessage, state: MaxState, withReactions: Boolean): IosEvent {
    val user = message.sender?.let { state.users[it] }
    return iosEvent(
        kind = kind,
        chatId = message.chatId?.toString().orEmpty(),
        messageId = message.id.toString(),
        authorId = message.sender?.toString().orEmpty(),
        text = message.text,
        timeMs = message.time,
        contentJson = messageContentJson(message) { id -> state.displayName(id) },
        authorName = user?.let { nameOf(it, state) }.orEmpty(),
        authorAvatarUrl = user?.baseUrl.orEmpty(),
        reactionsJson = if (withReactions) historyReactions(message, message.chatId, state) else "",
    ).apply {
        updateTime = message.updateTime ?: 0L
        marks = textMarksOf(message)
    }
}

private fun chatEvent(chat: Chat, state: MaxState): IosEvent {
    // A chat the account left or that was closed: the app drops it from the list, as Komet does.
    if (!isActive(chat)) return iosEvent(kind = "chatGone", chatId = chat.id.toString())
    val snap = chatSnapshot(chat, state)
    return iosEvent(
        kind = "chat",
        chatId = snap.id,
        messageId = snap.lastMessageId,
        text = snap.lastText,
        title = snap.title,
        chatType = snap.type,
        timeMs = snap.updatedAtMs,
        unread = snap.unread,
    )
}

private fun iosEvent(
    kind: String,
    chatId: String = "",
    messageId: String = "",
    authorId: String = "",
    text: String = "",
    title: String = "",
    chatType: String = "",
    timeMs: Long = 0,
    unread: Int = -1,
    contentJson: String = "",
    authorName: String = "",
    authorAvatarUrl: String = "",
    reactionsJson: String = "",
): IosEvent = IosEvent(
    kind, chatId, messageId, authorId, text, title, chatType, timeMs, unread, contentJson, authorName, authorAvatarUrl, reactionsJson,
)
