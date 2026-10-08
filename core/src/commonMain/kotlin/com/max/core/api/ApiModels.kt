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
 * @property updateTime when the message was last edited (`updateTime`, ms); `null` for a message
 *   that was never edited (no field, or `0`).
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
    val updateTime: Long? = null,
) {
    /**
     * [elements] as typed formatting ([TextElement.parseAll]; invalid entries are skipped, a
     * missing `length` runs to the end of [text]).
     */
    val textElements: List<TextElement> get() = TextElement.parseAll(elements, text.length)

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
                updateTime = m["updateTime"].asLong()?.takeIf { it > 0 },
            )
        }
    }
}

/** `CHAT_HISTORY` reply: `messages` (PyMax reads only this) and, with `getChat`, a `chat`. */
data class ChatHistory(val messages: List<MaxMessage>, val chat: Chat?, val raw: Map<*, *>)

/** `CHAT_MEDIA` reply: messages with the asked attachment types and the server's [total], if sent. */
data class ChatMediaPage(val messages: List<MaxMessage>, val total: Int?, val raw: Map<*, *>)

/**
 * `CHAT_MARK` reply (PyMax `ReadState`, both fields required). [local] is `true` for a read kept
 * on the device only (ghost mode): nothing was sent, [unread] is the local counter.
 */
data class ReadState(val unread: Int, val mark: Long, val raw: Map<*, *>, val local: Boolean = false)

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
 *
 * @property participants `participants` of the chat object: user id → that member's read mark,
 *   the time (ms) of the last message the member has read (not the moment of reading). Keys may
 *   arrive as integers or decimal strings; entries without a numeric id or mark are skipped. A
 *   chat object without the map gives an empty one. Large groups may list only some members
 *   ([participantsCount] counts all of them).
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
    val participants: Map<Long, Long> = emptyMap(),
) {
    /**
     * The public name of a group or channel without `@` ([MentionNames.ofChat] of the chat's
     * `link`; an invite link `…/join/…` gives none). For a dialog use the peer's
     * [MaxUser.mentionName].
     */
    val mentionName: String? get() = MentionNames.ofChat(raw["link"] as? String)

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
                participants = readMarks(m["participants"]),
            )
        }

        /**
         * `participants` as user id → read mark; non-numeric keys or values are skipped. A user
         * listed twice (the id once as a number and once as a string) keeps the larger mark: a
         * read mark only moves forward, so the later one is the true one whatever the key order.
         */
        fun readMarks(value: Any?): Map<Long, Long> {
            val map = value as? Map<*, *> ?: return emptyMap()
            val out = LinkedHashMap<Long, Long>()
            for ((k, v) in map) {
                val user = k.asLong() ?: continue
                val mark = v.asLong() ?: continue
                val known = out[user]
                if (known == null || mark > known) out[user] = mark
            }
            return out
        }
    }
}

/**
 * A chat member (PyMax `Member`: `contact`, `presence`); [userId] is `contact.id`. [readMark] is
 * the member's `readMark` (`CHAT_MEMBERS` 59): the time (ms) of the last message the member has
 * read, `null` when the entry has none.
 */
data class ChatMember(
    val userId: Long?,
    val contact: Map<*, *>,
    val presence: Map<*, *>?,
    val raw: Map<*, *>,
    val readMark: Long? = null,
) {
    /** [presence] as `{seen, status}` (`seen` as sent, usually Unix seconds); `null` without one. */
    val presenceInfo: PresenceInfo?
        get() = presence?.let { PresenceInfo(it["seen"].asLong(), it["status"].asLong()?.toInt()) }
}

/**
 * `CHAT_MEMBERS` page: `members` and the `marker` for the next page. A reply without `marker` is
 * the last page (as the MAX web client reads it): [marker] is `null` then, never `0`, so a
 * caller does not start over from the first page.
 */
data class ChatMembersPage(val members: List<ChatMember>, val marker: Long?, val raw: Map<*, *>)

/** One entry of a user's `names` (PyMax `Name`: `name`, `firstName`, `lastName`, `type`, all optional). */
data class UserName(val name: String?, val firstName: String?, val lastName: String?, val type: String?)

/**
 * A user / contact (PyMax `User`). Only `id` is required (as in PyMax); every other field is
 * optional and the decoded map stays in [raw].
 *
 * @property phone PyMax types it as an integer; a decimal string is accepted too.
 * @property displayName first `names` entry: `name`, else `firstName lastName`.
 * @property mentionName the name for `@` mentions and member search, without `@`: the path of
 *   [link] ([MentionNames.ofUser], as the MAX web client derives it); `null` without a link.
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
    val mentionName: String? get() = MentionNames.ofUser(link)

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
