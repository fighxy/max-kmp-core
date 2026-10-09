package com.maxly.core.state

import com.maxly.core.api.Chat
import com.maxly.core.api.MaxUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [Chat.hasWebApp]: the profile's mini-app rule on the dialog peer the store already holds. */
class ChatWebAppTest {
    private val me = 1L

    private fun dialog(id: Long, peer: Long) =
        Chat.from(mapOf("id" to id, "type" to "DIALOG", "status" to "ACTIVE", "participants" to mapOf("$me" to 0, "$peer" to 0)))!!

    private fun group(id: Long, member: Long) =
        Chat.from(mapOf("id" to id, "type" to "CHAT", "status" to "ACTIVE", "participants" to mapOf("$me" to 0, "$member" to 0)))!!

    private fun user(id: Long, vararg options: String) = MaxUser.from(mapOf("id" to id, "options" to options.toList()))!!

    private fun flags(vararg users: MaxUser, chats: List<Chat>): Map<Long, Boolean> {
        val store = MaxStore(initial = MaxState(me = me))
        store.putUsers(users.toList())
        store.putChats(chats)
        return store.state.value.chats.mapValues { it.value.hasWebApp }
    }

    @Test
    fun botWithTheOptionHasWebApp() {
        val f = flags(
            user(10, "BOT", "HAS_WEBAPP"), user(11, "BOT", "HAS_WEB_APP"), user(12, "WEBAPP", "BOT"),
            chats = listOf(dialog(100, 10), dialog(101, 11), dialog(102, 12)),
        )
        assertEquals(mapOf(100L to true, 101L to true, 102L to true), f)
    }

    @Test
    fun optionCaseDoesNotMatterButBotDoes() {
        // the option names are compared in any case (as the profile does); `BOT` is matched exactly
        val f = flags(
            user(10, "BOT", "has_webapp"), user(11, "BOT", "Has_Web_App"), user(12, "bot", "HAS_WEBAPP"),
            chats = listOf(dialog(100, 10), dialog(101, 11), dialog(102, 12)),
        )
        assertEquals(mapOf(100L to true, 101L to true, 102L to false), f)
    }

    @Test
    fun missingOptionsGiveFalse() {
        val f = flags(
            user(10, "BOT"), user(11), user(12, "BOT", "OFFICIAL", "WEB_APP", "MINIAPP"),
            chats = listOf(dialog(100, 10), dialog(101, 11), dialog(102, 12), dialog(103, 13)),
        )
        // 13 is not loaded: nothing is fetched for the list, the flag stays false
        assertEquals(mapOf(100L to false, 101L to false, 102L to false, 103L to false), f)
    }

    @Test
    fun nonBotsGroupsAndChannelsGiveFalse() {
        val channel = Chat.from(mapOf("id" to 104, "type" to "CHANNEL", "participants" to mapOf("$me" to 0, "10" to 0)))!!
        val f = flags(
            user(10, "BOT", "HAS_WEBAPP"), user(11, "HAS_WEBAPP"), user(me, "BOT", "HAS_WEBAPP"),
            chats = listOf(dialog(101, 11), group(102, 10), channel, Chat.from(mapOf("id" to 105, "type" to "DIALOG", "participants" to mapOf("$me" to 0)))!!),
        )
        assertEquals(mapOf(101L to false, 102L to false, 104L to false, 105L to false), f)
    }

    @Test
    fun flagFollowsThePeerAndSurvivesChatUpdates() {
        val store = MaxStore(initial = MaxState(me = me))
        store.putChats(listOf(dialog(100, 10)))
        assertFalse(store.state.value.chats.getValue(100).hasWebApp)
        store.putUsers(listOf(user(10, "BOT", "HAS_WEBAPP")))
        assertTrue(store.state.value.chats.getValue(100).hasWebApp)
        assertTrue(store.state.value.chatList.single().hasWebApp)
        // a fresh chat object from the server (flag false when parsed) gets it again
        store.putChats(listOf(dialog(100, 10)))
        assertTrue(store.state.value.chats.getValue(100).hasWebApp)
        store.putUsers(listOf(user(10, "BOT")))
        assertFalse(store.state.value.chats.getValue(100).hasWebApp)
    }

    @Test
    fun parsedChatAndOldConstructorDefaultToFalse() {
        assertFalse(dialog(100, 10).hasWebApp)
        val old = Chat(1, "DIALOG", "ACTIVE", null, null, 0, 0, 0, null, emptyMap<String, Any?>(), emptyMap())
        assertFalse(old.hasWebApp)
        assertTrue(old.copy(hasWebApp = true).hasWebApp)
    }

    @Test
    fun unchangedStateIsKept() {
        val s = MaxState(me = me, chats = mapOf(100L to dialog(100, 10)), users = mapOf(10L to user(10, "BOT")))
        assertSame(s, StateReducer.applyWebApps(s))
        val withFlag = StateReducer.applyWebApps(s.copy(users = mapOf(10L to user(10, "BOT", "WEBAPP"))))
        val again = withFlag.copy()
        assertSame(again, StateReducer.applyWebApps(again, withFlag))
        assertTrue(withFlag.chatHasWebApp(withFlag.chats.getValue(100)))
    }
}
