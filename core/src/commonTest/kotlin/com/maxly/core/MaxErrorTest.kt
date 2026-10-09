package com.maxly.core

import com.maxly.core.api.MalformedReplyException
import com.maxly.core.auth.InvalidTokenException
import com.maxly.core.auth.WrongPasswordException
import com.maxly.core.media.UploadException
import com.maxly.core.protocol.CmdType
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.PROTOCOL_VERSION
import com.maxly.core.protocol.PacketHeader
import com.maxly.core.session.SessionClosedException
import com.maxly.core.transport.ConnectTimeoutException
import com.maxly.core.transport.ConnectionClosedException
import com.maxly.core.transport.NotFoundException
import com.maxly.core.transport.ProxyException
import com.maxly.core.transport.RequestTimeoutException
import com.maxly.core.transport.ServerErrorException
import com.maxly.core.transport.TransportPacket
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaxErrorTest {
    private fun packet(cmd: CmdType, payload: Any?) = TransportPacket(PacketHeader(PROTOCOL_VERSION, cmd.value, 1, Opcode.LOGIN.value.toShort(), 0, false), payload)
    private fun serverError(error: String, message: String) = ServerErrorException.from(packet(CmdType.ERROR, mapOf("error" to error, "message" to message)))

    @Test
    fun classification() {
        val expired = serverError("login.token", "FAIL_LOGIN_TOKEN")
        assertEquals(ErrorKind.SESSION_EXPIRED, expired.toMaxError().kind)
        assertEquals("login.token", expired.toMaxError().errorKey)
        assertEquals(ErrorKind.SESSION_EXPIRED, InvalidTokenException(expired).toMaxError().kind)
        val server = serverError("attachment.not.ready", "not ready").toMaxError()
        assertEquals(ErrorKind.SERVER, server.kind)
        assertEquals("attachment.not.ready", server.errorKey)
        assertFalse(server.retryable)
        assertEquals(ErrorKind.AUTH, WrongPasswordException("error.password", emptyMap<Any?, Any?>()).toMaxError().kind)
        assertEquals(ErrorKind.NOT_FOUND, NotFoundException(packet(CmdType.NOT_FOUND, null)).toMaxError().kind)
        assertEquals(ErrorKind.MALFORMED_REPLY, MalformedReplyException(Opcode.LOGIN, "x", null).toMaxError().kind)
        assertEquals(ErrorKind.CLOSED, SessionClosedException().toMaxError().kind)
        assertEquals(ErrorKind.CANCELLED, CancellationException("c").toMaxError().kind)
        assertEquals(ErrorKind.UNKNOWN, IllegalStateException("bug").toMaxError().kind)
        for (e in listOf(ConnectTimeoutException("t"), RequestTimeoutException(19, 1, "t"))) {
            assertEquals(ErrorKind.TIMEOUT, e.toMaxError().kind)
            assertTrue(e.toMaxError().retryable)
        }
        for (e in listOf(ConnectionClosedException(), ProxyException("p"))) {
            assertEquals(ErrorKind.NETWORK, e.toMaxError().kind)
            assertTrue(e.toMaxError().retryable)
        }
    }

    @Test
    fun serverTextsAndLoginCodes() {
        val body = mapOf(
            "error" to "login.blocked", "message" to "blocked", "localizedMessage" to " Профиль заблокирован ",
            "title" to "Вход невозможен", "description" to "Обратитесь в поддержку",
        )
        val e = ServerErrorException.from(packet(CmdType.ERROR, body))
        assertEquals("Профиль заблокирован", e.message)
        assertEquals("Профиль заблокирован", e.localizedText)
        assertEquals("Вход невозможен", e.title)
        assertEquals("Обратитесь в поддержку", e.description)
        assertEquals("Вход невозможен", e.displayText) // title first, like the Android app
        assertFalse(e.isSessionExpired) // blocked is a login rejection, not an expired token
        val invalid = InvalidTokenException(e)
        assertEquals(com.maxly.core.auth.LoginRejection.BLOCKED, invalid.reason)
        val error = invalid.toMaxError()
        assertEquals(ErrorKind.SESSION_EXPIRED, error.kind)
        assertEquals("login.blocked", error.errorKey)
        assertEquals("Вход невозможен", error.title)
        assertEquals("Профиль заблокирован", error.localizedMessage)
        assertEquals("Обратитесь в поддержку", error.description)
        assertEquals("Вход невозможен", error.serverText)

        // any ERROR reply carries its texts into MaxError
        val plain = ServerErrorException.from(packet(CmdType.ERROR, mapOf("error" to "too.many.requests", "localizedMessage" to "Подождите")))
        assertEquals("Подождите", plain.displayText)
        assertEquals("Подождите", plain.toMaxError().serverText)
        assertEquals(null, plain.toMaxError().title)
        // no texts at all: the app shows its own
        assertEquals(null, ServerErrorException.from(packet(CmdType.ERROR, mapOf("error" to "internal"))).toMaxError().serverText)

        // login.token alone (no FAIL_* message) is an expired session; so are the legacy codes
        assertTrue(ServerErrorException.from(packet(CmdType.ERROR, mapOf("error" to "login.token"))).isSessionExpired)
        assertEquals(ErrorKind.SESSION_EXPIRED, ServerErrorException.from(packet(CmdType.ERROR, mapOf("error" to "login.token"))).toMaxError().kind)
        assertTrue(ServerErrorException.from(packet(CmdType.ERROR, mapOf("message" to "FAIL_LOGOUT_ALL"))).isSessionExpired)
    }

    @Test
    fun uploads() {
        val http413 = UploadException("too large", 413).toMaxError()
        assertEquals(ErrorKind.UPLOAD, http413.kind)
        assertEquals(413, http413.httpStatus)
        assertFalse(http413.retryable)
        assertTrue(UploadException("bad gateway", 502).toMaxError().retryable)
        assertEquals(ErrorKind.NETWORK, UploadException("io", cause = ConnectionClosedException()).toMaxError().kind)
        assertEquals(ErrorKind.UPLOAD, UploadException("timed out waiting for processing").toMaxError().kind)
    }
}
