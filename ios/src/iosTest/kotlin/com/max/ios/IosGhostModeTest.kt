package com.max.ios

import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.state.MaxState
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.ok
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Ghost mode, hidden read receipts and the own presence check through the bridge. */
class IosGhostModeTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    @Test
    fun switchesBecomeEventsWhateverTheAccount() {
        val base = MaxState(me = 5)
        val on = storeEvents(base, base.copy(ghostMode = true)).single()
        assertEquals("ghostMode" to "on", on.kind to on.text)
        val hidden = storeEvents(MaxState(), MaxState(hideReadReceipts = true)).single()
        assertEquals("hideReadReceipts" to "on", hidden.kind to hidden.text)
        val both = storeEvents(MaxState(ghostMode = true, hideReadReceipts = true), MaxState())
        assertEquals(listOf("ghostMode" to "off", "hideReadReceipts" to "off"), both.map { it.kind to it.text })
        assertTrue(storeEvents(base, base).isEmpty())
    }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(withTimeout(5.seconds) { takeWritten()!! })
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun FakeRawConnection.nextFrame(): Pair<Int, Any?> {
        val (header, payload) = decodePayloadPacket(withTimeout(5.seconds) { takeWritten()!! })
        return header.opcodeValue to payload
    }

    private suspend fun FakeRawConnection.assertSilent() {
        assertNull(takeWritten(300.milliseconds)?.let { Opcode.nameOf(decodePayloadPacket(it).first.opcodeValue) })
    }

    private suspend fun Channel<IosEvent>.nextOf(kind: String): IosEvent = withTimeout(5.seconds) {
        var e = receive()
        while (e.kind != kind) e = receive()
        e
    }

    @Test
    fun ghostModeReadsAndOwnPresenceThroughTheBridge() = runBlocking {
        val kv = InMemoryKeyValueStore(mapOf("max.default.ghostMode" to "1"))
        CredentialStore(kv, "max.default").save(StoredCredentials("device", "instance", token = "tok", userId = 5))
        val factory = ScriptedConnectionFactory()
        val c = MaxIosClient(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { s ->
            MaxClient(MaxClientConfig(host = "api.test", transport = quiet), kv, factory, noHttp, s)
        }
        assertTrue(c.ghostMode())
        assertFalse(c.hideReadReceipts())
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
        val login = conn.answer(
            Opcode.LOGIN,
            mapOf(
                "profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("firstName" to "Me")))),
                "chats" to listOf(
                    mapOf(
                        "id" to -70, "type" to "CHAT", "status" to "ACTIVE", "newMessages" to 2,
                        "lastMessage" to mapOf("id" to 11, "time" to 1_100L, "type" to "USER", "sender" to 7, "text" to "hi"),
                    ),
                ),
                "time" to 1700L,
            ),
        )!!
        assertEquals("ready", withTimeout(5.seconds) { started.await() })
        // a saved ghost mode holds the very first LOGIN
        assertEquals(false, login["interactive"])

        // foreground and typing in ghost mode: nothing goes out, no error for the app
        c.setAppActive(true)
        val typed = CompletableDeferred<String?>()
        c.sendTyping("-70", "TEXT", "") { kind, _ -> typed.complete(kind) }
        assertNull(withTimeout(5.seconds) { typed.await() })
        conn.assertSilent()

        // off: an event and online at once
        c.setGhostMode(false)
        assertEquals("off", events.nextOf("ghostMode").text)
        assertEquals(Opcode.PING.value to mapOf("interactive" to true), conn.nextFrame())
        assertFalse(c.ghostMode())

        // hidden read receipts: read locally, nothing sent
        c.setHideReadReceipts(true)
        assertEquals("on", events.nextOf("hideReadReceipts").text)
        val read = CompletableDeferred<IosReadMark>()
        c.markReadAt("-70", "11", 0) { r, _, _ -> read.complete(r) }
        val mark = withTimeout(5.seconds) { read.await() }
        assertEquals(0 to 1_100L, mark.unread to mark.mark)
        assertEquals(1_100L, c.localReadMarkOf("-70"))
        assertEquals(0L, c.localReadMarkOf("-71"))
        conn.assertSilent()

        // own presence, asked fresh
        val own = CompletableDeferred<IosPresence?>()
        c.checkOwnPresence { p, _, _ -> own.complete(p) }
        val asked = conn.answer(Opcode.CONTACT_PRESENCE, mapOf("presence" to mapOf("5" to mapOf("seen" to 1_800L, "status" to 0))))!!
        assertEquals(listOf(5L), (asked["contactIds"] as List<*>).map { (it as Number).toLong() })
        val p = assertNotNull(withTimeout(5.seconds) { own.await() })
        assertEquals("5", p.userId)
        assertEquals(1_800_000L, p.seenMs)
        val none = CompletableDeferred<Pair<IosPresence?, String?>>()
        c.checkOwnPresence { q, kind, _ -> none.complete(q to kind) }
        conn.answer(Opcode.CONTACT_PRESENCE, mapOf("presence" to emptyMap<String, Any?>()))
        assertEquals(null to null, withTimeout(5.seconds) { none.await() })

        // privacy through the generic setter: checked, sent, cached
        val privacy = CompletableDeferred<IosAccountSettings?>()
        c.setPrivacy("search_by_phone", "contacts") { settings, _, _ -> privacy.complete(settings) }
        val sentPrivacy = conn.answer(Opcode.CONFIG, mapOf("hash" to "cfg-2"))!!
        assertEquals(mapOf("settings" to mapOf("user" to mapOf("SEARCH_BY_PHONE" to "CONTACTS"))), sentPrivacy)
        assertEquals("CONTACTS", assertNotNull(withTimeout(5.seconds) { privacy.await() }).searchByPhone)
        assertEquals("CONTACTS", c.accountSettings().searchByPhone)
        val readOnly = CompletableDeferred<String?>()
        c.setPrivacyFlag("SHOW_READ_MARK", false) { _, kind, _ -> readOnly.complete(kind) }
        assertNotNull(withTimeout(5.seconds) { readOnly.await() })
        assertTrue(c.isPrivacyReadOnly("SHOW_READ_MARK"))
        assertFalse(c.isPrivacyReadOnly("HIDDEN"))
        conn.assertSilent()

        watch.cancel()
        val closed = CompletableDeferred<Unit>()
        c.close { closed.complete(Unit) }
        withTimeout(5.seconds) { closed.await() }
    }
}
