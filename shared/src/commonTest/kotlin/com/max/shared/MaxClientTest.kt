@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.auth.VerifyResult
import com.max.core.events.MaxEvent
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.session.UserAgentInfo
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import com.max.core.transport.push
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

class MaxClientTest {
    private val seed = 1234567890123L
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val config = MaxClientConfig(host = "api.test", transport = quiet)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private fun client(kv: KeyValueStore, factory: ScriptedConnectionFactory, scope: CoroutineScope, cfg: MaxClientConfig = config) =
        MaxClient(cfg, kv, factory, noHttp, scope)

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun FakeRawConnection.fail(opcode: Opcode, reply: Any?) {
        val (header, _) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue)
        feed(errorReply(header.seq, opcode.value, reply))
    }

    private fun loginReply(token: String?) = mapOf(
        "profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("name" to "Me")))),
        "chats" to listOf(mapOf("id" to 100, "type" to "DIALOG", "status" to "ACTIVE", "owner" to 5, "lastEventTime" to 10)),
        "time" to 1700L,
        "config" to mapOf("hash" to "cfg-1"),
    ) + (if (token != null) mapOf("token" to token) else emptyMap())

    /** Runs the SMS flow on a fresh client; returns it logged in. */
    private suspend fun TestScope.smsLogin(kv: KeyValueStore, factory: ScriptedConnectionFactory, cfg: MaxClientConfig = config): MaxClient {
        val c = client(kv, factory, backgroundScope, cfg)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        val hs = conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))!!
        // the handshake always carries the Android profile
        assertEquals("ANDROID", (hs["userAgent"] as Map<*, *>)["deviceType"])
        assertEquals("Pixel 8", (hs["userAgent"] as Map<*, *>)["deviceName"])
        assertIs<ClientState.AwaitingAuth>(starting.await())

        val code = async { c.requestCode("+79990000000") }
        runCurrent()
        assertEquals("+79990000000", conn.answer(Opcode.AUTH_REQUEST, mapOf("token" to "tmp", "codeLength" to 6))!!["phone"])
        assertEquals("tmp", code.await().token)

        val verify = async { c.verifyCode("tmp", "123456") }
        runCurrent()
        conn.answer(Opcode.AUTH, mapOf("tokenAttrs" to mapOf("LOGIN" to mapOf("token" to "login-1"))))
        runCurrent()
        assertEquals("login-1", conn.answer(Opcode.LOGIN, loginReply("login-2"))!!["token"])
        assertIs<VerifyResult.LoggedIn>(verify.await())
        runCurrent()
        return c
    }

    @Test
    fun smsFlowLogsInPersistsAndFeedsTheStore() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(kv, factory)
        assertEquals(ClientState.Ready(5), c.state.value)
        assertEquals(5L, c.userId.value)
        val saved = CredentialStore(kv, "max.default").load()!!
        assertEquals("login-2", saved.token) // refreshed token from the LOGIN reply
        assertEquals(1700L, saved.sync.chatsSync)
        assertEquals("cfg-1", saved.sync.configHash)
        assertEquals(c.device.deviceId, saved.deviceId)
        assertEquals(setOf(100L), c.store.state.value.chats.keys)
        assertEquals("Me", c.store.state.value.users.getValue(5).displayName)

        // pushes reach the store and router handlers
        val seen = ArrayList<MaxEvent>()
        c.router.on<MaxEvent.NewMessage> { seen += it }
        val conn = factory.lastConnection!!
        conn.feed(push(Opcode.NOTIF_MESSAGE.value, mapOf("chatId" to 100, "message" to mapOf("id" to 1, "time" to 20, "type" to "USER", "sender" to 7, "text" to "yo"))))
        runCurrent()
        assertEquals(1, seen.size)
        assertEquals("yo", c.store.state.value.chats.getValue(100).lastMessage!!.text)
        assertEquals(1, c.store.state.value.chats.getValue(100).newMessages)

        // raw Session access
        val raw = async { c.request(Opcode.CHAT_INFO.value, DefaultMessagePackCodec.encode(mapOf("chatIds" to listOf(100)))) }
        runCurrent()
        assertEquals(listOf(100L), (conn.answer(Opcode.CHAT_INFO, mapOf("chats" to emptyList<Any>()))!!["chatIds"] as List<*>).map { (it as Number).toLong() })
        assertEquals(mapOf("chats" to emptyList<Any>()), DefaultMessagePackCodec.decode(raw.await()))

        // logout: LOGOUT sent, token cleared, identity kept, store cleared
        val out = async { c.logout() }
        runCurrent()
        conn.answer(Opcode.LOGOUT, null)
        out.await()
        runCurrent()
        assertEquals(ClientState.Idle, c.state.value)
        val after = CredentialStore(kv, "max.default").load()!!
        assertNull(after.token)
        assertEquals(saved.deviceId, after.deviceId)
        assertTrue(c.store.state.value.chats.isEmpty())
        assertTrue(!c.hasStoredToken)
    }

    /** Fake server: markers > 0 get only the delta (nothing changed here), -1 gets everything. */
    private fun syncAwareLogin(request: Map<*, *>): Map<String, Any?> {
        val delta = ((request["chatsSync"] as Number).toLong()) > 0
        val contacts = listOf(mapOf("id" to 7, "names" to listOf(mapOf("name" to "Ann"))))
        return if (delta) loginReply(null) - "chats" + ("chats" to emptyList<Any>())
        else loginReply(null) + ("contacts" to contacts)
    }

    @Test
    fun storedTokenLogsInOnStartWithFullSnapshotAndReconnectUsesSyncMarkers() = runTest {
        val kv = InMemoryKeyValueStore()
        smsLogin(kv, ScriptedConnectionFactory()).disconnect()
        assertEquals(1700L, CredentialStore(kv, "max.default").load()!!.sync.chatsSync)

        // client B, same storage, empty in-memory store
        val factory = ScriptedConnectionFactory()
        val c = client(kv, factory, backgroundScope)
        assertTrue(c.hasStoredToken)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        val (header, payload) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(Opcode.LOGIN.value, header.opcodeValue)
        val login = payload as Map<*, *>
        assertEquals("login-2", login["token"])
        // reset markers: the saved ones belong to client A's snapshot
        assertEquals(-1L, (login["chatsSync"] as Number).toLong())
        assertEquals(-1L, (login["contactsSync"] as Number).toLong())
        assertEquals(com.max.core.auth.DEFAULT_CONFIG_HASH, login["configHash"])
        conn.feed(ok(header.seq, Opcode.LOGIN.value, syncAwareLogin(login)))
        assertEquals(ClientState.Ready(5), starting.await())
        assertEquals(1, c.logins.value)
        // unchanged chats and contacts are there although nothing changed on the server
        assertEquals(setOf(100L), c.store.state.value.chats.keys)
        assertEquals("Ann", c.store.state.value.users.getValue(7).displayName)
        assertEquals(1700L, CredentialStore(kv, "max.default").load()!!.sync.chatsSync)

        // a reconnect of the same client keeps its snapshot and asks only for changes
        c.disconnect()
        val again = async { c.start() }
        runCurrent()
        val conn2 = factory.lastConnection!!
        conn2.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        val (h2, p2) = decodePayloadPacket(conn2.takeWritten()!!)
        val relogin = p2 as Map<*, *>
        assertEquals(1700L, (relogin["chatsSync"] as Number).toLong())
        assertEquals("cfg-1", relogin["configHash"])
        conn2.feed(ok(h2.seq, Opcode.LOGIN.value, syncAwareLogin(relogin)))
        assertEquals(ClientState.Ready(5), again.await())
        assertEquals(2, c.logins.value)
        assertEquals(setOf(100L), c.store.state.value.chats.keys)
        c.close()
    }

    @Test
    fun reloginFillsHistoryGapsAndHelpersFeedTheStore() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(kv, factory)
        val conn = factory.lastConnection!!
        fun m(id: Long, time: Long, text: String = "m$id") = mapOf("id" to id, "time" to time, "type" to "USER", "sender" to 7, "text" to text)

        val history = async { c.loadHistory(100) }
        runCurrent()
        assertEquals(100L, (conn.answer(Opcode.CHAT_HISTORY, mapOf("messages" to listOf(m(1, 5))))!!["chatId"] as Number).toLong())
        history.await()
        val sent = async { c.sendText(100, "hi") }
        runCurrent()
        conn.answer(Opcode.MSG_SEND, mapOf("chatId" to 100, "message" to m(2, 6, "hi")))
        sent.await()
        assertEquals(listOf(1L, 2L), c.store.state.value.messagesOf(100).map { it.id })
        // the own message is the chat preview now; unread is unchanged
        val chat100 = c.store.state.value.chats.getValue(100)
        assertEquals(2L, chat100.lastMessage!!.id)
        assertEquals("hi", chat100.lastMessage!!.text)
        assertEquals(10L, chat100.lastEventTime) // max(10, message time 6)
        assertEquals(0, chat100.newMessages)

        // reconnect: LOGIN reports lastMessage 4, messages 3..4 were missed
        c.disconnect()
        val again = async { c.start() }
        runCurrent()
        val conn2 = factory.lastConnection!!
        conn2.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        val chat = mapOf("id" to 100, "type" to "DIALOG", "status" to "ACTIVE", "owner" to 5, "lastEventTime" to 30, "lastMessage" to m(4, 30))
        conn2.answer(Opcode.LOGIN, loginReply(null) + mapOf("chats" to listOf(chat)))
        assertEquals(ClientState.Ready(5), again.await())
        runCurrent()
        assertEquals(listOf(100L), c.store.state.value.historyGaps())
        val fill = conn2.answer(Opcode.CHAT_HISTORY, mapOf("messages" to listOf(m(3, 20), m(4, 30))))!!
        assertEquals(100L, (fill["chatId"] as Number).toLong())
        assertEquals(40, (fill["backward"] as Number).toInt())
        runCurrent()
        // messages 3..4 do not reach the local tail (id 2); the hole stays open
        assertEquals(listOf(100L), c.store.state.value.historyGaps())
        val older = conn2.answer(Opcode.CHAT_HISTORY, mapOf("messages" to listOf(m(2, 6), m(3, 20))))!!
        assertEquals(100L, (older["chatId"] as Number).toLong())
        assertEquals(20L, (older["from"] as Number).toLong())
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 4L), c.store.state.value.messagesOf(100).map { it.id })
        assertTrue(c.store.state.value.historyGaps().isEmpty())
    }

    @Test
    fun failedFirstStartReportsReconnectingAndLogsInOnceARetryGetsThrough() = runTest {
        val kv = InMemoryKeyValueStore()
        smsLogin(kv, ScriptedConnectionFactory()).disconnect()
        val scripted = ScriptedConnectionFactory()
        var failures = 1
        val factory = com.max.core.transport.ConnectionFactory { host, port, tls, proxy ->
            if (failures-- > 0) throw com.max.core.transport.ConnectionClosedException("no network")
            scripted.open(host, port, tls, proxy)
        }
        val c = MaxClient(config.copy(transport = quiet.copy(autoReconnect = true)), kv, factory, noHttp, backgroundScope)
        val started = c.start()
        assertIs<ClientState.Reconnecting>(started)
        assertIs<com.max.core.transport.ConnectionClosedException>(started.lastError)
        advanceTimeBy(3_301)
        runCurrent()
        val conn = scripted.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        conn.answer(Opcode.LOGIN, loginReply(null))
        runCurrent()
        assertEquals(ClientState.Ready(5), c.state.value)
        c.close()
    }

    @Test
    fun reloginWithoutGapFillSendsNoHistoryRequests() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(kv, factory, config.copy(fillGapsOnReconnect = false))
        val conn = factory.lastConnection!!
        fun m(id: Long, time: Long) = mapOf("id" to id, "time" to time, "type" to "USER", "sender" to 7, "text" to "m$id")
        val history = async { c.loadHistory(100) }
        runCurrent()
        conn.answer(Opcode.CHAT_HISTORY, mapOf("messages" to listOf(m(1, 5))))
        history.await()

        c.disconnect()
        val again = async { c.start() }
        runCurrent()
        val conn2 = factory.lastConnection!!
        conn2.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        val chat = mapOf("id" to 100, "type" to "DIALOG", "status" to "ACTIVE", "owner" to 5, "lastEventTime" to 30, "lastMessage" to m(4, 30))
        conn2.answer(Opcode.LOGIN, loginReply(null) + mapOf("chats" to listOf(chat)))
        assertEquals(ClientState.Ready(5), again.await())
        runCurrent()
        // the hole is known, but nothing pages through it on its own
        assertEquals(listOf(100L), c.store.state.value.historyGaps())
        assertNull(conn2.takeWritten())
        assertEquals(2, c.logins.value)
        c.close()
    }

    /** Parks dispatched continuations while closed, so a reply can be received but not yet processed. */
    private class Gate(private val target: CoroutineDispatcher) : CoroutineDispatcher() {
        private var closed = false
        private val parked = ArrayList<Pair<CoroutineContext, Runnable>>()
        fun close() {
            closed = true
        }
        fun release() {
            closed = false
            val queued = parked.toList()
            parked.clear()
            queued.forEach { (context, block) -> target.dispatch(context, block) }
        }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (closed) parked += context to block else target.dispatch(context, block)
        }
    }

    /** SMS flow on an existing, logged-out [c] as user [id] with login token [token]. */
    private suspend fun TestScope.loginAgain(c: MaxClient, factory: ScriptedConnectionFactory, id: Int, token: String) {
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        assertIs<ClientState.AwaitingAuth>(starting.await())
        val code = async { c.requestCode("+79990000001") }
        runCurrent()
        conn.answer(Opcode.AUTH_REQUEST, mapOf("token" to "tmp-b", "codeLength" to 6))
        code.await()
        val verify = async { c.verifyCode("tmp-b", "654321") }
        runCurrent()
        conn.answer(Opcode.AUTH, mapOf("tokenAttrs" to mapOf("LOGIN" to mapOf("token" to token))))
        runCurrent()
        val reply = mapOf(
            "profile" to mapOf("contact" to mapOf("id" to id, "names" to listOf(mapOf("name" to "B")))),
            "chats" to listOf(mapOf("id" to 300, "type" to "DIALOG", "status" to "ACTIVE", "owner" to id, "lastEventTime" to 10)),
            "time" to 2800L,
        )
        conn.answer(Opcode.LOGIN, reply)
        assertIs<VerifyResult.LoggedIn>(verify.await())
        runCurrent()
    }

    @Test
    fun lateRepliesOfAFinishedSessionDoNotTouchTheNextAccount() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(kv, factory)
        val connA = factory.lastConnection!!
        val gate = Gate(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        fun m(id: Long) = mapOf("id" to id, "time" to 50L, "type" to "USER", "sender" to 5, "text" to "late")

        val chats = async(gate) { runCatching { c.loadChats() } }
        val sessions = async(gate) { runCatching { c.closeOtherSessions() } }
        val sent = async(gate) { runCatching { c.sendText(100, "late") } }
        val profile = async(gate) { runCatching { c.updateProfile("Stale") } }
        runCurrent()
        // A's replies arrive, but their continuations do not run yet
        gate.close()
        connA.answer(Opcode.CHATS_LIST, mapOf("chats" to listOf(mapOf("id" to 999, "type" to "CHAT", "status" to "ACTIVE", "lastEventTime" to 1))))
        connA.answer(Opcode.SESSIONS_CLOSE, mapOf("token" to "stale-token"))
        connA.answer(Opcode.MSG_SEND, mapOf("chatId" to 100, "message" to m(77)))
        connA.answer(Opcode.PROFILE, mapOf("profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("name" to "Stale"))))))
        runCurrent()

        // logout A, log in as B
        val out = async { c.logout() }
        runCurrent()
        connA.answer(Opcode.LOGOUT, null)
        out.await()
        loginAgain(c, factory, id = 6, token = "login-b")
        val before = c.store.state.value
        assertEquals(6L, before.me)

        // A's continuations run now: every one fails and nothing of B changes
        gate.release()
        runCurrent()
        for (r in listOf(chats.await(), sessions.await(), sent.await(), profile.await())) {
            assertIs<com.max.core.session.SessionClosedException>(r.exceptionOrNull())
        }
        assertEquals(before, c.store.state.value)
        assertTrue(999L !in c.store.state.value.chats)
        assertTrue(5L !in c.store.state.value.users)
        val saved = CredentialStore(kv, "max.default").load()!!
        assertEquals("login-b", saved.token)
        assertEquals(6L, saved.userId)
        assertEquals(ClientState.Ready(6), c.state.value)
        c.close()
    }

    private fun newMessagePush(id: Long, chatId: Long = 100) =
        push(Opcode.NOTIF_MESSAGE.value, mapOf("chatId" to chatId, "message" to mapOf("id" to id, "time" to 20 + id, "type" to "USER", "sender" to 7, "text" to "m$id")))

    @Test
    fun logoutFromAnEventHandlerFinishesItsCleanup() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(kv, factory)
        val conn = factory.lastConnection!!
        var logouts = 0
        val seen = ArrayList<Long>()
        c.router.on<MaxEvent.NewMessage> { e ->
            seen += e.message.id
            if (e.message.text == "m1") {
                logouts++
                c.logout()
            }
        }
        conn.feed(newMessagePush(1))
        runCurrent()
        conn.answer(Opcode.LOGOUT, null)
        runCurrent()
        assertEquals(1, logouts)
        assertTrue(c.store.state.value.chats.isEmpty())
        assertNull(CredentialStore(kv, "max.default").load()!!.token)
        assertEquals(ClientState.Idle, c.state.value)
        assertEquals(com.max.core.session.SessionState.Closed, c.session.state.value)
        assertTrue(c.router.isRunning)

        // the client logs in again and the handlers keep working
        loginAgain(c, factory, id = 6, token = "login-b")
        assertEquals(ClientState.Ready(6), c.state.value)
        factory.lastConnection!!.feed(newMessagePush(2, chatId = 300))
        runCurrent()
        assertEquals(listOf(1L, 2L), seen)
        assertEquals(2L, c.store.state.value.chats.getValue(300).lastMessage!!.id)
        c.close()
    }

    @Test
    fun closeFromAnEventHandlerDisconnects() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(InMemoryKeyValueStore(), factory)
        var closed = false
        c.router.on<MaxEvent.NewMessage> {
            c.close()
            closed = true
        }
        factory.lastConnection!!.feed(newMessagePush(1))
        runCurrent()
        assertTrue(closed)
        assertEquals(com.max.core.session.SessionState.Closed, c.session.state.value)
        assertEquals(ClientState.Idle, c.state.value)
        assertTrue(!c.router.isRunning)
    }

    @Test
    fun blockedHandlerDoesNotCostTheStorePushes() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(InMemoryKeyValueStore(), factory)
        val conn = factory.lastConnection!!
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var handled = 0
        c.router.on<MaxEvent.NewMessage> {
            handled++
            if (handled == 1) release.await()
        }
        for (i in 1L..300L) conn.feed(newMessagePush(i))
        runCurrent()
        assertEquals(1, handled)
        // messageLimit is 500 by default: every one of the 300 messages is kept
        assertEquals((1L..300L).toList(), c.store.state.value.messagesOf(100).map { it.id })
        assertEquals(300L, c.store.state.value.chats.getValue(100).lastMessage!!.id)
        release.complete(Unit)
        runCurrent()
        assertEquals(300, handled)
        c.close()
    }

    @Test
    fun login2ProfileSetsTheIdentity() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val c = client(kv, factory, backgroundScope)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        starting.await()
        val verify = async { c.verifyCode("tmp", "1") }
        runCurrent()
        conn.answer(Opcode.AUTH, mapOf("tokenAttrs" to mapOf("LOGIN" to mapOf("token" to "login-1"))))
        runCurrent()
        // LOGIN without a profile asks for LOGIN2, which carries it
        conn.answer(Opcode.LOGIN, loginReply("login-2") - "profile" + ("login2Flags" to mapOf("profileEnabled" to true)))
        runCurrent()
        val payload8 = conn.answer(
            Opcode.CONTACTS_GET,
            mapOf(
                "profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("name" to "Me")))),
                "contactInfos" to listOf(mapOf("id" to 7, "names" to listOf(mapOf("name" to "Ann")))),
            ),
        )!!
        assertEquals(true, payload8["needProfile"])
        assertIs<VerifyResult.LoggedIn>(verify.await())
        runCurrent()
        assertEquals(ClientState.Ready(5), c.state.value)
        assertEquals(5L, c.userId.value)
        assertEquals(5L, c.store.state.value.me)
        assertEquals("Me", c.store.state.value.users.getValue(5).displayName)
        assertEquals("Ann", c.store.state.value.users.getValue(7).displayName)
        assertEquals(5L, CredentialStore(kv, "max.default").load()!!.userId)
        c.close()
    }

    @Test
    fun textAndMediaSendsOfOneClientNeverShareACid() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(InMemoryKeyValueStore(), factory)
        val conn = factory.lastConnection!!
        val cids = ArrayList<Long>()
        suspend fun answerSend(id: Long) {
            val payload = conn.answer(Opcode.MSG_SEND, mapOf("chatId" to 100, "message" to mapOf("id" to id, "time" to 1L, "type" to "USER", "sender" to 5, "text" to "x")))!!
            cids += ((payload["message"] as Map<*, *>)["cid"] as Number).toLong()
        }
        val photo = listOf(com.max.core.media.OutgoingAttachment.Photo("tok"))
        for (i in 0 until 3) {
            val text = async { c.sendText(100, "t$i") }
            val media = async { c.media.sendMessage(100, photo, "m$i") }
            runCurrent()
            answerSend(10L + 2 * i)
            answerSend(11L + 2 * i)
            text.await()
            media.await()
        }
        assertEquals(6, cids.toSet().size)
        assertEquals(cids.sorted(), cids)
        c.close()
    }

    @Test
    fun accountChangesUpdateStoredCredentials() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(kv, factory)
        val conn = factory.lastConnection!!
        val closing = async { c.closeOtherSessions() }
        runCurrent()
        conn.answer(Opcode.SESSIONS_CLOSE, mapOf("token" to "login-3"))
        assertTrue(closing.await())
        assertEquals("login-3", CredentialStore(kv, "max.default").load()!!.token)

        val privacy = async { c.updatePrivacy(com.max.core.api.PrivacySettings(hideOnlineStatus = true)) }
        runCurrent()
        conn.answer(Opcode.CONFIG, mapOf("hash" to "cfg-2"))
        assertEquals("cfg-2", privacy.await())
        val saved = CredentialStore(kv, "max.default").load()!!
        assertEquals("cfg-2", saved.sync.configHash)
        assertEquals("login-3", saved.token)
        assertEquals(5L, saved.userId)

        val profile = async { c.updateProfile("New") }
        runCurrent()
        conn.answer(Opcode.PROFILE, mapOf("profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("name" to "New"))))))
        profile.await()
        assertEquals("New", c.store.state.value.users.getValue(5).displayName)

        // the next reconnect logs in with the new token and config hash
        c.disconnect()
        val again = async { c.start() }
        runCurrent()
        val conn2 = factory.lastConnection!!
        conn2.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        val login = conn2.answer(Opcode.LOGIN, loginReply(null))!!
        assertEquals("login-3", login["token"])
        assertEquals("cfg-2", login["configHash"])
        assertEquals(ClientState.Ready(5), again.await())
    }

    @Test
    fun clientStateErrorsAreClassified() {
        val rejected = ClientState.TokenRejected(
            com.max.core.auth.InvalidTokenException(
                com.max.core.transport.ServerErrorException("rejected", "login.token", "FAIL_LOGIN_TOKEN", com.max.core.transport.TransportPacket(com.max.core.protocol.PacketHeader(10, 3, 1, 19, 0, false), null)),
            ),
        )
        assertEquals(com.max.core.ErrorKind.SESSION_EXPIRED, rejected.error?.kind)
        assertEquals(com.max.core.ErrorKind.NETWORK, ClientState.Reconnecting(2, com.max.core.transport.ConnectionClosedException()).error?.kind)
        assertNull(ClientState.Reconnecting(1, null).error)
        assertNull(ClientState.Idle.error)
    }

    @Test
    fun rejectedTokenIsClearedAndReported() = runTest {
        val kv = InMemoryKeyValueStore()
        smsLogin(kv, ScriptedConnectionFactory()).disconnect()
        val deviceId = CredentialStore(kv, "max.default").load()!!.deviceId

        val factory = ScriptedConnectionFactory()
        val c = client(kv, factory, backgroundScope)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        conn.fail(Opcode.LOGIN, mapOf("error" to "login.token", "message" to "FAIL_LOGIN_TOKEN"))
        assertIs<ClientState.TokenRejected>(starting.await())
        val after = CredentialStore(kv, "max.default").load()!!
        assertNull(after.token)
        assertEquals(deviceId, after.deviceId)
    }

    @Test
    fun passwordChallengeThenLogin() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = client(InMemoryKeyValueStore(), factory, backgroundScope)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        starting.await()
        val verify = async { c.verifyCode("tmp", "1") }
        runCurrent()
        conn.answer(Opcode.AUTH, mapOf("passwordChallenge" to mapOf("trackId" to "tr", "hint" to "h")))
        val r = assertIs<VerifyResult.PasswordRequired>(verify.await())
        assertIs<ClientState.AwaitingAuth>(c.state.value)
        val pw = async { c.checkPassword(r.trackId, "secret") }
        runCurrent()
        assertEquals("tr", conn.answer(Opcode.AUTH_LOGIN_CHECK_PASSWORD, mapOf("tokenAttrs" to mapOf("LOGIN" to mapOf("token" to "pw-login"))))!!["trackId"])
        runCurrent()
        assertEquals("pw-login", conn.answer(Opcode.LOGIN, loginReply(null))!!["token"])
        assertNotNull(pw.await().profile)
        runCurrent()
        assertEquals(ClientState.Ready(5), c.state.value)
    }

    @Test
    fun contactCardIsSentAsAttachmentAndRetriedWhileNotReady() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val c = smsLogin(kv, factory)
        val conn = factory.lastConnection!!
        val sent = async { c.sendContact(100, 7) }
        runCurrent()
        val (h1, p1) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(Opcode.MSG_SEND.value, h1.opcodeValue)
        val message = (p1 as Map<*, *>)["message"] as Map<*, *>
        val attach = (message["attaches"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("_type", "contactId"), attach.keys.toList())
        assertEquals("CONTACT", attach["_type"])
        assertEquals(7L, (attach["contactId"] as Number).toLong())
        assertTrue("text" !in message)
        // still processing: the same frame again a second later
        conn.feed(errorReply(h1.seq, Opcode.MSG_SEND.value, mapOf("error" to "attachment.not.ready", "message" to "not ready")))
        runCurrent()
        val before = testScheduler.currentTime
        val (h2, p2) = decodePayloadPacket(conn.takeWritten()!!)
        assertTrue(testScheduler.currentTime - before >= 1_000, "resent after a second")
        assertEquals(message["cid"], ((p2 as Map<*, *>)["message"] as Map<*, *>)["cid"])
        val card = mapOf("_type" to "CONTACT", "contactId" to 7, "name" to "Ann")
        conn.feed(ok(h2.seq, Opcode.MSG_SEND.value, mapOf("chatId" to 100, "message" to mapOf("id" to 9, "time" to 50, "type" to "USER", "sender" to 5, "attaches" to listOf(card)))))
        assertEquals(9L, sent.await().id)
        assertEquals(9L, c.store.state.value.chats.getValue(100).lastMessage!!.id)
        assertEquals(listOf(9L), c.store.state.value.messagesOf(100).map { it.id })

        // other errors are not retried
        val denied = async { runCatching { c.sendContact(100, 7) } }
        runCurrent()
        conn.fail(Opcode.MSG_SEND, mapOf("error" to "chat.denied", "message" to "denied"))
        assertTrue(denied.await().isFailure)
        runCurrent()
        assertNull(conn.takeWritten())
    }

    @Test
    fun onlyAndroidProfilesAreAccepted() {
        val ios = UserAgentInfo(deviceType = "IOS", osVersion = "iOS 18.0", deviceName = "iPhone 15", pushDeviceType = "APNS")
        assertFailsWith<IllegalArgumentException> { MaxClient(MaxClientConfig(userAgent = ios), InMemoryKeyValueStore(), ScriptedConnectionFactory(), noHttp) }
        assertFailsWith<IllegalArgumentException> { DeviceProfile.requireAndroid(UserAgentInfo(deviceName = "iPhone 15")) }
        assertFailsWith<IllegalArgumentException> { DeviceProfile.requireAndroid(UserAgentInfo(osVersion = "iOS 18")) }
        assertFailsWith<IllegalArgumentException> { DeviceProfile.requireAndroid(UserAgentInfo(pushDeviceType = "APNS")) }
        assertFailsWith<IllegalArgumentException> { DeviceProfile.requireAndroid(UserAgentInfo(deviceType = "WEB")) }
        DeviceProfile.requireAndroid(DeviceProfile.android)
        assertEquals("Pixel 8", DeviceProfile.android.deviceName)
    }
}
