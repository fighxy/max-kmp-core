package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode

/**
 * The own account over a [RequestSink], following PyMax `src/pymax/api/self/service.py`
 * (`SelfService`) and `payloads.py`: profile, privacy settings, other sessions and chat folders.
 * 2FA management is in [TwoFactorApi]; logout in `AuthApi.logout`.
 */
class AccountApi(private val sink: RequestSink, private val newFolderId: () -> String = ::randomUuid) {
    /**
     * Changes the profile (`PROFILE` 16, PyMax `ChangeProfilePayload`:
     * `{firstName, lastName?, description?, photoToken?, avatarType: "USER_AVATAR"}`; `null`
     * fields are left out). A new avatar: upload it with `MediaApi.uploadPhoto(..., profile = true)`
     * and pass its token. Reply `profile {contact, profileOptions}`.
     */
    suspend fun updateProfile(firstName: String, lastName: String? = null, description: String? = null, photoToken: String? = null): Profile {
        val payload = linkedMapOf<String, Any?>("firstName" to firstName)
        if (lastName != null) payload["lastName"] = lastName
        if (description != null) payload["description"] = description
        if (photoToken != null) payload["photoToken"] = photoToken
        payload["avatarType"] = "USER_AVATAR"
        val map = replyMap(sink.request(Opcode.PROFILE, payload), Opcode.PROFILE)
        return Profile.from(map["profile"]) ?: throw MalformedReplyException(Opcode.PROFILE, "no valid profile", map)
    }

    /**
     * Sets a new avatar (`PROFILE` 16, `{photoToken, avatarType: "USER_AVATAR"}`) without sending
     * the name, so a name changed meanwhile on another device is not overwritten (as Komet does).
     * [photoToken] comes from `MediaApi.uploadPhoto(..., profile = true)`. Reply `profile`.
     */
    suspend fun setAvatar(photoToken: String): Profile {
        require(photoToken.isNotBlank()) { "photoToken is blank" }
        val payload = linkedMapOf<String, Any?>("photoToken" to photoToken, "avatarType" to "USER_AVATAR")
        return profileReply(Opcode.PROFILE, replyMap(sink.request(Opcode.PROFILE, payload), Opcode.PROFILE))
    }

    /** Removes the avatar [photoId] (`REMOVE_CONTACT_PHOTO` 43, `{photoId}`); reply `profile`. */
    suspend fun removePhoto(photoId: Long): Profile =
        profileReply(
            Opcode.REMOVE_CONTACT_PHOTO,
            replyMap(sink.request(Opcode.REMOVE_CONTACT_PHOTO, linkedMapOf("photoId" to photoId)), Opcode.REMOVE_CONTACT_PHOTO),
        )

    /**
     * Schedules the deletion of the account (`PROFILE_DELETE` 199, `{delete: true, type: 0}`), or
     * cancels a scheduled one with [delete] `false`. The server deletes the profile 30 days later;
     * the reply's `timestamp` (returned, `null` when absent) is that moment.
     */
    suspend fun requestProfileDeletion(delete: Boolean = true): Long? =
        rawMap(sink.request(Opcode.PROFILE_DELETE, linkedMapOf("delete" to delete, "type" to 0)))["timestamp"].asLong()

    /**
     * Changes user settings (`CONFIG` 22, `{settings: {user: values}}`), any keys of
     * `config.user` (see [AccountConfig]); e.g. `{"HIDDEN": true}` or `{"INACTIVE_TTL": "3M"}`.
     * The reply carries the whole new `user` config and its `hash`.
     */
    suspend fun updateUserSettings(values: Map<String, Any?>): UserSettingsUpdate {
        require(values.isNotEmpty()) { "no setting given" }
        val map = replyMap(sink.request(Opcode.CONFIG, linkedMapOf("settings" to linkedMapOf("user" to LinkedHashMap(values)))), Opcode.CONFIG)
        return UserSettingsUpdate(
            user = (map["user"] as? Map<*, *>)?.let { AccountConfig.stringKeys(it) },
            hash = map["hash"]?.let { it as? String ?: it.asLong()?.toString() },
            raw = map,
        )
    }

    /**
     * Changes privacy settings (`CONFIG` 22, PyMax `ChangeProfileSettingsPayload`:
     * `{settings: {user: {...}}}` with only the given keys). Returns the new config `hash`
     * (PyMax stores it as the session's `configHash`; `null` if the reply has none).
     */
    suspend fun updatePrivacy(settings: PrivacySettings): String? {
        require(!settings.isEmpty()) { "no privacy setting given" }
        val map = replyMap(sink.request(Opcode.CONFIG, linkedMapOf("settings" to linkedMapOf("user" to settings.toPayload()))), Opcode.CONFIG)
        return map["hash"]?.let { it as? String ?: it.asLong()?.toString() }
    }

