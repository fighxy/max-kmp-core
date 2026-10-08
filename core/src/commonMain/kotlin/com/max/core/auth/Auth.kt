package com.max.core.auth

import com.max.core.protocol.Opcode
import com.max.core.session.DeviceInfo
import com.max.core.session.FatalSessionError
import com.max.core.session.HandshakeInfo
import com.max.core.session.HandshakePayload
import com.max.core.session.SessionMachine
import com.max.core.session.SessionState
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportPacket

/*
 * Phone-code authentication and stored-token login.
 *
 * Opcodes and payloads (docs/protocol.md; references):
 * - `AUTH_REQUEST` (17): `{phone, type, language?, mode?}` — kolibri
 *   `examples/auth_request.rs` and `kolibri-py/examples/call_bot.py` send
 *   `phone, type = START_AUTH, language = "ru", mode`; PyMax `RequestCodePayload` sends
 *   `phone, type, mode` (no `language`). `mode` is the 96-byte [ApkFingerprint] (mobile only;
 *   PyMax omits it for `deviceType = WEB`). Reply (PyMax `StartAuthResponse`): `token`,
 *   `codeLength`, `requestMaxDuration`, `requestCountLeft`, `altActionDuration`.
 * - `AUTH` (18): `{token, verifyCode, authTokenType = CHECK_CODE}` — identical in both. Reply
 *   (PyMax `CheckCodeResponse`): `tokenAttrs.LOGIN.token`, or `tokenAttrs.REGISTER.token` for an
 *   unknown number, or `passwordChallenge {trackId, hint}` when a 2FA password is set.
 * - `LOGIN` (19): PyMax `SyncPayload` (mobile) / `WebSyncPayload` (web), see [AuthApi.loginPayload].
 *   kolibri's session does not log in; its `call_bot.py` example sends a reduced map
 *   (`token, interactive, exp{chatsCountGroups = 0B 32}, chatCacheFingerprint, presenceSync,
 *   chatsSync`). PyMax, whose shape is followed here, sends `exp.chatsCountGroups = 0A 32`.
 *   An ERROR reply with `FAIL_LOGIN_TOKEN` / `FAIL_LOGOUT_ALL` (PyMax
 *   `_is_invalid_login_token_error`; kolibri maps `FAIL_LOGIN_TOKEN` to `SessionExpired`) becomes
 *   [InvalidTokenException].
 * - `AUTH_QR_APPROVE` (290): `{qrLink}` — PyMax `ApproveQrLoginPayload` /
 *   `AuthService.authorize_qr_login`; kolibri only defines the opcode constant. See
 *   [AuthApi.approveQrLogin].
 *
 * - `AUTH_LOGIN_CHECK_PASSWORD` (115): `{trackId, password}` — PyMax
 *   `CheckPasswordChallengePayload` / `check_password`; reply `tokenAttrs.LOGIN.token` or `error`
 *   (PyMax `CheckPasswordResponse`). See [AuthApi.checkPassword].
 * - `AUTH_CONFIRM` (23): `{firstName, lastName?, token, tokenType = REGISTER}` — PyMax
 *   `ConfirmRegistrationPayload`; reply `{token, profile, tokenType, userToken}`. See
 *   [AuthApi.confirmRegistration].
 * - `LOGOUT` (20): `{}` (PyMax `SelfService.logout`), see [AuthApi.logout].
 * - opcode 8 (PyMax `LOGIN2`, kolibri `CONTACTS_GET`, protocol.md K11): `{needProfile,
 *   contactsSync, configHash}` (PyMax `Login2Payload`), sent by PyMax right after `LOGIN` when
 *   `login2Flags` has any flag set. See [AuthApi.login2]; kolibri has no call site.
 */

/** Minimal request interface [AuthApi] needs; [SessionMachine] and `MaxTransport` both fit. */
fun interface RequestSink {
    /**
     * Sends [opcode] with [payload] and returns the OK reply.
     *
     * @throws ServerErrorException for an ERROR reply (and other transport exceptions).
     */
    suspend fun request(opcode: Opcode, payload: Any?): TransportPacket

    /**
     * Sends [opcode] with [payload] without waiting for a reply (fire-and-forget, e.g.
     * `MSG_TYPING` 65). Sinks over a socket send the frame and return once it is written
     * ([SessionMachine.sendWithoutReply], `MaxTransport.sendRequest`). The default, for sinks
     * that can only do request/response, sends through [request] and drops the reply.
     *
     * @throws Exception when the frame could not be sent (not connected, write failure); the
     *   default also throws what [request] throws.
     */
    suspend fun sendWithoutReply(opcode: Opcode, payload: Any?) {
        request(opcode, payload)
    }
}

