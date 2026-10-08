package com.max.core.events

import com.max.core.api.Chat
import com.max.core.api.Folder
import com.max.core.api.MaxMessage
import com.max.core.api.ReactionInfo
import com.max.core.calls.ConversationParams
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
 * | `NOTIF_CONTACT` 131 | [MaxEvent.ContactUpdated] (`{contact}`) |
 * | `NOTIF_TYPING` 129 | [MaxEvent.Typing] (`type` optional) |
 * | `NOTIF_MARK` 130 | [MaxEvent.MessageRead] |
 * | `NOTIF_PRESENCE` 132 | [MaxEvent.Presence] |
 * | `NOTIF_MSG_REACTIONS_CHANGED` 155 | [MaxEvent.ReactionsChanged] |
 * | `NOTIF_ATTACH` 136 | [MaxEvent.AttachmentReady] (`fileId` / `videoId` / `audioId`) |
 * | `NOTIF_CALL_START` 137 | [MaxEvent.CallStart] (`callerId` + `conversationId`; `vcp` decoded when present) |
 * | `NOTIF_CONFIG` 134 | [MaxEvent.ConfigUpdated] (`{config}` or `chats` / `user` / `server` / `hash` at the top; at least one of them) |
 * | `NOTIF_FOLDERS` 277 | [MaxEvent.FoldersChanged] (`folders` / `folder`, `foldersOrder`, `folderSync`; at least one of them) |
 * | `NOTIF_DRAFT` 152 | [MaxEvent.DraftSaved] (`chatId` / `userId`, `draft` with a time; shape assumed from `DRAFT_SAVE`) |
 * | `NOTIF_DRAFT_DISCARD` 153 | [MaxEvent.DraftDiscarded] (`chatId` / `userId`, `time`; shape assumed from `DRAFT_DISCARD`) |
 *
 * Everything else — including an empty
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
        Opcode.NOTIF_CONTACT.value -> com.max.core.api.MaxUser.from(map["contact"])?.let { MaxEvent.ContactUpdated(it, opcode, raw) }
        Opcode.NOTIF_TYPING.value -> {
            val chatId = map["chatId"].long()
            val userId = map["userId"].long()
            // `type` is optional; a non-string value is treated as absent
            if (chatId == null || userId == null) null else MaxEvent.Typing(chatId, userId, opcode, raw, map["type"] as? String)
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
                MaxEvent.ReactionsChanged(chatId, messageId, info.counters, info.totalCount, opcode, raw, info.yourReaction?.takeIf { it.isNotEmpty() })
            }
        }
        Opcode.NOTIF_ATTACH.value -> {
            val file = map["fileId"].long()
            val video = map["videoId"].long()
            val audio = map["audioId"].long()
            when {
                file != null -> MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.FILE, file, opcode, raw)
                video != null -> MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.VIDEO, video, opcode, raw)
                audio != null -> MaxEvent.AttachmentReady(MaxEvent.AttachmentReady.Kind.AUDIO, audio, opcode, raw)
                else -> null
            }
        }
        Opcode.NOTIF_CALL_START.value -> {
            val callerId = map["callerId"].long()
            val conversationId = map["conversationId"] as? String
            if (callerId == null || conversationId.isNullOrEmpty()) null
            else {
                val vcp = map["vcp"] as? String
                MaxEvent.CallStart(
                    callerId = callerId,
                    conversationId = conversationId,
                    type = map["type"] as? String,
                    chatId = map["chatId"].long(),
                    isContact = map["isContact"] as? Boolean,
                    vcp = vcp,
                    params = vcp?.let(ConversationParams::decode),
                    opcode = opcode,
                    raw = raw,
                )
            }
        }
        Opcode.NOTIF_FOLDERS.value -> folders(opcode, map, raw)
        Opcode.NOTIF_DRAFT.value -> draftSaved(opcode, map, raw)
        Opcode.NOTIF_DRAFT_DISCARD.value -> draftDiscarded(opcode, map, raw)
        Opcode.NOTIF_CONFIG.value -> com.max.core.api.AccountConfigUpdate.fromPush(map)?.let { MaxEvent.ConfigUpdated(it, opcode, raw) }
        Opcode.NOTIF_STORIES_UPDATE.value ->
            com.max.core.api.StoryPreview.from(map["storiesPreview"])?.let { MaxEvent.StoriesUpdated(it, opcode, raw) }
        else -> null
    }

    /** `NOTIF_FOLDERS`: `null` (→ unknown) when none of the folder keys is present. */
    private fun folders(opcode: Int, map: Map<*, *>, raw: Any?): MaxEvent? {
        val list = map["folders"] as? List<*>
        val single = map["folder"] as? Map<*, *>
        val order = (map["foldersOrder"] as? List<*>)?.mapNotNull { it?.toString() }
        val sync = map["folderSync"].long()
        if (list == null && single == null && order == null && sync == null) return null
        val folders = list.orEmpty().mapNotNull { Folder.from(it) } + listOfNotNull(Folder.from(single))
        return MaxEvent.FoldersChanged(folders, order, sync, opcode, raw)
    }

    /** `chatId` xor `userId` of a draft push (both: `chatId` wins); `null` when neither is an id. */
    private fun draftAddress(map: Map<*, *>): Pair<Long?, Long?>? {
        val chatId = map["chatId"].long()
        val userId = map["userId"].long()
        return when {
            chatId != null -> chatId to null
            userId != null -> null to userId
            else -> null
        }
    }

    /** `NOTIF_DRAFT` 152; `null` (→ unknown) without an address, a `draft` map or a time. */
    private fun draftSaved(opcode: Int, map: Map<*, *>, raw: Any?): MaxEvent? {
        val (chatId, userId) = draftAddress(map) ?: return null
        val draft = map["draft"] as? Map<*, *> ?: return null
        val time = (draft["saveTime"] ?: draft["updateTime"] ?: draft["time"] ?: map["time"]).long() ?: return null
        val text = draft["text"] as? String ?: ""
        val elements = runCatching { com.max.core.api.TextElement.parseAll(draft["elements"], text.length) }.getOrDefault(emptyList())
        return MaxEvent.DraftSaved(chatId, userId, time, text, elements, draft["replyTo"].long(), draft, opcode, raw)
    }

    /** `NOTIF_DRAFT_DISCARD` 153; `null` (→ unknown) without an address or a time. */
    private fun draftDiscarded(opcode: Int, map: Map<*, *>, raw: Any?): MaxEvent? {
        val (chatId, userId) = draftAddress(map) ?: return null
        val time = (map["time"] ?: (map["draft"] as? Map<*, *>)?.get("time")).long() ?: return null
        return MaxEvent.DraftDiscarded(chatId, userId, time, opcode, raw)
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
