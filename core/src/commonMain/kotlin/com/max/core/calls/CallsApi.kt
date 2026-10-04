package com.max.core.calls

import com.max.core.api.MalformedReplyException
import com.max.core.api.asLong
import com.max.core.api.randomUuid
import com.max.core.api.rawMap
import com.max.core.api.replyMap
import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode
import com.max.core.session.SessionMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
 * [initiateCall], [createConference], [createJoinLink] and [joinByLink] follow KometTeam/Komet
 * `CallsModule` (feature/FullStack). [internalParams] is the JSON string that module sends.
 * The reply carries a signaling endpoint inside a JSON string; this class does not open a
 * socket. Live media is ws2 (see [ConversationParams.ws2Url]); WebRTC stays on the host.
 */
class CallsApi(
    private val sink: RequestSink,
    private val newConversationId: () -> String = ::randomUuid,
) {
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

    /**
     * Starts a 1:1 call (`VIDEO_CHAT_START_ACTIVE` 78, Komet `initiateCall`):
     * `{conversationId, calleeIds: [calleeId], internalParams, isVideo}`.
     * The endpoint is read from the JSON string `internalCallerParams`.
     *
     * @throws MalformedReplyException when the reply has no endpoint.
     */
    suspend fun initiateCall(calleeId: Long, isVideo: Boolean = false, deviceId: String = ""): CallSignaling {
        val conversationId = newConversationId()
        val payload = linkedMapOf<String, Any?>(
            "conversationId" to conversationId,
            "calleeIds" to listOf(calleeId),
            "internalParams" to internalParams(deviceId),
            "isVideo" to isVideo,
        )
        val map = replyMap(sink.request(Opcode.VIDEO_CHAT_START_ACTIVE, payload), Opcode.VIDEO_CHAT_START_ACTIVE)
        val endpoint = callerEndpoint(map, listOf("internalCallerParams"), Opcode.VIDEO_CHAT_START_ACTIVE)
        return CallSignaling(
            conversationId = (map["conversationId"] as? String)?.takeIf { it.isNotEmpty() } ?: conversationId,
            endpoint = endpoint.endpoint,
            callsUserId = endpoint.callsUserId,
            peerExternalId = endpoint.external ?: calleeId,
            isVideo = isVideo,
        )
    }

    /**
     * Starts a conference (`VIDEO_CHAT_START` 76, Komet `createConference`): `{conversationId}`.
     * Uses the reply `joinLink`, or asks [createJoinLink] when the reply has none.
     *
     * @throws MalformedReplyException when neither reply contains a link.
     */
    suspend fun createConference(): CreatedConference {
        val conversationId = newConversationId()
        val map = replyMap(sink.request(Opcode.VIDEO_CHAT_START, linkedMapOf("conversationId" to conversationId)), Opcode.VIDEO_CHAT_START)
        val id = (map["conversationId"] as? String)?.takeIf { it.isNotEmpty() } ?: conversationId
        val link = (map["joinLink"] as? String)?.takeIf { it.isNotEmpty() } ?: createJoinLink(id)
            ?: throw MalformedReplyException(Opcode.VIDEO_CHAT_START, "no joinLink", map)
        val name = (map["callName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return CreatedConference(id, link, name, map["chatId"].asLong())
    }

    /** A join link (`VIDEO_CHAT_CREATE_JOIN_LINK` 84, `{conversationId}`); `null` when absent. */
    suspend fun createJoinLink(conversationId: String): String? {
        if (conversationId.isEmpty()) return null
        val map = rawMap(sink.request(Opcode.VIDEO_CHAT_CREATE_JOIN_LINK, linkedMapOf("conversationId" to conversationId)))
        return (map["joinLink"] as? String)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Joins by link (`VIDEO_CHAT_JOIN_BY_LINK` 166, Komet `joinByLink`):
     * `{joinLink, internalParams, isVideo}`. The endpoint is read from `internalParams`, then
     * `internalCallerParams`.
     */
    suspend fun joinByLink(joinLink: String, isVideo: Boolean = false, deviceId: String = ""): CallSignaling {
        require(joinLink.isNotEmpty()) { "joinLink is empty" }
        val payload = linkedMapOf<String, Any?>(
            "joinLink" to joinLink,
            "internalParams" to internalParams(deviceId),
            "isVideo" to isVideo,
        )
        val map = replyMap(sink.request(Opcode.VIDEO_CHAT_JOIN_BY_LINK, payload), Opcode.VIDEO_CHAT_JOIN_BY_LINK)
        val endpoint = callerEndpoint(map, listOf("internalParams", "internalCallerParams"), Opcode.VIDEO_CHAT_JOIN_BY_LINK)
        return CallSignaling(
            conversationId = (map["conversationId"] as? String).orEmpty(),
            endpoint = endpoint.endpoint,
            callsUserId = endpoint.callsUserId,
            peerExternalId = endpoint.external ?: 0L,
            isVideo = isVideo,
        )
    }

    private fun callerEndpoint(payload: Map<*, *>, keys: List<String>, opcode: Opcode): CallerEndpoint {
        for (key in keys) {
            val raw = payload[key] as? String ?: continue
            val found = runCatching {
                val parsed = Json.parseToJsonElement(raw).jsonObject
                val endpoint = parsed["endpoint"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@runCatching null
                val id = parsed["id"]?.let { runCatching { it.jsonObject }.getOrNull() }
                CallerEndpoint(endpoint, jsonLong(id?.get("internal")) ?: 0L, jsonLong(id?.get("external")))
            }.getOrNull() ?: continue
            return found
        }
        throw MalformedReplyException(opcode, "no endpoint", payload)
    }

    private fun jsonLong(element: kotlinx.serialization.json.JsonElement?): Long? {
        val primitive = element?.jsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    companion object {
        /** Komet `Ws2Config.defaultCapabilities`, sent as `hexCapability` inside [internalParams]. */
        const val HEX_CAPABILITY: String = "3c02f"

        /**
         * JSON string Komet `_internalParams` sends. The server rejects other SDK values.
         * Key order matches that object. [deviceId] is the session device id, or empty.
         */
        fun internalParams(deviceId: String): String = JsonObject(
            linkedMapOf(
                "platform" to JsonPrimitive("ANDROID"),
                "sdkVersion" to JsonPrimitive("0.2.1.3"),
                "clientAppKey" to JsonPrimitive("CGPGAGLGDIHBABABA"),
                "deviceId" to JsonPrimitive(deviceId),
                "protocolVersion" to JsonPrimitive(5),
                "onlyAdminCanRecord" to JsonPrimitive(false),
                "isWaitForAdminEnabled" to JsonPrimitive(false),
                "hexCapability" to JsonPrimitive(HEX_CAPABILITY),
            ),
        ).toString()
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

/**
 * Control-plane result of [CallsApi.initiateCall] or [CallsApi.joinByLink].
 * [endpoint] is the signaling address. This does not open audio or video.
 */
data class CallSignaling(
    val conversationId: String,
    val endpoint: String,
    val callsUserId: Long,
    val peerExternalId: Long,
    val isVideo: Boolean,
)

/** [CallsApi.createConference]: the server accepted a conference and returned a join link. */
data class CreatedConference(
    val conversationId: String,
    val joinLink: String,
    val callName: String?,
    val chatId: Long?,
)

private data class CallerEndpoint(val endpoint: String, val callsUserId: Long, val external: Long?)
