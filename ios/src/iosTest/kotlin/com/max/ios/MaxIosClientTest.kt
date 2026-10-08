package com.max.ios

import com.max.core.calls.CallSignaling
import com.max.core.calls.ConversationParams
import com.max.core.events.MaxEvent
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.transport.ConnectionClosedException
import com.max.core.transport.ConnectionFactory
import com.max.core.session.UserAgentInfo
import com.max.core.state.MaxState
import com.max.core.transport.TransportConfig
import com.max.shared.CredentialStore
import com.max.shared.InMemoryKeyValueStore
import com.max.shared.MaxClient
import com.max.shared.MaxClientConfig
import com.max.shared.StoredCredentials
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
        val readAt = callback<Pair<Long, String?>> { d -> c.markReadAt("1", "2", 1_700_000_000_000) { r, k, _ -> d.complete(r.mark to k) } }
        assertEquals(0L to "NETWORK", readAt)
        val badReadAt = callback<String?> { d -> c.markReadAt("x", "2", 0) { _, k, _ -> d.complete(k) } }
        assertEquals("UNKNOWN", badReadAt)
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
    fun callsWaitForAReconnectingSessionThenFailAsBefore() {
        val kv = InMemoryKeyValueStore()
        CredentialStore(kv, "max.default").save(StoredCredentials("device", "instance", token = "tok", userId = 5))
        val retrying = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = true)
        val c = MaxIosClient(scope(), sessionWaitMs = 600) { s ->
            MaxClient(MaxClientConfig(host = "api.test", transport = retrying), kv, offline, noHttp, s)
        }
        callback<String?> { d -> c.start { p, _, _ -> d.complete(p) } }
        assertTrue(c.phaseName() in setOf("reconnecting", "connecting"), c.phaseName())
        val started = TimeSource.Monotonic.markNow()
        val read = callback<String?> { d -> c.markRead("1", "2") { k, _ -> d.complete(k) } }
        // The call waited for the session, then ran and failed the old way.
        assertTrue(started.elapsedNow() >= 550.milliseconds, "waited ${started.elapsedNow()}")
        assertEquals("NETWORK", read)
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

    @Test
    fun callsAnswerWithErrorKindsOffline() {
        val c = offlineClient()
        val badCallee = callback<Pair<IosCallStart?, String?>> { d -> c.startCall("x", false) { s, k, _ -> d.complete(s to k) } }
        assertEquals(null to "UNKNOWN", badCallee)
        val call = callback<Pair<IosCallStart?, String?>> { d -> c.startCall("5", true) { s, k, _ -> d.complete(s to k) } }
        assertEquals(null to "NETWORK", call)
        val notALink = callback<Pair<IosCallStart?, String?>> { d -> c.joinCall("https://example.com/a b", false) { s, k, _ -> d.complete(s to k) } }
        assertEquals(null to "UNKNOWN", notALink)
        val link = callback<Pair<IosCallLink?, String?>> { d -> c.createCallLink { l, k, _ -> d.complete(l to k) } }
        assertEquals(null to "NETWORK", link)
        val badDelete = callback<String?> { d -> c.deleteCallHistory(listOf("x")) { k, _ -> d.complete(k) } }
        assertEquals("UNKNOWN", badDelete)
        val delete = callback<String?> { d -> c.deleteCallHistory(listOf("1")) { k, _ -> d.complete(k) } }
        assertEquals("NETWORK", delete)
        c.watchIncomingCalls { }.cancel()
        callback<Unit> { d -> c.close { d.complete(Unit) } }
    }

    @Test
    fun incomingCallCarriesTheSignalingAddressAndIceServers() {
        val params = ConversationParams(
            token = "tok",
            wsEndpoint = "wss://sig.test/ws",
            stun = "stun:s.test:3478",
            turn = listOf("turn:t.test:3478?transport=udp", "turn:t.test:443?transport=tcp"),
            turnUser = "1700000000:77",
            turnPassword = "pw",
            expiresAt = 1_700_000_100,
        )
        val event = MaxEvent.CallStart(
            callerId = 5, conversationId = "conv", type = "VIDEO", chatId = 9, isContact = true,
            vcp = null, params = params, opcode = 137, raw = null,
        )
        val call = incomingCallSnapshot(event, params, MaxState(), UserAgentInfo())
        assertEquals("conv", call.conversationId)
        assertEquals("5", call.callerId)
        assertEquals("", call.callerName)
        assertEquals("9", call.chatId)
        assertTrue(call.isVideo)
        assertEquals(77L, call.callsUserId)
        assertEquals(
            "wss://sig.test/ws?userId=77&entityType=USER&conversationId=conv&token=tok&version=5&capabilities=3c02f" +
                "&device=Google%2FPixel%208&platform=ANDROID&clientType=ONE_ME&appVersion=sdk-0.2.1.3&osVersion=34",
            call.ws2Url,
        )
        assertEquals(listOf("stun:s.test:3478"), call.stunUrls)
        assertEquals(2, call.turnUrls.size)
        assertEquals("1700000000:77", call.turnUsername)
        assertEquals("pw", call.turnPassword)
        assertEquals(1_700_000_100_000L, call.expiresAtMs)
    }

    @Test
    fun startedCallOpensTheEndpointAsTheCallSdk() {
        val signal = CallSignaling("conv", "wss://sig.test/ws?userId=3&token=t", callsUserId = 3, peerExternalId = 20, isVideo = false)
        val start = callStart(signal, UserAgentInfo(), joinLink = "")
        assertEquals("conv", start.conversationId)
        assertEquals(3L, start.callsUserId)
        assertEquals(20L, start.peerCallsUserId)
        assertTrue(start.ws2Url.startsWith("wss://sig.test/ws?userId=3&token=t&platform=ANDROID&version=5&capabilities=3c02f"))
        assertTrue(start.ws2Url.endsWith("&tgt=start"))
    }
}
