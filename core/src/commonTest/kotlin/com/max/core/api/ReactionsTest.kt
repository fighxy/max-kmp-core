package com.max.core.api

import com.max.core.auth.LoginResult
import com.max.core.events.EventParser
import com.max.core.events.MaxEvent
import com.max.core.media.reactionsJson
import com.max.core.protocol.Opcode
import com.max.core.state.MaxStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reactions: who reacted (181), the animoji catalog (27/28), the push (155), the store and the app JSON. */
class ReactionsTest {
    private fun thumbs(count: Int) = ReactionCounter("👍", count)

    @Test
    fun detailedReactionsSendKometPayloadAndSkipBrokenEntries() = runTest {
        val sink = ScriptSink(
            mapOf(
                "reactions" to listOf(
                    mapOf("userId" to 5L, "reaction" to "👍"),
                    mapOf("userId" to "6", "reaction" to "❤️"),
                    mapOf("userId" to 7L, "reaction" to ""),
                    mapOf("reaction" to "🔥"),
                    "junk",
                ),
            ),
            emptyMap<String, Any?>(),
        )
        val api = MaxApi(sink).messages
        assertEquals(listOf(ReactionUser(5, "👍"), ReactionUser(6, "❤️")), api.getDetailedReactions(100, 10))
        assertEquals(Opcode.MSG_GET_DETAILED_REACTIONS, sink.opcodes[0])
        assertEquals(msgpackHex(linkedMapOf("chatId" to 100L, "messageId" to 10L, "count" to 100)), sink.hex(0))
        assertEquals(emptyList(), api.getDetailedReactions(100, 10, count = 5))
        assertEquals(5, (sink.sent[1].second as Map<*, *>)["count"])
        assertFailsWith<IllegalArgumentException> { api.getDetailedReactions(100, 10, count = 0) }
    }

    @Test
    fun catalogWalksSetsThenAnimojiInBatches() = runTest {
        val ids = (1L..150L).toList()
        val sink = ScriptSink(
            mapOf("sections" to listOf(mapOf("animojiSetIds" to listOf(7L)), mapOf("animojiSetIds" to listOf(8L, 7L)))),
            mapOf("animojiSets" to listOf(mapOf("id" to 7L, "animojis" to ids.take(120)), mapOf("id" to 8L, "animojiIds" to ids.drop(100)))),
            mapOf("animojis" to ids.take(100).map { mapOf("id" to it, "emoji" to if (it == 2L) "👍" else "e$it", "setId" to 7L, "iconUrl" to "i$it") }),
            mapOf("animojis" to ids.drop(100).map { mapOf("id" to it, "emoji" to if (it == 101L) "👍" else "e$it") } + mapOf("emoji" to "no id")),
        )
        val catalog = MaxApi(sink).assets.reactionCatalog()
        assertEquals(
            listOf(Opcode.ASSETS_UPDATE, Opcode.ASSETS_GET_BY_IDS, Opcode.ASSETS_GET_BY_IDS, Opcode.ASSETS_GET_BY_IDS),
            sink.opcodes,
        )
        assertEquals(msgpackHex(linkedMapOf("type" to "ANIMOJI_SET", "sync" to 0)), sink.hex(0))
        assertEquals(msgpackHex(linkedMapOf("type" to "ANIMOJI_SET", "ids" to listOf(7L, 8L))), sink.hex(1))
        assertEquals(msgpackHex(linkedMapOf("type" to "ANIMOJI", "ids" to ids.take(100))), sink.hex(2))
        assertEquals(msgpackHex(linkedMapOf("type" to "ANIMOJI", "ids" to ids.drop(100))), sink.hex(3))
        // catalog order, one entry per emoji (101 repeats 👍 of 2)
        assertEquals(149, catalog.size)
        assertEquals(Animoji(1, "e1", 7, "i1", null), catalog[0])
        assertEquals("👍", catalog[1].emoji)
        assertFalse(catalog.any { it.id == 101L })
    }