/** This session as a [RequestSink]: [SessionMachine.request], and [SessionMachine.sendWithoutReply] for fire-and-forget frames. */
fun SessionMachine.asRequestSink(): RequestSink {
    val session = this
    return object : RequestSink {
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket = session.request(opcode, payload)
        override suspend fun sendWithoutReply(opcode: Opcode, payload: Any?) {
            session.sendWithoutReply(opcode, payload)
        }
    }
}

/** Base class of auth errors. */
open class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The stored login token was rejected (`FAIL_LOGIN_TOKEN`, `FAIL_LOGOUT_ALL`): the user must
 * authenticate again. A [FatalSessionError], so a session does not keep reconnecting with it.
 */
class InvalidTokenException(val serverError: ServerErrorException) :
    AuthException("login token rejected: ${serverError.rawMessage ?: serverError.errorKey ?: serverError.message}", serverError),
    FatalSessionError

/** `type` of `AUTH_REQUEST` (PyMax `AuthType`). */
enum class CodeRequestType { START_AUTH, RESEND }

/** Parsed `AUTH_REQUEST` reply. [raw] is the whole reply map. */
data class CodeRequest(
    val token: String,
    val codeLength: Int?,
    val requestMaxDuration: Long?,
    val requestCountLeft: Int?,
    val altActionDuration: Long?,
    val raw: Map<*, *>,
)

/** Outcome of [AuthApi.verifyCode]. */
sealed interface VerifyResult {
    val raw: Map<*, *>

    /** Code accepted: [loginToken] is `tokenAttrs.LOGIN.token`; profile and user id if the reply has them. */
    data class LoggedIn(val loginToken: String, val userId: Long?, val profile: Map<*, *>?, override val raw: Map<*, *>) : VerifyResult

    /** A 2FA password is set (`passwordChallenge`); continue with [AuthApi.checkPassword] (opcode 115). */
    data class PasswordRequired(val trackId: String, val hint: String?, override val raw: Map<*, *>) : VerifyResult

    /** Unknown number: `tokenAttrs.REGISTER.token` for [AuthApi.confirmRegistration] (`AUTH_CONFIRM` 23). */
    data class RegistrationRequired(val registerToken: String, override val raw: Map<*, *>) : VerifyResult
}

/** The 2FA password was rejected: the `error` of an OK `AUTH_LOGIN_CHECK_PASSWORD` (115) reply. */
class WrongPasswordException(val error: String, val raw: Map<*, *>) : AuthException("password rejected: $error")

/** Parsed `AUTH_CONFIRM` (23) reply (PyMax `ConfirmRegistrationResponse`). [token] is the login token. */
data class Registration(val token: String, val userId: Long?, val profile: Map<*, *>?, val raw: Map<*, *>)

/**
 * `login2Flags` of a `LOGIN` reply (PyMax `Login2Flags`: `configEnabled`, `contactEnabled`,
 * `profileEnabled`, all default `false`).
 */
data class Login2Flags(val configEnabled: Boolean, val contactEnabled: Boolean, val profileEnabled: Boolean) {
    /** PyMax `Login2Flags.enabled`: any flag set. */
    val enabled: Boolean get() = configEnabled || contactEnabled || profileEnabled

    companion object {
        fun from(value: Any?): Login2Flags? {
            val m = value as? Map<*, *> ?: return null
            return Login2Flags(m["configEnabled"] == true, m["contactEnabled"] == true, m["profileEnabled"] == true)
        }
    }
}

/**
 * Parsed opcode-8 (`LOGIN2`) reply (PyMax `Login2Response`): `profile`, `contactInfos`, `config`.
 */
