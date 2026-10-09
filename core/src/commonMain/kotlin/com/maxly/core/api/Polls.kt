package com.maxly.core.api

/** Poll setting bits (PyMax `PollFlags`). */
enum class PollFlag(val bit: Int) {
    ANONYMOUS(1), MULTISELECT(2), REVOTE(4), CLOSED(8), QUIZ(16), CAN_FORWARD(32);

    companion object {
        fun mask(flags: Set<PollFlag>): Int = flags.fold(0) { acc, f -> acc or f.bit }

        fun of(mask: Int): Set<PollFlag> = entries.filterTo(LinkedHashSet()) { mask and it.bit != 0 }
    }
}

/** A poll answer (PyMax `PollAnswer`: `text`, optional `answerId`). */
data class PollAnswer(val text: String, val answerId: Long? = null)

/** One vote (PyMax `PollVote`). */
data class PollVote(val userId: Long, val timestamp: Long)

/** Result for one answer (PyMax `PollResult`). */
data class PollResult(val answerId: Long, val voteCount: Int, val votes: List<PollVote>, val rate: Int, val options: Int)

/** Current poll state (PyMax `PollState`: `total`, `result?`, `voterPreviewIds`). */
data class PollState(val total: Int, val results: List<PollResult>, val voterPreviewIds: List<Long>, val raw: Map<*, *>) {
    companion object {
        fun from(value: Any?): PollState? {
            val m = value as? Map<*, *> ?: return null
            val results = (m["result"] as? List<*>).orEmpty().mapNotNull { r ->
                val rm = r as? Map<*, *> ?: return@mapNotNull null
                PollResult(
                    answerId = rm["answerId"].asLong() ?: return@mapNotNull null,
                    voteCount = rm["voteCount"].asLong()?.toInt() ?: 0,
                    votes = (rm["votes"] as? List<*>).orEmpty().mapNotNull { v ->
                        val vm = v as? Map<*, *> ?: return@mapNotNull null
                        PollVote(vm["userId"].asLong() ?: return@mapNotNull null, vm["timestamp"].asLong() ?: 0)
                    },
                    rate = rm["rate"].asLong()?.toInt() ?: 0,
                    options = rm["options"].asLong()?.toInt() ?: 0,
                )
            }
            return PollState(m["total"].asLong()?.toInt() ?: 0, results, (m["voterPreviewIds"] as? List<*>).orEmpty().mapNotNull { it.asLong() }, m)
        }
    }
}

/** Comment counter update of a channel post (PyMax `CommentsInfoUpdate`). */
data class CommentsInfo(val postId: Long, val totalCount: Int?, val raw: Map<*, *>) {
    companion object {
        fun from(value: Any?): CommentsInfo? {
            val m = value as? Map<*, *> ?: return null
            val info = m["commentsInfo"] as? Map<*, *>
            return CommentsInfo(m["postId"].asLong() ?: return null, info?.let { it["totalCount"].asLong()?.toInt() ?: 0 }, m)
        }
    }
}

/**
 * Delayed sending (PyMax `DelayedAttributes`): the server posts the message at [timeToFire]
 * (epoch milliseconds); [notifySender] as PyMax (it passes the `notify` flag).
 */
data class DelayedSend(val timeToFire: Long, val notifySender: Boolean = true) {
    fun toPayload(): Map<String, Any?> = linkedMapOf("timeToFire" to timeToFire, "notifySender" to notifySender)
}

/**
 * One poll from `GET_POLL_UPDATES` 306. The reply item has no `_type`; keys are the app's
 * `pollId`, `title`, `answers` (`text`, `answerId`), `settings`, `version`, `state`.
 */
internal fun pollUpdate(value: Any?): com.maxly.core.media.Attachment.Poll? {
    val m = value as? Map<*, *> ?: return null
    val id = m["pollId"].asLong() ?: return null
    val answers = (m["answers"] as? List<*>).orEmpty().mapNotNull { a ->
        val am = a as? Map<*, *> ?: return@mapNotNull null
        PollAnswer(am["text"] as? String ?: return@mapNotNull null, am["answerId"].asLong())
    }
    return com.maxly.core.media.Attachment.Poll(
        pollId = id,
        version = m["version"].asLong()?.toInt(),
        title = m["title"] as? String,
        answers = answers,
        flags = PollFlag.of(m["settings"].asLong()?.toInt() ?: 0),
        state = PollState.from(m["state"]),
        raw = m,
    )
}
