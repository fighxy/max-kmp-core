package com.max.core.auth

import com.max.core.session.DeviceInfo
import com.max.core.session.HandshakeInfo
import com.max.core.transport.MaxTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stored-token login as a `SessionMachine` `afterHandshake` hook: after every handshake (first
 * connect and each reconnect) [hook] sends `LOGIN` (19) with the handshake's `callsSeed`, so the
 * session only becomes Online once logged in, like PyMax's `App.start` (handshake, then login).
 *
 * ```
 * val login = AuthApi.tokenLoginHook(storedToken, config.device)
 * val session = SessionMachine(config, afterHandshake = login.hook)
 * session.connect()
 * login.result.value?.profile
 * ```
 *
 * Between logins it keeps the state PyMax persists: a refreshed [token] from the reply and the
 * [sync] markers (`time`, `config.hash`), so a re-login after reconnect asks only for changes.
 * An invalid token fails the hook with [InvalidTokenException], which ends the session in
 * `Failed` (it is a `FatalSessionError`).
 */
class TokenLogin(
    token: String,
    val device: DeviceInfo,
    val fingerprint: ApkFingerprint? = ApkFingerprint.forVersion(device.userAgent.appVersion),
    sync: SyncState = SyncState(),
    val interactive: Boolean = true,
    val chatsCount: Int? = null,
) {
    /** Current login token (replaced when a `LOGIN` reply carries a new one). */
    var token: String = token
        private set

    /** Sync markers for the next `LOGIN`. */
    var sync: SyncState = sync
        private set

    private val _result = MutableStateFlow<LoginResult?>(null)

    /** The last successful `LOGIN` reply (profile, chats, ...), `null` before the first. */
    val result: StateFlow<LoginResult?> = _result.asStateFlow()

    /** Pass as `SessionMachine(afterHandshake = ...)`. */
    val hook: suspend (MaxTransport, HandshakeInfo) -> Unit = { transport, handshake -> login(transport, handshake) }

    private suspend fun login(transport: MaxTransport, handshake: HandshakeInfo) {
        val api = AuthApi(RequestSink { opcode, payload -> transport.request(opcode, payload) }, device, { handshake }, fingerprint)
        val r = api.login(token, sync, interactive, chatsCount, handshake)
        r.token?.let { token = it }
        sync = sync.updatedBy(r)
        _result.value = r
    }
}
