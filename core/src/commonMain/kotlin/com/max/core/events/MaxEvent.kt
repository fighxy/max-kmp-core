package com.max.core.events

import com.max.core.api.Chat
import com.max.core.api.Folder
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
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

    /**
     * A contact changed on another session (`NOTIF_CONTACT` 131 `{contact}`, MAX web client):
     * a rename, add or remove. The store keeps the newer `updateTime`.
     */
    data class ContactUpdated(val user: MaxUser, override val opcode: Int, override val raw: Any?) : MaxEvent

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

    /**
     * Presence changed (`NOTIF_PRESENCE` 132 `{presence: {seen?, status?}, userId}`; PyMax
     * `PresenceEvent`). [status] codes are in [com.max.core.api.PresenceStatus]; [seen] is Unix
     * seconds. A push without `seen` keeps the stored time ([com.max.core.api.Presences.merge]).
     */
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
     * The account config changed on the server (`NOTIF_CONFIG` 134), e.g. a chat muted or unmuted
     * on another device: `config.chats[<chatId>].dontDisturbUntil` (`0` sound on, `-1` muted for
     * good, else the end of the mute in ms). [update] holds only what the push carries; `MaxClient`
     * merges it into its account config ([com.max.core.api.AccountConfig.mergedWith]) and keeps
     * [com.max.core.api.AccountConfigUpdate.hash] as the next `LOGIN` `configHash`. The payload
     * schema is not in the references (they only name the opcode): `{config: {...}}` and the
     * sections at the top level are both read ([com.max.core.api.AccountConfigUpdate.fromPush]).
     */
    data class ConfigUpdated(
        val update: com.max.core.api.AccountConfigUpdate,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent {
        /** The chats whose settings (mute) the push names. */
        val chatIds: List<Long> get() = update.chatIds

        /** The new config hash, `null` when the push has none. */
        val hash: String? get() = update.hash
    }

    /**
     * A draft saved on another device (`NOTIF_DRAFT` 152). The references only name the opcode
     * and the MAX web client ignores the push, so the payload is read tolerantly in the shape of
     * the `DRAFT_SAVE` 176 request: `{chatId | userId, draft: {text?, elements?, replyTo?,
     * attaches?, saveTime | updateTime | time}}` (a top-level `time` is used when the draft has
     * none). Exactly one of [chatId] / [userId] is set (a dialog is addressed by the peer, see
     * [targetChatId]). A push without an address or a time stays [Unknown].
     */
    data class DraftSaved(
        val chatId: Long?,
        val userId: Long?,
        val time: Long,
        val text: String,
        val elements: List<com.max.core.api.TextElement>,
        val replyTo: Long?,
        val draft: Map<*, *>,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent {
        /** The chat the draft belongs to: [chatId], or the dialog with [userId] (`me xor userId`); `null` without [me]. */
        fun targetChatId(me: Long?): Long? = chatId ?: userId?.let { u -> me?.let { com.max.core.api.Drafts.dialogChatId(it, u) } }

        /** The draft for chat [chatId] ([com.max.core.api.MaxDraft.attaches] are read from [draft]). */
        fun toDraft(chatId: Long): com.max.core.api.MaxDraft = com.max.core.api.MaxDraft(chatId, text, elements, replyTo, time, draft)
    }

    /**
     * A draft discarded on another device (`NOTIF_DRAFT_DISCARD` 153), read in the shape of the
     * `DRAFT_DISCARD` 177 request: `{chatId | userId, time}`. Unverified like [DraftSaved]; a
     * push without an address or a time stays [Unknown].
     */
    data class DraftDiscarded(
        val chatId: Long?,
        val userId: Long?,
        val time: Long,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent {
        /** As [DraftSaved.targetChatId]. */
        fun targetChatId(me: Long?): Long? = chatId ?: userId?.let { u -> me?.let { com.max.core.api.Drafts.dialogChatId(it, u) } }
    }

    /**
     * Pins of a chat changed (`NOTIF_CHAT_MESSAGE_PINNED` 243): `{chatId, pinnedMessagesState}`.
     * [chatId] is the push's own id when it is not zero, otherwise the id inside [state].
     */
    data class PinsChanged(
        val chatId: Long,
        val state: com.max.core.api.PinnedMessageState,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * This account's own reaction changed on another device (`NOTIF_MSG_YOU_REACTED` 156):
     * `{chatId, messageId, reactionInfo, postId?}`.
     */
    data class YouReacted(
        val chatId: Long,
        val messageId: Long,
        val reaction: com.max.core.api.ReactionInfo,
        val postId: Long?,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * The account profile changed on another device (`NOTIF_PROFILE` 159): `{profile}` with
     * `contact` and `profileOptions` (the app also keeps a restrictions map on the raw object).
     */
    data class ProfileUpdated(
        val profile: com.max.core.api.Profile,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * A voice message was transcribed (`TRANSCRIPTION_RESULT` 293). Same body as
     * [com.max.core.api.Transcription.from], including a nested `message`.
     */
    data class TranscriptionReady(
        val transcription: com.max.core.api.Transcription,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * An attachment failed (`NOTIF_ATTACH` 136 with `error` and no `fileId` / `videoId` / `audioId`).
     * A push that names an id stays [AttachmentReady]; the error string is not on that event.
     */
    data class AttachmentFailed(
        val error: String,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * A scheduled message was created, edited, deleted or posted (`NOTIF_MSG_DELAYED` 154):
     * `{chatId, userId, updateTypeId, message?, messageIds, lastDelayedUpdateTime}`.
     * [updateType] is null when the byte is missing or not 0..3. The store does not apply this.
     */
    data class DelayedUpdated(
        val chatId: Long,
        val userId: Long?,
        val updateType: com.max.core.api.DelayedUpdate?,
        val message: com.max.core.api.MaxMessage?,
        val messageIds: List<Long>,
        val lastDelayedUpdateTime: Long?,
        override val opcode: Int,
        override val raw: Any?,
    ) : MaxEvent

    /**
     * Any other push: opcodes without a typed event yet, payloads that do not fit the model, and
     * packets with `cmd != 0` (PyMax only maps `cmd == REQUEST (0)` frames). PyMax's "raw" events.
     */
    data class Unknown(override val opcode: Int, val cmd: Int, override val raw: Any?) : MaxEvent
}
