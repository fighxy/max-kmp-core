@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.api.PresenceInfo
import com.max.core.api.PresenceStatus
import com.max.core.events.MaxEvent
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import com.max.core.transport.push
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Presence, drafts and the applied-events flow through [MaxClient]. */
class PresenceDraftsClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }
    private val me = 5L
    private val peer = 6L
    private val dialog = me xor peer

    private fun Any?.long(): Long? = (this as? Number)?.toLong()

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *> {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *> ?: emptyMap<Any?, Any?>()
    }

    private suspend fun FakeRawConnection.fail(opcode: Opcode, error: String): Map<*, *> {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(errorReply(header.seq, opcode.value, mapOf("error" to error, "message" to error)))
        return payload as Map<*, *>
    }

    private fun loginReply(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "profile" to mapOf("contact" to mapOf("id" to me, "names" to listOf(mapOf("name" to "Me")))),
        "chats" to listOf(
            mapOf("id" to dialog, "type" to "DIALOG", "status" to "ACTIVE", "participants" to mapOf("$me" to 0, "$peer" to 0)),
            mapOf("id" to -70, "type" to "CHAT", "status" to "ACTIVE"),
        ),
        "time" to 1700L,
        "config" to mapOf("hash" to "cfg-1", "server" to mapOf("presence-ttl" to 60)),
    ) + extra

    private class Client(val c: MaxClient, val conn: FakeRawConnection, val factory: ScriptedConnectionFactory, val login: Map<*, *>, val kv: KeyValueStore)

    private suspend fun TestScope.loggedIn(extra: Map<String, Any?> = emptyMap(), cfg: MaxClientConfig = MaxClientConfig(host = "api.test", transport = quiet)): Client {
        val factory = ScriptedConnectionFactory()
        val kv = InMemoryKeyValueStore()
        val c = MaxClient(cfg, kv, factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        val sent = conn.answer(Opcode.LOGIN, loginReply(extra))
        login.await()
        runCurrent()
        return Client(c, conn, factory, sent, kv)
    }

    private fun msg(id: Long, chatId: Long) = mapOf("message" to mapOf("id" to id, "chatId" to chatId, "sender" to me, "time" to 2_000L + id, "type" to "USER", "text" to "t$id"))

    @Test
    fun loginPresenceIsStoredAndMovesPresenceSync() = runTest {
        val t = loggedIn(mapOf("presence" to mapOf("$peer" to mapOf("seen" to 1_700L, "status" to 1), "7" to mapOf("seen" to 1_600L, "status" to 3))))
        assertEquals(-1L, t.login["presenceSync"].long())
        assertEquals(true, t.login["interactive"])
        assertEquals(PresenceInfo(1_700L, 1), t.c.store.state.value.presence[peer])
        assertEquals(PresenceStatus.ONLINE, t.c.presenceStatusOf(peer))
        assertEquals(PresenceStatus.LONG_AGO, t.c.presenceStatusOf(7))
        assertEquals(PresenceStatus.UNKNOWN, t.c.presenceStatusOf(8))
        assertEquals(60_000L, t.c.presenceTtlMs)
        // applied: the next LOGIN may ask for the delta from the reply time
        assertEquals(1700L, CredentialStore(t.kv, "max.default").load()!!.sync.presenceSync)
    }

    @Test
    fun aLoginWithoutPresenceKeepsPresenceSync() = runTest {
        val t = loggedIn()
        assertEquals(-1L, CredentialStore(t.kv, "max.default").load()!!.sync.presenceSync)
    }

    @Test
    fun reconnectResetsOnlineAndAsksAgain() = runTest {
        val t = loggedIn(mapOf("presence" to mapOf("$peer" to mapOf("seen" to 1_700L, "status" to 1), "7" to mapOf("seen" to 1_600L, "status" to 1))))
        t.c.disconnect()
        val again = async { t.c.start() }
        runCurrent()
        val conn = t.factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        // delta: only 7 is refreshed
        val sent = conn.answer(Opcode.LOGIN, loginReply(mapOf("presence" to mapOf("7" to mapOf("status" to 1)))))
        again.await()
        assertEquals(1700L, sent["presenceSync"].long())
        assertEquals(PresenceStatus.OFFLINE, t.c.presenceStatusOf(peer))
        assertEquals(PresenceStatus.ONLINE, t.c.presenceStatusOf(7))
        runCurrent()
        // the one left behind is asked with CONTACT_PRESENCE 35
        val ask = conn.answer(Opcode.CONTACT_PRESENCE, mapOf("presence" to mapOf("$peer" to mapOf("seen" to 1_800L, "status" to 1))))
        assertEquals(listOf(peer), (ask["contactIds"] as List<*>).map { it.long() })
        runCurrent()
        assertEquals(PresenceInfo(1_800L, 1), t.c.store.state.value.presence[peer])
    }

    @Test
    fun onlineExpiresWithTheServerTtl() = runTest {
        val t = loggedIn(mapOf("presence" to mapOf("$peer" to mapOf("seen" to 1_700L, "status" to 1))))
        assertEquals(PresenceStatus.ONLINE, t.c.presenceStatusOf(peer))
        // presence-ttl 60 s, the sweep runs every 15 s (virtual time; the store clock is real)
        assertEquals(emptyList(), t.c.expirePresence())
        t.conn.feed(push(Opcode.NOTIF_PRESENCE.value, mapOf("userId" to 7, "presence" to mapOf("status" to 2))))
        runCurrent()
        assertEquals(PresenceStatus.RECENTLY, t.c.presenceStatusOf(7))
    }

    @Test
    fun loadPresenceAsksAndStores() = runTest {
        val t = loggedIn()
        assertEquals(emptyMap(), t.c.loadPresence(emptyList()))
        val r = async { t.c.loadPresence(listOf(peer, 7, peer)) }
        runCurrent()
        val sent = t.conn.answer(Opcode.CONTACT_PRESENCE, mapOf("presence" to mapOf("$peer" to mapOf("seen" to 10L, "status" to 0))))
        assertEquals(listOf(peer, 7L), (sent["contactIds"] as List<*>).map { it.long() })
        val got = r.await()
        assertEquals(PresenceInfo(10L, 0), got[peer])
        assertEquals(PresenceInfo(null, PresenceStatus.LONG_AGO), got[7L])
        assertEquals(PresenceStatus.LONG_AGO, t.c.presenceStatusOf(7))
    }

    @Test
    fun setInteractivePingsAtOnceAndGoesIntoTheNextLogin() = runTest {
        val t = loggedIn()
        assertTrue(t.c.isInteractive)
        assertTrue(t.c.setInteractive(false))
        val (h, payload) = decodePayloadPacket(t.conn.takeWritten()!!)
        assertEquals(Opcode.PING.value, h.opcodeValue)
        assertEquals(mapOf("interactive" to false), payload)
        assertFalse(t.c.isInteractive)
        assertFalse(t.c.setInteractive(false))
        // the reconnect LOGIN carries it too
        t.c.disconnect()
        val again = async { t.c.start() }
        runCurrent()
        val conn = t.factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        assertEquals(false, conn.answer(Opcode.LOGIN, loginReply())["interactive"])
        again.await()
    }

    @Test
    fun appliedEventsFireAfterStoreAndConfig() = runTest {
        val t = loggedIn()
        val checks = ArrayList<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            t.c.appliedEvents.collect { e ->
                val s = t.c.store.state.value
                checks += when (e) {
                    is MaxEvent.ConfigUpdated -> "config:" + t.c.chatMuteUntil(-70)
                    is MaxEvent.Presence -> "presence:" + s.presence[e.userId]?.status
                    is MaxEvent.DraftSaved -> "draft:" + s.drafts[-70L]?.text
                    is MaxEvent.DraftDiscarded -> "discard:" + (s.drafts[-70L] == null)
                    is MaxEvent.ContactUpdated -> "contact:" + s.users[e.user.id]?.displayName
                    else -> "other:" + e::class.simpleName
                }
            }
        }
        runCurrent()
        // one push at a time: a collector sees the state after its event (and maybe after later
        // pushes that were already applied, never before)
        for (p in listOf(
            push(Opcode.NOTIF_CONFIG.value, mapOf("chats" to mapOf("-70" to mapOf("dontDisturbUntil" to -1)))),
            push(Opcode.NOTIF_PRESENCE.value, mapOf("userId" to peer, "presence" to mapOf("status" to 1))),
            push(Opcode.NOTIF_DRAFT.value, mapOf("chatId" to -70, "draft" to mapOf("text" to "с телефона", "time" to 5_000L))),
            push(Opcode.NOTIF_DRAFT_DISCARD.value, mapOf("chatId" to -70, "time" to 5_000L)),
            push(Opcode.NOTIF_CONTACT.value, mapOf("contact" to mapOf("id" to 8, "names" to listOf(mapOf("name" to "Новое имя"))))),
        )) {
            t.conn.feed(p)
            runCurrent()
        }
        assertEquals(listOf("config:-1", "presence:1", "draft:с телефона", "discard:true", "contact:Новое имя"), checks)
    }

    @Test
    fun sendingClearsTheDraftAndDiscardsItOnceOnTheServer() = runTest {
        val t = loggedIn(
            mapOf("drafts" to mapOf("users" to mapOf("saved" to mapOf("$peer" to mapOf("text" to "", "replyTo" to 3L, "saveTime" to 900L))))),
        )
        assertEquals(3L, t.c.drafts[dialog]?.replyTo)
        val s1 = async { t.c.sendText(dialog, "hi") }
        runCurrent()
        t.conn.answer(Opcode.MSG_SEND, msg(1, dialog))
        s1.await()
        assertNull(t.c.drafts[dialog])
        assertEquals(900L, t.c.draftDiscardedAt(dialog))
        runCurrent()
        val discard = t.conn.answer(Opcode.DRAFT_DISCARD, emptyMap<String, Any?>())
        assertEquals(mapOf("userId" to peer, "time" to 900L), discard.mapKeys { it.key.toString() }.mapValues { it.value.long() })
        // a second send has no draft left: no second discard
        val s2 = async { t.c.sendText(dialog, "again") }
        runCurrent()
        t.conn.answer(Opcode.MSG_SEND, msg(2, dialog))
        s2.await()
        runCurrent()
        assertNull(t.conn.takeWritten())
    }

    @Test
    fun aFailedDiscardIsReportedNotThrown() = runTest {
        val t = loggedIn()
        val errors = ArrayList<String>()
        t.c.onBackgroundError = { what, _ -> errors += what }
        t.conn.feed(push(Opcode.NOTIF_DRAFT.value, mapOf("chatId" to -70, "draft" to mapOf("text" to "x", "time" to 1_000L))))
        runCurrent()
        assertEquals("x", t.c.drafts[-70L]?.text)
        val s = async { t.c.sendText(-70, "x") }
        runCurrent()
        t.conn.answer(Opcode.MSG_SEND, msg(3, -70))
        assertEquals(3L, s.await().id)
        runCurrent()
        assertEquals(mapOf("chatId" to -70L, "time" to 1_000L), t.conn.fail(Opcode.DRAFT_DISCARD, "boom").mapKeys { it.key.toString() }.mapValues { it.value.long() })
        runCurrent()
        assertEquals(1, errors.size)
        assertNull(t.c.drafts[-70L])
        // a sticker leaves the draft alone
        t.conn.feed(push(Opcode.NOTIF_DRAFT.value, mapOf("chatId" to -70, "draft" to mapOf("text" to "y", "time" to 2_000L))))
        runCurrent()
        val st = async { t.c.sendSticker(-70, 77) }
        runCurrent()
        t.conn.answer(Opcode.MSG_SEND, msg(4, -70))
        st.await()
        runCurrent()
        assertNull(t.conn.takeWritten())
        assertEquals("y", t.c.drafts[-70L]?.text)
    }

    @Test
    fun discardMarksReachTheClient() = runTest {
        val t = loggedIn(mapOf("drafts" to mapOf("chats" to mapOf("discarded" to mapOf("-70" to 1_500L)))))
        assertEquals(1_500L, t.c.draftDiscardedAt(-70))
        assertEquals(mapOf(-70L to 1_500L), t.c.draftDiscards)
        // the app's own draft from before the discard is cleared (fixture discard-newer-clears)
        val local = com.max.core.api.MaxDraft(-70, "мой", emptyList(), null, 1_000)
        assertNull(t.c.reconcileDraft(-70, local))
        assertEquals(local.copy(updateTime = 1_600), t.c.reconcileDraft(-70, local.copy(updateTime = 1_600)))
        // a draft not later than the mark is ignored, a later one replaces it
        t.conn.feed(push(Opcode.NOTIF_DRAFT.value, mapOf("chatId" to -70, "draft" to mapOf("text" to "секретный текст", "time" to 1_500L))))
        runCurrent()
        assertNull(t.c.drafts[-70L])
        t.conn.feed(push(Opcode.NOTIF_DRAFT.value, mapOf("chatId" to -70, "draft" to mapOf("text" to "секретный текст", "time" to 1_501L))))
        runCurrent()
        assertEquals("секретный текст", t.c.drafts[-70L]?.text)
        assertNull(t.c.draftDiscardedAt(-70))
        // an own discard sets the mark at the time sent
        val d = async { t.c.discardDraft(-70) }
        runCurrent()
        t.conn.answer(Opcode.DRAFT_DISCARD, emptyMap<String, Any?>())
        assertTrue(d.await())
        assertEquals(1_501L, t.c.draftDiscardedAt(-70))
        // an own save clears it
        val sv = async { t.c.saveDraft(-70, "снова") }
        runCurrent()
        t.conn.answer(Opcode.DRAFT_SAVE, mapOf("time" to 1_400L))
        sv.await()
        assertNull(t.c.draftDiscardedAt(-70))
        assertEquals("снова", t.c.drafts[-70L]?.text)
        // a later discard
        t.conn.feed(push(Opcode.NOTIF_DRAFT_DISCARD.value, mapOf("chatId" to -70, "time" to 2_000L)))
        runCurrent()
        assertEquals(2_000L, t.c.draftDiscardedAt(-70))
    }

    @Test
    fun preferAddressBookNamesSwitchesTheRule() = runTest {
        val t = loggedIn(
            mapOf("contacts" to listOf(mapOf("id" to peer, "phone" to 79131234567L, "names" to listOf(mapOf("type" to "ONEME", "name" to "Анна"), mapOf("type" to "CUSTOM", "firstName" to "Аня"))))),
        )
        t.c.setAddressBook(listOf(com.max.core.api.PhoneContact("8 913 123-45-67", "Мама")))
        assertTrue(t.c.preferAddressBookNames)
        assertEquals("Мама", t.c.displayName(peer))
        t.c.preferAddressBookNames = false
        assertEquals("Аня", t.c.displayName(peer))
    }
}
