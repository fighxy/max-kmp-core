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
     * firstName, lastName}`, as the MAX web client and KometTeam/Komet `updateContact` send it);
     * reply `contact`. The new name is the user's `CUSTOM` entry of `names`, seen only by this
     * account. Both names are trimmed; a blank [lastName] goes out as `null` (web client). A
     * blank [firstName] or a name over [CONTACT_NAME_MAX] characters fails before sending (the
     * server answers `error.contact.name.empty` / `error.contact.name.maxlength`). The web client
     * adds a user who is not a contact yet (`ADD`, [addContact]) before renaming.
     */
    suspend fun renameContact(userId: Long, firstName: String, lastName: String? = null): MaxUser {
        val first = firstName.trim()
        val last = lastName?.trim()?.takeIf { it.isNotEmpty() }
        require(first.isNotEmpty()) { "firstName must not be blank" }
        require(first.length <= CONTACT_NAME_MAX && (last?.length ?: 0) <= CONTACT_NAME_MAX) { "name longer than $CONTACT_NAME_MAX" }
        val payload = linkedMapOf<String, Any?>("contactId" to userId, "action" to "UPDATE", "firstName" to first, "lastName" to last)
        return contact(Opcode.CONTACT_UPDATE, sink.request(Opcode.CONTACT_UPDATE, payload).payload)
    }

    /**
     * Removes a contact (`CONTACT_UPDATE` 34, `{contactId, action: "REMOVE"}`). Returns the
     * reply's `contact` when there is one (the web client reads it); undo is [addContact].
     */
    suspend fun removeContact(userId: Long): MaxUser? {
        val map = replyMap(sink.request(Opcode.CONTACT_UPDATE, contactAction(userId, "REMOVE")), Opcode.CONTACT_UPDATE)
        return MaxUser.from(map["contact"])
    }

    /**
     * Adds a contact by phone (`CONTACT_ADD_BY_PHONE` 41, `{phone, firstName?, lastName?}`, MAX
     * web client); reply `{contact, new}`. Blank names are left out. [ContactByPhone.isNew] is the
     * reply's `new` (the contact was not in the list before).
     */
    suspend fun addContactByPhone(phone: String, firstName: String? = null, lastName: String? = null): ContactByPhone {
        val number = phone.trim()
        require(number.isNotEmpty()) { "phone must not be blank" }
        val payload = linkedMapOf<String, Any?>("phone" to number)
        firstName?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["firstName"] = it }
        lastName?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["lastName"] = it }
        val reply = sink.request(Opcode.CONTACT_ADD_BY_PHONE, payload).payload
        val user = contact(Opcode.CONTACT_ADD_BY_PHONE, reply)
        return ContactByPhone(user, (reply as Map<*, *>)["new"] == true)
    }

    /**
     * Imports phone-book entries (`SYNC` 21, PyMax `ImportContactsPayload`:
     * `{contactList: {<phone>: {firstName}}}`; PyMax sends only the first name). Reply `contacts`:
     * the entries that are Max users.
     */
    suspend fun importContacts(contacts: List<PhoneContact>): List<MaxUser> {
        val list = LinkedHashMap<String, Any?>()
        for (c in contacts) list[c.phone] = linkedMapOf("firstName" to c.firstName)
        return userList(Opcode.SYNC, linkedMapOf("contactList" to list))
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
        /** Longest first or last name of a contact the server accepts (MAX web client form). */
        const val CONTACT_NAME_MAX = 64

        /** Id of the dialog between two users (PyMax `get_chat_id`: `a xor b`). */
        fun dialogChatId(firstUserId: Long, secondUserId: Long): Long = firstUserId xor secondUserId
    }
}

/**
 * A phone-book entry: for [UsersApi.importContacts] (PyMax `ContactInfo`; [lastName] is not sent)
 * and for the on-device address book (`com.max.core.state.StateReducer.setAddressBook`, never
 * sent), where [fullName] is the name.
 */
data class PhoneContact(val phone: String, val firstName: String, val lastName: String? = null) {
    /** `firstName lastName`, trimmed; `null` when both are blank. */
    val fullName: String?
        get() = listOfNotNull(firstName.trim(), lastName?.trim()).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
}

/** Reply of [UsersApi.addContactByPhone]: the contact and whether it was [isNew] to the list. */
data class ContactByPhone(val user: MaxUser, val isNew: Boolean)

/**
 * Phone numbers for matching the device address book against users, by the rules the Orbitle
 * iOS and Android clients share (fixtures `test-fixtures/names/`):
 *
 * 1. spaces (any whitespace), dashes, parentheses and dots are dropped; a leading `+` is kept;
 * 2. a leading `00` counts as `+`;
 * 3. without `+`: 11 digits starting with `8` become `+7…` (8 913 123-45-67 → +79131234567),
 *    11 digits starting with `7` get the `+`;
 * 4. without `+`: exactly 10 digits are Russian, `+7` is put in front;
 * 5. any other number keeps (or gets) its `+`: the result always starts with `+`;
 * 6. fewer than 7 or more than 15 digits, or any other character, is no number (`null`), so
 *    short service numbers such as 900 never match.
 *
 * The server's `phone` of a user (digits as a number, e.g. `79131234567`) normalizes the same way.
 */
object PhoneNumbers {
    fun normalize(value: Any?): String? {
        val raw = when (value) {
            is Number -> value.toLong().takeIf { it > 0 }?.toString()
            is String -> value
            else -> null
        } ?: return null
        var t = raw.filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' || it == '.' }
        var plus = false
        if (t.startsWith("+")) {
            plus = true
            t = t.substring(1)
        } else if (t.startsWith("00")) {
            plus = true
            t = t.substring(2)
        }
        if (t.isEmpty() || t.any { it !in '0'..'9' } || t.length < 7 || t.length > 15) return null
        if (!plus) {
            t = when {
                t.length == 11 && t[0] == '8' -> "7" + t.substring(1)
                t.length == 10 -> "7$t"
                else -> t
            }
        }
        return "+$t"
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
