package com.max.core.media

/**
 * Image URL sizes of the Android app (`ey0` / `gy0`).
 *
 * A photo or avatar URL takes a query `fn`: `sqr_N` for a square (avatars) and `w_N` for a photo
 * by width. The app appends it as `&fn=` (even when the URL has no `?` yet). The `2x` square
 * names (`sqr_128_2x` and three more) count as twice their `N` when choosing.
 *
 * [pick] takes the size in **pixels** the caller already multiplied by screen density. The core
 * does not know the screen. The rule (agreed for this client; the app itself picks the nearest
 * size) is the first step of the ladder whose pixel size is at least the request. The URL with
 * no `fn` is the original and is only for a full-screen viewer: [pick] never returns it.
 *
 * Nothing here loads an image or refreshes an expired URL. Clients call [url] / [sizedUrl] when
 * they build a request, and [com.max.core.media.MediaApi.refreshPhotoUrls] when [isExpired].
 */
object ImageSizes {
    /** Square ladder, smallest first. [ImageFn.pixels] of a `2x` name is `N * 2`. */
    val SQUARE: List<ImageFn> = listOf(
        ImageFn("sqr_32", 32),
        ImageFn("sqr_48", 48),
        ImageFn("sqr_50", 50),
        ImageFn("sqr_56", 56),
        ImageFn("sqr_64", 64),
        ImageFn("sqr_72", 72),
        ImageFn("sqr_80", 80),
        ImageFn("sqr_96", 96),
        ImageFn("sqr_128", 128),
        ImageFn("sqr_160", 160),
        ImageFn("sqr_176", 176),
        ImageFn("sqr_192", 192),
        ImageFn("sqr_223", 223),
        ImageFn("sqr_224", 224),
        ImageFn("sqr_128_2x", 256),
        ImageFn("sqr_288", 288),
        ImageFn("sqr_320", 320),
        ImageFn("sqr_176_2x", 352),
        ImageFn("sqr_224_2x", 448),
        ImageFn("sqr_480", 480),
        ImageFn("sqr_492", 492),
        ImageFn("sqr_288_2x", 576),
        ImageFn("sqr_600", 600),
        ImageFn("sqr_720", 720),
    )

    /** Width ladder, smallest first. */
    val WIDTH: List<ImageFn> = listOf(
        ImageFn("w_180", 180),
        ImageFn("w_240", 240),
        ImageFn("w_320", 320),
        ImageFn("w_480", 480),
        ImageFn("w_600", 600),
        ImageFn("w_720", 720),
        ImageFn("w_960", 960),
        ImageFn("w_1080", 1080),
        ImageFn("w_1280", 1280),
        ImageFn("w_1440", 1440),
    )

    /** Fixed classes the app names (`fy0`): tiny, small, medium, large, max. */
    const val SQUARE_TINY: String = "sqr_64"
    const val SQUARE_SMALL: String = "sqr_96"
    const val SQUARE_MEDIUM: String = "sqr_192"
    const val SQUARE_LARGE: String = "sqr_480"
    const val SQUARE_MAX: String = "sqr_720"
    const val WIDTH_TINY: String = "w_180"
    const val WIDTH_SMALL: String = "w_240"
    const val WIDTH_MEDIUM: String = "w_480"
    const val WIDTH_LARGE: String = "w_1080"
    const val WIDTH_MAX: String = "w_1440"

    /**
     * The `fn` value for [neededPixels] (already density-adjusted). Zero or negative yields the
     * smallest step; a request past the top of the ladder yields the largest step, not the
     * original.
     */
    fun pick(shape: ImageShape, neededPixels: Int): String {
        val ladder = ladder(shape)
        val need = neededPixels.coerceAtLeast(0)
        return ladder.firstOrNull { it.pixels >= need }?.fn ?: ladder.last().fn
    }

    /** [url] with `&fn=` set to [pick]. A blank [base] is returned unchanged. */
    fun sizedUrl(base: String, shape: ImageShape, neededPixels: Int): String =
        url(base, pick(shape, neededPixels))

    /**
     * The app's `gy0.a`: [base] plus `&fn=[fn]`. A blank [base] or [fn] is returned unchanged.
     * An existing `fn` is not removed; pass the URL without one.
     */
    fun url(base: String, fn: String): String {
        if (base.isBlank() || fn.isBlank()) return base
        return "$base&fn=$fn"
    }

    /**
     * Epoch millis of the `expires` query, or `null` when it is missing or not a number.
     * The app compares this to its clock in milliseconds (`hog.f` = `currentTimeMillis` plus an
     * offset).
     */
    fun expiresAtMillis(url: String): Long? {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq) == "expires") return part.substring(eq + 1).toLongOrNull()
        }
        return null
    }

    /**
     * Whether the app would refresh this URL: `expires` is present and `nowMillis >= expires`.
     * No `expires`, or one that does not parse, is not expired (the app treats that as never).
     */
    fun isExpired(url: String, nowMillis: Long): Boolean {
        val expires = expiresAtMillis(url) ?: return false
        return nowMillis >= expires
    }

    private fun ladder(shape: ImageShape): List<ImageFn> = when (shape) {
        ImageShape.SQUARE -> SQUARE
        ImageShape.WIDTH -> WIDTH
    }
}

/** `sqr_` (avatars) or `w_` (photos). The app's `dy0`. */
enum class ImageShape { SQUARE, WIDTH }

/** One step of a ladder: the `fn` query value and the pixel size it stands for. */
data class ImageFn(val fn: String, val pixels: Int)
