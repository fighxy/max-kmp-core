package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode

/**
 * A server draft of one chat (MAX web client: `{saveTime, text, elements, replyTo?, attaches?}`).
 * [chatId] is the chat it belongs to (for a dialog `me xor peer`, see [Drafts.dialogChatId]);
 * [updateTime] is the server's save time (`saveTime`, the `time` of the `DRAFT_SAVE` reply).
 * Attachments of an incoming draft (`LOGIN`, push 152) are read into [attaches] from [raw]; the
 * core never sends them. A draft with empty text but a [replyTo] is a valid draft.
 */
data class MaxDraft(
    val chatId: Long,
    val text: String,
    val elements: List<TextElement>,
    val replyTo: Long?,
    val updateTime: Long,
    val raw: Map<*, *> = emptyMap<Any?, Any?>(),
) {
    /** `attaches` of the draft as the server sent them (maps); empty when none. Read only, never sent. */
    val attaches: List<Map<*, *>> get() = (raw["attaches"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    /**
     * No draft at all: blank text (after trimming) and no [replyTo] (shared Orbitle rule, fixtures
     * `test-fixtures/drafts/merge.json`; a reply alone is a draft, attachments do not count).
     */
    val isEmpty: Boolean get() = text.isBlank() && replyTo == null
}

/**
 * Drafts of the `LOGIN` 19 reply: `drafts: {chats: {saved: {chatId: draft}, discarded: {chatId:
 * time}}, users: {saved: {userId: draft}, discarded: {userId: time}}}`. User keys (dialogs, bots,
 * Saved Messages) are turned into dialog chat ids. Both maps are keyed by chat id. An empty
 * saved draft ([MaxDraft.isEmpty]) is listed in [discarded] at its time, like an empty push 152.
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
     * chat not known ([chat] `null`) is judged by its id as the MAX web client does: `0` (Saved
     * Messages) is the own id, a positive id is a dialog with `chatId xor me`, a negative one a
     * group or channel (`chatId`). Without [me] everything goes by `chatId`.
     */
    fun address(chatId: Long, chat: Chat?, me: Long?): DraftAddress {
        if (chat == null && me != null && chatId >= 0) return DraftAddress(null, if (chatId == 0L) me else chatId xor me)
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
                val d = parse(id, v) ?: return@forEach
                // An empty saved draft is a discard at its time, like an empty push 152.
                if (d.isEmpty) discarded[id] = maxOf(discarded[id] ?: Long.MIN_VALUE, d.updateTime)
                else if ((saved[id]?.updateTime ?: Long.MIN_VALUE) < d.updateTime) saved[id] = d
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

    /**
     * Rule for a draft saved on another device (push 152): only a strictly later [incoming]
     * replaces [stored]; on an equal time ours stays (shared Orbitle rule, fixture
     * `drafts/merge.json` `equal-keeps-local`).
     */
    fun mergeRemote(stored: MaxDraft?, incoming: MaxDraft): MaxDraft =
        if (stored != null && stored.updateTime >= incoming.updateTime) stored else incoming

    /**
     * What the composer of one chat should show, from the app's own [local] draft, the server
     * draft kept in the store ([server], `MaxState.draftOf`) and the chat's discard mark
     * ([discardedAt], `MaxState.draftDiscardedAt`). Shared Orbitle rule (fixture
     * `drafts/merge.json`):
     * 1. an empty draft ([MaxDraft.isEmpty]) counts as none;
     * 2. of the two drafts the later `updateTime` wins, on an equal time [local] stays;
     * 3. a discard at the winner's time or later clears it (`null`): on an equal time the
     *    discard wins.
     */
    fun reconcile(local: MaxDraft?, server: MaxDraft?, discardedAt: Long?): MaxDraft? {
        val l = local?.takeUnless { it.isEmpty }
        val s = server?.takeUnless { it.isEmpty }
        val winner = when {
            l == null -> s
            s == null -> l
            s.updateTime > l.updateTime -> s
            else -> l
        } ?: return null
        return if (discardedAt != null && discardedAt >= winner.updateTime) null else winner
    }
}

/**
 * Server drafts: `DRAFT_SAVE` 176 and `DRAFT_DISCARD` 177, as the MAX web client sends them.
 * The pushes `NOTIF_DRAFT` 152 / `NOTIF_DRAFT_DISCARD` 153 (ignored by the web client, payload
 * unverified) are read tolerantly as `MaxEvent.DraftSaved` / `MaxEvent.DraftDiscarded`.
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
