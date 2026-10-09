package com.maxly.core.api

import com.maxly.core.auth.RequestSink
import com.maxly.core.protocol.Opcode

/**
 * Cloud password (2FA) management over a logged-in [RequestSink], following PyMax
 * `src/pymax/api/auth/service.py` (`set_2fa`, `remove_2fa`, `change_password`) and its payloads.
 * Every flow starts with a track (`AUTH_CREATE_TRACK` 112) and ends with `AUTH_SET_2FA` 111.
 * Entering the password at login is `AuthApi.checkPassword` (115).
 *
 * Server errors surface as `ServerErrorException` (for example a wrong current password on 113).
 */
class TwoFactorApi(private val sink: RequestSink) {

    /** `AUTH_CREATE_TRACK` 112 `{type: 0}` → `trackId`. */
    suspend fun createTrack(): String {
        val map = replyMap(sink.request(Opcode.AUTH_CREATE_TRACK, linkedMapOf("type" to 0)), Opcode.AUTH_CREATE_TRACK)
        return map["trackId"] as? String ?: throw MalformedReplyException(Opcode.AUTH_CREATE_TRACK, "no trackId", map)
    }

    /** `AUTH_VALIDATE_PASSWORD` 107 `{trackId, password}`: proposes a new password. */
    suspend fun validateNewPassword(trackId: String, password: String) {
        sink.request(Opcode.AUTH_VALIDATE_PASSWORD, linkedMapOf("trackId" to trackId, "password" to password))
    }

    /** `AUTH_CHECK_PASSWORD` 113 `{trackId, password}`: proves the current password. */
    suspend fun checkCurrentPassword(trackId: String, password: String) {
        sink.request(Opcode.AUTH_CHECK_PASSWORD, linkedMapOf("trackId" to trackId, "password" to password))
    }

    /** `AUTH_VALIDATE_HINT` 108 `{trackId, hint}`. */
    suspend fun setHint(trackId: String, hint: String) {
        sink.request(Opcode.AUTH_VALIDATE_HINT, linkedMapOf("trackId" to trackId, "hint" to hint))
    }

    /** `AUTH_VERIFY_EMAIL` 109 `{trackId, email}`: the server mails a code. */
    suspend fun requestEmailCode(trackId: String, email: String) {
        sink.request(Opcode.AUTH_VERIFY_EMAIL, linkedMapOf("trackId" to trackId, "email" to email))
    }

    /** `AUTH_CHECK_EMAIL` 110 `{trackId, verifyCode}`. */
    suspend fun confirmEmailCode(trackId: String, code: String) {
        sink.request(Opcode.AUTH_CHECK_EMAIL, linkedMapOf("trackId" to trackId, "verifyCode" to code))
    }

    /**
     * The cloud password state (`AUTH_2FA_DETAILS` 104, `{trackId}`); reply `{password: {enabled,
     * email, hint}}` (as Komet reads it). The current password is not needed.
     */
    suspend fun details(trackId: String): TwoFactorDetails {
        val map = replyMap(sink.request(Opcode.AUTH_2FA_DETAILS, linkedMapOf("trackId" to trackId)), Opcode.AUTH_2FA_DETAILS)
        val password = map["password"] as? Map<*, *>
        return TwoFactorDetails(
            enabled = password?.get("enabled") == true,
            email = (password?.get("email") as? String)?.takeIf { it.isNotBlank() },
            hint = (password?.get("hint") as? String)?.takeIf { it.isNotBlank() },
            raw = map,
        )
    }

    /** [details] on a new track (`AUTH_CREATE_TRACK` 112 → 104). */
    suspend fun status(): TwoFactorDetails = details(createTrack())

    /**
     * Saves the recovery e-mail confirmed on [trackId] (`AUTH_SET_2FA` 111, `{expectedCapabilities:
     * [4], trackId}`, as Komet). Before it: [createTrack], [checkCurrentPassword],
     * [requestEmailCode], [confirmEmailCode] on the same track.
     */
    suspend fun commitEmail(trackId: String) {
        sink.request(
            Opcode.AUTH_SET_2FA,
            linkedMapOf("expectedCapabilities" to listOf(TwoFactorCapability.EMAIL.code), "trackId" to trackId),
        )
    }

