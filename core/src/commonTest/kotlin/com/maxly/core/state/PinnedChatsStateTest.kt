package com.maxly.core.state

import com.maxly.core.api.ChatFolders
import com.maxly.core.api.FolderList
import com.maxly.core.api.FolderUpdate
import com.maxly.core.auth.LoginResult
import com.maxly.core.events.EventParser
import com.maxly.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pinned chats in [MaxState]: from the `LOGIN` config, `FOLDERS_GET`, `FOLDERS_UPDATE` and `NOTIF_FOLDERS`. */
class PinnedChatsStateTest {
    private val me = 10L

    private fun chat(id: Long, lastEventTime: Long) =
        mapOf("id" to id, "type" to "DIALOG", "status" to "ACTIVE", "owner" to me, "lastEventTime" to lastEventTime)

    private fun allFolder(vararg pins: Long) = mapOf(
        "id" to ChatFolders.ALL_CHATS_FOLDER_ID, "title" to "Все", "include" to emptyList<Long>(),
        "filters" to emptyList<Any>(), "options" to emptyList<Any>(), "favorites" to pins.toList(),
    )

    private fun login(user: Long = me, folders: List<Any?>? = listOf(allFolder(3, 1))) = LoginResult.from(
        buildMap {
            put("profile", mapOf("contact" to mapOf("id" to user)))
            put("chats", listOf(chat(1, 100), chat(2, 300), chat(3, 50), chat(4, 200)))
            if (folders != null) {
                put("config", mapOf("chatFolders" to mapOf("FOLDERS" to folders, "foldersOrder" to listOf("all.chat.folder"), "folderSync" to 7L)))
            }
        },
    )

    private fun push(payload: Map<String, Any?>) = EventParser.parse(Opcode.NOTIF_FOLDERS.value, 0, payload)

    @Test
    fun loginPinsComeFirstInServerOrder() {
        val store = MaxStore()
        assertNull(store.state.value.pinnedChatIds)
        store.applyLogin(login())
        val s = store.state.value
        assertEquals(listOf(3L, 1L), s.pinnedChatIds)
        assertEquals(7L, s.chatFolders?.folderSync)
        // pinned 3 then 1 (server order, not activity), then the rest by activity
        assertEquals(listOf(3L, 1L, 2L, 4L), s.chatList.map { it.id })
    }

    @Test
    fun withoutFoldersTheListIsByActivityAndPinsAreUnknown() {
        val store = MaxStore()
        store.applyLogin(login(folders = null))
        assertNull(store.state.value.pinnedChatIds)
        assertEquals(listOf(2L, 4L, 1L, 3L), store.state.value.chatList.map { it.id })
    }

    @Test
    fun deltaLoginKeepsFoldersAndAnotherUserDropsThem() {
        val store = MaxStore()
        store.applyLogin(login())
        store.applyLogin(login(folders = null))
        assertEquals(listOf(3L, 1L), store.state.value.pinnedChatIds)
        store.applyLogin(login(user = 99, folders = null))
        assertNull(store.state.value.pinnedChatIds)
    }

    @Test
    fun pushFromAnotherDeviceRepins() {
        val store = MaxStore()
        store.applyLogin(login())
        store.apply(push(mapOf("folder" to allFolder(4, 3, 2), "folderSync" to 8L)))
        assertEquals(listOf(4L, 3L, 2L), store.state.value.pinnedChatIds)
        assertEquals(8L, store.state.value.chatFolders?.folderSync)
        assertEquals(listOf(4L, 3L, 2L, 1L), store.state.value.chatList.map { it.id })
        // unpinned everything elsewhere
        store.apply(push(mapOf("folders" to listOf(allFolder()))))
        assertEquals(emptyList(), store.state.value.pinnedChatIds)
        // a push before any folder list starts one
        val fresh = MaxStore()
        fresh.apply(push(mapOf("folder" to allFolder(2))))
        assertEquals(listOf(2L), fresh.state.value.pinnedChatIds)
    }

    @Test
    fun folderListAndPinUpdateReplies() {
        val store = MaxStore()
        store.applyLogin(login(folders = null))
        store.putFolders(FolderList.from(mapOf("folders" to listOf(allFolder(2)), "foldersOrder" to listOf("all.chat.folder"), "folderSync" to 9L)))
        assertEquals(listOf(2L), store.state.value.pinnedChatIds)

        store.putPinnedUpdate(FolderUpdate.from(mapOf("folder" to allFolder(1, 2), "folderSync" to 10L)), listOf(1L, 2L))
        assertEquals(listOf(1L, 2L), store.state.value.pinnedChatIds)
        assertEquals(10L, store.state.value.chatFolders?.folderSync)

        // the server may answer without the folder: the request was accepted, so the sent list applies
        store.putPinnedUpdate(FolderUpdate.from(mapOf("folderSync" to 11L)), listOf(2L))
        assertEquals(listOf(2L), store.state.value.pinnedChatIds)
        assertEquals(11L, store.state.value.chatFolders?.folderSync)
        assertEquals(listOf("all.chat.folder"), store.state.value.chatFolders?.order)

        store.clear()
        assertNull(store.state.value.pinnedChatIds)
    }
}
