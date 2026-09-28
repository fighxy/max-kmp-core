package com.max.core.calls

import com.max.core.api.MalformedReplyException
import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode
import com.max.core.session.SessionMachine

/**
 * Call control-plane over a [RequestSink] (a logged-in `SessionMachine` or `MaxTransport`).
 *
 * Only [requestCallsToken] is implemented: opcode `OK_TOKEN` 158 (PyMax `CALLS_TOKEN`).
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
}

/** Parsed `OK_TOKEN` 158 reply. Extra fields stay in [raw]. */
data class CallsToken(
    val token: String,
    val tokenLifetimeTs: Long?,
    val tokenRefreshTs: Long?,
    val raw: Map<*, *>,
)
