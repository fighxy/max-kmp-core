package com.max.core

import com.max.core.api.MalformedReplyException
import com.max.core.auth.InvalidTokenException
import com.max.core.auth.WrongPasswordException
import com.max.core.media.UploadException
import com.max.core.protocol.CmdType
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.session.SessionClosedException
import com.max.core.transport.ConnectTimeoutException
import com.max.core.transport.ConnectionClosedException
import com.max.core.transport.NotFoundException
import com.max.core.transport.ProxyException
import com.max.core.transport.RequestTimeoutException
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportPacket
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
