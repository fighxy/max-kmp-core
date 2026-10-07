@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.transport

import com.max.core.protocol.Opcode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** requestTimeout bounds the write lock, a blocking write and the reply. */
class MaxTransportWriteTimeoutTest {
    private val config = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false, requestTimeout = 5.seconds)

    /** A socket whose write blocks like a JVM `OutputStream.write`: it ignores cancellation and returns only when closed. */
    private class BlockingWriteConnection : RawConnection {
        private val closed = CompletableDeferred<Unit>()
        var writes = 0
        val isClosed: Boolean get() = closed.isCompleted

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            closed.await()
            return -1
        }

        override suspend fun write(bytes: ByteArray) {
            writes++
            withContext(NonCancellable) { closed.await() }
            throw ConnectionClosedException("socket closed")
        }

        override suspend fun close() {
            closed.complete(Unit)
        }
    }

    @Test
    fun hangingWriteFailsWithinTheTimeoutAndClearsPending() = runTest {
        val conn = BlockingWriteConnection()
        val t = MaxTransport(config, { _, _, _, _ -> conn }, scope = backgroundScope)
        t.connect()
        val started = currentTime
        val first = async { runCatching { t.request(Opcode.CHAT_INFO, mapOf("chatIds" to listOf(1))) } }
        // waits for the write lock the first request holds
        val second = async { runCatching { t.request(Opcode.CHAT_HISTORY, null) } }
        val ping = async { runCatching { t.sendRequest(Opcode.PING.value, null) } }
        runCurrent()
        assertEquals(1, conn.writes)

        advanceTimeBy(5.seconds.inWholeMilliseconds + 1)
        runCurrent()
        val e1 = first.await().exceptionOrNull()
        assertIs<RequestTimeoutException>(e1)
        assertTrue(currentTime - started <= 5_001, "failed after ${currentTime - started} ms")
        // the lock waiters are bounded by the same deadline
        assertIs<RequestTimeoutException>(second.await().exceptionOrNull())
        assertIs<RequestTimeoutException>(ping.await().exceptionOrNull())
        // the blocked socket was closed to unblock the write, nothing stays registered
        assertTrue(conn.isClosed)
        assertEquals(0, t.pendingCount())
        runCurrent()
        assertEquals(ConnectionState.Disconnected, t.state.value)

        // the next operation fails at once instead of waiting forever
        val before = currentTime
        assertIs<ConnectionClosedException>(runCatching { t.request(Opcode.PING, null) }.exceptionOrNull())
        assertEquals(before, currentTime)
    }

    @Test
    fun slowReplyFromALiveServerDoesNotCloseTheConnection() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = MaxTransport(config, factory, scope = backgroundScope)
        t.connect()
        val r = async { runCatching { t.request(Opcode.CHAT_INFO, null) } }
        runCurrent()
        // other traffic arrives: the socket is alive, only this reply is late
        advanceTimeBy(2.seconds.inWholeMilliseconds)
        factory.lastConnection!!.feed(push(Opcode.NOTIF_TYPING.value, mapOf("chatId" to 1)))
        advanceTimeBy(3.seconds.inWholeMilliseconds + 1)
        runCurrent()
        assertIs<RequestTimeoutException>(r.await().exceptionOrNull())
        assertEquals(0, t.pendingCount())
        assertEquals(ConnectionState.Connected, t.state.value)
        t.close()
    }

    @Test
    fun silentServerClosesTheConnectionAfterATimeout() = runTest {
        val factory = ScriptedConnectionFactory()
        val t = MaxTransport(config, factory, scope = backgroundScope)
        t.connect()
        val r = async { runCatching { t.request(Opcode.CHAT_INFO, null) } }
        runCurrent()
        advanceTimeBy(5.seconds.inWholeMilliseconds + 1)
        runCurrent()
        assertIs<RequestTimeoutException>(r.await().exceptionOrNull())
        // not a byte for the whole timeout: the dead socket is closed instead of kept
        assertEquals(ConnectionState.Disconnected, t.state.value)
        assertIs<ConnectionClosedException>(runCatching { t.request(Opcode.PING, null) }.exceptionOrNull())
    }
}
