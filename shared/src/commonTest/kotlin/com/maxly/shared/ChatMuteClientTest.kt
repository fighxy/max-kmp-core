@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.shared

import com.maxly.core.api.AccountConfig
import com.maxly.core.auth.DEFAULT_CONFIG_HASH
import com.maxly.core.media.HttpResponse
import com.maxly.core.media.MediaHttp
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.transport.FakeRawConnection
import com.maxly.core.transport.ScriptedConnectionFactory
import com.maxly.core.transport.TransportConfig
import com.maxly.core.transport.ok
import com.maxly.core.transport.push
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Chat mutes of [MaxClient]: reconnects, partial configs, `NOTIF_CONFIG` 134 and `CONFIG` 22. */
class ChatMuteClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private val fullConfig = mapOf(
        "hash" to "cfg-1",
        "user" to mapOf("HIDDEN" to false),
        "server" to mapOf("invite-link" to "https://max.ru/u/me"),
        "chats" to mapOf("7" to mapOf("dontDisturbUntil" to -1), "-100" to mapOf("dontDisturbUntil" to -1, "favIndex" to 1)),
    )

    private fun loginReply(config: Map<String, Any?>?) = buildMap {
        put("profile", mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("firstName" to "Me")))))
        put("chats", listOf(mapOf("id" to 7, "type" to "DIALOG", "status" to "ACTIVE", "owner" to 5, "lastEventTime" to 10)))
        put("time", 1700L)
        if (config != null) put("config", config)
    }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun TestScope.loggedIn(factory: ScriptedConnectionFactory, kv: KeyValueStore, config: Map<String, Any?>? = fullConfig): Pair<MaxClient, Map<*, *>> {
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), kv, factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        val sent = conn.answer(Opcode.LOGIN, loginReply(config))!!
        login.await()
        runCurrent()
        return c to sent
    }

    /** Disconnects, reconnects and answers the re-`LOGIN` with [config]; returns what was sent. */
    private suspend fun TestScope.reconnect(c: MaxClient, factory: ScriptedConnectionFactory, config: Map<String, Any?>?): Map<*, *> {
        c.disconnect()
        val again = async { c.start() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        val sent = conn.answer(Opcode.LOGIN, loginReply(config))!!
        again.await()
        runCurrent()
        return sent
    }

    @Test
    fun reconnectWithoutChatsKeepsTheMutes() = runTest {
        val factory = ScriptedConnectionFactory()
        val (c, first) = loggedIn(factory, InMemoryKeyValueStore())
        assertEquals(DEFAULT_CONFIG_HASH, first["configHash"])
        assertTrue(c.accountConfig.value!!.chatsKnown)
        assertEquals(true, c.isChatMuted(7))
        assertEquals(false, c.isChatMuted(8))

        // a config without `chats` (another section changed) keeps every known mute
        val sent = reconnect(c, factory, mapOf("hash" to "cfg-2", "server" to mapOf("invite-link" to "https://max.ru/u/new")))
        assertEquals("cfg-1", sent["configHash"])
        val config = c.accountConfig.value!!
        assertEquals(true, c.isChatMuted(7))
        assertEquals(true, c.isChatMuted(-100))
        assertEquals("cfg-2", config.hash)
        assertEquals("https://max.ru/u/new", config.inviteLink)
        assertEquals(false, config.userFlag("HIDDEN"))
        assertTrue(config.chatsKnown)

        // no config at all: nothing changes
        assertEquals("cfg-2", reconnect(c, factory, null)["configHash"])
        assertEquals(config, c.accountConfig.value)

        // a partial chats map is merged per chat
        reconnect(c, factory, mapOf("hash" to "cfg-3", "chats" to mapOf("8" to mapOf("dontDisturbUntil" to -1), "7" to mapOf("dontDisturbUntil" to 0))))
        assertEquals(false, c.isChatMuted(7))
        assertEquals(true, c.isChatMuted(8))
        assertEquals(true, c.isChatMuted(-100))
        assertEquals(1L, ((c.accountConfig.value!!.chats["-100"] as Map<*, *>)["favIndex"] as Number).toLong())
    }

    @Test
    fun notifConfigAppliesMutesFromAnotherDevice() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val (c, _) = loggedIn(factory, kv)
        val seen = ArrayList<AccountConfig?>()
        val watch = c.watchAccountConfig { seen += it }
        runCurrent()
        val conn = factory.lastConnection!!

        conn.feed(push(Opcode.NOTIF_CONFIG.value, mapOf("config" to mapOf("hash" to "cfg-9", "chats" to mapOf(7L to mapOf("dontDisturbUntil" to 0), 8L to mapOf("dontDisturbUntil" to -1))))))
        runCurrent()
        assertEquals(false, c.isChatMuted(7))
        assertEquals(true, c.isChatMuted(8))
        assertEquals(true, c.isChatMuted(-100))
        assertEquals("cfg-9", c.accountConfig.value!!.hash)
        assertEquals("cfg-9", CredentialStore(kv, "max.default").load()!!.sync.configHash)
        assertEquals(2, seen.size)

        // a timed mute, sections at the top level
        val until = 4_102_444_800_000L // 2100-01-01
        conn.feed(push(Opcode.NOTIF_CONFIG.value, mapOf("chats" to mapOf("7" to mapOf("dontDisturbUntil" to until)))))
        runCurrent()
        assertEquals(until, c.chatMuteUntil(7))
        assertEquals(true, c.isChatMuted(7))
        assertEquals(false, c.accountConfig.value!!.isMuted(7, nowMs = until))
        assertEquals("cfg-9", c.accountConfig.value!!.hash)

        // the next reconnect logs in with the pushed hash
        assertEquals("cfg-9", reconnect(c, factory, null)["configHash"])
        assertEquals(true, c.isChatMuted(8))
        watch.cancel()
    }

    @Test
    fun muteStoresTheConfigHash() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val (c, _) = loggedIn(factory, kv)
        val conn = factory.lastConnection!!
        val mute = async { c.setChatMuted(8, true) }
        runCurrent()
        val sent = conn.answer(Opcode.CONFIG, mapOf("hash" to "cfg-4"))!!
        assertEquals("{settings={chats={8={dontDisturbUntil=-1}}}}", sent.toString())
        mute.await()
        assertEquals(true, c.isChatMuted(8))
        assertEquals(true, c.isChatMuted(7))
        assertEquals("cfg-4", CredentialStore(kv, "max.default").load()!!.sync.configHash)
        assertEquals("cfg-4", reconnect(c, factory, null)["configHash"])
        assertEquals(true, c.isChatMuted(8))

        val timed = async { c.setChatMuteUntil(8, 4_102_444_800_000L) }
        runCurrent()
        factory.lastConnection!!.answer(Opcode.CONFIG, mapOf("hash" to "cfg-5"))
        timed.await()
        assertEquals(4_102_444_800_000L, c.chatMuteUntil(8))
    }

    @Test
    fun withoutAServerConfigOtherChatsStayUnknown() = runTest {
        val kv = InMemoryKeyValueStore()
        val factory = ScriptedConnectionFactory()
        val (c, _) = loggedIn(factory, kv, config = null)
        assertNull(c.accountConfig.value)
        assertNull(c.isChatMuted(7))
        val conn = factory.lastConnection!!

        val mute = async { c.setChatMuted(8, true) }
        runCurrent()
        conn.answer(Opcode.CONFIG, mapOf("hash" to "cfg-4"))
        mute.await()
        assertEquals(true, c.isChatMuted(8))
        assertNull(c.isChatMuted(7)) // not "sound on"
        assertNull(c.chatMuteUntil(7))
        // the hash of a config this client never had is not kept
        assertEquals(DEFAULT_CONFIG_HASH, CredentialStore(kv, "max.default").load()!!.sync.configHash)

        val settings = async { c.updateUserSettings(mapOf("HIDDEN" to true)) }
        runCurrent()
        conn.answer(Opcode.CONFIG, mapOf("hash" to "cfg-5"))
        settings.await()
        assertNull(c.isChatMuted(7))
        assertEquals(DEFAULT_CONFIG_HASH, CredentialStore(kv, "max.default").load()!!.sync.configHash)

        // a 134 push before any server config only names its chats
        conn.feed(push(Opcode.NOTIF_CONFIG.value, mapOf("chats" to mapOf("9" to mapOf("dontDisturbUntil" to 0)), "hash" to "cfg-6")))
        runCurrent()
        assertEquals(false, c.isChatMuted(9))
        assertNull(c.isChatMuted(7))
        assertEquals(DEFAULT_CONFIG_HASH, CredentialStore(kv, "max.default").load()!!.sync.configHash)

        // the next full config replaces the local one
        val sent = reconnect(c, factory, fullConfig)
        assertEquals(DEFAULT_CONFIG_HASH, sent["configHash"])
        assertTrue(c.accountConfig.value!!.chatsKnown)
        assertEquals(true, c.isChatMuted(7))
        assertEquals(false, c.isChatMuted(8))
    }

    @Test
    fun theConfigIsThereBeforeTheChats() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, backgroundScope)
        val seen = ArrayList<AccountConfig?>()
        // an unconfined collector runs at the very store update, before anything else happens
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.store.state.collect { if (it.chats.isNotEmpty()) seen += c.accountConfig.value }
        }
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(Opcode.LOGIN, loginReply(fullConfig))
        login.await()
        runCurrent()
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it != null && it.chatMuteState(7) == true }, seen.toString())
    }

    @Test
    fun logoutDropsTheConfigAndPushesAfterItAreIgnored() = runTest {
        val factory = ScriptedConnectionFactory()
        val (c, _) = loggedIn(factory, InMemoryKeyValueStore())
        val conn = factory.lastConnection!!
        val out = async { c.logout() }
        runCurrent()
        conn.answer(Opcode.LOGOUT, null)
        out.await()
        runCurrent()
        assertNull(c.accountConfig.value)
        assertNull(c.isChatMuted(7))
    }
}
