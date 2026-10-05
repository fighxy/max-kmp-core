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
     * Bot card (`BOT_INFO` 145, `{botId}`) as KometTeam/Komet reads it: reply `{commands:
     * [{name, description?}], contact}`. Commands without a name are dropped; a missing list is
     * empty.
     */
    suspend fun getBotInfo(botId: Long): BotInfo {
        val map = replyMap(sink.request(Opcode.BOT_INFO, linkedMapOf("botId" to botId)), Opcode.BOT_INFO)
        val commands = (map["commands"] as? List<*>).orEmpty().mapNotNull { item ->
            val c = item as? Map<*, *> ?: return@mapNotNull null
            val name = c["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) null else BotCommand(name, (c["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() })
        }
        return BotInfo(botId, commands, MaxUser.from(map["contact"]), map)
    }

    /**
     * Mini-app launch data (`WEB_APP_INIT_DATA` 160, `{botId, chatId?, startParam?}`); reply
     * `{url, queryId?}`. PyMax names the id `queryId`, the server Komet talks to `query_id`, and it
     * is not always sent, so only `url` is required ([WebAppInitData.queryId] may be `null`).
     */
    suspend fun getWebAppInitData(botId: Long, chatId: Long? = null, startParam: String? = null): WebAppInitData {
        val payload = linkedMapOf<String, Any?>("botId" to botId)
        if (chatId != null) payload["chatId"] = chatId
        if (startParam != null) payload["startParam"] = startParam
        val map = replyMap(sink.request(Opcode.WEB_APP_INIT_DATA, payload), Opcode.WEB_APP_INIT_DATA)
        val queryId = (map["queryId"] ?: map["query_id"])?.let { it as? String ?: it.asLong()?.toString() }
        val url = (map["url"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw MalformedReplyException(Opcode.WEB_APP_INIT_DATA, "no url", map)
        return WebAppInitData(queryId, url, map)
    }

    /**
     * Finishes an external step of a mini app (`EXTERNAL_CALLBACK` 105, `{url}`), e.g. the return
     * from the Gosuslugi login of the Digital ID app to a URL with `externalCallback=1` (as in
     * Komet). The reply names the mini app to relaunch: `botId`/`bot_id` and optional
     * `startParam`/`start_param`, at the top level or inside `data`, `result` or `response`.
     */
    suspend fun externalCallback(url: String): ExternalCallbackResult {
        require(url.isNotBlank()) { "url is blank" }
        val map = replyMap(sink.request(Opcode.EXTERNAL_CALLBACK, linkedMapOf("url" to url)), Opcode.EXTERNAL_CALLBACK)
        return ExternalCallbackResult.from(map) ?: throw MalformedReplyException(Opcode.EXTERNAL_CALLBACK, "no botId", map)
    }

    /**
     * Presses a `CALLBACK` button of an inline keyboard (`MSG_SEND_CALLBACK` 118) the way
     * KometTeam/Komet sends it (`MessagesModule.sendButtonCallback`, schema only): `{chatId,
     * messageId, callbackId, payload?}`. [callbackId] is the keyboard attach's `callbackId`,
     * [payload] the button's `payload`. See [ButtonAnswer] for the reply.
     */
    suspend fun pressButton(chatId: Long, messageId: Long, callbackId: String, payload: String? = null): ButtonAnswer {
        require(callbackId.isNotBlank()) { "callbackId is blank" }
        val body = linkedMapOf<String, Any?>("chatId" to chatId, "messageId" to messageId, "callbackId" to callbackId)
        if (payload != null) body["payload"] = payload
        val map = replyMap(sink.request(Opcode.MSG_SEND_CALLBACK, body), Opcode.MSG_SEND_CALLBACK)
        return ButtonAnswer(
            text = (map["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            url = (map["url"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            raw = map,
        )
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

/**
 * [BotsApi.pressButton] reply. Komet reads two optional fields: `text` (a short answer the client
 * shows as a notice) and `url` (a page to open). The bot's real answer usually arrives as a new
 * or edited message push; both fields are `null` then.
 */
data class ButtonAnswer(val text: String?, val url: String?, val raw: Map<*, *>)

/**
 * Contact or chat options that mark a bot with a mini app (the "Open app" button), as Komet checks
 * them (`kMiniAppOptions`): `HAS_WEBAPP`, `HAS_WEB_APP`, `WEBAPP`.
 */
val WEB_APP_OPTIONS: Set<String> = setOf("HAS_WEBAPP", "HAS_WEB_APP", "WEBAPP")

/** The bot behind these options opens a mini app ([WEB_APP_OPTIONS]). */
fun hasWebApp(options: Collection<String>): Boolean = options.any { it.uppercase() in WEB_APP_OPTIONS }

/** One `/command` of a bot menu. */
data class BotCommand(val name: String, val description: String?)

/** [BotsApi.getBotInfo] reply: the menu and the bot's contact card (description, link). */
data class BotInfo(val botId: Long, val commands: List<BotCommand>, val contact: MaxUser?, val raw: Map<*, *>)

/** Mini-app launch data: the `url` to open (it carries the launch parameters) and the optional query id. */
data class WebAppInitData(val queryId: String?, val url: String, val raw: Map<*, *>)

/** [BotsApi.externalCallback] reply: the mini app to relaunch and its start parameter. */
data class ExternalCallbackResult(val botId: Long, val startParam: String?, val raw: Map<*, *>) {
    companion object {
        fun from(map: Map<*, *>): ExternalCallbackResult? {
            val candidates = listOf<Map<*, *>?>(map, map["data"] as? Map<*, *>, map["result"] as? Map<*, *>, map["response"] as? Map<*, *>)
            for (m in candidates.filterNotNull()) {
                val botId = (m["botId"] ?: m["bot_id"]).asLong() ?: continue
                val start = (m["startParam"] ?: m["start_param"])?.toString()?.takeIf { it.isNotBlank() }
                return ExternalCallbackResult(botId, start, map)
            }
            return null
        }
    }
}

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
