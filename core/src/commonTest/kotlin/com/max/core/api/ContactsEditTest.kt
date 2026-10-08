package com.max.core.api

import com.max.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contact rename / remove / add by phone (`CONTACT_UPDATE` 34, `CONTACT_ADD_BY_PHONE` 41) and phone normalization. */
class ContactsEditTest {
    private val renamed = mapOf("id" to 5L, "names" to listOf(mapOf("firstName" to "Ваня", "lastName" to "Петров", "type" to "CUSTOM")))

    @Test
    fun renameSendsUpdateWithTrimmedNamesAndNullForABlankLastName() = runTest {
        val sink = ScriptSink(mapOf("contact" to renamed), mapOf("contact" to renamed))
        val api = UsersApi(sink)
        val u = api.renameContact(5, " Ваня ")
        assertEquals(5L, u.id)
        assertEquals(listOf(Opcode.CONTACT_UPDATE), sink.opcodes)
        val payload = sink.sent.single().second as Map<*, *>
        assertEquals(listOf("contactId", "action", "firstName", "lastName"), payload.keys.toList())
        assertEquals(mapOf("contactId" to 5L, "action" to "UPDATE", "firstName" to "Ваня", "lastName" to null), payload)
        assertEquals("Ваня Петров", ContactNames.customName(u))
        api.renameContact(5, "Ваня", " Петров ")
        assertEquals("Петров", (sink.sent[1].second as Map<*, *>)["lastName"])

        assertFailsWith<IllegalArgumentException> { UsersApi(ScriptSink()).renameContact(5, "  ") }
        assertFailsWith<IllegalArgumentException> { UsersApi(ScriptSink()).renameContact(5, "a".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { UsersApi(ScriptSink()).renameContact(5, "a", "b".repeat(65)) }
        UsersApi(ScriptSink(mapOf("contact" to renamed))).renameContact(5, "a".repeat(64))
        assertFailsWith<MalformedReplyException> { UsersApi(ScriptSink(mapOf("x" to 1))).renameContact(5, "A", "B") }
    }

    @Test
    fun removeReturnsTheReplyContact() = runTest {
        val sink = ScriptSink(mapOf("contact" to mapOf("id" to 5L, "names" to emptyList<Any?>())), emptyMap<String, Any?>())
        val api = UsersApi(sink)
        assertEquals(5L, api.removeContact(5)?.id)
        assertNull(api.removeContact(6))
        assertEquals(mapOf("contactId" to 6L, "action" to "REMOVE"), sink.sent[1].second)
    }

    @Test
    fun addByPhoneSendsOpcode41AndReadsNew() = runTest {
        val sink = ScriptSink(mapOf("contact" to renamed, "new" to true), mapOf("contact" to renamed))
        val api = UsersApi(sink)
        val added = api.addContactByPhone(" +79131234567 ", "Ваня", " ")
        assertEquals(5L, added.user.id)
        assertTrue(added.isNew)
        assertEquals(Opcode.CONTACT_ADD_BY_PHONE, sink.opcodes[0])
        assertEquals(41, Opcode.CONTACT_ADD_BY_PHONE.value)
        assertEquals(mapOf("phone" to "+79131234567", "firstName" to "Ваня"), sink.sent[0].second)
        assertFalse(api.addContactByPhone("+79131234567").isNew)
        assertEquals(mapOf("phone" to "+79131234567"), sink.sent[1].second)
        assertFailsWith<IllegalArgumentException> { api.addContactByPhone(" ") }
    }

    // ---- phone normalization: one test per shared rule ------------------------------------------

    @Test
    fun rule1StripsSeparatorsAndKeepsThePlus() {
        assertEquals("+79131234567", PhoneNumbers.normalize("+7 (913) 123-45.67"))
        assertEquals("+79131234567", PhoneNumbers.normalize(" +7\u00a0913 123 45 67 "))
        assertNull(PhoneNumbers.normalize("+7 913 abc 45 67"))
    }

    @Test
    fun rule2LeadingDoubleZeroIsAPlus() {
        assertEquals("+380501234567", PhoneNumbers.normalize("00 380 50 123 4567"))
        assertEquals("+79131234567", PhoneNumbers.normalize("0079131234567"))
    }

    @Test
    fun rule3ElevenDigitsStartingWith8Or7() {
        assertEquals("+79131234567", PhoneNumbers.normalize("8 913 123-45-67"))
        assertEquals("+79131234567", PhoneNumbers.normalize("79131234567"))
        assertEquals("+79131234567", PhoneNumbers.normalize(79131234567L))
        // with a plus an 8 stays: it is not a Russian trunk prefix then
        assertEquals("+89131234567", PhoneNumbers.normalize("+89131234567"))
    }

    @Test
    fun rule4TenDigitsAreRussian() {
        assertEquals("+79131234567", PhoneNumbers.normalize("913 123 45 67"))
        assertEquals("+79131234567", PhoneNumbers.normalize("(913)1234567"))
    }

    @Test
    fun rule5ForeignNumbersAlwaysCarryAPlus() {
        assertEquals("+4915112345678", PhoneNumbers.normalize("+49 151 12345678"))
        assertEquals("+380501234567", PhoneNumbers.normalize("380501234567"))
        assertEquals("+12025550123", PhoneNumbers.normalize("+1 (202) 555-0123"))
    }

    @Test
    fun rule6TooShortOrTooLongIsNoNumber() {
        assertNull(PhoneNumbers.normalize("900"))
        assertNull(PhoneNumbers.normalize("123456"))
        assertEquals("+1234567", PhoneNumbers.normalize("1234567"))
        assertEquals("+123456789012345", PhoneNumbers.normalize("+123456789012345"))
        assertNull(PhoneNumbers.normalize("+1234567890123456"))
        assertNull(PhoneNumbers.normalize(""))
        assertNull(PhoneNumbers.normalize("+"))
        assertNull(PhoneNumbers.normalize(0))
        assertNull(PhoneNumbers.normalize(null))
    }
}
