package com.max.core.api

import com.max.core.protocol.Opcode
import com.max.core.transport.OutboundDecision
import com.max.core.transport.OutboundGuard

/**
 * Two privacy switches of this core, independent of each other, both applied to every outgoing
 * request by [guard] (checked per request, so a request issued before a toggle but written after
 * it follows the new value):
 *
 * **Ghost mode** (nothing tells the server that the user is online or busy in a chat):
 * - `PING` 1 and `LOGIN` 19: `interactive` goes out as `false`, the flag the server uses for
 *   "online" (Komet's ghost mode does exactly this);
 * - `CHAT_HISTORY` 49: an `interactive: true` goes out as `false` (this core sends the flag only
 *   when a caller asks for it);
 * - `MSG_TYPING` 65 of every type (text, voice, video message, photo, video, file, sticker, i.e.
 *   typing, recording and uploading): not sent.
 *
 * **Hidden read receipts** (nothing tells others what was read):
 * - `CHAT_MARK` 50 `READ_MESSAGE`: not sent (`SET_AS_UNREAD` still goes: an explicit action that
 *   tells nobody anything); the client reads the chat locally ([LocalRead]);
 * - `STORIES_MARK` 214 (story seen; its owner sees the viewers): not sent;
 * - `MSG_DELIVERY` 303 (a delivery receipt; this core never sends one): not sent.
 *
 * Everything else goes unchanged: sending, editing, deleting messages, reactions, drafts,
 * uploads, calls, presence and history requests. They reveal activity by themselves.
 *
 * The answer to a server PING (opcode 1 sent by the server with `cmd` 0) is not a request and
 * never reaches the guard: the transport writes `cmd` 1 with the server's `seq` and an empty
 * body. It carries no `interactive` flag and reveals nothing beyond the open socket, so ghost
 * mode keeps answering (the Android app always answers; an unanswered keepalive may cost the
 * connection).
 */
object GhostMode {
    /** The device clock (ms), the fallback time of a local read whose message is not stored. */
    fun nowMs(): Long = com.max.core.epochMillis()

    /** `CHAT_MARK` types that tell the server something was read. */
    val READ_MARK_TYPES: Set<String> = setOf("READ_MESSAGE")

    /** The [OutboundGuard] of both switches, read per request. */
    fun guard(ghostMode: () -> Boolean, hideReadReceipts: () -> Boolean): OutboundGuard = OutboundGuard { opcode, payload ->
        decide(opcode, payload, ghostMode(), hideReadReceipts())
    }

    /** What the switches do with one request. */
    fun decide(opcode: Int, payload: Any?, ghostMode: Boolean, hideReadReceipts: Boolean): OutboundDecision = when (opcode) {
        Opcode.PING.value, Opcode.LOGIN.value, Opcode.CHAT_HISTORY.value ->
            if (ghostMode) notInteractive(opcode, payload) else OutboundDecision.Pass
        Opcode.MSG_TYPING.value ->
            if (ghostMode) OutboundDecision.Block("ghost mode: typing is not sent") else OutboundDecision.Pass
        Opcode.CHAT_MARK.value -> {
            val type = (payload as? Map<*, *>)?.get("type") as? String
            if (hideReadReceipts && (type == null || type in READ_MARK_TYPES)) {
                OutboundDecision.Block("read receipts hidden: read marks are not sent")
            } else {
                OutboundDecision.Pass
            }
        }
        Opcode.STORIES_MARK.value ->
            if (hideReadReceipts) OutboundDecision.Block("read receipts hidden: story views are not sent") else OutboundDecision.Pass
        Opcode.MSG_DELIVERY.value ->
            if (hideReadReceipts) OutboundDecision.Block("read receipts hidden: delivery receipts are not sent") else OutboundDecision.Pass
        else -> OutboundDecision.Pass
    }

    /**
     * [payload] with `interactive` set to `false`: always for `PING` / `LOGIN` (a missing flag is
     * added), only when present for `CHAT_HISTORY`.
     */
    private fun notInteractive(opcode: Int, payload: Any?): OutboundDecision {
        val map = payload as? Map<*, *>
        if (opcode == Opcode.CHAT_HISTORY.value && (map == null || "interactive" !in map)) return OutboundDecision.Pass
        if (map != null && map["interactive"] == false) return OutboundDecision.Pass
        val out = LinkedHashMap<Any?, Any?>()
        map?.let { out.putAll(it) }
        out["interactive"] = false
        return OutboundDecision.Rewrite(out)
    }
}

/**
 * A chat read locally only (hidden read receipts, [GhostMode]): [messageId] is the newest message the user
 * saw, [time] its time (ms, server clock). Unread counts after [time] for this account until the
 * server's own read mark reaches it.
 */
data class LocalRead(val messageId: Long, val time: Long) {
    companion object {
        /** `chatId:messageId:time` entries joined by `,` ([decode] reads it back). */
        fun encode(reads: Map<Long, LocalRead>): String =
            reads.entries.joinToString(",") { (chat, r) -> "$chat:${r.messageId}:${r.time}" }

        /** [encode]d entries; malformed ones are skipped. */
        fun decode(value: String?): Map<Long, LocalRead> {
            if (value.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<Long, LocalRead>()
            for (part in value.split(',')) {
                val f = part.trim().split(':')
                if (f.size != 3) continue
                val chat = f[0].toLongOrNull() ?: continue
                val id = f[1].toLongOrNull() ?: continue
                val time = f[2].toLongOrNull() ?: continue
                out[chat] = LocalRead(id, time)
            }
            return out
        }
    }
}
