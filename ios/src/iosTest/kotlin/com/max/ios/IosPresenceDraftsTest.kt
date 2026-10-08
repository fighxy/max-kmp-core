package com.max.ios

import com.max.core.api.ChatMember
import com.max.core.api.ChatMemberEntry
import com.max.core.api.ChatRoles
import com.max.core.api.MaxDraft
import com.max.core.api.PresenceInfo
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.state.MaxState
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `presence` and `draft` events, presence codes of members and contacts, and name settings of the bridge. */
class IosPresenceDraftsTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    @Test
    fun storeDiffsBecomePresenceAndDraftEvents() {
        val base = MaxState(me = 5)
        val withPresence = base.copy(presence = mapOf(7L to PresenceInfo(1_700L, 2), 8L to PresenceInfo(null, 3)))
        val events = storeEvents(base, withPresence)
        assertEquals(listOf("presence" to "7", "presence" to "8"), events.map { it.kind to it.authorId })
        assertEquals(listOf(2, 3), events.map { it.presence })
        assertEquals(listOf(1_700_000L, 0L), events.map { it.timeMs })
        // unchanged entries are not repeated
        val one = storeEvents(withPresence, withPresence.copy(presence = withPresence.presence + (7L to PresenceInfo(1_800L, 1))))
        assertEquals(listOf(1), one.map { it.presence })

        val draft = MaxDraft(-70, "", emptyList(), 33, 900)
        val saved = storeEvents(base, base.copy(drafts = mapOf(-70L to draft))).single()
        assertEquals("draft", saved.kind)
        assertEquals("-70", saved.chatId)
        assertEquals("33", saved.messageId)
        assertEquals(900L, saved.timeMs)
        assertEquals("33", saved.draft?.replyTo)
        assertEquals(-1, saved.presence)
        val gone = storeEvents(base.copy(drafts = mapOf(-70L to draft)), base).single()
        assertNull(gone.draft)
        assertEquals(0L, gone.timeMs)
        // a discard carries the discard mark's time, with or without a draft before
        val discarded = storeEvents(base.copy(drafts = mapOf(-70L to draft)), base.copy(draftDiscards = mapOf(-70L to 950L))).single()
        assertNull(discarded.draft)
        assertEquals(950L, discarded.timeMs)
        val marked = storeEvents(base, base.copy(draftDiscards = mapOf(-71L to 1_200L))).single()
        assertEquals("-71" to 1_200L, marked.chatId to marked.timeMs)
        assertNull(marked.draft)
        // a later draft replacing the mark: one event with the draft
        val back = storeEvents(base.copy(draftDiscards = mapOf(-70L to 950L)), base.copy(drafts = mapOf(-70L to draft.copy(updateTime = 1_000)))).single()
        assertEquals(1_000L, back.draft?.updateTime)

        // logout and another account: no flood of events
        assertTrue(storeEvents(withPresence, MaxState()).isEmpty())
        assertTrue(storeEvents(withPresence, withPresence.copy(me = 6, presence = mapOf(9L to PresenceInfo(1, 1)))).isEmpty())
        // the first login reports
        assertEquals(2, storeEvents(MaxState(), withPresence).size)
    }

    @Test
    fun membersCarryTheFullPresenceCode() {
        fun entry(id: Long, presence: Map<String, Any?>?) =
            ChatMemberEntry.of(ChatMember(id, mapOf("id" to id, "names" to listOf(mapOf("name" to "U$id"))), presence, emptyMap<Any?, Any?>()), ChatRoles.NONE)
        val state = MaxState(me = 5)
        val recently = groupMemberSnapshot(entry(7, mapOf("seen" to 1_700L, "status" to 2)), state)!!
        assertEquals(2, recently.presence)
        assertFalse(recently.online)
        assertEquals(1_700_000L, recently.lastSeenMs)
        assertEquals(3, groupMemberSnapshot(entry(8, mapOf("status" to 3)), state)!!.presence)
        assertEquals(-1, groupMemberSnapshot(entry(9, null), state)!!.presence)
        // an expired "online" of the store reads as offline
        val stale = state.copy(presence = mapOf(10L to PresenceInfo(1_000L, 1)), presenceTimes = mapOf(10L to 1_000_000L))
        val m = groupMemberSnapshot(entry(10, null), stale, nowMs = 1_000_000L + 300_001L, ttlMs = 300_000L)!!
        assertEquals(0, m.presence)
        assertFalse(m.online)
        assertEquals(1_000_000L, m.lastSeenMs)
        assertEquals(1, groupMemberSnapshot(entry(10, null), stale, nowMs = 1_000_000L + 1, ttlMs = 300_000L)!!.presence)
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
    fun presenceDraftsAndNamesThroughTheBridge() = runBlocking {
        val kv = InMemoryKeyValueStore()
        CredentialStore(kv, "max.default").save(StoredCredentials("device", "instance", token = "tok", userId = 5))
        val factory = ScriptedConnectionFactory()
        val c = MaxIosClient(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { s ->
            MaxClient(MaxClientConfig(host = "api.test", transport = quiet), kv, factory, noHttp, s)
        }
        val diagnostics = Channel<String>(Channel.UNLIMITED)
        IosDiagnostics.installDiagnosticLogger { diagnostics.trySend(it) }
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
                "chats" to listOf(mapOf("id" to -70, "type" to "CHAT", "status" to "ACTIVE")),
                "contacts" to listOf(
                    mapOf("id" to 7, "phone" to 79131234567L, "names" to listOf(mapOf("type" to "ONEME", "name" to "Анна"), mapOf("type" to "CUSTOM", "firstName" to "Аня"))),
                ),
                "presence" to mapOf("7" to mapOf("seen" to 1_700L, "status" to 1)),
                "time" to 1700L,
            ),
        )
        assertEquals("ready", withTimeout(5.seconds) { started.await() })
        val online = events.nextOf("presence")
        assertEquals("7", online.authorId)
        assertEquals(1, online.presence)
        assertEquals(1, c.presenceOf("7").status)
        assertEquals(-1, c.presenceOf("8").status)

        // "recently" from a push is no longer lost
        conn.feed(push(Opcode.NOTIF_PRESENCE.value, mapOf("userId" to 7, "presence" to mapOf("status" to 2))))
        val recently = events.nextOf("presence")
        assertEquals(2, recently.presence)
        assertEquals(1_700_000L, recently.timeMs) // the time stays

        // a draft saved on another device
        conn.feed(push(Opcode.NOTIF_DRAFT.value, mapOf("chatId" to -70, "draft" to mapOf("text" to "с телефона", "time" to 5_000L))))
        val draft = events.nextOf("draft")
        assertEquals("-70", draft.chatId)
        assertEquals("с телефона", assertNotNull(draft.draft).text)
        assertEquals(1, c.drafts().size)
        val logged = withTimeout(5.seconds) { diagnostics.receive() }
        assertEquals("DIAG push 152 -> DraftSaved | {\"chatId\":-70,\"draft\":{\"text\":\"с …(len=10)\",\"time\":5000}}", logged)
        // a local draft older than the server's loses, an equal one stays
        assertEquals("с телефона", c.reconcileDraft("-70", "мой", "", "", 4_000L)?.text)
        assertEquals("мой", c.reconcileDraft("-70", "мой", "", "", 5_000L)?.text)
        conn.feed(push(Opcode.NOTIF_DRAFT_DISCARD.value, mapOf("chatId" to -70, "time" to 5_000L)))
        val discarded = events.nextOf("draft")
        assertNull(discarded.draft)
        assertEquals(5_000L, discarded.timeMs)
        assertEquals(5_000L, c.draftDiscardedAt("-70"))
        assertEquals(0L, c.draftDiscardedAt("-71"))
        assertEquals("DIAG push 153 -> DraftDiscarded | {\"chatId\":-70,\"time\":5000}", withTimeout(5.seconds) { diagnostics.receive() })
        // the discard clears a local draft not newer than it (fixture discard-newer-clears)
        assertNull(c.reconcileDraft("-70", "мой", "", "", 4_000L))
        assertEquals("мой", c.reconcileDraft("-70", "мой", "", "", 5_001L)?.text)
        IosDiagnostics.installDiagnosticLogger { }

        // loadPresence
        val loaded = CompletableDeferred<List<IosPresence>?>()
        c.loadPresence(listOf("7", "x", "8")) { list, _, _ -> loaded.complete(list) }
        val asked = conn.answer(Opcode.CONTACT_PRESENCE, mapOf("presence" to mapOf("7" to mapOf("seen" to 1_800L))))!!
        assertEquals(listOf(7L, 8L), (asked["contactIds"] as List<*>).map { (it as Number).toLong() })
        val list = assertNotNull(withTimeout(5.seconds) { loaded.await() })
        assertEquals(listOf("7" to 0, "8" to 3), list.map { it.userId to it.status })
        assertEquals(1_800_000L, list[0].seenMs)

        // names: the address book first by default, the own rename when switched
        c.setAddressBook(listOf(IosPhoneContact("8 913 123-45-67", "Мама")))
        assertTrue(c.preferAddressBookNames())
        assertEquals("Мама", c.displayName("7"))
        c.setPreferAddressBookNames(false)
        assertFalse(c.preferAddressBookNames())
        assertEquals("Аня", c.displayName("7"))

        // rename with an empty first name goes out
        val renamed = CompletableDeferred<String?>()
        c.renameContact("7", "", "Петрова") { _, kind, _ -> renamed.complete(kind) }
        val sent = conn.answer(Opcode.CONTACT_UPDATE, mapOf("contact" to mapOf("id" to 7, "names" to listOf(mapOf("type" to "ONEME", "name" to "Анна")))))!!
        assertEquals("", sent["firstName"])
        assertEquals("Петрова", sent["lastName"])
        assertNull(withTimeout(5.seconds) { renamed.await() })

        // background: one PING with interactive false
        c.setAppActive(false)
        val (h, ping) = decodePayloadPacket(withTimeout(5.seconds) { conn.takeWritten()!! })
        assertEquals(Opcode.PING.value, h.opcodeValue)
        assertEquals(mapOf("interactive" to false), ping)

        watch.cancel()
        val closed = CompletableDeferred<Unit>()
        c.close { closed.complete(Unit) }
        withTimeout(5.seconds) { closed.await() }
    }
}
