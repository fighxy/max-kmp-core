package com.maxly.core.api

import com.maxly.core.events.EventParser
import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Chat mutes in the account config: `NOTIF_CONFIG` 134 and how partial configs are applied. */
class ChatMuteConfigTest {
    private val known = AccountConfig(
        user = mapOf("HIDDEN" to false, "PUSH_SOUND" to "ON"),
        server = mapOf("invite-link" to "https://max.ru/u/me", "max-readmarks" to 50),
        hash = "h1",
        chats = mapOf(
            "-100" to mapOf("dontDisturbUntil" to -1L, "favIndex" to 2),
            "7" to mapOf("dontDisturbUntil" to -1L),
            "8" to mapOf("dontDisturbUntil" to 9_000L),
        ),
    )

    @Test
    fun notifConfigWithAConfigMapIsTyped() {
        val payload = mapOf("config" to mapOf("hash" to "h2", "chats" to mapOf(-100L to mapOf("dontDisturbUntil" to 0))))
        val event = assertIs<MaxEvent.ConfigUpdated>(EventParser.parse(Opcode.NOTIF_CONFIG.value, 0, payload))
        assertEquals(134, event.opcode)
        assertEquals("h2", event.hash)
        assertEquals(listOf(-100L), event.chatIds)
        assertNull(event.update.user)
        assertNull(event.update.server)
        assertEquals(payload, event.raw)
    }

    @Test
    fun notifConfigWithTopLevelSectionsIsTyped() {
        val event = assertIs<MaxEvent.ConfigUpdated>(
            EventParser.parse(134, 0, mapOf("chats" to mapOf("7" to mapOf("dontDisturbUntil" to 0)), "hash" to 42L)),
        )
        assertEquals("42", event.hash)
        assertEquals(listOf(7L), event.chatIds)
        // a user-only push is typed too
        val user = assertIs<MaxEvent.ConfigUpdated>(EventParser.parse(134, 0, mapOf("user" to mapOf("HIDDEN" to true))))
        assertEquals(mapOf<String, Any?>("HIDDEN" to true), user.update.user)
        assertNull(user.update.chats)
    }

    @Test
    fun notifConfigWithoutConfigSectionsStaysUnknown() {
        assertIs<MaxEvent.Unknown>(EventParser.parse(134, 0, mapOf("foo" to 1)))
        assertIs<MaxEvent.Unknown>(EventParser.parse(134, 0, emptyMap<String, Any?>()))
        assertIs<MaxEvent.Unknown>(EventParser.parse(134, 0, mapOf("config" to emptyMap<String, Any?>())))
        assertIs<MaxEvent.Unknown>(EventParser.parse(134, 1, mapOf("chats" to mapOf("7" to mapOf("dontDisturbUntil" to 0)))))
    }

    @Test
    fun missingChatsKeepTheKnownMutes() {
        val update = AccountConfigUpdate.fromLoginReply(mapOf("config" to mapOf("hash" to "h2", "server" to mapOf("max-readmarks" to 20))))!!
        assertNull(update.chats)
        for (next in listOf(known.mergedWith(update), known.replacedBy(update))) {
            assertEquals(true, next.isMuted(7, nowMs = 1_000))
            assertEquals(true, next.isMuted(-100, nowMs = 1_000))
            assertEquals(known.chats, next.chats)
            assertEquals(known.user, next.user)
            assertEquals("h2", next.hash)
            assertEquals(20, next.maxReadmarks)
        }
        // missing user / server keep theirs as well
        val chatsOnly = known.mergedWith(AccountConfigUpdate(chats = mapOf("9" to mapOf("dontDisturbUntil" to -1L))))
        assertEquals(known.user, chatsOnly.user)
        assertEquals(known.server, chatsOnly.server)
        assertEquals("h1", chatsOnly.hash)
    }

    @Test
    fun deltaChatsAreMergedPerChat() {
        val update = AccountConfigUpdate(chats = mapOf("7" to mapOf("dontDisturbUntil" to 0), "9" to mapOf("dontDisturbUntil" to -1)))
        val next = known.mergedWith(update)
        assertEquals(false, next.isMuted(7, nowMs = 1_000)) // unmute applied
        assertEquals(true, next.isMuted(9, nowMs = 1_000)) // new mute
        assertEquals(true, next.isMuted(-100, nowMs = 1_000)) // untouched chat kept
        assertEquals(9_000L, next.dontDisturbUntil(8))
        // a field-only entry keeps the mute and the other fields
        val fav = known.mergedWith(AccountConfigUpdate(chats = mapOf("-100" to mapOf("favIndex" to 5))))
        assertEquals(-1L, fav.dontDisturbUntil(-100))
        assertEquals(5L, ((fav.chats["-100"] as Map<*, *>)["favIndex"] as Number).toLong())
        val sound = known.mergedWith(AccountConfigUpdate(chats = mapOf("-100" to mapOf("dontDisturbUntil" to 0L))))
        assertEquals(2L, ((sound.chats["-100"] as Map<*, *>)["favIndex"] as Number).toLong())
        assertEquals(false, sound.isMuted(-100, nowMs = 0))
        // an entry null drops the chat's settings
        val dropped = known.mergedWith(AccountConfigUpdate(chats = mapOf("7" to null)))
        assertNull(dropped.dontDisturbUntil(7))
        // user keys are merged
        val user = known.mergedWith(AccountConfigUpdate(user = mapOf("HIDDEN" to true)))
        assertEquals(true, user.userFlag("HIDDEN"))
        assertEquals("ON", user.userString("PUSH_SOUND"))
    }

