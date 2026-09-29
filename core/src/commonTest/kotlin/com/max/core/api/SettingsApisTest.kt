package com.max.core.api

import com.max.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings requests: payload shapes as KometTeam/Komet sends them (only the schema is taken, see
 * `lib/backend/modules/account`, `contacts.dart`, `folders.dart`, `webapp.dart`).
 */
class SettingsApisTest {
    private val me = mapOf("id" to 5L, "names" to listOf(mapOf("firstName" to "Ivan", "lastName" to "K")), "photoId" to 77L, "baseUrl" to "https://i/a")
    private val profile = mapOf("profile" to mapOf("contact" to me, "profileOptions" to listOf(2)))

    private fun ScriptSink.payload(i: Int): Map<*, *> = sent[i].second as Map<*, *>

    @Test
    fun avatarIsSetWithoutTheName() = runTest {
        val sink = ScriptSink(profile)
        val p = AccountApi(sink).setAvatar("tok")
        assertEquals(Opcode.PROFILE, sink.opcodes.single())
        assertEquals(mapOf("photoToken" to "tok", "avatarType" to "USER_AVATAR"), sink.payload(0))
        assertEquals(5L, p.contact.id)
        assertFailsWith<IllegalArgumentException> { AccountApi(ScriptSink()).setAvatar(" ") }
        assertFailsWith<MalformedReplyException> { AccountApi(ScriptSink(mapOf("x" to 1))).setAvatar("t") }
    }

    @Test
    fun photoRemovalAndProfileDeletion() = runTest {
        val sink = ScriptSink(profile, mapOf("timestamp" to 1760000000000L), emptyMap<String, Any?>())
        val api = AccountApi(sink)
        assertEquals(77L, api.removePhoto(77).contact.photoId)
        assertEquals(1760000000000L, api.requestProfileDeletion())
        assertNull(api.requestProfileDeletion(delete = false))
        assertEquals(listOf(Opcode.REMOVE_CONTACT_PHOTO, Opcode.PROFILE_DELETE, Opcode.PROFILE_DELETE), sink.opcodes)
        assertEquals(mapOf("photoId" to 77L), sink.payload(0))
        assertEquals(mapOf("delete" to true, "type" to 0), sink.payload(1))
        assertEquals(mapOf("delete" to false, "type" to 0), sink.payload(2))
    }

    @Test
    fun userSettingsGoUnderSettingsUser() = runTest {
        val sink = ScriptSink(mapOf("user" to mapOf("HIDDEN" to true, "INACTIVE_TTL" to "3M"), "hash" to "h2"), mapOf("hash" to 9L))
        val api = AccountApi(sink)
        val update = api.updateUserSettings(linkedMapOf("HIDDEN" to true))
        assertEquals(Opcode.CONFIG, sink.opcodes[0])
        assertEquals(mapOf("settings" to mapOf("user" to mapOf("HIDDEN" to true))), sink.payload(0))
        assertEquals(mapOf("HIDDEN" to true, "INACTIVE_TTL" to "3M"), update.user)
        assertEquals("h2", update.hash)
        val second = api.updateUserSettings(mapOf("INACTIVE_TTL" to "1M"))
        assertNull(second.user)
        assertEquals("9", second.hash)
        assertFailsWith<IllegalArgumentException> { api.updateUserSettings(emptyMap()) }
    }

    @Test
    fun folderEditKeepsPinsAndOptions() = runTest {
        val raw = mapOf(
            "id" to "f1", "title" to "Работа", "include" to listOf(1L, 2L), "filters" to listOf(4),
            "options" to listOf("X"), "favorites" to listOf(2L),
        )
        val folder = Folder.from(raw)!!
        val reply = mapOf("folder" to raw + ("title" to "Дом"), "folderSync" to 3L)
        val sink = ScriptSink(reply, reply, mapOf("folderSync" to 4L), mapOf("foldersOrder" to listOf("all.chat.folder", "f1")))
        val api = AccountApi(sink) { "new-id" }
        assertEquals("Дом", api.editFolder(folder, title = " Дом ").folder?.title)
        assertEquals(
            mapOf("id" to "f1", "title" to "Дом", "include" to listOf(1L, 2L), "filters" to listOf(4), "options" to listOf("X"), "favorites" to listOf(2L)),
            sink.payload(0),
        )
        api.editFolder(folder, chatIds = listOf(9L))
        assertEquals(listOf(9L), sink.payload(1)["include"])
        assertEquals("Работа", sink.payload(1)["title"])
        assertEquals(listOf(2L), sink.payload(1)["favorites"])
        assertEquals(4L, api.deleteFolders(listOf("f1")).folderSync)
        assertEquals(mapOf("folderIds" to listOf("f1")), sink.payload(2))
        assertEquals(listOf("all.chat.folder", "f1"), api.reorderFolders(listOf("all.chat.folder", "f1")).foldersOrder)
        assertEquals(mapOf("foldersOrder" to listOf("all.chat.folder", "f1")), sink.payload(3))
        assertEquals(listOf(Opcode.FOLDERS_UPDATE, Opcode.FOLDERS_UPDATE, Opcode.FOLDERS_DELETE, Opcode.FOLDERS_REORDER), sink.opcodes)
        assertFailsWith<IllegalArgumentException> { api.reorderFolders(emptyList()) }
        assertFailsWith<IllegalArgumentException> { api.deleteFolders(emptyList()) }
    }

