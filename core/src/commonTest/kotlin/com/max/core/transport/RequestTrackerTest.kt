@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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

    @Test
    fun backoffIs2_4_8_15_15() {
        assertEquals(listOf(2, 4, 8, 15, 15, 15).map { it.seconds }, (0..5).map(::reconnectDelay))
        assertEquals(15.seconds, reconnectDelay(1000))
    }
}
