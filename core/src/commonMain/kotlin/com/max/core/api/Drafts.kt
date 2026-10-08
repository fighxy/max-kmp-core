package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode

/**
 * A server draft of one chat (MAX web client: `{saveTime, text, elements, replyTo?, attaches?}`).
 * [chatId] is the chat it belongs to (for a dialog `me xor peer`, see [Drafts.dialogChatId]);
 * [updateTime] is the server's save time (`saveTime`, the `time` of the `DRAFT_SAVE` reply).
 * Attachments are kept only in [raw]: no client sends them.
 */
data class MaxDraft(
    val chatId: Long,
    val text: String,
    val elements: List<TextElement>,
    val replyTo: Long?,
    val updateTime: Long,
    val raw: Map<*, *> = emptyMap<Any?, Any?>(),
)

/**
 * Drafts of the `LOGIN` 19 reply: `drafts: {chats: {saved: {chatId: draft}, discarded: {chatId:
 * time}}, users: {saved: {userId: draft}, discarded: {userId: time}}}`. User keys (dialogs, bots,
 * Saved Messages) are turned into dialog chat ids. Both maps are keyed by chat id.
 */
data class DraftsSnapshot(val saved: Map<Long, MaxDraft>, val discarded: Map<Long, Long>) {
    companion object {
        val EMPTY = DraftsSnapshot(emptyMap(), emptyMap())
    }
}

/** Where a draft request goes: `chatId` for groups, channels and threads, `userId` for dialogs. */
data class DraftAddress(val chatId: Long?, val userId: Long?) {
    init {
        require((chatId == null) != (userId == null)) { "exactly one of chatId and userId" }
    }

    fun put(into: MutableMap<String, Any?>) {
        if (chatId != null) into["chatId"] = chatId else into["userId"] = userId
    }
}

/** Parsing and addressing of server drafts (protocol as the MAX web client uses it). */
object Drafts {
    /** Chat id of the dialog of [me] with [userId] (`me xor userId`; Saved Messages: the own id). */
    fun dialogChatId(me: Long, userId: Long): Long = me xor userId

    /**
     * The address of [chat]'s draft: a `DIALOG` (also bots and Saved Messages) is addressed by
     * the other participant's id (the own id for Saved Messages), anything else by `chatId`. A
     * chat not known ([chat] `null`) is addressed by [chatId].
     */
    fun address(chatId: Long, chat: Chat?, me: Long?): DraftAddress {
        if (chat == null || chat.type != "DIALOG" || me == null) return DraftAddress(chatId, null)
        val ids = (chat.raw["participants"] as? Map<*, *>).orEmpty().keys.mapNotNull { it.asLong() }.distinct()
        val peer = ids.firstOrNull { it != me } ?: me.takeIf { ids.contains(me) } ?: (chatId xor me)
        return DraftAddress(null, peer)
    }

    /** One draft object; `null` when it is not a map or has no save time. */
    fun parse(chatId: Long, raw: Any?): MaxDraft? {
        val m = raw as? Map<*, *> ?: return null
        val time = (m["saveTime"] ?: m["updateTime"] ?: m["time"]).asLong() ?: return null
        val text = m["text"] as? String ?: ""
        return MaxDraft(chatId, text, TextElement.parseAll(m["elements"], text.length), m["replyTo"].asLong(), time, m)
    }

    /** The `drafts` of a `LOGIN` reply ([DraftsSnapshot]); missing parts are empty. */
    fun fromLogin(loginReply: Map<*, *>, me: Long?): DraftsSnapshot {
        val drafts = loginReply["drafts"] as? Map<*, *> ?: return DraftsSnapshot.EMPTY
        val saved = LinkedHashMap<Long, MaxDraft>()
        val discarded = LinkedHashMap<Long, Long>()
        fun read(section: Any?, chatIdOf: (Long) -> Long?) {
            val map = section as? Map<*, *> ?: return
            (map["saved"] as? Map<*, *>).orEmpty().forEach { (k, v) ->
                val id = k.asLong()?.let(chatIdOf) ?: return@forEach
                parse(id, v)?.let { d -> if ((saved[id]?.updateTime ?: Long.MIN_VALUE) < d.updateTime) saved[id] = d }
            }
            (map["discarded"] as? Map<*, *>).orEmpty().forEach { (k, v) ->
                val id = k.asLong()?.let(chatIdOf) ?: return@forEach
                val time = v.asLong() ?: return@forEach
                discarded[id] = maxOf(discarded[id] ?: Long.MIN_VALUE, time)
            }
        }
        read(drafts["chats"]) { it }
        read(drafts["users"]) { user -> me?.let { dialogChatId(it, user) } }
        return DraftsSnapshot(saved, discarded)
    }

    /**
     * Store rule (MAX web client): a newer [incoming] replaces [stored], an equal one too (same
     * save), an older one is ignored.
     */
    fun merge(stored: MaxDraft?, incoming: MaxDraft): MaxDraft =
        if (stored != null && stored.updateTime > incoming.updateTime) stored else incoming
}

/**
 * Server drafts: `DRAFT_SAVE` 176 and `DRAFT_DISCARD` 177, as the MAX web client sends them.
 * Pushes `NOTIF_DRAFT` 152 / `NOTIF_DRAFT_DISCARD` 153 are not read (the web client ignores them
 * and their payload is unverified); they stay `MaxEvent.Unknown`.
 */
class DraftsApi(private val sink: RequestSink) {
    /**
     * Saves a draft: `{chatId | userId, draft: {text?, elements, replyTo?}}` (`text` left out
     * when empty, `elements` always sent, `[]` without formatting; attachments never). Returns
     * the server's save time (reply `time`), the draft's new update time.
     */
    suspend fun saveDraft(address: DraftAddress, text: String, elements: List<TextElement> = emptyList(), replyTo: Long? = null): Long {
        val draft = linkedMapOf<String, Any?>()
        if (text.isNotEmpty()) draft["text"] = text
        draft["elements"] = TextElement.payloadFor(text, elements)
        if (replyTo != null) draft["replyTo"] = replyTo
        val payload = linkedMapOf<String, Any?>()
        address.put(payload)
        payload["draft"] = draft
        val map = replyMap(sink.request(Opcode.DRAFT_SAVE, payload), Opcode.DRAFT_SAVE)
        return map["time"].asLong() ?: throw MalformedReplyException(Opcode.DRAFT_SAVE, "no time", map)
    }

    /** Discards the draft saved at [time] (its update time): `{chatId | userId, time}`. */
    suspend fun discardDraft(address: DraftAddress, time: Long) {
        val payload = linkedMapOf<String, Any?>()
        address.put(payload)
        payload["time"] = time
        rawMap(sink.request(Opcode.DRAFT_DISCARD, payload))
    }
}
