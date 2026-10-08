package com.max.core.api

import com.max.core.events.EventParser
import com.max.core.events.MaxEvent
import com.max.core.protocol.Opcode
import com.max.core.state.MaxState
import com.max.core.state.MaxStore
import com.max.core.state.StateReducer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pushes 152 / 153 (drafts changed on another device) and the presence request 35. */
class DraftPushesTest {
    private val me = 10L
    private val dialogId = 10L xor 20L

    private fun seeded(): MaxStore {
        val store = MaxStore(clock = { 0L })
        store.applyLogin(com.max.core.auth.LoginResult.from(mapOf("profile" to mapOf("contact" to mapOf("id" to me)))))
        store.putDraft(MaxDraft(-70, "ours", emptyList(), null, 1_000))
        return store
    }

    @Test
    fun savedPushIsParsedTolerantly() {
        val e = assertIs<MaxEvent.DraftSaved>(
            EventParser.parse(
                152, 0,
                mapOf("chatId" to -70L, "draft" to mapOf("text" to "hi", "elements" to listOf(mapOf("type" to "STRONG", "from" to 0, "length" to 2)), "replyTo" to "55", "updateTime" to 2_000L, "attaches" to listOf(mapOf("_type" to "PHOTO")))),
            ),
        )
        assertEquals(-70L, e.chatId)
        assertEquals(2_000L, e.time)
        assertEquals(55L, e.replyTo)
        val d = e.toDraft(-70)
        assertEquals(listOf(TextElement("STRONG", 0, 2)), d.elements)
        assertEquals(listOf<Map<*, *>>(mapOf("_type" to "PHOTO")), d.attaches)
        // userId form, time beside the draft, string ids
        val u = assertIs<MaxEvent.DraftSaved>(EventParser.parse(152, 0, mapOf("userId" to "20", "time" to 3_000L, "draft" to mapOf("text" to "x"))))
        assertEquals(dialogId, u.targetChatId(me))
        assertNull(u.targetChatId(null))
        assertEquals(3_000L, u.time)
        // saveTime, as in LOGIN drafts
        assertEquals(4_000L, assertIs<MaxEvent.DraftSaved>(EventParser.parse(152, 0, mapOf("chatId" to 1, "draft" to mapOf("saveTime" to 4_000L)))).time)
    }

    @Test
    fun garbageStaysUnknown() {
        val bad = listOf(
            null, "x", emptyMap<String, Any?>(),
            mapOf("draft" to mapOf("text" to "no address", "time" to 1L)),
            mapOf("chatId" to 1L, "draft" to "not a map"),
            mapOf("chatId" to 1L, "draft" to mapOf("text" to "no time")),
            mapOf("chatId" to "abc", "draft" to mapOf("time" to 1L)),
            mapOf("chatId" to 1L, "draft" to mapOf("time" to 1L, "elements" to "garbage", "text" to 5)),
        )
        for (p in bad.dropLast(1)) assertIs<MaxEvent.Unknown>(EventParser.parse(152, 0, p), "$p")
        // bad elements / text are dropped, the draft itself still reads
        assertIs<MaxEvent.DraftSaved>(EventParser.parse(152, 0, bad.last()))
        for (p in listOf(null, mapOf("chatId" to 1L), mapOf("time" to 5L), mapOf("userId" to 2L, "time" to "x"))) {
            assertIs<MaxEvent.Unknown>(EventParser.parse(153, 0, p), "$p")
        }
        assertEquals(MaxEvent.DraftDiscarded(null, 20, 7, 153, mapOf("userId" to 20L, "time" to 7L)), EventParser.parse(153, 0, mapOf("userId" to 20L, "time" to 7L)))
        // an unknown event leaves the store alone
        val store = seeded()
        val before = store.state.value
        bad.forEach { store.apply(EventParser.parse(152, 0, it)) }
        assertEquals(before.drafts, store.state.value.drafts.filterKeys { it == -70L })
    }