    @Test
    fun newFolderLikeKomet() = runTest {
        val sink = ScriptSink(mapOf("folder" to mapOf("id" to "new-id", "title" to "Личные")))
        AccountApi(sink) { "new-id" }.addFolder(" Личные ", filters = listOf(4L))
        assertEquals(
            mapOf("id" to "new-id", "title" to "Личные", "include" to emptyList<Long>(), "filters" to listOf(4L), "options" to emptyList<Any?>(), "favorites" to emptyList<Long>()),
            sink.payload(0),
        )
    }

    @Test
    fun blackListAndContactsSync() = runTest {
        val sink = ScriptSink(
            mapOf("contacts" to listOf(me)),
            emptyMap<String, Any?>(),
            emptyMap<String, Any?>(),
            mapOf("contacts" to listOf(me, mapOf("bad" to 1))),
            mapOf("contactInfos" to listOf(me)),
            emptyMap<String, Any?>(),
        )
        val api = UsersApi(sink)
        assertEquals(listOf(5L), api.blockedContacts(from = 100).map { it.id })
        api.setBlocked(5, blocked = false)
        api.setBlocked(5, blocked = true)
        assertEquals(listOf(5L), api.syncContacts().map { it.id })
        assertEquals(listOf(5L), api.syncContacts().map { it.id })
        assertTrue(api.syncContacts().isEmpty())
        assertEquals(
            listOf(Opcode.CONTACT_LIST, Opcode.CONTACT_UPDATE, Opcode.CONTACT_UPDATE, Opcode.CONTACTS_GET, Opcode.CONTACTS_GET, Opcode.CONTACTS_GET),
            sink.opcodes,
        )
        assertEquals(mapOf("status" to "BLOCKED", "count" to 100, "from" to 100), sink.payload(0))
        assertEquals(mapOf("contactId" to 5L, "action" to "UNBLOCK"), sink.payload(1))
        assertEquals(mapOf("contactId" to 5L, "action" to "BLOCK"), sink.payload(2))
        assertEquals(mapOf("contactsSync" to 0), sink.payload(3))
        assertFailsWith<IllegalArgumentException> { api.blockedContacts(from = -1) }
    }

    @Test
    fun sessionsWithTheFieldsTheServerSends() = runTest {
        val sink = ScriptSink(
            mapOf(
                "sessions" to listOf(
                    mapOf("client" to "MAX iOS", "info" to "iPhone 15", "location" to "Новосибирск", "current" to true, "time" to 1759000000000L),
                    mapOf("id" to 3, "lastActivity" to 1700L),
                ),
            ),
        )
        val list = UsersApi(sink).getSessions()
        assertEquals("MAX iOS", list[0].client)
        assertEquals("iPhone 15", list[0].info)
        assertEquals(1759000000000L, list[0].lastSeen)
        assertNull(list[0].id)
        assertEquals(1700L, list[1].lastSeen)
        assertFalse(list[1].current)
    }

    @Test
    fun twoFactorDetailsAndEmailChange() = runTest {
        val sink = ScriptSink(
            mapOf("trackId" to "t1"),
            mapOf("password" to mapOf("enabled" to true, "email" to "ivan@ya.ru", "hint" to "")),
            mapOf("blockingDuration" to 30),
            emptyMap<String, Any?>(),
            emptyMap<String, Any?>(),
            emptyMap<String, Any?>(),
        )
        val api = TwoFactorApi(sink)
        val status = api.status()
        assertTrue(status.enabled)
        assertEquals("ivan@ya.ru", status.email)
        assertNull(status.hint)
        assertEquals(30, api.sendEmailCode("t1", "new@ya.ru"))
        api.confirmEmailCode("t1", "123456")
        api.commitEmail("t1")
        assertEquals(60, api.sendEmailCode("t1", "new@ya.ru"))
        assertEquals(
            listOf(Opcode.AUTH_CREATE_TRACK, Opcode.AUTH_2FA_DETAILS, Opcode.AUTH_VERIFY_EMAIL, Opcode.AUTH_CHECK_EMAIL, Opcode.AUTH_SET_2FA, Opcode.AUTH_VERIFY_EMAIL),
            sink.opcodes,
        )
        assertEquals(mapOf("trackId" to "t1"), sink.payload(1))
        assertEquals(mapOf("trackId" to "t1", "email" to "new@ya.ru"), sink.payload(2))
        assertEquals(mapOf("expectedCapabilities" to listOf(4), "trackId" to "t1"), sink.payload(4))
        val off = TwoFactorApi(ScriptSink(emptyMap<String, Any?>())).details("t")
        assertFalse(off.enabled)
        assertNull(off.email)
    }

    @Test
    fun externalCallbackFindsTheBotAtAnyLevel() = runTest {
        val sink = ScriptSink(
            mapOf("botId" to 8250447L, "startParam" to "s1"),
            mapOf("data" to mapOf("bot_id" to "42", "start_param" to "")),
            mapOf("result" to mapOf("x" to 1)),
        )
        val api = BotsApi(sink)
        assertEquals(ExternalCallbackResult(8250447L, "s1", mapOf("botId" to 8250447L, "startParam" to "s1")), api.externalCallback("https://max.ru/?externalCallback=1"))
        val nested = api.externalCallback("u")
        assertEquals(42L, nested.botId)
        assertNull(nested.startParam)
        assertFailsWith<MalformedReplyException> { api.externalCallback("u") }
        assertEquals(mapOf("url" to "https://max.ru/?externalCallback=1"), sink.payload(0))
        assertEquals(List(3) { Opcode.EXTERNAL_CALLBACK }, sink.opcodes)
        assertFailsWith<IllegalArgumentException> { api.externalCallback(" ") }
    }
}
