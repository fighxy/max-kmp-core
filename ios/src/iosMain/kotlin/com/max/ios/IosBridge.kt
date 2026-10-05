@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.ios

import com.max.core.ErrorKind
import com.max.core.api.AccountConfig
import com.max.core.api.EntryApp
import com.max.core.api.Transcription
import com.max.core.api.Chat
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.ReactionInfo
import com.max.core.api.hasWebApp
import com.max.core.media.OutgoingMedia
import com.max.core.media.UploadProgress
import com.max.core.media.fileUploadSource
import com.max.core.media.messageContentJson
import com.max.core.media.reactionsJson
import com.max.core.calls.CallLogEntry
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
import kotlinx.coroutines.flow.map
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
    private val factory: (CoroutineScope) -> MaxClient,
) {
    /**
     * The app keeps its own history, so the core does not page through every chat's history gap
     * after each reconnect ([MaxClientConfig.fillGapsOnReconnect]): that burst of `CHAT_HISTORY`
     * requests ran into `too.many.requests`. Open chats reload their history themselves.
     */
    constructor(namespace: String) : this(
        newScope(),
        { scope -> MaxClient(MaxClientConfig(namespace = namespace, fillGapsOnReconnect = false), scope = scope) },
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
    private fun client(): MaxClient = clientLock.locked { created ?: factory(scope).also { created = it } }

    fun phaseName(): String = attempt("failed") { phaseOf(client().state.value) }

    fun currentUserId(): String = attempt("") { client().userId.value?.toString().orEmpty() }

    fun hasStoredToken(): Boolean = attempt(false) { client().hasStoredToken }

    /**
     * HTTP User-Agent of the session's Android profile (`OKMessages/…`). The CDN addresses from
     * [mediaLink] are issued for that client, so video and file requests send it too.
     */
    fun mediaUserAgent(): String = attempt(DeviceProfile.android.httpUserAgent) { client().config.userAgent.httpUserAgent }

    fun start(onResult: (String?, String?, String?) -> Unit) {
        perform(onResult, { null }) { phaseOf(it.start()) }
    }

    fun requestCode(phone: String, resend: Boolean, onResult: (IosCodeRequest?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val type = if (resend) CodeRequestType.RESEND else CodeRequestType.START_AUTH
            val code = c.requestCode(phone, type)
            IosCodeRequest(code.token, code.codeLength ?: 0)
        }
    }

    fun verifyCode(token: String, code: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            when (val result = c.verifyCode(token, code)) {
                is VerifyResult.LoggedIn -> loggedInStep(c)
                is VerifyResult.PasswordRequired -> IosAuthStep("password", result.trackId, result.hint.orEmpty(), "", "")
                is VerifyResult.RegistrationRequired -> IosAuthStep("register", "", "", result.registerToken, "")
            }
        }
    }

    fun checkPassword(trackId: String, password: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            c.checkPassword(trackId, password)
            loggedInStep(c)
        }
    }

    fun register(registerToken: String, firstName: String, lastName: String, onResult: (IosAuthStep?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            c.register(registerToken, firstName, lastName.takeIf { it.isNotBlank() })
            loggedInStep(c)
        }
    }

    fun logout(onResult: (String?, String?) -> Unit) {
        runUnit(onResult) {
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
        perform(onResult, { emptyList() }) { c -> flights.share("chats") { chatList(c) } }
    }

    private suspend fun chatList(c: MaxClient): List<IosChat> {
        val user = c.userId.value
        val logins = c.logins.value
        when {
            user != null && clientLock.locked { pagedUser } != user -> {
                c.loadAllChats()
                syncFolders(c)
                clientLock.locked {
                    pagedUser = user
                    listedLogins = logins
                }
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
        return chats.map { chatSnapshot(it, state, config) }
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
            listedContacts(state)
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

    /** Replaces the text of a sent message (`MSG_EDIT` 67); the edited message comes back. */
    fun editMessage(chatId: String, messageId: String, text: String, onResult: (IosMessage?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val edited = c.api.messages.editMessage(parseId(chatId), parseId(messageId), text)
            // The edit reply may leave reactions out; the app keeps the ones it has.
            messageSnapshot(edited, chatId, c.store.state.value, withReactions = false)
        }
    }

    /**
     * Deletes messages (`MSG_DELETE` 66). [forEveryone] `false` removes them only for this
     * account (`forMe`), `true` for every participant.
     */
    fun deleteMessages(chatId: String, messageIds: List<String>, forEveryone: Boolean, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { c ->
            val ids = messageIds.map(::parseId)
            require(ids.isNotEmpty()) { "no message ids" }
            c.api.messages.deleteMessages(parseId(chatId), ids, forMe = !forEveryone)
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
                IosReactionUser(entry.userId.toString(), user?.displayName.orEmpty(), user?.baseUrl.orEmpty(), entry.reaction)
            }
        }
    }

    fun markRead(chatId: String, messageId: String, onResult: (String?, String?) -> Unit) {
        runUnit(onResult) { it.api.messages.markRead(parseId(chatId), parseId(messageId)) }
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

    /** The whole contact list (opcode 8 `{contactsSync: 0}`) into the store, then as [loadContacts]. */
    fun syncContacts(onResult: (List<IosContact>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.syncContacts()
            val state = c.store.state.value
            listedContacts(state)
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
            contactSnapshot(user, c.store.state.value)
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
            contactSnapshot(user, c.store.state.value)
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

    /** First page of group or channel members. */
    fun chatMembers(chatId: String, onResult: (List<IosChatMember>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.api.chats.getChatMembers(parseId(chatId)).members.mapNotNull { member ->
                val user = MaxUser.from(member.contact) ?: return@mapNotNull null
                IosChatMember(user.id.toString(), user.displayName?.trim().orEmpty().ifEmpty { "Участник" })
            }
        }
    }

    /** Bot menu (`BOT_INFO` 145). Names keep the server's spelling, without a leading slash. */
    fun botCommands(botId: String, onResult: (List<IosBotCommand>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            c.api.bots.getBotInfo(parseId(botId)).commands.map { IosBotCommand(it.name, it.description.orEmpty()) }
        }
    }

    /**
     * Asks the server to start a call. Media is not opened here.
     * `null` when the reply has no endpoint.
     */
    fun signalCall(calleeId: String, isVideo: Boolean, onResult: (IosCallSignal?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val conversationId = randomUuid()
            val packet = c.session.request(
                Opcode.VIDEO_CHAT_START_ACTIVE,
                linkedMapOf(
                    "conversationId" to conversationId,
                    "calleeIds" to listOf(parseId(calleeId)),
                    "internalParams" to callInternalParams(c.device.deviceId),
                    "isVideo" to isVideo,
                ),
            )
            val map = packet.payload as? Map<*, *> ?: return@perform null
            val endpoint = endpointOf(map["internalCallerParams"] as? String) ?: return@perform null
            val conversation = (map["conversationId"] as? String)?.takeIf { it.isNotEmpty() } ?: conversationId
            IosCallSignal(conversation, endpoint)
        }
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
     * Pushes in arrival order. A message from a sender the store does not know yet waits up to
     * [SENDER_WAIT_MS] for `CONTACT_INFO`, so the event carries the sender name and avatar.
     */
    fun watchEvents(onEach: (IosEvent) -> Unit): IosWatch = watch { c ->
        c.events.all
            .map { event ->
                val sender = when (event) {
                    is MaxEvent.NewMessage -> event.message.sender
                    is MaxEvent.MessageEdited -> event.message.sender
                    else -> null
                }
                if (sender != null) withTimeoutOrNull(SENDER_WAIT_MS) { resolveUsers(c, listOf(sender)) }
                event
            }
            .watch(scope) { event -> guarded { flatten(event, c.store.state.value).forEach(onEach) } }
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
        val presence = c.store.state.value.presence[peer]
        return IosProfile(
            kind = if (isBot) "bot" else "user",
            chatId = chatId,
            peerId = peer.toString(),
            title = user.displayName.orEmpty(),
            avatarUrl = user.baseUrl.orEmpty(),
            description = card.description?.trim().orEmpty(),
            link = card.link.orEmpty(),
            phone = user.phone?.takeIf { it > 0 }?.toString().orEmpty(),
            lastSeenMs = presence?.seen?.let { if (it < 100_000_000_000L) it * 1000 else it } ?: 0L,
            online = presence?.status == 1,
            official = "OFFICIAL" in user.options,
            commands = bot?.commands.orEmpty().map { IosBotCommand(it.name, it.description.orEmpty()) },
            hasWebApp = isBot && hasWebApp(user.options + card.options),
        )
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
    private fun <T> perform(onResult: (T, String?, String?) -> Unit, fallback: () -> T, body: suspend (MaxClient) -> T): Job =
        scope.launch(start = CoroutineStart.ATOMIC) {
            val outcome = try {
                Result.success(body(client()))
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

    private fun runUnit(onResult: (String?, String?) -> Unit, body: suspend (MaxClient) -> Unit) {
        perform<Unit>({ _, kind, key -> onResult(kind, key) }, { }) { body(it) }
    }
}

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

/** `hexCapability` in the call body. Media is not opened from this JSON. */
private fun callInternalParams(deviceId: String): String = buildString {
    append("{\"platform\":\"ANDROID\",\"sdkVersion\":\"0.2.1.3\",\"clientAppKey\":\"CGPGAGLGDIHBABABA\",\"deviceId\":")
    append('"')
    append(deviceId.replace("\\", "\\\\").replace("\"", "\\\""))
    append("\",\"protocolVersion\":5,\"onlyAdminCanRecord\":false,\"isWaitForAdminEnabled\":false,\"hexCapability\":\"3c02f\"}")
}

/** First `"endpoint"` string inside the call reply JSON. */
private fun endpointOf(json: String?): String? {
    if (json.isNullOrBlank()) return null
    val needle = "\"endpoint\""
    val at = json.indexOf(needle)
    if (at < 0) return null
    var i = json.indexOf(':', at + needle.length)
    if (i < 0) return null
    i++
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != '"') return null
    i++
    val out = StringBuilder()
    while (i < json.length) {
        val c = json[i]
        if (c == '\\' && i + 1 < json.length) {
            out.append(json[i + 1])
            i += 2
            continue
        }
        if (c == '"') return out.toString().takeIf { it.isNotEmpty() }
        out.append(c)
        i++
    }
    return null
}

/** Version-4 UUID. `java.util.UUID` is not on the native target. */
private fun randomUuid(): String {
    val bytes = kotlin.random.Random.Default.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
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
    /** Notifications off (`config.chats[id].dontDisturbUntil`): `1` muted, `0` on, `-1` unknown. */
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
)

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
)

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
)

/** Reactions of one message ([MaxIosClient.loadReactions]); [json] as [IosMessage.reactionsJson]. */
class IosReactions(val messageId: String, val json: String)

/** An animated emoji in sent text ([MaxIosClient.sendText]): UTF-16 [from]/[length] of the emoji. */
class IosAnimojiMark(val from: Int, val length: Int, val animojiId: String, val lottieUrl: String)

/** A `USER_MENTION` over `@name` in the outgoing text. [userId] is decimal. */
class IosMentionMark(val from: Int, val length: Int, val userId: String)

class IosChatMember(val id: String, val name: String)

/** Server accepted a call signal. [endpoint] is the first `"endpoint"` in `internalCallerParams`. */
class IosCallSignal(val conversationId: String, val endpoint: String)

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

/** [MaxIosClient.transcribeVoice]: [status] `1` ready, `0` in progress, `-1` failed. */
class IosTranscription(val status: Int, val text: String)

/** One entry of [MaxIosClient.loadReactionUsers]; [name] and [avatarUrl] are empty for an unknown user. */
class IosReactionUser(val userId: String, val name: String, val avatarUrl: String, val reaction: String)

/**
 * A push the app stores or shows.
 *
 * [kind] is `message`, `edited`, `deleted`, `chat`, `typing`, `read`, `reactions` or
 * `transcription` (the text of a voice message in [text], its status in [unread]).
 * [unread] is `-1` when this event does not change the unread counter.
 * [reactionsJson] as in [IosMessage]: set for `message` and `reactions`, empty for `edited` (an
 * edit keeps the reactions). For `reactions` it has no `yourReaction` key when the own reaction
 * is unknown (`NOTIF_MSG_REACTIONS_CHANGED` 155 carries only counters).
 * Call, presence and unknown pushes are not forwarded.
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
)

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

private fun chatSnapshot(chat: Chat, state: MaxState, config: AccountConfig? = null): IosChat {
    val last = chat.lastMessage
    val updated = when {
        chat.lastEventTime > 0 -> chat.lastEventTime
        last != null -> last.time
        else -> 0L
    }
    val peer = dialogPeer(chat, state.me)?.let { state.users[it] }
    val title = chat.title?.takeIf { it.isNotBlank() } ?: peer?.displayName.orEmpty()
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
        muted = when (config?.isMuted(chat.id, (NSDate().timeIntervalSince1970 * 1000).toLong())) {
            true -> 1
            false -> 0
            null -> if (config == null) -1 else 0
        },
        lastAuthorName = last?.sender?.let { state.users[it]?.displayName }.orEmpty(),
        lastFromMe = when {
            last?.sender == null || me == null -> -1
            last.sender == me -> 1
            else -> 0
        },
        lastForwarded = if (forwarded != null) 1 else 0,
        peerReadMs = peerReadMark(chat, me),
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
    )
}

/** Contact list for the UI: a deleted account (`accountStatus != 0`) stays out, as in Komet. */
private fun listedContacts(state: MaxState): List<IosContact> =
    state.contactIds.mapNotNull { id ->
        val user = state.users[id] ?: return@mapNotNull null
        if ((user.accountStatus ?: 0) != 0) return@mapNotNull null
        contactSnapshot(user, state)
    }

/**
 * Name for the list, as Komet stores it: the `CUSTOM` entry, else `ONEME`, else the first.
 * `firstName` and `lastName` win. A blank pair falls back to `name`.
 */
private fun contactSnapshot(user: MaxUser, state: MaxState): IosContact {
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
    val presence = state.presence[user.id]
    val options = user.options
    return IosContact(
        id = user.id.toString(),
        firstName = first,
        lastName = last,
        phone = user.phone?.toString().orEmpty(),
        avatarUrl = user.baseUrl.orEmpty(),
        lastSeenMs = presence?.seen?.let { if (it < 100_000_000_000L) it * 1000 else it } ?: 0L,
        online = presence?.status == 1,
        accountStatus = user.accountStatus ?: 0,
        isBot = "BOT" in options,
        isOfficial = "OFFICIAL" in options,
        isServiceAccount = "SERVICE_ACCOUNT" in options,
    )
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

private fun callSnapshot(entry: CallLogEntry, me: Long?, state: MaxState): IosCall {
    val peerId = entry.peerId(me)
    val peer = peerId?.let { state.users[it] }
    return IosCall(
        id = entry.messageId.toString(),
        chatId = entry.chatId?.toString().orEmpty(),
        peerId = peerId?.toString().orEmpty(),
        title = peer?.displayName.orEmpty(),
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

private fun messageSnapshot(message: MaxMessage, fallbackChatId: String, state: MaxState, withReactions: Boolean = true): IosMessage {
    val user = message.sender?.let { state.users[it] }
    return IosMessage(
        id = message.id.toString(),
        chatId = message.chatId?.toString() ?: fallbackChatId,
        authorId = message.sender?.toString().orEmpty(),
        text = message.text,
        timeMs = message.time,
        contentJson = messageContentJson(message) { id -> state.users[id]?.displayName },
        authorName = user?.displayName.orEmpty(),
        authorAvatarUrl = user?.baseUrl.orEmpty(),
        reactionsJson = if (withReactions) historyReactions(message, message.chatId ?: fallbackChatId.toLongOrNull(), state) else "",
    )
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
    is MaxEvent.Typing -> listOf(iosEvent(kind = "typing", chatId = event.chatId.toString(), authorId = event.userId.toString()))
    is MaxEvent.MessageRead -> listOf(
        iosEvent(
            kind = "read",
            chatId = event.chatId.toString(),
            authorId = event.userId.toString(),
            timeMs = event.mark,
            unread = if (event.setAsUnread) 1 else 0,
        ),
    )
    is MaxEvent.Unknown -> if (event.opcode == Opcode.TRANSCRIPTION_RESULT.value) transcriptionEvent(event.raw) else emptyList()
    else -> emptyList()
}

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

private fun messageEvent(kind: String, message: MaxMessage, state: MaxState, withReactions: Boolean): IosEvent {
    val user = message.sender?.let { state.users[it] }
    return iosEvent(
        kind = kind,
        chatId = message.chatId?.toString().orEmpty(),
        messageId = message.id.toString(),
        authorId = message.sender?.toString().orEmpty(),
        text = message.text,
        timeMs = message.time,
        contentJson = messageContentJson(message) { id -> state.users[id]?.displayName },
        authorName = user?.displayName.orEmpty(),
        authorAvatarUrl = user?.baseUrl.orEmpty(),
        reactionsJson = if (withReactions) historyReactions(message, message.chatId, state) else "",
    )
}

private fun chatEvent(chat: Chat, state: MaxState): IosEvent {
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
