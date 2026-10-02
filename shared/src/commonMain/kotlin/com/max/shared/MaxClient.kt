package com.max.shared

import com.max.core.MaxError
import com.max.core.api.AccountConfig
import com.max.core.api.Chat
import com.max.core.api.ChatFolders
import com.max.core.api.ChatHistory
import com.max.core.api.ChatsApi
import com.max.core.api.Folder
import com.max.core.api.FolderUpdate
import com.max.core.api.MaxApi
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.Transcription
import com.max.core.api.PrivacySettings
import com.max.core.api.Profile
import com.max.core.api.Animoji
import com.max.core.api.ReactionInfo
import com.max.core.api.ReactionUser
import com.max.core.toMaxError
import com.max.core.auth.ApkFingerprint
import com.max.core.auth.AuthApi
import com.max.core.auth.CodeRequest
import com.max.core.auth.CodeRequestType
import com.max.core.auth.InvalidTokenException
import com.max.core.auth.LoginResult
import com.max.core.auth.QrApproval
import com.max.core.auth.SyncState
import com.max.core.auth.TokenLogin
import com.max.core.auth.VerifyResult
import com.max.core.events.EventRouter
import com.max.core.events.MaxEvent
import com.max.core.events.MaxEvents
import com.max.core.media.MediaApi
import com.max.core.media.MediaHttp
import com.max.core.media.MediaHttpConfig
import com.max.core.media.OutgoingAttachment
import com.max.core.media.OutgoingMedia
import com.max.core.media.UploadProgress
import com.max.core.media.defaultMediaHttp
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.session.DEFAULT_HOST
import com.max.core.session.DeviceInfo
import com.max.core.session.HandshakeInfo
import com.max.core.session.SessionClosedException
import com.max.core.session.SessionConfig
import com.max.core.session.SessionMachine
import com.max.core.session.SessionState
import com.max.core.session.UserAgentInfo
import com.max.core.state.MaxState
import com.max.core.state.MaxStore
import com.max.core.transport.ConnectionFactory
import com.max.core.transport.ProxyConfig
import com.max.core.transport.TransportConfig
import com.max.core.transport.defaultConnectionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Settings of a [MaxClient].
 *
 * @property userAgent always an Android profile ([DeviceProfile.requireAndroid]); defaults to the
 *   Pixel 8 profile on every platform.
 * @property transport socket settings; `host` / `port` / `proxyUrl` here override its fields.
 * @property namespace separates credentials of several accounts in one [KeyValueStore].
 * @property messageLimit messages kept per chat in [MaxClient.store].
 * @property fillGapsOnReconnect after a re-login, page backward through chats with an open history
 *   hole ([MaxClient.fillGaps]) until a page overlaps the local tail or the server runs out.
 * @property gapFillCount messages requested per history page when filling a gap (PyMax history default 40).
 * @property gapFillPageLimit how many pages to walk per chat. The hole stays open when the cap is
 *   hit, or when a page repeats the same oldest id. Values below 1 are treated as 1.
 */
data class MaxClientConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = 443,
    val proxyUrl: String? = null,
    val userAgent: UserAgentInfo = DeviceProfile.android,
    val transport: TransportConfig = TransportConfig(host = host, port = port, proxyUrl = proxyUrl),
    val media: MediaHttpConfig = MediaHttpConfig(proxy = proxyUrl?.let(ProxyConfig::parse)),
    val namespace: String = "default",
    val messageLimit: Int = MaxStore.DEFAULT_MESSAGE_LIMIT,
    val fingerprint: ApkFingerprint? = ApkFingerprint.forVersion(userAgent.appVersion),
    val fillGapsOnReconnect: Boolean = true,
    val gapFillCount: Int = 40,
    val gapFillPageLimit: Int = 16,
)

/** High-level state of a [MaxClient]. */
sealed interface ClientState {
    /** Not started, or stopped by [MaxClient.close] / [MaxClient.logout]. */
    data object Idle : ClientState

    /** Connecting, TLS, handshake or `LOGIN` in progress. */
    data object Connecting : ClientState

    /** Handshake done, no login token: run the SMS flow ([MaxClient.requestCode]). */
    data class AwaitingAuth(val handshake: HandshakeInfo) : ClientState

    /** Logged in and online. */
    data class Ready(val userId: Long?) : ClientState

    /** Connection lost; reconnecting with backoff (and re-login). */
    data class Reconnecting(val attempt: Int, val lastError: Throwable?) : ClientState

    /** The stored token was rejected (`FAIL_LOGIN_TOKEN` / `FAIL_LOGOUT_ALL`); it has been cleared. */
    data class TokenRejected(val cause: InvalidTokenException) : ClientState

    /** Connection or handshake failed for good. */
    data class Failed(val cause: Throwable) : ClientState
}

/** The classified error behind [ClientState.Reconnecting], [ClientState.TokenRejected] or [ClientState.Failed]. */
val ClientState.error: MaxError?
    get() = when (this) {
        is ClientState.Reconnecting -> lastError?.toMaxError()
        is ClientState.TokenRejected -> cause.toMaxError()
        is ClientState.Failed -> cause.toMaxError()
        else -> null
    }

