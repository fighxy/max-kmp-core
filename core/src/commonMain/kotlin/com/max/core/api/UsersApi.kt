package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode

/**
 * Users and contacts over a [RequestSink], following PyMax `src/pymax/api/users/service.py`
 * (`UserService`) and `payloads.py`. PyMax's user cache is not reproduced (the caller can keep
 * results in `com.max.core.state.MaxStore.putUsers`).
 *
 * Contact search by name and presence subscription have no payload in the references and are not
 * exposed; of the contact list opcodes (35-40) only the black list page of `CONTACT_LIST` 36 is.
 * `CONTACT_UPDATE` 34 covers add, rename (`UPDATE`), remove, block and unblock.
 */
class UsersApi(private val sink: RequestSink) {
    /** Users by id (`CONTACT_INFO` 32, `{contactIds}`); reply `contacts`. Unknown ids are absent. */
    suspend fun getUsers(userIds: List<Long>): List<MaxUser> {
        require(userIds.isNotEmpty()) { "userIds must not be empty" }
        return userList(Opcode.CONTACT_INFO, linkedMapOf("contactIds" to userIds))
    }

    /** One user via [getUsers], or `null` if the server did not return it (PyMax `get_user`). */
    suspend fun getUser(userId: Long): MaxUser? = getUsers(listOf(userId)).firstOrNull { it.id == userId }

    /** Finds a user by phone (`CONTACT_INFO_BY_PHONE` 46, `{phone}`); reply `contact`. */
    suspend fun findByPhone(phone: String): MaxUser =
        contact(Opcode.CONTACT_INFO_BY_PHONE, sink.request(Opcode.CONTACT_INFO_BY_PHONE, linkedMapOf("phone" to phone)).payload)

    /**
     * Adds a contact (`CONTACT_UPDATE` 34, `{contactId, action: "ADD"}`); reply `contact`.
     * A non-blank [firstName] is sent as well (Komet `addContact`). An empty name is left out,
     * so [addContact] without it keeps the previous payload.
     */
    suspend fun addContact(userId: Long, firstName: String? = null): MaxUser {
        val payload = linkedMapOf<String, Any?>("contactId" to userId, "action" to "ADD")
        val name = firstName?.trim().orEmpty()
        if (name.isNotEmpty()) payload["firstName"] = name
        return contact(Opcode.CONTACT_UPDATE, sink.request(Opcode.CONTACT_UPDATE, payload).payload)
    }

    /**
     * Renames a contact for this account (`CONTACT_UPDATE` 34, `{contactId, action: "UPDATE",
     * firstName, lastName}`, KometTeam/Komet `ContactsModule.updateContact`); reply `contact`.
     * The new name is the user's `CUSTOM` entry of `names`, seen only by this account. An empty
     * [lastName] is sent as `""`, as Komet does.
     */
    suspend fun renameContact(userId: Long, firstName: String, lastName: String = ""): MaxUser {
        val first = firstName.trim()
        require(first.isNotEmpty()) { "firstName must not be blank" }
        val payload = linkedMapOf<String, Any?>("contactId" to userId, "action" to "UPDATE", "firstName" to first, "lastName" to lastName.trim())
        return contact(Opcode.CONTACT_UPDATE, sink.request(Opcode.CONTACT_UPDATE, payload).payload)
    }

    /** Removes a contact (`CONTACT_UPDATE` 34, `{contactId, action: "REMOVE"}`). */
    suspend fun removeContact(userId: Long) {
        replyMap(sink.request(Opcode.CONTACT_UPDATE, contactAction(userId, "REMOVE")), Opcode.CONTACT_UPDATE)
    }

    /**
     * Imports phone-book entries (`SYNC` 21, PyMax `ImportContactsPayload`:
     * `{contactList: {<phone>: {firstName}}}`; PyMax sends only the first name). Reply `contacts`:
     * the entries that are Max users.
     */
    suspend fun importContacts(contacts: List<PhoneContact>): List<MaxUser> = userList(Opcode.SYNC, importPayload(contacts))

