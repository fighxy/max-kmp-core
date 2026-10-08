package com.max.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionNamesTest {
    @Test
    fun aUserNameIsThePathOfItsLink() {
        assertEquals("anya_p", MentionNames.ofUser("https://max.ru/anya_p"))
        assertEquals("anya_p", MentionNames.ofUser(" https://max.ru/anya_p?utm=1#x "))
        assertEquals("u/abc", MentionNames.ofUser("https://max.ru/u/abc")) // the whole path, as the web client
        assertNull(MentionNames.ofUser("https://max.ru/"))
        assertNull(MentionNames.ofUser("https://max.ru"))
        assertNull(MentionNames.ofUser("anya_p")) // not an absolute URL
        assertNull(MentionNames.ofUser("@anya_p"))
        assertNull(MentionNames.ofUser(""))
        assertNull(MentionNames.ofUser(null))
        assertNull(MentionNames.ofUser("https:///x"))
    }

    @Test
    fun aChatInviteLinkGivesNoName() {
        assertEquals("news", MentionNames.ofChat("https://max.ru/news"))
        assertNull(MentionNames.ofChat("https://max.ru/join/AbCdEf"))
        assertNull(MentionNames.ofChat(null))
    }

    @Test
    fun modelsExposeIt() {
        assertEquals("anya_p", MaxUser.from(mapOf("id" to 1L, "link" to "https://max.ru/anya_p"))?.mentionName)
        assertNull(MaxUser.from(mapOf("id" to 1L))?.mentionName)
        assertEquals("news", Chat.from(mapOf("id" to -1L, "type" to "CHANNEL", "link" to "https://max.ru/news"))?.mentionName)
        assertNull(Chat.from(mapOf("id" to -1L, "type" to "CHAT", "link" to "https://max.ru/join/x"))?.mentionName)
    }
}
