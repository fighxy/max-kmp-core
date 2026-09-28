package com.max.core

import com.max.core.api.ApiException
import com.max.core.api.MalformedReplyException
import com.max.core.auth.AuthException
import com.max.core.auth.InvalidTokenException
import com.max.core.media.UploadException
import com.max.core.session.SessionClosedException
import com.max.core.transport.ConnectTimeoutException
import com.max.core.transport.NotFoundException
import com.max.core.transport.ProxyException
import com.max.core.transport.RequestTimeoutException
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportException
import kotlinx.coroutines.CancellationException

/** What went wrong, for UI and retry decisions ([toMaxError]). */
enum class ErrorKind {
    /** Connection refused / dropped / TLS / DNS / proxy failure. Retry when online again. */
    NETWORK,

    /** Connect or request timeout. Retryable. */
    TIMEOUT,

    /** The login token is no longer valid (`FAIL_LOGIN_TOKEN`, `FAIL_LOGOUT_ALL`): log in again. */
    SESSION_EXPIRED,

    /** Wrong password or another auth-flow rejection. */
    AUTH,

    /** An ERROR reply with an error key ([MaxError.errorKey]); not retried automatically. */
    SERVER,

    /** A NOT_FOUND reply (`cmd = 2`). */
    NOT_FOUND,

    /** An OK reply without the fields the client needs. */
    MALFORMED_REPLY,

    /** A CDN upload failed ([MaxError.httpStatus] when an HTTP status is known). */
    UPLOAD,

    /** The session was closed locally ([com.max.core.session.SessionMachine.disconnect]). */
    CLOSED,

    /** The calling coroutine was cancelled. */
    CANCELLED,

    /** Anything else (bugs, invalid arguments). */
    UNKNOWN,
}

/**
 * A classified failure of any core call.
 *
 * @property errorKey server `error` key (e.g. `attachment.not.ready`) for [ErrorKind.SERVER] /
 *   [ErrorKind.SESSION_EXPIRED].
 * @property retryable whether repeating the same call later can succeed without user action.
 */
data class MaxError(
    val kind: ErrorKind,
    val message: String,
    val errorKey: String? = null,
    val httpStatus: Int? = null,
    val cause: Throwable,
) {
    val retryable: Boolean get() = kind == ErrorKind.NETWORK || kind == ErrorKind.TIMEOUT || (kind == ErrorKind.UPLOAD && (httpStatus == null || httpStatus >= 500))
}

/** Classifies any exception thrown by the core. */
fun Throwable.toMaxError(): MaxError {
    val msg = message ?: this::class.simpleName ?: "error"
    return when (this) {
        is CancellationException -> MaxError(ErrorKind.CANCELLED, msg, cause = this)
        is InvalidTokenException -> MaxError(ErrorKind.SESSION_EXPIRED, msg, serverError.errorKey ?: serverError.rawMessage, cause = this)
        is AuthException -> MaxError(ErrorKind.AUTH, msg, cause = this)
        is ServerErrorException ->
            if (isSessionExpired) MaxError(ErrorKind.SESSION_EXPIRED, msg, errorKey ?: rawMessage, cause = this)
            else MaxError(ErrorKind.SERVER, msg, errorKey, cause = this)
        is NotFoundException -> MaxError(ErrorKind.NOT_FOUND, msg, cause = this)
        is ConnectTimeoutException, is RequestTimeoutException -> MaxError(ErrorKind.TIMEOUT, msg, cause = this)
        is SessionClosedException -> MaxError(ErrorKind.CLOSED, msg, cause = this)
        is ProxyException, is TransportException -> MaxError(ErrorKind.NETWORK, msg, cause = this)
        is MalformedReplyException -> MaxError(ErrorKind.MALFORMED_REPLY, msg, cause = this)
        is UploadException -> {
            val io = generateSequence(cause) { it.cause }.any { isPlatformIoException(it) || it is TransportException }
            if (io && status == null) MaxError(ErrorKind.NETWORK, msg, cause = this) else MaxError(ErrorKind.UPLOAD, msg, httpStatus = status, cause = this)
        }
        is ApiException -> MaxError(ErrorKind.UNKNOWN, msg, cause = this)
        else -> if (isPlatformIoException(this)) MaxError(ErrorKind.NETWORK, msg, cause = this) else MaxError(ErrorKind.UNKNOWN, msg, cause = this)
    }
}

/** Platform I/O failure types (JVM / Android `java.io.IOException`, iOS `UrlSessionException`). */
internal expect fun isPlatformIoException(t: Throwable): Boolean
