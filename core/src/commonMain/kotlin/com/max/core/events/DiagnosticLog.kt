package com.max.core.events

import com.max.core.protocol.Opcode

/**
 * Diagnostics lines for raw pushes whose payload is still unverified: `NOTIF_DRAFT` 152 and
 * `NOTIF_DRAFT_DISCARD` 153. A line keeps the payload's shape (every key, ids, times, numbers,
 * booleans, element `type` / attach `_type` and other enum-like values) but no user content:
 * - `text` keeps only its length and at most the first [TEXT_PREFIX_CHARS] characters
 *   (`"пр…(len=12)"`);
 * - any other free string keeps only its length (`"<str len=8>"`), unless it is enum-like
 *   ([KEEP_STRING_KEYS]), an id or a time (the key ends with `id` / `ids` / `time`, any case)
 *   or a decimal number;
 * - byte arrays become `"<bytes N>"`.
 * A line is capped at [MAX_ENTRY_CHARS] characters.
 */
object DiagnosticLog {
    /** Longest diagnostics line; a longer one is cut and ends with `…(+N)`. */
    const val MAX_ENTRY_CHARS: Int = 2000

    /** Characters of a draft `text` kept in a line. */
    const val TEXT_PREFIX_CHARS: Int = 2

    /** Keys whose string values are kept (enum-like values, never user text). */
    val KEEP_STRING_KEYS: Set<String> = setOf("type", "_type", "status", "action", "attachType", "linkType", "chatType", "kind")

    private const val MAX_DEPTH = 8
    private const val MAX_LIST_ITEMS = 50
    private const val MAX_KEPT_STRING = 64
    private val TEXT_KEYS = setOf("text")
    private val NUMBER = Regex("-?\\d+")

    /** Whether [event] is one of the logged pushes (152 / 153), parsed or [MaxEvent.Unknown]. */
    fun isDraftPush(event: MaxEvent): Boolean =
        event.opcode == Opcode.NOTIF_DRAFT.value || event.opcode == Opcode.NOTIF_DRAFT_DISCARD.value

    /**
     * One line for a 152 / 153 push: `push 152 -> DraftSaved | {"chatId":-70,"draft":{...}}`.
     * The part after `->` is how the core read it (`DraftSaved`, `DraftDiscarded` or `Unknown`).
     */
    fun draftPush(event: MaxEvent): String {
        val outcome = event::class.simpleName ?: "?"
        return cap("push ${event.opcode} -> $outcome | ${redact(event.raw)}")
    }

    /** [value] rendered JSON-like with the redaction rules above. */
    fun redact(value: Any?): String = StringBuilder().also { render(it, value, null, 0) }.toString()

    /** [line] cut to [MAX_ENTRY_CHARS]. */
    fun cap(line: String): String {
        if (line.length <= MAX_ENTRY_CHARS) return line
        val suffix = "…(+${line.length - MAX_ENTRY_CHARS})"
        var end = MAX_ENTRY_CHARS - suffix.length
        if (end > 0 && line[end - 1].isHighSurrogate()) end--
        return line.substring(0, end) + suffix
    }

    private fun render(out: StringBuilder, value: Any?, key: String?, depth: Int) {
        if (out.length > MAX_ENTRY_CHARS) return
        when (value) {
            null -> out.append("null")
            is Boolean, is Number -> out.append(value.toString())
            is String -> out.append(quote(string(value, key)))
            is ByteArray -> out.append(quote("<bytes ${value.size}>"))
            is Map<*, *> -> {
                if (depth >= MAX_DEPTH) { out.append(quote("<map ${value.size}>")); return }
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    val name = k.toString()
                    out.append(quote(name)).append(':')
                    render(out, v, name, depth + 1)
                }
                out.append('}')
            }
            is Collection<*> -> {
                if (depth >= MAX_DEPTH) { out.append(quote("<list ${value.size}>")); return }
                out.append('[')
                value.take(MAX_LIST_ITEMS).forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    // list items inherit the key: a list of ids stays ids, of texts stays texts
                    render(out, v, key, depth + 1)
                }
                if (value.size > MAX_LIST_ITEMS) out.append(",").append(quote("…+${value.size - MAX_LIST_ITEMS}"))
                out.append(']')
            }
            else -> out.append(quote("<${value::class.simpleName ?: "value"}>"))
        }
    }

    private fun string(value: String, key: String?): String {
        val k = key?.lowercase()
        return when {
            key != null && key in TEXT_KEYS -> textPreview(value)
            key != null && (key in KEEP_STRING_KEYS || k!!.endsWith("id") || k.endsWith("ids") || k.endsWith("time")) ->
                if (value.length <= MAX_KEPT_STRING) value else "<str len=${value.length}>"
            NUMBER.matches(value) && value.length <= MAX_KEPT_STRING -> value
            else -> "<str len=${value.length}>"
        }
    }

    private fun textPreview(text: String): String {
        var n = minOf(TEXT_PREFIX_CHARS, text.length)
        if (n > 0 && text[n - 1].isHighSurrogate()) n--
        val more = if (n < text.length) "…" else ""
        return "${text.substring(0, n)}$more(len=${text.length})"
    }

    private fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append("\\u").append(c.code.toString(16).padStart(4, '0')) else b.append(c)
        }
        return b.append('"').toString()
    }
}