    @Test
    fun laterSaveReplacesOlderOrEqualIsIgnored() {
        val store = seeded()
        store.apply(EventParser.parse(152, 0, mapOf("chatId" to -70L, "draft" to mapOf("text" to "older", "time" to 999L))))
        assertEquals("ours", store.state.value.drafts[-70L]?.text)
        store.apply(EventParser.parse(152, 0, mapOf("chatId" to -70L, "draft" to mapOf("text" to "same time", "time" to 1_000L))))
        assertEquals("ours", store.state.value.drafts[-70L]?.text)
        store.apply(EventParser.parse(152, 0, mapOf("chatId" to -70L, "draft" to mapOf("text" to "phone", "time" to 1_001L))))
        assertEquals(MaxDraft(-70, "phone", emptyList(), null, 1_001), store.state.value.drafts[-70L]?.copy(raw = emptyMap<Any?, Any?>()))
        // a dialog by userId lands on the dialog chat
        store.apply(EventParser.parse(152, 0, mapOf("userId" to 20L, "draft" to mapOf("text" to "", "replyTo" to 5L, "time" to 10L))))
        val reply = store.state.value.drafts[dialogId]!!
        assertEquals("", reply.text)
        assertEquals(5L, reply.replyTo)
    }

    @Test
    fun discardAppliesUnlessOursIsNewer() {
        val store = seeded()
        store.apply(EventParser.parse(153, 0, mapOf("chatId" to -70L, "time" to 999L)))
        assertEquals("ours", store.state.value.drafts[-70L]?.text)
        store.apply(EventParser.parse(153, 0, mapOf("chatId" to -70L, "time" to 1_000L)))
        assertNull(store.state.value.drafts[-70L])
        store.putDraft(MaxDraft(-70, "again", emptyList(), null, 2_000))
        store.apply(EventParser.parse(153, 0, mapOf("chatId" to -70L, "time" to 3_000L)))
        assertNull(store.state.value.drafts[-70L])
        // nothing stored: no-op
        store.apply(EventParser.parse(153, 0, mapOf("userId" to 20L, "time" to 3_000L)))
        assertTrue(store.state.value.drafts.isEmpty())
    }

    @Test
    fun anEmptySavedDraftIsADiscard() {
        val store = seeded()
        store.apply(EventParser.parse(152, 0, mapOf("chatId" to -70L, "draft" to mapOf("text" to "  ", "time" to 900L))))
        assertEquals("ours", store.state.value.drafts[-70L]?.text) // older: ignored
        store.apply(EventParser.parse(152, 0, mapOf("chatId" to -70L, "draft" to mapOf("text" to "  ", "time" to 1_500L))))
        assertNull(store.state.value.drafts[-70L])
        // and an empty saved draft of LOGIN is no draft, but a discard mark at its time
        val s = StateReducer.putDrafts(MaxState(), Drafts.fromLogin(mapOf("drafts" to mapOf("chats" to mapOf("saved" to mapOf("-5" to mapOf("text" to " ", "saveTime" to 1L))))), me))
        assertTrue(s.drafts.isEmpty())
        assertEquals(mapOf(-5L to 1L), s.draftDiscards)
    }

    @Test
    fun dialogPushesWaitForTheOwnId() {
        val s = StateReducer.reduce(MaxState(), EventParser.parse(152, 0, mapOf("userId" to 20L, "draft" to mapOf("text" to "x", "time" to 1L))), 0)
        assertTrue(s.drafts.isEmpty())
    }

    @Test
    fun unknownChatsAreAddressedByTheirId() {
        assertEquals(DraftAddress(null, 20), Drafts.address(dialogId, null, me))
        assertEquals(DraftAddress(null, me), Drafts.address(0, null, me))
        assertEquals(DraftAddress(-70, null), Drafts.address(-70, null, me))
        assertEquals(DraftAddress(dialogId, null), Drafts.address(dialogId, null, null))
    }

    @Test
    fun presenceRequestsGoInBatchesOf100() = runTest {
        val ids = (1L..150L).toList()
        val sink = ScriptSink(
            mapOf("presence" to mapOf("1" to mapOf("seen" to 5L, "status" to 1))),
            mapOf("presence" to mapOf(150L to mapOf("seen" to 6L))),
        )
        val got = UsersApi(sink).getPresence(ids + 1L)
        assertEquals(listOf(Opcode.CONTACT_PRESENCE, Opcode.CONTACT_PRESENCE), sink.opcodes)
        assertEquals(35, Opcode.CONTACT_PRESENCE.value)
        assertEquals(mapOf("contactIds" to (1L..100L).toList()), sink.sent[0].second)
        assertEquals(mapOf("contactIds" to (101L..150L).toList()), sink.sent[1].second)
        assertEquals(150, got.size)
        assertEquals(PresenceInfo(5, 1), got[1L])
        assertEquals(PresenceInfo(6, null), got[150L])
        assertEquals(PresenceInfo(null, PresenceStatus.LONG_AGO), got[2L])
    }
}
