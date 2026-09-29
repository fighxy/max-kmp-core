package com.max.shared

import com.max.core.MaxError
import com.max.core.api.Chat
import com.max.core.api.ChatHistory
import com.max.core.api.MaxApi
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.PrivacySettings
import com.max.core.api.Profile
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
import com.max.core.media.defaultMediaHttp
import com.max.core.protocol.DefaultMessagePackCodec
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val lifecycle = Mutex()
    /** Bumped on login, logout and token rejection so an in-flight gap fill cannot write afterwards. */
    private var sessionEpoch = 0
    /** Bumped on logout, token rejection and close; with the [TokenLogin] identity it names an account session. */
    private var accountGen = 0
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

    /** Uploads, download links, messages with attachments. */
    val media: MediaApi = MediaApi(session, mediaHttp ?: defaultMediaHttp(config.media))

    /** High-level state. */
    val state: StateFlow<ClientState> = combine(session.state, loggedInFlag) { s, logged -> map(s, logged) }
        .stateIn(this.scope, SharingStarted.Eagerly, ClientState.Idle)

    /** Own user id once logged in. */
    val userId: StateFlow<Long?> get() = loggedIn

    /** `true` when a login token is stored. */
    val hasStoredToken: Boolean get() = credentials.load()?.token != null

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

    /** Approves a web QR login from this account (`AUTH_QR_APPROVE` 290). */
    @Throws(CancellationException::class, Exception::class)
    suspend fun approveQrLogin(qrLink: String): QrApproval = auth.approveQrLogin(qrLink)

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
            login.login2Result.value?.let { r2 -> r2.contacts.mapNotNull(com.max.core.api.MaxUser::from).let(store::putUsers) }
            val uid = r.userId ?: loggedIn.value
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

    private fun saveCredentials(token: String? = null, sync: SyncState? = null) {
        val current = credentials.load() ?: stored
        credentials.save(
            StoredCredentials(
                device.deviceId, device.instanceId, token ?: current.token, loggedIn.value ?: current.userId, sync ?: current.sync,
            ),
        )
    }

    /** Sends a text message and adds the server's copy to [store] (own messages are not pushed back). */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendText(chatId: Long, text: String, replyTo: Long? = null): MaxMessage {
        val t = ticket()
        val message = api.messages.sendMessage(chatId, text, replyTo)
        commit(t) { store.putMessages(chatId, listOf(message)) }
        return message
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
}

/** Drops exceptions that escape a coroutine of a client-owned scope (on iOS they would abort the app). */
private val swallowUncaught = CoroutineExceptionHandler { _, _ -> }
