package com.maxly.core.api

import com.maxly.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextElementsTest {

    @Test
    fun payloadKeepsTheKeyOrderAndLeavesUnsetFieldsOut() {
        assertEquals(mapOf("type" to "STRONG", "from" to 0, "length" to 4), TextElement.strong(0, 4).toPayload())
        assertEquals(listOf("type", "from", "length"), TextElement.quote(1, 2).toPayload().keys.toList())
        val link = TextElement.link(5, 3, "https://max.ru").toPayload()
        assertEquals(listOf("type", "from", "length", "attributes"), link.keys.toList())
        assertEquals(mapOf("url" to "https://max.ru"), link["attributes"])
        val mention = TextElement.mention(0, 5, 42, "Ann").toPayload()
        assertEquals(listOf("type", "from", "length", "entityId", "entityName"), mention.keys.toList())
        assertEquals(42L, mention["entityId"])
        assertEquals(listOf("type", "from", "length", "entityId"), TextElement.mention(0, 5, 42).toPayload().keys.toList())
        val animoji = TextElement.animoji(2, 2, 7, "https://cdn/a.json").toPayload()
        assertEquals(listOf("type", "from", "length", "entityId", "attributes"), animoji.keys.toList())
        assertEquals(mapOf("animojiLottieUrl" to "https://cdn/a.json"), animoji["attributes"])
    }

    @Test
    fun typesAreTheWireNames() {
        assertEquals(
            listOf("STRONG", "EMPHASIZED", "UNDERLINE", "STRIKETHROUGH", "MONOSPACED", "CODE", "HEADING", "QUOTE", "LINK", "USER_MENTION", "ANIMOJI"),
            TextElementType.all,
        )
        assertEquals("EMPHASIZED", TextElement.emphasized(0, 1).type)
        assertEquals("UNDERLINE", TextElement.underline(0, 1).type)
        assertEquals("STRIKETHROUGH", TextElement.strikethrough(0, 1).type)
        assertEquals("MONOSPACED", TextElement.monospaced(0, 1).type)
        assertEquals("HEADING", TextElement.heading(0, 1).type)
    }

    @Test
    fun parsingSkipsBrokenEntriesAndKeepsUnknownTypes() {
        val parsed = TextElement.parseAll(
            listOf(
                mapOf("type" to "STRONG", "from" to 0, "length" to 5),
                mapOf("type" to "LINK", "from" to "6", "length" to "4", "attributes" to mapOf("url" to "https://a.b")),
                mapOf("type" to "USER_MENTION", "from" to 11, "length" to 4, "entityId" to 77, "entityName" to "Bob"),
                mapOf("type" to "SPOILER_NEW", "from" to 1, "length" to 1),
                mapOf("type" to "STRONG", "from" to 0, "length" to 0), // empty
                mapOf("type" to "", "from" to 0, "length" to 2), // no type
                mapOf("type" to "QUOTE", "from" to -1, "length" to 2), // negative offset
                mapOf("type" to "HEADING", "length" to 3), // no from = 0
                "garbage",
            ),
        )
        assertEquals(listOf("STRONG", "LINK", "USER_MENTION", "SPOILER_NEW", "HEADING"), parsed.map { it.type })
        assertEquals("https://a.b", parsed[1].url)
        assertEquals(6, parsed[1].from)
        assertEquals(10, parsed[1].end)
        assertEquals(77L, parsed[2].entityId)
        assertEquals("Bob", parsed[2].entityName)
        assertEquals(0, parsed[4].from)
        assertNull(parsed[0].url)
        assertEquals(emptyList(), TextElement.parseAll(null))
        assertEquals(emptyList(), TextElement.parseAll(mapOf("type" to "STRONG")))
    }

    @Test
    fun messageExposesTypedElementsAndRoundTripsThem() {
        val sent = listOf(TextElement.strong(0, 5), TextElement.link(6, 4, "https://a.b"), TextElement.animoji(11, 2, 9, "https://l"))
        val m = MaxMessage.from(
            mapOf("id" to 1L, "time" to 2L, "type" to "USER", "text" to "Hello link 🙂", "elements" to sent.map { it.toPayload() }),
        )!!
        assertEquals(sent, m.textElements)
        assertEquals("https://l", m.textElements[2].animojiLottieUrl)
    }

    @Test
    fun payloadForDropsElementsOutsideTheText() {
        val text = "Hi 👋" // 👋 is two UTF-16 units: length 5
        val out = TextElement.payloadFor(text, listOf(TextElement.strong(0, 2), TextElement.emphasized(3, 2), TextElement.underline(4, 2), TextElement.quote(0, 0)))
        assertEquals(listOf("STRONG", "EMPHASIZED"), out.map { it["type"] })
        assertTrue(TextElement.emphasized(3, 2).fits(text.length))
    }

    @Test
    fun formattedSendAndEditCarryTheElements() = runTest {
        val reply = mapOf("chatId" to 5L, "message" to mapOf("id" to 9L, "time" to 1L, "type" to "USER", "text" to "bold"))
        val sink = ScriptSink(reply, mapOf("message" to mapOf("id" to 9L, "time" to 1L, "type" to "USER", "text" to "bold!", "status" to "EDITED")))
        val api = MessagesApi(sink, clock = { 1_000L })
        val elements = TextElement.payloadFor("bold", listOf(TextElement.strong(0, 4)))
        api.sendMessage(5, "bold", elements = elements)
        api.editMessage(5, 9, "bold!", elements = TextElement.payloadFor("bold!", listOf(TextElement.strong(0, 4))))
        assertEquals(listOf(Opcode.MSG_SEND, Opcode.MSG_EDIT), sink.opcodes)
        val sentMessage = (sink.sent[0].second as Map<*, *>)["message"] as Map<*, *>
        assertEquals(listOf(mapOf("type" to "STRONG", "from" to 0, "length" to 4)), sentMessage["elements"])
        assertEquals(listOf(mapOf("type" to "STRONG", "from" to 0, "length" to 4)), (sink.sent[1].second as Map<*, *>)["elements"])
    }

    @Test
    fun parsingFollowsTheWebClientRules() {
        val raw = listOf(
            mapOf("type" to "STRONG", "length" to 3), // no from: 0
            mapOf("type" to "EMPHASIZED", "from" to 4), // no length: to the end
            mapOf("type" to "UNDERLINE", "from" to 2, "length" to 0), // zero length: dropped
            mapOf("type" to "CODE", "from" to 0, "length" to 2), // read as monospaced
            mapOf("type" to "FUTURE_TYPE", "from" to 1, "length" to 1), // kept
            mapOf("type" to "QUOTE", "from" to 9), // starts at the end: nothing left
        )
        val parsed = TextElement.parseAll(raw, textLength = 9)
        assertEquals(listOf("STRONG", "EMPHASIZED", "MONOSPACED", "FUTURE_TYPE"), parsed.map { it.type })
        assertEquals(0, parsed[0].from)
        assertEquals(4 to 5, parsed[1].from to parsed[1].length)
        // without the text length an open element cannot be placed
        assertEquals(listOf("STRONG", "MONOSPACED", "FUTURE_TYPE"), TextElement.parseAll(raw).map { it.type })
        // offsets are UTF-16 code units: an emoji is two
        val text = "😀 bold"
        val message = MaxMessage.from(mapOf("id" to 1L, "time" to 1L, "type" to "USER", "text" to text, "elements" to listOf(mapOf("type" to "STRONG", "from" to 3))))!!
        assertEquals(TextElement(TextElementType.STRONG, 3, 4), message.textElements.single())
        assertEquals("bold", text.substring(3, 7))
    }
}

