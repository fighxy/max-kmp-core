package com.max.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountConfigTest {
    private val banners = listOf(
        mapOf("items" to listOf(mapOf("appid" to 111L, "icon" to "https://st/digital-id.png", "title" to "Госключ"))),
        mapOf("items" to listOf(mapOf("appid" to 222, "icon" to "https://st/x.png", "title" to "Войти в Сферум"), "junk")),
    )

    @Test
    fun readsUserServerAndHashFromLogin() {
        val reply = mapOf(
            "config" to mapOf(
                "hash" to "h1",
                "user" to mapOf("HIDDEN" to true, "PHONE_NUMBER_PRIVACY" to "CONTACTS", "SAFE_MODE" to "OFF", "N" to 1),
                "server" to mapOf("invite-link" to "@ivan", "settings-entry-banners" to banners),
            ),
        )
        val c = AccountConfig.fromLoginReply(reply)!!
        assertEquals("h1", c.hash)
        assertEquals(true, c.userFlag("HIDDEN"))
        assertEquals(false, c.userFlag("SAFE_MODE"))
        assertEquals(true, c.userFlag("N"))
        assertNull(c.userFlag("PHONE_NUMBER_PRIVACY"))
        assertEquals("CONTACTS", c.userString("PHONE_NUMBER_PRIVACY"))
        assertEquals("1", c.userString("N"))
        assertEquals("https://max.ru/ivan", c.inviteLink)
        // the icon marker wins; the title is the fallback
        assertEquals(111L, c.entryAppBotId(EntryApp.DIGITAL_ID))
        assertEquals(222L, c.entryAppBotId(EntryApp.SFERUM))
        assertNull(AccountConfig.fromLoginReply(mapOf("chats" to emptyList<Any>())))
    }

    @Test
    fun fallsBackToKnownBots() {
        val c = AccountConfig()
        assertEquals(2340831L, c.entryAppBotId(EntryApp.SFERUM))
        assertEquals(8250447L, c.entryAppBotId(EntryApp.DIGITAL_ID))
        assertNull(c.inviteLink)
    }

    @Test
    fun normalizesLinks() {
        assertEquals("https://max.ru/u/abc", AccountConfig.normalizeLink("https://max.ru/u/abc"))
        assertEquals("https://max.ru/name", AccountConfig.normalizeLink(" @name "))
        assertEquals("https://max.ru/name", AccountConfig.normalizeLink("max.ru/name"))
        assertNull(AccountConfig.normalizeLink("  "))
        assertNull(AccountConfig.normalizeLink("@"))
        assertNull(AccountConfig.normalizeLink(null))
    }

    @Test
    fun configReplyReplacesUser() {
        val c = AccountConfig(user = mapOf("HIDDEN" to false), server = mapOf("invite-link" to "https://max.ru/x"), hash = "h1")
        val next = c.withUser(mapOf("HIDDEN" to true), "h2")
        assertEquals(mapOf("HIDDEN" to true), next.user)
        assertEquals("h2", next.hash)
        assertEquals(c.server, next.server)
        assertEquals(c, c.withUser(null, null))
    }
}
