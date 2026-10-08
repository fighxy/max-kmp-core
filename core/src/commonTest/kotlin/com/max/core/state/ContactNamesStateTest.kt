package com.max.core.state

import com.max.core.api.ContactNames
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.PhoneContact
import com.max.core.api.ReactionCounter
import com.max.core.api.ReactionInfo
import com.max.core.api.UserName
import com.max.core.auth.LoginResult
import com.max.core.events.EventParser
import com.max.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Display names: address book > contact name > own name > first entry > phone > "Участник". */
class ContactNamesStateTest {
    private fun user(id: Long, phone: Long? = null, vararg names: Map<String, Any?>) = MaxUser.from(
        linkedMapOf<String, Any?>("id" to id, "names" to names.toList()).also { if (phone != null) it["phone"] = phone },
    )!!

    private val oneme = mapOf("name" to "Иван Петров", "firstName" to "Иван", "lastName" to "Петров", "type" to "ONEME")
    private val custom = mapOf("firstName" to "Ваня", "lastName" to "", "type" to "CUSTOM")

    @Test
    fun precedenceIsAddressBookThenContactNameThenOwnNameThenFirstThenPhone() {
        val profileOnly = user(1, 79990000001, oneme)
        val withCustom = user(2, 79990000002, oneme, custom)
        val firstOnly = user(3, null, mapOf("name" to "Plain"))
        val phoneOnly = user(4, 79990000004)
        val nothing = user(5)
        var s = StateReducer.putUsers(MaxState(), listOf(profileOnly, withCustom, firstOnly, phoneOnly, nothing))
        assertEquals("Иван Петров", s.displayName(1))
        assertEquals("Ваня", s.displayName(2))
        assertEquals("Plain", s.displayName(3))
        assertEquals("+79990000004", s.displayName(4))
        assertNull(s.displayName(5))
        assertEquals(ContactNames.FALLBACK, s.displayLabel(5))
        assertEquals("Участник", s.displayLabel(100))

        s = StateReducer.setAddressBook(
            s,
            listOf(
                PhoneContact("8 (999) 000-00-01", "Брат"),
                PhoneContact("+79990000002", "Иван", "Работа"),
                PhoneContact("+7 999 000-00-02", "Дубль"), // same number: the first name wins
                PhoneContact("900", "Сервис"),
                PhoneContact("+79990000004", " "), // no name: skipped
                PhoneContact("9990000004", "Четвёртый"), // so this one takes the number
            ),
        )
        assertEquals(mapOf("+79990000001" to "Брат", "+79990000002" to "Иван Работа", "+79990000004" to "Четвёртый"), s.addressBook)
        assertEquals("Брат", s.displayName(1)) // book beats the own name
        assertEquals("Иван Работа", s.displayName(2)) // book beats the contact name too
        assertEquals("Четвёртый", s.displayName(4))
        assertNull(s.addressBookName(3)) // no phone

        s = StateReducer.setLocalName(s, 3, "  Сосед ")
        assertEquals("Сосед", s.displayName(3))
        // a user not loaded yet is named only by the id name
        s = StateReducer.setLocalName(s, 99, "Новый")
        assertEquals("Новый", s.displayName(99))
        s = StateReducer.setLocalName(s, 99, " ")
        assertNull(s.displayName(99))
    }

    @Test
    fun entryNames() {
        assertEquals("Ann Lee", ContactNames.entryName(UserName("Nick", "Ann", "Lee", "CUSTOM")))
        assertEquals("Nick", ContactNames.entryName(UserName("Nick", " ", null, "ONEME")))
        assertNull(ContactNames.entryName(UserName(null, null, null, null)))
        // own name: `name` first
        assertEquals("Nick", ContactNames.profileName(user(1, null, mapOf("name" to "Nick", "firstName" to "Ann", "type" to "ONEME"))))
        assertNull(ContactNames.resolve(null, " "))
        assertEquals("Участник", ContactNames.label(null, null))
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
        // the reply contact is stored (still without the custom name)
        s = StateReducer.removeContact(s, 3, user(3, null, oneme, custom))
        assertEquals("Иван Петров", s.displayName(3))
        assertTrue(s.contactIds.isEmpty())
        assertEquals(emptySet(), StateReducer.removeContact(s, 42).contactIds)
    }

    @Test
    fun contactPushKeepsTheNewerUpdateTime() {
        fun pushed(name: String, time: Long) = mapOf("id" to 5L, "updateTime" to time, "names" to listOf(mapOf("firstName" to name, "type" to "CUSTOM")))
        val store = MaxStore(initial = MaxState(me = 1))
        store.apply(EventParser.parse(Opcode.NOTIF_CONTACT.value, 0, mapOf("contact" to pushed("Новое", 200))))
        assertEquals("Новое", store.state.value.displayName(5))
        store.apply(EventParser.parse(Opcode.NOTIF_CONTACT.value, 0, mapOf("contact" to pushed("Старое", 100))))
        assertEquals("Новое", store.state.value.displayName(5))
        store.apply(EventParser.parse(Opcode.NOTIF_CONTACT.value, 0, mapOf("contact" to pushed("Ещё новее", 300))))
        assertEquals("Ещё новее", store.state.value.displayName(5))
        // a push without contact is unknown
        assertTrue(EventParser.parse(Opcode.NOTIF_CONTACT.value, 0, mapOf("x" to 1)) is com.max.core.events.MaxEvent.Unknown)
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
        s = StateReducer.putEditedMessage(s, 7, message("new", 300))
        val stored = s.messagesOf(7).single()
        assertEquals("new", stored.text)
        assertEquals(300L, stored.updateTime)
        assertEquals(7L, stored.chatId)
        assertEquals(2, stored.reactionInfo?.totalCount)
        s = StateReducer.putEditedMessage(s, 7, message("newer"))
        assertEquals(300L, s.messagesOf(7).single().updateTime)
    }
}