data class Login2Result(val profile: Map<*, *>?, val contacts: List<Any?>, val configHash: Any?, val raw: Map<*, *>) {
    companion object {
        fun from(payload: Any?): Login2Result {
            val map = payload as? Map<*, *> ?: emptyMap<Any?, Any?>()
            return Login2Result(
                profile = map["profile"] as? Map<*, *>,
                // PyMax names the list `contactInfos`, KometTeam/Komet reads `contacts`.
                contacts = map["contactInfos"] as? List<Any?> ?: map["contacts"] as? List<Any?> ?: emptyList(),
                configHash = configHashOf(map),
                raw = map,
            )
        }
    }
}

/**
 * Accepted `AUTH_QR_APPROVE` (290) request. The OK reply has no documented fields (PyMax only
 * checks that it is not an error), so [raw] keeps whatever the server sent (empty for no payload).
 */
data class QrApproval(val qrLink: String, val raw: Map<*, *>)

/** Default PyMax `configHash` (no cached config). */
const val DEFAULT_CONFIG_HASH: String =
    "00000000-0000000000000000-00000000-0000000000000000-0000000000000000-0-0000000000000000-00000000"

/**
 * Sync markers sent with `LOGIN` (PyMax `SyncState`); `-1` means "send everything".
 * [configHash] is a `String` or a `Long` (PyMax `ConfigHash = str | int`).
 */
data class SyncState(
    val chatsSync: Long = -1,
    val contactsSync: Long = -1,
    val draftsSync: Long = -1,
    val presenceSync: Long = -1,
    val configHash: Any = DEFAULT_CONFIG_HASH,
) {
    /** Markers after a login (PyMax `LoginResponse.update_sync_state`): all syncs = `time`, hash = `config.hash`. */
    fun updatedBy(result: LoginResult): SyncState {
        val t = result.time
        return SyncState(
            chatsSync = t ?: chatsSync,
            contactsSync = t ?: contactsSync,
            draftsSync = t ?: draftsSync,
            presenceSync = t ?: presenceSync,
            configHash = result.configHash ?: configHash,
        )
    }

    /** Markers after `LOGIN2` (PyMax `Login2Response.update_sync_state`): only the config hash changes. */
    fun updatedBy(result: Login2Result): SyncState = copy(configHash = result.configHash ?: configHash)
}

/**
 * Parsed `LOGIN` reply (PyMax `LoginResponse`); [raw] keeps everything (`messages`, `contacts`, ...).
 *
 * @property token a refreshed login token, if the server sent one; store it.
 * @property chats the `chats` list as decoded maps.
 */
data class LoginResult(
    val profile: Map<*, *>?,
    val userId: Long?,
    val chats: List<Any?>,
    val token: String?,
    val time: Long?,
    val configHash: Any?,
    val login2Flags: Map<*, *>?,
    val raw: Map<*, *>,
) {
    /** [login2Flags] parsed; `null` when the reply has none. */
    val login2: Login2Flags? get() = Login2Flags.from(login2Flags)

    /**
     * This reply with the profile of the `LOGIN2` reply [login2] (sent after this `LOGIN`) merged
     * in: its fields win over the ones of this reply, and [userId] comes from the merged
     * `profile.contact.id`. Unchanged when [login2] is `null` or has no profile.
     */
    fun withLogin2(login2: Login2Result?): LoginResult {
        val extra = login2?.profile ?: return this
        val merged: Map<*, *> = LinkedHashMap<Any?, Any?>().apply {
            profile?.let { putAll(it) }
            putAll(extra)
        }
        return copy(profile = merged, userId = userIdOf(merged) ?: userId ?: userIdOf(extra))
    }

    companion object {
        fun from(payload: Any?): LoginResult {
            val map = payload as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val profile = map["profile"] as? Map<*, *>
            return LoginResult(
                profile = profile,
                userId = userIdOf(profile),
                chats = map["chats"] as? List<Any?> ?: emptyList(),
                token = map["token"] as? String,
                time = (map["time"] as? Number)?.toLong(),
                configHash = configHashOf(map),
                login2Flags = map["login2Flags"] as? Map<*, *>,
                raw = map,
            )
        }
    }
}

