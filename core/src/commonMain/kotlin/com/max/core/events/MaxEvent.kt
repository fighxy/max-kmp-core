package com.max.core.events

import com.max.core.api.Chat
import com.max.core.api.Folder
import com.max.core.api.MaxMessage
import com.max.core.api.ReactionCounter
import com.max.core.calls.ConversationParams

/**
 * A server-initiated notification, parsed from a push packet by [EventParser].
 *
 * Event kinds, opcodes and fields follow PyMax's dispatcher (`src/pymax/dispatch/mapping.py`
 * `EVENT_MAP` / `EventMapper`, `resolvers.py`) and its event models (`src/pymax/types/events/`).
 * kolibri only forwards raw pushes (`transport/dispatcher.rs`, `session/manager.rs`) and has no
 * typed events, except that incoming calls (`NOTIF_CALL_START` 137) are decoded as `vcp` in
 * `kolibri-net/src/calls/` / `kolibri-py/examples/call_bot.py`. Every event keeps the decoded
 * payload in [raw] and the packet [opcode].
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

    /**
     * Someone is typing (`NOTIF_TYPING` 129 `{chatId, userId, type?}`; PyMax `TypingEvent`).
     *
     * @property type the raw `type` as the server sent it (`null` when the push has none). Known
     *   values are in [com.max.core.api.TypingType]; use [effectiveType] for the normalised one.
     *   It is the last parameter, so positional calls with four arguments keep compiling.
     */
    data class Typing(
        val chatId: Long,
        val userId: Long,
        override val opcode: Int,
        override val raw: Any?,
        val type: String? = null,
    ) : MaxEvent {
        /**
         * [type] normalised by [com.max.core.api.TypingType.effective]: a known value as is,
         * [com.max.core.api.TypingType.TEXT] for a missing, blank or unrecognised one.
         */
        val effectiveType: String get() = com.max.core.api.TypingType.effective(type)
    }

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
     * Neither PyMax nor KometTeam/Komet read an own reaction from this push; [yourReaction] is
     * set only if the push happens to carry `yourReaction`, otherwise the stored one is kept.
     */
    data class ReactionsChanged(
        val chatId: Long,
        val messageId: String,
        val counters: List<ReactionCounter>,
        val totalCount: Int,
        override val opcode: Int,
        override val raw: Any?,
        val yourReaction: String? = null,
    ) : MaxEvent

    /**
     * An uploaded file / video / voice finished server-side processing (`NOTIF_ATTACH` 136).
     * PyMax `resolve_attach` tries, in this order, `FileUploadSignal {fileId}` → `FILE_READY`,
     * `VideoUploadSignal {videoId}` → `VIDEO_READY`, `AudioUploadSignal {audioId}` → `VOICE_READY`
     * (`src/pymax/types/events/{file,video,voice}.py`); its upload service waits for these.
     */
    data class AttachmentReady(val kind: Kind, val id: Long, override val opcode: Int, override val raw: Any?) : MaxEvent {
        enum class Kind { FILE, VIDEO, AUDIO }
    }

    /**
     * An incoming call (`NOTIF_CALL_START` 137). PyMax has no typed call event; fields follow
     * kolibri-py `examples/call_bot.py` (`callerId`, `type`, `vcp`, `conversationId`) plus the
     * optional `chatId` / `isContact` seen in third-party captures. [params] is the decoded [vcp]
     * or `null` when the string is missing or corrupt — the event is still typed.
     */
    data class CallStart(
        val callerId: Long,
        val conversationId: String,
        val type: String?,
        val chatId: Long?,
        val isContact: Boolean?,
        val vcp: String?,
        val params: ConversationParams?,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * Chat folders changed on the server, e.g. chats pinned, unpinned or reordered on another
     * device (`NOTIF_FOLDERS` 277). The payload carries `folders` (a list) and / or `folder` (one),
     * both merged into [folders], plus optional `foldersOrder` and `folderSync` (schema as used by
     * KometTeam/Komet `FoldersModule`). The pinned chats are the `favorites` of the "all chats"
     * folder, see `com.max.core.api.ChatFolders`.
     */
    data class FoldersChanged(
        val folders: List<Folder>,
        val foldersOrder: List<String>?,
        val folderSync: Long?,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * An owner's story ring changed (`NOTIF_STORIES_UPDATE` 216, `{storiesPreview}`, schema as
     * KometTeam/Komet `feature/FullStack` reads it): new stories, seen counts, or none left
     * ([StoryPreview.isEmpty]).
     */
    data class StoriesUpdated(val preview: com.max.core.api.StoryPreview, override val opcode: Int, override val raw: Any?) : MaxEvent

    /**
     * Any other push: opcodes without a typed event yet, payloads that do not fit the model, and
     * packets with `cmd != 0` (PyMax only maps `cmd == REQUEST (0)` frames). PyMax's "raw" events.
     */
    data class Unknown(override val opcode: Int, val cmd: Int, override val raw: Any?) : MaxEvent
}
