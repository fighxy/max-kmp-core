package com.max.core.auth

/**
 * Auth helpers: request code → verify → login; anti-spoof fingerprint inputs
 * are host-supplied (see kolibri / PyMax).
 *
 * TODO: wire opcodes AUTH_REQUEST / AUTH / LOGIN.
 */
class AuthApi(
    // TODO: session / request sink
) {
    suspend fun requestCode(phone: String): String = error("TODO: AuthApi.requestCode")
    suspend fun verifyCode(token: String, code: String): String = error("TODO: AuthApi.verifyCode")
}
