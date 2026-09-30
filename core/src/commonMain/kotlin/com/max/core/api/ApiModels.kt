package com.max.core.api

import com.max.core.protocol.Opcode

/*
 * Reply models of the message / chat API. Field names follow PyMax's domain models
 * (`src/pymax/types/domain/message.py`, `chat.py`, `member.py`); every model keeps the decoded
 * map in `raw`, so fields not modelled here stay accessible.
 */

/** Base class of API errors other than server ERROR replies (those stay `ServerErrorException`). */
open class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An OK reply that lacks a required field or has the wrong shape. [payload] is the decoded reply. */
class MalformedReplyException(val opcode: Opcode, detail: String, val payload: Any?) :
    ApiException("malformed ${opcode.name} (${opcode.value}) reply: $detail")

/**
 * A message (PyMax `Message`). Required, as in PyMax: `id`, `time`, `type`.
 *
 * Message events and `MSG_SEND` replies wrap the message as `{chatId, message: {...},
 * prevMessageId, ttl, unread, mark}`; [from] unwraps that like PyMax
 * `Message._unwrap_message_event` (outer `chatId`, `prevMessageId`, `ttl`, `unread`, `mark` win).
 *
 * @property chatId from the message or its envelope; `MSG_GET` / `MSG_EDIT` replies omit it, then
 *   the requested chat id is filled in (as PyMax does).
 * @property elements text formatting elements (`{type, from, length, attributes?}`), raw.
 * @property attaches attachments, raw (media handling is out of scope here).
 * @property raw the map the message was parsed from (the envelope, if there was one).
 */
data class MaxMessage(
    val id: Long,
    val chatId: Long?,
    val sender: Long?,
    val text: String,
    val time: Long,
    val type: String,
    val cid: Long?,
    val status: String?,
    val prevMessageId: Long?,
    val unread: Int?,
    val mark: Long?,
    val elements: List<Any?>,
    val attaches: List<Any?>,
    val link: Map<*, *>?,
    val reactionInfo: ReactionInfo?,
    val raw: Map<*, *>,
) {
    companion object {
        /** Parses [value]; `null` if it is not a map or lacks `id` / `time` / `type`. */
        fun from(value: Any?, fallbackChatId: Long? = null): MaxMessage? {
            val outer = value as? Map<*, *> ?: return null
            val inner = outer["message"] as? Map<*, *>
            val m: Map<*, *> = if (inner == null) outer else LinkedHashMap<Any?, Any?>(inner).apply {
                for (key in listOf("chatId", "prevMessageId", "ttl", "unread", "mark")) put(key, outer[key])
            }
            return MaxMessage(
                id = m["id"].asLong() ?: return null,
                chatId = m["chatId"].asLong() ?: fallbackChatId,
                sender = m["sender"].asLong(),
                text = m["text"] as? String ?: "",
                time = m["time"].asLong() ?: return null,
                type = m["type"] as? String ?: return null,
                cid = m["cid"].asLong(),
                status = m["status"] as? String,
                prevMessageId = m["prevMessageId"].asLong(),
                unread = m["unread"].asLong()?.toInt(),
                mark = m["mark"].asLong(),
                elements = m["elements"] as? List<Any?> ?: emptyList(),
                attaches = m["attaches"] as? List<Any?> ?: emptyList(),
                link = m["link"] as? Map<*, *>,
                reactionInfo = ReactionInfo.from(m["reactionInfo"]),
                raw = outer,
            )
        }
    }
}

/** `CHAT_HISTORY` reply: `messages` (PyMax reads only this) and, with `getChat`, a `chat`. */
data class ChatHistory(val messages: List<MaxMessage>, val chat: Chat?, val raw: Map<*, *>)

/** `CHAT_MARK` reply (PyMax `ReadState`, both fields required). */
data class ReadState(val unread: Int, val mark: Long, val raw: Map<*, *>)

/** One reaction counter (PyMax `ReactionCounter`). */
data class ReactionCounter(val reaction: String, val count: Int)

/** Reactions of a message (PyMax `ReactionInfo`: `totalCount`, `counters`, `yourReaction`). */
data class ReactionInfo(val totalCount: Int, val counters: List<ReactionCounter>, val yourReaction: String?, val raw: Map<*, *>) {
    companion object {
        fun from(value: Any?): ReactionInfo? {
            val m = value as? Map<*, *> ?: return null
            val counters = (m["counters"] as? List<*>).orEmpty().mapNotNull { c ->
                val cm = c as? Map<*, *> ?: return@mapNotNull null
                ReactionCounter(cm["reaction"] as? String ?: return@mapNotNull null, cm["count"].asLong()?.toInt() ?: 0)
            }
            return ReactionInfo(m["totalCount"].asLong()?.toInt() ?: 0, counters, m["yourReaction"] as? String, m)
        }

        /**
         * Built from counters, as the store keeps it after a local change. [raw] is the same shape
         * the server sends (`{counters, totalCount, yourReaction?}`), so it can be stored and exported
         * like a server value. Counters with a count below 1 are dropped.
         */
        fun of(counters: List<ReactionCounter>, yourReaction: String?): ReactionInfo {
            val kept = counters.filter { it.count > 0 }
            val total = kept.sumOf { it.count }
            val own = yourReaction?.takeIf { mine -> kept.any { it.reaction == mine } }
            val raw = linkedMapOf<String, Any?>(
                "counters" to kept.map { linkedMapOf("reaction" to it.reaction, "count" to it.count) },
                "totalCount" to total,
            )
            if (own != null) raw["yourReaction"] = own
            return ReactionInfo(total, kept, own, raw)
        }
    }

