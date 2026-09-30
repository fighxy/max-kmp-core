@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.ios

import com.max.core.ErrorKind
import com.max.core.api.AccountConfig
import com.max.core.api.EntryApp
import com.max.core.api.Chat
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.ReactionInfo
import com.max.core.media.OutgoingMedia
import com.max.core.media.UploadProgress
import com.max.core.media.messageContentJson
import com.max.core.media.reactionsJson
import com.max.core.calls.CallLogEntry
import com.max.core.state.MaxState
import com.max.core.auth.CodeRequestType
import com.max.core.auth.VerifyResult
import com.max.core.events.MaxEvent
import com.max.core.toMaxError
import com.max.shared.MaxClient
import com.max.shared.DeviceProfile
import com.max.shared.MaxClientConfig
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
    constructor(namespace: String) : this(newScope(), { scope -> MaxClient(MaxClientConfig(namespace = namespace), scope = scope) })

    private val clientLock = NSLock()
    private var created: MaxClient? = null
    private val watches = mutableListOf<Watcher>()
    /** The user whose whole chat list was already paged in ([loadChats]). */
    private var pagedUser: Long? = null

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
            clientLock.locked { pagedUser = null }
        }
    }

    /**
     * The chat list. The first call after each login pages through the whole list
     * ([MaxClient.loadAllChats]) and resyncs the folders with the pinned chats
     * ([MaxClient.loadFolders], best effort: the `LOGIN` config normally has them already);
     * later calls (polls) refresh only the newest page.
     */
    fun loadChats(onResult: (List<IosChat>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val user = c.userId.value
            if (user != null && clientLock.locked { pagedUser } != user) {
                c.loadAllChats()
                syncFolders(c)
                clientLock.locked { pagedUser = user }
            } else {
                c.loadChats()
            }
            val chats = c.store.state.value.chats.values
            // Dialog peers and the authors of groups' last messages, so rows can name them.
            resolveUsers(c, chats.mapNotNull { dialogPeer(it, c.userId.value) } + chats.filter { it.type == "CHAT" }.mapNotNull { it.lastMessage?.sender })
            val state = c.store.state.value
            val config = c.accountConfig.value
            chats.map { chatSnapshot(it, state, config) }
        }
    }

    fun loadChat(chatId: String, onResult: (IosChat?, String?, String?) -> Unit) {
        perform(onResult, { null }) { c ->
            val chat = c.api.chats.getChat(parseId(chatId))
            resolveUsers(c, listOfNotNull(dialogPeer(chat, c.userId.value)))
            chatSnapshot(chat, c.store.state.value, c.accountConfig.value)
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
            val me = c.userId.value
            val stored = c.store.state.value.chats[id]
            val chat = if (stored == null || stored.type != "DIALOG") fetchChatOrNull(c, id) ?: stored else stored
            if (chat != null && chat.type != "DIALOG") {
                chatProfile(chat)
            } else {
                val peer = chat?.let { dialogPeer(it, me) } ?: me?.let { id xor it }
                if (peer == null || peer == me || peer == 0L) {
                    IosProfile(kind = "saved", chatId = chatId)
                } else {
                    userProfile(c, chatId, peer)
                }
            }
        }
    }

    /** The account's contact list from the last `LOGIN` reply, with the last known presence. */
    fun loadContacts(onResult: (List<IosContact>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val state = c.store.state.value
            state.contactIds.mapNotNull { id -> state.users[id]?.let { contactSnapshot(it, state) } }
        }
    }

    /**
     * The call log (`VIDEO_CHAT_HISTORY` 79), newest first as the server sends it. Unknown peers
     * are looked up with `CONTACT_INFO` once; a call whose peer stays unknown is a group call.
     */
    fun loadCallHistory(onResult: (List<IosCall>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val me = c.userId.value
            val entries = c.api.calls.history()
            resolveUsers(c, entries.mapNotNull { it.peerId(me) })
            val state = c.store.state.value
            entries.map { callSnapshot(it, me, state) }
        }
    }

    fun loadHistory(chatId: String, beforeMs: Long, limit: Int, onResult: (List<IosMessage>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c ->
            val history = c.loadHistory(parseId(chatId), from = beforeMs.takeIf { it > 0 }, backward = limit.coerceIn(1, 100))
            resolveUsers(c, history.messages.mapNotNull { it.sender })
            val state = c.store.state.value
            history.messages.map { messageSnapshot(it, chatId, state) }
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
            val page = c.api.messages.getCommentHistory(
                parseId(chatId),
                parseId(postId),
                from = if (beforeMs > 0) beforeMs else -1,
                backward = limit.coerceIn(1, 100),
            )
            resolveUsers(c, page.mapNotNull { it.sender })
            val state = c.store.state.value
            page.filter { beforeMs <= 0 || it.time < beforeMs }
                .sortedBy { it.time }
                .map { messageSnapshot(it, chatId, state) }
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
            c.api.messages.getCommentsInfo(parseId(chatId), ids).map { info ->
                IosCommentCount(postId = info.postId.toString(), count = info.totalCount ?: 0)
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
            ids.chunked(REACTIONS_PAGE).flatMap { page ->
                c.loadReactions(parseId(chatId), page).map { (id, info) ->
                    // Без ключа `yourReaction` своя реакция неизвестна, а не «нет».
                    val mineKnown = info.yourReaction != null || info.raw.containsKey("yourReaction")
                    IosReactions(id.toString(), reactionsJson(info, mineKnown = mineKnown))
                }
            }
        }
    }

    /** Emoji the server offers for reactions (the animoji catalog), in its order; may be empty. */
    fun loadReactionCatalog(onResult: (List<String>, String?, String?) -> Unit) {
        perform(onResult, { emptyList() }) { c -> c.reactionCatalog().map { it.emoji } }
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
            state.contactIds.mapNotNull { id -> state.users[id]?.let { contactSnapshot(it, state) } }
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

/** Decimal id from Swift; a malformed one becomes an [IllegalArgumentException] (error kind `UNKNOWN`). */
private fun parseId(value: String): Long =
    value.toLongOrNull() ?: throw IllegalArgumentException("not a numeric id: \"$value\"")

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
)

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

/** One entry of [MaxIosClient.loadReactionUsers]; [name] and [avatarUrl] are empty for an unknown user. */
class IosReactionUser(val userId: String, val name: String, val avatarUrl: String, val reaction: String)

/**
 * A push the app stores or shows.
 *
 * [kind] is `message`, `edited`, `deleted`, `chat`, `typing`, `read` or `reactions`.
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
    )
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
        "CALL" -> "call"
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

private fun contactSnapshot(user: MaxUser, state: MaxState): IosContact {
    val name = user.names.firstOrNull()
    val first = name?.firstName?.takeIf { it.isNotBlank() } ?: name?.name.orEmpty()
    val presence = state.presence[user.id]
    return IosContact(
        id = user.id.toString(),
        firstName = first,
        lastName = name?.lastName.orEmpty(),
        phone = user.phone?.toString().orEmpty(),
        avatarUrl = user.baseUrl.orEmpty(),
        lastSeenMs = presence?.seen?.let { if (it < 100_000_000_000L) it * 1000 else it } ?: 0L,
        online = presence?.status == 1,
    )
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
    else -> emptyList()
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
