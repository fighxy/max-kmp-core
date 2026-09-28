package com.max.core.api

import com.max.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BotsApi]; bytes from PyMax `pymax.api.bots.payloads` + msgpack-python. */
class BotsApiTest {
    private val now = 1759100000000L

    @Test
    fun initData() = runTest {
        val sink = ScriptSink(mapOf("queryId" to "q1", "url" to "https://app"), mapOf("queryId" to 5L, "url" to "u"), mapOf("url" to "u"))
        val api = MaxApi(sink) { now }.bots
        assertEquals(WebAppInitData("q1", "https://app", mapOf("queryId" to "q1", "url" to "https://app")), api.getWebAppInitData(42, 100, "s"))
        assertEquals("5", api.getWebAppInitData(42).queryId)
        assertFailsWith<MalformedReplyException> { api.getWebAppInitData(42) }
        assertEquals(List(3) { Opcode.WEB_APP_INIT_DATA }, sink.opcodes)
        assertEquals("83a5626f7449642aa663686174496464aa7374617274506172616da173", sink.hex(0))
        assertEquals("81a5626f7449642a", sink.hex(1))
    }

    @Test
    fun callback() = runTest {
        val reply = mapOf(
            "success" to true, "unread" to 0, "mark" to 7L,
            "chat" to mapOf("id" to 100L, "type" to "DIALOG"),
            "message" to mapOf("id" to 9L, "time" to now, "type" to "USER", "text" to "ok"),
        )
        val sink = ScriptSink(reply, mapOf("success" to false))
        val api = BotsApi(sink) { now }
        val r = api.sendCallback("cb-1", payload = "p")
        assertTrue(r.success)
        assertEquals(7L, r.mark)
        assertEquals(100L, r.message?.chatId)
        assertEquals(100L, r.chat?.id)
        val r2 = api.sendCallback("cb-1")
        assertEquals(false, r2.success)
        assertNull(r2.message)
        assertEquals("84aa63616c6c6261636b4964a463622d31a474797065a843414c4c4241434ba77061796c6f6164a170a974696d657374616d70cf000001999287d700", sink.hex(0))
        assertEquals("83aa63616c6c6261636b4964a463622d31a474797065a843414c4c4241434ba974696d657374616d70cf000001999287d700", sink.hex(1))
    }
}
