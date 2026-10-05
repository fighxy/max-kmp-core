package com.max.core.api

import com.max.core.media.Attachment
import com.max.core.media.OutgoingAttachment
import com.max.core.media.attachments
import com.max.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Delayed sending, polls and comments in [MessagesApi]; bytes from PyMax `pymax.api.messages.payloads` + msgpack-python. */
class CommentsPollsTest {
    private val now = 1759100000000L
    private val pymax = mapOf(
        "scheduled" to "83a663686174496464a76d65737361676585a474657874a56c61746572a3636964cf000001999287d701a8656c656d656e747390a8617474616368657390b164656c617965644174747269627574657382aa74696d65546f46697265cf00000199987db800ac6e6f7469667953656e646572c3a66e6f74696679c3",
        "comment" to "84a6636861744964d09ca76d65737361676585a474657874a46e696365a3636964cf000001999287d701a8656c656d656e747390a8617474616368657390a46c696e6b82a474797065a55245504c59a96d657373616765496409a66e6f74696679c3a6706f7374496407",
        "poll" to "83a663686174496464a76d65737361676583a3636964cf000001999287d701a8656c656d656e747390a861747461636865739184a57469746c65a2513fa7616e73776572739281a474657874a14181a474657874a142a873657474696e677303a55f74797065a4504f4c4ca66e6f74696679c3",
        "vote" to "84a663686174496464a96d657373616765496405a6706f6c6c496403aa616e7377657273496473920102",
        "getcomments" to "83a6636861744964d09caa6d6573736167654964739109a6706f7374496407",
        "editcomment" to "86a6636861744964d09ca96d657373616765496409a474657874a3757064a8656c656d656e747390ab6174746163686d656e747390a6706f7374496407",
        "delcomment" to "84a6636861744964d09caa6d6573736167654964739109a5666f724d65c2a6706f7374496407",
        "reactcomment" to "84a6636861744964d09ca96d657373616765496409a87265616374696f6e82ac7265616374696f6e54797065a5454d4f4a49a26964a4f09f918da6706f7374496407",
        "unreactcomment" to "83a6636861744964d09ca96d657373616765496409a6706f7374496407",
        "fetchcomments" to "8ba6636861744964d09ca7666f727761726400a86261636b776172641eac6261636b7761726454696d6500ab666f727761726454696d6500a767657443686174c2a466726f6dffa86974656d54797065a7524547554c4152ab6765744d65737361676573c3ab696e746572616374697665c2a6706f7374496407",
        "subscribe" to "83a6636861744964d09ca6706f7374496407a9737562736372696265c2",
        "commentsinfo" to "82a6636861744964d09ca7706f7374496473920708",
        "deluser" to "84a6636861744964d09ca6706f7374496407a675736572496401a96d657373616765496409",
    )

    private fun msg(id: Long, extra: Map<String, Any?> = emptyMap()) = mapOf("message" to mapOf("id" to id, "time" to now, "type" to "USER", "text" to "t") + extra)
    private fun messages(sink: ScriptSink) = MaxApi(sink) { now }.messages

    @Test
    fun scheduledAndPoll() = runTest {
        val sink = ScriptSink(msg(1), msg(2))
        val api = messages(sink)
        assertEquals(1L, api.scheduleMessage(100, "later", sendAt = 1759200000000L).id)
        val poll = OutgoingAttachment.Poll("Q?", listOf(PollAnswer("A"), PollAnswer("B")), setOf(PollFlag.ANONYMOUS, PollFlag.MULTISELECT))
        assertEquals(2L, api.sendPoll(100, poll).id)
        assertEquals(pymax["scheduled"], sink.hex(0))
        // a new MaxApi so the cid is again now + 1, as in the PyMax vector
        val sink2 = ScriptSink(msg(2))
        messages(sink2).sendPoll(100, poll)
        assertEquals(pymax["poll"], sink2.hex(0))
        assertFailsWith<IllegalArgumentException> { OutgoingAttachment.Poll("Q", listOf(PollAnswer("only"))) }
    }

