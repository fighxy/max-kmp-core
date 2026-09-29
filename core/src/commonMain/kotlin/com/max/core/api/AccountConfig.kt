package com.max.core.api

/**
 * The account configuration of a `LOGIN` 19 reply (`config`), as KometTeam/Komet reads it (only
 * the facts are taken, no code):
 * - [user] (`config.user`): the user's settings, e.g. privacy (`HIDDEN`, `PHONE_NUMBER_PRIVACY`,
 *   `SEARCH_BY_PHONE`, `INCOMING_CALL`, `CHATS_INVITE`, `CONTENT_LEVEL_ACCESS`), safe mode
 *   (`SAFE_MODE`, `SAFE_MODE_NO_PIN`), `FAMILY_PROTECTION`, `INACTIVE_TTL` and the `PUSH_*` keys;
 * - [server] (`config.server`): server parameters, e.g. `invite-link` and the mini apps of the
 *   settings screen (`settings-entry-banners`);
 * - [hash] (`config.hash`): the fingerprint the next `LOGIN` sends as `configHash`.
 *
 * The server sends `config` only when the `LOGIN` `configHash` is unknown to it (e.g. the default
 * all-zero hash), so a client keeps the last one. `CONFIG` 22 replies carry the new `user` and
 * `hash` ([withUser]).
 */
data class AccountConfig(
    val user: Map<String, Any?> = emptyMap(),
    val server: Map<String, Any?> = emptyMap(),
    val hash: String? = null,
) {
    /** A string setting of [user] (numbers and booleans as text), `null` when absent. */
    fun userString(key: String): String? = when (val v = user[key]) {
        null -> null
        is String -> v
        else -> v.toString()
    }

    /** A boolean setting of [user]; `"true"`/`"false"`, `"ON"`/`"OFF"` and `1`/`0` count too. */
    fun userFlag(key: String): Boolean? = when (val v = user[key]) {
        is Boolean -> v
        is Number -> v.toLong() != 0L
        is String -> when (v.trim().uppercase()) {
            "TRUE", "ON", "1" -> true
            "FALSE", "OFF", "0" -> false
            else -> null
        }
        else -> null
    }

    /** `config.server["invite-link"]` as a full URL ([normalizeLink]), `null` when absent. */
    val inviteLink: String?
        get() = normalizeLink(server["invite-link"]?.toString())

    /**
     * The bot id of the mini app [app] from `settings-entry-banners`: a list of banners with
     * `items: [{appid, icon, title}]`. An item matches when its `icon` contains the app's icon
     * marker; only when no icon matches, the `title` is tried. [EntryApp.fallbackBotId] when the
     * config names no such app.
     */
    fun entryAppBotId(app: EntryApp): Long {
        val items = (server["settings-entry-banners"] as? List<*>).orEmpty()
            .flatMap { ((it as? Map<*, *>)?.get("items") as? List<*>).orEmpty() }
            .mapNotNull { it as? Map<*, *> }
        for ((field, marker) in listOf("icon" to app.iconMarker, "title" to app.titleMarker)) {
            val hit = items.firstOrNull { item ->
                item["appid"].asLong() != null && item[field]?.toString()?.lowercase()?.contains(marker) == true
            }
            hit?.get("appid").asLong()?.let { return it }
        }
        return app.fallbackBotId
    }

    /** This config with a `CONFIG` 22 reply applied: [newUser] replaces [user] when present, [newHash] the hash. */
    fun withUser(newUser: Map<String, Any?>?, newHash: String?): AccountConfig =
        copy(user = newUser ?: user, hash = newHash ?: hash)

    companion object {
        /** `config` of a `LOGIN` reply ([loginReply] is the whole reply map); `null` when it has none. */
        fun fromLoginReply(loginReply: Map<*, *>): AccountConfig? {
            val config = loginReply["config"] as? Map<*, *> ?: return null
            return AccountConfig(
                user = stringKeys(config["user"]),
                server = stringKeys(config["server"]),
                hash = config["hash"]?.let { it as? String ?: it.asLong()?.toString() },
            )
        }

        /**
         * A profile or invite link as a full URL: a value with `://` stays as it is, a short name
         * becomes `https://max.ru/<name>` (a leading `@` dropped). `null` for a blank value.
         */
        fun normalizeLink(link: String?): String? {
            val value = link?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if ("://" in value) return value
            val name = value.removePrefix("@").trimStart('/')
            if (name.isEmpty()) return null
            return if (name.startsWith("max.ru/")) "https://$name" else "https://max.ru/$name"
        }

        internal fun stringKeys(value: Any?): Map<String, Any?> {
            val map = value as? Map<*, *> ?: return emptyMap()
            val out = LinkedHashMap<String, Any?>()
            map.forEach { (k, v) -> if (k != null) out[k.toString()] = v }
            return out
        }
    }
}

/**
 * A built-in mini app of the settings screen. The bot ids are the ones Komet falls back to when the
 * server config names no app.
 */
enum class EntryApp(val iconMarker: String, val titleMarker: String, val fallbackBotId: Long) {
    SFERUM("sferum", "ферум", 2340831),
    DIGITAL_ID("digital", "цифровой", 8250447),
}
