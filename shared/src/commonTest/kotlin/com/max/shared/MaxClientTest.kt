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
