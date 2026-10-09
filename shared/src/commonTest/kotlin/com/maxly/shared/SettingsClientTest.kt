@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.shared

import com.maxly.core.api.AccountConfig
import com.maxly.core.api.FamilyProtection
import com.maxly.core.api.PrivacyAccess
import com.maxly.core.api.PrivacySettings
import com.maxly.core.media.HttpResponse
import com.maxly.core.media.MediaHttp
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.transport.FakeRawConnection
import com.maxly.core.transport.ScriptedConnectionFactory
import com.maxly.core.transport.TransportConfig
import com.maxly.core.transport.ok
import com.maxly.core.transport.push
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Account config, settings, contacts, folders and QR approval of [MaxClient]. */
class SettingsClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private val all = mapOf("id" to "all.chat.folder", "title" to "Все", "favorites" to listOf(100L), "options" to listOf("NO_DELETE"))
    private val work = mapOf("id" to "w", "title" to "Работа", "include" to listOf(100L), "filters" to listOf(4), "favorites" to listOf(100L))

    private fun loginReply(withConfig: Boolean) = buildMap {
        put("profile", mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("firstName" to "Me")), "photoId" to 9L)))
        put("chats", listOf(mapOf("id" to 100, "type" to "DIALOG", "status" to "ACTIVE", "owner" to 5, "lastEventTime" to 10)))
        put("time", 1700L)
        if (withConfig) {
            put(
                "config",
                mapOf(
                    "hash" to "cfg-1",
                    "user" to mapOf("HIDDEN" to false, "PHONE_NUMBER_PRIVACY" to "ALL"),
                    "server" to mapOf("invite-link" to "https://max.ru/u/me"),
                    "chatFolders" to mapOf("FOLDERS" to listOf(all, work), "foldersOrder" to listOf("all.chat.folder", "w"), "folderSync" to 1L),
                ),
            )
        }
    }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun TestScope.loggedIn(factory: ScriptedConnectionFactory): MaxClient {
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(Opcode.LOGIN, loginReply(withConfig = true))
        login.await()
        runCurrent()
        return c
    }

    @Test
    fun configComesWithLoginAndFollowsSettingChanges() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val seen = ArrayList<AccountConfig?>()
        val watch = c.watchAccountConfig { seen += it }
        runCurrent()
        val config = c.accountConfig.value!!
        assertEquals("cfg-1", config.hash)
        assertEquals("https://max.ru/u/me", config.inviteLink)
        assertEquals(false, config.userFlag("HIDDEN"))

        val conn = factory.lastConnection!!
        val change = async { c.updateUserSettings(mapOf("HIDDEN" to true)) }
        runCurrent()
        val sent = conn.answer(Opcode.CONFIG, mapOf("user" to mapOf("HIDDEN" to true, "PHONE_NUMBER_PRIVACY" to "ALL"), "hash" to "cfg-2"))!!
        assertEquals(mapOf("settings" to mapOf("user" to mapOf("HIDDEN" to true))), sent)
        assertEquals(true, change.await().userFlag("HIDDEN"))
        runCurrent()
        assertEquals("cfg-2", c.accountConfig.value!!.hash)
        assertEquals("https://max.ru/u/me", c.accountConfig.value!!.inviteLink)
        assertEquals(2, seen.size)

        // a reply without `user` still keeps the sent value
        val second = async { c.updateUserSettings(mapOf("INACTIVE_TTL" to "3M")) }
        runCurrent()
        conn.answer(Opcode.CONFIG, emptyMap<String, Any?>())
        assertEquals("3M", second.await().userString("INACTIVE_TTL"))
        assertEquals(true, c.accountConfig.value!!.userFlag("HIDDEN"))

        val out = async { c.logout() }
        runCurrent()
        conn.answer(Opcode.LOGOUT, null)
        out.await()
        runCurrent()
        assertNull(c.accountConfig.value)
        watch.cancel()
    }

    @Test
    fun privacyChangesAreCheckedAndLandInTheConfig() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val conn = factory.lastConnection!!
        // LOGIN config: HIDDEN false, phone ALL; the rest takes the web client's defaults
        val before = assertNotNull(c.privacy)
        assertEquals(PrivacyAccess.ALL, before.phoneNumber)
        assertEquals(PrivacyAccess.ALL, before.incomingCalls)
        assertFalse(before.locked)

        val nobody = async { c.setPhoneNumberPrivacy(PrivacyAccess.NOBODY) }
        runCurrent()
        // a reply with only the hash: the sent value is kept
        val sent = conn.answer(Opcode.CONFIG, mapOf("hash" to "cfg-2"))!!
        assertEquals(mapOf("settings" to mapOf("user" to mapOf("PHONE_NUMBER_PRIVACY" to "NOBODY"))), sent)
        nobody.await()
        assertEquals(PrivacyAccess.NOBODY, c.privacy!!.phoneNumber)
        assertEquals("cfg-2", c.accountConfig.value!!.hash)

        // safe mode: the Komet set, then the four keys are locked and nothing is sent for them
        val safe = async { c.setSafeMode(true) }
        runCurrent()
        val safeSent = (conn.answer(Opcode.CONFIG, mapOf("user" to mapOf("SAFE_MODE" to true), "hash" to "cfg-3"))!!["settings"] as Map<*, *>)["user"] as Map<*, *>
        assertEquals(true, safeSent["SAFE_MODE"])
        assertEquals("CONTACTS", safeSent["INCOMING_CALL"])
        safe.await()
        val locked = c.privacy!!
        assertTrue(locked.locked)
        assertEquals(PrivacyAccess.CONTACTS, locked.searchByPhone)
        assertEquals(PrivacyAccess.NOBODY, locked.phoneNumber) // a reply with some keys merges into the known ones
        assertFailsWith<IllegalStateException> { c.setIncomingCalls(PrivacyAccess.ALL) }
        assertFailsWith<IllegalArgumentException> { c.setPrivacy("SHOW_READ_MARK", false) }
        assertNull(conn.takeWritten(kotlin.time.Duration.parse("100ms")))

        // a settings push from another device updates the cached config
        conn.feed(push(Opcode.NOTIF_CONFIG.value, mapOf("config" to mapOf("hash" to "cfg-4", "user" to mapOf("SAFE_MODE" to false, "FAMILY_PROTECTION" to "ADMIN", "INCOMING_CALL" to "ALL")))))
        runCurrent()
        val pushed = c.privacy!!
        assertFalse(pushed.locked)
        assertEquals(PrivacyAccess.ALL, pushed.incomingCalls)
        assertEquals(FamilyProtection.ADMIN, pushed.familyProtection)
        assertEquals(PrivacyAccess.NOBODY, pushed.phoneNumber)

        // the PyMax-style update goes through the same path and also lands in the config
        val legacy = async { c.updatePrivacy(PrivacySettings(chatInvites = PrivacyAccess.CONTACTS)) }
        runCurrent()
        conn.answer(Opcode.CONFIG, mapOf("hash" to "cfg-5"))
        assertEquals("cfg-5", legacy.await())
        assertEquals(PrivacyAccess.CONTACTS, c.privacy!!.chatInvites)
    }

    @Test
    fun qrApprovalPingsAndListsSessionsFirst() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        c.qrApproveDelayMs = 0
        val conn = factory.lastConnection!!
        val approve = async { c.approveQrLogin(" https://max.ru/:auth/abc ") }
        runCurrent()
        assertEquals(mapOf("interactive" to true), conn.answer(Opcode.PING, emptyMap<String, Any?>()))
        runCurrent()
        conn.answer(Opcode.SESSIONS_INFO, mapOf("sessions" to emptyList<Any>()))
        runCurrent()
        assertEquals(mapOf("qrLink" to "https://max.ru/:auth/abc"), conn.answer(Opcode.AUTH_QR_APPROVE, emptyMap<String, Any?>()))
        assertEquals("https://max.ru/:auth/abc", approve.await().qrLink)
    }

    @Test
    fun contactsSyncFillsTheContactList() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val conn = factory.lastConnection!!
        val sync = async { c.syncContacts() }
        runCurrent()
        val sent = conn.answer(Opcode.CONTACTS_GET, mapOf("contacts" to listOf(mapOf("id" to 7, "names" to listOf(mapOf("name" to "Аня"))))))!!
        assertEquals(setOf<Any?>("contactsSync"), sent.keys.toSet())
        assertEquals(0L, (sent["contactsSync"] as Number).toLong())
        assertEquals(listOf(7L), sync.await().map { it.id })
        assertTrue(7L in c.store.state.value.contactIds)
    }

    @Test
    fun folderChangesKeepPinsAndReachTheStore() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val conn = factory.lastConnection!!

        val rename = async { c.editFolder("w", title = "Работа 2") }
        runCurrent()
        val sent = conn.answer(Opcode.FOLDERS_UPDATE, mapOf("folder" to work + ("title" to "Работа 2"), "folderSync" to 2L))!!
        assertEquals("Работа 2", sent["title"])
        assertEquals(listOf(100L), (sent["favorites"] as List<*>).map { (it as Number).toLong() })
        assertEquals(listOf(100L), (sent["include"] as List<*>).map { (it as Number).toLong() })
        assertNotNull(rename.await())
        assertEquals("Работа 2", c.store.state.value.chatFolders!!.folders.first { it.id == "w" }.title)

        val create = async { c.createFolder("Каналы", filters = listOf(2L)) }
        runCurrent()
        val created = conn.answer(Opcode.FOLDERS_UPDATE, mapOf("folder" to mapOf("id" to "n", "title" to "Каналы", "filters" to listOf(2))))!!
        assertEquals("Каналы", created["title"])
        create.await()
        assertEquals(listOf("all.chat.folder", "w", "n"), c.store.state.value.chatFolders!!.folders.map { it.id })

        val reorder = async { c.reorderFolders(listOf("all.chat.folder", "n", "w")) }
        runCurrent()
        assertEquals(listOf("all.chat.folder", "n", "w"), conn.answer(Opcode.FOLDERS_REORDER, emptyMap<String, Any?>())!!["foldersOrder"])
        reorder.await()
        assertEquals(listOf("all.chat.folder", "n", "w"), c.store.state.value.chatFolders!!.folders.map { it.id })

        val delete = async { c.deleteFolders(listOf("n")) }
        runCurrent()
        assertEquals(listOf("n"), conn.answer(Opcode.FOLDERS_DELETE, emptyMap<String, Any?>())!!["folderIds"])
        delete.await()
        assertEquals(listOf("all.chat.folder", "w"), c.store.state.value.chatFolders!!.folders.map { it.id })
        // the pins survive all of it
        assertEquals(listOf(100L), c.store.state.value.pinnedChatIds)
    }

    @Test
    fun avatarRemovalUsesTheOwnPhotoId() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory)
        val conn = factory.lastConnection!!
        val remove = async { c.removeAvatar() }
        runCurrent()
        val sent = conn.answer(Opcode.REMOVE_CONTACT_PHOTO, mapOf("profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("firstName" to "Me"))))))!!
        assertEquals(9L, (sent["photoId"] as Number).toLong())
        assertNull(remove.await()!!.contact.photoId)
        assertNull(c.store.state.value.users.getValue(5).photoId)
        // nothing to remove now: no request
        assertNull(c.removeAvatar())
    }
}
