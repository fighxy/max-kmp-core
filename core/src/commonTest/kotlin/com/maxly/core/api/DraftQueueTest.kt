@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.core.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DraftQueueTest {
    @Test
    fun theDiscardAfterASendWaitsForTheSaveInFlightAndCoversIt() = runTest {
        val q = DraftQueue()
        val log = ArrayList<String>()
        val reply = CompletableDeferred<Long>()
        val save = async {
            runCatching { q.save(7, send = { log += "176 out"; reply.await() }, onSaved = { log += "stored $it" }) }
        }
        runCurrent()
        val order = q.issueSend(7)
        val (consumed, open) = q.sent(7, order) { consume -> consume }
        assertTrue(consumed)
        assertTrue(open)
        val discard = async { q.discardAfterSend(7) { t -> log += "177 at $t" } }
        runCurrent()
        assertEquals(listOf("176 out"), log) // the 177 waits
        reply.complete(1_200)
        runCurrent()
        assertEquals(1_200L, assertIs<DraftSupersededException>(save.await().exceptionOrNull()).time)
        discard.await()
        assertEquals(listOf("176 out", "177 at 1200"), log)
    }

    @Test
    fun aFailedSaveReleasesTheDiscard() = runTest {
        val q = DraftQueue()
        val log = ArrayList<String>()
        val reply = CompletableDeferred<Long>()
        val save = async { runCatching { q.save(7, send = { reply.await() }, onSaved = { log += "stored" }) } }
        runCurrent()
        q.sent(7, q.issueSend(7)) { }
        val discard = async { q.discardAfterSend(7) { t -> log += "177 at $t" } }
        runCurrent()
        assertEquals(emptyList(), log)
        reply.completeExceptionally(IllegalStateException("network"))
        runCurrent()
        assertEquals("network", save.await().exceptionOrNull()?.message)
        discard.await()
        assertEquals(listOf("177 at null"), log)
    }

    @Test
    fun aQueuedSaveIssuedBeforeTheSendIsDropped() = runTest {
        val q = DraftQueue()
        val log = ArrayList<String>()
        val first = CompletableDeferred<Long>()
        val a = async { runCatching { q.save(7, send = { log += "176 a"; first.await() }, onSaved = { log += "stored a" }) } }
        val b = async { runCatching { q.save(7, send = { log += "176 b"; 999L }, onSaved = { log += "stored b" }) } }
        runCurrent()
        q.sent(7, q.issueSend(7)) { }
        first.complete(1_000)
        runCurrent()
        assertEquals(1_000L, assertIs<DraftSupersededException>(a.await().exceptionOrNull()).time)
        assertEquals(null, assertIs<DraftSupersededException>(b.await().exceptionOrNull()).time)
        assertEquals(listOf("176 a"), log)
    }

    @Test
    fun aSaveIssuedAfterTheSendStartedIsKeptAndNotConsumed() = runTest {
        val q = DraftQueue()
        val order = q.issueSend(7)
        assertEquals(1_300L, q.save(7, send = { 1_300L }, onSaved = { it }))
        val (consume, open) = q.sent(7, order) { it }
        assertEquals(false, consume)
        assertEquals(false, open)
        // another chat is independent
        val other = q.issueSend(8)
        assertEquals(true, q.sent(8, other) { it }.first)
    }

    @Test
    fun savesOfOneChatGoOutOneAtATimeInOrder() = runTest {
        val q = DraftQueue()
        val log = ArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        val a = async { q.save(7, send = { log += "a out"; gate.await(); log += "a back"; 1L }, onSaved = { it }) }
        val b = async { q.save(7, send = { log += "b out"; 2L }, onSaved = { it }) }
        val c = async { q.exclusive(7) { log += "discard" } }
        runCurrent()
        assertEquals(listOf("a out"), log)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1L, 2L), listOf(a.await(), b.await()))
        c.await()
        assertEquals(listOf("a out", "a back", "b out", "discard"), log)
    }
}
