package com.maxly.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageSizesTest {
    @Test
    fun laddersMatchTheAppAndGrow() {
        assertEquals(
            listOf(32, 48, 50, 56, 64, 72, 80, 96, 128, 160, 176, 192, 223, 224, 256, 288, 320, 352, 448, 480, 492, 576, 600, 720),
            ImageSizes.SQUARE.map { it.pixels },
        )
        assertEquals(
            listOf("sqr_32", "sqr_128_2x", "sqr_176_2x", "sqr_224_2x", "sqr_288_2x", "sqr_720"),
            listOf(0, 14, 17, 18, 21, 23).map { ImageSizes.SQUARE[it].fn },
        )
        assertEquals(
            listOf(180, 240, 320, 480, 600, 720, 960, 1080, 1280, 1440),
            ImageSizes.WIDTH.map { it.pixels },
        )
        assertEquals(ImageSizes.SQUARE_TINY, "sqr_64")
        assertEquals(ImageSizes.WIDTH_LARGE, "w_1080")
        assertTrue(ImageSizes.SQUARE.zipWithNext().all { (a, b) -> a.pixels < b.pixels })
        assertTrue(ImageSizes.WIDTH.zipWithNext().all { (a, b) -> a.pixels < b.pixels })
    }

    @Test
    fun pickIsTheFirstStepAtLeastTheNeededPixels() {
        assertEquals("sqr_32", ImageSizes.pick(ImageShape.SQUARE, 1))
        assertEquals("sqr_32", ImageSizes.pick(ImageShape.SQUARE, 32))
        assertEquals("sqr_48", ImageSizes.pick(ImageShape.SQUARE, 33))
        assertEquals("sqr_223", ImageSizes.pick(ImageShape.SQUARE, 200))
        // 250 is past sqr_224 (224) and under sqr_128_2x (256)
        assertEquals("sqr_128_2x", ImageSizes.pick(ImageShape.SQUARE, 250))
        assertEquals("sqr_720", ImageSizes.pick(ImageShape.SQUARE, 10_000))
        assertEquals("sqr_32", ImageSizes.pick(ImageShape.SQUARE, 0))
        assertEquals("sqr_32", ImageSizes.pick(ImageShape.SQUARE, -5))
        assertEquals("w_480", ImageSizes.pick(ImageShape.WIDTH, 400))
        assertEquals("w_1440", ImageSizes.pick(ImageShape.WIDTH, 1440))
        assertEquals("w_1440", ImageSizes.pick(ImageShape.WIDTH, 2000))
    }

    @Test
    fun urlAppendsFnTheWayTheAppDoes() {
        assertEquals("https://cdn/a?expires=5&fn=sqr_64", ImageSizes.url("https://cdn/a?expires=5", "sqr_64"))
        // no query yet: the app still uses '&', not '?'
        assertEquals("https://cdn/a&fn=w_480", ImageSizes.sizedUrl("https://cdn/a", ImageShape.WIDTH, 400))
        assertEquals("", ImageSizes.url("", "sqr_64"))
        assertEquals("https://cdn/a", ImageSizes.url("https://cdn/a", ""))
    }

    @Test
    fun expiresIsMillisAndMissingMeansNotExpired() {
        val url = "https://cdn/a?x=1&expires=5000&fn=w_180"
        assertEquals(5000L, ImageSizes.expiresAtMillis(url))
        assertFalse(ImageSizes.isExpired(url, 4999))
        assertTrue(ImageSizes.isExpired(url, 5000))
        assertFalse(ImageSizes.isExpired("https://cdn/a", 9_999))
        assertNull(ImageSizes.expiresAtMillis("https://cdn/a?expires=soon"))
        assertFalse(ImageSizes.isExpired("https://cdn/a?expires=soon", 9_999))
    }
}
