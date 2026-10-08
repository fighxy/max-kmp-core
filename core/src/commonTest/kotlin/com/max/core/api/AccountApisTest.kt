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
 * [UsersApi], [AccountApi], [TwoFactorApi]. Expected request bytes come from PyMax's payload
 * models (`pymax.api.users/self/auth.payloads`, `.to_payload()`) packed with msgpack-python.
 */
class AccountApisTest {
    private val user = mapOf("id" to 5L, "names" to listOf(mapOf("name" to "Ivan K", "type" to "ONEME")), "phone" to 79990001122L)

    // --- users ---

    @Test
    fun usersRequestsMatchPyMax() = runTest {
        val sink = ScriptSink(
            mapOf("contacts" to listOf(user, mapOf("id" to 2L))),
            mapOf("contact" to user),
            mapOf("contact" to user),
            emptyMap<String, Any?>(),
            mapOf("contacts" to listOf(user), "phones" to mapOf("+79990001122" to 79990001122L)),
        )
        val api = UsersApi(sink)
        assertEquals(listOf(5L, 2L), api.getUsers(listOf(1, 2)).map { it.id })
        assertEquals("Ivan K", api.findByPhone("+79990001122").displayName)
        assertEquals(5L, api.addContact(5).id)
        api.removeContact(5)
        assertEquals(listOf(5L), api.importContacts(listOf(PhoneContact("+79990001122", "Ivan"))).map { it.id })
        assertEquals(listOf(Opcode.CONTACT_INFO, Opcode.CONTACT_INFO_BY_PHONE, Opcode.CONTACT_UPDATE, Opcode.CONTACT_UPDATE, Opcode.SYNC), sink.opcodes)
        assertEquals("81aa636f6e74616374496473920102", sink.hex(0))
        assertEquals("81a570686f6e65ac2b3739393930303031313232", sink.hex(1))
        assertEquals("82a9636f6e74616374496405a6616374696f6ea3414444", sink.hex(2))
        val named = ScriptSink(mapOf("contact" to user))
        assertEquals(5L, UsersApi(named).addContact(5, "  Анна ").id)
        assertEquals(Opcode.CONTACT_UPDATE, named.opcodes.single())
        assertEquals(mapOf<String, Any>("contactId" to 5L, "action" to "ADD", "firstName" to "Анна"), named.sent.single().second)
        val blankName = ScriptSink(mapOf("contact" to user))
        UsersApi(blankName).addContact(5, "   ")
        assertEquals(mapOf<String, Any>("contactId" to 5L, "action" to "ADD"), blankName.sent.single().second)
        assertEquals("82a9636f6e74616374496405a6616374696f6ea652454d4f5645", sink.hex(3))
        assertEquals("81ab636f6e746163744c69737481ac2b373939393030303131323281a966697273744e616d65a44976616e", sink.hex(4))
    }

    @Test
    fun usersRepliesAndErrors() = runTest {
        val api = UsersApi(ScriptSink(emptyMap<String, Any?>(), mapOf("contacts" to listOf(mapOf("x" to 1))), emptyMap<String, Any?>(), mapOf("contacts" to "x")))
        assertNull(api.getUser(9))
        assertFailsWith<MalformedReplyException> { api.getUsers(listOf(1)) }
        assertFailsWith<MalformedReplyException> { api.findByPhone("+7") }
        assertFailsWith<MalformedReplyException> { api.importContacts(emptyList()) }
        assertFailsWith<IllegalArgumentException> { api.getUsers(emptyList()) }
        assertEquals(5L xor 7L, UsersApi.dialogChatId(5, 7))
        assertEquals(UsersApi.dialogChatId(7, 5), UsersApi.dialogChatId(5, 7))
    }

    @Test
    fun sessions() = runTest {
        val sink = ScriptSink(
            mapOf(
                "sessions" to listOf(
                    mapOf("id" to 1L, "current" to true, "deviceName" to "Pixel 8", "platform" to "ANDROID", "lastActivity" to 1700L),
                    mapOf("id" to "abc", "location" to "Moscow"),
                ),
            ),
        )
        val list = UsersApi(sink).getSessions()
        assertEquals(Opcode.SESSIONS_INFO, sink.opcodes.single())
        assertEquals("80", sink.hex(0))
        assertEquals("1", list[0].id)
        assertTrue(list[0].current)
        assertEquals("Pixel 8", list[0].deviceName)
        assertEquals(1700L, list[0].lastActivity)
        assertEquals("abc", list[1].id)
        assertFalse(list[1].current)
        assertEquals("Moscow", list[1].location)
    }