    @Test
    fun catalogFallsBackToLooseIdsAndMayBeEmpty() = runTest {
        val sink = ScriptSink(
            mapOf("animojiUpdates" to mapOf("3" to 1, 4L to 1)),
            mapOf("animojis" to listOf(mapOf("id" to 4L, "emoji" to "🔥"), mapOf("id" to 3L, "emoji" to "❤️"))),
        )
        assertEquals(listOf("❤️", "🔥"), MaxApi(sink).assets.reactionCatalog().map { it.emoji })
        // no set request without sets
        assertEquals(listOf(Opcode.ASSETS_UPDATE, Opcode.ASSETS_GET_BY_IDS), sink.opcodes)
        assertEquals(msgpackHex(linkedMapOf("type" to "ANIMOJI", "ids" to listOf(3L, 4L))), sink.hex(1))

        val empty = ScriptSink(emptyMap<String, Any?>())
        assertEquals(emptyList(), MaxApi(empty).assets.reactionCatalog())
        assertEquals(listOf(Opcode.ASSETS_UPDATE), empty.opcodes)
    }

    @Test
    fun reactionInfoBuiltLocally() {
        val info = ReactionInfo.of(listOf(thumbs(2), ReactionCounter("❤️", 0), ReactionCounter("🔥", 1)), "👍")
        assertEquals(3, info.totalCount)
        assertEquals(listOf(thumbs(2), ReactionCounter("🔥", 1)), info.counters)
        assertEquals("👍", info.yourReaction)
        assertEquals(info, ReactionInfo.from(info.raw)!!.copy(raw = info.raw))
        // an own reaction without its counter is not kept
        assertNull(ReactionInfo.of(listOf(thumbs(1)), "❤️").yourReaction)

        val without = info.withoutOwn()
        assertEquals(listOf(thumbs(1), ReactionCounter("🔥", 1)), without.counters)
        assertNull(without.yourReaction)
        assertEquals(emptyList(), ReactionInfo.of(listOf(thumbs(1)), "👍").withoutOwn().counters)
        // nothing own: unchanged
        val theirs = ReactionInfo.of(listOf(thumbs(1)), null)
        assertEquals(theirs, theirs.withoutOwn())
    }

    @Test
    fun pushReadsAnOwnReactionOnlyWhenPresent() {
        val plain = assertIs<MaxEvent.ReactionsChanged>(
            EventParser.parse(155, 0, mapOf("chatId" to 1, "messageId" to 5L, "counters" to listOf(mapOf("reaction" to "👍", "count" to 1)), "totalCount" to 1)),
        )
        assertEquals("5", plain.messageId)
        assertNull(plain.yourReaction)
        val own = assertIs<MaxEvent.ReactionsChanged>(
            EventParser.parse(155, 0, mapOf("chatId" to 1, "messageId" to "5", "counters" to emptyList<Any?>(), "totalCount" to 0, "yourReaction" to "")),
        )
        assertNull(own.yourReaction)
        val mine = assertIs<MaxEvent.ReactionsChanged>(
            EventParser.parse(155, 0, mapOf("chatId" to 1, "messageId" to "5", "counters" to listOf(mapOf("reaction" to "🔥", "count" to 1)), "yourReaction" to "🔥")),
        )
        assertEquals("🔥", mine.yourReaction)
    }

    private fun storeWithMessage(reactionInfo: Map<String, Any?>?): MaxStore = MaxStore().also {
        val message = linkedMapOf<String, Any?>("id" to 5L, "chatId" to 1L, "sender" to 20L, "time" to 100L, "type" to "USER", "text" to "hi")
        if (reactionInfo != null) message["reactionInfo"] = reactionInfo
        it.applyLogin(
            LoginResult.from(
                mapOf(
                    "profile" to mapOf("contact" to mapOf("id" to 10L)),
                    "chats" to listOf(mapOf("id" to 1L, "type" to "CHAT", "status" to "ACTIVE", "lastEventTime" to 100L, "lastMessage" to message)),
                    "messages" to mapOf("1" to listOf(message)),
                ),
            ),
        )
    }