/** `config.hash` of a login reply: a string or an integer (PyMax `ConfigHash`). */
private fun configHashOf(map: Map<*, *>): Any? = (map["config"] as? Map<*, *>)?.get("hash")?.let { if (it is Number) it.toLong() else it as? String }

/** `profile.contact.id` (PyMax `Profile.contact: User`). */
private fun userIdOf(profile: Map<*, *>?): Long? = ((profile?.get("contact") as? Map<*, *>)?.get("id") as? Number)?.toLong()

/**
 * Phone-code authentication and stored-token login over a [RequestSink].
 *
 * @param device the identity used for the handshake; `deviceId`, `appVersion`, `arch` and
 *   `deviceType` feed the fingerprint and the login payload.
 * @param handshake the reply of the current connection's handshake (for `callsSeed`).
 * @param fingerprint digests for the fingerprint; by default the built-in set for
 *   `device.userAgent.appVersion`. Required for mobile device types.
 */
class AuthApi(
    private val sink: RequestSink,
    val device: DeviceInfo,
    private val handshake: () -> HandshakeInfo?,
    val fingerprint: ApkFingerprint? = ApkFingerprint.forVersion(device.userAgent.appVersion),
) {
    /** Auth over [session]: its requests, its device, and the handshake of its current Online state. */
    constructor(session: SessionMachine, fingerprint: ApkFingerprint? = ApkFingerprint.forVersion(session.config.device.userAgent.appVersion)) : this(
        RequestSink { opcode, payload -> session.request(opcode, payload) },
        session.config.device,
        { (session.state.value as? SessionState.Online)?.handshake },
        fingerprint,
    )

    /**
     * Requests an SMS / phone code (`AUTH_REQUEST`, 17).
     *
     * @param language sent as `language` (kolibri); `null` omits it (PyMax).
     * @throws ServerErrorException if the server rejects the request (bad number, limits).
     * @throws AuthException if the reply has no `token`, or the fingerprint cannot be computed
     *   (no `callsSeed`, no digests for the app version, or an unknown `arch`).
     */
    suspend fun requestCode(phone: String, type: CodeRequestType = CodeRequestType.START_AUTH, language: String? = "ru"): CodeRequest {
        val reply = sink.request(Opcode.AUTH_REQUEST, requestCodePayload(phone, type, language, handshake()))
        val map = reply.payload as? Map<*, *> ?: throw AuthException("AUTH_REQUEST reply is not a map")
        val token = map["token"] as? String ?: throw AuthException("AUTH_REQUEST reply has no token")
        return CodeRequest(
            token = token,
            codeLength = (map["codeLength"] as? Number)?.toInt(),
            requestMaxDuration = (map["requestMaxDuration"] as? Number)?.toLong(),
            requestCountLeft = (map["requestCountLeft"] as? Number)?.toInt(),
            altActionDuration = (map["altActionDuration"] as? Number)?.toLong(),
            raw = map,
        )
    }

    /**
     * Checks the code (`AUTH`, 18) for the [token] from [requestCode].
     *
     * @throws ServerErrorException for a wrong / expired code.
     * @throws AuthException if the reply carries neither a token nor a password challenge.
     */
    suspend fun verifyCode(token: String, code: String): VerifyResult {
        val reply = sink.request(Opcode.AUTH, verifyCodePayload(token, code))
        val map = reply.payload as? Map<*, *> ?: throw AuthException("AUTH reply is not a map")
        (map["passwordChallenge"] as? Map<*, *>)?.let { challenge ->
            val trackId = challenge["trackId"] as? String ?: throw AuthException("passwordChallenge without trackId")
            return VerifyResult.PasswordRequired(trackId, challenge["hint"] as? String, map)
        }
        val attrs = map["tokenAttrs"] as? Map<*, *>
        fun tokenOf(kind: String): String? = ((attrs?.get(kind) as? Map<*, *>)?.get("token") as? String)
        tokenOf("LOGIN")?.let { login ->
            val profile = map["profile"] as? Map<*, *>
            return VerifyResult.LoggedIn(login, userIdOf(profile), profile, map)
        }
        tokenOf("REGISTER")?.let { return VerifyResult.RegistrationRequired(it, map) }
        throw AuthException("AUTH reply has neither tokenAttrs.LOGIN, tokenAttrs.REGISTER nor passwordChallenge")
    }

    /**
     * Logs in with a stored [token] (`LOGIN`, 19). Usually called from [TokenLogin.hook] right
     * after the handshake.
     *
     * @throws InvalidTokenException for `FAIL_LOGIN_TOKEN` / `FAIL_LOGOUT_ALL`.
     * @throws ServerErrorException for other errors.
     */
    suspend fun login(
        token: String,
        sync: SyncState = SyncState(),
        interactive: Boolean = true,
        chatsCount: Int? = null,
        handshake: HandshakeInfo? = this.handshake(),
    ): LoginResult {
        val reply = try {
            sink.request(Opcode.LOGIN, loginPayload(token, sync, interactive, chatsCount, handshake))
        } catch (e: ServerErrorException) {
            if (isInvalidToken(e)) throw InvalidTokenException(e)
            throw e
        }
        return LoginResult.from(reply.payload)
    }

    /**
     * Answers a 2FA password challenge (`AUTH_LOGIN_CHECK_PASSWORD`, 115) with the `trackId` of
     * [VerifyResult.PasswordRequired] (PyMax `check_password`).
     *
     * @return [VerifyResult.LoggedIn] with `tokenAttrs.LOGIN.token`.
     * @throws WrongPasswordException if the OK reply carries an `error` instead of a token.
     * @throws ServerErrorException for an ERROR reply.
     * @throws AuthException if the reply has neither a token nor an error.
     */
    suspend fun checkPassword(trackId: String, password: String): VerifyResult.LoggedIn {
        require(trackId.isNotEmpty()) { "trackId must not be empty" }
        val reply = sink.request(Opcode.AUTH_LOGIN_CHECK_PASSWORD, checkPasswordPayload(trackId, password))
        val map = reply.payload as? Map<*, *> ?: throw AuthException("AUTH_LOGIN_CHECK_PASSWORD reply is not a map")
        val token = ((map["tokenAttrs"] as? Map<*, *>)?.get("LOGIN") as? Map<*, *>)?.get("token") as? String
        if (token != null) {
            val profile = map["profile"] as? Map<*, *>
            return VerifyResult.LoggedIn(token, userIdOf(profile), profile, map)
        }
        (map["error"] as? String)?.let { throw WrongPasswordException(it, map) }
        throw AuthException("AUTH_LOGIN_CHECK_PASSWORD reply has neither tokenAttrs.LOGIN nor error")
    }

    /**
     * Registers a new account for an unknown number (`AUTH_CONFIRM`, 23) with the token of
     * [VerifyResult.RegistrationRequired] (PyMax `confirm_registration`). The returned
     * [Registration.token] is the login token (PyMax's SMS flow logs in with it).
     */
    suspend fun confirmRegistration(registerToken: String, firstName: String, lastName: String? = null): Registration {
        require(firstName.isNotBlank()) { "firstName must not be blank" }
        val reply = sink.request(Opcode.AUTH_CONFIRM, confirmRegistrationPayload(registerToken, firstName, lastName))
        val map = reply.payload as? Map<*, *> ?: throw AuthException("AUTH_CONFIRM reply is not a map")
        val token = map["token"] as? String ?: throw AuthException("AUTH_CONFIRM reply has no token")
        val profile = map["profile"] as? Map<*, *>
        return Registration(token, userIdOf(profile), profile, map)
    }

    /**
     * Ends this session on the server (`LOGOUT`, 20, `{}`; PyMax `SelfService.logout`). The login
     * token is invalid afterwards; the reply is kept raw.
     */
    suspend fun logout(): Map<*, *> = sink.request(Opcode.LOGOUT, emptyMap<String, Any?>()).payload as? Map<*, *> ?: emptyMap<Any?, Any?>()

    /**
     * Opcode 8 as PyMax's `LOGIN2` (`mobile_login2`): `{needProfile = flags.profileEnabled,
     * contactsSync = sync.contactsSync if flags.contactEnabled else -1, configHash}`. PyMax sends it
     * right after `LOGIN` when [LoginResult.login2Flags] has any flag set; kolibri names the code
     * `CONTACTS_GET` and never sends it (protocol.md K11).
     */
    suspend fun login2(flags: Login2Flags, sync: SyncState): Login2Result =
        Login2Result.from(sink.request(Opcode.CONTACTS_GET, login2Payload(flags, sync)).payload)

    /** `AUTH_LOGIN_CHECK_PASSWORD` body (PyMax `CheckPasswordChallengePayload`). */
    fun checkPasswordPayload(trackId: String, password: String): Map<String, Any?> = linkedMapOf("trackId" to trackId, "password" to password)

    /** `AUTH_CONFIRM` body (PyMax `ConfirmRegistrationPayload`, `lastName` omitted when `null`). */
    fun confirmRegistrationPayload(registerToken: String, firstName: String, lastName: String?): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>("firstName" to firstName)
        if (lastName != null) payload["lastName"] = lastName
        payload["token"] = registerToken
        payload["tokenType"] = "REGISTER"
        return payload
    }

    /** Opcode-8 body (PyMax `Login2Payload.from_sync_state`). */
    fun login2Payload(flags: Login2Flags, sync: SyncState): Map<String, Any?> = linkedMapOf(
        "needProfile" to flags.profileEnabled,
        "contactsSync" to (if (flags.contactEnabled) sync.contactsSync else -1L),
        "configHash" to sync.configHash,
    )

    /**
     * Approves a web/desktop QR login from this signed-in session (`AUTH_QR_APPROVE`, 290).
     *
     * The whole QR scheme (PyMax `auth/qr.py` `QrAuthFlow`, `api/auth/service.py`):
     * 1. the web client (`WebClient`, deviceType WEB) sends `GET_QR` (288) `{}` and gets
     *    `{expiresAt, pollingInterval, qrLink, trackId, ttl}` (`RequestQrResponse`), then shows
     *    `qrLink` as a QR code;
     * 2. a signed-in phone scans it and sends `AUTH_QR_APPROVE` (290) `{qrLink}` — this call
     *    (`authorize_qr_login`, payload `ApproveQrLoginPayload(qr_link)`);
     * 3. the web client polls `GET_QR_STATUS` (289) `{trackId}` until
     *    `status.loginAvailable` (`CheckQrResponse`);
     * 4. the web client sends `LOGIN_BY_QR` (291) `{trackId}` and takes its login token from
     *    `tokenAttrs.LOGIN.token` (`CheckCodeResponse`, as for `AUTH` 18).
     *
     * Only step 2 runs on this (mobile) side. It must go over a session that is already logged
     * in, e.g. a `SessionMachine` whose [TokenLogin.hook] has sent `LOGIN` (19) with the Android
     * user agent and fingerprint; the 290 payload itself carries no device data (PyMax). kolibri
     * only defines the opcode constant (`kolibri-net/src/protocol/opcodes.rs`), with no payload.
     * The reply shape is undocumented (PyMax ignores it), so it is kept raw in [QrApproval].
     *
     * @throws AuthException if [qrLink] is blank.
     * @throws InvalidTokenException if the server reports the session's token as invalid
     *   (`FAIL_LOGIN_TOKEN` / `FAIL_LOGOUT_ALL`, as for [login]).
     * @throws ServerErrorException for other ERROR replies (e.g. an expired QR).
     */
    suspend fun approveQrLogin(qrLink: String): QrApproval {
        if (qrLink.isBlank()) throw AuthException("qrLink is blank")
        val reply = try {
            sink.request(Opcode.AUTH_QR_APPROVE, approveQrLoginPayload(qrLink))
        } catch (e: ServerErrorException) {
            if (isInvalidToken(e)) throw InvalidTokenException(e)
            throw e
        }
        return QrApproval(qrLink, reply.payload as? Map<*, *> ?: emptyMap<Any?, Any?>())
    }

    /** `AUTH_QR_APPROVE` body: `{qrLink}` (PyMax `ApproveQrLoginPayload`). */
    fun approveQrLoginPayload(qrLink: String): Map<String, Any?> = linkedMapOf("qrLink" to qrLink)

    /** `AUTH_REQUEST` body: `phone, type, language?, mode?` (mode only for non-web devices). */
    fun requestCodePayload(phone: String, type: CodeRequestType, language: String?, handshake: HandshakeInfo?): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>("phone" to phone, "type" to type.name)
        if (language != null) payload["language"] = language
        if (!HandshakePayload.isWeb(device.userAgent)) payload["mode"] = computeFingerprint(handshake)
        return payload
    }

    /** `AUTH` body. */
    fun verifyCodePayload(token: String, code: String): Map<String, Any?> =
        linkedMapOf("token" to token, "verifyCode" to code, "authTokenType" to "CHECK_CODE")

    /**
     * `LOGIN` body.
     *
     * Mobile (PyMax `SyncPayload`, field order kept): `userAgent` (same map as in the handshake),
     * `token`, `chatCacheFingerprint` (96-byte [ApkFingerprint]), `chatsCount` (only if set),
     * `chatsSync`, `contactsSync`, `draftsSync`, `interactive`, `presenceSync`,
     * `exp = {chatsCountGroups: bin 0A 32}`, `configHash`.
     *
     * Web (PyMax `WebSyncPayload`): `token`, `chatsCount` (default 40), `interactive`,
     * `chatsSync`, `contactsSync`, `presenceSync`, `draftsSync`.
     */
    fun loginPayload(token: String, sync: SyncState, interactive: Boolean, chatsCount: Int?, handshake: HandshakeInfo?): Map<String, Any?> {
        if (HandshakePayload.isWeb(device.userAgent)) {
            return linkedMapOf(
                "token" to token,
                "chatsCount" to (chatsCount ?: WEB_CHATS_COUNT),
                "interactive" to interactive,
                "chatsSync" to sync.chatsSync,
                "contactsSync" to sync.contactsSync,
                "presenceSync" to sync.presenceSync,
                "draftsSync" to sync.draftsSync,
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "userAgent" to HandshakePayload.mobileUserAgent(device.userAgent),
            "token" to token,
            "chatCacheFingerprint" to computeFingerprint(handshake),
        )
        if (chatsCount != null) payload["chatsCount"] = chatsCount
        payload["chatsSync"] = sync.chatsSync
        payload["contactsSync"] = sync.contactsSync
        payload["draftsSync"] = sync.draftsSync
        payload["interactive"] = interactive
        payload["presenceSync"] = sync.presenceSync
        payload["exp"] = linkedMapOf("chatsCountGroups" to byteArrayOf(0x0A, 0x32))
        payload["configHash"] = sync.configHash
        return payload
    }

    private fun computeFingerprint(handshake: HandshakeInfo?): ByteArray {
        val seed = handshake?.callsSeed
            ?: throw AuthException("the handshake reply has no callsSeed (needed for the fingerprint; the server omits it for IOS)")
        val fp = fingerprint
            ?: throw AuthException("no ApkFingerprint for app version ${device.userAgent.appVersion}; pass one to AuthApi / TokenLogin")
        val arch = device.userAgent.arch?.takeIf { it.isNotEmpty() } ?: ApkFingerprint.DEFAULT_ARCH
        if (arch !in fp.soSha256) {
            throw AuthException("no native-library digest for arch '$arch' in ApkFingerprint ${fp.appVersion}; known: ${fp.soSha256.keys.joinToString()}")
        }
        return fp.compute(seed, device.deviceId, arch)
    }

    companion object {
        /** PyMax `WebSyncPayload.chats_count`. */
        const val WEB_CHATS_COUNT: Int = 40

        private val INVALID_TOKEN_ERRORS = setOf("FAIL_LOGIN_TOKEN", "FAIL_LOGOUT_ALL")

        /** `true` if [e] means the login token is no longer valid (checks `error` and `message`). */
        fun isInvalidToken(e: ServerErrorException): Boolean =
            e.errorKey in INVALID_TOKEN_ERRORS || e.rawMessage in INVALID_TOKEN_ERRORS

        /**
         * A [TokenLogin] for [token]; pass its [TokenLogin.hook] as `afterHandshake` of the
         * [SessionMachine] built with the same [device].
         */
        fun tokenLoginHook(
            token: String,
            device: DeviceInfo,
            fingerprint: ApkFingerprint? = ApkFingerprint.forVersion(device.userAgent.appVersion),
            sync: SyncState = SyncState(),
            interactive: Boolean = true,
        ): TokenLogin = TokenLogin(token, device, fingerprint, sync, interactive)
    }
}