    // --- account ---

    @Test
    fun profileMatchesPyMax() = runTest {
        val reply = mapOf("profile" to mapOf("contact" to user, "profileOptions" to listOf(2, 4)))
        val sink = ScriptSink(reply, reply, emptyMap<String, Any?>())
        val api = AccountApi(sink)
        val profile = api.updateProfile("Ivan", "K", "hi", "pt")
        assertEquals(5L, profile.contact.id)
        assertEquals(listOf(2, 4), profile.profileOptions)
        assertTrue(TwoFactorApi.isEnabled(profile))
        api.updateProfile("Ivan")
        assertEquals(
            "85a966697273744e616d65a44976616ea86c6173744e616d65a14bab6465736372697074696f6ea26869aa70686f746f546f6b656ea27074aa61766174617254797065ab555345525f415641544152",
            sink.hex(0),
        )
        assertEquals("82a966697273744e616d65a44976616eaa61766174617254797065ab555345525f415641544152", sink.hex(1))
        assertFailsWith<MalformedReplyException> { api.updateProfile("x") }
    }

    @Test
    fun privacyKeysMatchPyMaxWithTheWebNobody() = runTest {
        val sink = ScriptSink(mapOf("hash" to "h1"), mapOf("hash" to 42L), emptyMap<String, Any?>())
        val api = AccountApi(sink)
        assertEquals("h1", api.updatePrivacy(PrivacySettings(searchByPhone = PrivacyAccess.CONTACTS, hideOnlineStatus = true)))
        assertEquals(
            "42",
            api.updatePrivacy(
                PrivacySettings(PrivacyAccess.ALL, PrivacyAccess.NOBODY, PrivacyAccess.CONTACTS, PrivacyAccess.NOBODY, hideOnlineStatus = false, safeContentOnly = true),
            ),
        )
        assertNull(api.updatePrivacy(PrivacySettings(safeContentOnly = false)))
        assertEquals(List(3) { Opcode.CONFIG }, sink.opcodes)
        assertEquals("81a873657474696e677381a47573657282af5345415243485f42595f50484f4e45a8434f4e5441435453a648494444454ec3", sink.hex(0))
        assertEquals(
            "81a873657474696e677381a47573657286af5345415243485f42595f50484f4e45a3414c4cad494e434f4d494e475f43414c4ca64e4f424f4459ac43484154535f494e56495445a8434f4e5441435453b450484f4e455f4e554d4245525f50524956414359a64e4f424f4459a648494444454ec2b4434f4e54454e545f4c4556454c5f414343455353c3",
            sink.hex(1),
        )
        assertFailsWith<IllegalArgumentException> { api.updatePrivacy(PrivacySettings()) }
    }

    @Test
    fun sessionsCloseReturnsNewToken() = runTest {
        val sink = ScriptSink(mapOf("token" to "new-token"), emptyMap<String, Any?>())
        val api = AccountApi(sink)
        assertEquals("new-token", api.closeOtherSessions())
        assertNull(api.closeOtherSessions())
        assertEquals(Opcode.SESSIONS_CLOSE, sink.opcodes[0])
        assertEquals("80", sink.hex(0))
    }

    @Test
    fun foldersMatchPyMax() = runTest {
        val folder = mapOf("id" to "f-1", "title" to "Work", "include" to listOf(10L, 20L), "updateTime" to 5L, "sourceId" to 1L)
        val update = mapOf("folder" to folder, "foldersOrder" to listOf("f-1"), "folderSync" to 77L)
        val sink = ScriptSink(
            mapOf("folders" to listOf(folder), "foldersOrder" to listOf("f-1"), "folderSync" to 76L, "allFilterExcludeFolders" to emptyList<Any>()),
            update, update, mapOf("foldersOrder" to emptyList<String>(), "folderSync" to 78L),
        )
        val api = AccountApi(sink, newFolderId = { "f-1" })
        val list = api.getFolders()
        assertEquals(76L, list.folderSync)
        assertEquals(listOf(10L, 20L), list.folders.single().include)
        assertEquals("Work", api.createFolder("Work", listOf(10, 20)).folder?.title)
        assertEquals(77L, api.updateFolder("f-1", "Work", listOf(10)).folderSync)
        val deleted = api.deleteFolder("f-1")
        assertNull(deleted.folder)
        assertEquals(78L, deleted.folderSync)
        assertEquals(listOf(Opcode.FOLDERS_GET, Opcode.FOLDERS_UPDATE, Opcode.FOLDERS_UPDATE, Opcode.FOLDERS_DELETE), sink.opcodes)
        assertEquals("81aa666f6c64657253796e6300", sink.hex(0))
        assertEquals("84a26964a3662d31a57469746c65a4576f726ba7696e636c756465920a14a766696c7465727390", sink.hex(1))
        assertEquals("85a26964a3662d31a57469746c65a4576f726ba7696e636c756465910aa766696c7465727390a76f7074696f6e7390", sink.hex(2))
        assertEquals("81a9666f6c64657249647391a3662d31", sink.hex(3))
    }

