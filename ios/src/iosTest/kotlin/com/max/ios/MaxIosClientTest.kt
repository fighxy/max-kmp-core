package com.max.ios

import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.transport.ConnectionClosedException
import com.max.core.transport.ConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.shared.InMemoryKeyValueStore
import com.max.shared.MaxClient
import com.max.shared.MaxClientConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Swift facade reports every failure through its callback; nothing throws or aborts. */
class MaxIosClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }
    private val offline = ConnectionFactory { _, _, _, _ -> throw ConnectionClosedException("offline") }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun offlineClient() = MaxIosClient(scope()) { s ->
        MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), offline, noHttp, s)
    }

    private fun <T> callback(block: (CompletableDeferred<T>) -> Unit): T = runBlocking {
        val done = CompletableDeferred<T>()
        block(done)
        withTimeout(10.seconds) { done.await() }
    }

    @Test
    fun failingClientCreationBecomesAnErrorKind() {
        var attempts = 0
        val c = MaxIosClient(scope()) {
            attempts++
            throw IllegalStateException("keychain read failed")
        }
        assertEquals("failed", c.phaseName())
        assertEquals("", c.currentUserId())
        assertFalse(c.hasStoredToken())
        val (phase, kind) = callback<Pair<String?, String?>> { d -> c.start { p, k, _ -> d.complete(p to k) } }
        assertNull(phase)
        assertEquals("UNKNOWN", kind)
        val chats = callback<Pair<Int, String?>> { d -> c.loadChats { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", chats)
        c.watchState { }.cancel()
        c.watchPinnedChats { }.cancel()
        // creation is retried on every call instead of caching the failure
        assertTrue(attempts >= 5)
        callback<Unit> { d -> c.close { d.complete(Unit) } }
    }

    @Test
    fun networkErrorsAndBadIdsAreDeliveredAsKinds() {
        val c = offlineClient()
        val start = callback<Pair<String?, String?>> { d -> c.start { p, k, _ -> d.complete(p to k) } }
        assertEquals(null to "NETWORK", start)
        val send = callback<Pair<IosMessage?, String?>> { d -> c.sendText("not-a-number", "hi") { m, k, _ -> d.complete(m to k) } }
        assertNull(send.first)
        assertEquals("UNKNOWN", send.second)
        val history = callback<Pair<Int, String?>> { d -> c.loadHistory("x", 0, 10) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", history)
        val around = callback<Pair<Int, String?>> { d -> c.loadHistoryAround("x", "1", 0, 20, 20) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", around)
        val aroundMessage = callback<Pair<Int, String?>> { d -> c.loadHistoryAround("1", "y", 0, 20, 20) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", aroundMessage)
        val read = callback<String?> { d -> c.markRead("1", "2") { k, _ -> d.complete(k) } }
        assertEquals("NETWORK", read)
        val code = callback<String?> { d -> c.requestCode("+79990000000", false) { _, k, _ -> d.complete(k) } }
        assertEquals("NETWORK", code)
        val badPin = callback<Pair<Int, String?>> { d -> c.setPinnedChats(listOf("1", "x")) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", badPin)
        // no folders yet: the folder resync needs the network
        val pin = callback<Pair<Int, String?>> { d -> c.setPinnedChats(listOf("1")) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "NETWORK", pin)
        val feed = callback<Pair<Int, String?>> { d -> c.loadStoriesFeed { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "NETWORK", feed)
        val owner = callback<Pair<IosOwnerStories?, String?>> { d -> c.loadOwnerStories("x", 0) { o, k, _ -> d.complete(o to k) } }
        assertEquals(null to "UNKNOWN", owner)
        val story = callback<Pair<IosPublishedStory?, String?>> { d -> c.publishStory("/nope.gif", "gif", 0, 1, { }) { p, k, _ -> d.complete(p to k) } }
        assertEquals(null to "UNKNOWN", story)
        callback<Unit> { d -> c.close { d.complete(Unit) } }
    }

    @Test
    fun throwingCallbacksAndCallsAfterCloseDoNotAbort() {
        val c = offlineClient()
        c.start { _, _, _ -> throw RuntimeException("callback bug") }
        // the scope survives a throwing callback
        assertNotNull(callback<String?> { d -> c.logout { k, _ -> d.complete(k ?: "ok") } })
        callback<Unit> { d -> c.close { d.complete(Unit) } }
        // after close every call still answers exactly once, with an error kind
        val kind = callback<String?> { d -> c.sendText("1", "late") { _, k, _ -> d.complete(k) } }
        assertNotNull(kind)
    }
}
