package com.maxly.core

import com.maxly.core.media.UploadException
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformErrorMappingTest {
    @Test
    fun ioExceptionsAreNetwork() {
        assertEquals(ErrorKind.NETWORK, java.net.ConnectException("refused").toMaxError().kind)
        assertEquals(ErrorKind.NETWORK, javax.net.ssl.SSLHandshakeException("bad cert").toMaxError().kind)
        assertEquals(ErrorKind.NETWORK, UploadException("HTTP error", cause = java.net.SocketTimeoutException("t")).toMaxError().kind)
    }
}