    private fun push(counters: List<Pair<String, Int>>, total: Int) = EventParser.parse(
        155, 0,
        mapOf("chatId" to 1L, "messageId" to "5", "counters" to counters.map { mapOf("reaction" to it.first, "count" to it.second) }, "totalCount" to total),
    )

    @Test
    fun pushKeepsTheOwnReactionWhileItsCounterStays() {
        val st = storeWithMessage(mapOf("counters" to listOf(mapOf("reaction" to "👍", "count" to 1)), "totalCount" to 1, "yourReaction" to "👍"))
        st.apply(push(listOf("👍" to 2, "🔥" to 1), 3))
        var info = st.state.value.messagesOf(1).single().reactionInfo!!
        assertEquals("👍", info.yourReaction)
        assertEquals(listOf(thumbs(2), ReactionCounter("🔥", 1)), info.counters)
        assertEquals("👍", info.raw["yourReaction"])
        assertEquals(info, st.state.value.chats.getValue(1).lastMessage!!.reactionInfo)

        // the own counter vanished (removed on another device): no own reaction any more
        st.apply(push(listOf("🔥" to 1), 1))
        info = st.state.value.messagesOf(1).single().reactionInfo!!
        assertNull(info.yourReaction)

        // no counters left: no reactions
        st.apply(push(emptyList(), 0))
        assertNull(st.state.value.messagesOf(1).single().reactionInfo)
        assertNull(st.state.value.chats.getValue(1).lastMessage!!.reactionInfo)
    }

    @Test
    fun putReactionsReplacesOnlyKnownMessages() {
        val st = storeWithMessage(null)
        st.putReactions(1, 5, ReactionInfo.of(listOf(thumbs(1)), "👍"))
        assertEquals("👍", st.state.value.messagesOf(1).single().reactionInfo!!.yourReaction)
        val before = st.state.value
        st.putReactions(1, 404, ReactionInfo.of(listOf(thumbs(1)), "👍"))
        st.putReactions(2, 5, ReactionInfo.of(listOf(thumbs(1)), "👍"))
        assertEquals(before, st.state.value)
        st.putReactions(1, 5, null)
        assertNull(st.state.value.messagesOf(1).single().reactionInfo)
    }

    @Test
    fun appJsonCarriesCountersAndTheOwnReactionWhenKnown() {
        val info = ReactionInfo.of(listOf(thumbs(2), ReactionCounter("🔥", 1)), "🔥")
        val known = Json.parseToJsonElement(reactionsJson(info)).jsonObject
        assertEquals(2, known.getValue("counters").jsonArray.size)
        assertEquals("👍", known.getValue("counters").jsonArray[0].jsonObject.getValue("reaction").jsonPrimitive.content)
        assertEquals("2", known.getValue("counters").jsonArray[0].jsonObject.getValue("count").jsonPrimitive.content)
        assertEquals("3", known.getValue("totalCount").jsonPrimitive.content)
        assertEquals("🔥", known.getValue("yourReaction").jsonPrimitive.content)

        val unknown = Json.parseToJsonElement(reactionsJson(info, mineKnown = false)).jsonObject
        assertFalse("yourReaction" in unknown)

        val none = Json.parseToJsonElement(reactionsJson(null)).jsonObject
        assertEquals(0, none.getValue("counters").jsonArray.size)
        assertEquals("0", none.getValue("totalCount").jsonPrimitive.content)
        assertEquals(JsonNull, none.getValue("yourReaction"))

        // a server total larger than the counters (a truncated list) is kept
        val truncated = ReactionInfo(10, listOf(thumbs(2)), null, emptyMap<Any?, Any?>())
        assertEquals("10", Json.parseToJsonElement(reactionsJson(truncated)).jsonObject.getValue("totalCount").jsonPrimitive.content)
        assertTrue(reactionsJson(ReactionInfo(1, listOf(ReactionCounter("👍", 0)), "👍", emptyMap<Any?, Any?>())).contains("\"counters\":[]"))
    }
}
