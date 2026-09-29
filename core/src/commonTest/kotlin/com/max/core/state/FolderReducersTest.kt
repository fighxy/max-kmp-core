package com.max.core.state

import com.max.core.api.ChatFolders
import com.max.core.api.Folder
import com.max.core.api.FolderUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderReducersTest {
    private fun folder(id: String, title: String = id) = Folder.from(mapOf("id" to id, "title" to title))!!
    private val all = folder(ChatFolders.ALL_CHATS_FOLDER_ID, "Все")
    private val base = MaxState(chatFolders = ChatFolders.of(listOf(all, folder("a"), folder("b")), listOf(ChatFolders.ALL_CHATS_FOLDER_ID, "a", "b"), 1))

    @Test
    fun createdAndChangedFoldersAreMergedIn() {
        val created = StateReducer.putFolderUpdate(base, FolderUpdate.from(mapOf("folder" to mapOf("id" to "c", "title" to "C"), "folderSync" to 2L)))
        assertEquals(listOf(ChatFolders.ALL_CHATS_FOLDER_ID, "a", "b", "c"), created.chatFolders!!.folders.map { it.id })
        assertEquals(2L, created.chatFolders!!.folderSync)
        val renamed = StateReducer.putFolderUpdate(created, FolderUpdate.from(mapOf("folder" to mapOf("id" to "a", "title" to "A2"))))
        assertEquals("A2", renamed.chatFolders!!.folders.first { it.id == "a" }.title)
        assertEquals(2L, renamed.chatFolders!!.folderSync)
        // a first folder without any list yet
        val fresh = StateReducer.putFolderUpdate(MaxState(), FolderUpdate.from(mapOf("folder" to mapOf("id" to "x"))))
        assertEquals(listOf("x"), fresh.chatFolders!!.folders.map { it.id })
    }

    @Test
    fun deletedFoldersAreGone() {
        val next = StateReducer.removeFolders(base, listOf("a"), FolderUpdate.from(emptyMap<String, Any?>()))
        assertEquals(listOf(ChatFolders.ALL_CHATS_FOLDER_ID, "b"), next.chatFolders!!.folders.map { it.id })
        assertEquals(listOf(ChatFolders.ALL_CHATS_FOLDER_ID, "b"), next.chatFolders!!.order)
        assertNull(StateReducer.removeFolders(MaxState(), listOf("a"), FolderUpdate.from(emptyMap<String, Any?>())).chatFolders)
    }

    @Test
    fun reorderKeepsEveryFolder() {
        val order = listOf(ChatFolders.ALL_CHATS_FOLDER_ID, "b", "a")
        val next = StateReducer.reorderFolders(base, order, FolderUpdate.from(mapOf("folderSync" to 5L)))
        assertEquals(order, next.chatFolders!!.folders.map { it.id })
        assertEquals(5L, next.chatFolders!!.folderSync)
        // a partial order never drops folders
        val partial = StateReducer.reorderFolders(base, listOf("b"), FolderUpdate.from(emptyMap<String, Any?>()))
        assertEquals(setOf(ChatFolders.ALL_CHATS_FOLDER_ID, "a", "b"), partial.chatFolders!!.folders.map { it.id }.toSet())
        assertEquals("b", partial.chatFolders!!.folders.first().id)
    }

    @Test
    fun storeExposesFolders() {
        val store = MaxStore(initial = base)
        store.putFolderUpdate(FolderUpdate.from(mapOf("folder" to mapOf("id" to "c"))))
        store.removeFolders(listOf("b"), FolderUpdate.from(emptyMap<String, Any?>()))
        store.reorderFolders(listOf("c", ChatFolders.ALL_CHATS_FOLDER_ID, "a"), FolderUpdate.from(emptyMap<String, Any?>()))
        assertEquals(listOf("c", ChatFolders.ALL_CHATS_FOLDER_ID, "a"), store.state.value.chatFolders!!.folders.map { it.id })
    }
}
