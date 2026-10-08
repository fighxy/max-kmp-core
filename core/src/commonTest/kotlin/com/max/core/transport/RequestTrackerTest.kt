@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RequestTrackerTest {

    @Test
    fun seqStartsAtOneAndIncrements() {
        val c = SeqCounter()
        assertEquals(0, c.current)
        assertEquals(listOf(1, 2, 3), List(3) { c.next() })
    }

    @Test
    fun seqWrapsFrom65535ToZeroLikeKolibri() {
        // kolibri: AtomicU16 fetch_add(1).wrapping_add(1) → ..., 65534, 65535, 0, 1
        val c = SeqCounter(start = 65533)
        assertEquals(listOf(65534, 65535, 0, 1), List(4) { c.next() })
    }

    @Test
    fun seqResetRestartsAtOne() {
        val c = SeqCounter()
        repeat(10) { c.next() }
        c.reset()
        assertEquals(1, c.next())
    }

    @Test
    fun reusedSeqFailsOldWaiterInsteadOfSkipping() {
        val p = PendingRequests()
        val first = p.register(7)
        val second = p.register(7)
        assertTrue(first.isCompleted)
        assertIs<ConnectionClosedException>(first.getCompletionExceptionOrNull())
        assertFalse(second.isCompleted)
        assertSame(second, p.take(7))
        assertNull(p.take(7))
    }

    @Test
    fun removeOnlyDropsTheSameWaiter() {
        val p = PendingRequests()
        val old = p.register(1)
        val new = p.register(1)
        p.remove(1, old)
        assertTrue(1 in p)
        p.remove(1, new)
        assertFalse(1 in p)
    }

    @Test
    fun failAllCompletesEveryWaiter() {
        val p = PendingRequests()
        val a = p.register(1)
        val b = p.register(2)
        p.failAll(ConnectionClosedException("gone"))
        assertEquals(0, p.size)
        assertIs<ConnectionClosedException>(a.getCompletionExceptionOrNull())
        assertIs<ConnectionClosedException>(b.getCompletionExceptionOrNull())
    }

    /** A [Random] whose `nextDouble()` is always [value] (0 → -10 %, 0.5 → exact, 1 → +10 %). */
    private class FixedRandom(private val value: Double) : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    @Test
    fun backoffIs3sDoublingTo96sWithTenPercentJitter() {
        val exact = FixedRandom(0.5)
        assertEquals(listOf(3, 6, 12, 24, 48, 96, 96).map { it.seconds }, (0..6).map { reconnectDelay(it, exact) })
        assertEquals(96.seconds, reconnectDelay(1000, exact))
        assertEquals(3.seconds, reconnectDelay(-1, exact))
        // ±10 %, like the app's random(-0.1, 0.1)
        assertEquals(2_700.milliseconds, reconnectDelay(0, FixedRandom(0.0)))
        assertEquals(86_400.milliseconds, reconnectDelay(9, FixedRandom(0.0)))
        assertEquals(105_600.milliseconds, reconnectDelay(9, FixedRandom(1.0)))
        val seeded = kotlin.random.Random(42)
        repeat(200) {
            val d = reconnectDelay(it % 8, seeded)
            val base = minOf(3_000L shl minOf(it % 8, 5), 96_000L)
            assertTrue(d.inWholeMilliseconds in (base * 9 / 10)..(base * 11 / 10), "$d for attempt ${it % 8}")
        }
    }
}
