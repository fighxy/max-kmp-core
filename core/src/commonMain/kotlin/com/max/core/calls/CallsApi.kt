package com.max.core.calls

import com.max.core.api.MalformedReplyException
import com.max.core.api.asLong
import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode
import com.max.core.session.SessionMachine

/**
 * Call control-plane over a [RequestSink] (a logged-in `SessionMachine` or `MaxTransport`).
 *
 * [history] reads the call log (`VIDEO_CHAT_HISTORY` 79). The request and reply shapes follow
 * KometTeam/Komet (`CallsModule.fetchHistory`): the request is an empty map, the reply is
 * `{history: [{message: {id, time, sender, attaches: [{_type: "CALL", ...}]}}]}`.
 *
 * [requestCallsToken] is opcode `OK_TOKEN` 158 (PyMax `CALLS_TOKEN`).
 * kolibri and PyMax define the constant but have **no call site** and no payload builder
 * (`docs/protocol.md` K12). The empty request body and `{token, token_lifetime_ts,
 * token_refresh_ts}` reply are observed in third-party notes (`icyfalc0n/maxcalls`,
 * `pr0bel1230/max-api-docs`); they are not copied from those repos.
 *
 * `VIDEO_CHAT_START_ACTIVE` 78 and `VIDEO_CHAT_JOIN_BY_LINK` 166 stay out until a kolibri or
 * PyMax payload vector exists. Live signaling is ws2 (see [ConversationParams.ws2Url]); WebRTC
 * stays on the host.
 */
class CallsApi(private val sink: RequestSink) {
    constructor(session: SessionMachine) : this(RequestSink { opcode, payload -> session.request(opcode, payload) })

    /**
     * Requests a Calls-API token (`OK_TOKEN` 158) with an empty map.
     *
     * @throws MalformedReplyException if the OK reply is not a map or has no `token` string.
     * @throws com.max.core.transport.ServerErrorException for an ERROR reply.
     */
    suspend fun requestCallsToken(): CallsToken {
        val reply = sink.request(Opcode.OK_TOKEN, emptyMap<String, Any?>())
        val map = reply.payload as? Map<*, *>
            ?: throw MalformedReplyException(Opcode.OK_TOKEN, "payload is not a map", reply.payload)
        val token = map["token"] as? String
            ?: throw MalformedReplyException(Opcode.OK_TOKEN, "no token", map)
        if (token.isEmpty()) throw MalformedReplyException(Opcode.OK_TOKEN, "empty token", map)
        return CallsToken(
            token = token,
            tokenLifetimeTs = (map["token_lifetime_ts"] as? Number)?.toLong(),
            tokenRefreshTs = (map["token_refresh_ts"] as? Number)?.toLong(),
            raw = map,
        )
    }

    /**
     * The call log (`VIDEO_CHAT_HISTORY` 79), as the server orders it. Items without a message or
     * without a `CALL` attach are skipped.
     *
     * @throws MalformedReplyException if the OK reply is not a map.
     * @throws com.max.core.transport.ServerErrorException for an ERROR reply.
     */
    suspend fun history(): List<CallLogEntry> {
        val reply = sink.request(Opcode.VIDEO_CHAT_HISTORY, emptyMap<String, Any?>())
        val map = reply.payload as? Map<*, *>
            ?: throw MalformedReplyException(Opcode.VIDEO_CHAT_HISTORY, "payload is not a map", reply.payload)
        return (map["history"] as? List<*>).orEmpty().mapNotNull(CallLogEntry::from)
    }
}

/**
 * One call of the call log: the message that carries a `CALL` attach.
 *
 * @property senderId who started the call (the message sender).
 * @property contactIds the `contactIds` of the attach: the called users of an outgoing call.
 * @property hangupType `hangupType` of the attach (`HUNGUP`, `CANCELED`, `REJECTED`, `MISSED`, ...).
 * @property duration call length (0 when nobody answered).
 * @property callType `callType` of the attach (`AUDIO` / `VIDEO`), `null` if absent.
 */
data class CallLogEntry(
    val messageId: Long,
    val chatId: Long?,
    val time: Long,
    val senderId: Long,
    val contactIds: List<Long>,
    val hangupType: String?,
    val duration: Long,
    val callType: String?,
    val raw: Map<*, *>,
) {
    val isVideo: Boolean get() = callType == "VIDEO"

    /** The other side for a 1:1 call seen by [me]; `null` for a group call or when unknown. */
    fun peerId(me: Long?): Long? =
        if (me != null && senderId == me) contactIds.firstOrNull { it != me } else senderId.takeIf { it != 0L }

    /** Missed for [me]: an incoming call that was not answered. */
    fun isMissed(me: Long?): Boolean =
        !(me != null && senderId == me) && (duration == 0L || hangupType in MISSED_HANGUPS)

    companion object {
        private val MISSED_HANGUPS = setOf("CANCELED", "REJECTED", "MISSED")

        /** Parses one `history` item; `null` without a message id or a `CALL` attach. */
        fun from(item: Any?): CallLogEntry? {
            val entry = item as? Map<*, *> ?: return null
            val message = entry["message"] as? Map<*, *> ?: return null
            val attach = (message["attaches"] as? List<*>).orEmpty()
                .firstOrNull { (it as? Map<*, *>)?.get("_type") == "CALL" } as? Map<*, *> ?: return null
            return CallLogEntry(
                messageId = message["id"].asLong() ?: return null,
                chatId = (entry["chatId"] ?: message["chatId"]).asLong(),
                time = message["time"].asLong() ?: 0L,
                senderId = message["sender"].asLong() ?: 0L,
                contactIds = (attach["contactIds"] as? List<*>).orEmpty().mapNotNull { it.asLong() },
                hangupType = attach["hangupType"] as? String,
                duration = attach["duration"].asLong() ?: 0L,
                callType = attach["callType"] as? String,
                raw = entry,
            )
        }
    }
}

/** Parsed `OK_TOKEN` 158 reply. Extra fields stay in [raw]. */
data class CallsToken(
    val token: String,
    val tokenLifetimeTs: Long?,
    val tokenRefreshTs: Long?,
    val raw: Map<*, *>,
)
