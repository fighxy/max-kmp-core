package com.max.ios

import com.max.core.api.AccountConfig
import com.max.core.api.ChatFolders
import com.max.core.api.Folder
import com.max.core.api.MaxUser
import com.max.core.api.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Snapshots the settings screen gets from the bridge. */
class IosSettingsTest {
    @Test
    fun settingsDefaultsBeforeTheConfig() {
        val s = settingsSnapshot(null)
        assertFalse(s.known)
        // the MAX web client's defaults
        assertEquals("CONTACTS", s.phonePrivacy)
        assertEquals("ALL", s.incomingCalls)
        assertEquals("ALL", s.chatInvites)
        assertEquals("ALL", s.searchByPhone)
        assertFalse(s.safeContentOnly)
        assertEquals("OFF", s.familyProtection)
        assertFalse(s.privacyLocked)
        assertTrue(s.showReadMark)
        assertFalse(s.showReadMarkKnown)
        assertFalse(s.onlineHidden)
        assertEquals("6M", s.inactiveTtl)
        assertEquals(2340831L, s.sferumBotId)
        assertEquals(8250447L, s.digitalIdBotId)
        assertEquals("", s.inviteLink)
    }

    @Test
    fun settingsFromTheConfig() {
        val config = AccountConfig(
            user = mapOf(
                "PHONE_NUMBER_PRIVACY" to "_NONE_", "HIDDEN" to true, "SAFE_MODE" to true, "INACTIVE_TTL" to "3m",
                "FAMILY_PROTECTION" to "MANAGEABLE", "INCOMING_CALL" to "ALL", "SHOW_READ_MARK" to false,
            ),
            server = mapOf("invite-link" to "me"),
        )
        val s = settingsSnapshot(config)
        assertTrue(s.known)
        assertEquals("NOBODY", s.phonePrivacy)
        assertTrue(s.onlineHidden)
        assertTrue(s.safeMode)
        assertEquals("3M", s.inactiveTtl)
        assertEquals("MANAGEABLE", s.familyProtection)
        assertEquals("MANAGEABLE", s.familyProtectionRaw)
        // safe mode forces and locks search, calls, invites and content
        assertTrue(s.privacyLocked)
        assertEquals("CONTACTS", s.incomingCalls)
        assertEquals("CONTACTS", s.chatInvites)
        assertEquals("CONTACTS", s.searchByPhone)
        assertTrue(s.safeContentOnly)
        assertFalse(s.showReadMark)
        assertTrue(s.showReadMarkKnown)
        // a value the web client does not know is kept raw
        val odd = settingsSnapshot(AccountConfig(user = mapOf("FAMILY_PROTECTION" to "ON", "INCOMING_CALL" to "CONTACTS")))
        assertEquals("UNKNOWN" to "ON", odd.familyProtection to odd.familyProtectionRaw)
        assertFalse(odd.privacyLocked)
        assertEquals("CONTACTS", odd.incomingCalls)
        assertEquals("ADMIN", settingsSnapshot(AccountConfig(user = mapOf("FAMILY_PROTECTION" to "ADMIN"))).familyProtection)
        assertEquals("https://max.ru/me", s.inviteLink)
    }

    @Test
    fun profileSessionsAndFolders() {
        val user = MaxUser.from(mapOf("id" to 5, "names" to listOf(mapOf("firstName" to "Иван", "lastName" to "К")), "phone" to 79990001122L, "photoId" to 7, "link" to "@ivan"))!!
        val p = myProfileSnapshot(user, null)
        assertEquals("Иван", p.firstName)
        assertEquals("К", p.lastName)
        assertEquals("79990001122", p.phone)
        assertEquals("7", p.photoId)
        assertEquals("https://max.ru/ivan", p.link)
        assertEquals("https://max.ru/u/x", myProfileSnapshot(user, AccountConfig(server = mapOf("invite-link" to "https://max.ru/u/x"))).link)

        val s = sessionSnapshot(SessionInfo.from(mapOf("client" to "MAX Web", "info" to "Chrome", "time" to 1_759_000_000L, "current" to true))!!)
        assertEquals("MAX Web", s.client)
        assertEquals(1_759_000_000_000L, s.lastSeenMs)
        assertTrue(s.current)

        val folders = ChatFolders.of(
            listOf(
                Folder.from(mapOf("id" to "all.chat.folder", "title" to "Все", "favorites" to listOf(1L, 2L)))!!,
                Folder.from(mapOf("id" to "w", "title" to "Каналы", "include" to listOf(9L), "filters" to listOf(2, "BOT")))!!,
            ),
            emptyList(),
            0,
        )
        val list = folderSnapshots(folders)
        assertTrue(list[0].isAllChats)
        assertEquals(2, list[0].pinnedCount)
        assertEquals(listOf("9"), list[1].chatIds)
        assertEquals(listOf("2", "BOT"), list[1].filters)
        assertEquals(listOf<Any?>(4L, "CHANNEL"), parseFilters(listOf(" 4", "CHANNEL", "")))
    }
}
