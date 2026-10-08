package com.max.core.api

import com.max.core.auth.LoginResult
import com.max.core.events.EventParser
import com.max.core.state.MaxState
import com.max.core.state.MaxStore
import com.max.core.state.StateReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Discard marks: the time of the latest known discard of a chat's draft (`MaxState.draftDiscards`). */
class DraftDiscardMarksTest {
    private val me = 10L
    private val dialogId = 10L xor 20L

    private fun store(): MaxStore = MaxStore(clock = { 0L }).also {
        it.applyLogin(LoginResult.from(mapOf("profile" to mapOf("contact" to mapOf("id" to me)))))
    }

    private fun saved(chatId: Long, text: String, time: Long) =
        EventParser.parse(152, 0, mapOf("chatId" to chatId, "draft" to mapOf("text" to text, "time" to time)))

    private fun discarded(chatId: Long, time: Long) = EventParser.parse(153, 0, mapOf("chatId" to chatId, "time" to time))

    @Test
    fun aDiscardWithoutAStoredDraftIsRemembered() {
        val s = store()
        s.apply(discarded(-70, 1_500))
        assertEquals(1_500L, s.state.value.draftDiscardedAt(-70))
        assertNull(s.state.value.draftOf(-70))
        // an older discard does not move the mark back, a later one moves it on
        s.apply(discarded(-70, 1_200))
        assertEquals(1_500L, s.state.value.draftDiscardedAt(-70))
        s.apply(discarded(-70, 1_800))
        assertEquals(1_800L, s.state.value.draftDiscardedAt(-70))
        // dialogs by userId land on the dialog chat
        s.apply(EventParser.parse(153, 0, mapOf("userId" to 20L, "time" to 7L)))
        assertEquals(7L, s.state.value.draftDiscardedAt(dialogId))
    }

    @Test
    fun onlyAStrictlyLaterDraftReplacesTheMark() {
        val s = store()
        s.apply(discarded(-70, 2_000))
        s.apply(saved(-70, "older", 1_999))
        s.apply(saved(-70, "same time", 2_000))
        assertNull(s.state.value.draftOf(-70))
        assertEquals(2_000L, s.state.value.draftDiscardedAt(-70))
        s.apply(saved(-70, "later", 2_001))
        assertEquals("later", s.state.value.draftOf(-70)?.text)
        assertNull(s.state.value.draftDiscardedAt(-70))
    }

    @Test
    fun aDiscardOlderThanTheStoredDraftLeavesNoMark() {
        val s = store()
        s.putDraft(MaxDraft(-70, "ours", emptyList(), null, 3_000))
        s.apply(discarded(-70, 2_000))
        assertEquals("ours", s.state.value.draftOf(-70)?.text)
        assertNull(s.state.value.draftDiscardedAt(-70))
        // equal time: the discard wins
        s.apply(discarded(-70, 3_000))
        assertNull(s.state.value.draftOf(-70))
        assertEquals(3_000L, s.state.value.draftDiscardedAt(-70))
    }

    @Test
    fun anEmptyPush152IsADiscardMark() {
        val s = store()
        s.apply(saved(-70, " ", 900))
        assertEquals(900L, s.state.value.draftDiscardedAt(-70))
    }

    @Test
    fun ownSaveClearsTheMarkAndOwnDiscardSetsIt() {
        val s = store()
        s.apply(discarded(-70, 5_000))
        s.putDraft(MaxDraft(-70, "typed", emptyList(), null, 4_000))
        assertEquals("typed", s.state.value.draftOf(-70)?.text)
        assertNull(s.state.value.draftDiscardedAt(-70))
        s.removeDraft(-70, 4_000)
        assertNull(s.state.value.draftOf(-70))
        assertEquals(4_000L, s.state.value.draftDiscardedAt(-70))
        // without a time only the draft goes
        s.putDraft(MaxDraft(-71, "x", emptyList(), null, 1))
        s.removeDraft(-71)
        assertNull(s.state.value.draftOf(-71))
        assertNull(s.state.value.draftDiscardedAt(-71))
    }

    @Test
    fun takeDraftMarksTheChatAtTheDraftsTime() {
        val s = store()
        s.putDraft(MaxDraft(-70, "sent", emptyList(), null, 6_000))
        assertEquals("sent", s.takeDraft(-70)?.text)
        assertEquals(6_000L, s.state.value.draftDiscardedAt(-70))
        assertNull(s.takeDraft(-70))
    }

    @Test
    fun loginDiscardedEntriesAndEmptySavedDraftsBecomeMarks() {
        val reply = mapOf(
            "drafts" to mapOf(
                "chats" to mapOf(
                    "saved" to mapOf("-5" to mapOf("text" to " ", "saveTime" to 11L), "-6" to mapOf("text" to "kept", "saveTime" to 30L)),
                    "discarded" to mapOf("-7" to 12L, "-6" to 20L),
                ),
                "users" to mapOf("discarded" to mapOf("20" to 13L)),
            ),
        )
        val s = StateReducer.putDrafts(MaxState(me = me), Drafts.fromLogin(reply, me))
        assertEquals(mapOf(-5L to 11L, -7L to 12L, dialogId to 13L), s.draftDiscards)
        assertEquals("kept", s.draftOf(-6)?.text)
        assertEquals(30L, s.draftsSyncTime)
        // a later LOGIN with a saved draft not newer than the mark keeps the mark
        val again = StateReducer.putDrafts(s, Drafts.fromLogin(mapOf("drafts" to mapOf("chats" to mapOf("saved" to mapOf("-7" to mapOf("text" to "old", "saveTime" to 12L))))), me))
        assertNull(again.draftOf(-7))
        assertEquals(12L, again.draftDiscardedAt(-7))
    }

    @Test
    fun anotherAccountAndClearDropTheMarks() {
        val s = store()
        s.apply(discarded(-70, 1))
        s.applyLogin(LoginResult.from(mapOf("profile" to mapOf("contact" to mapOf("id" to 99L)))))
        assertEquals(emptyMap(), s.state.value.draftDiscards)
        s.apply(discarded(-70, 1))
        s.clear()
        assertEquals(emptyMap(), s.state.value.draftDiscards)
    }

    @Test
    fun aRepeatedDiscardKeepsTheSameState() {
        val s = StateReducer.discardDraft(MaxState(), -70, 5)
        assertSame(s, StateReducer.discardDraft(s, -70, 5))
        assertSame(s, StateReducer.discardDraft(s, -70, 4))
    }

    @Test
    fun reconcileFollowsTheSharedRule() {
        val local = MaxDraft(-70, "mine", emptyList(), null, 1_000)
        val server = MaxDraft(-70, "phone", emptyList(), null, 2_000)
        assertEquals(server, Drafts.reconcile(local, server, null))
        assertEquals(local, Drafts.reconcile(local, server.copy(updateTime = 1_000), null))
        assertNull(Drafts.reconcile(local, null, 1_500))
        assertNull(Drafts.reconcile(null, server, 2_000))
        assertEquals(server, Drafts.reconcile(local, server, 1_999))
        assertNull(Drafts.reconcile(local.copy(text = " "), null, null))
        assertEquals(local.copy(text = "", replyTo = 5), Drafts.reconcile(local.copy(text = "", replyTo = 5), null, null))
    }
}
