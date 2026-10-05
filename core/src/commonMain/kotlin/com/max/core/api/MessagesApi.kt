package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.epochMillis
import com.max.core.protocol.Opcode
import com.max.core.transport.TransportPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Client message ids (`cid`) as PyMax `MessageService._next_cid`: the current time in ms, but
 * always greater than the previous id (starts from the clock at construction).
 *
 * Thread-safe (lock-free compare-and-set), so concurrent sends never get the same id. Use one
 * generator per session for every sender: `MaxApi.cids` is passed to its [MessagesApi] and
 * [ChatsApi], and `MaxClient` hands the same instance to its `MediaApi`.
 */
class ClientIdGenerator(private val clock: () -> Long = ::epochMillis) {
    private val prev = MutableStateFlow(clock())

    fun next(): Long {
        val now = clock()
        return prev.updateAndGet { maxOf(now, it + 1) }
    }
}

/** `itemType` of `CHAT_HISTORY` (PyMax `ItemType`). */
enum class HistoryItemType { REGULAR, DELAYED }

/**
 * Message requests over a [RequestSink] (a logged-in `SessionMachine` or `MaxTransport`).
 *
 * Opcodes, payloads and reply fields follow PyMax `src/pymax/api/messages/service.py`
 * (`MessageService`) and `payloads.py`; kolibri has no message methods, only the same opcode
 * numbers (`kolibri-net/src/protocol/opcodes.rs`). Payloads are sent in PyMax's key order with
 * `None` fields left out (`CamelModel.to_payload`, `exclude_none`).
 *
 * Text is sent as given, with optional raw formatting [elements][sendMessage]; PyMax instead
 * parses Markdown in the text (`Formatter.format_markdown`) into the plain text plus
 * `elements`. Attachments are sent through `com.max.core.media.MediaApi.sendMessage`. Delayed
 * sending ([scheduleMessage]), polls ([sendPoll], [votePoll]) and channel-post comments
 * ([sendComment] and the other `*Comment*` methods) follow the same service.
 *
 * Errors: ERROR replies throw `ServerErrorException`; an OK reply without the required fields
 * throws [MalformedReplyException]; invalid arguments throw `IllegalArgumentException`.
 */
