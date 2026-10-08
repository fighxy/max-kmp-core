package com.max.core.api

/**
 * One user of "who read this message" ([MessageReaders.build]).
 *
 * @property readMark the user's read mark (ms): the time of the last message the user has read,
 *   not the moment of reading. Set when it reaches the message; `null` for a user who is listed
 *   only for a reaction while the known mark is older than the message or missing.
 * @property reaction the user's emoji on the message, `null` when the user did not react.
 */
data class MessageReader(val userId: Long, val readMark: Long?, val reaction: String?)

/**
 * "Who read this message" as the MAX web client computes it. The server has no request for it:
 * readers are the members whose read mark reaches the message time (an equal mark counts as
 * read), plus the users who reacted to the message (`MSG_GET_DETAILED_REACTIONS` 181).
 *
 * Read marks come from the chat's `participants` ([Chat.participants], `CHAT_INFO` 48), the
 * `readMark` of `CHAT_MEMBERS` 59 entries for members the map leaves out, and `NOTIF_MARK` 130
 * pushes (`MaxState.readMarks`); for each user the later mark wins ([mergeMarks]).
 *
 * Pure functions; [ReadersApi.loadMessageReaders] does the requests.
 */
object MessageReaders {
    /** `count` of the `MSG_GET_DETAILED_REACTIONS` request (no paging). */
    const val REACTIONS_COUNT: Int = 100

    /**
     * Whether [chat] shows who read its messages: a group (`type` `CHAT`; not a dialog, Saved
     * Messages, a channel or channel comments) without a running group call (`videoConversation`)
     * and with at most [maxReadmarks] members (`max-readmarks` of the server config,
     * [AccountConfig.maxReadmarks]). The member count is `participantsCount`, or the size of
     * `participants` when the chat object has no count. Any message of such a chat qualifies,
     * whoever sent it and however old it is.
     */
    fun isAvailable(chat: Chat, maxReadmarks: Int = AccountConfig.DEFAULT_MAX_READMARKS): Boolean {
        if (chat.type != "CHAT") return false
        if (hasVideoConversation(chat)) return false
        val members = chat.participantsCount.takeIf { it > 0 } ?: chat.participants.size
        return members <= maxReadmarks
    }

    /**
     * `videoConversation` of the chat object is set: missing, `null`, `false` and an empty map
     * mean there is none.
     */
    fun hasVideoConversation(chat: Chat): Boolean = when (val v = chat.raw["videoConversation"]) {
        null, false -> false
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    /** [server] marks with [live] ones merged in; for a user in both the later mark wins. */
    fun mergeMarks(server: Map<Long, Long>, live: Map<Long, Long>?): Map<Long, Long> {
        if (live.isNullOrEmpty()) return server
        val out = LinkedHashMap(server)
        for ((user, mark) in live) {
            val old = out[user]
            if (old == null || mark > old) out[user] = mark
        }
        return out
    }

    /**
     * The readers of a message sent at [messageTime] by [authorId], without [me] and the author.
     *
     * Order: first the users in [reactions], in the order the server returned them, each with
     * the emoji; then the users whose mark in [marks] is at least [messageTime], latest mark
     * first, equal marks by ascending user id. Each user appears once: a reader who also reacted
     * is in the reaction group only (with the read mark when it reaches the message). A user
     * listed twice in [reactions] keeps the first entry.
     */
    fun build(
        messageTime: Long,
        authorId: Long?,
        me: Long?,
        marks: Map<Long, Long>,
        reactions: List<ReactionUser>,
    ): List<MessageReader> {
        fun excluded(user: Long) = user == me || user == authorId
        val out = ArrayList<MessageReader>()
        val seen = HashSet<Long>()
        for (r in reactions) {
            if (excluded(r.userId) || !seen.add(r.userId)) continue
            out += MessageReader(r.userId, marks[r.userId]?.takeIf { it >= messageTime }, r.reaction)
        }
        marks.entries
            .filter { (user, mark) -> mark >= messageTime && !excluded(user) && user !in seen }
            .sortedWith(compareByDescending<Map.Entry<Long, Long>> { it.value }.thenBy { it.key })
            .forEach { (user, mark) -> out += MessageReader(user, mark, null) }
        return out
    }
}
