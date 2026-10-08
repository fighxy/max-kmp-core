package com.max.core.api

/**
 * Names for `@` mentions, as the MAX web client derives them (bundle 2026-10, contact and chat
 * models): there is no separate field, the name is the path of the object's `link` URL without
 * the leading `/` (`https://max.ru/anya_p` -> `anya_p`). A link that is not an absolute URL, or
 * has an empty path, gives no name. A chat link whose path starts with `/join/` is an invite
 * link and gives none either. The path is returned as is (no percent-decoding, nothing cut
 * after a further `/`), like the web client.
 */
object MentionNames {
    /** The mention name of a user / contact from its `link`, or `null`. */
    fun ofUser(link: String?): String? = path(link)?.drop(1)?.takeIf { it.isNotEmpty() }

    /** The mention name of a group / channel from its `link` (none for `/join/…`), or `null`. */
    fun ofChat(link: String?): String? {
        val p = path(link) ?: return null
        if (p.startsWith("/join/")) return null
        return p.drop(1).takeIf { it.isNotEmpty() }
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /** The path of an absolute `scheme://host/path` URL (`/` when empty), or `null`. */
    private fun path(link: String?): String? {
        val s = link?.trim().orEmpty()
        val scheme = SCHEME.find(s) ?: return null
        val rest = s.substring(scheme.value.length)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        if (authorityEnd == 0) return null
        val tail = rest.substring(authorityEnd)
        val pathEnd = tail.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) tail.length else it }
        return tail.substring(0, pathEnd).ifEmpty { "/" }
    }
}

/**
 * Search among loaded group members (shared Orbitle rule, fixture `members/search.json`): a
 * case-insensitive substring of the full name or of the mention name, `ё` = `е`, the query
 * trimmed, an empty query matches everyone. A query starting with `@` searches only the
 * mention name (`"@"` alone matches everyone). The order of the list is kept.
 */
object MemberSearch {
    /** Whether a member with [name] and [mentionName] matches [query]. */
    fun matches(query: String, name: String?, mentionName: String?): Boolean {
        val q = query.trim()
        if (q.startsWith("@")) {
            val m = fold(q.drop(1))
            return m.isEmpty() || fold(mentionName.orEmpty()).contains(m)
        }
        val f = fold(q)
        return f.isEmpty() || fold(name.orEmpty()).contains(f) || fold(mentionName.orEmpty()).contains(f)
    }

    /** The members of [items] matching [query], in their order. */
    fun <T> filter(items: List<T>, query: String, name: (T) -> String?, mentionName: (T) -> String?): List<T> =
        items.filter { matches(query, name(it), mentionName(it)) }

    private fun fold(s: String): String = s.lowercase().replace('ё', 'е')
}
