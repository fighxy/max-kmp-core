package com.maxly.core.api

import com.maxly.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Group management in [ChatsApi]; request bytes from PyMax `pymax.api.chats.payloads` + msgpack-python. */
class ChatsGroupTest {
    private val now = 1759100000000L
    private val chat = mapOf("id" to -100L, "type" to "CHAT", "title" to "Team", "participantsCount" to 3)
    private val withChat = mapOf("chat" to chat)

    private val pymax = mapOf(
        "create" to "82a76d65737361676582a3636964cf000001999287d701a861747461636865739185a55f74797065a7434f4e54524f4ca56576656e74a36e6577a86368617454797065a443484154a57469746c65a45465616da775736572496473920102a66e6f74696679c3",
        "invite" to "84a6636861744964d09ca775736572496473920102ab73686f77486973746f7279c3a96f7065726174696f6ea3616464",
        "remove" to "84a6636861744964d09ca7757365724964739101a96f7065726174696f6ea672656d6f7665ae636c65616e4d7367506572696f6400",
        "settings" to "82a6636861744964d09ca76f7074696f6e7382b3414c4c5f43414e5f50494e5f4d455353414745c3b34f4e4c595f41444d494e5f43414e5f43414c4cc2",
        "settings_all" to "82a6636861744964d09ca76f7074696f6e7385d9204f4e4c595f4f574e45525f43414e5f4348414e47455f49434f4e5f5449544c45c3b3414c4c5f43414e5f50494e5f4d455353414745c3b94f4e4c595f41444d494e5f43414e5f4144445f4d454d424552c2b34f4e4c595f41444d494e5f43414e5f43414c4cc2bc4d454d424552535f43414e5f5345455f505249564154455f4c494e4bc3",
        "profile" to "84a6636861744964d09ca57468656d65a34e6577ab6465736372697074696f6ea164aa70686f746f546f6b656ea27074",
        "profile_min" to "82a6636861744964d09ca57468656d65a34e6577",
        "rework" to "82b17265766f6b65507269766174654c696e6bc3a6636861744964d09c",
        "join" to "81a46c696e6ba86a6f696e2f616263",
        "joinreq" to "83a6636861744964d09ca474797065ac4a4f494e5f52455155455354a5636f756e7464",
        "confirm" to "85a6636861744964d09ca7757365724964739101a474797065ac4a4f494e5f52455155455354ab73686f77486973746f7279c3a96f7065726174696f6ea3616464",
        "decline" to "84a6636861744964d09ca7757365724964739101a474797065ac4a4f494e5f52455155455354a96f7065726174696f6ea672656d6f7665",
        "admin" to "85a6636861744964d09ca7757365724964739101a474797065a541444d494ea96f7065726174696f6ea3616464ab7065726d697373696f6e7312",
        "comments" to "82a6636861744964d09ca76f7074696f6e7381a8434f4d4d454e5453c3",
        "block" to "87a6636861744964d09ca6706f7374496407a7757365724964739101a96d657373616765496409a474797065b2434f4d4d454e54535f424c41434b4c495354a96f7065726174696f6ea3616464ae636c65616e4d7367506572696f6400",
    )

    private fun api(sink: ScriptSink) = MaxApi(sink) { now }.chats

    @Test
    fun createGroup() = runTest {
        val reply = mapOf("chatId" to -100L, "chat" to chat, "message" to mapOf("id" to 1L, "time" to now, "type" to "USER", "text" to ""))
        val sink = ScriptSink(reply, mapOf("message" to mapOf("id" to 1L, "time" to now, "type" to "USER")))
        val created = api(sink).createGroup("Team", listOf(1, 2))!!
        assertEquals(-100L, created.chat.id)
        assertEquals(1L, created.message.id)
        assertEquals(-100L, created.message.chatId)
        assertEquals(Opcode.MSG_SEND, sink.opcodes[0])
        assertEquals(pymax["create"], sink.hex(0))
        assertNull(api(sink).createGroup("x"))
    }

