@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadPacingTest {
    @Test
    fun identicalReadsInFlightShareOneRequest() = runTest {
        val flight = SingleFlight()
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val a = async { flight.share("history:1") { calls++; gate.await(); "page" } }
        val b = async { flight.share("history:1") { calls++; "other" } }
        val c = async { flight.share("history:2") { calls++; "second chat" } }
        runCurrent()
        gate.complete(Unit)
        assertEquals("page", a.await())
        assertEquals("page", b.await())
        assertEquals("second chat", c.await())
        assertEquals(2, calls)
        assertEquals(0, flight.size())
        // A finished call is not cached.
        assertEquals("again", flight.share("history:1") { calls++; "again" })
        assertEquals(3, calls)
    }

    @Test
    fun aFailureReachesEveryWaiter() = runTest {
        val flight = SingleFlight()
        val gate = CompletableDeferred<Unit>()
        val a = async { runCatching { flight.share<String>("k") { gate.await(); throw IllegalStateException("too.many.requests") } } }
        val b = async { runCatching { flight.share("k") { "never" } } }
        runCurrent()
        gate.complete(Unit)
        assertEquals("too.many.requests", a.await().exceptionOrNull()?.message)
        assertEquals("too.many.requests", b.await().exceptionOrNull()?.message)
        assertFailsWith<IllegalStateException> { flight.share<Unit>("k") { throw IllegalStateException() } }
    }

    @Test
    fun aWaiterRunsTheReadWhenTheFirstCallerIsCancelled() = runTest {
        val flight = SingleFlight()
        val a = launch { flight.share("k") { delay(10_000); "first" } }
        val b = async { flight.share("k") { "second" } }
        runCurrent()
        a.cancel()
        assertEquals("second", b.await())
        assertEquals(0, flight.size())
    }

    @Test
    fun backgroundReadsRunOneAtATimeAndSpaced() = runTest {
        val pacer = ReadPacer(spacingMs = 300, now = { testScheduler.currentTime })
        val started = mutableListOf<Long>()
        val jobs = (1..3).map {
            launch { pacer.background { started += testScheduler.currentTime; delay(100) } }
        }
        jobs.forEach { it.join() }
        // 0..100 the first read, the next ones 300 ms after the previous one finished.
        assertEquals(listOf(0L, 400L, 800L), started)
    }

    @Test
    fun aPriorityReadGoesFirstAndHoldsBackgroundReads() = runTest {
        val pacer = ReadPacer(spacingMs = 0, now = { testScheduler.currentTime })
        val order = mutableListOf<String>()
        val comments = CompletableDeferred<Unit>()
        val urgent = launch { pacer.priority { order += "comments:start"; comments.await(); order += "comments:end" } }
        runCurrent()
        val media = launch { pacer.background { order += "media" } }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("comments:start"), order)
        comments.complete(Unit)
        urgent.join()
        media.join()
        assertEquals(listOf("comments:start", "comments:end", "media"), order)
        // Priority reads do not wait for background ones.
        val slow = CompletableDeferred<Unit>()
        val bg = launch { pacer.background { slow.await() } }
        runCurrent()
        assertTrue(pacer.priority { true })
        slow.complete(Unit)
        bg.join()
    }
}
