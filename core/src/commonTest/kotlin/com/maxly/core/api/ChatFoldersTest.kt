package com.maxly.core.api

import com.maxly.core.events.EventParser
import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pinned chats: the `favorites` of the "all chats" folder. Payload shapes follow the schemas used
 * by KometTeam/Komet (`LOGIN` `config.chatFolders`, `FOLDERS_UPDATE` 274, `NOTIF_FOLDERS` 277);
 * expected request bytes were packed with msgpack-python.
 */
class ChatFoldersTest {
    private val group = -70000000000001L
    private val all = linkedMapOf<String, Any?>(
        "id" to ChatFolders.ALL_CHATS_FOLDER_ID,
        "title" to "Все",
        "include" to emptyList<Long>(),
        "filters" to emptyList<Any>(),
        "options" to listOf("NO_DELETE"),
        "favorites" to listOf(group, 100L, "200", 100L),
        "updateTime" to 5L,
    )
    private val work = linkedMapOf<String, Any?>("id" to "f-work", "title" to "Work", "include" to listOf(100L), "filters" to listOf(3), "favorites" to listOf(300L))

    private fun loginReply(folders: List<Any?>, order: List<String> = listOf("all.chat.folder", "f-work")) = mapOf(
        "config" to mapOf(
            "hash" to "cfg",
            "chats" to mapOf("100" to mapOf("favIndex" to 7, "dontDisturbUntil" to 0)),
            "chatFolders" to mapOf("FOLDERS" to folders, "foldersOrder" to order, "folderSync" to 42L),
        ),
    )

    @Test
    fun loginConfigCarriesFoldersAndPins() {
        val f = ChatFolders.fromLoginConfig(loginReply(listOf(work, all)))!!
        assertEquals(listOf("all.chat.folder", "f-work"), f.folders.map { it.id }) // sorted by foldersOrder
        assertEquals(42L, f.folderSync)
        assertEquals(ChatFolders.ALL_CHATS_FOLDER_ID, f.allChats?.id)
        // string ids are accepted, duplicates dropped, order kept; favIndex of `config.chats` is not used
        assertEquals(listOf(group, 100L, 200L), f.pinnedChatIds)
        assertEquals(listOf(300L), f.folders[1].favorites)
    }

    @Test
    fun missingOrPartialConfigMeansUnknown() {
        assertNull(ChatFolders.fromLoginConfig(emptyMap<String, Any?>()))
        assertNull(ChatFolders.fromLoginConfig(mapOf("config" to mapOf("hash" to "x"))))
        assertNull(ChatFolders.fromLoginConfig(mapOf("config" to mapOf("chatFolders" to mapOf("folderSync" to 1)))))
        // a folder list without the "all chats" folder: pins are unknown, not empty
        assertNull(ChatFolders.fromLoginConfig(loginReply(listOf(work)))!!.pinnedChatIds)
        // an empty favourites list is a real "nothing pinned"
        assertEquals(emptyList(), ChatFolders.fromLoginConfig(loginReply(listOf(all + ("favorites" to emptyList<Long>()))))!!.pinnedChatIds)
    }

    @Test
    fun allChatsFolderFallsBackToItsTitle() {
        val f = ChatFolders.of(listOf(Folder.from(work)!!, Folder.from(mapOf("id" to "x-1", "title" to " Все чаты ", "favorites" to listOf(9)))!!), emptyList(), 0)
        assertEquals("x-1", f.allChats?.id)
        assertEquals(listOf(9L), f.pinnedChatIds)
        assertNull(ChatFolders.of(listOf(Folder.from(work)!!), emptyList(), 0).allChats)
    }

