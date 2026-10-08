package com.max.core.api

import com.max.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Contact rename (`CONTACT_UPDATE` UPDATE) and phone-book import (`SYNC`) mapping. */
class ContactsEditTest {
    private fun user(id: Long, phone: Long) = mapOf("id" to id, "phone" to phone, "names" to listOf(mapOf("name" to "U$id", "type" to "ONEME")))

    @Test
    fun renameSendsUpdateWithTrimmedNamesAndReadsTheContact() = runTest {
        val renamed = mapOf("id" to 5L, "names" to listOf(mapOf("firstName" to "Ваня", "lastName" to "", "type" to "CUSTOM")))
        val sink = ScriptSink(mapOf("contact" to renamed))
        val u = UsersApi(sink).renameContact(5, " Ваня ")
        assertEquals(5L, u.id)
        assertEquals(listOf(Opcode.CONTACT_UPDATE), sink.opcodes)
        val payload = sink.sent.single().second as Map<*, *>
        assertEquals(listOf("contactId", "action", "firstName", "lastName"), payload.keys.toList())
        assertEquals(mapOf("contactId" to 5L, "action" to "UPDATE", "firstName" to "Ваня", "lastName" to ""), payload)
        assertEquals("Ваня", ContactNames.customName(u))

        assertFailsWith<IllegalArgumentException> { UsersApi(ScriptSink()).renameContact(5, "  ") }
        assertFailsWith<MalformedReplyException> { UsersApi(ScriptSink(mapOf("x" to 1))).renameContact(5, "A", "B") }
    }

    @Test
    fun importSendsTheContactListAndMapsPhonesByNumber() = runTest {
        val sink = ScriptSink(mapOf("contacts" to listOf(user(1, 79990000001), "junk", user(2, 79990000002))))
        val book = listOf(PhoneContact("+7 999 000-00-01", "Брат", "Старший"), PhoneContact("+79990000002", "Петя"), PhoneContact("+79990000003", "Нет"))
        val result = UsersApi(sink).importPhoneBook(book)
        assertEquals(Opcode.SYNC, sink.opcodes.single())
        assertEquals(
            mapOf("contactList" to mapOf("+7 999 000-00-01" to mapOf("firstName" to "Брат"), "+79990000002" to mapOf("firstName" to "Петя"), "+79990000003" to mapOf("firstName" to "Нет"))),
            sink.sent.single().second,
        )
        assertEquals(listOf(1L, 2L), result.users.map { it.id })
        assertEquals(mapOf("+7 999 000-00-01" to 1L, "+79990000002" to 2L), result.byPhone.mapValues { it.value.id })
        assertTrue(result.phones.isEmpty())
        assertEquals("Брат Старший", book[0].fullName)
    }

    @Test
    fun importUsesTheServerPhoneFormWhenTheReplyHasPhones() = runTest {
        val sink = ScriptSink(mapOf("contacts" to listOf(user(1, 79990000001)), "phones" to mapOf("89990000001" to 79990000001L, "bad" to "x", "s" to "+7 999 000-00-01")))
        val result = UsersApi(sink).importPhoneBook(listOf(PhoneContact("89990000001", "Брат")))
        assertEquals(mapOf("89990000001" to 79990000001L, "s" to 79990000001L), result.phones)
        assertEquals(1L, result.byPhone.getValue("89990000001").id)

        assertTrue(UsersApi(ScriptSink(emptyMap<String, Any?>())).importPhoneBook(listOf(PhoneContact("1", "A"))).users.isEmpty())
        assertFailsWith<IllegalArgumentException> { UsersApi(ScriptSink()).importPhoneBook(emptyList()) }
    }

    @Test
    fun phoneDigits() {
        assertEquals(79990001122L, PhoneNumbers.digits("+7 (999) 000-11-22"))
        assertEquals(5L, PhoneNumbers.digits(5))
        assertEquals(null, PhoneNumbers.digits(""))
        assertEquals(null, PhoneNumbers.digits("0"))
        assertEquals(null, PhoneNumbers.digits("1234567890123456789"))
        assertEquals(null, PhoneNumbers.digits(null))
    }
}