    /**
     * Imports phone-book entries like [importContacts] (`SYNC` 21, `{contactList: {<phone>:
     * {firstName}}}`) and maps each requested phone to the Max user it belongs to
     * ([PhoneBookImport.byPhone]). The reply's `contacts` are the entries that are Max users; an
     * invalid entry is skipped instead of failing the whole import. A user is matched by its
     * `phone`; when the reply also carries `phones` (requested phone → the server's form of it,
     * noted only in a PyMax comment: `{contacts, phones}`), that form is used for the match.
     * Entries without a match are not Max users (or hide their number).
     *
     * Only `firstName` goes on the wire, as in PyMax and Komet; [PhoneContact.lastName] stays local.
     */
    suspend fun importPhoneBook(contacts: List<PhoneContact>): PhoneBookImport {
        require(contacts.isNotEmpty()) { "contacts must not be empty" }
        val map = replyMap(sink.request(Opcode.SYNC, importPayload(contacts)), Opcode.SYNC)
        val users = (map["contacts"] as? List<*>).orEmpty().mapNotNull { MaxUser.from(it) }
        val phones = LinkedHashMap<String, Long>()
        (map["phones"] as? Map<*, *>).orEmpty().forEach { (k, v) ->
            val key = k?.toString() ?: return@forEach
            PhoneNumbers.digits(v)?.let { phones[key] = it }
        }
        return PhoneBookImport(users, phones, PhoneBookImport.match(contacts, users, phones), map)
    }

    /** `SYNC` 21 body (PyMax `ImportContactsPayload`): `{contactList: {<phone>: {firstName}}}`, phones as given. */
    fun importPayload(contacts: List<PhoneContact>): Map<String, Any?> {
        val list = LinkedHashMap<String, Any?>()
        for (c in contacts) list[c.phone] = linkedMapOf("firstName" to c.firstName)
        return linkedMapOf("contactList" to list)
    }

    /**
     * One page of blocked users (`CONTACT_LIST` 36, `{status: "BLOCKED", count, from}`, as Komet
     * pages the black list); reply `contacts`. An empty page is the end.
     */
    suspend fun blockedContacts(from: Int = 0, count: Int = 100): List<MaxUser> {
        require(from >= 0 && count > 0) { "bad page: from=$from count=$count" }
        return userList(Opcode.CONTACT_LIST, linkedMapOf("status" to "BLOCKED", "count" to count, "from" to from))
    }

    /** Blocks or unblocks [userId] (`CONTACT_UPDATE` 34, `{contactId, action: "BLOCK" | "UNBLOCK"}`). */
    suspend fun setBlocked(userId: Long, blocked: Boolean) {
        rawMap(sink.request(Opcode.CONTACT_UPDATE, contactAction(userId, if (blocked) "BLOCK" else "UNBLOCK")))
    }

    /**
     * The whole contact list (opcode 8, `CONTACTS_GET` in Komet, `{contactsSync: 0}`); reply
     * `contacts` (PyMax's `LOGIN2` name `contactInfos` is read as well). Komet sends it when the
     * `LOGIN` reply had no contacts.
     */
    suspend fun syncContacts(): List<MaxUser> {
        val map = replyMap(sink.request(Opcode.CONTACTS_GET, linkedMapOf("contactsSync" to 0)), Opcode.CONTACTS_GET)
        val items = map["contacts"] ?: map["contactInfos"] ?: return emptyList()
        val list = items as? List<*> ?: throw MalformedReplyException(Opcode.CONTACTS_GET, "contacts is not a list", map)
        return list.mapNotNull { MaxUser.from(it) }
    }

    /** Active sessions of the account (`SESSIONS_INFO` 96, `{}`); reply `sessions`. */
    suspend fun getSessions(): List<SessionInfo> {
        val map = replyMap(sink.request(Opcode.SESSIONS_INFO, emptyMap<String, Any?>()), Opcode.SESSIONS_INFO)
        val items = map["sessions"] as? List<*> ?: return emptyList()
        return items.map { SessionInfo.from(it) ?: throw MalformedReplyException(Opcode.SESSIONS_INFO, "session is not a map", map) }
    }

    private suspend fun userList(opcode: Opcode, payload: Map<String, Any?>): List<MaxUser> {
        val map = replyMap(sink.request(opcode, payload), opcode)
        val items = map["contacts"] ?: return emptyList()
        val list = items as? List<*> ?: throw MalformedReplyException(opcode, "contacts is not a list", map)
        return list.map { MaxUser.from(it) ?: throw MalformedReplyException(opcode, "invalid user in contacts", map) }
    }

    private fun contact(opcode: Opcode, payload: Any?): MaxUser {
        val map = payload as? Map<*, *> ?: throw MalformedReplyException(opcode, "payload is not a map", payload)
        return MaxUser.from(map["contact"]) ?: throw MalformedReplyException(opcode, "no valid contact", map)
    }