    /** The same reactions without this account's one: its counter drops by one, [yourReaction] clears. */
    fun withoutOwn(): ReactionInfo {
        val own = yourReaction ?: return this
        return of(counters.map { if (it.reaction == own) it.copy(count = it.count - 1) else it }, null)
    }
}

/** Who put which reaction on a message (`MSG_GET_DETAILED_REACTIONS` 181, one entry of `reactions`). */
data class ReactionUser(val userId: Long, val reaction: String)

/**
 * A chat (PyMax `Chat`). PyMax requires `id`, `type`, `status`, `owner`; here only `id` and `type`
 * are required so that partial chat objects still parse.
 */
data class Chat(
    val id: Long,
    val type: String,
    val status: String?,
    val owner: Long?,
    val title: String?,
    val participantsCount: Int,
    val newMessages: Int,
    val lastEventTime: Long,
    val lastMessage: MaxMessage?,
    val raw: Map<*, *>,
) {
    companion object {
        fun from(value: Any?): Chat? {
            val m = value as? Map<*, *> ?: return null
            val id = m["id"].asLong() ?: return null
            return Chat(
                id = id,
                type = m["type"] as? String ?: return null,
                status = m["status"] as? String,
                owner = m["owner"].asLong(),
                title = m["title"] as? String,
                participantsCount = m["participantsCount"].asLong()?.toInt() ?: 0,
                newMessages = m["newMessages"].asLong()?.toInt() ?: 0,
                lastEventTime = m["lastEventTime"].asLong() ?: 0,
                lastMessage = MaxMessage.from(m["lastMessage"], id),
                raw = m,
            )
        }
    }
}

/** A chat member (PyMax `Member`: `contact`, `presence`); [userId] is `contact.id`. */
data class ChatMember(val userId: Long?, val contact: Map<*, *>, val presence: Map<*, *>?, val raw: Map<*, *>)

/** `CHAT_MEMBERS` page: `members` and the `marker` for the next page (`0` when absent, as in PyMax). */
data class ChatMembersPage(val members: List<ChatMember>, val marker: Long, val raw: Map<*, *>)

/** One entry of a user's `names` (PyMax `Name`: `name`, `firstName`, `lastName`, `type`, all optional). */
data class UserName(val name: String?, val firstName: String?, val lastName: String?, val type: String?)

/**
 * A user / contact (PyMax `User`). Only `id` is required (as in PyMax); every other field is
 * optional and the decoded map stays in [raw].
 *
 * @property phone PyMax types it as an integer; a decimal string is accepted too.
 * @property displayName first `names` entry: `name`, else `firstName lastName`.
 */
data class MaxUser(
    val id: Long,
    val names: List<UserName>,
    val phone: Long?,
    val accountStatus: Int?,
    val status: String?,
    val description: String?,
    val link: String?,
    val baseUrl: String?,
    val photoId: Long?,
    val updateTime: Long?,
    val options: List<String>,
    val raw: Map<*, *>,
) {
    val displayName: String?
        get() = names.firstOrNull()?.let { n ->
            n.name?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(n.firstName, n.lastName).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotEmpty() }
        }

    companion object {
        /** Parses [value]; `null` if it is not a map or has no `id`. */
        fun from(value: Any?): MaxUser? {
            val m = value as? Map<*, *> ?: return null
            val names = (m["names"] as? List<*>).orEmpty().mapNotNull { n ->
                val nm = n as? Map<*, *> ?: return@mapNotNull null
                UserName(nm["name"] as? String, nm["firstName"] as? String, nm["lastName"] as? String, nm["type"] as? String)
            }
            return MaxUser(
                id = m["id"].asLong() ?: return null,
                names = names,
                phone = m["phone"].asLong(),
                accountStatus = m["accountStatus"].asLong()?.toInt(),
                status = m["status"] as? String,
                description = m["description"] as? String,
                link = m["link"]?.let { it as? String ?: it.asLong()?.toString() },
                baseUrl = m["baseUrl"] as? String,
                photoId = m["photoId"].asLong(),
                updateTime = m["updateTime"].asLong(),
                options = (m["options"] as? List<*>).orEmpty().filterIsInstance<String>(),
                raw = m,
            )
        }
    }
}

/** Presence of a user (PyMax `Presence`: `seen` Unix time, `status` code; both optional). */
data class PresenceInfo(val seen: Long?, val status: Int?)

/** Integer from a decoded value; ids sometimes arrive as decimal strings (e.g. in links). */
internal fun Any?.asLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull()
    else -> null
}
