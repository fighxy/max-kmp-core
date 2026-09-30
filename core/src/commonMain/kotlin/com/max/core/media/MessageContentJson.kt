package com.max.core.media

import com.max.core.api.MaxMessage
import com.max.core.api.ReactionInfo
import com.max.core.protocol.MsgPackExt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fragment the app stores beside the message text: attachments, text formatting (`elements`),
 * a reply link, reactions and comment counters. Empty when the message has none of those, so a later text-only echo does
 * not wipe a fragment the app already kept.
 *
 * Attachment maps are copied as decoded. Fields this module does not model (an audio wave, a
 * file address) stay in the JSON. A missing address is left missing; nothing is built from a token.
 */
fun messageContentJson(message: MaxMessage, senderName: (Long) -> String? = { null }): String {
    val body = (message.raw["message"] as? Map<*, *>) ?: message.raw
    val commentsCount = body["commentsCount"]
    val commentsInfo = body["commentsInfo"]
    if (message.attaches.isEmpty() && message.link == null && message.reactionInfo == null
        && commentsCount == null && commentsInfo == null && message.elements.isEmpty() && message.status != "EDITED"
    ) {
        return ""
    }
    val fields = linkedMapOf<String, JsonElement>()
    if (message.attaches.isNotEmpty()) fields["attaches"] = toJsonElement(message.attaches)
    if (message.status == "EDITED") fields["edited"] = JsonPrimitive(true)
    // Formatting of the text: `{type, from, length, attributes?}` with UTF-16 offsets.
    if (message.elements.isNotEmpty()) fields["elements"] = toJsonElement(message.elements)
    message.link?.let { fields["link"] = toJsonElement(withSenderName(it, senderName)) }
    message.reactionInfo?.let { fields["reactionInfo"] = toJsonElement(it.raw) }
    if (commentsCount != null) fields["commentsCount"] = toJsonElement(commentsCount)
    if (commentsInfo != null) fields["commentsInfo"] = toJsonElement(commentsInfo)
    return JsonObject(fields).toString()
}

/**
 * Reactions of one message for the app, apart from [messageContentJson]:
 * `{"counters": [{"reaction", "count"}], "totalCount", "yourReaction"}`.
 *
 * `null` [info] is "no reactions" (empty counters). With [mineKnown] `false` the key
 * `yourReaction` is left out, so the app keeps the own reaction it already has (a
 * `NOTIF_MSG_REACTIONS_CHANGED` push has only counters); with `true` it is always written,
 * JSON `null` when there is none. Counters below 1 are dropped.
 */
fun reactionsJson(info: ReactionInfo?, mineKnown: Boolean = true): String {
    val counters = info?.counters.orEmpty().filter { it.count > 0 && it.reaction.isNotEmpty() }
    val fields = linkedMapOf<String, JsonElement>(
        "counters" to JsonArray(
            counters.map { JsonObject(linkedMapOf("reaction" to JsonPrimitive(it.reaction), "count" to JsonPrimitive(it.count))) },
        ),
        "totalCount" to JsonPrimitive(if (counters.isEmpty()) 0 else maxOf(info?.totalCount ?: 0, counters.sumOf { it.count })),
    )
    if (mineKnown) {
        val mine = info?.yourReaction?.takeIf { own -> counters.any { it.reaction == own } }
        fields["yourReaction"] = mine?.let(::JsonPrimitive) ?: JsonNull
    }
    return JsonObject(fields).toString()
}

/**
 * The quoted message of a link carries only the `sender` id. [senderName] adds its display name
 * as `senderName`, so the app can title the quote; unknown senders stay without one.
 */
private fun withSenderName(link: Map<*, *>, senderName: (Long) -> String?): Map<*, *> {
    val quoted = link["message"] as? Map<*, *> ?: return link
    if (quoted["senderName"] != null) return link
    val id = (quoted["sender"] as? Number)?.toLong() ?: return link
    val name = senderName(id)?.takeIf { it.isNotBlank() } ?: return link
    val copy = LinkedHashMap<Any?, Any?>(link)
    copy["message"] = LinkedHashMap<Any?, Any?>(quoted).apply { put("senderName", name) }
    return copy
}

private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is ByteArray -> JsonArray(value.map { JsonPrimitive(it.toInt() and 0xFF) })
    is ULong -> if (value <= Long.MAX_VALUE.toULong()) JsonPrimitive(value.toLong()) else JsonPrimitive(value.toString())
    is UInt -> JsonPrimitive(value.toLong())
    is UShort -> JsonPrimitive(value.toLong())
    is UByte -> JsonPrimitive(value.toLong())
    is Number -> number(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) })
    is List<*> -> JsonArray(value.map { toJsonElement(it) })
    is Array<*> -> JsonArray(value.map { toJsonElement(it) })
    is MsgPackExt -> JsonNull
    else -> JsonPrimitive(value.toString())
}

private fun number(value: Number): JsonPrimitive {
    val asLong = value.toLong()
    val asDouble = value.toDouble()
    return if (asDouble.isFinite() && asDouble == asLong.toDouble()) JsonPrimitive(asLong) else JsonPrimitive(asDouble)
}
