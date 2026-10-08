package com.max.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Privacy settings of `config.user`: reading with the web client's defaults and checked changes. */
class PrivacyConfigTest {
    private fun read(vararg user: Pair<String, Any?>) = PrivacyConfig.from(AccountConfig(user = mapOf(*user)))

    @Test
    fun missingKeysTakeTheWebClientDefaults() {
        val p = PrivacyConfig.from(null)
        assertEquals(PrivacyConfig(), p)
        assertEquals(PrivacyAccess.ALL, p.searchByPhone)
        assertEquals(PrivacyAccess.ALL, p.incomingCalls)
        assertEquals(PrivacyAccess.ALL, p.chatInvites)
        assertEquals(PrivacyAccess.CONTACTS, p.phoneNumber)
        assertFalse(p.onlineHidden)
        assertFalse(p.safeContentOnly)
        assertEquals(FamilyProtection.OFF, p.familyProtection)
        assertNull(p.showReadMark)
        assertEquals(p, read())
        // values the client does not know fall back too
        assertEquals(PrivacyAccess.CONTACTS, read("PHONE_NUMBER_PRIVACY" to "SOMEONE").phoneNumber)
    }

    @Test
    fun nobodyIsSentAsTheWebClientSendsItAndReadInBothSpellings() {
        assertEquals("NOBODY", PrivacyAccess.NOBODY.wire)
        assertEquals(PrivacyAccess.NOBODY, read("PHONE_NUMBER_PRIVACY" to "NOBODY").phoneNumber)
        assertEquals(PrivacyAccess.NOBODY, read("PHONE_NUMBER_PRIVACY" to "_NONE_").phoneNumber)
        assertEquals(PrivacyAccess.NOBODY, read("PHONE_NUMBER_PRIVACY" to "nobody").phoneNumber)
        assertEquals(mapOf("PHONE_NUMBER_PRIVACY" to "NOBODY"), PrivacyConfig.payload("PHONE_NUMBER_PRIVACY", PrivacyAccess.NOBODY))
        assertEquals(mapOf("PHONE_NUMBER_PRIVACY" to "NOBODY"), PrivacyConfig.payload("phone_number_privacy", "_NONE_"))
        assertEquals(mapOf("PHONE_NUMBER_PRIVACY" to "NOBODY"), PrivacySettings(phoneNumberVisibility = PrivacyAccess.NOBODY).toPayload())
    }

    @Test
    fun familyProtectionIsAnEnumWithTheRawValueKept() {
        assertEquals(FamilyProtection.OFF, read("FAMILY_PROTECTION" to "OFF").familyProtection)
        assertEquals(FamilyProtection.ADMIN, read("FAMILY_PROTECTION" to "ADMIN").familyProtection)
        val protectedAccount = read("FAMILY_PROTECTION" to "MANAGEABLE", "INCOMING_CALL" to "ALL")
        assertEquals(FamilyProtection.MANAGEABLE, protectedAccount.familyProtection)
        assertTrue(protectedAccount.locked)
        // locked, but the values are the stored ones: only safe mode forces them
        assertEquals(PrivacyAccess.ALL, protectedAccount.incomingCalls)
        assertTrue(protectedAccount.isReadOnly("SAFE_MODE"))
        val odd = read("FAMILY_PROTECTION" to "ON")
        assertEquals(FamilyProtection.UNKNOWN, odd.familyProtection)
        assertEquals("ON", odd.familyProtectionRaw)
        assertFalse(odd.locked)
    }

    @Test
    fun safeModeForcesAndLocksTheFourKeys() {
        val p = read("SAFE_MODE" to true, "INCOMING_CALL" to "ALL", "SEARCH_BY_PHONE" to "ALL", "CHATS_INVITE" to "ALL", "CONTENT_LEVEL_ACCESS" to false, "HIDDEN" to true)
        assertTrue(p.locked)
        assertEquals(PrivacyAccess.CONTACTS, p.searchByPhone)
        assertEquals(PrivacyAccess.CONTACTS, p.incomingCalls)
        assertEquals(PrivacyAccess.CONTACTS, p.chatInvites)
        assertTrue(p.safeContentOnly)
        assertTrue(p.onlineHidden)
        for (key in PrivacyConfig.LOCKED_BY_SAFE_MODE) {
            assertTrue(p.isReadOnly(key), key)
            assertFailsWith<IllegalStateException> { PrivacyConfig.payload(key, if (key == "CONTENT_LEVEL_ACCESS") false else "ALL", p) }
        }
        // the others stay open, safe mode itself too
        assertEquals(mapOf("HIDDEN" to false), PrivacyConfig.payload("HIDDEN", false, p))
        assertEquals(mapOf("PHONE_NUMBER_PRIVACY" to "ALL"), PrivacyConfig.payload("PHONE_NUMBER_PRIVACY", "ALL", p))
        assertEquals(linkedMapOf<String, Any?>("SAFE_MODE_NO_PIN" to false, "SAFE_MODE" to false), PrivacyConfig.payload("SAFE_MODE", false, p))
    }

    @Test
    fun changesAreChecked() {
        assertEquals(mapOf("SEARCH_BY_PHONE" to "CONTACTS"), PrivacyConfig.payload("SEARCH_BY_PHONE", "contacts"))
        assertEquals(mapOf("INCOMING_CALL" to "ALL"), PrivacyConfig.payload("INCOMING_CALL", PrivacyAccess.ALL))
        assertEquals(mapOf("CHATS_INVITE" to "CONTACTS"), PrivacyConfig.payload("CHATS_INVITE", PrivacyAccess.CONTACTS))
        assertEquals(mapOf("CONTENT_LEVEL_ACCESS" to true), PrivacyConfig.payload("CONTENT_LEVEL_ACCESS", "true"))
        assertEquals(mapOf("HIDDEN" to true), PrivacyConfig.payload("HIDDEN", true))
        assertEquals(
            listOf("INCOMING_CALL", "SEARCH_BY_PHONE", "SAFE_MODE_NO_PIN", "CONTENT_LEVEL_ACCESS", "CHATS_INVITE", "SAFE_MODE"),
            PrivacyConfig.payload("SAFE_MODE", true).keys.toList(),
        )
        // nobody is not an option for search, calls and invites
        assertFailsWith<IllegalArgumentException> { PrivacyConfig.payload("INCOMING_CALL", "NOBODY") }
        assertFailsWith<IllegalArgumentException> { PrivacyConfig.payload("HIDDEN", "yes") }
        assertFailsWith<IllegalArgumentException> { PrivacyConfig.payload("HIDDEN", "ALL") }
        assertFailsWith<IllegalArgumentException> { PrivacyConfig.payload("SEARCH_BY_PHONE", true) }
        assertFailsWith<IllegalArgumentException> { PrivacyConfig.payload("SHOW_READ_MARK", false) }
        assertFailsWith<IllegalArgumentException> { PrivacyConfig.payload("FAMILY_PROTECTION", "OFF") }
        assertFailsWith<IllegalArgumentException> { PrivacyConfig.payload("INACTIVE_TTL", "3M") }
        assertTrue(PrivacyConfig().isReadOnly("SHOW_READ_MARK"))
        assertEquals(false, read("SHOW_READ_MARK" to false).showReadMark)
    }
}