    @Test
    fun voteAndIncomingPoll() = runTest {
        val state = mapOf("total" to 3, "result" to listOf(mapOf("answerId" to 1L, "voteCount" to 2, "votes" to listOf(mapOf("userId" to 5L, "timestamp" to 9L)), "rate" to 66, "options" to 0)), "voterPreviewIds" to listOf(5L))
        val sink = ScriptSink(mapOf("state" to state), emptyMap<String, Any?>())
        val api = messages(sink)
        val s = api.votePoll(100, 5, 3, listOf(1, 2))
        assertEquals(3, s.total)
        assertEquals(PollResult(1, 2, listOf(PollVote(5, 9)), 66, 0), s.results.single())
        assertEquals(listOf(5L), s.voterPreviewIds)
        assertEquals(Opcode.SEND_VOTE, sink.opcodes[0])
        assertEquals(pymax["vote"], sink.hex(0))
        assertFailsWith<MalformedReplyException> { api.votePoll(100, 5, 3, listOf(1)) }
        assertFailsWith<IllegalArgumentException> { api.votePoll(100, 5, 3, emptyList()) }
        // an incoming POLL attach
        val m = MaxMessage.from(
            mapOf("id" to 1L, "time" to now, "type" to "USER", "attaches" to listOf(mapOf("_type" to "POLL", "pollId" to 3L, "version" to 1, "title" to "Q?", "answers" to listOf(mapOf("text" to "A", "answerId" to 1L)), "settings" to 5, "state" to state))),
            100,
        )!!
        val p = assertIs<Attachment.Poll>(m.attachments.single())
        assertEquals(3L, p.pollId)
        assertEquals(setOf(PollFlag.ANONYMOUS, PollFlag.REVOTE), p.flags)
        assertEquals(listOf(PollAnswer("A", 1)), p.answers)
        assertEquals(3, p.state?.total)
        assertEquals(PollFlag.of(PollFlag.mask(PollFlag.entries.toSet())), PollFlag.entries.toSet())
    }

    @Test
    fun commentsMatchPyMax() = runTest {
        val info = mapOf("commentsInfoUpdates" to listOf(mapOf("postId" to 7L, "commentsInfo" to mapOf("totalCount" to 4)), mapOf("postId" to 8L, "commentsInfo" to null)))
        val sink = ScriptSink(
            msg(9), mapOf("messages" to listOf(msg(9)["message"])), msg(9), emptyMap<String, Any?>(), mapOf("reactionInfo" to mapOf("totalCount" to 1)),
            emptyMap<String, Any?>(), mapOf("messages" to emptyList<Any>()), emptyMap<String, Any?>(), info, emptyMap<String, Any?>(),
        )
        val api = messages(sink)
        assertEquals(-100L, api.sendComment(-100, 7, "nice", replyTo = 9).chatId)
        assertEquals(listOf(9L), api.getComments(-100, 7, listOf(9)).map { it.id })
        api.editComment(-100, 7, 9, "upd")
        api.deleteComments(-100, 7, listOf(9))
        assertEquals(1, api.addCommentReaction(-100, 7, 9, "👍")?.totalCount)
        assertNull(api.removeCommentReaction(-100, 7, 9))
        assertEquals(emptyList(), api.getCommentHistory(-100, 7))
        api.subscribeComments(-100, 7, subscribe = false)
        val counters = api.getCommentsInfo(-100, listOf(7, 8))
        assertEquals(listOf(CommentsInfo(7, 4, (info["commentsInfoUpdates"] as List<*>)[0] as Map<*, *>)), counters.take(1))
        assertNull(counters[1].totalCount)
        api.deleteUserComments(-100, 7, 1, 9)
        assertEquals(
            listOf(
                Opcode.MSG_SEND, Opcode.MSG_GET, Opcode.MSG_EDIT, Opcode.MSG_DELETE, Opcode.MSG_REACTION, Opcode.MSG_CANCEL_REACTION,
                Opcode.CHAT_HISTORY, Opcode.CHAT_SUBSCRIBE, Opcode.MSG_GET_COMMENTS_INFO, Opcode.MSG_DELETE_USER_COMMENTS,
            ),
            sink.opcodes,
        )
        listOf("comment", "getcomments", "editcomment", "delcomment", "reactcomment", "unreactcomment", null, "subscribe", "commentsinfo", "deluser")
            .forEachIndexed { i, k -> if (k != null) assertEquals(pymax[k], sink.hex(i), k) }
        // Comment history goes out in Komet's shape (feature/FullStack), not PyMax's.
        assertEquals(
            linkedMapOf<String, Any?>("chatId" to -100L, "postId" to 7L, "from" to -1L, "forward" to 0, "backward" to 30, "getMessages" to true),
            sink.sent[6].second,
        )
    }
}
