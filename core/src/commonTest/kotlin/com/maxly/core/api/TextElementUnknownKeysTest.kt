package com.maxly.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

class TextElementUnknownKeysTest {
    private val received = """
        [
          {"type":"SPOILER","from":1,"length":2,"entityId":7,"color":"red",
           "meta":{"depth":{"level":3,"tags":["a",{"b":null}]},"ratio":0.5,"on":true},
           "ranges":[[0,1],[2,3]],"big":9007199254740993,"attributes":{"url":"x","n":4}},
          {"type":"strong","from":0,"length":3,"weight":700,"style":{"fill":[1,2,3]}},
          {"type":"STRONG","from":0,"length":99,"note":"clipped"}
        ]
    """.trimIndent()

    @Test
    fun everyKeyOfEveryElementRoundTripsThroughJson() {
        val elements = TextElementsJson.parse(received, 10)
        assertEquals(3, elements.size)
        val spoiler = elements[0]
        assertEquals(
            mapOf(
                "color" to "red",
                "meta" to mapOf("depth" to mapOf("level" to 3L, "tags" to listOf("a", mapOf("b" to null))), "ratio" to 0.5, "on" to true),
                "ranges" to listOf(listOf(0L, 1L), listOf(2L, 3L)),
                "big" to 9007199254740993L,
            ),
            spoiler.extra,
        )
        assertEquals(7L, spoiler.entityId)
        assertEquals(mapOf("url" to "x", "n" to 4L), spoiler.attributes)
        // an element parsing did not change goes back exactly as it came (here: the same JSON)
        val original = Json.parseToJsonElement(received) as JsonArray
        val written = Json.parseToJsonElement(TextElementsJson.write(elements)) as JsonArray
        assertEquals(original[0], written[0])
        // a respelled type or a clipped element is written from its fields and keeps its other keys
        assertEquals(Json.parseToJsonElement("""{"type":"STRONG","from":0,"length":3,"weight":700,"style":{"fill":[1,2,3]}}"""), written[1])
        assertEquals(
            Json.parseToJsonElement("""{"type":"STRONG","from":0,"length":10,"note":"clipped"}"""),
            written[2],
        )
        // and the round trip reads the same elements
        assertEquals(elements, TextElementsJson.parse(TextElementsJson.write(elements), 10))
    }

    @Test
    fun aKnownTypeMatchesInAnyCaseAndKeepsItsKeys() {
        val e = TextElement.parse(mapOf("type" to "Strong", "from" to 0, "length" to 3, "junk" to 1), 3)!!
        assertEquals(TextElement(TextElementType.STRONG, 0, 3, extra = mapOf("junk" to 1L)), e)
        // the type was respelled: written from the fields
        assertEquals(mapOf("type" to "STRONG", "from" to 0, "length" to 3, "junk" to 1L), e.toPayload())
        assertEquals(TextElementType.MONOSPACED, TextElement.parse(mapOf("type" to "code", "length" to 1), 1)!!.type)
    }

    @Test
    fun aChangedElementIsWrittenFromItsFields() {
        val e = TextElement.parse(mapOf("type" to "SPOILER", "length" to 2, "from" to 1, "x" to listOf(1)), 10)!!
        // as received: key order and value types unchanged
        assertEquals(listOf("type", "length", "from", "x"), e.toPayload().keys.toList())
        assertEquals(listOf(1), e.toPayload()["x"])
        // numbers sent as strings go out as numbers
        assertEquals(1, TextElement.parse(mapOf("type" to "SPOILER", "from" to "1", "length" to 2), 10)!!.toPayload()["from"])
        val moved = e.copy(from = 4)
        assertTrue(moved.raw.isEmpty())
        assertEquals(mapOf("type" to "SPOILER", "from" to 4, "length" to 2, "x" to listOf(1L)), moved.toPayload())
        // made in code: no raw form
        assertTrue(TextElement.strong(0, 1).raw.isEmpty())
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
