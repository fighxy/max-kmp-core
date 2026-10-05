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
        val sink = ScriptSink(
            mapOf("queryId" to "q1", "url" to "https://app"),
            mapOf("queryId" to 5L, "url" to "u"),
            mapOf("query_id" to "q2", "url" to "u"),
            mapOf("url" to "u"),
            mapOf("queryId" to "q3"),
        )
        val api = MaxApi(sink) { now }.bots
        assertEquals(WebAppInitData("q1", "https://app", mapOf("queryId" to "q1", "url" to "https://app")), api.getWebAppInitData(42, 100, "s"))
        assertEquals("5", api.getWebAppInitData(42).queryId)
        // Komet's server names it query_id and may leave it out; only the url is required
        assertEquals("q2", api.getWebAppInitData(42).queryId)
        assertNull(api.getWebAppInitData(42).queryId)
        assertFailsWith<MalformedReplyException> { api.getWebAppInitData(42) }
        assertEquals(List(5) { Opcode.WEB_APP_INIT_DATA }, sink.opcodes)
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

    @Test
    fun pressButtonSendsKometSchemaAndReadsTheAnswer() = runTest {
        val sink = ScriptSink(mapOf("text" to " Готово ", "url" to ""), mapOf("x" to 1))
        val api = BotsApi(sink)
        val answer = api.pressButton(-70L, 9L, "cb-1", payload = "p")
        assertEquals(com.max.core.protocol.Opcode.MSG_SEND_CALLBACK, sink.sent[0].first)
        assertEquals(mapOf<String, Any>("chatId" to -70L, "messageId" to 9L, "callbackId" to "cb-1", "payload" to "p"), sink.sent[0].second)
        assertEquals("Готово", answer.text)
        assertNull(answer.url)
        val silent = api.pressButton(-70L, 9L, "cb-1")
        assertEquals(mapOf<String, Any>("chatId" to -70L, "messageId" to 9L, "callbackId" to "cb-1"), sink.sent[1].second)
        assertNull(silent.text)
    }

    @Test
    fun webAppOptions() {
        assertTrue(hasWebApp(listOf("BOT", "HAS_WEBAPP")))
        assertTrue(hasWebApp(listOf("has_web_app")))
        assertEquals(false, hasWebApp(listOf("BOT", "OFFICIAL")))
    }

    @Test
    fun botInfoParsesCommandsAndContact() = runTest {
        val sink = ScriptSink(
            mapOf(
                "commands" to listOf(
                    mapOf("name" to "start", "description" to " Начать "),
                    mapOf("name" to "help"),
                    mapOf("name" to "  "),
                ),
                "contact" to mapOf("id" to 77, "names" to listOf(mapOf("name" to "Бот")), "description" to "Помощник", "link" to "helper_bot", "options" to listOf("BOT")),
            ),
            mapOf("x" to 1),
        )
        val api = BotsApi(sink)
        val info = api.getBotInfo(77)
        kotlin.test.assertEquals(com.max.core.protocol.Opcode.BOT_INFO, sink.sent[0].first)
        kotlin.test.assertEquals(mapOf<String, Any>("botId" to 77L), sink.sent[0].second)
        kotlin.test.assertEquals(listOf(BotCommand("start", "Начать"), BotCommand("help", null)), info.commands)
        kotlin.test.assertEquals("helper_bot", info.contact?.link)
        kotlin.test.assertEquals("Помощник", info.contact?.description)
        val empty = api.getBotInfo(78)
        kotlin.test.assertEquals(emptyList<BotCommand>(), empty.commands)
        kotlin.test.assertEquals(null, empty.contact)
    }
}