    @Test
    fun membersMatchPyMax() = runTest {
        val member = mapOf("contact" to mapOf("id" to 1L), "presence" to mapOf("seen" to 5L))
        val sink = ScriptSink(withChat, withChat, withChat, mapOf("members" to listOf(member)), withChat, emptyMap<String, Any?>(), withChat)
        val chats = api(sink)
        assertEquals(-100L, chats.addMembers(-100, listOf(1, 2))?.id)
        assertEquals("Team", chats.removeMembers(-100, listOf(1))?.title)
        assertEquals(-100L, chats.addAdmin(-100, 1, setOf(ChatPermission.ADD_REMOVE_MEMBER, ChatPermission.PIN_MESSAGE))?.id)
        assertEquals(listOf(1L), chats.getJoinRequests(-100).map { it.userId })
        chats.confirmJoinRequests(-100, listOf(1))
        assertNull(chats.declineJoinRequests(-100, listOf(1)))
        chats.blockCommentAuthors(-100, postId = 7, userIds = listOf(1), messageId = 9)
        assertEquals(
            listOf(Opcode.CHAT_MEMBERS_UPDATE, Opcode.CHAT_MEMBERS_UPDATE, Opcode.CHAT_MEMBERS_UPDATE, Opcode.CHAT_MEMBERS, Opcode.CHAT_MEMBERS_UPDATE, Opcode.CHAT_MEMBERS_UPDATE, Opcode.CHAT_MEMBERS_UPDATE),
            sink.opcodes,
        )
        listOf("invite", "remove", "admin", "joinreq", "confirm", "decline", "block").forEachIndexed { i, k -> assertEquals(pymax[k], sink.hex(i), k) }
        assertFailsWith<IllegalArgumentException> { chats.addAdmin(-100, 1, emptySet()) }
    }

    @Test
    fun updatesMatchPyMax() = runTest {
        val sink = ScriptSink(withChat, withChat, withChat, withChat, withChat, withChat, emptyMap<String, Any?>())
        val chats = api(sink)
        chats.updateSettings(-100, GroupSettings(allCanPinMessage = true, onlyAdminCanCall = false))
        chats.updateSettings(-100, GroupSettings(true, true, false, false, true))
        chats.updateProfile(-100, "New", "d", "pt")
        chats.updateProfile(-100, "New")
        assertEquals(-100L, chats.revokeInviteLink(-100).id)
        chats.setChannelComments(-100, true)
        assertFailsWith<MalformedReplyException> { chats.revokeInviteLink(-100) }
        assertEquals(List(7) { Opcode.CHAT_UPDATE }, sink.opcodes)
        listOf("settings", "settings_all", "profile", "profile_min", "rework", "comments").forEachIndexed { i, k -> assertEquals(pymax[k], sink.hex(i), k) }
        assertFailsWith<IllegalArgumentException> { chats.updateSettings(-100, GroupSettings()) }
    }

    @Test
    fun joinLinks() = runTest {
        val sink = ScriptSink(withChat, withChat, withChat, emptyMap<String, Any?>(), emptyMap<String, Any?>())
        val chats = api(sink)
        assertEquals(-100L, chats.join("https://max.ru/join/abc").id)
        assertEquals(-100L, chats.join("join/abc").id)
        assertEquals(-100L, chats.resolveLink("https://max.ru/join/abc")?.id)
        assertNull(chats.resolveLink("join/abc"))
        assertFailsWith<MalformedReplyException> { chats.join("channelname") }
        assertEquals(listOf(Opcode.CHAT_JOIN, Opcode.CHAT_JOIN, Opcode.LINK_INFO, Opcode.LINK_INFO, Opcode.CHAT_JOIN), sink.opcodes)
        assertEquals(pymax["join"], sink.hex(0))
        assertEquals(pymax["join"], sink.hex(1))
        assertEquals(pymax["join"], sink.hex(2)) // LinkInfoPayload has the same shape
        assertEquals("81a46c696e6bab6368616e6e656c6e616d65", sink.hex(4))
        assertFailsWith<IllegalArgumentException> { chats.resolveLink("https://max.ru/abc") }
        assertEquals("join/x", joinPath("https://max.ru/join/x"))
        assertNull(joinPath("x"))
    }
}