class TextElementsJsonTest {
    @Test
    fun jsonRoundTripAndWebRules() {
        val text = "👋 hi there"
        val elements = listOf(TextElement.strong(0, 2), TextElement.link(3, 2, "https://max.ru"), TextElement.mention(6, 5, 42))
        val json = TextElementsJson.write(elements)
        kotlin.test.assertEquals(
            """[{"type":"STRONG","from":0,"length":2},{"type":"LINK","from":3,"length":2,"attributes":{"url":"https://max.ru"}},{"type":"USER_MENTION","from":6,"length":5,"entityId":42}]""",
            json,
        )
        kotlin.test.assertEquals(elements, TextElementsJson.parse(json, text.length))
        // CODE reads as monospaced, open length runs to the end, empty ones go, entityId as a string
        val read = TextElementsJson.parse(
            """[{"type":"CODE","from":3},{"type":"STRONG","from":1,"length":0},{"type":"USER_MENTION","length":2,"entityId":"7"},{"type":"SPOILER","from":0,"length":1}]""",
            text.length,
        )
        kotlin.test.assertEquals(
            listOf(
                TextElement(TextElementType.MONOSPACED, 3, text.length - 3),
                TextElement(TextElementType.USER_MENTION, 0, 2, entityId = 7),
                TextElement("SPOILER", 0, 1),
            ),
            read,
        )
        kotlin.test.assertEquals(emptyList(), TextElementsJson.parse("  "))
        kotlin.test.assertEquals(emptyList(), TextElementsJson.parse("[]"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { TextElementsJson.parse("{\"type\":\"STRONG\"}") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { TextElementsJson.parse("[{") }
    }
}
