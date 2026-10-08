package com.max.core.api

import com.max.core.auth.LoginResult
import com.max.core.protocol.Opcode
import com.max.core.state.MaxState
import com.max.core.state.StateReducer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Server drafts: `DRAFT_SAVE` 176, `DRAFT_DISCARD` 177 and `drafts` of the `LOGIN` reply. */
class DraftsTest {
    private val me = 10L
    private val dialog = Chat.from(mapOf("id" to (10L xor 20L), "type" to "DIALOG", "participants" to mapOf("10" to 0, "20" to 0)))!!
    private val saved = Chat.from(mapOf("id" to 0L, "type" to "DIALOG", "participants" to mapOf("10" to 0)))!!
    private val group = Chat.from(mapOf("id" to -70L, "type" to "CHAT"))!!

    @Test
    fun dialogsAreAddressedByThePeerAndGroupsByChatId() {
        assertEquals(DraftAddress(null, 20), Drafts.address(dialog.id, dialog, me))
        assertEquals(DraftAddress(null, 10), Drafts.address(0, saved, me))
        assertEquals(DraftAddress(-70, null), Drafts.address(-70, group, me))
        assertEquals(DraftAddress(-70, null), Drafts.address(-70, null, me))
        // a dialog without participants: the peer from the chat id
        val bare = Chat.from(mapOf("id" to (10L xor 30L), "type" to "DIALOG"))!!
        assertEquals(DraftAddress(null, 30), Drafts.address(bare.id, bare, me))
        assertFailsWith<IllegalArgumentException> { DraftAddress(1, 2) }
    }

    @Test
    fun saveAndDiscardPayloads() = runTest {
        val sink = ScriptSink(mapOf("time" to 1_700L), emptyMap<String, Any?>(), mapOf("time" to 1_800L), mapOf("x" to 1))
        val api = DraftsApi(sink)
        val time = api.saveDraft(DraftAddress(null, 20), "hello", listOf(TextElement.strong(0, 5), TextElement.strong(3, 9)), replyTo = 55)
        assertEquals(1_700L, time)
        assertEquals(Opcode.DRAFT_SAVE, sink.opcodes[0])
        assertEquals(
            mapOf("userId" to 20L, "draft" to mapOf("text" to "hello", "elements" to listOf(mapOf("type" to "STRONG", "from" to 0, "length" to 5)), "replyTo" to 55L)),
            sink.sent[0].second,
        )
        api.discardDraft(DraftAddress(-70, null), 1_700)
        assertEquals(Opcode.DRAFT_DISCARD, sink.opcodes[1])
        assertEquals(mapOf("chatId" to -70L, "time" to 1_700L), sink.sent[1].second)
        // an empty text is left out, the elements are always there
        api.saveDraft(DraftAddress(-70, null), "")
        assertEquals(mapOf("chatId" to -70L, "draft" to mapOf("elements" to emptyList<Any?>())), sink.sent[2].second)
        assertFailsWith<MalformedReplyException> { api.saveDraft(DraftAddress(-70, null), "x") }
    }

    @Test
    fun loginDraftsAreKeptByTheStoreRule() {
        val drafts = mapOf(
            "chats" to mapOf(
                "saved" to mapOf("-70" to mapOf("saveTime" to 500L, "text" to "group draft", "elements" to listOf(mapOf("type" to "EMPHASIZED", "from" to 6)))),
                "discarded" to mapOf("-80" to 400L),
            ),
            "users" to mapOf(
                "saved" to mapOf("20" to mapOf("saveTime" to 600L, "text" to "to peer", "replyTo" to 9L), "30" to mapOf("text" to "no time")),
                "discarded" to mapOf("10" to 700L),
            ),
        )
        val snapshot = Drafts.fromLogin(mapOf("drafts" to drafts), me)
        assertEquals(setOf(-70L, 10L xor 20L), snapshot.saved.keys)
        assertEquals(mapOf(-80L to 400L, 0L to 700L), snapshot.discarded)
        val g = snapshot.saved.getValue(-70)
        assertEquals("group draft", g.text)
        assertEquals(TextElement(TextElementType.EMPHASIZED, 6, 5), g.elements.single())
        assertEquals(9L, snapshot.saved.getValue(10L xor 20L).replyTo)
        assertEquals(DraftsSnapshot.EMPTY, Drafts.fromLogin(emptyMap<String, Any?>(), me))

        // the store: newer or equal replaces, older is ignored; a discard removes an older draft
        var s = MaxState(me = me)
        s = StateReducer.putDraft(s, MaxDraft(-80, "old", emptyList(), null, 300))
        s = StateReducer.putDraft(s, MaxDraft(-70, "newer local", emptyList(), null, 900))
        s = StateReducer.putDraft(s, MaxDraft(0, "saved msgs", emptyList(), null, 800))
        val profile = mapOf("contact" to mapOf("id" to me))
        val login = LoginResult(profile, me, emptyList(), null, 1_000L, null, null, mapOf("profile" to profile, "drafts" to drafts))
        s = StateReducer.login(s, login)
        assertEquals("newer local", s.draftOf(-70)?.text)
        assertNull(s.draftOf(-80)) // discarded at 400 > 300
        assertEquals("saved msgs", s.draftOf(0)?.text) // discarded at 700 < 800: kept
        assertEquals("to peer", s.draftOf(10L xor 20L)?.text)
        assertEquals(900L, s.draftsSyncTime)
        s = StateReducer.removeDraft(s, -70)
        assertNull(s.draftOf(-70))
        assertEquals(-1L, MaxState().draftsSyncTime)

        // another account: drafts of the old one are gone
        val otherProfile = mapOf("contact" to mapOf("id" to 11L))
        val other = LoginResult(otherProfile, 11L, emptyList(), null, 1_000L, null, null, mapOf("profile" to otherProfile))
        assertEquals(emptyMap(), StateReducer.login(s, other).drafts)
    }
}
