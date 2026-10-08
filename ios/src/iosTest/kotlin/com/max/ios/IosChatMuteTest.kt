package com.max.ios

import com.max.core.api.AccountConfig
import com.max.core.api.AccountConfigUpdate
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.ok
import com.max.core.transport.push
import com.max.shared.CredentialStore
import com.max.shared.InMemoryKeyValueStore
import com.max.shared.MaxClient
import com.max.shared.MaxClientConfig
import com.max.shared.StoredCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `IosChat.muted` codes, `isChatMuted` and the `chatMute` / `config` events of the bridge. */
class IosChatMuteTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private val full = AccountConfig(
        chats = mapOf("7" to mapOf("dontDisturbUntil" to -1L), "8" to mapOf("dontDisturbUntil" to 5_000L)),
        chatsKnown = true,
    )

    @Test
    fun muteCodesKeepUnknownApartFromSoundOn() {
        assertEquals(-1, muteCode(null, 7, 0))
        assertEquals(1, muteCode(full, 7, 0))
        assertEquals(1, muteCode(full, 8, 4_999))
        assertEquals(0, muteCode(full, 8, 5_000)) // the timed mute ran out
        assertEquals(0, muteCode(full, 9, 0)) // full section, no entry: sound on
        // a config without the full chats section does not know chat 9
        val partial = full.copy(chatsKnown = false)
        assertEquals(1, muteCode(partial, 7, 0))
        assertEquals(-1, muteCode(partial, 9, 0))
        assertEquals(-1, muteCode(AccountConfig(), 9, 0))
        assertEquals(-1, muteCode(AccountConfig().withChatMute(7, -1), 9, 0))
    }

    @Test
    fun configChangesBecomeChatMuteEvents() {
        val next = full.mergedWith(AccountConfigUpdate(chats = mapOf("7" to mapOf("dontDisturbUntil" to 0), "9" to mapOf("dontDisturbUntil" to -1))))
        val events = configEvents(full, next, 0)
        assertEquals(listOf("chatMute" to "7", "chatMute" to "9"), events.map { it.kind to it.chatId })
        assertEquals(listOf(0, 1), events.map { it.muted })
        assertEquals(listOf(0L, -1L), events.map { it.timeMs })
        assertTrue(configEvents(full, full, 0).isEmpty())
        // login and logout: one bulk event
        assertEquals(listOf("config"), configEvents(full, null, 0).map { it.kind }.filter { it == "config" })
        assertEquals("config", configEvents(null, full, 0).last().kind)
        assertEquals("config", configEvents(full.copy(chatsKnown = false), full, 0).last().kind)
        // an entry removed from a partial config: unknown
        val partial = full.copy(chatsKnown = false)
        val dropped = configEvents(partial, partial.mergedWith(AccountConfigUpdate(chats = mapOf("7" to null))), 0).single()
        assertEquals("7", dropped.chatId)
        assertEquals(-1, dropped.muted)
    }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun Channel<IosEvent>.nextOf(kind: String): IosEvent = withTimeout(5.seconds) {
        var e = receive()
        while (e.kind != kind) e = receive()
        e
    }

    @Test
    fun pushAndMuteReachWatchEvents() = runBlocking {
        val kv = InMemoryKeyValueStore()
        CredentialStore(kv, "max.default").save(StoredCredentials("device", "instance", token = "tok", userId = 5))
        val factory = ScriptedConnectionFactory()
        val c = MaxIosClient(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { s ->
            MaxClient(MaxClientConfig(host = "api.test", transport = quiet), kv, factory, noHttp, s)
        }
        assertEquals(-1, c.isChatMuted("7"))
        val events = Channel<IosEvent>(Channel.UNLIMITED)
        val watch = c.watchEvents { events.trySend(it) }
        delay(200)
        val started = CompletableDeferred<String?>()
        c.start { phase, _, _ -> started.complete(phase) }
        val conn = withTimeout(5.seconds) {
            while (factory.lastConnection == null) delay(10)
            factory.lastConnection!!
        }
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        conn.answer(
            Opcode.LOGIN,
            mapOf(
                "profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("firstName" to "Me")))),
                "chats" to emptyList<Any>(),
                "time" to 1700L,
                "config" to mapOf("hash" to "h1", "chats" to mapOf("7" to mapOf("dontDisturbUntil" to -1))),
            ),
        )
        assertEquals("ready", withTimeout(5.seconds) { started.await() })
        events.nextOf("config")
        assertEquals(1, c.isChatMuted("7"))
        assertEquals(0, c.isChatMuted("8"))
        assertEquals(-1, c.isChatMuted("x"))
        assertEquals(-1L, c.chatMuteUntil("7"))

        // unmuted on another device
        conn.feed(push(Opcode.NOTIF_CONFIG.value, mapOf("config" to mapOf("hash" to "h2", "chats" to mapOf("7" to mapOf("dontDisturbUntil" to 0))))))
        val unmuted = events.nextOf("chatMute")
        assertEquals("7", unmuted.chatId)
        assertEquals(0, unmuted.muted)
        assertEquals(0, c.isChatMuted("7"))

        // muted here
        val done = CompletableDeferred<String?>()
        c.setChatMuted("8", true) { kind, _ -> done.complete(kind) }
        conn.answer(Opcode.CONFIG, mapOf("hash" to "h3"))
        assertNull(withTimeout(5.seconds) { done.await() })
        val muted = events.nextOf("chatMute")
        assertEquals("8", muted.chatId)
        assertEquals(1, muted.muted)
        assertEquals(-1L, muted.timeMs)
        assertEquals(1, c.isChatMuted("8"))
        watch.cancel()
        val closed = CompletableDeferred<Unit>()
        c.close { closed.complete(Unit) }
        withTimeout(5.seconds) { closed.await() }
    }
}
