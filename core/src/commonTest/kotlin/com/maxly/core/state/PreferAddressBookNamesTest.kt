package com.maxly.core.state

import com.maxly.core.api.ContactNames
import com.maxly.core.api.MaxUser
import com.maxly.core.api.PhoneContact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `preferAddressBookNames`: which of the address book and the own contact name wins. */
class PreferAddressBookNamesTest {
    private val renamed = MaxUser.from(
        mapOf(
            "id" to 7L, "phone" to 79131234567L,
            "names" to listOf(mapOf("type" to "ONEME", "firstName" to "Анна"), mapOf("type" to "CUSTOM", "firstName" to "Аня", "lastName" to "соседка")),
        ),
    )!!
    private val plain = MaxUser.from(mapOf("id" to 8L, "phone" to 79130000000L, "names" to listOf(mapOf("type" to "ONEME", "firstName" to "Борис"))))!!

    private fun store(): MaxStore = MaxStore().apply {
        putUsers(listOf(renamed, plain))
        setAddressBook(listOf(PhoneContact("8 913 123-45-67", "Мама"), PhoneContact("+7 913 000-00-00", "Боря")))
    }

    @Test
    fun addressBookFirstByDefault() {
        val store = store()
        assertTrue(store.state.value.preferAddressBookNames)
        assertEquals("Мама", store.state.value.displayName(7))
        assertEquals("Боря", store.state.value.displayName(8))
    }

    @Test
    fun ownRenameFirstWhenSwitchedOff() {
        val store = store()
        store.setPreferAddressBookNames(false)
        assertFalse(store.state.value.preferAddressBookNames)
        assertEquals("Аня соседка", store.state.value.displayName(7))
        // no rename: the address book still beats the profile name
        assertEquals("Боря", store.state.value.displayName(8))
        assertEquals("Аня соседка", ContactNames.label(renamed, "Мама", false))
        assertEquals("Мама", ContactNames.label(renamed, "Мама", true))
    }

    @Test
    fun theSettingSurvivesClearAndAnotherAccount() {
        val store = store()
        store.setPreferAddressBookNames(false)
        store.clear()
        assertFalse(store.state.value.preferAddressBookNames)
        store.applyLogin(com.maxly.core.auth.LoginResult.from(mapOf("profile" to mapOf("contact" to mapOf("id" to 1L)))))
        store.applyLogin(com.maxly.core.auth.LoginResult.from(mapOf("profile" to mapOf("contact" to mapOf("id" to 2L)))))
        assertFalse(store.state.value.preferAddressBookNames)
    }

    @Test
    fun phoneFallbackShowsAnUnnormalizedNumberAsIs() {
        val short = MaxUser.from(mapOf("id" to 9L, "phone" to 900L, "names" to emptyList<Any?>()))!!
        assertEquals("900", ContactNames.label(short, null))
        val empty = MaxUser.from(mapOf("id" to 9L, "names" to listOf(mapOf("type" to "OTHER"), mapOf("type" to "OTHER", "name" to "Нюра"))))!!
        assertEquals("Нюра", ContactNames.label(empty, null))
    }
}