/**
 * The one entry point for apps: a [SessionMachine] with token login, persisted credentials,
 * a [MaxStore] fed by an [EventRouter], and the request APIs.
 *
 * ```
 * val client = MaxClient(MaxClientConfig())
 * when (client.start()) {
 *     is ClientState.AwaitingAuth -> {
 *         val code = client.requestCode("+79990000000")
 *         when (val r = client.verifyCode(code.token, "123456")) {
 *             is VerifyResult.PasswordRequired -> client.checkPassword(r.trackId, password)
 *             is VerifyResult.RegistrationRequired -> client.register(r.registerToken, "Name")
 *             is VerifyResult.LoggedIn -> Unit // already logged in
 *         }
 *     }
 *     else -> Unit
 * }
 * client.api.messages.sendMessage(chatId, "hi")
 * client.store.state.value.chatList
 * ```
 *
 * Credentials (device id, `mt_instanceid`, login token, sync markers) are loaded from and saved
 * to [keyValueStore] (by default [PlatformSession.defaultStore]); a refreshed token from `LOGIN`
 * is saved automatically, a rejected one is cleared ([ClientState.TokenRejected]). Every
 * reconnect re-runs the handshake and `LOGIN` with the current token and markers. [store] lives
 * only in memory, so the first `LOGIN` of a new client sends reset markers (`-1`, default
 * `configHash`) to get a full snapshot; the saved markers are used only once this client holds
 * the snapshot they belong to (reconnects).
 *
 * The device profile is always Android ([MaxClientConfig.userAgent], checked with
 * [DeviceProfile.requireAndroid]); nothing about the host device is sent.
 *
 * Swift boundary: every public operation that can fail is annotated `@Throws(CancellationException,
 * Exception)`, so Kotlin/Native turns any core exception into an `NSError` (the Kotlin exception is in
 * `userInfo["KotlinException"]`; classify it with `toMaxError`) instead of terminating the process.
 * The constructor throws as well (invalid profile, unreadable credential store). Coroutines the
 * client launches in its own scope never let an exception escape uncaught. The Swift app uses the
 * callback facade `com.max.ios.MaxIosClient`, which never throws at all.
 */
