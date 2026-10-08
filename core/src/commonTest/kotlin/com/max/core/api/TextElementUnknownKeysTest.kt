package com.max.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextElementUnknownKeysTest {
    @Test
    fun anUnknownTypeKeepsEveryKeyAndSendsItBack() {
        val raw = mapOf("type" to "SPOILER", "from" to 1, "length" to 2, "color" to "red", "meta" to mapOf("x" to 1L), "entityId" to 7L)
        val e = TextElement.parse(raw, 10)!!
        assertEquals(mapOf("color" to "red", "meta" to mapOf("x" to 1L)), e.extra)
        assertEquals(
            mapOf("type" to "SPOILER", "from" to 1, "length" to 2, "entityId" to 7L, "color" to "red", "meta" to mapOf("x" to 1L)),
            e.toPayload(),
        )
        assertEquals(listOf(e), TextElementsJson.parse(TextElementsJson.write(listOf(e)), 10))
    }

    @Test
    fun aKnownTypeDropsUnknownKeysAndMatchesInAnyCase() {
        val e = TextElement.parse(mapOf("type" to "Strong", "from" to 0, "length" to 3, "junk" to 1), 3)!!
        assertEquals(TextElement(TextElementType.STRONG, 0, 3), e)
        assertEquals(TextElementType.MONOSPACED, TextElement.parse(mapOf("type" to "code", "length" to 1), 1)!!.type)
    }

    @Test
    fun theTextBoundsApply() {
        assertEquals(3, TextElement.parse(mapOf("type" to "STRONG", "from" to 2, "length" to 50), 5)!!.length)
        assertNull(TextElement.parse(mapOf("type" to "STRONG", "from" to 5, "length" to 1), 5))
        assertNull(TextElement.parse(mapOf("type" to "LINK", "from" to 0, "length" to 1, "attributes" to mapOf("url" to "")), 5))
        // without a text length only the element itself is checked
        assertEquals(50, TextElement.parse(mapOf("type" to "STRONG", "from" to 2, "length" to 50))!!.length)
    }
}
