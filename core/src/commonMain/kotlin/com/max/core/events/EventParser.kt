package com.max.core.events

import com.max.core.api.Chat
import com.max.core.api.MaxMessage
import com.max.core.api.ReactionInfo
import com.max.core.protocol.Opcode
import com.max.core.transport.TransportPacket

/**
 * Maps a push (opcode + payload) to a [MaxEvent], like PyMax's `EventResolver` + `EventMapper`
 * (`src/pymax/dispatch/mapping.py`):
 *
 * | opcode | event |
 * |---|---|
 * | `NOTIF_MESSAGE` 128, `MSG_EDIT` 67 | [MaxEvent.NewMessage] / [MaxEvent.MessageEdited] (`status` EDITED) / [MaxEvent.MessagesDeleted] (`status` REMOVED) |
 * | `NOTIF_MSG_DELETE` 142 | [MaxEvent.MessagesDeleted] |
 * | `NOTIF_CHAT` 135 | [MaxEvent.ChatUpdated] |
 * | `NOTIF_TYPING` 129 | [MaxEvent.Typing] |
 * | `NOTIF_MARK` 130 | [MaxEvent.MessageRead] |
 * | `NOTIF_PRESENCE` 132 | [MaxEvent.Presence] |
 * | `NOTIF_MSG_REACTIONS_CHANGED` 155 | [MaxEvent.ReactionsChanged] |
 *
 * Everything else — including `NOTIF_ATTACH` 136 upload signals (media, not covered), an empty
 * payload (PyMax passes such frames on raw), a payload missing a required field, and
 * `cmd != 0` — becomes [MaxEvent.Unknown]. Parsing never throws.
 */
object EventParser {
    /** PyMax `Command.REQUEST`: the only `cmd` PyMax maps to typed events. */
    const val PUSH_CMD: Int = 0

    fun parse(packet: TransportPacket): MaxEvent = parse(packet.opcode, packet.cmd, packet.payload)

    fun parse(opcode: Int, cmd: Int, payload: Any?): MaxEvent {
        val unknown = MaxEvent.Unknown(opcode, cmd, payload)
        if (cmd != PUSH_CMD) return unknown
        val map = payload as? Map<*, *>
        if (map.isNullOrEmpty()) return unknown
        return runCatching { typed(opcode, map, payload) }.getOrNull() ?: unknown
    }

    private fun typed(opcode: Int, map: Map<*, *>, raw: Any?): MaxEvent? = when (opcode) {
        Opcode.NOTIF_MESSAGE.value, Opcode.MSG_EDIT.value -> message(opcode, map, raw)
        Opcode.NOTIF_MSG_DELETE.value -> {
            val chat = map["chat"] as? Map<*, *>
            val chatId = chat?.get("id").long() ?: map["chatId"].long()
            val ids = (map["messageIds"] as? List<*>)?.map { it.long() ?: return null }
            if (chatId == null || ids == null) null
            else MaxEvent.MessagesDeleted(chatId, ids, Chat.from(chat), null, map["ttl"] as? Boolean ?: false, opcode, raw)
        }
        Opcode.NOTIF_CHAT.value -> Chat.from(map["chat"])?.let { MaxEvent.ChatUpdated(it, opcode, raw) }
        Opcode.NOTIF_TYPING.value -> {
            val chatId = map["chatId"].long()
            val userId = map["userId"].long()
            if (chatId == null || userId == null) null else MaxEvent.Typing(chatId, userId, opcode, raw)
        }
        Opcode.NOTIF_MARK.value -> {
            val chatId = map["chatId"].long()
            val userId = map["userId"].long()
            val mark = map["mark"].long()
            val unread = map["setAsUnread"] as? Boolean
            if (chatId == null || userId == null || mark == null || unread == null) null
            else MaxEvent.MessageRead(chatId, userId, mark, unread, opcode, raw)
        }
        Opcode.NOTIF_PRESENCE.value -> {
            val presence = map["presence"] as? Map<*, *>
            val userId = map["userId"].long()
            if (presence == null || userId == null) null
            else MaxEvent.Presence(userId, presence["seen"].long(), presence["status"].long()?.toInt(), opcode, raw)
        }
        Opcode.NOTIF_MSG_REACTIONS_CHANGED.value -> {
            val chatId = map["chatId"].long()
            val messageId = map["messageId"]?.let { if (it is String) it else it.long()?.toString() }
            if (chatId == null || messageId == null) null
            else {
                val info = ReactionInfo.from(map)!!
                MaxEvent.ReactionsChanged(chatId, messageId, info.counters, info.totalCount, opcode, raw)
            }
        }
        else -> null
    }

    /** PyMax `resolve_message`: EDITED → edit, REMOVED → delete (`MessageDeleteEvent` from the envelope), else new. */
    private fun message(opcode: Int, map: Map<*, *>, raw: Any?): MaxEvent? {
        val message = MaxMessage.from(map) ?: return null
        return when (message.status) {
            "EDITED" -> MaxEvent.MessageEdited(message, opcode, raw)
            "REMOVED" -> {
                val chatId = message.chatId ?: return null
                MaxEvent.MessagesDeleted(chatId, listOf(message.id), null, message, map["ttl"] as? Boolean ?: false, opcode, raw)
            }
            else -> MaxEvent.NewMessage(message, opcode, raw)
        }
    }

    private fun Any?.long(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }
}
