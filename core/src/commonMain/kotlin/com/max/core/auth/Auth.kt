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
 *
 * Not implemented, only surfaced: the 2FA password check (`AUTH_LOGIN_CHECK_PASSWORD` 115) and
 * registration (`AUTH_CONFIRM` 23) — see [VerifyResult]; `LOGIN2` (opcode 8 in PyMax, `CONTACTS_GET` in Opcodes.kt; requested via
 * `login2Flags`) — the flags are kept in [LoginResult.login2Flags].
 */

/** Minimal request interface [AuthApi] needs; [SessionMachine] and `MaxTransport` both fit. */
fun interface RequestSink {
    /**
     * Sends [opcode] with [payload] and returns the OK reply.
     *
     * @throws ServerErrorException for an ERROR reply (and other transport exceptions).
     */
    suspend fun request(opcode: Opcode, payload: Any?): TransportPacket
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

    /** A 2FA password is set (`passwordChallenge`); continue with opcode 115 (not implemented). */
    data class PasswordRequired(val trackId: String, val hint: String?, override val raw: Map<*, *>) : VerifyResult

    /** Unknown number: `tokenAttrs.REGISTER.token` for `AUTH_CONFIRM` (23, not implemented). */
    data class RegistrationRequired(val registerToken: String, override val raw: Map<*, *>) : VerifyResult
}

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
                configHash = (map["config"] as? Map<*, *>)?.get("hash")?.let { if (it is Number) it.toLong() else it as? String },
                login2Flags = map["login2Flags"] as? Map<*, *>,
                raw = map,
            )
        }
    }
}

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
