package com.max.core.state

import com.max.core.api.PresenceInfo
import com.max.core.api.PresenceStatus
import com.max.core.api.Presences
import com.max.core.auth.LoginResult
import com.max.core.events.EventParser
import com.max.core.events.MaxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Presence in the store: `LOGIN` `presence`, push 132, TTL and the reset of a new session. */
class PresenceStateTest {
    private var now = 1_000_000L
    private fun store() = MaxStore(clock = { now })

    private val ttl = 300_000L

    @Test
    fun loginPresenceIsRead() {
        val store = store()
        store.applyLogin(
            LoginResult.from(
                mapOf(
                    "time" to 5L,
                    "presence" to mapOf("7" to mapOf("seen" to 1_700_000_000L, "status" to 1), 8L to mapOf("seen" to 1_600_000_000L), "x" to mapOf("seen" to 1)),
                ),
            ),
        )
        val s = store.state.value
        assertEquals(PresenceInfo(1_700_000_000L, 1), s.presence[7L])
        assertEquals(PresenceInfo(1_600_000_000L, null), s.presence[8L])
        assertEquals(setOf(7L, 8L), s.presence.keys)
        assertEquals(now, s.presenceTimes[7L])
        assertEquals(PresenceStatus.ONLINE, s.presenceStatus(7L, now, ttl))
        assertEquals(PresenceStatus.OFFLINE, s.presenceStatus(8L, now, ttl))
        assertEquals(PresenceStatus.UNKNOWN, s.presenceStatus(9L, now, ttl))
    }

    @Test
    fun pushWithoutSeenKeepsTheStoredTime() {
        val store = store()
        store.apply(EventParser.parse(132, 0, mapOf("userId" to 7L, "presence" to mapOf("seen" to 1_700_000_000L, "status" to 0))))
        store.apply(EventParser.parse(132, 0, mapOf("userId" to 7L, "presence" to mapOf("status" to 1))))
        assertEquals(PresenceInfo(1_700_000_000L, 1), store.state.value.presence[7L])
        // without status: offline, the time still kept
        store.apply(EventParser.parse(132, 0, mapOf("userId" to 7L, "presence" to emptyMap<String, Any?>())))
        assertEquals(PresenceInfo(1_700_000_000L, null), store.state.value.presence[7L])
        // a push with a time replaces it
        store.apply(EventParser.parse(132, 0, mapOf("userId" to 7L, "presence" to mapOf("seen" to 1_700_000_100L, "status" to 2))))
        assertEquals(PresenceInfo(1_700_000_100L, 2), store.state.value.presence[7L])
        // a user never seen before: no time is invented
        store.apply(EventParser.parse(132, 0, mapOf("userId" to 9L, "presence" to mapOf("status" to 3))))
        assertEquals(PresenceInfo(null, 3), store.state.value.presence[9L])
    }

    @Test
    fun onlineExpiresAfterTheTtl() {
        val store = store()
        now = 1_700_000_000_000L
        store.apply(MaxEvent.Presence(7L, 1_699_999_000L, 1, 132, null))
        store.apply(MaxEvent.Presence(8L, 1_699_000_000L, 2, 132, null))
        now += ttl
        assertEquals(emptyList(), store.expirePresence(ttl))
        assertEquals(PresenceStatus.ONLINE, store.state.value.presenceStatus(7L, now, ttl))
        now += 1
        // read through presenceAt: degraded before any sweep
        assertEquals(PresenceInfo(1_700_000_000L, 0), store.presenceAt(7L, ttl))
        assertEquals(listOf(7L), store.expirePresence(ttl))
        // offline, last seen = the last time it was known online (the refresh, in seconds)
        assertEquals(PresenceInfo(1_700_000_000L, PresenceStatus.OFFLINE), store.state.value.presence[7L])
        // "recently" has no TTL
        assertEquals(PresenceInfo(1_699_000_000L, 2), store.state.value.presence[8L])
        assertEquals(emptyList(), store.expirePresence(ttl))
    }

