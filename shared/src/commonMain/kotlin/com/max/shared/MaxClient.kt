package com.max.shared

import com.max.core.MaxError
import com.max.core.api.AccountConfig
import com.max.core.api.AccountConfigUpdate
import com.max.core.api.Chat
import com.max.core.api.ChatFolders
import com.max.core.api.ChatHistory
import com.max.core.api.ChatMembersResult
import com.max.core.api.ChatMemberEntry
import com.max.core.api.ChatRoles
import com.max.core.api.ContactByPhone
import com.max.core.api.DeleteResult
import com.max.core.api.ChatMemberRole
import com.max.core.api.ChatRights
import com.max.core.api.DeletePlan
import com.max.core.api.DeleteScope
import com.max.core.api.Drafts
import com.max.core.api.MessageDeletion
import com.max.core.api.MaxDraft
import com.max.core.api.MemberListType
import com.max.core.api.ChatsApi
import com.max.core.api.Folder
import com.max.core.api.FolderUpdate
import com.max.core.api.ForwardBatch
import com.max.core.api.HistoryItemType
import com.max.core.api.MaxApi
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.MessageReader
import com.max.core.api.MessageReaders
import com.max.core.api.PhoneContact
import com.max.core.api.PresenceInfo
import com.max.core.api.PresenceStatus
import com.max.core.api.TextElement
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
import com.max.core.auth.DEFAULT_CONFIG_HASH
import com.max.core.auth.InvalidTokenException
import com.max.core.auth.LoginResult
import com.max.core.auth.QrApproval
import com.max.core.auth.SyncState
import com.max.core.auth.TokenLogin
import com.max.core.auth.VerifyResult
import com.max.core.events.DiagnosticLog
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * @property presenceSweepIntervalMs how often "online" entries older than the server's
 *   `presence-ttl` are degraded in [MaxClient.store] ([MaxClient.expirePresence]); `0` or less
 *   turns the periodic sweep off (reads through [MaxClient.presenceOf] still apply the TTL).
 * @property refreshPresenceOnLogin after a `LOGIN` of the same account, users that were "online"
 *   but are not in the reply's `presence` (a delta) are degraded and asked again with
 *   `CONTACT_PRESENCE` 35 in the background ([MaxClient.loadPresence], best effort).
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
    val presenceSweepIntervalMs: Long = 15_000,
    val refreshPresenceOnLogin: Boolean = true,
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
    /**
     * `true` once [accountConfig] holds a config the server sent (`LOGIN` / `LOGIN2`); only then a
     * `CONFIG` / `NOTIF_CONFIG` hash may become the next `LOGIN` `configHash`. Guarded by [lifecycle].
     */
    private var serverConfigLoaded = false
    private var gapJob: Job? = null
    private var presenceJob: Job? = null

    /** `interactive` for PING and `LOGIN` ([setInteractive]); the user is looking at the app. */
    @kotlin.concurrent.Volatile
    private var interactiveFlag: Boolean = config.transport.pingInteractive

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

    /**
     * How many `LOGIN` replies this client has applied to [store] (the first login and every
     * re-login after a reconnect). Each reply already carries the changed chats, so a caller can
     * tell that the stored list is fresh without asking `CHATS_LIST` again.
     */
    val logins: StateFlow<Int> get() = loginCount

    /** `true` when a login token is stored. */
    val hasStoredToken: Boolean get() = credentials.load()?.token != null

    /**
     * The account configuration ([AccountConfig]: `config.user` settings, `config.server`
     * parameters, `config.chats` mutes) of the last `LOGIN` that carried one, updated by
     * [updateUserSettings], [setChatMuted] and the `NOTIF_CONFIG` 134 push
     * ([MaxEvent.ConfigUpdated]); `null` before the first login and after logout. The first `LOGIN`
     * of each process sends empty sync markers (default `configHash`), so the server always sends
     * the whole config then; a reconnect whose reply leaves it out keeps the known one, and a
     * partial one is merged (a missing `chats` / `user` / `server` keeps its value, `chats` is
     * merged per chat id).
     */
    val accountConfig: StateFlow<AccountConfig?> = _accountConfig.asStateFlow()

    private val _appliedEvents = MutableSharedFlow<MaxEvent>(extraBufferCapacity = APPLIED_EVENTS_BUFFER)

    /**
     * Every push, in order, emitted only once it has been applied: [store] already holds it (the
     * router's first stage) and a `NOTIF_CONFIG` 134 ([MaxEvent.ConfigUpdated]) is already merged
     * into [accountConfig]. Covers all [MaxEvent]s the router sees, among them presence 132,
     * drafts 152 / 153, contacts 131 and config 134; a collector can read the new state right
     * away without racing the merge. The state it reads may already contain later pushes too
     * (the store runs ahead of this stream), never less than the event. Hot, no replay, lossless while a collector keeps up: a
     * collector more than [APPLIED_EVENTS_BUFFER] events behind delays only the router's handler
     * stage (handlers registered after the client's own), never [store].
     */
    val appliedEvents: SharedFlow<MaxEvent> = _appliedEvents.asSharedFlow()

    /**
     * Called with a description and the error when a background step of the client fails without
     * a caller to tell: today the automatic `DRAFT_DISCARD` after a send. Must not throw.
     */
    @kotlin.concurrent.Volatile
    var onBackgroundError: ((String, Throwable) -> Unit)? = null

    /**
     * Diagnostics lines for the app's log, today one per push `NOTIF_DRAFT` 152 /
     * `NOTIF_DRAFT_DISCARD` 153 (parsed or not), with the raw payload redacted by
     * [com.max.core.events.DiagnosticLog.draftPush]: keys, ids, times and types stay, a draft
     * `text` keeps only its length and first 2 characters, a line is at most
     * [com.max.core.events.DiagnosticLog.MAX_ENTRY_CHARS] characters. Called on the event
     * handler stage after [store] applied the push; must not throw (an exception is dropped).
     */
    @kotlin.concurrent.Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    init {
        // registered before any caller's handler, so those already see the merged config
        router.on<MaxEvent.ConfigUpdated> { applyConfigPush(it) }
        // after the config merge and the store (stage 1): the "applied" stream
        router.on<MaxEvent> { e ->
            val log = onDiagnostic
            if (log != null && DiagnosticLog.isDraftPush(e)) runCatching { log(DiagnosticLog.draftPush(e)) }
        }
        router.on<MaxEvent> { _appliedEvents.emit(it) }
        router.start(this.scope)
        if (config.presenceSweepIntervalMs > 0) {
            presenceJob = this.scope.launch {
                while (true) {
                    delay(config.presenceSweepIntervalMs)
                    expirePresence()
                }
            }
        }
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
     * When the first connect fails with auto-reconnect on, the session keeps retrying in the
     * background (kolibri's supervisor) and this returns [ClientState.Reconnecting] with the
     * error; [state] reaches [ClientState.Ready] (or [ClientState.AwaitingAuth]) once a retry
     * gets through. Other failures throw.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun start(): ClientState {
        lifecycle.withLock {
            val c = credentials.load() ?: stored
            if (tokenLogin.value == null && c.token != null) {
                // [store] is not persisted: saved markers describe a snapshot this process does not
                // have, and a LOGIN with them would return only the delta. Ask for everything instead.
                val sync = if (snapshotLoaded) c.sync else SyncState()
                tokenLogin.value = TokenLogin(c.token, device, config.fingerprint, sync, interactiveFlag)
            }
        }
        try {
            session.connect()
        } catch (e: InvalidTokenException) {
            rejectToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (session.state.value !is SessionState.Reconnecting) throw e
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
        val login = TokenLogin(token, device, config.fingerprint, interactive = interactiveFlag)
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
                serverConfigLoaded = false
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
        var stale: List<Long> = emptyList()
        val fillEpoch = lifecycle.withLock {
            if (tokenLogin.value !== login) return@withLock null
            sessionEpoch += 1
            val epoch = sessionEpoch
            val relogin = loginCount.value > 0
            loginCount.value += 1
            val sameAccount = r.userId == null || loggedIn.value == null || r.userId == loggedIn.value
            // config first: an observer of the store never sees the new chats with the old (or no) mutes
            applyLoginConfig(login, r.raw, login.login2Result.value?.raw, sameAccount)
            // "online" users the reply does not refresh are degraded by applyLogin; ask them again
            val before = store.state.value
            val refreshed = com.max.core.api.Presences.parseMap(r.raw["presence"])?.keys.orEmpty()
            if (sameAccount) {
                stale = before.presence.filter { (id, p) -> p.status == PresenceStatus.ONLINE && id !in refreshed }.keys.toList()
            }
            store.applyLogin(r)
            // the reply's presence is in the store now: the next LOGIN may ask only for the delta
            login.presenceApplied(r)
            snapshotLoaded = true
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
        if (stale.isNotEmpty() && config.refreshPresenceOnLogin) {
            scope.launch {
                try {
                    loadPresence(stale)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // best effort: they stay offline until a push or the next request
                }
            }
        }
    }

    /**
     * The `config` of a `LOGIN` reply (and of the `LOGIN2` that followed it) into [accountConfig].
     * Caller holds [lifecycle].
     *
     * - Another account: nothing of the previous config is kept.
     * - `LOGIN` sent with the default `configHash`: a full snapshot ([AccountConfig.replacedBy];
     *   a carried `chats` makes [AccountConfig.chatsKnown] `true`).
     * - Otherwise (a reconnect with the last hash): only what changed ([AccountConfig.mergedWith]).
     *
     * In every case a section the reply leaves out (`chats`, `user`, `server`) keeps its value, so
     * a reconnect never drops the known chat mutes. `LOGIN2`'s config is merged on top.
     */
    private fun applyLoginConfig(login: TokenLogin, reply: Map<*, *>, login2: Map<*, *>?, sameAccount: Boolean) {
        val update = AccountConfigUpdate.fromLoginReply(reply)
        val update2 = login2?.let { AccountConfigUpdate.fromLoginReply(it) }
        val prev = _accountConfig.value?.takeIf { sameAccount }
        val full = login.sentSync?.configHash == DEFAULT_CONFIG_HASH
        var next = when {
            update == null -> prev
            full -> (prev ?: AccountConfig()).replacedBy(update)
            else -> (prev ?: AccountConfig()).mergedWith(update)
        }
        if (update2 != null) next = (next ?: AccountConfig()).mergedWith(update2)
        if (!sameAccount) serverConfigLoaded = false
        if (update != null || update2 != null) serverConfigLoaded = true
        if (!sameAccount || next != null) _accountConfig.value = next
    }

    /**
     * A `NOTIF_CONFIG` 134 push into [accountConfig] ([AccountConfig.mergedWith]); its hash goes to
     * the sync markers of the next `LOGIN`, as after `CONFIG` 22. Ignored while no account is
     * logged in. Before any server config is known the push still lands on an empty config that
     * knows only the chats it names ([AccountConfig.chatsKnown] `false`), and its hash is not kept:
     * the next `LOGIN` must still ask for the whole config.
     */
    private suspend fun applyConfigPush(event: MaxEvent.ConfigUpdated) {
        lifecycle.withLock {
            val login = tokenLogin.value ?: return@withLock
            if (lastLogin !== login) return@withLock
            _accountConfig.value = (_accountConfig.value ?: AccountConfig()).mergedWith(event.update)
            if (serverConfigLoaded) event.update.hash?.let { hash -> storeConfigHash(login, hash) }
        }
    }

    /** [hash] as the `configHash` of [login]'s next `LOGIN` and in the saved markers. Caller holds [lifecycle]. */
    private fun storeConfigHash(login: TokenLogin?, hash: Any) {
        login?.updateSync { it.copy(configHash = hash) }
        saveCredentials(sync = login?.sync ?: (credentials.load() ?: stored).sync.copy(configHash = hash))
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
     * One page of the members of group/channel [chatId] (`CHAT_MEMBERS` 59, `{type: "MEMBER",
     * chatId, marker, count}`; start with [marker] `0` and pass [ChatMembersResult.nextMarker]
     * until it is `null`). Each member carries its role: owner and admins come from the chat
     * (`owner`, `admins`, `adminParticipants`, [ChatRoles]); for the first page of a chat missing
     * from [store] the chat is asked first (`CHAT_INFO`, best effort: without it every member is
     * a plain member). The member profiles and their presence go into [store].
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadChatMembers(chatId: Long, marker: Long = 0, count: Int = 50): ChatMembersResult {
        require(marker >= 0 && count > 0) { "bad page: marker=$marker count=$count" }
        if (marker == 0L && chatId !in store.state.value.chats) {
            try {
                val t = ticket()
                val chat = api.chats.getChat(chatId)
                commit(t) { store.putChats(listOf(chat)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Roles are optional: the page is still shown.
            }
        }
        val t = ticket()
        val page = api.chats.getChatMembers(chatId, marker, count)
        val result = ChatMembersResult.of(page, marker, ChatRoles.of(store.state.value.chats[chatId]))
        commit(t) { putMembers(result.members) }
        return result
    }

    /**
     * Searches the members of group/channel [chatId] by [query] (`CHAT_MEMBERS` 59, `{chatId, type,
     * query}`; [type] is a [MemberListType] constant). The found profiles and their presence go
     * into [store]; the roles come from [ChatRoles] of the stored chat.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun searchChatMembers(chatId: Long, query: String, type: String = MemberListType.MEMBER): List<ChatMemberEntry> {
        val t = ticket()
        val roles = ChatRoles.of(store.state.value.chats[chatId])
        val found = api.chats.searchChatMembers(chatId, query, type).map { ChatMemberEntry.of(it, roles) }
        commit(t) { putMembers(found) }
        return found
    }

    private fun putMembers(members: List<ChatMemberEntry>) {
        store.putUsers(members.mapNotNull { it.user })
        val fresh = LinkedHashMap<Long, PresenceInfo>()
        val stored = store.state.value.presence
        for (m in members) {
            val id = m.userId ?: continue
            val p = m.member.presenceInfo ?: continue
            // A live NOTIF_PRESENCE may be newer than the page.
            val known = stored[id]?.seen
            if (known != null && (p.seen == null || p.seen!! <= known)) continue
            fresh[id] = p
        }
        store.putPresence(fresh)
    }

    // ---- Presence -------------------------------------------------------------------------------

    /**
     * How long a presence is trusted without a refresh, in ms: `presence-ttl` of the server config
     * ([AccountConfig.presenceTtlSeconds]), 300 s while the config is unknown.
     */
    val presenceTtlMs: Long
        get() = (_accountConfig.value?.presenceTtlSeconds ?: AccountConfig.DEFAULT_PRESENCE_TTL_S) * 1000

    /**
     * Presence of [userId] now ([MaxState.presenceAt] with [presenceTtlMs]): an "online" that was
     * not refreshed within the TTL reads as offline with the last known time. `null` when unknown.
     */
    fun presenceOf(userId: Long): PresenceInfo? = store.presenceAt(userId, presenceTtlMs)

    /** [PresenceStatus] code of [presenceOf]: `-1` unknown, `0` offline, `1` online, `2` recently, `3` long ago. */
    fun presenceStatusOf(userId: Long): Int = PresenceStatus.of(presenceOf(userId))

    /**
     * Degrades in [store] every "online" not refreshed within [presenceTtlMs] (offline, `seen` =
     * the last time it was known online). The client runs it every
     * [MaxClientConfig.presenceSweepIntervalMs]; returns the users that changed.
     */
    fun expirePresence(): List<Long> = store.expirePresence(presenceTtlMs)

    /**
     * Asks the presence of [userIds] (`CONTACT_PRESENCE` 35 `{contactIds}`, batches of 100,
     * [com.max.core.api.UsersApi.getPresence]) and puts it into [store] (refresh time now, a
     * missing `seen` keeps the stored time). An id the server leaves out is
     * [PresenceStatus.LONG_AGO]. Duplicates are dropped; an empty list sends nothing.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadPresence(userIds: List<Long>): Map<Long, PresenceInfo> {
        val ids = userIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val t = ticket()
        val presence = api.users.getPresence(ids)
        commit(t) { store.putPresence(presence) }
        return presence
    }

    /** Whether the app is in the foreground ([setInteractive]); starts as [TransportConfig.pingInteractive]. */
    val isInteractive: Boolean get() = interactiveFlag

    /**
     * Tells the server whether the user is looking at the app: `true` in the foreground, `false`
     * in the background. Every later `PING` 1 carries `{interactive}` (the MAX web client sends
     * "not idle", PyMax `set_presence`, kolibri `set_ping_interactive`), and so does the `LOGIN`
     * of the next reconnect. When the flag changes on a live connection one `PING` goes out at
     * once (as kolibri does), so the server need not wait for the next 30 s tick. The references
     * have no explicit "going offline" request: after `interactive: false` the server decides the
     * presence by itself. Returns `true` when that immediate `PING` was written; never throws
     * for a send error.
     */
    @Throws(CancellationException::class)
    suspend fun setInteractive(interactive: Boolean): Boolean {
        interactiveFlag = interactive
        tokenLogin.value?.interactive = interactive
        return session.transport.setPingInteractive(interactive)
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
        commit(t) { storeConfigHash(t.login, hash) }
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
            val prev = _accountConfig.value
            val user = update.user ?: ((prev?.user ?: emptyMap()) + values)
            // without a server config the chats stay unknown (chatsKnown false) and the hash is not kept
            val next = (prev ?: AccountConfig()).withUser(user, update.hash)
            _accountConfig.value = next
            if (serverConfigLoaded) update.hash?.let { hash -> storeConfigHash(t.login, hash) }
            next
        }
    }

    /**
     * Mutes [chatId] for good or turns its sound back on (`CONFIG` 22, `dontDisturbUntil` `-1` /
     * `0`). [accountConfig] gets the new value, so [AccountConfig.isMuted] reflects it at once.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setChatMuted(chatId: Long, muted: Boolean) {
        setChatMuteUntil(chatId, if (muted) -1L else 0L)
    }

    /**
     * Sets [chatId]'s `dontDisturbUntil` (`CONFIG` 22): `0` sound on, `-1` muted for good, else
     * muted until that time (Unix ms). [accountConfig] gets the new value, and the reply's hash
     * becomes the `configHash` of the next `LOGIN`, as for the other `CONFIG` changes. Without a
     * server config yet, the new config knows only this chat ([AccountConfig.chatsKnown] `false`,
     * every other chat stays unknown) and the hash is not kept.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setChatMuteUntil(chatId: Long, until: Long) {
        require(until >= -1L) { "bad dontDisturbUntil: $until" }
        val t = ticket()
        val hash = api.account.setChatMute(chatId, until)
        commit(t) {
            val prev = _accountConfig.value
            // without a server config only this chat becomes known (chatsKnown stays false)
            val base = prev ?: AccountConfig()
            _accountConfig.value = base.withChatMute(chatId, until).let { if (hash != null) it.copy(hash = hash) else it }
            // with no server config held, the next LOGIN must still ask for all of it
            if (hash != null && serverConfigLoaded) storeConfigHash(t.login, hash)
        }
    }

    /**
     * Whether [chatId] is muted now ([AccountConfig.chatMuteState] on [accountConfig], device
     * clock): `true` muted (for good or until a time still ahead), `false` sound on (`0`, a timed
     * mute that ran out, or no entry in a full `chats` section), `null` unknown (no config before
     * the first login or after logout, or a config that does not know this chat). Do not store
     * `null` as `false`: keep what was shown before.
     */
    fun isChatMuted(chatId: Long): Boolean? = _accountConfig.value?.chatMuteState(chatId)

    /**
     * [chatId]'s raw `dontDisturbUntil` ([AccountConfig.chatMuteUntil]: `0` sound on, `-1` muted for
     * good, else the end of the mute in ms); `null` while unknown.
     */
    fun chatMuteUntil(chatId: Long): Long? = _accountConfig.value?.chatMuteUntil(chatId)

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

    /**
     * Renames contact [userId] for this account (`CONTACT_UPDATE` 34, `{contactId, action:
     * "UPDATE", firstName, lastName}`; a blank [lastName] is sent as `null`, each name at most
     * 64 characters). An empty [firstName] is allowed (web client): with a last name the server
     * uses the person's own first name, with both empty the original names come back. The
     * renamed contact goes into [store]; its name is the `CUSTOM` entry ([MaxState.displayName]).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun renameContact(userId: Long, firstName: String, lastName: String? = null): MaxUser {
        val t = ticket()
        val user = api.users.renameContact(userId, firstName, lastName)
        commit(t) { store.putContacts(listOf(user)) }
        return user
    }

    /**
     * Removes contact [userId] (`CONTACT_UPDATE` 34, `{contactId, action: "REMOVE"}`). [store]
     * drops it from the contact list and forgets its `CUSTOM` name; the user stays known (with
     * the contact of the reply when there is one). Undo is [addContactByPhone] or `ADD`.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun removeContact(userId: Long): MaxUser? {
        val t = ticket()
        val reply = api.users.removeContact(userId)
        commit(t) { store.removeContact(userId, reply) }
        return reply
    }

    /**
     * Adds a contact by phone number (`CONTACT_ADD_BY_PHONE` 41, `{phone, firstName?, lastName?}`
     * -> `{contact, new}`). The contact goes into [store]. [ContactByPhone.isNew] says whether it
     * was not a contact before.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun addContactByPhone(phone: String, firstName: String? = null, lastName: String? = null): ContactByPhone {
        val t = ticket()
        val added = api.users.addContactByPhone(phone, firstName, lastName)
        commit(t) { store.putContacts(listOf(added.user)) }
        return added
    }

    /**
     * Replaces the device address book kept in [store] (no request, nothing goes to the server):
     * entries are matched to users by normalized phone ([com.max.core.api.PhoneNumbers]) for
     * [MaxState.displayName]; for a number listed twice the first non-empty name wins. It
     * survives a switch to another account and is dropped by [logout].
     */
    fun setAddressBook(entries: List<PhoneContact>) = store.setAddressBook(entries)

    /** Sets (or with `null` / blank clears) the local address-book name of [userId] (no request). */
    fun setLocalName(userId: Long, name: String?) = store.setLocalName(userId, name)

    /**
     * The name rule of [displayName] ([MaxState.preferAddressBookNames]): `true` (default) the
     * address book wins over the contact name this account set, `false` the contact name wins.
     * A device setting kept in [store]; it survives logout and account switches.
     */
    var preferAddressBookNames: Boolean
        get() = store.state.value.preferAddressBookNames
        set(value) = store.setPreferAddressBookNames(value)

    /**
     * The name to show for [userId]: address book, else the own contact name (`CUSTOM`), else the
     * own profile name (`ONEME`), else the first `names` entry, else the phone; `null` when
     * nothing is known ([displayLabel] falls back to "Участник"). With [preferAddressBookNames]
     * `false` the contact name comes before the address book.
     */
    fun displayName(userId: Long): String? = store.state.value.displayName(userId)

    /** [displayName] or "Участник". */
    fun displayLabel(userId: Long): String = store.state.value.displayLabel(userId)

    /** Server drafts kept in [store] by chat id (from `LOGIN`, [saveDraft] and the pushes 152 / 153). */
    val drafts: Map<Long, MaxDraft> get() = store.state.value.drafts

    /**
     * Discard marks by chat id: the server time (ms) of the latest known discard of the chat's
     * draft ([com.max.core.state.MaxState.draftDiscards]). A mark stays until a strictly later
     * draft replaces it; a chat has a draft in [drafts] or a mark here, never both.
     */
    val draftDiscards: Map<Long, Long> get() = store.state.value.draftDiscards

    /** The discard mark of [chatId] ([draftDiscards]), or `null`. */
    fun draftDiscardedAt(chatId: Long): Long? = store.state.value.draftDiscardedAt(chatId)

    /**
     * What the composer of [chatId] should show given the app's own unsent [local] draft
     * ([Drafts.reconcile]: empty counts as none, the later draft wins with [local] kept on an equal
     * time, a discard at the winner's time or later clears it). `null`: an empty composer.
     */
    fun reconcileDraft(chatId: Long, local: MaxDraft?): MaxDraft? {
        val state = store.state.value
        return Drafts.reconcile(local, state.draftOf(chatId), state.draftDiscardedAt(chatId))
    }

    /**
     * Saves the draft of [chatId] on the server (`DRAFT_SAVE` 176). Dialogs and Saved Messages are
     * addressed by the peer's `userId`, other chats by `chatId` ([Drafts.address]). The server
     * time of the reply becomes the draft's `updateTime`; the draft goes into [store].
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun saveDraft(chatId: Long, text: String, elements: List<TextElement> = emptyList(), replyTo: Long? = null): MaxDraft {
        val state = store.state.value
        val address = Drafts.address(chatId, state.chats[chatId], state.me)
        val kept = elements.filter { it.fits(text.length) }
        val t = ticket()
        val time = api.drafts.saveDraft(address, text, kept, replyTo)
        val draft = MaxDraft(chatId, text, kept, replyTo, time)
        commit(t) { store.putDraft(draft) }
        return draft
    }

    /**
     * Discards the draft of [chatId] (`DRAFT_DISCARD` 177, `{chatId | userId, time}`). [time]
     * defaults to the stored draft's `updateTime`; without either nothing is sent and `false` is
     * returned. [store] drops the draft; after a sent discard the chat gets a discard mark at
     * that time ([draftDiscardedAt]).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun discardDraft(chatId: Long, time: Long? = null): Boolean {
        val state = store.state.value
        val at = time ?: state.draftOf(chatId)?.updateTime ?: run {
            store.removeDraft(chatId)
            return false
        }
        val t = ticket()
        api.drafts.discardDraft(Drafts.address(chatId, state.chats[chatId], state.me), at)
        commit(t) { store.removeDraft(chatId, at) }
        return true
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
     * A server draft of [chatId] in [store] is cleared and discarded on the server afterwards
     * (once; a failed `DRAFT_DISCARD` goes to [onBackgroundError], the send still succeeds).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendText(chatId: Long, text: String, replyTo: Long? = null, elements: List<Map<String, Any?>> = emptyList()): MaxMessage =
        sendTextImpl(chatId, text, replyTo, elements, discardDraft = true)

    private suspend fun sendTextImpl(chatId: Long, text: String, replyTo: Long?, elements: List<Map<String, Any?>>, discardDraft: Boolean): MaxMessage {
        val t = ticket()
        val message = api.messages.sendMessage(chatId, text, replyTo, elements = elements)
        val draft = commit(t) {
            store.putSentMessage(chatId, message)
            if (discardDraft) store.takeDraft(chatId) else null
        }
        draft?.let { discardSentDraft(t, it) }
        return message
    }

    /**
     * After a successful send into a chat with a stored draft (MAX web client: the draft is sent,
     * then discarded on the server): the draft was already taken out of [store] atomically
     * ([MaxStore.takeDraft], so of two sends only one gets it); here `DRAFT_DISCARD` 177 goes out
     * with its time, in the background. A failure goes to [onBackgroundError], never to the
     * sender: the server then keeps the draft until it is replaced or discarded elsewhere.
     */
    private fun discardSentDraft(t: Ticket, draft: MaxDraft) {
        val state = store.state.value
        val address = Drafts.address(draft.chatId, state.chats[draft.chatId], state.me)
        scope.launch {
            try {
                if (t.gen != lifecycle.withLock { accountGen }) return@launch
                api.drafts.discardDraft(address, draft.updateTime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { onBackgroundError?.invoke("DRAFT_DISCARD after send to ${draft.chatId} failed", e) }
            }
        }
    }

    /**
     * Sends [text] with formatting [elements] ([TextElement]: bold, italic, underline,
     * strikethrough, monospace, heading, quote, link, mention, animoji). Offsets are UTF-16
     * indexes into [text]; an element outside [text] is dropped. Like [sendText] otherwise.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendFormattedText(chatId: Long, text: String, elements: List<TextElement>, replyTo: Long? = null): MaxMessage =
        sendText(chatId, text, replyTo, TextElement.payloadFor(text, elements))

    /**
     * Replaces the text and formatting of own message [messageId] (`MSG_EDIT` 67, `{chatId,
     * messageId, text, elements, attachments}`). An empty [elements] clears the formatting. The
     * edited copy goes into [store] keeping its reactions (the reply has none).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun editText(chatId: Long, messageId: Long, text: String, elements: List<TextElement> = emptyList()): MaxMessage {
        val t = ticket()
        val edited = api.messages.editMessage(chatId, messageId, text, TextElement.payloadFor(text, elements))
        commit(t) { store.putEditedMessage(chatId, edited) }
        return store.state.value.messagesOf(chatId).firstOrNull { it.id == edited.id } ?: edited
    }

    /**
     * Deletes the selected messages of [chatId] in one `MSG_DELETE` 66 (`{chatId, postId?,
     * messageIds, forMe}`, plus `itemType` when set). [forMe] `true` removes them only for this
     * account. [store] drops the ids the server deleted ([DeleteResult.deleted]); the ids of
     * `failedMessageIds` ([DeleteResult.failed]) stay. Other devices get push 142.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun deleteMessages(
        chatId: Long,
        messageIds: List<Long>,
        forMe: Boolean = false,
        itemType: HistoryItemType? = null,
        postId: Long? = null,
    ): DeleteResult {
        val ids = messageIds.distinct()
        val t = ticket()
        val result = api.messages.deleteMessages(chatId, ids, forMe, itemType, postId)
        commit(t) {
            if (result.deleted.isNotEmpty()) {
                store.apply(MaxEvent.MessagesDeleted(chatId, result.deleted, null, null, false, Opcode.MSG_DELETE.value, result.raw))
            }
        }
        return result
    }

    /**
     * Forwards the selected messages of [fromChatId] to [toChatId], one `MSG_SEND` with a
     * `FORWARD` link per message (the protocol has no batch form). Messages known to [store] are
     * sent oldest first (by `time`, equal times by id), the rest keep their place after them in
     * the given order. An optional [comment] is sent first as a plain text message, trimmed (a
     * blank one is skipped); it leaves the target's draft alone. The first failure stops the rest
     * ([ForwardBatch.failedIndex]); the messages sent so far go into [store] either way.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun forwardMessages(
        toChatId: Long,
        fromChatId: Long,
        messageIds: List<Long>,
        notify: Boolean = true,
        comment: String? = null,
    ): ForwardBatch {
        require(messageIds.isNotEmpty()) { "messageIds must not be empty" }
        val times = store.state.value.messagesOf(fromChatId).associate { it.id to it.time }
        val ordered = com.max.core.api.ForwardOrder.of(messageIds, times)
        val note = comment?.trim().orEmpty()
        if (note.isNotEmpty()) sendTextImpl(toChatId, note, null, emptyList(), discardDraft = false)
        val t = ticket()
        val batch = api.messages.forwardMessages(toChatId, fromChatId, ordered, notify)
        commit(t) { batch.sent.forEach { store.putSentMessage(toChatId, it) } }
        return batch
    }

    /**
     * Tells [chatId] that this account is typing (`MSG_TYPING` 65, `{chatId, type, postId?}`;
     * [com.max.core.api.MessagesApi.sendTyping]). Fire-and-forget: no reply is awaited and send
     * errors are swallowed; the result says whether the frame was written. [type] is a
     * [com.max.core.api.TypingType] constant (`TEXT`, `AUDIO`, `VIDEO_MSG`, `PHOTO`, `VIDEO`,
     * `FILE`, `STICKER`); any other string is sent as given. [postId] marks typing a comment under
     * that channel post. No throttling: every call sends a frame, so call it at most once per 6 s
     * per chat while the user is busy. The store does not change.
     */
    @Throws(CancellationException::class)
    suspend fun sendTyping(chatId: Long, type: String, postId: Long? = null): Boolean =
        api.messages.sendTyping(chatId, type, postId)

    /**
     * Marks [chatId] unread from [mark] (message time, ms) and stores the read boundary one
     * millisecond earlier, so that message stays unread. Returns the server's unread count.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun markUnread(chatId: Long, mark: Long): Int {
        val t = ticket()
        val state = api.messages.markUnread(chatId, mark)
        val me = store.state.value.me
        if (me != null) {
            val boundary = if (mark > 0) mark - 1 else mark
            commit(t) {
                store.apply(MaxEvent.MessageRead(chatId, me, boundary, true, Opcode.CHAT_MARK.value, null))
            }
        }
        return state.unread
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
     * [ATTACHMENT_SEND_ATTEMPTS] times. Clears the chat's draft like [sendText] ([sendContact] and
     * [sendSticker] leave it).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendAttachments(
        chatId: Long,
        attachments: List<OutgoingAttachment>,
        text: String? = null,
        replyTo: Long? = null,
    ): MaxMessage = sendAttachmentsImpl(chatId, attachments, text, replyTo, discardDraft = true)

    private suspend fun sendAttachmentsImpl(
        chatId: Long,
        attachments: List<OutgoingAttachment>,
        text: String?,
        replyTo: Long?,
        discardDraft: Boolean,
    ): MaxMessage {
        val t = ticket()
        val message = media.sendMessage(
            chatId,
            attachments,
            text?.takeIf { it.isNotBlank() },
            replyTo,
            notReadyAttempts = ATTACHMENT_SEND_ATTEMPTS,
        )
        val draft = commit(t) {
            store.putSentMessage(chatId, message)
            if (discardDraft) store.takeDraft(chatId) else null
        }
        draft?.let { discardSentDraft(t, it) }
        return message
    }

    /** Sends the card of MAX user [contactId] (`{_type: CONTACT, contactId}`), see [sendAttachments]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendContact(chatId: Long, contactId: Long, replyTo: Long? = null): MaxMessage =
        sendAttachmentsImpl(chatId, listOf(OutgoingAttachment.Contact(contactId)), null, replyTo, discardDraft = false)

    /** Sends sticker [stickerId] of the server catalog (`{_type: STICKER, stickerId}`), see [sendAttachments]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun sendSticker(chatId: Long, stickerId: Long, replyTo: Long? = null): MaxMessage =
        sendAttachmentsImpl(chatId, listOf(OutgoingAttachment.Sticker(stickerId)), null, replyTo, discardDraft = false)

    // ---- Stickers -------------------------------------------------------------------------------

    /** Sticker sets and recent stickers ([com.max.core.api.AssetsApi.stickerSections]). */
    @Throws(CancellationException::class, Exception::class)
    suspend fun stickerSections(): com.max.core.api.StickerSections = api.assets.stickerSections()

    @Throws(CancellationException::class, Exception::class)
    suspend fun stickerSets(ids: List<Long>): List<com.max.core.api.StickerSet> = api.assets.stickerSets(ids)

    @Throws(CancellationException::class, Exception::class)
    suspend fun stickers(ids: List<Long>): List<com.max.core.api.StickerItem> = api.assets.stickers(ids)

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

    // ---- Readers ---------------------------------------------------------------------------------

    /**
     * `edit-timeout` of the server config in seconds ([AccountConfig.editTimeoutSeconds]): how long
     * an own message may be edited and deleted for everyone; `0` while the config is unknown or
     * has none (as the MAX web client).
     */
    val editTimeoutSeconds: Long get() = _accountConfig.value?.editTimeoutSeconds ?: 0L

    /**
     * The own rights in [chatId] ([ChatRights.of] from the stored chat: `owner`, `admins`,
     * `adminParticipants`); [ChatRights.NONE] for a dialog, Saved Messages or an unknown chat.
     */
    fun chatRights(chatId: Long): ChatRights {
        val state = store.state.value
        return ChatRights.of(state.chats[chatId], state.me)
    }

    /** The own role in [chatId] ([chatRights]). */
    fun myRole(chatId: Long): ChatMemberRole = chatRights(chatId).role

    /** The own admin permission bits in [chatId] as the server sent them, `null` when not an admin or unknown ([chatRights]). */
    fun myPermissions(chatId: Long): Int? = chatRights(chatId).permissions

    /**
     * The delete dialog for [messageIds] of [chatId] ([MessageDeletion.plan]: Saved Messages only
     * for this account; own messages younger than [editTimeoutSeconds] for everyone; others' in
     * groups and channels for everyone only with the right to delete any message; nothing in a
     * channel without it). An id the store does not hold counts as an unsent message.
     */
    fun deletePlan(chatId: Long, messageIds: List<Long>): DeletePlan =
        MessageDeletion.plan(store.state.value, chatId, messageIds, editTimeoutSeconds)

    /** Whether [messageId] of [chatId] may be deleted for everyone ([deletePlan], [DeleteScope.ALL]). */
    fun canDeleteForEveryone(chatId: Long, messageId: Long): Boolean =
        deletePlan(chatId, listOf(messageId)).scopes[messageId] == DeleteScope.ALL

    /** `max-readmarks` of the server config ([AccountConfig.maxReadmarks]); 100 while the config is unknown. */
    private fun maxReadmarks(): Int = _accountConfig.value?.maxReadmarks ?: AccountConfig.DEFAULT_MAX_READMARKS

    /**
     * Whether [chatId] shows who read its messages ([MessageReaders.isAvailable] with the server's
     * `max-readmarks`), judged from the chat in [store]; `false` for a chat not in [store]. No
     * request: an app can use it to show or hide the action. [loadMessageReaders] decides again
     * with fresh chat info.
     */
    fun isMessageReadersAvailable(chatId: Long): Boolean {
        val chat = store.state.value.chats[chatId] ?: return false
        return MessageReaders.isAvailable(chat, maxReadmarks())
    }

    /**
     * Who read [messageId] in group [chatId] ([com.max.core.api.ReadersApi.loadMessageReaders]):
     * users who reacted first (with the emoji), then members whose read mark reaches the message,
     * latest first; without this account and the message author. Empty for a chat that does not
     * show readers (dialogs, channels, groups above `max-readmarks`, a running group call).
     *
     * Every call asks `CHAT_INFO` so the marks are fresh, and the chat goes into [store]. The
     * server marks are merged with the `NOTIF_MARK` marks of [store] (the later wins). The message
     * time and author come from [store] (stored messages or the chat's last message), else from
     * `MSG_GET`. Reactions (`MSG_GET_DETAILED_REACTIONS` 181) are best effort: when they fail,
     * only the readers are returned. Users missing from [store] are fetched (`CONTACT_INFO`, best
     * effort) so the caller can name them.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun loadMessageReaders(chatId: Long, messageId: Long): List<MessageReader> {
        val t = ticket()
        val state = store.state.value
        val known = state.messagesOf(chatId).firstOrNull { it.id == messageId }
            ?: state.chats[chatId]?.lastMessage?.takeIf { it.id == messageId }
        val result = api.readers.loadMessageReaders(
            chatId,
            messageId,
            me = state.me ?: userId.value,
            maxReadmarks = maxReadmarks(),
            liveMarks = state.readMarks[chatId].orEmpty(),
            message = known,
        )
        commit(t) { store.putChats(listOf(result.chat)) }
        val unknown = result.readers.map { it.userId }.filter { it !in store.state.value.users }
        if (unknown.isNotEmpty()) {
            try {
                unknown.chunked(READER_USERS_PAGE).forEach { loadUsers(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Names are optional: the list is shown with ids.
            }
        }
        return result.readers
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
            presenceJob?.cancel()
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

/** Events [MaxClient.appliedEvents] buffers for a slow collector before the handler stage waits. */
const val APPLIED_EVENTS_BUFFER: Int = 1024

/** Users per `CONTACT_INFO` request when [MaxClient.loadMessageReaders] names the readers. */
private const val READER_USERS_PAGE = 100
