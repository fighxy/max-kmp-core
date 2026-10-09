package com.maxly.core.auth

import com.maxly.core.session.DeviceInfo
import com.maxly.core.session.HandshakeInfo
import com.maxly.core.transport.MaxTransport
import kotlinx.coroutines.CancellationException
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
 * `presenceSync` is the exception: the reply's `presence` delta is only worth skipping once it
 * sits in a store, so the login keeps the old marker and the owner of the store moves it with
 * [presenceApplied] after applying the reply (`MaxClient` does). A reply that was dropped (an
 * account switch, a late reply) thus leaves `presenceSync` where it was, and the next `LOGIN`
 * asks for that presence again.
 * An invalid token fails the hook with [InvalidTokenException], which ends the session in
 * `Failed` (it is a `FatalSessionError`).
 *
 * With [followLogin2] (default, as PyMax `App.login`) a reply whose `login2Flags` has a flag set
 * is followed by opcode 8 ([AuthApi.login2]); its result goes to [login2Result] and its profile is
 * merged into [result] ([LoginResult.withLogin2]), so `userId` / `profile` describe this login even
 * when only `LOGIN2` carried the profile. Both are reset at the start of every login. A failed
 * `LOGIN2` does not fail the login (kolibri never sends it, protocol.md K11); the error is kept in
 * [login2Error].
 */
class TokenLogin(
    token: String,
    val device: DeviceInfo,
    val fingerprint: ApkFingerprint? = ApkFingerprint.forVersion(device.userAgent.appVersion),
    sync: SyncState = SyncState(),
    interactive: Boolean = true,
    val chatsCount: Int? = null,
    val followLogin2: Boolean = true,
) {
    /**
     * `interactive` of the next `LOGIN` (the user is looking at the app). `MaxClient.setInteractive`
     * keeps it in step with the PING flag, so a reconnect in the background logs in non-interactive.
     */
    @kotlin.concurrent.Volatile
    var interactive: Boolean = interactive

    /** Current login token (replaced when a `LOGIN` reply carries a new one). */
    var token: String = token
        private set

    /** Sync markers for the next `LOGIN`. */
    var sync: SyncState = sync
        private set

    /**
     * The markers the last `LOGIN` was sent with (`null` before the first). With the default
     * `configHash` ([DEFAULT_CONFIG_HASH]) its `config` is the full snapshot, otherwise only what
     * changed since that hash.
     */
    var sentSync: SyncState? = null
        private set

    /** Replaces [token], e.g. with the one `SESSIONS_CLOSE` (97) returns; used from the next `LOGIN`. */
    fun replaceToken(newToken: String) {
        token = newToken
    }

    /** Replaces [sync], e.g. with a new `configHash` returned by `CONFIG` (22). */
    fun updateSync(transform: (SyncState) -> SyncState) {
        sync = transform(sync)
    }

    /**
     * Marks the `presence` of [result] (a reply of this login) as applied to a store: the next
     * `LOGIN` sends its `time` as `presenceSync`. Does nothing for a reply without a `presence`
     * map or without `time`.
     */
    fun presenceApplied(result: LoginResult) {
        val time = result.time ?: return
        if (result.raw["presence"] !is Map<*, *>) return
        sync = sync.copy(presenceSync = time)
    }

    private val _result = MutableStateFlow<LoginResult?>(null)

    /** The last successful `LOGIN` reply (profile merged with `LOGIN2`'s, chats, ...), `null` before the first. */
    val result: StateFlow<LoginResult?> = _result.asStateFlow()

    private val _login2 = MutableStateFlow<Login2Result?>(null)

    /** The last `LOGIN2` (opcode 8) reply, if one was sent. */
    val login2Result: StateFlow<Login2Result?> = _login2.asStateFlow()

    /** Why the last `LOGIN2` failed, `null` if it succeeded or was not sent. */
    var login2Error: Throwable? = null
        private set

    /** Pass as `SessionMachine(afterHandshake = ...)`. */
    val hook: suspend (MaxTransport, HandshakeInfo) -> Unit = { transport, handshake -> login(transport, handshake) }

    private suspend fun login(transport: MaxTransport, handshake: HandshakeInfo) {
        val api = AuthApi(RequestSink { opcode, payload -> transport.request(opcode, payload) }, device, { handshake }, fingerprint)
        // results of the previous login must not be mistaken for this one
        _login2.value = null
        login2Error = null
        sentSync = sync
        val r = api.login(token, sync, interactive, chatsCount, handshake)
        r.token?.let { token = it }
        // presenceSync moves only when the reply's presence reached a store (presenceApplied)
        sync = sync.updatedBy(r).copy(presenceSync = sync.presenceSync)
        val flags = r.login2
        var r2: Login2Result? = null
        if (followLogin2 && flags != null && flags.enabled) {
            try {
                r2 = api.login2(flags, sync)
                sync = sync.updatedBy(r2)
                _login2.value = r2
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                login2Error = e
            }
        }
        _result.value = r.withLogin2(r2)
    }
}