    @Test
    fun uuidShape() {
        val id = randomUuid()
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id), id)
    }

    // --- 2FA ---

    @Test
    fun enableWithEmailAndHintFollowsPyMax() = runTest {
        val sink = ScriptSink(mapOf("trackId" to "tr"))
        val codes = ArrayList<String>()
        TwoFactorApi(sink).enable("pw", hint = "cat", email = "a@b.c") { codes += it; "123456" }
        assertEquals(listOf("a@b.c"), codes)
        assertEquals(
            listOf(Opcode.AUTH_CREATE_TRACK, Opcode.AUTH_VALIDATE_PASSWORD, Opcode.AUTH_VERIFY_EMAIL, Opcode.AUTH_CHECK_EMAIL, Opcode.AUTH_VALIDATE_HINT, Opcode.AUTH_SET_2FA),
            sink.opcodes,
        )
        assertEquals(
            listOf(
                "81a47479706500",
                "82a7747261636b4964a27472a870617373776f7264a27077",
                "82a7747261636b4964a27472a5656d61696ca56140622e63",
                "82a7747261636b4964a27472aa766572696679436f6465a6313233343536",
                "82a7747261636b4964a27472a468696e74a3636174",
                "84b465787065637465644361706162696c697469657393000304a7747261636b4964a27472a870617373776f7264a27077a468696e74a3636174",
            ),
            sink.sent.indices.map { sink.hex(it) },
        )
    }

    @Test
    fun enableChangeDisable() = runTest {
        val sink = ScriptSink(mapOf("trackId" to "tr"), emptyMap<String, Any?>(), emptyMap<String, Any?>(), mapOf("trackId" to "tr"), emptyMap<String, Any?>(), emptyMap<String, Any?>(), emptyMap<String, Any?>(), mapOf("trackId" to "tr"))
        val api = TwoFactorApi(sink)
        api.enable("pw")
        assertEquals("83b465787065637465644361706162696c69746965739100a7747261636b4964a27472a870617373776f7264a27077", sink.hex(2))
        api.changePassword("pw", "pw2")
        assertEquals(listOf(Opcode.AUTH_CREATE_TRACK, Opcode.AUTH_CHECK_PASSWORD, Opcode.AUTH_VALIDATE_PASSWORD, Opcode.AUTH_SET_2FA), sink.opcodes.subList(3, 7))
        assertEquals("82a7747261636b4964a27472a870617373776f7264a27077", sink.hex(4))
        assertEquals("83b465787065637465644361706162696c69746965739101a7747261636b4964a27472a870617373776f7264a3707732", sink.hex(6))
        api.disable("pw")
        assertEquals(listOf(Opcode.AUTH_CREATE_TRACK, Opcode.AUTH_CHECK_PASSWORD, Opcode.AUTH_SET_2FA), sink.opcodes.subList(7, 10))
        assertEquals("83a7747261636b4964a27472a972656d6f7665326661c3b465787065637465644361706162696c69746965739105", sink.hex(9))
    }

    @Test
    fun twoFactorErrors() = runTest {
        assertFailsWith<MalformedReplyException> { TwoFactorApi(ScriptSink(emptyMap<String, Any?>())).createTrack() }
        assertFailsWith<IllegalArgumentException> { TwoFactorApi(ScriptSink()).enable("pw", email = "a@b.c") }
        // wrong current password stops the flow before AUTH_SET_2FA
        val sink = ScriptSink(mapOf("trackId" to "tr"), serverError(Opcode.AUTH_CHECK_PASSWORD, "error.password.invalid"))
        assertFailsWith<com.max.core.transport.ServerErrorException> { TwoFactorApi(sink).disable("bad") }
        assertEquals(listOf(Opcode.AUTH_CREATE_TRACK, Opcode.AUTH_CHECK_PASSWORD), sink.opcodes)
    }
}
