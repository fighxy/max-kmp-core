@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.events

import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.state.MaxStore
import com.max.core.transport.MaxTransport
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.TransportPacket
import com.max.core.transport.ok
import com.max.core.transport.push
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration

/** A blocked handler must not cost the store events, nor block the socket reader. */
class EventRouterBackpressureTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)

    private fun message(id: Int) =
        push(Opcode.NOTIF_MESSAGE.value, mapOf("chatId" to 1, "message" to mapOf("id" to id, "time" to id, "type" to "USER", "sender" to 2, "text" to "m$id")))

    private fun typing(user: Int) = push(Opcode.NOTIF_TYPING.value, mapOf("chatId" to 1, "userId" to user))

    @Test
    fun blockedHandlerDoesNotDropEventsForTheStoreOrTheHandlers() = runTest {
        val factory = ScriptedConnectionFactory()
        val transport = MaxTransport(quiet, factory, scope = backgroundScope)
        transport.connect()
        val conn = factory.lastConnection!!
        val store = MaxStore(messageLimit = 0, clock = { 0 })
        val router = EventRouter(MaxEvents(transport.reliablePushes()).all, store)
        val release = CompletableDeferred<Unit>()
        val handled = ArrayList<MaxEvent>()
        router.on<MaxEvent> { e ->
            handled += e
            if (handled.size == 1) release.await()
        }
        router.start(backgroundScope)

        // far more than MaxTransport.PUSH_BUFFER_CAPACITY (256), mixed types
        val total = 3 * MaxTransport.PUSH_BUFFER_CAPACITY
        var messages = 0
        for (i in 1..total) {
            if (i % 4 == 0) conn.feed(typing(1000 + i)) else conn.feed(message(i)).also { messages++ }
        }
        runCurrent()
        // the handler is still blocked on the first event, the store already has everything
        assertEquals(1, handled.size)
        assertEquals(messages, store.state.value.messagesOf(1).size)
        assertEquals(total / 4, store.state.value.typing.getValue(1).size)

        release.complete(Unit)
        runCurrent()
        assertEquals(total, handled.size)
        val ids = handled.filterIsInstance<MaxEvent.NewMessage>().map { it.message.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(messages, ids.size)
        router.stop()
    }

    @Test
    fun handlerCanAwaitAReplyWhilePushesKeepArriving() = runTest {
        val factory = ScriptedConnectionFactory()
        val transport = MaxTransport(quiet, factory, scope = backgroundScope)
        transport.connect()
        val conn = factory.lastConnection!!
        val store = MaxStore(messageLimit = 0, clock = { 0 })
        val router = EventRouter(MaxEvents(transport.reliablePushes()).all, store)
        var reply: TransportPacket? = null
        var handled = 0
        router.on<MaxEvent.NewMessage> {
            handled++
            if (reply == null) reply = transport.request(Opcode.CHAT_INFO, mapOf("chatIds" to listOf(1)))
        }
        router.start(backgroundScope)

        conn.feed(message(1))
        runCurrent()
        val (header, _) = decodePayloadPacket(conn.takeWritten()!!)
        // 300 pushes arrive before the reply the handler waits for
        for (i in 2..301) conn.feed(message(i))
        conn.feed(ok(header.seq, Opcode.CHAT_INFO.value, mapOf("chats" to emptyList<Any>())))
        runCurrent()
        assertNotNull(reply)
        assertEquals(301, store.state.value.messagesOf(1).size)
        assertEquals(301, handled)
        router.stop()
    }
}