class MaxClient @Throws(Exception::class) constructor(
    val config: MaxClientConfig = MaxClientConfig(),
    keyValueStore: KeyValueStore = PlatformSession.defaultStore(config.namespace),
    connectionFactory: ConnectionFactory = defaultConnectionFactory(),
    mediaHttp: MediaHttp? = null,
    scope: CoroutineScope? = null,
) : Session {
    private val ownsScope = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default + swallowUncaught)
    private val credentials = CredentialStore(keyValueStore, "max.${config.namespace}")
    private val tokenLogin = MutableStateFlow<TokenLogin?>(null)
    private val loggedIn = MutableStateFlow<Long?>(null)
    private val loggedInFlag = MutableStateFlow(false)
    private val loginCount = MutableStateFlow(0)
    private val _accountConfig = MutableStateFlow<AccountConfig?>(null)
    private val lifecycle = Mutex()
    /** Bumped on login, logout and token rejection so an in-flight gap fill cannot write afterwards. */
    private var sessionEpoch = 0
    /** Bumped on logout, token rejection and close; with the [TokenLogin] identity it names an account session. */
    private var accountGen = 0
    /** The [TokenLogin] of the last applied login; guarded by [lifecycle]. */
    private var lastLogin: TokenLogin? = null
    /** `true` while [store] holds a `LOGIN` snapshot of this process; guarded by [lifecycle]. */
    private var snapshotLoaded = false
    private var gapJob: Job? = null

    init {
        DeviceProfile.requireAndroid(config.userAgent)
    }

    private val stored: StoredCredentials = credentials.loadOrCreate()

    /** The identity sent in every handshake (persisted device id / instance id + Android profile). */
    val device: DeviceInfo = DeviceInfo(deviceId = stored.deviceId, instanceId = stored.instanceId, userAgent = config.userAgent)

    /** The underlying session (state, raw requests, pushes). */
    val session: SessionMachine = SessionMachine(
        config = SessionConfig(config.transport.copy(host = config.host, port = config.port, proxyUrl = config.proxyUrl), device),
        connectionFactory = connectionFactory,
        scope = this.scope,
        afterHandshake = { transport, handshake ->
            val login = tokenLogin.value
            if (login != null) {
                login.hook(transport, handshake)
                onLoggedIn(login)
            }
        },
    )

    /** Typed pushes. Hot, no replay. */
    val events: MaxEvents = MaxEvents(session)

    /** Local state (chats, messages, users, presence, typing, read marks). */
    val store: MaxStore = MaxStore(config.messageLimit)

    /**
     * Applies pushes to [store] and runs handlers registered with [EventRouter.on]. It reads the
     * lossless push stream, so a slow handler never costs the store an event.
     */
    val router: EventRouter = EventRouter(MaxEvents(session.reliablePushes).all, store)

    /** Phone-code auth, 2FA, registration, QR approval, logout. */
    val auth: AuthApi = AuthApi(session, config.fingerprint)

    /** Messages, chats, users, calls requests. */
    val api: MaxApi = MaxApi(session)

    /** Uploads, download links, messages with attachments; shares [api]'s `cid` generator. */
    val media: MediaApi = MediaApi(session, mediaHttp ?: defaultMediaHttp(config.media), api.cids)

    /** High-level state. */
    val state: StateFlow<ClientState> = combine(session.state, loggedInFlag) { s, logged -> map(s, logged) }
        .stateIn(this.scope, SharingStarted.Eagerly, ClientState.Idle)

    /** Own user id once logged in. */
    val userId: StateFlow<Long?> get() = loggedIn

    /** `true` when a login token is stored. */
    val hasStoredToken: Boolean get() = credentials.load()?.token != null

    /**
     * The account configuration ([AccountConfig]: `config.user` settings, `config.server`
     * parameters) of the last `LOGIN` that carried one, updated by [updateUserSettings]; `null`
     * before the first login and after logout. The first `LOGIN` of each process sends empty sync
     * markers (default `configHash`), so the server always sends the whole config then; a
     * reconnect whose reply leaves it out keeps the known one.
     */
    val accountConfig: StateFlow<AccountConfig?> = _accountConfig.asStateFlow()

    init {
        router.start(this.scope)
        this.scope.launch {
            session.state.collect { s ->
                if (s is SessionState.Failed && s.cause is InvalidTokenException) {
                    // a failing credential store must not escape into the scope (uncaught on iOS = crash)
                    try {
                        rejectToken()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the token stays stored; the next start() reports the rejection again
                    }
                }
            }
        }
    }

    private fun map(s: SessionState, logged: Boolean): ClientState = when (s) {
        SessionState.Disconnected, SessionState.Closed -> ClientState.Idle
        SessionState.Connecting, SessionState.Handshaking -> ClientState.Connecting
        is SessionState.Online -> if (logged) ClientState.Ready(loggedIn.value) else ClientState.AwaitingAuth(s.handshake)
        is SessionState.Reconnecting -> ClientState.Reconnecting(s.attempt, s.lastError)
        is SessionState.Failed -> (s.cause as? InvalidTokenException)?.let { ClientState.TokenRejected(it) } ?: ClientState.Failed(s.cause)
    }

    /**
     * Connects. With a stored token the handshake is followed by `LOGIN` and the result is
     * [ClientState.Ready]; without one it is [ClientState.AwaitingAuth]. A rejected token gives
     * [ClientState.TokenRejected] (the token is cleared; call [start] again for the SMS flow).
     * Other failures throw.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun start(): ClientState {
        lifecycle.withLock {
            val c = credentials.load() ?: stored
            if (tokenLogin.value == null && c.token != null) {
                // [store] is not persisted: saved markers describe a snapshot this process does not
                // have, and a LOGIN with them would return only the delta. Ask for everything instead.
                val sync = if (snapshotLoaded) c.sync else SyncState()
                tokenLogin.value = TokenLogin(c.token, device, config.fingerprint, sync)
            }
        }
        try {
            session.connect()
        } catch (e: InvalidTokenException) {
            rejectToken()
        }
        return currentState()
    }

    /** Drops the token under [lifecycle] and invalidates any gap fill still in flight. */
    private suspend fun rejectToken() {
        lifecycle.withLock {
            sessionEpoch += 1
            accountGen += 1
            credentials.clearToken()
            tokenLogin.value = null
            loggedInFlag.value = false
            gapJob?.cancel()
            gapJob = null
        }
    }

    private fun currentState(): ClientState = map(session.state.value, loggedInFlag.value)

    /** Requests an SMS code (`AUTH_REQUEST` 17); connects first if needed. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun requestCode(phone: String, type: CodeRequestType = CodeRequestType.START_AUTH): CodeRequest {
        session.connect()
        return auth.requestCode(phone, type)
    }

    /**
     * Checks the SMS code (`AUTH` 18). On [VerifyResult.LoggedIn] the client logs in with the new
     * token right away (state [ClientState.Ready]); the other results need [checkPassword] or
     * [register].
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun verifyCode(codeToken: String, code: String): VerifyResult {
        val r = auth.verifyCode(codeToken, code)
        if (r is VerifyResult.LoggedIn) loginWithToken(r.loginToken)
        return r
    }

    /** Answers the 2FA challenge (`AUTH_LOGIN_CHECK_PASSWORD` 115) and logs in. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun checkPassword(trackId: String, password: String): LoginResult =
        loginWithToken(auth.checkPassword(trackId, password).loginToken)

    /** Registers the unknown number (`AUTH_CONFIRM` 23) and logs in. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun register(registerToken: String, firstName: String, lastName: String? = null): LoginResult =
        loginWithToken(auth.confirmRegistration(registerToken, firstName, lastName).token)

    /**
     * Logs in with [token] (`LOGIN` 19) on the current connection (connecting first if needed),
     * saves it, and keeps using it for every reconnect.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loginWithToken(token: String): LoginResult {
        val login = TokenLogin(token, device, config.fingerprint)
        lifecycle.withLock { tokenLogin.value = login }
        val handshake = session.connect()
        if (login.result.value == null) {
            // connect() found an existing session without a token: log in on it now
            login.hook(session.transport, handshake)
            onLoggedIn(login)
        }
        return login.result.value ?: throw IllegalStateException("LOGIN finished without a result")
    }

    /**
     * Approves a web / desktop QR login from this account. As in Komet, the approval is preceded by
     * `PING` 1 `{interactive: true}` and `SESSIONS_INFO` 96 and a [qrApproveDelayMs] pause; the
     * server rejects `AUTH_QR_APPROVE` 290 `{qrLink}` without them.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun approveQrLogin(qrLink: String): QrApproval {
        val link = qrLink.trim()
        require(link.isNotEmpty()) { "qrLink is blank" }
        session.request(Opcode.PING, linkedMapOf("interactive" to true))
        api.users.getSessions()
        delay(qrApproveDelayMs)
        return auth.approveQrLogin(link)
    }

    /** Pause before `AUTH_QR_APPROVE` in [approveQrLogin]; tests set it to 0. */
    internal var qrApproveDelayMs: Long = 300

    /**
     * Logs out on the server (`LOGOUT` 20, best effort), clears the token and the local state,
     * and disconnects. The device identity is kept. Gap fill is cancelled and the event router
     * is stopped before the snapshot is dropped, then the router is started again so this client
     * can log in once more. Safe to call from an [EventRouter] handler: the local cleanup always
     * completes (the handler's own coroutine is cancelled by the router stop).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun logout() {
        runCatching { if (loggedInFlag.value) auth.logout() }
        // Mandatory cleanup: it also runs when logout() is called from an event handler, whose
        // coroutine router.stop() cancels, and when the caller is cancelled half-way.
        withContext(NonCancellable) {
            val gap = lifecycle.withLock {
                sessionEpoch += 1
                accountGen += 1
                tokenLogin.value = null
                credentials.clearToken()
                loggedInFlag.value = false
                loggedIn.value = null
                val running = gapJob
                gapJob = null
                running?.cancel()
                running
            }
            gap?.cancelAndJoin()
            router.stop()
            lifecycle.withLock {
                store.clear()
                snapshotLoaded = false
                _accountConfig.value = null
            }
            session.disconnect()
            router.start(scope)
        }
    }

    /** Disconnects (the token stays stored); [start] reconnects. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun disconnect() {
        session.disconnect()
        loggedInFlag.value = false
    }

    private suspend fun onLoggedIn(login: TokenLogin) {
        val r = login.result.value ?: return
        val fillEpoch = lifecycle.withLock {
            if (tokenLogin.value !== login) return@withLock null
            sessionEpoch += 1
            val epoch = sessionEpoch
            val relogin = loginCount.value > 0
            loginCount.value += 1
            store.applyLogin(r)
            snapshotLoaded = true
            AccountConfig.fromLoginReply(r.raw)?.let { _accountConfig.value = it }
            login.login2Result.value?.let { r2 -> r2.contacts.mapNotNull(com.max.core.api.MaxUser::from).let(store::putContacts) }
            // r already carries the LOGIN2 profile; a reconnect without a profile keeps this login's id
            val uid = r.userId ?: loggedIn.value.takeIf { lastLogin === login }
            lastLogin = login
            credentials.save(StoredCredentials(device.deviceId, device.instanceId, login.token, uid, login.sync))
            loggedIn.value = uid
            loggedInFlag.value = true
            if (relogin && config.fillGapsOnReconnect) epoch else null
        }
        if (fillEpoch != null) scheduleGapFill(fillEpoch)
    }

    /** Publishes [fillGapsAt] only while [epoch] is still the current session. */
    private suspend fun scheduleGapFill(epoch: Int) {
        val job = scope.launch(start = CoroutineStart.LAZY) { fillGapsAt(epoch) }
        lifecycle.withLock {
            if (sessionEpoch != epoch || tokenLogin.value == null) {
                job.cancel()
            } else {
                gapJob?.cancel()
                gapJob = job
                job.start()
            }
        }
    }

    // ---- store-backed helpers -------------------------------------------------------------------

    /**
     * Pages backward through every chat in `MaxState.historyGaps` ([MaxClientConfig.gapFillCount]
     * messages at a time, up to [MaxClientConfig.gapFillPageLimit] pages). A hole closes when a
     * page contains its anchor or the server returns an empty page. A short page, a repeated
     * oldest id, or the page cap leaves the hole open. Failures are skipped per chat; returns
     * the chats whose holes closed.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun fillGaps(): List<Long> {
        val epoch = lifecycle.withLock { sessionEpoch }
        return fillGapsAt(epoch)
    }

    private suspend fun fillGapsAt(epoch: Int): List<Long> {
        val closed = ArrayList<Long>()
        for (chatId in store.state.value.historyGaps()) {
            if (fillChatGap(chatId, epoch)) closed += chatId
        }
        return closed
    }

    private suspend fun fillChatGap(chatId: Long, epoch: Int): Boolean {
        var from: Long? = null
        var previousOldest: Long? = null
        repeat(config.gapFillPageLimit.coerceAtLeast(1)) {
            if (!gapStillOpen(chatId, epoch)) {
                return lifecycle.withLock { sessionOpen(epoch) && chatId !in store.state.value.historyGaps() }
            }
            val history = try {
                api.messages.getChatHistory(chatId, from = from, backward = config.gapFillCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false
            }
            if (history.messages.isEmpty()) {
                return lifecycle.withLock {
                    if (!sessionOpen(epoch)) false
                    else {
                        store.closeHistoryGap(chatId)
                        true
                    }
                }
            }
            val oldest = history.messages.minWith(compareBy({ it.time }, { it.id }))
            if (oldest.id == previousOldest) return false
            previousOldest = oldest.id
            val committed = lifecycle.withLock {
                if (!sessionOpen(epoch)) false
                else {
                    store.putHistory(chatId, history)
                    true
                }
            }
            if (!committed) return false
            if (chatId !in store.state.value.historyGaps()) return true
            from = oldest.time
        }
        return false
    }

    private suspend fun gapStillOpen(chatId: Long, epoch: Int): Boolean = lifecycle.withLock {
        sessionOpen(epoch) && chatId in store.state.value.historyGaps()
    }

    /** Caller holds [lifecycle]. */
    private fun sessionOpen(epoch: Int): Boolean = sessionEpoch == epoch && tokenLogin.value != null

    /** The account session an operation starts in: the current [TokenLogin] and [accountGen]. */
    private class Ticket(val login: TokenLogin?, val gen: Int)

    private suspend fun ticket(): Ticket = lifecycle.withLock { Ticket(tokenLogin.value, accountGen) }

    /**
     * Runs [mutation] under [lifecycle] only while [ticket] is still the current account session.
     * A reply that arrives after logout, token rejection, [close] or a switch to another login
     * must not touch [store] or the credentials of the new session: the operation fails with
     * [SessionClosedException] instead.
     */
    private suspend fun <T> commit(ticket: Ticket, mutation: () -> T): T = lifecycle.withLock {
        if (ticket.gen != accountGen || ticket.login !== tokenLogin.value) {
            throw SessionClosedException("the account session ended before the reply was applied")
        }
        mutation()
    }

    /**
     * One page of the chat list (`CHATS_LIST`) into [store]. Like every store-backed helper below,
     * it fails with [SessionClosedException] (and changes nothing) when the account session ended
     * while the request was in flight.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadChats(marker: Long? = null): List<Chat> {
        val t = ticket()
        val chats = api.chats.fetchChats(marker)
        commit(t) { store.putChats(chats) }
        return chats
    }

    /**
     * The whole chat list: the `LOGIN` reply carries only the first chats and a `chatMarker`, the
     * rest comes in `CHATS_LIST` pages of [ChatsApi.CHATS_PAGE_SIZE] (KometTeam/Komet
     * `paginateChats`). Stops on an empty or short page, a repeated marker or [maxPages]. Without
     * a `chatMarker` it is one [loadChats]. Returns every chat now in [store].
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadAllChats(maxPages: Int = 200): List<Chat> {
        val start = lifecycle.withLock { lastLogin?.result?.value?.raw?.get("chatMarker").asMarker() }
        if (start == null || start <= 0) {
            loadChats()
            return store.state.value.chats.values.toList()
        }
        var marker: Long = start
        var pages = 0
        while (pages < maxPages) {
            pages += 1
            val t = ticket()
            val page = api.chats.fetchChatsPage(marker)
            commit(t) { store.putChats(page.chats) }
            val next = page.nextMarker
            if (page.chats.size < ChatsApi.CHATS_PAGE_SIZE || next == null || next == marker) break
            marker = next
        }
        return store.state.value.chats.values.toList()
    }

    /**
     * The full folder list (`FOLDERS_GET` 272, `{folderSync: 0}`) into [store]; it carries the
     * pinned chats (`favorites` of the "all chats" folder, see [ChatFolders]). The `LOGIN` config
     * usually has the same list already; this is the explicit resync.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadFolders(): ChatFolders {
        val t = ticket()
        val list = api.account.getFolders(0)
        commit(t) { store.putFolders(list) }
        return ChatFolders.from(list)
    }

    /**
     * Sets the pinned chats of the chat list to [chatIds], top first (duplicates dropped). Pinning,
     * unpinning and reordering are all this call with the whole new list: `FOLDERS_UPDATE` 274 on
     * the "all chats" folder with the new `favorites` ([com.max.core.api.AccountApi.setFolderFavorites]).
     * The folder comes from [store]; without one the list is fetched first ([loadFolders]), and if
     * the server has no such folder the call fails with an [IllegalStateException] ("not found").
     * [store] changes only after the server accepted the request, so a failure leaves the previous
     * pins in place. Returns the pinned ids now in [store].
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setPinnedChats(chatIds: List<Long>): List<Long> {
        val wanted = chatIds.distinct()
        val folder = store.state.value.chatFolders?.allChats
            ?: loadFolders().allChats
            ?: throw IllegalStateException("all-chats folder not found")
        val t = ticket()
        val update = api.account.setFolderFavorites(folder, wanted)
        commit(t) { store.putPinnedUpdate(update, wanted) }
        return store.state.value.pinnedChatIds ?: wanted
    }

    /** A history page (`CHAT_HISTORY`, the latest [backward] messages before [from]) into [store]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadHistory(chatId: Long, from: Long? = null, backward: Int = 40): ChatHistory {
        val t = ticket()
        val history = api.messages.getChatHistory(chatId, from = from, backward = backward)
        commit(t) { store.putHistory(chatId, history) }
        return history
    }

    /** Users by id (`CONTACT_INFO`) into [store]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadUsers(userIds: List<Long>): List<MaxUser> {
        val t = ticket()
        val users = api.users.getUsers(userIds)
        commit(t) { store.putUsers(users) }
        return users
    }

    /**
     * Closes every other session (`SESSIONS_CLOSE` 97). The server issues a new token for this
     * session; it replaces the stored one and is used for the next reconnects (PyMax
     * `close_all_sessions`). Returns `false` if the reply carried no token.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun closeOtherSessions(): Boolean {
        val t = ticket()
        val token = api.account.closeOtherSessions() ?: return false
        commit(t) {
            t.login?.replaceToken(token)
            saveCredentials(token = token)
        }
        return true
    }

    /**
     * Changes privacy settings (`CONFIG` 22) and stores the returned config hash in the sync
     * markers sent with the next `LOGIN` (PyMax `change_profile_settings`).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun updatePrivacy(settings: PrivacySettings): String? {
        val t = ticket()
        val hash = api.account.updatePrivacy(settings) ?: return null
        commit(t) {
            val login = t.login
            login?.updateSync { it.copy(configHash = hash) }
            saveCredentials(sync = login?.sync ?: (credentials.load() ?: stored).sync.copy(configHash = hash))
        }
        return hash
    }

    /** Changes the own profile (`PROFILE` 16) and updates the own user in [store]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun updateProfile(firstName: String, lastName: String? = null, description: String? = null, photoToken: String? = null): Profile {
        val t = ticket()
        val profile = api.account.updateProfile(firstName, lastName, description, photoToken)
        commit(t) { store.putUsers(listOf(profile.contact)) }
        return profile
    }

    /**
     * Changes user settings (`CONFIG` 22 with [values], see [com.max.core.api.AccountApi.updateUserSettings]).
     * The reply's `user` replaces [accountConfig]'s and its hash goes to the sync markers.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun updateUserSettings(values: Map<String, Any?>): AccountConfig {
        val t = ticket()
        val update = api.account.updateUserSettings(values)
        return commit(t) {
            // without `user` in the reply the server still accepted these values
            val user = update.user ?: ((_accountConfig.value?.user ?: emptyMap()) + values)
            val next = (_accountConfig.value ?: AccountConfig()).withUser(user, update.hash)
            _accountConfig.value = next
            update.hash?.let { hash ->
                val login = t.login
                login?.updateSync { it.copy(configHash = hash) }
                saveCredentials(sync = login?.sync ?: (credentials.load() ?: stored).sync.copy(configHash = hash))
            }
            next
        }
    }

    /**
     * Mutes [chatId] for good or turns its sound back on (`CONFIG` 22, `dontDisturbUntil` `-1` /
     * `0`). [accountConfig] gets the new value, so [AccountConfig.isMuted] reflects it at once.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setChatMuted(chatId: Long, muted: Boolean) {
        val t = ticket()
        val until = if (muted) -1L else 0L
        val hash = api.account.setChatMute(chatId, until)
        commit(t) {
            val base = _accountConfig.value ?: AccountConfig()
            _accountConfig.value = base.withChatMute(chatId, until).let { if (hash != null) it.copy(hash = hash) else it }
        }
    }

    /** The own user from [store], or `CONTACT_INFO` for it when missing; `null` before login. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadMe(): MaxUser? {
        val me = userId.value ?: return null
        return store.state.value.users[me] ?: loadUsers(listOf(me)).firstOrNull { it.id == me }
    }

    /**
     * Uploads [bytes] (JPEG / PNG) as the new avatar: `PHOTO_UPLOAD` 80 with `profile: true`, the
     * HTTP upload, then `PROFILE` 16 `{photoToken, avatarType}` without the name. The new own
     * contact goes to [store].
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun uploadAvatar(bytes: ByteArray, fileName: String = "avatar.jpg"): Profile {
        val t = ticket()
        val photo = media.uploadPhoto(bytes, fileName, profile = true)
        val profile = api.account.setAvatar(photo.photoToken)
        commit(t) { store.putUsers(listOf(profile.contact)) }
        return profile
    }

    /** Removes the current avatar (`REMOVE_CONTACT_PHOTO` 43); `null` when there is none. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun removeAvatar(): Profile? {
        val photoId = loadMe()?.photoId?.takeIf { it > 0 } ?: return null
        val t = ticket()
        val profile = api.account.removePhoto(photoId)
        commit(t) { store.putUsers(listOf(profile.contact)) }
        return profile
    }

    /** The whole contact list (opcode 8 `{contactsSync: 0}`) into [store]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun syncContacts(): List<MaxUser> {
        val t = ticket()
        val contacts = api.users.syncContacts()
        commit(t) { store.putContacts(contacts) }
        return contacts
    }

    /** Active sessions (`SESSIONS_INFO` 96). */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadSessions(): List<com.max.core.api.SessionInfo> = api.users.getSessions()

    /** Creates a folder ([com.max.core.api.AccountApi.addFolder]) and merges it into [store]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun createFolder(title: String, chatIds: List<Long> = emptyList(), filters: List<Any?> = emptyList()): Folder? {
        val t = ticket()
        val update = api.account.addFolder(title, chatIds, filters)
        commit(t) { store.putFolderUpdate(update) }
        return update.folder
    }

    /**
     * Changes the folder [folderId] from [store] ([com.max.core.api.AccountApi.editFolder]: `null`
     * arguments and the pinned chats stay as the server sent them).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun editFolder(folderId: String, title: String? = null, chatIds: List<Long>? = null, filters: List<Any?>? = null): Folder? {
        val folder = folderOf(folderId)
        val t = ticket()
        val update = api.account.editFolder(folder, title, chatIds, filters)
        commit(t) { store.putFolderUpdate(update) }
        return update.folder
    }

    /** Deletes folders (`FOLDERS_DELETE` 276) and drops them from [store]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun deleteFolders(folderIds: List<String>) {
        val t = ticket()
        val update = api.account.deleteFolders(folderIds)
        commit(t) { store.removeFolders(folderIds, update) }
    }

    /** Sets the folder order (`FOLDERS_REORDER` 275, the whole list) and applies it to [store]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun reorderFolders(order: List<String>) {
        val t = ticket()
        val update = api.account.reorderFolders(order)
        commit(t) { store.reorderFolders(order, update) }
    }

    private suspend fun folderOf(folderId: String): Folder =
        store.state.value.chatFolders?.folders?.firstOrNull { it.id == folderId }
            ?: loadFolders().folders.firstOrNull { it.id == folderId }
            ?: throw IllegalStateException("folder $folderId not found")

    private fun saveCredentials(token: String? = null, sync: SyncState? = null) {
        val current = credentials.load() ?: stored
        credentials.save(
            StoredCredentials(
                device.deviceId, device.instanceId, token ?: current.token, loggedIn.value ?: current.userId, sync ?: current.sync,
            ),
        )
    }

    /**
     * Sends a text message and adds the server's copy to [store] (own messages are not pushed
     * back): it becomes the chat's last message and moves the chat up the list; unread stays.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendText(chatId: Long, text: String, replyTo: Long? = null): MaxMessage {
        val t = ticket()
        val message = api.messages.sendMessage(chatId, text, replyTo)
        commit(t) { store.putSentMessage(chatId, message) }
        return message
    }

    /**
     * Uploads [items] in order ([MediaApi.uploadAll], [progress] over the whole batch) and sends
     * them as one message with [text] as its caption ([sendAttachments]). Cancelling the caller
     * stops the upload; nothing is sent then.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendMedia(
        chatId: Long,
        items: List<OutgoingMedia>,
        text: String? = null,
        replyTo: Long? = null,
        progress: UploadProgress? = null,
    ): MaxMessage {
        require(items.isNotEmpty()) { "nothing to send" }
        val attachments = media.uploadAll(items, progress)
        return sendAttachments(chatId, attachments, text, replyTo)
    }

    /**
     * Sends ready [attachments] (`MSG_SEND` 64 `attaches`) with an optional caption, and adds the
     * server's copy to [store] like [sendText]. While the server still processes an upload
     * (`attachment.not.ready`) the frame is sent again once a second, up to
     * [ATTACHMENT_SEND_ATTEMPTS] times.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendAttachments(
        chatId: Long,
        attachments: List<OutgoingAttachment>,
        text: String? = null,
        replyTo: Long? = null,
    ): MaxMessage {
        val t = ticket()
        val message = media.sendMessage(
            chatId,
            attachments,
            text?.takeIf { it.isNotBlank() },
            replyTo,
            notReadyAttempts = ATTACHMENT_SEND_ATTEMPTS,
        )
        commit(t) { store.putSentMessage(chatId, message) }
        return message
    }

    /** Sends the card of MAX user [contactId] (`{_type: CONTACT, contactId}`), see [sendAttachments]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendContact(chatId: Long, contactId: Long, replyTo: Long? = null): MaxMessage =
        sendAttachments(chatId, listOf(OutgoingAttachment.Contact(contactId)), null, replyTo)

    // ---- Reactions ------------------------------------------------------------------------------

    /**
     * Sets this account's reaction on a message to [reaction], or removes it when [reaction] is
     * `null` (`MSG_REACTION` 178 / `MSG_CANCEL_REACTION` 179). A comment of channel post [postId]
     * uses the same requests with `postId`. One account has at most one reaction per message: a
     * new one replaces the previous.
     *
     * Returns the reactions the server sent back, or `null` when the reply had none. For a
     * message in [store] they are stored; a removal without a reply drops only the own counter.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setReaction(chatId: Long, messageId: Long, reaction: String?, postId: Long? = null): ReactionInfo? {
        val t = ticket()
        val info = when {
            reaction != null && postId != null -> api.messages.addCommentReaction(chatId, postId, messageId, reaction)
            reaction != null -> api.messages.addReaction(chatId, messageId, reaction)
            postId != null -> api.messages.removeCommentReaction(chatId, postId, messageId)
            else -> api.messages.removeReaction(chatId, messageId)
        }
        if (postId == null) {
            commit(t) {
                val stored = store.state.value.messages[chatId]?.firstOrNull { it.id == messageId }?.reactionInfo
                when {
                    info != null -> store.putReactions(chatId, messageId, info)
                    reaction == null && stored != null -> store.putReactions(chatId, messageId, stored.withoutOwn())
                }
            }
        }
        return info
    }

    /**
     * Current reactions of several messages (`MSG_GET_REACTIONS` 180), keyed by message id; ids
     * the server left out are missing. Messages in [store] get the new values.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadReactions(chatId: Long, messageIds: List<Long>): Map<Long, ReactionInfo> {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val t = ticket()
        val found = api.messages.getReactions(chatId, ids).orEmpty()
            .mapNotNull { (key, info) -> key.toLongOrNull()?.let { it to info } }
            .toMap()
        commit(t) { found.forEach { (id, info) -> store.putReactions(chatId, id, info) } }
        return found
    }

    /** Speech to text of a voice message or video note (`AUDIO_TRANSCRIPTION` 202). */
    @Throws(CancellationException::class, Exception::class)
    suspend fun transcribe(chatId: Long, messageId: Long, mediaId: Long): Transcription =
        api.messages.transcribe(chatId, messageId, mediaId)

    /**
     * Who reacted to a message (`MSG_GET_DETAILED_REACTIONS` 181), with the users missing from
     * [store] fetched (`CONTACT_INFO`) so the caller can name them.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadReactionUsers(chatId: Long, messageId: Long, count: Int = 100): List<ReactionUser> {
        val users = api.messages.getDetailedReactions(chatId, messageId, count)
        val unknown = users.map { it.userId }.distinct().filter { it !in store.state.value.users }
        if (unknown.isNotEmpty()) {
            try {
                loadUsers(unknown)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Names are optional: the list is shown with ids.
            }
        }
        return users
    }

    private val catalogLock = Mutex()
    private var catalog: List<Animoji>? = null

    /**
     * Emoji the server offers for reactions ([com.max.core.api.AssetsApi.reactionCatalog]), in
     * catalog order. Asked once per client; an empty or failed answer is asked again next time.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun reactionCatalog(): List<Animoji> = catalogLock.withLock {
        catalog?.let { return@withLock it }
        val loaded = api.assets.reactionCatalog()
        if (loaded.isNotEmpty()) catalog = loaded
        loaded
    }

    // ---- Session (raw access) ------------------------------------------------------------------

    @Throws(CancellationException::class, Exception::class)
    override suspend fun connect(): SessionInfo = SessionInfo.from(session.connect())

    @Throws(CancellationException::class, Exception::class)
    override suspend fun request(opcode: Int, payload: ByteArray): ByteArray {
        require(opcode in 0..0xFFFF) { "opcode out of range: $opcode" }
        val body = if (payload.isEmpty()) null else DefaultMessagePackCodec.decode(payload)
        val reply = session.transport.request(opcode, body)
        return reply.payload?.let { DefaultMessagePackCodec.encode(it) } ?: ByteArray(0)
    }

    override val pushes: Flow<Push> = session.pushes.map { p ->
        Push(p.opcode, p.cmd, p.payload?.let { DefaultMessagePackCodec.encode(it) } ?: ByteArray(0))
    }

    /** Stops everything: disconnects and, if the client created its own scope, cancels it. */
    @Throws(CancellationException::class, Exception::class)
    override suspend fun close() {
        // NonCancellable: close() may run inside an event handler, which router.stop() cancels
        withContext(NonCancellable) {
            val gap = lifecycle.withLock {
                sessionEpoch += 1
                accountGen += 1
                val running = gapJob
                gapJob = null
                running?.cancel()
                running
            }
            gap?.cancelAndJoin()
            router.stop()
            session.disconnect()
            loggedInFlag.value = false
        }
        if (ownsScope) scope.cancel()
    }

    // ---- callback bridges (Swift / Java) ---------------------------------------------------------

    fun watchState(onEach: (ClientState) -> Unit): Watcher = state.watch(scope, onEach = onEach)
    fun watchEvents(onEach: (MaxEvent) -> Unit): Watcher = events.all.watch(scope, onEach = onEach)
    fun watchStore(onEach: (MaxState) -> Unit): Watcher = store.state.watch(scope, onEach = onEach)

    /** [accountConfig] whenever it changes; `null` while unknown. */
    fun watchAccountConfig(onEach: (AccountConfig?) -> Unit): Watcher = accountConfig.watch(scope, onEach = onEach)

    /** [MaxState.chatFolders] whenever they change; `null` while unknown. */
    fun watchFolders(onEach: (ChatFolders?) -> Unit): Watcher = store.chatFolders.watch(scope, onEach = onEach)

    /** Pinned chat ids ([MaxStore.pinnedChats]) whenever they change; `null` while unknown. */
    fun watchPinnedChats(onEach: (List<Long>?) -> Unit): Watcher = store.pinnedChats.watch(scope, onEach = onEach)
}

/** Drops exceptions that escape a coroutine of a client-owned scope (on iOS they would abort the app). */
private val swallowUncaught = CoroutineExceptionHandler { _, _ -> }

/** `chatMarker` of a `LOGIN` reply: a number, sometimes a decimal string. */
private fun Any?.asMarker(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull()
    else -> null
}

/**
 * `MSG_SEND` attempts of [MaxClient.sendAttachments] while the server answers
 * `attachment.not.ready`, a second apart (Komet waits up to 30 s for a video).
 */
const val ATTACHMENT_SEND_ATTEMPTS: Int = 30
