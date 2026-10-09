package com.maxly.core

import com.maxly.core.api.ApiException
import com.maxly.core.api.MalformedReplyException
import com.maxly.core.auth.AuthException
import com.maxly.core.auth.InvalidTokenException
import com.maxly.core.media.UploadException
import com.maxly.core.session.SessionClosedException
import com.maxly.core.transport.ConnectTimeoutException
import com.maxly.core.transport.NotFoundException
import com.maxly.core.transport.OutboundBlockedException
import com.maxly.core.transport.ProxyException
import com.maxly.core.transport.RequestTimeoutException
import com.maxly.core.transport.ServerErrorException
import com.maxly.core.transport.TransportException
import kotlinx.coroutines.CancellationException

/** What went wrong, for UI and retry decisions ([toMaxError]). */
enum class ErrorKind {
    /** Connection refused / dropped / TLS / DNS / proxy failure. Retry when online again. */
    NETWORK,

    /** Connect or request timeout. Retryable. */
    TIMEOUT,

    /**
     * The login was refused for good (`login.token`, `login.blocked`, `login.flood`, legacy
     * `FAIL_LOGIN_TOKEN` / `FAIL_LOGOUT_ALL`; [com.maxly.core.auth.LoginRejection]): log in again.
     */
    SESSION_EXPIRED,

    /** Wrong password or another auth-flow rejection. */
    AUTH,

    /**
     * An ERROR reply with an error key ([MaxError.errorKey]); not retried automatically. Also a
     * request the core refuses because the server does not support it
     * ([com.maxly.core.transport.RefusedOpcodes], e.g. `pinned.unsupported`); nothing was sent.
     */
    SERVER,

    /** A NOT_FOUND reply (`cmd = 2`). */
    NOT_FOUND,

    /** An OK reply without the fields the client needs. */
    MALFORMED_REPLY,

    /** A CDN upload failed ([MaxError.httpStatus] when an HTTP status is known). */
    UPLOAD,

    /** The session was closed locally ([com.maxly.core.session.SessionMachine.disconnect]). */
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
 * @property title the server's `title` for the user (ERROR replies only), when sent.
 * @property localizedMessage the server's `localizedMessage` for the user (ERROR replies only).
 * @property description the server's `description` (longer text under [title]), when sent.
 * @property retryable whether repeating the same call later can succeed without user action.
 */
data class MaxError(
    val kind: ErrorKind,
    val message: String,
    val errorKey: String? = null,
    val httpStatus: Int? = null,
    val cause: Throwable,
    val title: String? = null,
    val localizedMessage: String? = null,
    val description: String? = null,
) {
    val retryable: Boolean get() = kind == ErrorKind.NETWORK || kind == ErrorKind.TIMEOUT || (kind == ErrorKind.UPLOAD && (httpStatus == null || httpStatus >= 500))

    /**
     * The server's own text to show, as the Android app picks it: [title], else
     * [localizedMessage]; `null` when the server sent neither (show a generic text then).
     */
    val serverText: String? get() = title ?: localizedMessage
}

/** [this] with the server's texts of [e] ([ServerErrorException.title] and the others). */
private fun MaxError.withTexts(e: ServerErrorException): MaxError =
    copy(title = e.title, localizedMessage = e.localizedText, description = e.description)

/** Classifies any exception thrown by the core. */
fun Throwable.toMaxError(): MaxError {
    val msg = message ?: this::class.simpleName ?: "error"
    return when (this) {
        is CancellationException -> MaxError(ErrorKind.CANCELLED, msg, cause = this)
        is InvalidTokenException ->
            MaxError(ErrorKind.SESSION_EXPIRED, msg, serverError.errorKey ?: serverError.rawMessage, cause = this).withTexts(serverError)
        is AuthException -> MaxError(ErrorKind.AUTH, msg, cause = this)
        is ServerErrorException ->
            if (isSessionExpired) MaxError(ErrorKind.SESSION_EXPIRED, msg, errorKey ?: rawMessage, cause = this).withTexts(this)
            else MaxError(ErrorKind.SERVER, msg, errorKey, cause = this).withTexts(this)
        is NotFoundException -> MaxError(ErrorKind.NOT_FOUND, msg, cause = this)
        is ConnectTimeoutException, is RequestTimeoutException -> MaxError(ErrorKind.TIMEOUT, msg, cause = this)
        is SessionClosedException -> MaxError(ErrorKind.CLOSED, msg, cause = this)
        is ProxyException, is TransportException -> MaxError(ErrorKind.NETWORK, msg, cause = this)
        is MalformedReplyException -> MaxError(ErrorKind.MALFORMED_REPLY, msg, cause = this)
        is OutboundBlockedException ->
            if (errorKey != null) MaxError(ErrorKind.SERVER, msg, errorKey, cause = this) else MaxError(ErrorKind.UNKNOWN, msg, cause = this)
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
