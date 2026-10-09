@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.shared

import com.maxly.core.api.LocalRead
import com.maxly.core.api.PresenceInfo
import com.maxly.core.api.StoryOwner
import com.maxly.core.media.HttpResponse
import com.maxly.core.media.MediaHttp
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.transport.FakeRawConnection
import com.maxly.core.transport.OutboundBlockedException
import com.maxly.core.transport.ScriptedConnectionFactory
import com.maxly.core.transport.TransportConfig
import com.maxly.core.transport.ok
import com.maxly.core.transport.push
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Ghost mode and hidden read receipts through [MaxClient] over a fake socket. */
class GhostModeClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }
    private val me = 5L
    private val other = 9L

    private fun Any?.long(): Long? = (this as? Number)?.toLong()

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *> {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *> ?: emptyMap<Any?, Any?>()
    }

    /** The next fire-and-forget frame: its opcode and payload. */
    private suspend fun FakeRawConnection.next(): Pair<Int, Map<*, *>> {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        return header.opcodeValue to (payload as? Map<*, *> ?: emptyMap<Any?, Any?>())
    }

    /** Nothing more was written. */
    private suspend fun FakeRawConnection.assertSilent() {
        val next = takeWritten(100.milliseconds)
        assertNull(next?.let { Opcode.nameOf(decodePayloadPacket(it).first.opcodeValue) }, "unexpected request")
    }

    private fun chat(newMessages: Int, lastId: Long, lastTime: Long, myMark: Long = 0L) = mapOf(
        "id" to -70, "type" to "CHAT", "status" to "ACTIVE", "newMessages" to newMessages,
        "participants" to mapOf("$me" to myMark, "$other" to lastTime),
        "lastMessage" to mapOf("id" to lastId, "time" to lastTime, "type" to "USER", "sender" to other, "text" to "m$lastId"),
    )

    private fun loginReply(chats: List<Any?>) = mapOf(
        "profile" to mapOf("contact" to mapOf("id" to me, "names" to listOf(mapOf("name" to "Me")))),
        "chats" to chats,
        "time" to 1700L,
    )

    private class Client(val c: MaxClient, val conn: FakeRawConnection, val login: Map<*, *>)

    private suspend fun TestScope.loggedIn(
        kv: KeyValueStore = InMemoryKeyValueStore(),
        chats: List<Any?> = listOf(chat(2, 11, 1_100)),
    ): Client {
        val factory = ScriptedConnectionFactory()
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), kv, factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        val sent = conn.answer(Opcode.LOGIN, loginReply(chats))
        login.await()
        runCurrent()
        return Client(c, conn, sent)
    }

    @Test
    fun aSavedGhostModeHoldsTheFirstLoginTypingAndOnline() = runTest {
        val kv = InMemoryKeyValueStore(mapOf("max.default.ghostMode" to "1"))
        val t = loggedIn(kv)
        assertTrue(t.c.ghostMode)
        // the very first LOGIN of the run already says "not interactive"
        assertEquals(false, t.login["interactive"])
        // the app comes to the foreground: remembered, not sent
        assertFalse(t.c.setInteractive(true))
        assertTrue(t.c.isInteractive)
        t.conn.assertSilent()
        // typing of every kind is held back, also a direct API call
        assertFalse(t.c.sendTyping(-70, "TEXT"))
        assertFalse(t.c.sendTyping(-70, "VIDEO_MSG"))
        assertFalse(t.c.api.messages.sendTyping(-70, "FILE"))
        t.conn.assertSilent()
        // a typing call issued before the switch but run after it follows the switch
        t.c.setGhostMode(false).also { assertTrue(it) }
        assertEquals(Opcode.PING.value to mapOf("interactive" to true), t.conn.next())
        val late = async { t.c.api.messages.sendTyping(-70, "TEXT") }
        assertTrue(t.c.setGhostMode(true))
        assertEquals(Opcode.PING.value to mapOf("interactive" to false), t.conn.next())
        runCurrent()
        assertFalse(late.await())
        t.conn.assertSilent()
        // the next reconnect LOGIN would say "not interactive" as well
        assertEquals("1", kv.get("max.default.ghostMode"))
    }

    @Test
    fun turningGhostOffRestoresTheRememberedAppState() = runTest {
        val t = loggedIn()
        assertEquals(true, t.login["interactive"])
        assertTrue(t.c.setInteractive(false))
        assertEquals(Opcode.PING.value to mapOf("interactive" to false), t.conn.next())
        // background, then ghost on: the server already has false, no PING needed
        assertFalse(t.c.setGhostMode(true))
        t.conn.assertSilent()
        // still in the background when ghost goes off: stays false
        assertFalse(t.c.setGhostMode(false))
        t.conn.assertSilent()
        // foreground while ghost is on: nothing; off: online at once
        t.c.ghostMode = true
        runCurrent()
        assertFalse(t.c.setInteractive(true))
        t.conn.assertSilent()
        assertTrue(t.c.setGhostMode(false))
        assertEquals(Opcode.PING.value to mapOf("interactive" to true), t.conn.next())
        assertTrue(t.c.sendTyping(-70, "TEXT"))
        assertEquals(Opcode.MSG_TYPING.value, t.conn.next().first)
    }

    @Test
    fun qrApprovalPingRespectsGhostMode() = runTest {
        val t = loggedIn()
        t.c.qrApproveDelayMs = 0
        t.c.setGhostMode(true)
        assertEquals(Opcode.PING.value to mapOf("interactive" to false), t.conn.next())
        val approve = async { t.c.approveQrLogin("https://max.ru/:auth/abc") }
        runCurrent()
        assertEquals(mapOf("interactive" to false), t.conn.answer(Opcode.PING, emptyMap<String, Any?>()))
        runCurrent()
        t.conn.answer(Opcode.SESSIONS_INFO, mapOf("sessions" to emptyList<Any>()))
        runCurrent()
        t.conn.answer(Opcode.AUTH_QR_APPROVE, emptyMap<String, Any?>())
        approve.await()
    }

    @Test
    fun hiddenReadReceiptsReadLocallyAndSurviveARestart() = runTest {
        val kv = InMemoryKeyValueStore()
        val t = loggedIn(kv)
        assertEquals(2, t.c.store.state.value.chats.getValue(-70).newMessages)
        t.c.hideReadReceipts = true
        assertFalse(t.c.ghostMode)
        val read = t.c.markRead(-70, 11)
        assertTrue(read.local)
        assertEquals(0, read.unread)
        assertEquals(1_100L, read.mark)
        assertEquals(mapOf(-70L to LocalRead(11, 1_100)), t.c.localReadMarks)
        assertEquals(0, t.c.store.state.value.chats.getValue(-70).newMessages)
        // nothing reached the server, a direct API call is held back too, and so are story views
        assertIs<OutboundBlockedException>(runCatching { t.c.api.messages.markRead(-70, 11) }.exceptionOrNull())
        assertFalse(t.c.markStorySeen(StoryOwner(other), 3))
        t.conn.assertSilent()
        // a new message counts again; a chat update from the server with its old counter does not undo the read
        t.conn.feed(push(Opcode.NOTIF_MESSAGE.value, mapOf("chatId" to -70, "message" to mapOf("id" to 12, "time" to 1_200, "type" to "USER", "sender" to other, "text" to "m12"))))
        runCurrent()
        assertEquals(1, t.c.store.state.value.chats.getValue(-70).newMessages)
        t.c.markRead(-70, 12)
        assertEquals(0, t.c.store.state.value.chats.getValue(-70).newMessages)
        t.conn.feed(push(Opcode.NOTIF_CHAT.value, mapOf("chat" to chat(4, 12, 1_200))))
        runCurrent()
        assertEquals(0, t.c.store.state.value.chats.getValue(-70).newMessages)
        assertEquals("-70:12:1200", kv.get("max.default.localReads.$me"))

        // a restart: a new client, the server still counts 4 unread and has an old own mark
        val again = loggedIn(kv, listOf(chat(4, 12, 1_200, myMark = 900)))
        assertTrue(again.c.hideReadReceipts)
        assertEquals(0, again.c.store.state.value.chats.getValue(-70).newMessages)
        assertEquals(LocalRead(12, 1_200), again.c.localReadMarks[-70L])
        // turning it off sends nothing for the reads kept meanwhile
        again.c.hideReadReceipts = false
        again.conn.assertSilent()
        // the next real mark goes out as usual and covers everything up to its message
        val mark = async { again.c.markRead(-70, 12) }
        runCurrent()
        val body = again.conn.answer(Opcode.CHAT_MARK, mapOf("unread" to 0, "mark" to 1_200L))
        assertEquals("READ_MESSAGE", body["type"])
        assertEquals(12L, body["messageId"].long())
        assertEquals(1_200L, body["mark"].long())
        assertFalse(mark.await().local)
        // the server's mark reached the local one: it goes, on the device too
        assertTrue(again.c.localReadMarks.isEmpty())
        assertNull(kv.get("max.default.localReads.$me"))
    }

    @Test
    fun theServerMarkDropsALocalReadAndAnotherAccountDoesNotSeeIt() = runTest {
        val kv = InMemoryKeyValueStore(mapOf("max.default.localReads.$me" to "-70:11:1100", "max.default.hideReadReceipts" to "1"))
        // the server already has a later own mark: the local one is obsolete
        val t = loggedIn(kv, listOf(chat(0, 11, 1_100, myMark = 1_150)))
        assertTrue(t.c.localReadMarks.isEmpty())
        assertNull(kv.get("max.default.localReads.$me"))
        // reads of account 5 are not applied to account 6
        val kv2 = InMemoryKeyValueStore(mapOf("max.default.localReads.6" to "-70:11:1100"))
        val u = loggedIn(kv2)
        assertTrue(u.c.localReadMarks.isEmpty())
        assertEquals(2, u.c.store.state.value.chats.getValue(-70).newMessages)
    }

    @Test
    fun ownPresenceIsAskedFreshAndNeverMadeUp() = runTest {
        val t = loggedIn()
        val first = async { t.c.checkOwnPresence() }
        runCurrent()
        val body = t.conn.answer(Opcode.CONTACT_PRESENCE, mapOf("presence" to mapOf("$me" to mapOf("seen" to 1_700_000_000L, "status" to 0))))
        assertEquals(listOf(me), (body["contactIds"] as List<*>).map { it.long() })
        assertEquals(PresenceInfo(1_700_000_000L, 0), first.await())
        // asked again, no cache; an empty reply is "unknown", not "long ago"
        val second = async { t.c.checkOwnPresence() }
        runCurrent()
        t.conn.answer(Opcode.CONTACT_PRESENCE, mapOf("presence" to emptyMap<String, Any?>()))
        assertNull(second.await())
        // the request itself goes out in both modes
        t.c.setGhostMode(true)
        t.conn.next()
        t.c.hideReadReceipts = true
        val third = async { t.c.checkOwnPresence() }
        runCurrent()
        t.conn.answer(Opcode.CONTACT_PRESENCE, mapOf("presence" to mapOf("$me" to mapOf("status" to 1))))
        assertEquals(1, third.await()?.status)
    }
}
