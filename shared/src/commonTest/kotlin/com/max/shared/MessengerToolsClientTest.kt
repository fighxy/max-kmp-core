@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.api.ChatMemberRole
import com.max.core.api.PhoneContact
import com.max.core.api.TextElement
import com.max.core.api.TextElementType
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Multi-select, formatting, chat members, contact edits and display names through [MaxClient]. */
class MessengerToolsClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private fun message(id: Long, time: Long, text: String = "m$id", extra: Map<String, Any?> = emptyMap()) =
        linkedMapOf<String, Any?>("id" to id, "sender" to 9L, "time" to time, "type" to "USER", "text" to text) + extra

    private fun user(id: Long, name: String, phone: Long? = null, custom: String? = null) = linkedMapOf<String, Any?>(
        "id" to id,
        "names" to listOfNotNull(
            custom?.let { mapOf("firstName" to it, "lastName" to "", "type" to "CUSTOM") },
            mapOf("name" to name, "firstName" to name, "type" to "ONEME"),
        ),
    ).also { if (phone != null) it["phone"] = phone }

    /** Answers the next request with [reply] and returns its payload. */
    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *> {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as Map<*, *>
    }

    private suspend fun FakeRawConnection.fail(opcode: Opcode, error: String): Map<*, *> {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(errorReply(header.seq, opcode.value, mapOf("error" to error, "message" to error)))
        return payload as Map<*, *>
    }

    private fun Any?.long(): Long? = (this as? Number)?.toLong()

    private suspend fun TestScope.loggedIn(
        scope: CoroutineScope,
        chats: List<Map<String, Any?>> = emptyList(),
        messages: Map<String, Any?> = emptyMap(),
        contacts: List<Map<String, Any?>> = emptyList(),
    ): Pair<MaxClient, FakeRawConnection> {
        val factory = ScriptedConnectionFactory()
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, scope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(
            Opcode.LOGIN,
            mapOf("profile" to mapOf("contact" to mapOf("id" to 9)), "chats" to chats, "messages" to messages, "contacts" to contacts, "time" to 1700L),
        )
        login.await()
        runCurrent()
        return c to conn
    }

    @Test
    fun deletingASelectionDropsItFromTheStoreAfterTheServerAccepts() = runTest {
        val chat = mapOf("id" to 7, "type" to "CHAT", "status" to "ACTIVE", "lastMessage" to message(3, 300))
        val (c, conn) = loggedIn(backgroundScope, listOf(chat), mapOf("7" to listOf(message(1, 100), message(2, 200), message(3, 300))))

        val failed = async { runCatching { c.deleteMessages(7, listOf(1, 2)) } }
        runCurrent()
        conn.fail(Opcode.MSG_DELETE, "not.allowed")
        assertTrue(failed.await().isFailure)
        assertEquals(listOf(1L, 2L, 3L), c.store.state.value.messagesOf(7).map { it.id })

        // the server refuses one id: only the others leave the store
        val partly = async { c.deleteMessages(7, listOf(1, 2)) }
        runCurrent()
        conn.answer(Opcode.MSG_DELETE, mapOf("messageIds" to listOf(1L, 2L), "failedMessageIds" to listOf(1L)))
        val partial = partly.await()
        assertEquals(listOf(2L), partial.deleted)
        assertEquals(listOf(1L), partial.failed)
        assertEquals(listOf(1L, 3L), c.store.state.value.messagesOf(7).map { it.id })

        val ok = async { c.deleteMessages(7, listOf(3, 3), forMe = true) }
        runCurrent()
        val sent = conn.answer(Opcode.MSG_DELETE, emptyMap<String, Any?>())
        assertEquals(listOf(3L), ok.await().deleted)
        assertEquals(7L, sent["chatId"].long())
        assertEquals(listOf(3L), (sent["messageIds"] as List<*>).map { it.long() })
        assertEquals(true, sent["forMe"])
        assertFalse("itemType" in sent)
        assertFalse("postId" in sent)
        assertEquals(listOf(1L), c.store.state.value.messagesOf(7).map { it.id })
        assertEquals(1L, c.store.state.value.chats.getValue(7).lastMessage?.id)
    }

    @Test
    fun forwardingSendsTheCommentFirstAndKnownMessagesOldestFirst() = runTest {
        val chats = listOf(mapOf("id" to 7, "type" to "CHAT", "status" to "ACTIVE"), mapOf("id" to 8, "type" to "CHAT", "status" to "ACTIVE"))
        val (c, conn) = loggedIn(backgroundScope, chats, mapOf("7" to listOf(message(21, 100), message(22, 200))))
        val batch = async { c.forwardMessages(toChatId = 8, fromChatId = 7, messageIds = listOf(99, 22, 21), comment = "смотри") }
        runCurrent()
        val comment = conn.answer(Opcode.MSG_SEND, mapOf("message" to message(200, 500, "смотри")))
        assertEquals("смотри", (comment["message"] as Map<*, *>)["text"])
        val ids = mutableListOf<Any?>()
        for (id in 201L..203L) {
            runCurrent()
            ids += ((conn.answer(Opcode.MSG_SEND, mapOf("message" to message(id, 500 + id)))["message"] as Map<*, *>)["link"] as Map<*, *>)["messageId"]
        }
        assertTrue(batch.await().complete)
        assertEquals<List<Any?>>(listOf("21", "22", "99"), ids)
        assertEquals(listOf(200L, 201L, 202L, 203L), c.store.state.value.messagesOf(8).map { it.id }.sorted())
    }

    @Test
    fun forwardingOrdersEqualTimesByIdAndTrimsTheComment() = runTest {
        val chats = listOf(mapOf("id" to 7, "type" to "CHAT", "status" to "ACTIVE"), mapOf("id" to 8, "type" to "CHAT", "status" to "ACTIVE"))
        val (c, conn) = loggedIn(backgroundScope, chats, mapOf("7" to listOf(message(100, 300), message(99, 300))))
        val batch = async { c.forwardMessages(toChatId = 8, fromChatId = 7, messageIds = listOf(100, 99), comment = "  смотри  ") }
        runCurrent()
        assertEquals("смотри", (conn.answer(Opcode.MSG_SEND, mapOf("message" to message(200, 500, "смотри")))["message"] as Map<*, *>)["text"])
        val ids = mutableListOf<Any?>()
        for (id in 201L..202L) {
            runCurrent()
            ids += ((conn.answer(Opcode.MSG_SEND, mapOf("message" to message(id, 500 + id)))["message"] as Map<*, *>)["link"] as Map<*, *>)["messageId"]
        }
        assertTrue(batch.await().complete)
        assertEquals<List<Any?>>(listOf("99", "100"), ids)
        // a blank comment is not sent
        val quietBatch = async { c.forwardMessages(toChatId = 8, fromChatId = 7, messageIds = listOf(99), comment = "   ") }
        runCurrent()
        assertEquals("99", ((conn.answer(Opcode.MSG_SEND, mapOf("message" to message(203, 600)))["message"] as Map<*, *>)["link"] as Map<*, *>)["messageId"])
        assertTrue(quietBatch.await().complete)
    }

    @Test
    fun forwardingSeveralStopsAtTheFirstFailureAndKeepsTheSentOnes() = runTest {
        val chats = listOf(mapOf("id" to 7, "type" to "CHAT", "status" to "ACTIVE"), mapOf("id" to 8, "type" to "CHAT", "status" to "ACTIVE"))
        val (c, conn) = loggedIn(backgroundScope, chats)
        val batch = async { c.forwardMessages(toChatId = 8, fromChatId = 7, messageIds = listOf(11, 12, 13)) }
        runCurrent()
        val first = conn.answer(Opcode.MSG_SEND, mapOf("message" to message(101, 500)))
        runCurrent()
        val second = conn.fail(Opcode.MSG_SEND, "not.found")
        val result = batch.await()
        assertEquals(listOf(101L), result.sent.map { it.id })
        assertEquals(1, result.failedIndex)
        assertFalse(result.complete)
        assertEquals(8L, first["chatId"].long())
        val link = (first["message"] as Map<*, *>)["link"] as Map<*, *>
        assertEquals("FORWARD", link["type"])
        assertEquals("11", link["messageId"]) // a string, as PyMax sends it
        assertEquals(7L, link["chatId"].long())
        assertEquals("12", (((second["message"] as Map<*, *>)["link"]) as Map<*, *>)["messageId"])
        assertEquals(listOf(101L), c.store.state.value.messagesOf(8).map { it.id })
        assertNull(conn.takeWritten(Duration.ZERO))
    }

    @Test
    fun formattedTextIsSentAndEditedWithElements() = runTest {
        val chat = mapOf("id" to 7, "type" to "CHAT", "status" to "ACTIVE")
        val reactions = mapOf("counters" to listOf(mapOf("reaction" to "👍", "count" to 3)), "totalCount" to 3)
        val (c, conn) = loggedIn(backgroundScope, listOf(chat), mapOf("7" to listOf(message(5, 100, "hello world", mapOf("reactionInfo" to reactions)))))

        val send = async { c.sendFormattedText(7, "hello world", listOf(TextElement.strong(0, 5), TextElement.link(6, 5, "https://max.ru"), TextElement.emphasized(8, 10))) }
        runCurrent()
        val sentElements = (conn.answer(Opcode.MSG_SEND, mapOf("message" to message(6, 200, "hello world")))["message"] as Map<*, *>)["elements"] as List<*>
        send.await()
        // the element past the end of the text is dropped
        assertEquals(listOf("STRONG", "LINK"), sentElements.map { (it as Map<*, *>)["type"] })
        assertEquals("https://max.ru", ((sentElements[1] as Map<*, *>)["attributes"] as Map<*, *>)["url"])

        val edit = async { c.editText(7, 5, "hello there", listOf(TextElement.strikethrough(6, 5))) }
        runCurrent()
        val editElements = mapOf("type" to "STRIKETHROUGH", "from" to 6, "length" to 5)
        val sent = conn.answer(Opcode.MSG_EDIT, mapOf("message" to message(5, 100, "hello there", mapOf("elements" to listOf(editElements), "updateTime" to 300L))))
        val edited = edit.await()
        assertEquals("hello there", sent["text"])
        assertEquals(listOf("STRIKETHROUGH"), (sent["elements"] as List<*>).map { (it as Map<*, *>)["type"] })
        assertEquals(TextElementType.STRIKETHROUGH, edited.textElements.single().type)
        val stored = c.store.state.value.messagesOf(7).single { it.id == 5L }
        assertEquals("hello there", stored.text)
        assertEquals(3, stored.reactionInfo?.totalCount)
        assertEquals(300L, stored.updateTime)
    }

    @Test
    fun membersComeWithRolesAndPagesEnd() = runTest {
        val (c, conn) = loggedIn(backgroundScope)
        val page = async { c.loadChatMembers(70) }
        runCurrent()
        // the chat is unknown: it is asked first for its owner and admins
        conn.answer(
            Opcode.CHAT_INFO,
            mapOf("chats" to listOf(mapOf("id" to 70, "type" to "CHAT", "status" to "ACTIVE", "owner" to 1L, "admins" to listOf(2L), "adminParticipants" to mapOf("2" to mapOf("permissions" to 5, "alias" to "модератор"))))),
        )
        runCurrent()
        val members = listOf(
            mapOf("contact" to user(1, "Owner"), "presence" to mapOf("seen" to 1000L, "status" to 1)),
            mapOf("contact" to user(2, "Admin")),
            mapOf("contact" to user(3, "Member")),
        )
        val sent = conn.answer(Opcode.CHAT_MEMBERS, mapOf("members" to members, "marker" to 33L))
        val first = page.await()
        assertEquals("MEMBER", sent["type"])
        assertEquals(0L, sent["marker"].long())
        assertEquals(listOf(ChatMemberRole.OWNER, ChatMemberRole.ADMIN, ChatMemberRole.MEMBER), first.members.map { it.role })
        assertEquals("модератор", first.members[1].admin?.alias)
        assertEquals(33L, first.nextMarker)
        assertEquals("Admin", c.displayName(2))
        assertEquals(1000L, c.store.state.value.presence[1]?.seen)

        val next = async { c.loadChatMembers(70, marker = 33) }
        runCurrent()
        assertEquals(33L, conn.answer(Opcode.CHAT_MEMBERS, mapOf("members" to listOf(mapOf("contact" to user(4, "Late"))), "marker" to 33L))["marker"].long())
        val last = next.await()
        assertEquals(ChatMemberRole.MEMBER, last.members.single().role)
        assertNull(last.nextMarker)
    }

    @Test
    fun searchingMembersSendsTheQuery() = runTest {
        val chat = mapOf("id" to 70, "type" to "CHAT", "status" to "ACTIVE", "owner" to 1L)
        val (c, conn) = loggedIn(backgroundScope, listOf(chat))
        val search = async { c.searchChatMembers(70, "Ow") }
        runCurrent()
        val sent = conn.answer(Opcode.CHAT_MEMBERS, mapOf("members" to listOf(mapOf("contact" to user(1, "Owner")))))
        assertEquals<Map<*, *>>(mapOf("chatId" to 70L, "type" to "MEMBER", "query" to "Ow"), sent.mapValues { (_, v) -> if (v is Number) v.toLong() else v })
        assertEquals(ChatMemberRole.OWNER, search.await().single().role)
        assertEquals("Owner", c.displayName(1))
    }

    @Test
    fun contactEditsAndAddressBookNames() = runTest {
        val (c, conn) = loggedIn(backgroundScope, contacts = listOf(user(5, "Ivan", 79990000005), user(6, "Petr", 79990000006)))
        assertEquals(setOf(5L, 6L), c.store.state.value.contactIds)
        c.setAddressBook(listOf(PhoneContact("8 (999) 000-00-05", "Брат")))
        assertEquals("Брат", c.displayName(5))

        val rename = async { c.renameContact(5, "Ваня") }
        runCurrent()
        val sent = conn.answer(Opcode.CONTACT_UPDATE, mapOf("contact" to user(5, "Ivan", 79990000005, custom = "Ваня")))
        rename.await()
        assertEquals("UPDATE", sent["action"])
        assertEquals("Ваня", sent["firstName"])
        assertTrue("lastName" in sent && sent["lastName"] == null)
        assertEquals("Брат", c.displayName(5)) // the address book stays first
        c.setAddressBook(emptyList())
        assertEquals("Ваня", c.displayName(5)) // then the own contact name

        val remove = async { c.removeContact(5) }
        runCurrent()
        assertEquals("REMOVE", conn.answer(Opcode.CONTACT_UPDATE, mapOf("contact" to user(5, "Ivan", 79990000005)))["action"])
        assertEquals(5L, remove.await()?.id)
        assertEquals(setOf(6L), c.store.state.value.contactIds)
        assertEquals("Ivan", c.displayName(5))

        val add = async { c.addContactByPhone("+79990000007", "Маша") }
        runCurrent()
        val body = conn.answer(Opcode.CONTACT_ADD_BY_PHONE, mapOf("contact" to user(7, "Maria", 79990000007, custom = "Маша"), "new" to true))
        val added = add.await()
        assertEquals<Map<*, *>>(mapOf("phone" to "+79990000007", "firstName" to "Маша"), body)
        assertTrue(added.isNew)
        assertTrue(7L in c.store.state.value.contactIds)
        assertEquals("Маша", c.displayName(7))
        assertEquals("Участник", c.displayLabel(404))
        // nothing went to the server for the address book
        assertNull(conn.takeWritten(Duration.ZERO))
    }

    @Test
    fun draftsComeWithLoginAndAreSavedByPeerOrChat() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        val dialogId = 9L xor 20L
        val chats = listOf(
            mapOf("id" to dialogId, "type" to "DIALOG", "status" to "ACTIVE", "participants" to mapOf("9" to 0, "20" to 0)),
            mapOf("id" to 70, "type" to "CHAT", "status" to "ACTIVE"),
        )
        val drafts = mapOf("users" to mapOf("saved" to mapOf("20" to mapOf("saveTime" to 600L, "text" to "привет"))))
        conn.answer(
            Opcode.LOGIN,
            mapOf("profile" to mapOf("contact" to mapOf("id" to 9)), "chats" to chats, "drafts" to drafts, "time" to 1700L),
        )
        login.await()
        runCurrent()
        assertEquals("привет", c.drafts.getValue(dialogId).text)

        val save = async { c.saveDraft(70, "черновик", listOf(TextElement.strong(0, 4), TextElement.strong(5, 40))) }
        runCurrent()
        val saved = conn.answer(Opcode.DRAFT_SAVE, mapOf("time" to 900L))
        assertEquals(900L, save.await().updateTime)
        assertEquals(70L, saved["chatId"].long())
        assertEquals(1, ((saved["draft"] as Map<*, *>)["elements"] as List<*>).size)
        assertEquals("черновик", c.drafts.getValue(70).text)

        val discard = async { c.discardDraft(dialogId) }
        runCurrent()
        val body = conn.answer(Opcode.DRAFT_DISCARD, emptyMap<String, Any?>())
        assertTrue(discard.await())
        assertEquals(20L, body["userId"].long())
        assertEquals(600L, body["time"].long())
        assertFalse("chatId" in body)
        assertFalse(dialogId in c.drafts)
        assertFalse(c.discardDraft(12345))
        assertNull(conn.takeWritten(Duration.ZERO))
    }
}