    /**
     * Closes every other session (`SESSIONS_CLOSE` 97, `{}`). The reply carries a new login
     * `token` for this session (PyMax replaces the stored token with it); `null` if absent.
     */
    suspend fun closeOtherSessions(): String? =
        rawMap(sink.request(Opcode.SESSIONS_CLOSE, emptyMap<String, Any?>()))["token"] as? String

    /** Chat folders (`FOLDERS_GET` 272, `{folderSync}`, `0` = everything). */
    suspend fun getFolders(folderSync: Long = 0): FolderList =
        FolderList.from(replyMap(sink.request(Opcode.FOLDERS_GET, linkedMapOf("folderSync" to folderSync)), Opcode.FOLDERS_GET))

    /** Creates a folder (`FOLDERS_UPDATE` 274, `{id: <new UUID>, title, include, filters}`). */
    suspend fun createFolder(title: String, chatIds: List<Long>, filters: List<Any?> = emptyList()): FolderUpdate =
        folderUpdate(Opcode.FOLDERS_UPDATE, linkedMapOf("id" to newFolderId(), "title" to title, "include" to chatIds, "filters" to filters))

    /** Updates a folder (`FOLDERS_UPDATE` 274, `{id, title, include, filters, options}`). */
    suspend fun updateFolder(
        folderId: String,
        title: String,
        chatIds: List<Long> = emptyList(),
        filters: List<Any?> = emptyList(),
        options: List<Any?> = emptyList(),
    ): FolderUpdate = folderUpdate(
        Opcode.FOLDERS_UPDATE,
        linkedMapOf("id" to folderId, "title" to title, "include" to chatIds, "filters" to filters, "options" to options),
    )

    /**
     * Changes [folder] (`FOLDERS_UPDATE` 274): the given [title], [chatIds] (`include`) and
     * [filters]; what is `null` and the folder's `options` and `favorites` (its pinned chats) are
     * sent back as the server sent them, so nothing else is lost ([ChatFolders.editPayload]).
     */
    suspend fun editFolder(folder: Folder, title: String? = null, chatIds: List<Long>? = null, filters: List<Any?>? = null): FolderUpdate =
        folderUpdate(Opcode.FOLDERS_UPDATE, ChatFolders.editPayload(folder, title, chatIds, filters))

    /**
     * Creates a folder as Komet does (`FOLDERS_UPDATE` 274, `{id: <new UUID>, title, include,
     * filters, options: [], favorites: []}`). [filters] are Komet `FolderFilter` codes (e.g. 4
     * dialogs, 2 channels, 10 bots).
     */
    suspend fun addFolder(title: String, chatIds: List<Long> = emptyList(), filters: List<Any?> = emptyList()): FolderUpdate =
        folderUpdate(
            Opcode.FOLDERS_UPDATE,
            linkedMapOf(
                "id" to newFolderId(), "title" to title.trim(), "include" to chatIds, "filters" to filters,
                "options" to emptyList<Any?>(), "favorites" to emptyList<Long>(),
            ),
        )

    /** Deletes folders (`FOLDERS_DELETE` 276, `{folderIds}`). */
    suspend fun deleteFolders(folderIds: List<String>): FolderUpdate {
        require(folderIds.isNotEmpty()) { "no folder id given" }
        return folderUpdate(Opcode.FOLDERS_DELETE, linkedMapOf("folderIds" to folderIds))
    }

    /** Sets the folder order (`FOLDERS_REORDER` 275, `{foldersOrder}`, the whole list). */
    suspend fun reorderFolders(order: List<String>): FolderUpdate {
        require(order.isNotEmpty()) { "empty folder order" }
        return folderUpdate(Opcode.FOLDERS_REORDER, linkedMapOf("foldersOrder" to order))
    }

    /**
     * Sets the pinned chats: `FOLDERS_UPDATE` 274 on the "all chats" [folder] with
     * [ChatFolders.favoritesPayload] (`favorites` = [chatIds], top first; everything else as the
     * server sent it). Pin, unpin and reorder are all this one request with the whole new list.
     */
    suspend fun setFolderFavorites(folder: Folder, chatIds: List<Long>): FolderUpdate =
        folderUpdate(Opcode.FOLDERS_UPDATE, ChatFolders.favoritesPayload(folder, chatIds))

    /** Deletes a folder (`FOLDERS_DELETE` 276, `{folderIds: [id]}`). */
    suspend fun deleteFolder(folderId: String): FolderUpdate =
        folderUpdate(Opcode.FOLDERS_DELETE, linkedMapOf("folderIds" to listOf(folderId)))

    private fun profileReply(opcode: Opcode, map: Map<*, *>): Profile =
        Profile.from(map["profile"]) ?: throw MalformedReplyException(opcode, "no valid profile", map)

    private suspend fun folderUpdate(opcode: Opcode, payload: Map<String, Any?>): FolderUpdate =
        FolderUpdate.from(replyMap(sink.request(opcode, payload), opcode))
}

