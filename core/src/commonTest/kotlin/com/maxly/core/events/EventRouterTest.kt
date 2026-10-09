@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.core.events

import com.maxly.core.state.MaxStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventRouterTest {
    private val typing = EventParser.parse(129, 0, mapOf("chatId" to 1, "userId" to 2))
    private val message = EventParser.parse(128, 0, mapOf("chatId" to 1, "message" to mapOf("id" to 5, "time" to 1, "type" to "USER", "text" to "hi")))

    @Test
    fun routesByTypeAfterApplyingToTheStore() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<MaxEvent>()
        val store = MaxStore(clock = { 42 })
        val router = EventRouter(flow, store)
        val seen = ArrayList<String>()
        router.on<MaxEvent.NewMessage> { seen += "msg:${it.message.text}:${store.state.value.messagesOf(1).size}" }
        router.on<MaxEvent.Typing> { seen += "typing:${it.userId}" }
        val all = router.on(MaxEvent::class) { seen += "any:${it.opcode}" }
        router.start(backgroundScope)
        assertTrue(router.isRunning)

        flow.emit(typing)
        flow.emit(message)
        assertEquals(listOf("typing:2", "any:129", "msg:hi:1", "any:128"), seen)
        assertEquals(setOf(2L), store.state.value.typingUsers(1, 42))

        all.cancel()
        seen.clear()
        flow.emit(typing)
        assertEquals(listOf("typing:2"), seen)

        router.stop()
        assertFalse(router.isRunning)
        flow.emit(typing)
        assertEquals(listOf("typing:2"), seen)
    }

    @Test
    fun handlerErrorsAreReportedAndDoNotStopTheLoop() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableSharedFlow<MaxEvent>()
        val router = EventRouter(flow)
        val errors = ArrayList<Pair<Int, String?>>()
        val ok = ArrayList<Int>()
        router.on<MaxEvent.Typing> { error("boom") }
        router.on<MaxEvent> { ok += it.opcode }
        router.onError { e, t -> errors += e.opcode to t.message }
        router.start(backgroundScope)
        // start is idempotent while running
        router.start(backgroundScope)
        flow.emit(typing)
        flow.emit(message)
        assertEquals(listOf<Pair<Int, String?>>(129 to "boom"), errors)
        assertEquals(listOf(129, 128), ok)
    }
}