    private fun contactAction(userId: Long, action: String): Map<String, Any?> = linkedMapOf("contactId" to userId, "action" to action)

    companion object {
        /** Id of the dialog between two users (PyMax `get_chat_id`: `a xor b`). */
        fun dialogChatId(firstUserId: Long, secondUserId: Long): Long = firstUserId xor secondUserId
    }
}

/**
 * A phone-book entry for [UsersApi.importContacts] / [UsersApi.importPhoneBook] (PyMax
 * `ContactInfo`). [lastName] is not sent (PyMax drops it too); it only completes the local
 * address-book name ([fullName]).
 */
data class PhoneContact(val phone: String, val firstName: String, val lastName: String? = null) {
    /** `firstName lastName`, trimmed; `null` when both are blank. */
    val fullName: String?
        get() = listOfNotNull(firstName.trim(), lastName?.trim()).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
}

/**
 * Result of [UsersApi.importPhoneBook].
 *
 * @property users the reply's `contacts`: the imported entries that are Max users.
 * @property phones the reply's `phones` (requested phone → server phone digits), empty when absent.
 * @property byPhone requested phone (as given) → its Max user.
 */
data class PhoneBookImport(val users: List<MaxUser>, val phones: Map<String, Long>, val byPhone: Map<String, MaxUser>, val raw: Map<*, *>) {
    companion object {
        /**
         * Requested phone → user: the server form from [phones] when present, else the digits of
         * the requested phone, compared with the user's `phone`. Numbers are not rewritten
         * otherwise (no country-code guessing).
         */
        fun match(contacts: List<PhoneContact>, users: List<MaxUser>, phones: Map<String, Long>): Map<String, MaxUser> {
            val byNumber = users.filter { (it.phone ?: 0L) > 0L }.associateBy { it.phone!! }
            val out = LinkedHashMap<String, MaxUser>()
            for (c in contacts) {
                val number = phones[c.phone] ?: PhoneNumbers.digits(c.phone) ?: continue
                byNumber[number]?.let { out[c.phone] = it }
            }
            return out
        }
    }
}

/** Phone numbers as the server keeps them in `phone`: digits only, as a number. */
object PhoneNumbers {
    /** Digits of [value] (a number or a string such as `+7 (999) 000-11-22`) as a positive number; `null` otherwise. */
    fun digits(value: Any?): Long? = when (value) {
        is Number -> value.toLong().takeIf { it > 0 }
        is String -> value.filter { it in '0'..'9' }.takeIf { it.isNotEmpty() && it.length <= 18 }?.toLongOrNull()?.takeIf { it > 0 }
        else -> null
    }
}

/**
 * An active session of the account (PyMax `Session`, every field optional). The server Komet talks
 * to sends `client` (app and platform), `info` (device), `location` and `time` (last activity);
 * [lastSeen] picks whichever time is present.
 */
data class SessionInfo(
    val id: String?,
    val deviceId: String?,
    val current: Boolean,
    val userAgent: String?,
    val appVersion: String?,
    val deviceName: String?,
    val deviceType: String?,
    val platform: String?,
    val ip: String?,
    val location: String?,
    val created: Long?,
    val updated: Long?,
    val lastActivity: Long?,
    val raw: Map<*, *>,
    val client: String? = null,
    val info: String? = null,
    val time: Long? = null,
) {
    /** Last activity: `time`, else `lastActivity`, else `updated`; `null` when none is sent. */
    val lastSeen: Long? get() = time ?: lastActivity ?: updated

    companion object {
        fun from(value: Any?): SessionInfo? {
            val m = value as? Map<*, *> ?: return null
            return SessionInfo(
                id = m["id"]?.let { it as? String ?: it.asLong()?.toString() },
                deviceId = m["deviceId"] as? String,
                current = m["current"] == true,
                userAgent = m["userAgent"] as? String,
                appVersion = m["appVersion"] as? String,
                deviceName = m["deviceName"] as? String,
                deviceType = m["deviceType"] as? String,
                platform = m["platform"] as? String,
                ip = m["ip"] as? String,
                location = m["location"] as? String,
                created = m["created"].asLong(),
                updated = m["updated"].asLong(),
                lastActivity = m["lastActivity"].asLong(),
                raw = m,
                client = m["client"] as? String,
                info = m["info"] as? String,
                time = m["time"].asLong(),
            )
        }
    }
}
