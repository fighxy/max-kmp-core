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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class MaxClientTest {
    private val seed = 1234567890123L
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val config = MaxClientConfig(host = "api.test", transport = quiet)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private fun client(kv: KeyValueStore, factory: ScriptedConnectionFactory, scope: CoroutineScope) =
        MaxClient(config, kv, factory, noHttp, scope)

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
    private suspend fun TestScope.smsLogin(kv: KeyValueStore, factory: ScriptedConnectionFactory): MaxClient {
        val c = client(kv, factory, backgroundScope)
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

    @Test
    fun storedTokenLogsInOnStartAndReconnectUsesSyncMarkers() = runTest {
        val kv = InMemoryKeyValueStore()
        smsLogin(kv, ScriptedConnectionFactory()).disconnect()

        val factory = ScriptedConnectionFactory()
        val c = client(kv, factory, backgroundScope)
        assertTrue(c.hasStoredToken)
        val starting = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        val login = conn.answer(Opcode.LOGIN, loginReply(null))!!
        assertEquals("login-2", login["token"])
        assertEquals(1700L, (login["chatsSync"] as Number).toLong())
        assertEquals("cfg-1", login["configHash"])
        assertEquals(ClientState.Ready(5), starting.await())
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
