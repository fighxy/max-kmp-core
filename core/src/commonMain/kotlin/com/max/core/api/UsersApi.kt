package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode

/**
 * Users and contacts over a [RequestSink], following PyMax `src/pymax/api/users/service.py`
 * (`UserService`) and `payloads.py`. PyMax's user cache is not reproduced (the caller can keep
 * results in `com.max.core.state.MaxStore.putUsers`).
 *
 * Contact search by name, presence subscription and the contact list opcodes (35-40) have no
 * payload in the references and are not exposed.
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

    /** Adds a contact (`CONTACT_UPDATE` 34, `{contactId, action: "ADD"}`); reply `contact`. */
    suspend fun addContact(userId: Long): MaxUser =
        contact(Opcode.CONTACT_UPDATE, sink.request(Opcode.CONTACT_UPDATE, contactAction(userId, "ADD")).payload)

    /** Removes a contact (`CONTACT_UPDATE` 34, `{contactId, action: "REMOVE"}`). */
    suspend fun removeContact(userId: Long) {
        replyMap(sink.request(Opcode.CONTACT_UPDATE, contactAction(userId, "REMOVE")), Opcode.CONTACT_UPDATE)
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

/** A phone-book entry for [UsersApi.importContacts] (PyMax `ContactInfo`; `lastName` is not sent). */
data class PhoneContact(val phone: String, val firstName: String)

/** An active session of the account (PyMax `Session`, every field optional). */
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
) {
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
            )
        }
    }
}
