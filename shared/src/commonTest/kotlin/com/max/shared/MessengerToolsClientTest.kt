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

        val ok = async { c.deleteMessages(7, listOf(2, 3, 3), forMe = true) }
        runCurrent()
        val sent = conn.answer(Opcode.MSG_DELETE, emptyMap<String, Any?>())
        ok.await()
        assertEquals(7L, sent["chatId"].long())
        assertEquals(listOf(2L, 3L), (sent["messageIds"] as List<*>).map { it.long() })
        assertEquals(true, sent["forMe"])
        assertFalse("itemType" in sent)
        assertEquals(listOf(1L), c.store.state.value.messagesOf(7).map { it.id })
        assertEquals(1L, c.store.state.value.chats.getValue(7).lastMessage?.id)
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
    fun contactEditsAndPhoneBookNames() = runTest {
        val (c, conn) = loggedIn(backgroundScope, contacts = listOf(user(5, "Ivan", 79990000005), user(6, "Petr", 79990000006)))
        assertEquals(setOf(5L, 6L), c.store.state.value.contactIds)
        c.setAddressBook(listOf(PhoneContact("+7 999 000-00-05", "Брат")))
        assertEquals("Брат", c.displayName(5))

        val rename = async { c.renameContact(5, "Ваня") }
        runCurrent()
        val sent = conn.answer(Opcode.CONTACT_UPDATE, mapOf("contact" to user(5, "Ivan", 79990000005, custom = "Ваня")))
        rename.await()
        assertEquals("UPDATE", sent["action"])
        assertEquals("Ваня", sent["firstName"])
        assertEquals("Ваня", c.displayName(5)) // own contact name wins over the book

        val remove = async { c.removeContact(5) }
        runCurrent()
        assertEquals("REMOVE", conn.answer(Opcode.CONTACT_UPDATE, emptyMap<String, Any?>())["action"])
        remove.await()
        assertEquals(setOf(6L), c.store.state.value.contactIds)
        assertEquals("Брат", c.displayName(5)) // back to the book name

        val import = async { c.importPhoneBook(listOf(PhoneContact("+79990000007", "Маша"), PhoneContact("+79990000008", "Никто"))) }
        runCurrent()
        val body = conn.answer(Opcode.SYNC, mapOf("contacts" to listOf(user(7, "Maria", 79990000007))))
        val result = import.await()
        assertEquals(setOf("+79990000007", "+79990000008"), (body["contactList"] as Map<*, *>).keys)
        assertEquals(7L, result.byPhone.getValue("+79990000007").id)
        assertEquals("Маша", c.displayName(7))
        // the import also keeps the entries as address book
        c.setLocalName(7, null)
        assertEquals("Маша", c.displayName(7))
        c.setAddressBook(emptyList())
        assertEquals("Maria", c.displayName(7))
    }
}
