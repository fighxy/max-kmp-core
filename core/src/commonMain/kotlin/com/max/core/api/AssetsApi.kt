package com.max.core.api

import com.max.core.auth.RequestSink
import com.max.core.protocol.Opcode

/** One animated emoji of the server catalog (`ASSETS_GET_BY_IDS` 28, type `ANIMOJI`). */
data class Animoji(
    val id: Long,
    val emoji: String,
    val setId: Long?,
    val iconUrl: String?,
    val lottieUrl: String?,
) {
    companion object {
        /** `{id, emoji, setId?, iconUrl?, lottieUrl?}`; `null` without an id or an emoji. */
        fun from(value: Any?): Animoji? {
            val m = value as? Map<*, *> ?: return null
            val id = m["id"].asLong() ?: return null
            val emoji = (m["emoji"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            return Animoji(id, emoji, m["setId"].asLong(), m["iconUrl"] as? String, m["lottieUrl"] as? String)
        }
    }
}

/**
 * Server assets used for reactions: the animated emoji ("animoji") catalog.
 *
 * The flow follows KometTeam/Komet `AnimojiModule` (schema only):
 * 1. `ASSETS_UPDATE` 27 `{type: "ANIMOJI_SET", sync: 0}` → `sections[].animojiSetIds`; when there
 *    are no sections, the keys of `animojiUpdates` are animoji ids.
 * 2. `ASSETS_GET_BY_IDS` 28 `{type: "ANIMOJI_SET", ids}` → `animojiSets[]` with the animoji ids of
 *    each set (`animojis` or `animojiIds`), in catalog order.
 * 3. `ASSETS_GET_BY_IDS` 28 `{type: "ANIMOJI", ids}` in batches of [BATCH] (the server rejects a
 *    longer list) → `animojis[]`.
 *
 * These are the emoji the server offers for reactions. Per-chat reaction settings
 * (`REACTIONS_SETTINGS_GET_BY_CHAT_ID` 258) have no known payload and are not requested.
 */
class AssetsApi(private val sink: RequestSink) {

    /** Step 1: animoji set ids, or (second) bare animoji ids when the reply has no sections. */
    suspend fun animojiSetIds(): Pair<List<Long>, List<Long>> {
        val map = rawMap(sink.request(Opcode.ASSETS_UPDATE, linkedMapOf("type" to "ANIMOJI_SET", "sync" to 0)))
        val sets = (map["sections"] as? List<*>).orEmpty().flatMap { section ->
            ids((section as? Map<*, *>)?.get("animojiSetIds"))
        }
        val loose = (map["animojiUpdates"] as? Map<*, *>).orEmpty().keys.mapNotNull { it.asLong() }
        return sets.distinct() to loose.distinct()
    }

    /** Step 2: animoji ids of the given sets, in set order. */
    suspend fun animojiIdsOfSets(setIds: List<Long>): List<Long> {
        if (setIds.isEmpty()) return emptyList()
        val map = rawMap(sink.request(Opcode.ASSETS_GET_BY_IDS, linkedMapOf("type" to "ANIMOJI_SET", "ids" to setIds)))
        return (map["animojiSets"] as? List<*>).orEmpty().flatMap { set ->
            val m = set as? Map<*, *> ?: return@flatMap emptyList()
            ids(m["animojis"]) + ids(m["animojiIds"])
        }.distinct()
    }

    /** Step 3: the animoji themselves, requested in batches of [BATCH]; unknown ids are left out. */
    suspend fun animojis(ids: List<Long>): List<Animoji> {
        val found = LinkedHashMap<Long, Animoji>()
        for (batch in ids.distinct().chunked(BATCH)) {
            val map = rawMap(sink.request(Opcode.ASSETS_GET_BY_IDS, linkedMapOf("type" to "ANIMOJI", "ids" to batch)))
            (map["animojis"] as? List<*>).orEmpty().mapNotNull(Animoji::from).forEach { found[it.id] = it }
        }
        return ids.distinct().mapNotNull { found[it] }
    }

    /** The whole reaction catalog in server order (steps 1–3); empty when the server has none. */
    suspend fun reactionCatalog(): List<Animoji> {
        val (sets, loose) = animojiSetIds()
        val ordered = animojiIdsOfSets(sets).ifEmpty { loose }
        if (ordered.isEmpty()) return emptyList()
        // One emoji once: several sets may carry the same one.
        return animojis(ordered).distinctBy { it.emoji }
    }

    private fun ids(value: Any?): List<Long> = (value as? List<*>).orEmpty().mapNotNull { it.asLong() }

    companion object {
        const val BATCH = 100
    }
}
