package com.maxly.core.api

import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.Opcode
import com.maxly.core.state.MaxState
import com.maxly.core.state.MaxStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Who read this message": merging marks, availability and the list order. */
class MessageReadersTest {
    private val me = 1L
    private val author = 2L

    private fun chatMap(
        id: Long = 100,
        type: String = "CHAT",
        count: Int? = null,
        participants: Map<Any, Any?> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to id, "type" to type, "status" to "ACTIVE", "participants" to participants)
        if (count != null) m["participantsCount"] = count
        m.putAll(extra)
        return m
    }

    private fun chat(
        type: String = "CHAT",
        count: Int? = null,
        participants: Map<Any, Any?> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ): Chat = Chat.from(chatMap(type = type, count = count, participants = participants, extra = extra))!!

    // ---- merge ------------------------------------------------------------------------------------

    @Test
    fun laterMarkWinsWhenMerging() {
        val server = mapOf(5L to 1_000L, 6L to 3_000L)
        assertEquals(server, MessageReaders.mergeMarks(server, null))
        assertEquals(server, MessageReaders.mergeMarks(server, emptyMap()))
        assertEquals(
            mapOf(5L to 2_000L, 6L to 3_000L, 7L to 500L),
            MessageReaders.mergeMarks(server, mapOf(5L to 2_000L, 6L to 2_500L, 7L to 500L)),
        )
    }

    @Test
    fun stateMergesStoredParticipantsWithPushedMarks() {
        val store = MaxStore()
        store.putChats(listOf(chat(participants = mapOf(5L to 1_000L, 6L to 3_000L))))
        store.apply(MaxEvent.MessageRead(100, 5, 2_000, false, Opcode.NOTIF_MARK.value, null))
        store.apply(MaxEvent.MessageRead(100, 6, 2_500, false, Opcode.NOTIF_MARK.value, null))
        store.apply(MaxEvent.MessageRead(100, 7, 900, false, Opcode.NOTIF_MARK.value, null))
        val s = store.state.value
        assertEquals(mapOf(5L to 2_000L, 6L to 3_000L, 7L to 900L), s.chatReadMarks(100))
        // fresher server marks passed in explicitly
        assertEquals(mapOf(5L to 4_000L, 7L to 900L, 6L to 2_500L), s.chatReadMarks(100, mapOf(5L to 4_000L)))
        assertEquals(emptyMap(), MaxState().chatReadMarks(100))
    }

    // ---- availability -----------------------------------------------------------------------------

    @Test
    fun onlySmallGroupsWithoutACallShowReaders() {
        assertTrue(MessageReaders.isAvailable(chat(count = 3)))
        assertTrue(MessageReaders.isAvailable(chat(count = 100)))
        assertFalse(MessageReaders.isAvailable(chat(count = 101)))
        assertTrue(MessageReaders.isAvailable(chat(count = 101), maxReadmarks = 200))
        assertFalse(MessageReaders.isAvailable(chat(count = 11), maxReadmarks = 10))
        // no count: the participants map decides
        assertTrue(MessageReaders.isAvailable(chat(participants = mapOf(1L to 1L, 2L to 2L)), maxReadmarks = 2))
        assertFalse(MessageReaders.isAvailable(chat(participants = mapOf(1L to 1L, 2L to 2L, 3L to 3L)), maxReadmarks = 2))

        assertFalse(MessageReaders.isAvailable(chat(type = "DIALOG", count = 2)))
        assertFalse(MessageReaders.isAvailable(chat(type = "CHANNEL", count = 5)))
        assertFalse(MessageReaders.isAvailable(chat(type = "GROUP", count = 5)))

        assertFalse(MessageReaders.isAvailable(chat(count = 3, extra = mapOf("videoConversation" to mapOf("conversationId" to "c1")))))
        assertFalse(MessageReaders.isAvailable(chat(count = 3, extra = mapOf("videoConversation" to true))))
        assertTrue(MessageReaders.isAvailable(chat(count = 3, extra = mapOf("videoConversation" to null))))
        assertTrue(MessageReaders.isAvailable(chat(count = 3, extra = mapOf("videoConversation" to false))))
        assertTrue(MessageReaders.isAvailable(chat(count = 3, extra = mapOf("videoConversation" to emptyMap<String, Any?>()))))
    }

    // ---- build ------------------------------------------------------------------------------------

    @Test
    fun reactionsFirstThenReadersByMarkWithTiesByUserId() {
        val marks = mapOf(
            10L to 1_000L, // exactly the message time: read
            11L to 999L, // before the message: not read
            12L to 5_000L,
            13L to 3_000L,
            14L to 3_000L,
            9L to 3_000L,
            20L to 4_000L, // reacted too
        )
        val reactions = listOf(ReactionUser(21, "🔥"), ReactionUser(20, "👍"), ReactionUser(11, "❤️"))
        val readers = MessageReaders.build(1_000, author, me, marks, reactions)
        assertEquals(
            listOf(
                MessageReader(21, null, "🔥"),
                MessageReader(20, 4_000, "👍"),
                MessageReader(11, null, "❤️"), // reacted with an older mark: listed, mark not shown
                MessageReader(12, 5_000, null),
                MessageReader(9, 3_000, null),
                MessageReader(13, 3_000, null),
                MessageReader(14, 3_000, null),
                MessageReader(10, 1_000, null),
            ),
            readers,
        )
    }

    @Test
    fun meAndTheAuthorAreNeverListed() {
        val marks = mapOf(me to 9_000L, author to 9_000L, 5L to 9_000L)
        val reactions = listOf(ReactionUser(me, "👍"), ReactionUser(author, "❤️"), ReactionUser(6, "😂"))
        assertEquals(
            listOf(MessageReader(6, null, "😂"), MessageReader(5, 9_000, null)),
            MessageReaders.build(1_000, author, me, marks, reactions),
        )
        // unknown author and me: nobody is excluded
        assertEquals(listOf(1L, 2L, 5L), MessageReaders.build(1_000, null, null, marks, emptyList()).map { it.userId }.sorted())
    }

    @Test
    fun eachUserAppearsOnce() {
        val reactions = listOf(ReactionUser(5, "👍"), ReactionUser(5, "❤️"), ReactionUser(6, "🔥"))
        val readers = MessageReaders.build(1_000, author, me, mapOf(5L to 2_000L, 6L to 1_500L, 7L to 1_200L), reactions)
        assertEquals(listOf(MessageReader(5, 2_000, "👍"), MessageReader(6, 1_500, "🔥"), MessageReader(7, 1_200, null)), readers)
    }

    @Test
    fun withoutReactionsOnlyReadersRemain() {
        val readers = MessageReaders.build(1_000, author, me, mapOf(5L to 2_000L, 6L to 900L), emptyList())
        assertEquals(listOf(MessageReader(5, 2_000, null)), readers)
        assertEquals(emptyList(), MessageReaders.build(1_000, author, me, emptyMap(), emptyList()))
    }
}
