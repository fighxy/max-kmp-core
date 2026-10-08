package com.max.core.state

import com.max.core.api.ContactNames
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.PhoneBookImport
import com.max.core.api.PhoneContact
import com.max.core.api.ReactionInfo
import com.max.core.api.ReactionCounter
import com.max.core.auth.LoginResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Display names: contact name > address book > profile; contact removal; phone-book import; own edits. */
class ContactNamesStateTest {
    private fun user(id: Long, phone: Long? = null, vararg names: Map<String, Any?>) = MaxUser.from(
        linkedMapOf<String, Any?>("id" to id, "names" to names.toList()).also { if (phone != null) it["phone"] = phone },
    )!!

    private val oneme = mapOf("name" to "Иван Петров", "firstName" to "Иван", "lastName" to "Петров", "type" to "ONEME")
    private val custom = mapOf("firstName" to "Ваня", "lastName" to "", "type" to "CUSTOM")

    @Test
    fun precedenceIsContactNameThenAddressBookThenProfile() {
        val profileOnly = user(1, 79990000001, oneme)
        val withCustom = user(2, 79990000002, oneme, custom)
        val customFirst = user(3, null, custom, oneme)
        var s = StateReducer.putUsers(MaxState(), listOf(profileOnly, withCustom, customFirst))
        assertEquals("Иван Петров", s.displayName(1))
        assertEquals("Ваня", s.displayName(2))
        assertEquals("Ваня", s.displayName(3))

        s = StateReducer.setAddressBook(
            s,
            listOf(PhoneContact("+7 (999) 000-00-01", "Брат"), PhoneContact("+79990000002", "Иван", "Работа"), PhoneContact("", "нет номера")),
        )
        assertEquals(mapOf(79990000001L to "Брат", 79990000002L to "Иван Работа"), s.addressBook)
        assertEquals("Брат", s.displayName(1)) // book beats the profile
        assertEquals("Ваня", s.displayName(2)) // own contact name beats the book
        assertNull(s.addressBookName(3)) // no phone, no id name

        s = StateReducer.setLocalName(s, 3, "  Сосед ")
        assertEquals("Сосед", s.addressBookName(3))
        assertEquals("Ваня", s.displayName(3))
        // a user not loaded yet is named only by the id name
        s = StateReducer.setLocalName(s, 99, "Новый")
        assertEquals("Новый", s.displayName(99))
        s = StateReducer.setLocalName(s, 99, " ")
        assertNull(s.displayName(99))
        assertNull(s.displayName(100))
    }

    @Test
    fun entryNamesPreferFirstAndLastName() {
        assertEquals("Ann Lee", ContactNames.entryName(com.max.core.api.UserName("Nick", "Ann", "Lee", "CUSTOM")))
        assertEquals("Nick", ContactNames.entryName(com.max.core.api.UserName("Nick", " ", null, "ONEME")))
        assertNull(ContactNames.entryName(com.max.core.api.UserName(null, null, null, null)))
        // no ONEME entry: the first entry, as MaxUser.displayName
        assertEquals("Plain", ContactNames.profileName(user(1, null, mapOf("name" to "Plain"))))
        assertNull(ContactNames.resolve(null, " "))
    }

    @Test
    fun removingAContactDropsItFromTheListAndForgetsTheCustomName() {
        val u = user(2, 79990000002, oneme, custom)
        var s = StateReducer.putContacts(MaxState(me = 1), listOf(u, user(3, null, oneme)))
        assertEquals(setOf(2L, 3L), s.contactIds)
        s = StateReducer.removeContact(s, 2)
        assertEquals(setOf(3L), s.contactIds)
        assertEquals("Иван Петров", s.displayName(2))
        assertFalse(s.users.getValue(2).names.any { it.type == "CUSTOM" })
        assertTrue((s.users.getValue(2).raw["names"] as List<*>).none { (it as Map<*, *>)["type"] == "CUSTOM" })
        // unknown user: only the id leaves the list
        assertEquals(setOf(3L), StateReducer.removeContact(s, 42).contactIds)
    }

    @Test
    fun phoneBookImportMapsPhonesToUsersAndKeepsTheBookNames() {
        val contacts = listOf(PhoneContact("8 999 000-00-05", "Маша", "Соседка"), PhoneContact("+79990000006", "Петя"), PhoneContact("+70000000000", "Никто"))
        val users = listOf(user(5, 79990000005, oneme), user(6, 79990000006, oneme))
        // the server's `phones` maps the local "8 999…" form to its own number
        val phones = mapOf("8 999 000-00-05" to 79990000005L)
        val result = PhoneBookImport(users, phones, PhoneBookImport.match(contacts, users, phones), emptyMap<Any?, Any?>())
        assertEquals(mapOf("8 999 000-00-05" to 5L, "+79990000006" to 6L), result.byPhone.mapValues { it.value.id })

        val s = StateReducer.putPhoneBookImport(MaxState(), contacts, result)
        assertEquals(setOf(5L, 6L), s.users.keys)
        assertEquals(mapOf(5L to "Маша Соседка", 6L to "Петя"), s.localNames)
        assertEquals("Маша Соседка", s.displayName(5))
        assertEquals("Петя", s.displayName(6))
        assertEquals("Никто", s.addressBook[70000000000L])
        // imported users are not added to the contact list
        assertTrue(s.contactIds.isEmpty())
        // without `phones` an "8…" number does not match: no country-code guessing
        assertEquals(setOf("+79990000006"), PhoneBookImport.match(contacts, users, emptyMap()).keys)
    }

    @Test
    fun anotherAccountKeepsTheAddressBookAndClearDropsIt() {
        var s = StateReducer.setAddressBook(MaxState(me = 1), listOf(PhoneContact("+79990000001", "Брат")))
        s = StateReducer.setLocalName(s, 7, "Семь")
        val profile = mapOf("contact" to mapOf("id" to 2L))
        val other = LoginResult(profile, 2L, emptyList(), null, null, null, null, mapOf("profile" to profile))
        val switched = StateReducer.login(s, other)
        assertEquals(2L, switched.me)
        assertEquals(s.addressBook, switched.addressBook)
        assertEquals(s.localNames, switched.localNames)
        val store = MaxStore(initial = switched)
        store.clear()
        assertTrue(store.state.value.addressBook.isEmpty())
    }

    @Test
    fun ownEditKeepsStoredReactionsAndTheEditTime() {
        fun message(text: String, updateTime: Long? = null, reactions: ReactionInfo? = null) = MaxMessage.from(
            linkedMapOf<String, Any?>("id" to 5L, "time" to 100L, "type" to "USER", "text" to text, "sender" to 1L).also {
                if (updateTime != null) it["updateTime"] = updateTime
                if (reactions != null) it["reactionInfo"] = reactions.raw
            },
        )!!
        val reactions = ReactionInfo.of(listOf(ReactionCounter("👍", 2)), "👍")
        var s = StateReducer.putMessages(MaxState(), 7, listOf(message("old", 150, reactions)))
        // the MSG_EDIT reply has no chatId and no reactions
        s = StateReducer.putEditedMessage(s, 7, message("new", 300))
        val stored = s.messagesOf(7).single()
        assertEquals("new", stored.text)
        assertEquals(300L, stored.updateTime)
        assertEquals(7L, stored.chatId)
        assertEquals(2, stored.reactionInfo?.totalCount)
        // a reply without updateTime keeps the known one
        s = StateReducer.putEditedMessage(s, 7, message("newer"))
        assertEquals(300L, s.messagesOf(7).single().updateTime)
    }
}