/** Reply to [AccountApi.updateUserSettings]: the whole new `config.user` (`null` if absent) and the config `hash`. */
data class UserSettingsUpdate(val user: Map<String, Any?>?, val hash: String?, val raw: Map<*, *>)

/** Who may do something (PyMax `PrivacyAccess`). */
enum class PrivacyAccess(val wire: String) { ALL("ALL"), CONTACTS("CONTACTS"), NOBODY("_NONE_") }

/**
 * Privacy settings for [AccountApi.updatePrivacy] (PyMax `PrivacySettingsUpdate`); only non-null
 * values are sent, under the server keys `SEARCH_BY_PHONE`, `INCOMING_CALL`, `CHATS_INVITE`,
 * `PHONE_NUMBER_PRIVACY`, `HIDDEN`, `CONTENT_LEVEL_ACCESS` (in this order).
 */
data class PrivacySettings(
    val searchByPhone: PrivacyAccess? = null,
    val incomingCalls: PrivacyAccess? = null,
    val chatInvites: PrivacyAccess? = null,
    val phoneNumberVisibility: PrivacyAccess? = null,
    val hideOnlineStatus: Boolean? = null,
    val safeContentOnly: Boolean? = null,
) {
    fun isEmpty(): Boolean = toPayload().isEmpty()

    fun toPayload(): Map<String, Any?> = buildMap {
        searchByPhone?.let { put("SEARCH_BY_PHONE", it.wire) }
        incomingCalls?.let { put("INCOMING_CALL", it.wire) }
        chatInvites?.let { put("CHATS_INVITE", it.wire) }
        phoneNumberVisibility?.let { put("PHONE_NUMBER_PRIVACY", it.wire) }
        hideOnlineStatus?.let { put("HIDDEN", it) }
        safeContentOnly?.let { put("CONTENT_LEVEL_ACCESS", it) }
    }
}

/** The own profile (PyMax `Profile`: `contact` + optional `profileOptions`). */
data class Profile(val contact: MaxUser, val profileOptions: List<Int>, val raw: Map<*, *>) {
    companion object {
        fun from(value: Any?): Profile? {
            val m = value as? Map<*, *> ?: return null
            val contact = MaxUser.from(m["contact"]) ?: return null
            return Profile(contact, (m["profileOptions"] as? List<*>).orEmpty().mapNotNull { it.asLong()?.toInt() }, m)
        }
    }
}

/**
 * A chat folder (PyMax `Folder`; missing fields default like PyMax). [favorites] are the chats
 * pinned in this folder, top first (Komet `ChatFolder.favorites`); the "all chats" folder's list is
 * the pinned block of the chat list, see [ChatFolders].
 */
data class Folder(
    val id: String,
    val title: String,
    val include: List<Long>,
    val filters: List<Any?>,
    val options: List<Any?>,
    val sourceId: Long,
    val updateTime: Long,
    val raw: Map<*, *>,
    val favorites: List<Long> = emptyList(),
) {
    companion object {
        fun from(value: Any?): Folder? {
            val m = value as? Map<*, *> ?: return null
            return Folder(
                id = m["id"] as? String ?: "",
                title = m["title"] as? String ?: "",
                include = (m["include"] as? List<*>).orEmpty().mapNotNull { it.asLong() },
                filters = (m["filters"] as? List<*>).orEmpty(),
                options = (m["options"] as? List<*>).orEmpty(),
                sourceId = m["sourceId"].asLong() ?: 0,
                updateTime = m["updateTime"].asLong() ?: 0,
                raw = m,
                favorites = (m["favorites"] as? List<*>).orEmpty().mapNotNull { it.asLong() },
            )
        }
    }
}

/** Reply to `FOLDERS_GET` (PyMax `FolderList`). */
data class FolderList(val folders: List<Folder>, val foldersOrder: List<String>, val folderSync: Long, val raw: Map<*, *>) {
    companion object {
        fun from(m: Map<*, *>): FolderList = FolderList(
            (m["folders"] as? List<*>).orEmpty().mapNotNull { Folder.from(it) },
            (m["foldersOrder"] as? List<*>).orEmpty().filterIsInstance<String>(),
            m["folderSync"].asLong() ?: 0,
            m,
        )
    }
}

/** Reply to `FOLDERS_UPDATE` / `FOLDERS_DELETE` (PyMax `FolderUpdate`). */
data class FolderUpdate(val folder: Folder?, val foldersOrder: List<String>, val folderSync: Long, val raw: Map<*, *>) {
    companion object {
        fun from(m: Map<*, *>): FolderUpdate = FolderUpdate(
            Folder.from(m["folder"]),
            (m["foldersOrder"] as? List<*>).orEmpty().filterIsInstance<String>(),
            m["folderSync"].asLong() ?: 0,
            m,
        )
    }
}

/** Random version-4 UUID string (PyMax `uuid4()` for new folder ids). */
internal fun randomUuid(): String {
    val b = kotlin.random.Random.Default.nextBytes(16)
    b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val h = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}