    @Test
    fun mergeReplacesAddsAndFollowsTheNewOrder() {
        val base = ChatFolders.fromLoginConfig(loginReply(listOf(all, work)))!!
        val repinned = Folder.from(all + ("favorites" to listOf(100L)))!!
        val merged = base.merge(listOf(repinned), null, 43L)
        assertEquals(listOf(100L), merged.pinnedChatIds)
        assertEquals(listOf("all.chat.folder", "f-work"), merged.folders.map { it.id })
        assertEquals(43L, merged.folderSync)
        // a new order drops folders it does not name, except ones that came with the change
        val fresh = Folder.from(mapOf("id" to "f-new", "title" to "New"))!!
        val reordered = merged.merge(listOf(fresh), listOf("f-new", "all.chat.folder"), null)
        assertEquals(listOf("f-new", "all.chat.folder"), reordered.folders.map { it.id })
        assertEquals(43L, reordered.folderSync)
        // no order and no sync: both stay
        val same = reordered.merge(emptyList())
        assertEquals(reordered, same)
        assertEquals(listOf(5L, 6L), same.withPinned(listOf(5L, 6L)).pinnedChatIds)
    }

    @Test
    fun favoritesPayloadEchoesTheFolderAndSetsThePins() {
        val folder = Folder.from(all)!!
        val payload = ChatFolders.favoritesPayload(folder, listOf(group, 100L, group))
        assertEquals(listOf("id", "title", "include", "filters", "options", "favorites"), payload.keys.toList())
        assertEquals(listOf("NO_DELETE"), payload["options"]) // raw server values, not re-encoded
        assertEquals(
            "86a26964af616c6c2e636861742e666f6c646572a57469746c65a6d092d181d0b5a7696e636c75646590a766696c7465727390" +
                "a76f7074696f6e7391a94e4f5f44454c455445a96661766f726974657392d3ffffc055dadd9fff64",
            msgpackHex(payload),
        )
    }

    @Test
    fun setFolderFavoritesSendsFoldersUpdate() = runTest {
        val reply = mapOf("folder" to all + ("favorites" to listOf(100L)), "folderSync" to 44L)
        val sink = ScriptSink(reply, serverError(Opcode.FOLDERS_UPDATE, "folder.favorites.limit"))
        val api = AccountApi(sink)
        val folder = Folder.from(all)!!
        val update = api.setFolderFavorites(folder, listOf(100L))
        assertEquals(listOf(Opcode.FOLDERS_UPDATE), sink.opcodes)
        assertEquals(listOf(100L), (sink.sent[0].second as Map<*, *>)["favorites"])
        assertEquals(listOf(100L), update.folder?.favorites)
        assertEquals(44L, update.folderSync)
        val error = runCatching { api.setFolderFavorites(folder, listOf(1L, 2L)) }.exceptionOrNull()
        assertEquals("folder.favorites.limit", (error as com.maxly.core.transport.ServerErrorException).errorKey)
    }

    @Test
    fun foldersPushIsTyped() {
        val list = EventParser.parse(Opcode.NOTIF_FOLDERS.value, 0, mapOf("folders" to listOf(all), "foldersOrder" to listOf("all.chat.folder"), "folderSync" to 50L))
        assertIs<MaxEvent.FoldersChanged>(list)
        assertEquals(listOf(group, 100L, 200L, 100L), list.folders.single().favorites)
        assertEquals(listOf("all.chat.folder"), list.foldersOrder)
        assertEquals(50L, list.folderSync)

        val single = EventParser.parse(Opcode.NOTIF_FOLDERS.value, 0, mapOf("folder" to work))
        assertIs<MaxEvent.FoldersChanged>(single)
        assertEquals(listOf("f-work"), single.folders.map { it.id })
        assertNull(single.foldersOrder)
        assertNull(single.folderSync)

        val syncOnly = EventParser.parse(Opcode.NOTIF_FOLDERS.value, 0, mapOf("folderSync" to "51"))
        assertIs<MaxEvent.FoldersChanged>(syncOnly)
        assertEquals(51L, syncOnly.folderSync)

        assertIs<MaxEvent.Unknown>(EventParser.parse(Opcode.NOTIF_FOLDERS.value, 0, mapOf("other" to 1)))
        assertIs<MaxEvent.Unknown>(EventParser.parse(Opcode.NOTIF_FOLDERS.value, 1, mapOf("folder" to work)))
    }
}
