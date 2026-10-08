package com.max.core.api

/**
 * `type` values of message text formatting (`elements` of a message). The names are the wire
 * strings used by KometTeam/Komet (`core/utils/text_format.dart`: `STRONG`, `EMPHASIZED`,
 * `UNDERLINE`, `STRIKETHROUGH`, `MONOSPACED`, `HEADING`, `QUOTE`, `LINK`, `ANIMOJI`,
 * `USER_MENTION`) and PyMax (`formatting/markdown.py`, which also emits `CODE` for a ``` block).
 */
object TextElementType {
    /** Bold. */
    const val STRONG = "STRONG"

    /** Italic. */
    const val EMPHASIZED = "EMPHASIZED"
    const val UNDERLINE = "UNDERLINE"
    const val STRIKETHROUGH = "STRIKETHROUGH"

    /** Inline monospace (`code`). */
    const val MONOSPACED = "MONOSPACED"

    /** A code block: received from some clients, read as [MONOSPACED] ([TextElement.parse]); not sent. */
    const val CODE = "CODE"
    const val HEADING = "HEADING"
    const val QUOTE = "QUOTE"

    /** A link over the text; the target is `attributes.url`. */
    const val LINK = "LINK"

    /** A mention of user `entityId` (Komet may add `entityName`). */
    const val USER_MENTION = "USER_MENTION"

    /** An animated emoji `entityId` with `attributes.animojiLottieUrl`. */
    const val ANIMOJI = "ANIMOJI"

    val all: List<String> = listOf(STRONG, EMPHASIZED, UNDERLINE, STRIKETHROUGH, MONOSPACED, CODE, HEADING, QUOTE, LINK, USER_MENTION, ANIMOJI)
}

/**
 * One formatting element of a message text (PyMax `Element`, Komet `FormatRange`):
 * `{type, from, length, entityId?, entityName?, attributes?}`. [from] and [length] are UTF-16
 * code units of the text (Kotlin `String` indexes), as both references count them.
 *
 * The same shape goes out with `MSG_SEND` 64 / `MSG_EDIT` 67 ([toPayload]) and comes back in
 * every message ([MaxMessage.textElements]). Elements may overlap (bold inside a quote).
 *
 * @property type a [TextElementType] value; an unknown type from the server is kept as is.
 * @property entityId the user of a `USER_MENTION`, the animoji of an `ANIMOJI`.
 * @property attributes `attributes` as sent (`url` of a `LINK`, `animojiLottieUrl` of an `ANIMOJI`).
 * @property extra every other key of an element of an unknown type, as received; sent back
 *   unchanged after the known keys ([toPayload]), so an edit keeps markup of other clients
 *   (shared Orbitle rule, `formatting/README.md`). Always empty for a known type.
 */
