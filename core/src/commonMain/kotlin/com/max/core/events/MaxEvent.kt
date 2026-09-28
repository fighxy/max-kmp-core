package com.max.core.events

import com.max.core.api.Chat
import com.max.core.api.MaxMessage
import com.max.core.api.ReactionCounter

/**
 * A server-initiated notification, parsed from a push packet by [EventParser].
 *
 * Event kinds, opcodes and fields follow PyMax's dispatcher (`src/pymax/dispatch/mapping.py`
 * `EVENT_MAP` / `EventMapper`, `resolvers.py`) and its event models (`src/pymax/types/events/`).
 * kolibri only forwards raw pushes (`transport/dispatcher.rs`, `session/manager.rs`) and has no
 * typed events. Every event keeps the decoded payload in [raw] and the packet [opcode].
 */
sealed interface MaxEvent {
    val opcode: Int
    val raw: Any?

    /** A new message (`NOTIF_MESSAGE` 128; PyMax `MESSAGE_NEW`). */
    data class NewMessage(val message: MaxMessage, override val opcode: Int, override val raw: Any?) : MaxEvent

    /**
     * An edited message: `NOTIF_MESSAGE` 128 or a pushed `MSG_EDIT` 67 whose message has
     * `status = "EDITED"` (PyMax `resolve_message` → `MESSAGE_EDIT`).
     */
    data class MessageEdited(val message: MaxMessage, override val opcode: Int, override val raw: Any?) : MaxEvent

    /**
     * Deleted messages (PyMax `MESSAGE_DELETE`, `MessageDeleteEvent`): either `NOTIF_MSG_DELETE` 142
     * `{chat, messageIds, ttl?}` ([chat] set, `chatId = chat.id`), or `NOTIF_MESSAGE` 128 / `MSG_EDIT`
     * 67 carrying a message with `status = "REMOVED"` ([message] set, `messageIds = [message.id]`).
     */
    data class MessagesDeleted(
        val chatId: Long,
        val messageIds: List<Long>,
        val chat: Chat?,
        val message: MaxMessage?,
        val ttl: Boolean,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /** A chat changed (`NOTIF_CHAT` 135 `{chat}`; PyMax `CHAT_UPDATE`). */
    data class ChatUpdated(val chat: Chat, override val opcode: Int, override val raw: Any?) : MaxEvent

    /** Someone is typing (`NOTIF_TYPING` 129 `{chatId, userId}`; PyMax `TypingEvent`). */
    data class Typing(val chatId: Long, val userId: Long, override val opcode: Int, override val raw: Any?) : MaxEvent

    /**
     * Read mark moved (`NOTIF_MARK` 130 `{setAsUnread, chatId, userId, mark}`; PyMax
     * `MessageReadEvent`, all fields required).
     */
    data class MessageRead(
        val chatId: Long,
        val userId: Long,
        val mark: Long,
        val setAsUnread: Boolean,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /** Presence changed (`NOTIF_PRESENCE` 132 `{presence: {seen?, status?}, userId}`; PyMax `PresenceEvent`). */
    data class Presence(val userId: Long, val seen: Long?, val status: Int?, override val opcode: Int, override val raw: Any?) : MaxEvent

    /**
     * Reactions on a message changed (`NOTIF_MSG_REACTIONS_CHANGED` 155 `{messageId, chatId,
     * counters?, totalCount}`; PyMax `ReactionUpdateEvent`, `messageId` is a string there).
     */
    data class ReactionsChanged(
        val chatId: Long,
        val messageId: String,
        val counters: List<ReactionCounter>,
        val totalCount: Int,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * Any other push: opcodes without a typed event yet, payloads that do not fit the model, and
     * packets with `cmd != 0` (PyMax only maps `cmd == REQUEST (0)` frames). PyMax's "raw" events.
     */
    data class Unknown(override val opcode: Int, val cmd: Int, override val raw: Any?) : MaxEvent
}
