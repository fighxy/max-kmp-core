package com.maxly.core.api

import com.maxly.core.auth.RequestSink
import com.maxly.core.protocol.Opcode

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

    // ---- Stickers (KometTeam/Komet `StickersModule`, schema only) ------------------------------

    /**
     * Sticker sets to show and the recently used stickers:
     * 1. `ASSETS_UPDATE` 27 `{type: "FAVORITE_STICKER", sync: 0}` → section `FAVORITE_STICKER_SETS`
     *    `stickerSets` (the account's sets, first);
     * 2. `ASSETS_UPDATE` 27 `{type: "STICKER", sync: 0}` → section `NEW_STICKER_SETS` `stickerSets`
     *    with a `marker` for more, and a `RECENTS` section `recentsList[]` of `{type: STICKER, stickerId|id}`;
     * 3. `ASSETS_GET` 26 `{sectionId: "NEW_STICKER_SETS", from: marker, count: 100}` while the
     *    marker moves (at most [MAX_PAGES] pages).
     * A failed favourites request leaves them out; the catalog itself must answer.
     */
    suspend fun stickerSections(): StickerSections {
        val favorites = runCatching {
            val map = rawMap(sink.request(Opcode.ASSETS_UPDATE, linkedMapOf("type" to "FAVORITE_STICKER", "sync" to 0)))
            sections(map).filter { it["id"] == "FAVORITE_STICKER_SETS" }.flatMap { ids(it["stickerSets"]) }
        }.getOrElse { if (it is kotlin.coroutines.cancellation.CancellationException) throw it else emptyList() }
        val map = rawMap(sink.request(Opcode.ASSETS_UPDATE, linkedMapOf("type" to "STICKER", "sync" to 0)))
        val catalog = mutableListOf<Long>()
        val recents = mutableListOf<Long>()
        var marker = 0L
        for (section in sections(map)) {
            if (section["id"] == "NEW_STICKER_SETS") {
                catalog += ids(section["stickerSets"])
                marker = section["marker"].asLong() ?: 0L
            } else if (section["type"] == "RECENTS") {
                (section["recentsList"] as? List<*>).orEmpty().forEach { entry ->
                    val e = entry as? Map<*, *> ?: return@forEach
                    if (e["type"] != "STICKER") return@forEach
                    (e["stickerId"].asLong() ?: e["id"].asLong())?.let(recents::add)
                }
            }
        }
        var pages = 0
        while (marker != 0L && pages < MAX_PAGES) {
            pages++
            val page = rawMap(sink.request(Opcode.ASSETS_GET, linkedMapOf("sectionId" to "NEW_STICKER_SETS", "from" to marker, "count" to BATCH)))
            val before = catalog.size
            catalog += ids(page["stickerSets"])
            if (catalog.size == before) break
            marker = page["marker"].asLong() ?: 0L
        }
        return StickerSections((favorites + catalog).distinct(), favorites.distinct(), recents.distinct())
    }

    /** Sets by id (`ASSETS_GET_BY_IDS` 28, type `STICKER_SET`, batches of [BATCH]) in the given order. */
    suspend fun stickerSets(ids: List<Long>): List<StickerSet> =
        byIds(ids, "STICKER_SET", "stickerSets", StickerSet::from) { it.id }

    /** Stickers by id (`ASSETS_GET_BY_IDS` 28, type `STICKER`, batches of [BATCH]) in the given order. */
    suspend fun stickers(ids: List<Long>): List<StickerItem> =
        byIds(ids, "STICKER", "stickers", StickerItem::from) { it.id }

    private suspend fun <T : Any> byIds(ids: List<Long>, type: String, key: String, parse: (Any?) -> T?, id: (T) -> Long): List<T> {
        val found = LinkedHashMap<Long, T>()
        for (batch in ids.distinct().chunked(BATCH)) {
            val map = rawMap(sink.request(Opcode.ASSETS_GET_BY_IDS, linkedMapOf("type" to type, "ids" to batch)))
            (map[key] as? List<*>).orEmpty().mapNotNull(parse).forEach { found[id(it)] = it }
        }
        return ids.distinct().mapNotNull { found[it] }
    }

    private fun sections(map: Map<*, *>): List<Map<*, *>> =
        (map["sections"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }

    private fun ids(value: Any?): List<Long> = (value as? List<*>).orEmpty().mapNotNull { it.asLong() }

    companion object {
        const val BATCH = 100
        const val MAX_PAGES = 50
    }
}

/** What the sticker panel shows: [setIds] in order (the account's [favoriteSetIds] first) and [recentStickerIds]. */
data class StickerSections(
    val setIds: List<Long>,
    val favoriteSetIds: List<Long>,
    val recentStickerIds: List<Long>,
)

/** A sticker set: `{id, name, iconUrl, stickers: [ids], link?}`. */
data class StickerSet(
    val id: Long,
    val name: String,
    val iconUrl: String?,
    val stickerIds: List<Long>,
    val link: String?,
) {
    companion object {
        fun from(value: Any?): StickerSet? {
            val m = value as? Map<*, *> ?: return null
            val id = m["id"].asLong() ?: return null
            val stickers = (m["stickers"] as? List<*>).orEmpty().mapNotNull { it.asLong() }
            return StickerSet(id, m["name"] as? String ?: "", (m["iconUrl"] as? String)?.takeIf { it.isNotEmpty() }, stickers, m["link"] as? String)
        }
    }
}

/** A sticker: `{id, url, lottieUrl?, setId?, width?, height?, tags?}`; with a `lottieUrl` it is animated. */
data class StickerItem(
    val id: Long,
    val url: String,
    val lottieUrl: String?,
    val setId: Long?,
    val width: Int?,
    val height: Int?,
    val tags: List<String>,
) {
    companion object {
        fun from(value: Any?): StickerItem? {
            val m = value as? Map<*, *> ?: return null
            val id = m["id"].asLong() ?: return null
            return StickerItem(
                id,
                m["url"] as? String ?: "",
                (m["lottieUrl"] as? String)?.takeIf { it.isNotEmpty() },
                m["setId"].asLong(),
                (m["width"] as? Number)?.toInt(),
                (m["height"] as? Number)?.toInt(),
                (m["tags"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.takeIf(String::isNotEmpty) },
            )
        }
    }
}