    @Test
    fun fullSnapshotReplacesPresentChats() {
        val full = known.replacedBy(AccountConfigUpdate(chats = mapOf("9" to mapOf("dontDisturbUntil" to -1), "10" to null), hash = "h3"))
        assertEquals(setOf("9"), full.chats.keys)
        assertTrue(full.chatsKnown)
        assertEquals(false, full.chatMuteState(7, nowMs = 0))
        assertEquals(0L, full.chatMuteUntil(7))
        assertEquals(true, full.chatMuteState(9, nowMs = 0))
        assertEquals("h3", full.hash)
        assertEquals(known.user, full.user)
        // a delta keeps the flag, a full snapshot without chats keeps it too
        assertTrue(full.mergedWith(AccountConfigUpdate(chats = mapOf("7" to mapOf("dontDisturbUntil" to -1)))).chatsKnown)
        assertTrue(full.replacedBy(AccountConfigUpdate(hash = "h4")).chatsKnown)
        assertEquals(false, known.mergedWith(AccountConfigUpdate(chats = emptyMap())).chatsKnown)
    }

    @Test
    fun chatsWithoutEntryAreUnknownUnlessTheSectionIsFull() {
        // a config built locally (mute before any server config) or from a reply without chats
        val local = AccountConfig().withChatMute(7, -1)
        assertEquals(false, local.chatsKnown)
        assertEquals(true, local.chatMuteState(7, nowMs = 0))
        assertNull(local.chatMuteState(8, nowMs = 0))
        assertNull(local.chatMuteUntil(8))
        assertNull(AccountConfig().withUser(mapOf("HIDDEN" to true), "h").chatMuteState(8, nowMs = 0))
        val noChats = AccountConfig.fromLoginReply(mapOf("config" to mapOf("hash" to "h", "user" to emptyMap<String, Any?>())))!!
        assertEquals(false, noChats.chatsKnown)
        assertNull(noChats.chatMuteState(8, nowMs = 0))
        // a full section: no entry = sound on
        val full = AccountConfig.fromLoginReply(mapOf("config" to mapOf("chats" to mapOf("7" to mapOf("dontDisturbUntil" to -1)))))!!
        assertTrue(full.chatsKnown)
        assertEquals(false, full.chatMuteState(8, nowMs = 0))
        // the real config replaces the local one
        val real = local.replacedBy(AccountConfigUpdate.fromLoginReply(mapOf("config" to mapOf("chats" to mapOf("-1" to mapOf("dontDisturbUntil" to -1)))))!!)
        assertTrue(real.chatsKnown)
        assertEquals(false, real.chatMuteState(7, nowMs = 0))
        assertEquals(true, real.chatMuteState(-1, nowMs = 0))
    }

    @Test
    fun timedMutesEndAtTheirTime() {
        val until = 50_000L
        val next = known.mergedWith(AccountConfigUpdate.fromPush(mapOf("chats" to mapOf("7" to mapOf("dontDisturbUntil" to until))))!!)
        assertEquals(until, next.dontDisturbUntil(7))
        assertEquals(true, next.isMuted(7, nowMs = until - 1))
        assertEquals(false, next.isMuted(7, nowMs = until))
        assertEquals(false, next.isMuted(8, nowMs = 10_000))
        assertTrue(AccountConfig(chats = mapOf("1" to mapOf("dontDisturbUntil" to -1))).isMuted(1)!!)
        assertEquals(false, AccountConfig(chats = mapOf("1" to mapOf("dontDisturbUntil" to 1L))).isMuted(1))
    }

    @Test
    fun fromLoginReplyStillBuildsAConfigOfItsOwn() {
        val c = AccountConfig.fromLoginReply(mapOf("config" to mapOf("hash" to 5, "chats" to mapOf(3L to mapOf("dontDisturbUntil" to -1)))))!!
        assertEquals("5", c.hash)
        assertEquals(true, c.isMuted(3, nowMs = 0))
        assertEquals(emptyMap(), c.user)
        assertNull(AccountConfig.fromLoginReply(mapOf("chats" to emptyList<Any>())))
    }

    @Test
    fun muteChangesNameOnlyTheChatsThatChanged() {
        val full = known.copy(chatsKnown = true)
        val next = full.mergedWith(AccountConfigUpdate(chats = mapOf("7" to mapOf("dontDisturbUntil" to 0), "-100" to mapOf("favIndex" to 9), "11" to mapOf("dontDisturbUntil" to -1))))
        assertEquals(
            listOf(ChatMuteChange(7, 0, false), ChatMuteChange(11, -1, true)),
            AccountConfig.chatMuteChanges(full, next, nowMs = 1_000),
        )
        assertEquals(emptyList(), AccountConfig.chatMuteChanges(full, full, nowMs = 1_000))
        // a removed entry: sound on with a full section, unknown without one
        val drop = AccountConfigUpdate(chats = mapOf("7" to null))
        assertEquals(listOf(ChatMuteChange(7, 0, false)), AccountConfig.chatMuteChanges(full, full.mergedWith(drop), nowMs = 0))
        assertEquals(listOf(ChatMuteChange(7, null, null)), AccountConfig.chatMuteChanges(known, known.mergedWith(drop), nowMs = 0))
        // an expired timed mute that the server replaces with 0 changes the raw value only
        assertEquals(listOf(ChatMuteChange(8, 0, false)), AccountConfig.chatMuteChanges(known, known.withChatMute(8, 0), nowMs = 10_000))
    }
}
