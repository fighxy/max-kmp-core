package com.max.core.api

/**
 * The account's chat folders as the client knows them, and the pinned chats derived from them.
 *
 * Max keeps the pinned ("favourite") chats of the chat list in the `favorites` list of the system
 * folder "all chats" (id [ALL_CHATS_FOLDER_ID]): chat ids, top of the list first. Where the state
 * comes from (schemas as used by KometTeam/Komet, `folders.dart` / `chats.dart`; only the facts
 * are taken, no code):
 * - `LOGIN` 19 reply: `config.chatFolders = {FOLDERS: [folder], foldersOrder: [id], folderSync}`,
 *   the full list ([fromLoginConfig]);
 * - `FOLDERS_GET` 272 `{folderSync: 0}` reply: `{folders, foldersOrder, folderSync}`, the full
 *   list ([FolderList]);
 * - `FOLDERS_UPDATE` 274 reply: `{folder, foldersOrder?, folderSync}`, one changed folder;
 * - `NOTIF_FOLDERS` 277 push (another device changed a folder or the pins): `folders` and / or
 *   `folder`, optional `foldersOrder` and `folderSync` ([merge]).
 *
 * The `LOGIN` config also has a per-chat `config.chats.{chatId}.favIndex` (1-based, `0` = not set);
 * Komet treats the folder list as authoritative over it, and so does this class.
 *
 * Pinning, unpinning and reordering all send the whole new list: `FOLDERS_UPDATE` with the "all
 * chats" folder's `id`, `title`, `include`, `filters` and `options` as the server sent them and the
 * new `favorites` ([favoritesPayload]).
 *
 * @property folders folders in [order] (folders the order does not name at the end).
 * @property order `foldersOrder` as last received.
 * @property folderSync last `folderSync` marker (`0` when never received).
 */
data class ChatFolders(
    val folders: List<Folder>,
    val order: List<String> = emptyList(),
    val folderSync: Long = 0,
) {
    /**
     * The "all chats" folder: the one with id [ALL_CHATS_FOLDER_ID]; the server does not always
     * mark it, so a folder titled "Все", "Все чаты", "All" or "All chats" counts too (as in Komet).
     * `null` when neither exists.
     */
    val allChats: Folder?
        get() = folders.firstOrNull { it.id == ALL_CHATS_FOLDER_ID }
            ?: folders.firstOrNull { it.title.trim().lowercase() in ALL_CHATS_TITLES }

    /** Pinned chat ids, top first, without duplicates; `null` when there is no "all chats" folder. */
    val pinnedChatIds: List<Long>?
        get() = allChats?.favorites?.distinct()

    /**
     * Applies an incremental change (`NOTIF_FOLDERS` push or `FOLDERS_UPDATE` reply): folders with
     * a known id are replaced, new ones added. A non-empty [newOrder] becomes the order, and folders
     * it does not name are dropped unless they came with this change. [newSync] replaces the marker
     * when present.
     */
    fun merge(incoming: List<Folder>, newOrder: List<String>? = null, newSync: Long? = null): ChatFolders {
        val byId = LinkedHashMap<String, Folder>()
        folders.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        var merged = byId.values.toList()
        val order = newOrder?.takeIf { it.isNotEmpty() } ?: order
        if (!newOrder.isNullOrEmpty()) {
            val keep = newOrder.toSet() + incoming.map { it.id }
            merged = merged.filter { it.id in keep }
        }
        return ChatFolders(sorted(merged, order), order, newSync ?: folderSync)
    }

    /** This state without the folders [ids] (after `FOLDERS_DELETE`). */
    fun without(ids: Collection<String>): ChatFolders {
        val drop = ids.toSet()
        return copy(folders = folders.filter { it.id !in drop }, order = order.filter { it !in drop })
    }

    /** This state with the "all chats" folder's `favorites` set to [chatIds] (no-op without that folder). */
    fun withPinned(chatIds: List<Long>): ChatFolders {
        val all = allChats ?: return this
        return copy(folders = folders.map { if (it.id == all.id) it.copy(favorites = chatIds) else it })
    }

    companion object {
        /** Id of the system folder that holds the pinned chats. */
        const val ALL_CHATS_FOLDER_ID: String = "all.chat.folder"

        private val ALL_CHATS_TITLES = setOf("все", "все чаты", "all", "all chats")

        /** A full list (`FOLDERS_GET` reply or `LOGIN` config), sorted by its order. */
        fun of(folders: List<Folder>, order: List<String>, folderSync: Long): ChatFolders =
            ChatFolders(sorted(folders, order), order, folderSync)

        /** A `FOLDERS_GET` reply. */
        fun from(list: FolderList): ChatFolders = of(list.folders, list.foldersOrder, list.folderSync)

        /**
         * `config.chatFolders` of a `LOGIN` reply ([loginReply] is the whole reply map); `null` when
         * the reply has no folder list (a delta `LOGIN` may leave the config out).
         */
        fun fromLoginConfig(loginReply: Map<*, *>): ChatFolders? {
            val config = loginReply["config"] as? Map<*, *> ?: return null
            val chatFolders = config["chatFolders"] as? Map<*, *> ?: return null
            val list = chatFolders["FOLDERS"] as? List<*> ?: return null
            return of(
                list.mapNotNull { Folder.from(it) },
                (chatFolders["foldersOrder"] as? List<*>).orEmpty().mapNotNull { it?.toString() },
                chatFolders["folderSync"].asLong() ?: 0,
            )
        }

        /**
         * The `FOLDERS_UPDATE` payload that sets [folder]'s pinned chats to [favorites]: `{id, title,
         * include, filters, options, favorites}`. `include`, `filters` and `options` are sent back
         * exactly as the server sent them (from [Folder.raw]), so nothing but the pins changes.
         */
        fun favoritesPayload(folder: Folder, favorites: List<Long>): Map<String, Any?> = linkedMapOf(
            "id" to folder.id,
            "title" to folder.title,
            "include" to (folder.raw["include"] as? List<*> ?: folder.include),
            "filters" to (folder.raw["filters"] as? List<*> ?: folder.filters),
            "options" to (folder.raw["options"] as? List<*> ?: folder.options),
            "favorites" to favorites.distinct(),
        )

        /**
         * The `FOLDERS_UPDATE` payload that changes [folder]: `{id, title, include, filters,
         * options, favorites}` with [title], [chatIds] and [filters] where given and everything
         * else exactly as the server sent it (from [Folder.raw]).
         */
        fun editPayload(folder: Folder, title: String? = null, chatIds: List<Long>? = null, filters: List<Any?>? = null): Map<String, Any?> =
            linkedMapOf(
                "id" to folder.id,
                "title" to (title?.trim() ?: folder.title),
                "include" to (chatIds ?: folder.raw["include"] as? List<*> ?: folder.include),
                "filters" to (filters ?: folder.raw["filters"] as? List<*> ?: folder.filters),
                "options" to (folder.raw["options"] as? List<*> ?: folder.options),
                "favorites" to (folder.raw["favorites"] as? List<*> ?: folder.favorites),
            )

        private fun sorted(folders: List<Folder>, order: List<String>): List<Folder> {
            if (order.isEmpty()) return folders
            val index = HashMap<String, Int>()
            order.forEachIndexed { i, id -> index.getOrPut(id) { i } }
            // stable: unknown folders keep their relative order at the end
            return folders.sortedBy { index[it.id] ?: Int.MAX_VALUE }
        }
    }
}