data class TextElement(
    val type: String,
    val from: Int,
    val length: Int,
    val entityId: Long? = null,
    val entityName: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** End offset (exclusive). */
    val end: Int get() = from + length

    /** Target of a `LINK` (`attributes.url`). */
    val url: String? get() = (attributes["url"] as? String)?.takeIf { it.isNotEmpty() }

    /** Lottie address of an `ANIMOJI` (`attributes.animojiLottieUrl`). */
    val animojiLottieUrl: String? get() = (attributes["animojiLottieUrl"] as? String)?.takeIf { it.isNotEmpty() }

    /** `true` when the element lies inside a text of [textLength] UTF-16 units and is not empty. */
    fun fits(textLength: Int): Boolean = from >= 0 && length > 0 && end <= textLength

    /**
     * Wire form, in Komet's key order: `{type, from, length}`, then `entityId`, `entityName` and
     * `attributes` only when set.
     */
    fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>("type" to type, "from" to from, "length" to length).apply {
        if (entityId != null) put("entityId", entityId)
        if (!entityName.isNullOrEmpty()) put("entityName", entityName)
        if (attributes.isNotEmpty()) put("attributes", attributes)
        for ((k, v) in extra) if (k !in RESERVED_KEYS) put(k, v)
    }

    companion object {
        private val RESERVED_KEYS = setOf("type", "from", "length", "entityId", "entityName", "attributes")

        fun strong(from: Int, length: Int) = TextElement(TextElementType.STRONG, from, length)
        fun emphasized(from: Int, length: Int) = TextElement(TextElementType.EMPHASIZED, from, length)
        fun underline(from: Int, length: Int) = TextElement(TextElementType.UNDERLINE, from, length)
        fun strikethrough(from: Int, length: Int) = TextElement(TextElementType.STRIKETHROUGH, from, length)
        fun monospaced(from: Int, length: Int) = TextElement(TextElementType.MONOSPACED, from, length)
        fun heading(from: Int, length: Int) = TextElement(TextElementType.HEADING, from, length)
        fun quote(from: Int, length: Int) = TextElement(TextElementType.QUOTE, from, length)

        /** `LINK` with `attributes: {url}` (PyMax `ElementAttributes.url`, Komet `sendLinkMessage`). */
        fun link(from: Int, length: Int, url: String) = TextElement(TextElementType.LINK, from, length, attributes = mapOf("url" to url))

        /** `USER_MENTION` of [userId] (`entityId`). */
        fun mention(from: Int, length: Int, userId: Long, name: String? = null) =
            TextElement(TextElementType.USER_MENTION, from, length, entityId = userId, entityName = name)

        /** `ANIMOJI` [animojiId] with its Lottie address (Komet `RichMessageController`). */
        fun animoji(from: Int, length: Int, animojiId: Long, lottieUrl: String) =
            TextElement(TextElementType.ANIMOJI, from, length, entityId = animojiId, attributes = mapOf("animojiLottieUrl" to lottieUrl))

        /**
         * One received element, read as the MAX web client reads it and as the shared Orbitle
         * rule (`formatting/parse-*.json`) says: a missing `from` is `0`, a missing `length` runs
         * to the end of the text ([textLength]; without it such an element is dropped), a zero
         * or negative length is dropped. With [textLength] an element starting at or after the
         * end of the text is dropped and a tail past the end is cut. A known type matches in any
         * case (`strong` is [TextElementType.STRONG]); `CODE` reads as
         * [TextElementType.MONOSPACED]; a `LINK` without a non-empty `attributes.url` is dropped.
         * An unknown type is kept with its spelling and every other key ([extra]). `null` when
         * it is not a map or has no non-blank `type`. `from` / `length` may arrive as numbers or
         * decimal strings.
         */
        fun parse(raw: Any?, textLength: Int? = null): TextElement? {
            val m = raw as? Map<*, *> ?: return null
            val sent = (m["type"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val known = TextElementType.all.firstOrNull { it.equals(sent, ignoreCase = true) }
            val type = when (known) {
                null -> sent
                TextElementType.CODE -> TextElementType.MONOSPACED
                else -> known
            }
            val from = m["from"].asLong()?.toInt() ?: 0
            if (from < 0) return null
            if (textLength != null && from >= textLength) return null
            var length = (if (m["length"] == null && textLength != null) textLength - from else m["length"].asLong()?.toInt())
                ?.takeIf { it > 0 } ?: return null
            if (textLength != null && from + length > textLength) length = textLength - from
            val attributes = (m["attributes"] as? Map<*, *>)?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
                ?.toMap()
                .orEmpty()
            if (type == TextElementType.LINK && (attributes["url"] as? String).isNullOrEmpty()) return null
            val extra = if (known != null) emptyMap() else m.entries
                .mapNotNull { (k, v) -> (k as? String)?.takeIf { it !in RESERVED_KEYS }?.let { it to v } }
                .toMap()
            return TextElement(
                type = type,
                from = from,
                length = length,
                entityId = m["entityId"].asLong(),
                entityName = (m["entityName"] as? String)?.takeIf { it.isNotEmpty() },
                attributes = attributes,
                extra = extra,
            )
        }

        /** Every valid element of [raw] in order ([parse]); a non-list is empty. */
        fun parseAll(raw: Any?, textLength: Int? = null): List<TextElement> =
            (raw as? List<*>).orEmpty().mapNotNull { parse(it, textLength) }

        /** [elements] that fit [text] ([fits]), as wire maps for `MSG_SEND` / `MSG_EDIT`. */
        fun payloadFor(text: String, elements: List<TextElement>): List<Map<String, Any?>> =
            elements.filter { it.fits(text.length) }.map { it.toPayload() }
    }
}
