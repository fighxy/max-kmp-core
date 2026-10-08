package com.max.core.api

import com.max.core.epochMillis

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
 *
 * A `config` is not always whole. The hash has several parts, and a reply to a known but stale
 * hash may leave sections out: Komet saves `user`, `server` and `chatFolders` each only when the
 * reply has it, and keeps a chat's mute when `config.chats` has no entry for the chat. Here such
 * a reply is an [AccountConfigUpdate]: [replacedBy] for the full snapshot (`LOGIN` with the
 * default hash), [mergedWith] for everything else (a `LOGIN` after a reconnect, `LOGIN2`, the
 * `NOTIF_CONFIG` 134 push). In both, a section the reply does not carry keeps its known value.
 */
data class AccountConfig(
    val user: Map<String, Any?> = emptyMap(),
    val server: Map<String, Any?> = emptyMap(),
    val hash: String? = null,
    /** `config.chats`: per-chat settings by chat id, e.g. `{"-123": {"dontDisturbUntil": -1}}`. */
    val chats: Map<String, Any?> = emptyMap(),
    /**
     * `true` when [chats] is the whole section of a full snapshot (a `LOGIN` sent with the default
     * `configHash` whose config carried `chats`), so a chat without an entry has the sound on.
     * `false` for a config that only holds some chats (built locally before any server config, or
     * from a reply without `chats`): a chat without an entry is unknown then ([chatMuteState]).
     */
    val chatsKnown: Boolean = false,
) {
    /**
     * `dontDisturbUntil` of [chatId]: `0` sound on, `-1` muted for good, else the end of the
     * mute in ms. `null` when the config says nothing about the chat.
     */
    fun dontDisturbUntil(chatId: Long): Long? =
        ((chats[chatId.toString()] as? Map<*, *>)?.get("dontDisturbUntil"))?.asLong()

    /** Whether [chatId] is muted at [nowMs] ([dontDisturbUntil]); `null` when unknown. */
    fun isMuted(chatId: Long, nowMs: Long): Boolean? {
        val until = dontDisturbUntil(chatId) ?: return null
        return until < 0 || until > nowMs
    }

    /** Whether [chatId] is muted now (device clock, [isMuted]); `null` when the config has no entry. */
    fun isMuted(chatId: Long): Boolean? = isMuted(chatId, epochMillis())

    /**
     * The mute of [chatId] at [nowMs] as an app should show it:
     * - the chat has an entry: `true` muted (`-1`, or an end time still ahead), `false` sound on
     *   (`0`, or a timed mute that ran out);
     * - no entry and [chatsKnown]: `false` (a full `chats` section leaves out chats with the sound on);
     * - no entry otherwise: `null`, unknown. Keep the value shown before, never read it as sound on.
     */
    fun chatMuteState(chatId: Long, nowMs: Long): Boolean? = isMuted(chatId, nowMs) ?: if (chatsKnown) false else null

    /** [chatMuteState] at the device clock. */
    fun chatMuteState(chatId: Long): Boolean? = chatMuteState(chatId, epochMillis())

    /** [dontDisturbUntil], or `0` for a chat without an entry when [chatsKnown]; `null` when unknown. */
    fun chatMuteUntil(chatId: Long): Long? = dontDisturbUntil(chatId) ?: if (chatsKnown) 0L else null

    /**
     * The full snapshot [update] (a `LOGIN` sent with the default `configHash`): each section it
     * carries replaces the known one, a section it leaves out (`null`) keeps its value. The
     * [hash] becomes the update's when it has one. A carried `chats` makes [chatsKnown] `true`.
     */
    fun replacedBy(update: AccountConfigUpdate): AccountConfig = AccountConfig(
        user = update.user ?: user,
        server = update.server ?: server,
        hash = update.hash ?: hash,
        chats = update.chats?.let(::dropNulls) ?: chats,
        chatsKnown = update.chats != null || chatsKnown,
    )

    /**
     * A partial [update] (a `LOGIN` after a reconnect, `LOGIN2`, `NOTIF_CONFIG` 134) on top of this
     * config:
     * - a missing section keeps its value;
     * - `user` and `server` are merged key by key (the update's keys win);
     * - `chats` is merged per chat id: a chat the update does not name keeps its entry, a named
     *   entry is merged field by field (so `{"dontDisturbUntil": 0}` turns the sound back on and
     *   keeps e.g. `favIndex`), and an entry `null` removes the chat's settings;
     * - the [hash] becomes the update's when it has one;
     * - [chatsKnown] stays as it was: a partial `chats` says nothing about the chats it leaves out.
     */
    fun mergedWith(update: AccountConfigUpdate): AccountConfig = AccountConfig(
        user = update.user?.let { user + it } ?: user,
        server = update.server?.let { server + it } ?: server,
        hash = update.hash ?: hash,
        chats = update.chats?.let { mergeChats(chats, it) } ?: chats,
        chatsKnown = chatsKnown,
    )

    /** This config with [chatId]'s `dontDisturbUntil` set to [until]. */
    fun withChatMute(chatId: Long, until: Long): AccountConfig {
        val key = chatId.toString()
        val entry = LinkedHashMap<String, Any?>()
        (chats[key] as? Map<*, *>)?.forEach { (k, v) -> if (k != null) entry[k.toString()] = v }
        entry["dontDisturbUntil"] = until
        return copy(chats = chats + (key to entry))
    }

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

    /**
     * `config.server["max-readmarks"]`: the largest group (by members) that shows who read a
     * message ([MessageReaders.isAvailable]). [DEFAULT_MAX_READMARKS] when the key is absent, not a
     * number or not positive.
     */
    val maxReadmarks: Int
        get() = server["max-readmarks"].asLong()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: DEFAULT_MAX_READMARKS

    /**
     * `config.server["presence-ttl"]` in seconds: how long a presence (above all "online") is
     * trusted without a refresh (MAX web client: its presence cache TTL). [DEFAULT_PRESENCE_TTL_S]
     * (300) when the key is absent, not a number or not positive.
     */
    val presenceTtlSeconds: Long
        get() = server["presence-ttl"].asLong()?.takeIf { it > 0 } ?: DEFAULT_PRESENCE_TTL_S

    /**
     * `config.server["edit-timeout"]` in seconds: how long an own message may be edited and
     * deleted for everyone. `0` when the key is absent, not a number or negative, as the MAX web
     * client's server config defaults have it (then an own message cannot be deleted for
     * everyone).
     */
    val editTimeoutSeconds: Long
        get() = server["edit-timeout"].asLong()?.takeIf { it >= 0 } ?: 0L

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
        /**
         * The chats named in [prev] or [next] whose mute differs at [nowMs]: a different
         * [chatMuteUntil] or [chatMuteState] (so a chat without an entry is sound on or unknown by
         * [chatsKnown]). Sorted by chat id; ids that are not numbers are skipped. Chats neither
         * config names are not listed, even when [chatsKnown] differs: check that separately.
         */
        fun chatMuteChanges(prev: AccountConfig, next: AccountConfig, nowMs: Long): List<ChatMuteChange> =
            (prev.chats.keys + next.chats.keys).mapNotNull { it.toLongOrNull() }.distinct().sorted().mapNotNull { id ->
                val after = next.chatMuteUntil(id)
                val mutedAfter = next.chatMuteState(id, nowMs)
                if (prev.chatMuteUntil(id) == after && prev.chatMuteState(id, nowMs) == mutedAfter) null
                else ChatMuteChange(id, after, mutedAfter)
            }

        /** [maxReadmarks] when the server config does not name one. */
        const val DEFAULT_MAX_READMARKS: Int = 100

        /** `presence-ttl` of the MAX web client's server config defaults, in seconds. */
        const val DEFAULT_PRESENCE_TTL_S: Long = 300

        /**
         * `config` of a `LOGIN` reply ([loginReply] is the whole reply map) as a config of its own
         * (missing sections empty, [chatsKnown] when it carries `chats`); `null` when it has none. To apply it to a known config use
         * [AccountConfigUpdate.fromLoginReply] with [replacedBy] or [mergedWith]: this alone would
         * drop every section the reply leaves out.
         */
        fun fromLoginReply(loginReply: Map<*, *>): AccountConfig? =
            AccountConfigUpdate.fromLoginReply(loginReply)?.let { AccountConfig().replacedBy(it) }

        private fun dropNulls(chats: Map<String, Any?>): Map<String, Any?> = chats.filterValues { it != null }

        private fun mergeChats(known: Map<String, Any?>, update: Map<String, Any?>): Map<String, Any?> {
            val out = LinkedHashMap(known)
            for ((id, entry) in update) {
                when (entry) {
                    null -> out.remove(id)
                    is Map<*, *> -> {
                        val merged = LinkedHashMap<String, Any?>()
                        (out[id] as? Map<*, *>)?.forEach { (k, v) -> if (k != null) merged[k.toString()] = v }
                        entry.forEach { (k, v) -> if (k != null) merged[k.toString()] = v }
                        out[id] = merged
                    }
                    else -> out[id] = entry
                }
            }
            return out
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
 * A chat whose mute changed ([AccountConfig.chatMuteChanges]): its new [dontDisturbUntil]
 * ([AccountConfig.chatMuteUntil]: `0` sound on, `-1` muted for good, else the end of the mute in
 * ms) and whether it is [muted] at the time of the comparison ([AccountConfig.chatMuteState]).
 * Both are `null` when the new config does not know the chat.
 */
data class ChatMuteChange(val chatId: Long, val dontDisturbUntil: Long?, val muted: Boolean?)

/**
 * A `config` as the server sent it, before it is applied to a known [AccountConfig]: a `null`
 * section is one the payload does not carry (keep the known one), an empty map one it carries
 * empty. Comes from the `config` of a `LOGIN` 19 or `LOGIN2` 8 reply ([fromLoginReply]) and from
 * the `NOTIF_CONFIG` 134 push ([fromPush]).
 *
 * @property chats `config.chats` by chat id (keys as decimal strings); an entry may be `null`.
 * @property hash `config.hash` as text (an integer hash in decimal).
 */
data class AccountConfigUpdate(
    val user: Map<String, Any?>? = null,
    val server: Map<String, Any?>? = null,
    val chats: Map<String, Any?>? = null,
    val hash: String? = null,
) {
    /** The chats [chats] names (ids that are not numbers are skipped). */
    val chatIds: List<Long> get() = chats.orEmpty().keys.mapNotNull { it.toLongOrNull() }

    /** `true` when the update carries nothing to apply. */
    val isEmpty: Boolean get() = user == null && server == null && chats == null && hash == null

    companion object {
        /** The sections of a `config` map; `null` when [config] is not a map. */
        fun from(config: Any?): AccountConfigUpdate? {
            val map = config as? Map<*, *> ?: return null
            return AccountConfigUpdate(
                user = section(map["user"]),
                server = section(map["server"]),
                chats = section(map["chats"]),
                hash = map["hash"]?.let { it as? String ?: it.asLong()?.toString() },
            )
        }

        /** `config` of a `LOGIN` / `LOGIN2` reply; `null` when the reply has none. */
        fun fromLoginReply(reply: Map<*, *>): AccountConfigUpdate? = from(reply["config"])

        /**
         * A `NOTIF_CONFIG` 134 push. None of the references reads its payload (KometTeam/Komet,
         * PyMax and kolibri only name the opcode), so both shapes a config travels in are taken:
         * `{config: {...}}` as in `LOGIN`, or the sections at the top (`{chats, user, server,
         * hash}`). `null` when the payload has none of them.
         */
        fun fromPush(payload: Map<*, *>): AccountConfigUpdate? {
            val update = (payload["config"] as? Map<*, *>)?.let(::from) ?: from(payload)
            return update?.takeUnless { it.isEmpty }
        }

        private fun section(value: Any?): Map<String, Any?>? =
            (value as? Map<*, *>)?.let { AccountConfig.stringKeys(it) }
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
