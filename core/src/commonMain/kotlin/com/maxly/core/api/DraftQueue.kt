package com.maxly.core.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A draft save that lost to a message send into the same chat ([DraftQueue.save]): it was
 * issued before the send and either never went out ([time] `null`) or reached the server and is
 * covered by the `DRAFT_DISCARD` that follows the send ([time] is the server time of that save).
 * Nothing is wrong with the connection; the app may ignore it.
 */
class DraftSupersededException(val chatId: Long, val time: Long?) :
    IllegalStateException("draft of $chatId superseded by a send" + (time?.let { " (saved at $it)" } ?: " (not sent)"))

/**
 * Orders the draft requests of each chat so that a `DRAFT_SAVE` 176 cannot land after the
 * `DRAFT_DISCARD` 177 that follows a message send and leave a stale draft on the server.
 *
 * Per chat:
 * - saves and discards ([save], [exclusive], [discardAfterSend]) go out one at a time, in the
 *   order they were issued (a fair lock);
 * - every save and send gets an order number when it is issued ([save], [issueSend]);
 * - once a send succeeds ([sent]) a save issued before it that has not gone out yet is dropped,
 *   and one already in flight is "superseded": its result must not become the stored draft, and
 *   its server time is handed to the discard after the send;
 * - the discard after a send ([discardAfterSend]) waits until saves in flight complete (with
 *   success or failure), then discards at the latest of the consumed draft and the superseded
 *   saves.
 *
 * A save issued after a send started is not affected: a new draft typed meanwhile stays, even
 * when it was stored before the send completed.
 */
class DraftQueue {
    private class Lane {
        /** Serializes the requests of the chat. */
        val io = Mutex()
        var issued = 0L
        /** The order number of the latest successful send. */
        var sentUpTo = 0L
        /** Orders of saves issued and not finished. */
        val open = HashSet<Long>()
        /** Latest server time of a save superseded by a send, until a discard takes it. */
        var supersededTime: Long? = null
        /** The order number of the save whose draft was stored last. */
        var storedOrder = 0L
    }

    private val guard = Mutex()
    private val lanes = HashMap<Long, Lane>()

    private fun lane(chatId: Long): Lane = lanes.getOrPut(chatId) { Lane() }

    /**
     * Runs the save [send] (returns the server time) for [chatId] after the chat's earlier
     * requests. [onSaved] runs atomically with [sent] when the save is still current (put the
     * draft into the store there). Throws [DraftSupersededException] when a send issued later
     * already succeeded, before (not sent) or while (sent, not stored) it was in flight; the
     * errors of [send] pass through.
     */
    suspend fun <T> save(chatId: Long, send: suspend () -> Long, onSaved: suspend (Long) -> T): T {
        val (lane, order) = guard.withLock { lane(chatId).let { it to ++it.issued }.also { (l, o) -> l.open += o } }
        try {
            return lane.io.withLock {
                if (guard.withLock { lane.sentUpTo > order }) throw DraftSupersededException(chatId, null)
                val time = send()
                guard.withLock {
                    if (lane.sentUpTo > order) {
                        lane.supersededTime = maxOf(lane.supersededTime ?: time, time)
                        null
                    } else {
                        lane.storedOrder = order
                        Current(onSaved(time))
                    }
                }?.value ?: throw DraftSupersededException(chatId, time)
            }
        } finally {
            guard.withLock { lane.open -= order }
        }
    }

    private class Current<T>(val value: T)

    /** An order number for a message send into [chatId] issued now; pass it to [sent]. */
    suspend fun issueSend(chatId: Long): Long = guard.withLock { ++lane(chatId).issued }

    /**
     * Records the success of the send [order] into [chatId] and runs [onSent] atomically with
     * it (take the draft out of the store there when its argument is `true`: `false` when the
     * stored draft comes from a save issued after the send started, a newer draft to keep). The
     * second value tells whether a save issued before the send is still open: then a discard
     * must follow even without a consumed draft.
     */
    suspend fun <T> sent(chatId: Long, order: Long, onSent: suspend (consumeDraft: Boolean) -> T): Pair<T, Boolean> = guard.withLock {
        val lane = lane(chatId)
        lane.sentUpTo = maxOf(lane.sentUpTo, order)
        onSent(lane.storedOrder < order) to lane.open.any { it < order }
    }

    /**
     * The discard after a send into [chatId]: waits for the chat's requests issued before it,
     * then runs [discard] with the time of the latest superseded save (`null` when none),
     * which it takes.
     */
    suspend fun <T> discardAfterSend(chatId: Long, discard: suspend (supersededTime: Long?) -> T): T {
        val lane = guard.withLock { lane(chatId) }
        return lane.io.withLock {
            val superseded = guard.withLock { lane.supersededTime.also { lane.supersededTime = null } }
            discard(superseded)
        }
    }

    /** Runs [block] (an explicit discard) after the chat's earlier draft requests. */
    suspend fun <T> exclusive(chatId: Long, block: suspend () -> T): T {
        val lane = guard.withLock { lane(chatId) }
        return lane.io.withLock { block() }
    }
}