class MessagesApi(
    private val sink: RequestSink,
    private val clock: () -> Long = ::epochMillis,
    private val cids: ClientIdGenerator = ClientIdGenerator(clock),
) {
    /**
     * Sends a text message (`MSG_SEND`, 64; PyMax `send_message`, `SendMessagePayload`):
     * `{chatId, message: {text, cid, elements, attaches: [], link?: {type: "REPLY", messageId}},
     * notify}`. The reply is the message envelope `{chatId, message, ...}`.
     *
     * @param replyTo id of the message to reply to (`link`).
     * @param notify PyMax's `send_message` default is `true`.
     * @param elements formatting elements `{type, from, length, attributes?}` (PyMax `Element`).
     */
    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyTo: Long? = null,
        notify: Boolean = true,
        elements: List<Map<String, Any?>> = emptyList(),
    ): MaxMessage {
        require(text.isNotEmpty()) { "text must not be empty" }
        val reply = sink.request(Opcode.MSG_SEND, sendMessagePayload(chatId, text, cids.next(), replyTo, notify, elements))
        return requireMessage(reply, Opcode.MSG_SEND, chatId)
    }

    /**
     * Forwards [messageId] from [sourceChatId] to [chatId] (`MSG_SEND`, 64; PyMax
     * `forward_message`, `ForwardMessagePayload`): `{chatId, message: {cid: -cid, link: {type:
     * "FORWARD", messageId: "<id as string>", chatId: source}, attaches: []}, notify}`.
     */
    suspend fun forwardMessage(chatId: Long, messageId: Long, sourceChatId: Long = chatId, notify: Boolean = true): MaxMessage {
        val reply = sink.request(Opcode.MSG_SEND, forwardMessagePayload(chatId, messageId, sourceChatId, -cids.next(), notify))
        return requireMessage(reply, Opcode.MSG_SEND, chatId)
    }

    /**
     * Loads messages by id (`MSG_GET`, 71; PyMax `get_messages`, `GetMessagesPayload`):
     * `{chatId, messageIds}`. Reply: `messages` (missing = empty).
     */
    suspend fun getMessages(chatId: Long, messageIds: List<Long>): List<MaxMessage> {
        val reply = sink.request(Opcode.MSG_GET, linkedMapOf("chatId" to chatId, "messageIds" to messageIds))
        return messageList(reply, Opcode.MSG_GET, chatId)
    }

    /**
     * Edits a message's text (`MSG_EDIT`, 67; PyMax `edit_message`, `EditMessagePayload`):
     * `{chatId, messageId, text, elements, attachments: []}`. Reply: `message` (required).
     */
    suspend fun editMessage(chatId: Long, messageId: Long, text: String, elements: List<Map<String, Any?>> = emptyList()): MaxMessage {
        require(text.isNotEmpty()) { "text must not be empty" }
        val payload = linkedMapOf<String, Any?>(
            "chatId" to chatId, "messageId" to messageId, "text" to text, "elements" to elements, "attachments" to emptyList<Any?>(),
        )
        val map = replyMap(sink.request(Opcode.MSG_EDIT, payload), Opcode.MSG_EDIT)
        return MaxMessage.from(map["message"], chatId) ?: throw MalformedReplyException(Opcode.MSG_EDIT, "no valid message", map)
    }

    /**
     * Deletes messages (`MSG_DELETE`, 66; PyMax `delete_message`, `DeleteMessagePayload`):
     * `{chatId, messageIds, forMe}`. PyMax ignores the reply; it is returned raw.
     */
    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>, forMe: Boolean = false): Map<*, *> {
        require(messageIds.isNotEmpty()) { "messageIds must not be empty" }
        val reply = sink.request(Opcode.MSG_DELETE, linkedMapOf("chatId" to chatId, "messageIds" to messageIds, "forMe" to forMe))
        return rawMap(reply)
    }

    /**
     * Loads chat history (`CHAT_HISTORY`, 49) in the shape Komet's current client sends
     * (`feature/FullStack`, `MessagesModule.fetchHistory`): `{chatId, from, forward, backward,
     * getMessages}`, `from` defaulting to a day ahead of now so the newest page never misses a
     * message to clock skew. PyMax's extra fields (`backwardTime`, `forwardTime`, `getChat`,
     * `itemType`, `interactive`) go out only when a caller asks for a non-default value: the full
     * PyMax shape, with `interactive: false` on every page, drew `too.many.requests` for some
     * chats where Komet's requests do not.
     * Reply: `messages` (missing = empty), plus `chat` when [getChat].
     */
    suspend fun getChatHistory(
        chatId: Long,
        from: Long? = null,
        forward: Int = 0,
        backward: Int = 40,
        backwardTime: Long = 0,
        forwardTime: Long = 0,
        getChat: Boolean = false,
        getMessages: Boolean = true,
        interactive: Boolean = false,
        itemType: HistoryItemType = HistoryItemType.REGULAR,
    ): ChatHistory {
        val payload = linkedMapOf<String, Any?>(
            "chatId" to chatId,
            "from" to (from ?: (clock() + NEWEST_PAGE_AHEAD_MS)),
            "forward" to forward,
            "backward" to backward,
            "getMessages" to getMessages,
        )
        if (backwardTime != 0L) payload["backwardTime"] = backwardTime
        if (forwardTime != 0L) payload["forwardTime"] = forwardTime
        if (getChat) payload["getChat"] = true
        if (itemType != HistoryItemType.REGULAR) payload["itemType"] = itemType.name
        if (interactive) payload["interactive"] = true
        val reply = sink.request(Opcode.CHAT_HISTORY, payload)
        val map = replyMap(reply, Opcode.CHAT_HISTORY)
        return ChatHistory(messageList(reply, Opcode.CHAT_HISTORY, chatId), Chat.from(map["chat"]), map)
    }

    /**
     * Messages of a chat with attachments of the given types (`CHAT_MEDIA`, 51), as Komet's shared
     * content screen reads them (`SharedContentModule.fetchMedia`): `{chatId, messageId,
     * attachTypes, forward, backward}`. [messageId] is the anchor: the first page starts from the
     * chat's last message, the next ones from the oldest message already received. Attachment
     * types are the `_type` names (`PHOTO`, `VIDEO`, `FILE`, `AUDIO`, `SHARE`).
     *
     * Reply: `messages` (missing = empty) and `total`, the number of matching messages on the
     * server when it sends one. The server may also return messages with other attachments and
     * the anchor page overlaps the next one, so callers filter and deduplicate. Invalid items are
     * skipped, not an error: one bad message must not hide the rest of the media.
     */
    suspend fun getChatMedia(
        chatId: Long,
        messageId: Long,
        attachTypes: List<String>,
        forward: Int = 0,
        backward: Int = 40,
    ): ChatMediaPage {
        require(attachTypes.isNotEmpty()) { "attachTypes must not be empty" }
        val payload = linkedMapOf<String, Any?>(
            "chatId" to chatId,
            "messageId" to messageId,
            "attachTypes" to attachTypes,
            "forward" to forward,
            "backward" to backward,
        )
        val map = replyMap(sink.request(Opcode.CHAT_MEDIA, payload), Opcode.CHAT_MEDIA)
        val items = map["messages"] as? List<*> ?: emptyList<Any?>()
        val messages = items.mapNotNull { MaxMessage.from(it, chatId) }
        return ChatMediaPage(messages, map["total"].asLong()?.toInt(), map)
    }

    /**
     * Marks messages up to [messageId] as read (`CHAT_MARK`, 50; PyMax `read_message`,
     * `ReadMessagesPayload`): `{type: "READ_MESSAGE", chatId, messageId, mark}` with `mark` = now.
     * Reply: `{unread, mark}` (required).
     */
    suspend fun markRead(chatId: Long, messageId: Long, mark: Long? = null): ReadState {
        val payload = linkedMapOf<String, Any?>("type" to "READ_MESSAGE", "chatId" to chatId, "messageId" to messageId, "mark" to (mark ?: clock()))
        val map = replyMap(sink.request(Opcode.CHAT_MARK, payload), Opcode.CHAT_MARK)
        val unread = map["unread"].asLong() ?: throw MalformedReplyException(Opcode.CHAT_MARK, "no unread", map)
        val newMark = map["mark"].asLong() ?: throw MalformedReplyException(Opcode.CHAT_MARK, "no mark", map)
        return ReadState(unread.toInt(), newMark, map)
    }

    /**
     * Marks the chat unread from [mark] (`CHAT_MARK`, 50, type `SET_AS_UNREAD`; Komet
     * `markUnread`). [mark] is the message time in milliseconds. Reply: `{unread, mark}`.
     */
    suspend fun markUnread(chatId: Long, mark: Long): ReadState {
        val payload = linkedMapOf<String, Any?>("type" to "SET_AS_UNREAD", "chatId" to chatId, "mark" to mark)
        val map = replyMap(sink.request(Opcode.CHAT_MARK, payload), Opcode.CHAT_MARK)
        val unread = map["unread"].asLong() ?: throw MalformedReplyException(Opcode.CHAT_MARK, "no unread", map)
        val newMark = map["mark"].asLong() ?: throw MalformedReplyException(Opcode.CHAT_MARK, "no mark", map)
        return ReadState(unread.toInt(), newMark, map)
    }

    /**
     * Pins a message (`CHAT_UPDATE`, 55; PyMax `pin_message`, `PinMessagePayload`):
     * `{chatId, notifyPin, pinMessageId}`. PyMax ignores the reply; it is returned raw.
     */
    suspend fun pinMessage(chatId: Long, messageId: Long, notifyPin: Boolean = true): Map<*, *> {
        val reply = sink.request(Opcode.CHAT_UPDATE, linkedMapOf("chatId" to chatId, "notifyPin" to notifyPin, "pinMessageId" to messageId))
        return rawMap(reply)
    }

    /**
     * Sets an emoji reaction (`MSG_REACTION`, 178; PyMax `add_reaction`, `AddReactionPayload`):
     * `{chatId, messageId, reaction: {reactionType: "EMOJI", id}}`. Reply: `reactionInfo` (optional).
     */
    suspend fun addReaction(chatId: Long, messageId: Long, reaction: String): ReactionInfo? {
        require(reaction.isNotEmpty()) { "reaction must not be empty" }
        val payload = linkedMapOf<String, Any?>(
            "chatId" to chatId, "messageId" to messageId, "reaction" to linkedMapOf("reactionType" to "EMOJI", "id" to reaction),
        )
        return ReactionInfo.from(rawMap(sink.request(Opcode.MSG_REACTION, payload))["reactionInfo"])
    }

    /**
     * Removes own reaction (`MSG_CANCEL_REACTION`, 179; PyMax `remove_reaction`,
     * `RemoveReactionPayload`): `{chatId, messageId}`. Reply: `reactionInfo` (optional).
     */
    suspend fun removeReaction(chatId: Long, messageId: Long): ReactionInfo? =
        ReactionInfo.from(rawMap(sink.request(Opcode.MSG_CANCEL_REACTION, linkedMapOf("chatId" to chatId, "messageId" to messageId)))["reactionInfo"])

    /**
     * Reactions of several messages (`MSG_GET_REACTIONS`, 180; PyMax `get_reactions`,
     * `GetReactionsPayload`): `{chatId, messageIds}`. Reply: `messagesReactions`, keyed by the
     * message id as a string; `null` when absent (as in PyMax).
     */
    suspend fun getReactions(chatId: Long, messageIds: List<Long>): Map<String, ReactionInfo>? {
        val map = rawMap(sink.request(Opcode.MSG_GET_REACTIONS, linkedMapOf("chatId" to chatId, "messageIds" to messageIds)))
        val reactions = map["messagesReactions"] as? Map<*, *> ?: return null
        return reactions.entries.mapNotNull { (k, v) -> ReactionInfo.from(v)?.let { k.toString() to it } }.toMap()
    }

    /**
     * Speech to text of a voice message or video note (`AUDIO_TRANSCRIPTION`, 202; the request
     * KometTeam/Komet sends): `{chatId, messageId, mediaId}`, [mediaId] being the attachment's
     * `audioId` (or `videoId`). Reply `{transcriptionStatus, transcription?}`: `1` ready (the
     * text may be empty when no speech was recognised), `0` in progress — the text then comes
     * with the `TRANSCRIPTION_RESULT` push (293). A missing status is `-1`.
     */
    suspend fun transcribe(chatId: Long, messageId: Long, mediaId: Long): Transcription {
        val map = rawMap(sink.request(Opcode.AUDIO_TRANSCRIPTION, linkedMapOf("chatId" to chatId, "messageId" to messageId, "mediaId" to mediaId)))
        return Transcription.from(map) ?: Transcription(-1, null)
    }

    /**
     * Who reacted to a message (`MSG_GET_DETAILED_REACTIONS`, 181; the request KometTeam/Komet
     * sends for its "read by" list): `{chatId, messageId, count}`. Reply: `reactions`, a list of
     * `{userId, reaction}`; entries without a numeric `userId` or with an empty reaction are
     * skipped, a missing list is empty.
     */
    suspend fun getDetailedReactions(chatId: Long, messageId: Long, count: Int = 100): List<ReactionUser> {
        require(count > 0) { "count must be positive" }
        val map = rawMap(sink.request(Opcode.MSG_GET_DETAILED_REACTIONS, linkedMapOf("chatId" to chatId, "messageId" to messageId, "count" to count)))
        return (map["reactions"] as? List<*>).orEmpty().mapNotNull { entry ->
            val m = entry as? Map<*, *> ?: return@mapNotNull null
            val user = m["userId"].asLong() ?: return@mapNotNull null
            val reaction = (m["reaction"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ReactionUser(user, reaction)
        }
    }

    // ---- delayed sending and polls ------------------------------------------------------------

    /**
     * Schedules a message (PyMax `send_message(send_at=...)`): `MSG_SEND` with
     * `message.delayedAttributes {timeToFire, notifySender}` ([sendAt] in epoch milliseconds,
     * `notifySender` = [notify] as PyMax). Scheduled messages are listed with
     * [getChatHistory]`(itemType = DELAYED)`.
     */
    suspend fun scheduleMessage(chatId: Long, text: String, sendAt: Long, notify: Boolean = true, elements: List<Map<String, Any?>> = emptyList()): MaxMessage {
        require(text.isNotEmpty()) { "text must not be empty" }
        val payload = sendMessagePayload(chatId, text, cids.next(), null, notify, elements, delayed = DelayedSend(sendAt, notify))
        return requireMessage(sink.request(Opcode.MSG_SEND, payload), Opcode.MSG_SEND, chatId)
    }

    /** Sends a poll (`MSG_SEND` with one PyMax `Poll` attach, no text). */
    suspend fun sendPoll(chatId: Long, poll: com.max.core.media.OutgoingAttachment.Poll, notify: Boolean = true): MaxMessage {
        val payload = sendMessagePayload(chatId, null, cids.next(), null, notify, attaches = listOf(poll.toPayload()))
        return requireMessage(sink.request(Opcode.MSG_SEND, payload), Opcode.MSG_SEND, chatId)
    }

    /** Votes (`SEND_VOTE` 304, PyMax `vote_poll`): `{chatId, messageId, pollId, answersIds}`; reply `state` (required). */
    suspend fun votePoll(chatId: Long, messageId: Long, pollId: Long, answerIds: List<Long>): PollState {
        require(answerIds.isNotEmpty()) { "answerIds must not be empty" }
        val payload = linkedMapOf<String, Any?>("chatId" to chatId, "messageId" to messageId, "pollId" to pollId, "answersIds" to answerIds)
        val map = replyMap(sink.request(Opcode.SEND_VOTE, payload), Opcode.SEND_VOTE)
        return PollState.from(map["state"]) ?: throw MalformedReplyException(Opcode.SEND_VOTE, "no poll state", map)
    }

    // ---- comments on channel posts (PyMax *_comment*) ------------------------------------------

    /** Comments on post [postId] (`MSG_SEND` + `postId`, PyMax `SendCommentPayload`). */
    suspend fun sendComment(chatId: Long, postId: Long, text: String, replyTo: Long? = null, notify: Boolean = true, elements: List<Map<String, Any?>> = emptyList()): MaxMessage {
        require(text.isNotEmpty()) { "text must not be empty" }
        val payload = sendMessagePayload(chatId, text, cids.next(), replyTo, notify, elements, postId = postId)
        return requireMessage(sink.request(Opcode.MSG_SEND, payload), Opcode.MSG_SEND, chatId)
    }

    /** Comments by id (`MSG_GET` 71, `{chatId, messageIds, postId}`). */
    suspend fun getComments(chatId: Long, postId: Long, messageIds: List<Long>): List<MaxMessage> {
        val reply = sink.request(Opcode.MSG_GET, linkedMapOf("chatId" to chatId, "messageIds" to messageIds, "postId" to postId))
        return messageList(reply, Opcode.MSG_GET, chatId)
    }

    /**
     * Comment history (`CHAT_HISTORY` 49 with `postId`) in the shape Komet's current client sends
     * (`feature/FullStack`, `CommentsModule.fetchHistory`): `{chatId, postId, from, forward,
     * backward, getMessages}`; `backward = 30`, `from = -1` (newest) by default.
     */
    suspend fun getCommentHistory(chatId: Long, postId: Long, from: Long = -1, backward: Int = 30, forward: Int = 0, getMessages: Boolean = true): List<MaxMessage> {
        val payload = linkedMapOf<String, Any?>(
            "chatId" to chatId, "postId" to postId, "from" to from, "forward" to forward, "backward" to backward,
            "getMessages" to getMessages,
        )
        return messageList(sink.request(Opcode.CHAT_HISTORY, payload), Opcode.CHAT_HISTORY, chatId)
    }

    /** Edits a comment (`MSG_EDIT` 67, `{chatId, messageId, text, elements, attachments: [], postId}`). */
    suspend fun editComment(chatId: Long, postId: Long, messageId: Long, text: String, elements: List<Map<String, Any?>> = emptyList()): MaxMessage {
        require(text.isNotEmpty()) { "text must not be empty" }
        val payload = linkedMapOf<String, Any?>(
            "chatId" to chatId, "messageId" to messageId, "text" to text, "elements" to elements, "attachments" to emptyList<Any?>(), "postId" to postId,
        )
        val map = replyMap(sink.request(Opcode.MSG_EDIT, payload), Opcode.MSG_EDIT)
        return MaxMessage.from(map["message"], chatId) ?: throw MalformedReplyException(Opcode.MSG_EDIT, "no valid message", map)
    }

    /** Deletes comments (`MSG_DELETE` 66, `{chatId, messageIds, forMe, postId}`); reply raw. */
    suspend fun deleteComments(chatId: Long, postId: Long, messageIds: List<Long>, forMe: Boolean = false): Map<*, *> {
        require(messageIds.isNotEmpty()) { "messageIds must not be empty" }
        return rawMap(sink.request(Opcode.MSG_DELETE, linkedMapOf("chatId" to chatId, "messageIds" to messageIds, "forMe" to forMe, "postId" to postId)))
    }

    /** Reacts to a comment (`MSG_REACTION` 178, reaction payload + `postId`). */
    suspend fun addCommentReaction(chatId: Long, postId: Long, messageId: Long, reaction: String): ReactionInfo? {
        require(reaction.isNotEmpty()) { "reaction must not be empty" }
        val payload = linkedMapOf<String, Any?>(
            "chatId" to chatId, "messageId" to messageId, "reaction" to linkedMapOf("reactionType" to "EMOJI", "id" to reaction), "postId" to postId,
        )
        return ReactionInfo.from(rawMap(sink.request(Opcode.MSG_REACTION, payload))["reactionInfo"])
    }

    /** Removes own comment reaction (`MSG_CANCEL_REACTION` 179, `{chatId, messageId, postId}`). */
    suspend fun removeCommentReaction(chatId: Long, postId: Long, messageId: Long): ReactionInfo? = ReactionInfo.from(
        rawMap(sink.request(Opcode.MSG_CANCEL_REACTION, linkedMapOf("chatId" to chatId, "messageId" to messageId, "postId" to postId)))["reactionInfo"],
    )

    /** (Un)subscribes from a post's comments (`CHAT_SUBSCRIBE` 75, `{chatId, postId, subscribe}`). */
    suspend fun subscribeComments(chatId: Long, postId: Long, subscribe: Boolean = true) {
        sink.request(Opcode.CHAT_SUBSCRIBE, linkedMapOf("chatId" to chatId, "postId" to postId, "subscribe" to subscribe))
    }

    /** Comment counters (`MSG_GET_COMMENTS_INFO` 91, `{chatId, postIds}`); reply `commentsInfoUpdates`. */
    suspend fun getCommentsInfo(chatId: Long, postIds: List<Long>): List<CommentsInfo> {
        val map = rawMap(sink.request(Opcode.MSG_GET_COMMENTS_INFO, linkedMapOf("chatId" to chatId, "postIds" to postIds)))
        return (map["commentsInfoUpdates"] as? List<*>).orEmpty().mapNotNull { CommentsInfo.from(it) }
    }

    /** Deletes all comments of [userId] under a post (`MSG_DELETE_USER_COMMENTS` 94, `{chatId, postId, userId, messageId}`). */
    suspend fun deleteUserComments(chatId: Long, postId: Long, userId: Long, messageId: Long) {
        sink.request(Opcode.MSG_DELETE_USER_COMMENTS, linkedMapOf("chatId" to chatId, "postId" to postId, "userId" to userId, "messageId" to messageId))
    }

    /**
     * `MSG_SEND` body (PyMax `SendMessagePayload` / `SendMessagePayloadMessage`): `text` is left
     * out when `null` (attachments only), `attaches` holds attachment payloads such as
     * `com.max.core.media.OutgoingAttachment.toPayload()`.
     */
    fun sendMessagePayload(
        chatId: Long,
        text: String?,
        cid: Long,
        replyTo: Long?,
        notify: Boolean,
        elements: List<Map<String, Any?>> = emptyList(),
        attaches: List<Map<String, Any?>> = emptyList(),
        delayed: DelayedSend? = null,
        postId: Long? = null,
    ): Map<String, Any?> {
        val message = linkedMapOf<String, Any?>()
        if (text != null) message["text"] = text
        message["cid"] = cid
        message["elements"] = elements
        message["attaches"] = attaches
        if (replyTo != null) message["link"] = linkedMapOf("type" to "REPLY", "messageId" to replyTo)
        if (delayed != null) message["delayedAttributes"] = delayed.toPayload()
        val payload = linkedMapOf<String, Any?>("chatId" to chatId, "message" to message, "notify" to notify)
        // PyMax SendCommentPayload: subclass field after the inherited ones
        if (postId != null) payload["postId"] = postId
        return payload
    }

    /**
     * Next client message id (PyMax `_next_cid`); exposed so a caller that must resend the same
     * `MSG_SEND` payload (e.g. after `attachment.not.ready`) can build it once.
     */
    fun nextCid(): Long = cids.next()

    /**
     * Sends a prepared `MSG_SEND` [payload] (see [sendMessagePayload]) and parses the reply like
     * [sendMessage].
     */
    suspend fun sendPrepared(chatId: Long, payload: Map<String, Any?>): MaxMessage =
        requireMessage(sink.request(Opcode.MSG_SEND, payload), Opcode.MSG_SEND, chatId)

    /** `MSG_SEND` body for a forward (PyMax `ForwardMessagePayload`); [cid] is negative in PyMax. */
    fun forwardMessagePayload(chatId: Long, messageId: Long, sourceChatId: Long, cid: Long, notify: Boolean): Map<String, Any?> =
        linkedMapOf(
            "chatId" to chatId,
            "message" to linkedMapOf(
                "cid" to cid,
                "link" to linkedMapOf("type" to "FORWARD", "messageId" to messageId.toString(), "chatId" to sourceChatId),
                "attaches" to emptyList<Any?>(),
            ),
            "notify" to notify,
        )
}

internal fun rawMap(reply: TransportPacket): Map<*, *> = reply.payload as? Map<*, *> ?: emptyMap<Any?, Any?>()

/** The reply map; a non-map payload (including none) is malformed. */
internal fun replyMap(reply: TransportPacket, opcode: Opcode): Map<*, *> =
    reply.payload as? Map<*, *> ?: throw MalformedReplyException(opcode, "payload is not a map", reply.payload)

private fun requireMessage(reply: TransportPacket, opcode: Opcode, chatId: Long): MaxMessage {
    val map = replyMap(reply, opcode)
    if (map.isEmpty()) throw MalformedReplyException(opcode, "empty payload", map)
    return MaxMessage.from(map, chatId) ?: throw MalformedReplyException(opcode, "no valid message", map)
}

private fun messageList(reply: TransportPacket, opcode: Opcode, chatId: Long): List<MaxMessage> {
    val map = replyMap(reply, opcode)
    val items = map["messages"] ?: return emptyList()
    val list = items as? List<*> ?: throw MalformedReplyException(opcode, "messages is not a list", map)
    return list.map { MaxMessage.from(it, chatId) ?: throw MalformedReplyException(opcode, "invalid message in messages", map) }
}

/**
 * A transcription answer or push: [status] `1` ready, `0` in progress, `-1` failed or unknown;
 * [text] is the recognised speech (empty when there was none), `null` while not ready.
 * [messageId] and [chatId] are set for the `TRANSCRIPTION_RESULT` push.
 */
data class Transcription(val status: Int, val text: String?, val messageId: Long? = null, val chatId: Long? = null) {
    companion object {
        /** Reads `{transcriptionStatus, transcription, messageId, chatId}`, also nested in `message`. */
        fun from(raw: Any?): Transcription? {
            val outer = raw as? Map<*, *> ?: return null
            val map = outer["message"] as? Map<*, *> ?: outer
            val status = map["transcriptionStatus"].asLong()?.toInt() ?: if (map.containsKey("transcription")) 1 else -1
            return Transcription(
                status = status,
                text = map["transcription"] as? String,
                messageId = (map["messageId"] ?: map["msgId"]).asLong(),
                chatId = map["chatId"].asLong(),
            )
        }
    }
}

/** How far ahead of now the newest history page starts (`from`), as Komet sends it: one day. */
internal const val NEWEST_PAGE_AHEAD_MS: Long = 86_400_000L