    @Test
    fun aRefreshKeepsOnline() {
        val store = store()
        store.apply(MaxEvent.Presence(7L, null, 1, 132, null))
        now += ttl - 1
        store.apply(MaxEvent.Presence(7L, null, 1, 132, null))
        now += ttl - 1
        assertEquals(emptyList(), store.expirePresence(ttl))
        store.putPresence(mapOf(7L to PresenceInfo(null, 1)))
        now += ttl + 1
        assertEquals(listOf(7L), store.expirePresence(ttl))
        // no `seen` was ever sent: the refresh time becomes the last seen (seconds)
        assertEquals(PresenceInfo((now - ttl - 1) / 1000, 0), store.state.value.presence[7L])
    }

    @Test
    fun aNewSessionResetsOnlineUntilFreshDataArrives() {
        val store = store()
        store.applyLogin(LoginResult.from(mapOf("profile" to mapOf("contact" to mapOf("id" to 1L)), "presence" to mapOf("7" to mapOf("seen" to 100L, "status" to 1), "8" to mapOf("seen" to 50L, "status" to 1)))))
        store.apply(MaxEvent.Presence(9L, 70L, 3, 132, null))
        now += 10_000
        // reconnect: the delta only refreshes 8
        store.applyLogin(LoginResult.from(mapOf("profile" to mapOf("contact" to mapOf("id" to 1L)), "presence" to mapOf("8" to mapOf("status" to 1)))))
        val s = store.state.value
        assertEquals(PresenceInfo(1_000L, 0), s.presence[7L]) // offline at the last refresh (1 000 000 ms)
        assertEquals(PresenceInfo(1_000L, 1), s.presence[8L]) // fresh, `seen` = the last known time kept
        assertEquals(PresenceInfo(70L, 3), s.presence[9L]) // not online: untouched
        // a LOGIN without any presence still resets
        store.applyLogin(LoginResult.from(mapOf("profile" to mapOf("contact" to mapOf("id" to 1L)))))
        assertEquals(PresenceStatus.OFFLINE, PresenceStatus.of(store.state.value.presence[8L]))
    }

    @Test
    fun statusCodes() {
        assertEquals(PresenceStatus.UNKNOWN, PresenceStatus.of(null))
        assertEquals(PresenceStatus.OFFLINE, PresenceStatus.of(PresenceInfo(5, null)))
        for (code in 0..3) assertEquals(code, PresenceStatus.of(PresenceInfo(null, code)))
        assertEquals(PresenceStatus.RECENTLY, PresenceStatus.of(PresenceInfo(null, 9)))
        assertEquals(0L, Presences.seenMs(null))
        assertEquals(1_700_000_000_000L, Presences.seenMs(1_700_000_000L))
        assertEquals(1_700_000_000_123L, Presences.seenMs(1_700_000_000_123L))
        // a millisecond `seen` keeps its unit when degraded
        assertEquals(PresenceInfo(1_700_000_000_500L, 0), Presences.degrade(PresenceInfo(1_700_000_000_000L, 1), 1_700_000_000_500L))
    }

    @Test
    fun contactPresenceReply() {
        val reply = mapOf("presence" to mapOf("7" to mapOf("seen" to 10L, "status" to 2), 8L to mapOf("seen" to 11L), "99" to mapOf("status" to 1), "bad" to 1))
        val got = Presences.fromContactPresence(listOf(7L, 8L, 9L), reply)
        assertEquals(listOf(7L, 8L, 9L, 99L), got.keys.toList())
        assertEquals(PresenceInfo(10L, 2), got[7L])
        assertEquals(PresenceInfo(11L, null), got[8L])
        assertEquals(PresenceInfo(null, PresenceStatus.LONG_AGO), got[9L])
        assertEquals(PresenceInfo(null, 1), got[99L])
        assertEquals(PresenceInfo(null, 3), Presences.fromContactPresence(listOf(1L), emptyMap<String, Any?>())[1L])
        assertNull(Presences.parseMap("x"))
    }

    @Test
    fun presenceTimesKeepTheirShape() {
        val s = StateReducer.putPresence(MaxState(), mapOf(1L to PresenceInfo(1, 1)), StateReducer.NO_TIME)
        assertTrue(1L !in s.presenceTimes)
        // without a refresh time the TTL never applies
        assertEquals(s, StateReducer.expirePresence(s, Long.MAX_VALUE / 2, 1))
        assertIs<MaxEvent.Presence>(EventParser.parse(132, 0, mapOf("userId" to "7", "presence" to mapOf("status" to 1))))
    }
}
