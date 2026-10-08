package com.max.core.api

/**
 * `status` codes of a presence `{seen, status}` as the MAX web client reads them, plus [UNKNOWN]
 * for "no presence known" (never sent by the server).
 *
 * - [OFFLINE] `0` (also a presence without `status`): offline, `seen` is the last time online;
 * - [ONLINE] `1`;
 * - [RECENTLY] `2`: was online recently, the time is hidden;
 * - [LONG_AGO] `3`: was online long ago (also what the web client assumes for an id missing
 *   from a `CONTACT_PRESENCE` 35 reply);
 * - any other value is read as [RECENTLY] (web client).
 */
object PresenceStatus {
    const val UNKNOWN: Int = -1
    const val OFFLINE: Int = 0
    const val ONLINE: Int = 1
    const val RECENTLY: Int = 2
    const val LONG_AGO: Int = 3

    /**
     * The effective code of [info]: [UNKNOWN] for `null` (nothing known), [OFFLINE] for a presence
     * without `status`, `0`..`3` as sent, [RECENTLY] for any other value.
     */
    fun of(info: PresenceInfo?): Int {
        if (info == null) return UNKNOWN
        return when (val s = info.status) {
            null -> OFFLINE
            OFFLINE, ONLINE, RECENTLY, LONG_AGO -> s
            else -> RECENTLY
        }
    }
}

/** Parsing and merging of presence maps (`{seen, status}`, `seen` in Unix seconds). */
object Presences {
    /** Values of `seen` from this one on are already milliseconds (a seconds value is ~1.7e9). */
    const val MS_THRESHOLD: Long = 100_000_000_000L

    /** One `{seen?, status?}` map; `null` when [raw] is not a map. */
    fun parse(raw: Any?): PresenceInfo? {
        val m = raw as? Map<*, *> ?: return null
        return PresenceInfo(m["seen"].asLong(), m["status"].asLong()?.toInt())
    }

    /**
     * A `{userId: {seen, status}}` map (the `presence` of a `LOGIN` 19 or `CONTACT_PRESENCE` 35
     * reply). Keys may be integers or decimal strings; entries whose key is not an id or whose
     * value is not a map are skipped. `null` when [raw] is not a map at all.
     */
    fun parseMap(raw: Any?): Map<Long, PresenceInfo>? {
        val m = raw as? Map<*, *> ?: return null
        val out = LinkedHashMap<Long, PresenceInfo>()
        for ((k, v) in m) {
            val id = k.asLong() ?: continue
            out[id] = parse(v) ?: continue
        }
        return out
    }

    /**
     * The `CONTACT_PRESENCE` 35 reply `{presence: {userId: {seen, status}}}` for the asked [ids]:
     * an id the reply leaves out is [PresenceStatus.LONG_AGO] without a time (the MAX web client
     * does the same). Ids the reply adds without being asked are kept too.
     */
    fun fromContactPresence(ids: List<Long>, reply: Map<*, *>): Map<Long, PresenceInfo> {
        val got = parseMap(reply["presence"]).orEmpty()
        val out = LinkedHashMap<Long, PresenceInfo>()
        for (id in ids) out[id] = got[id] ?: PresenceInfo(null, PresenceStatus.LONG_AGO)
        for ((id, p) in got) if (id !in out) out[id] = p
        return out
    }

    /** [incoming] over [stored]: a missing `seen` keeps the stored one (push 132 without `seen`). */
    fun merge(stored: PresenceInfo?, incoming: PresenceInfo): PresenceInfo =
        if (incoming.seen == null && stored?.seen != null) incoming.copy(seen = stored.seen) else incoming

    /** [seen] in milliseconds (seconds are multiplied, a millisecond value is kept); `0` when unknown. */
    fun seenMs(seen: Long?): Long = when {
        seen == null || seen <= 0 -> 0L
        seen < MS_THRESHOLD -> seen * 1000
        else -> seen
    }

    /**
     * "Online" that is no longer trusted (no refresh within the TTL, a new session): offline,
     * with `seen` the last time it was known to be online, that is the later of its `seen` and
     * [refreshedAtMs] (the local time the online status last arrived, `null` when unknown). The
     * result keeps the unit of `seen` (seconds unless the server sent milliseconds).
     */
    fun degrade(info: PresenceInfo, refreshedAtMs: Long?): PresenceInfo {
        val seen = info.seen
        val inMs = seen != null && seen >= MS_THRESHOLD
        val refreshed = refreshedAtMs?.takeIf { it > 0 }?.let { if (inMs) it else it / 1000 }
        val last = listOfNotNull(seen?.takeIf { it > 0 }, refreshed).maxOrNull()
        return PresenceInfo(last, PresenceStatus.OFFLINE)
    }
}
