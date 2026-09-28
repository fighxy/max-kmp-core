package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.epochMillis
import com.max.core.protocol.Opcode

/**
 * Bot interactions over a [RequestSink], following PyMax `src/pymax/api/bots/service.py`
 * (`BotsService`) and `payloads.py`: web-app init data and inline-keyboard callbacks.
 */
class BotsApi(private val sink: RequestSink, private val clock: () -> Long = ::epochMillis) {
    /**
     * Mini-app launch data (`WEB_APP_INIT_DATA` 160, `{botId, chatId?, startParam?}`); reply
     * `{queryId, url}` (required, PyMax `InitData`).
     */
    suspend fun getWebAppInitData(botId: Long, chatId: Long? = null, startParam: String? = null): WebAppInitData {
        val payload = linkedMapOf<String, Any?>("botId" to botId)
        if (chatId != null) payload["chatId"] = chatId
        if (startParam != null) payload["startParam"] = startParam
        val map = replyMap(sink.request(Opcode.WEB_APP_INIT_DATA, payload), Opcode.WEB_APP_INIT_DATA)
        val queryId = map["queryId"]?.let { it as? String ?: it.asLong()?.toString() }
            ?: throw MalformedReplyException(Opcode.WEB_APP_INIT_DATA, "no queryId", map)
        val url = map["url"] as? String ?: throw MalformedReplyException(Opcode.WEB_APP_INIT_DATA, "no url", map)
        return WebAppInitData(queryId, url, map)
    }

    /**
     * Presses an inline-keyboard button (`MSG_SEND_CALLBACK` 118, PyMax `SendCallbackPayload`:
     * `{callbackId, type, payload?, timestamp}`); [callbackId] is the keyboard attach's
     * `callbackId`, [type] the button type (`CALLBACK`, ...). Reply `{success, unread, mark,
     * message?, chat?, chatAccessToken?}`.
     */
    suspend fun sendCallback(callbackId: String, type: String = "CALLBACK", payload: String? = null): CallbackResult {
        val body = linkedMapOf<String, Any?>("callbackId" to callbackId, "type" to type)
        if (payload != null) body["payload"] = payload
        body["timestamp"] = clock()
        val map = replyMap(sink.request(Opcode.MSG_SEND_CALLBACK, body), Opcode.MSG_SEND_CALLBACK)
        val chat = Chat.from(map["chat"])
        return CallbackResult(
            success = map["success"] == true,
            unread = map["unread"].asLong()?.toInt() ?: 0,
            mark = map["mark"].asLong() ?: 0,
            message = MaxMessage.from(map["message"], chat?.id),
            chat = chat,
            chatAccessToken = map["chatAccessToken"] as? String,
            raw = map,
        )
    }
}

/** PyMax `InitData`: the web-app `queryId` and the `url` to open. */
data class WebAppInitData(val queryId: String, val url: String, val raw: Map<*, *>)

/** PyMax `CallbackResponse`. */
data class CallbackResult(
    val success: Boolean,
    val unread: Int,
    val mark: Long,
    val message: MaxMessage?,
    val chat: Chat?,
    val chatAccessToken: String?,
    val raw: Map<*, *>,
)