    /**
     * Asks for the e-mail code (`AUTH_VERIFY_EMAIL` 109, `{trackId, email}`); returns the reply's
     * `blockingDuration` (seconds until a new code may be requested; 60 when absent, as in Komet).
     */
    suspend fun sendEmailCode(trackId: String, email: String): Int =
        rawMap(sink.request(Opcode.AUTH_VERIFY_EMAIL, linkedMapOf("trackId" to trackId, "email" to email)))["blockingDuration"].asLong()?.toInt() ?: 60

    /**
     * `AUTH_SET_2FA` 111 `{expectedCapabilities, trackId, password, hint?}` (PyMax
     * `SetTwoFactorPayload`, this key order).
     */
    suspend fun commit(trackId: String, password: String, capabilities: List<TwoFactorCapability>, hint: String? = null) {
        val payload = linkedMapOf<String, Any?>(
            "expectedCapabilities" to capabilities.map { it.code },
            "trackId" to trackId,
            "password" to password,
        )
        if (hint != null) payload["hint"] = hint
        sink.request(Opcode.AUTH_SET_2FA, payload)
    }

    /**
     * Enables the cloud password (PyMax `set_2fa`): track → 107 → optional e-mail (109, [emailCode]
     * supplies the mailed code, 110) → optional hint (108) → 111 with `SET_PASSWORD` (+ `HINT`,
     * `EMAIL`).
     */
    suspend fun enable(password: String, hint: String? = null, email: String? = null, emailCode: (suspend (String) -> String)? = null) {
        require(email == null || emailCode != null) { "emailCode is required with an email" }
        val track = createTrack()
        validateNewPassword(track, password)
        if (email != null) {
            requestEmailCode(track, email)
            confirmEmailCode(track, emailCode!!(email))
        }
        if (hint != null) setHint(track, hint)
        val caps = buildList {
            add(TwoFactorCapability.SET_PASSWORD)
            if (hint != null) add(TwoFactorCapability.HINT)
            if (email != null) add(TwoFactorCapability.EMAIL)
        }
        commit(track, password, caps, hint)
    }

    /** Changes the password (PyMax `change_password`): track → 113 old → 107 new → 111 `UPDATE_PASSWORD`. */
    suspend fun changePassword(oldPassword: String, newPassword: String) {
        val track = createTrack()
        checkCurrentPassword(track, oldPassword)
        validateNewPassword(track, newPassword)
        commit(track, newPassword, listOf(TwoFactorCapability.UPDATE_PASSWORD))
    }

    /**
     * Disables the cloud password (PyMax `remove_2fa`): track → 113 → 111
     * `{trackId, remove2fa: true, expectedCapabilities: [5]}`.
     */
    suspend fun disable(password: String) {
        val track = createTrack()
        checkCurrentPassword(track, password)
        sink.request(
            Opcode.AUTH_SET_2FA,
            linkedMapOf("trackId" to track, "remove2fa" to true, "expectedCapabilities" to listOf(TwoFactorCapability.REMOVE.code)),
        )
    }

    companion object {
        /** PyMax `ProfileOptions.SECOND_FACTOR_PASSWORD_ENABLED` in `Profile.profileOptions`. */
        const val PROFILE_OPTION_PASSWORD_ENABLED: Int = 2

        /** Whether the profile says a cloud password is set (PyMax `check_2fa`). */
        fun isEnabled(profile: Profile): Boolean = PROFILE_OPTION_PASSWORD_ENABLED in profile.profileOptions
    }
}

/** PyMax `TwoFactorAction` (`expectedCapabilities` values). */
enum class TwoFactorCapability(val code: Int) {
    SET_PASSWORD(0), UPDATE_PASSWORD(1), RESTORE_PASSWORD(2), HINT(3), EMAIL(4), REMOVE(5)
}

/** The cloud password state ([TwoFactorApi.details]); [email] and [hint] are `null` when not set. */
data class TwoFactorDetails(val enabled: Boolean, val email: String?, val hint: String?, val raw: Map<*, *>)
