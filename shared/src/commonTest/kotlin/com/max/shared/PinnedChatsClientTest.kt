@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.shared

import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportConfig
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import com.max.core.transport.push
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

/** [MaxClient.setPinnedChats], [MaxClient.loadFolders] and pins pushed from other devices. */
class PinnedChatsClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private fun allFolder(vararg pins: Long) = mapOf(
        "id" to "all.chat.folder", "title" to "Все", "include" to emptyList<Long>(),
        "filters" to emptyList<Any>(), "options" to listOf("NO_DELETE"), "favorites" to pins.toList(), "updateTime" to 1L,
    )

    private fun loginReply(folders: List<Any?>?) = buildMap {
        put("profile", mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("name" to "Me")))))
        put("chats", listOf(100, 200, 300).map { mapOf("id" to it, "type" to "DIALOG", "status" to "ACTIVE", "owner" to 5, "lastEventTime" to it) })
        put("time", 1700L)
        put("token", "login-2")
        put("config", buildMap {
            put("hash", "cfg-1")
            if (folders != null) put("chatFolders", mapOf("FOLDERS" to folders, "foldersOrder" to listOf("all.chat.folder"), "folderSync" to 3L))
        })
    }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun FakeRawConnection.fail(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(errorReply(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    /** A client logged in with a stored token; [folders] go into the `LOGIN` config. */
    private suspend fun TestScope.loggedIn(factory: ScriptedConnectionFactory, folders: List<Any?>?): MaxClient {
        val kv = InMemoryKeyValueStore()
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), kv, factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(Opcode.LOGIN, loginReply(folders))
        login.await()
        runCurrent()
        return c
    }

    private fun longs(value: Any?): List<Long> = (value as List<*>).map { (it as Number).toLong() }

    @Test
    fun pinsFromLoginAndPushesReachTheWatcher() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory, listOf(allFolder(300, 100)))
        val seen = ArrayList<List<Long>?>()
        val watch = c.watchPinnedChats { seen += it }
        runCurrent()
        assertEquals(listOf(300L, 100L), c.store.state.value.pinnedChatIds)
        assertEquals(listOf(300L, 100L, 200L), c.store.state.value.chatList.map { it.id })

        // another device reordered and pinned one more
        factory.lastConnection!!.feed(push(Opcode.NOTIF_FOLDERS.value, mapOf("folder" to allFolder(100, 200, 300), "folderSync" to 4L)))
        runCurrent()
        assertEquals<List<List<Long>?>>(listOf(listOf(300L, 100L), listOf(100L, 200L, 300L)), seen)
        // a push that does not change the pins is not delivered again
        factory.lastConnection!!.feed(push(Opcode.NOTIF_FOLDERS.value, mapOf("folderSync" to 5L)))
        runCurrent()
        assertEquals(2, seen.size)
        watch.cancel()
    }

    @Test
    fun setPinnedChatsSendsTheWholeListAndAppliesTheReply() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory, listOf(allFolder(300)))
        val conn = factory.lastConnection!!

        val pin = async { c.setPinnedChats(listOf(200, 300, 200)) }
        runCurrent()
        val sent = conn.answer(Opcode.FOLDERS_UPDATE, mapOf("folder" to allFolder(200, 300), "folderSync" to 4L))!!
        assertEquals(listOf("id", "title", "include", "filters", "options", "favorites"), sent.keys.toList())
        assertEquals("all.chat.folder", sent["id"])
        assertEquals("Все", sent["title"])
        assertEquals(listOf("NO_DELETE"), sent["options"])
        assertEquals(listOf(200L, 300L), longs(sent["favorites"]))
        assertEquals(listOf(200L, 300L), pin.await())
        assertEquals(listOf(200L, 300L), c.store.state.value.pinnedChatIds)

        // the server refuses: the error reaches the caller and the store keeps the previous pins
        val refused = async { runCatching { c.setPinnedChats(listOf(100, 200, 300)) } }
        runCurrent()
        assertEquals(listOf(100L, 200L, 300L), longs(conn.fail(Opcode.FOLDERS_UPDATE, mapOf("error" to "folder.favorites.limit", "message" to "limit"))!!["favorites"]))
        assertIs<ServerErrorException>(refused.await().exceptionOrNull())
        assertEquals(listOf(200L, 300L), c.store.state.value.pinnedChatIds)

        // unpin everything
        val none = async { c.setPinnedChats(emptyList()) }
        runCurrent()
        assertEquals(emptyList(), longs(conn.answer(Opcode.FOLDERS_UPDATE, mapOf("folderSync" to 5L))!!["favorites"]))
        assertEquals(emptyList(), none.await())
    }

    @Test
    fun withoutFoldersInTheLoginTheyAreFetchedFirst() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory, folders = null)
        val conn = factory.lastConnection!!
        val pin = async { c.setPinnedChats(listOf(100)) }
        runCurrent()
        val get = conn.answer(Opcode.FOLDERS_GET, mapOf("folders" to listOf(allFolder()), "foldersOrder" to listOf("all.chat.folder"), "folderSync" to 9L))!!
        assertEquals(0L, (get["folderSync"] as Number).toLong())
        runCurrent()
        assertEquals(listOf(100L), longs(conn.answer(Opcode.FOLDERS_UPDATE, mapOf("folder" to allFolder(100)))!!["favorites"]))
        assertEquals(listOf(100L), pin.await())

        // no "all chats" folder on the server at all: after the resync nothing is sent
        val factory2 = ScriptedConnectionFactory()
        val c2 = loggedIn(factory2, folders = listOf(mapOf("id" to "f-1", "title" to "Work")))
        val attempt = async { runCatching { c2.setPinnedChats(listOf(1)) } }
        runCurrent()
        factory2.lastConnection!!.answer(Opcode.FOLDERS_GET, mapOf("folders" to listOf(mapOf("id" to "f-1", "title" to "Work"))))
        runCurrent()
        assertIs<IllegalStateException>(attempt.await().exceptionOrNull())
        kotlin.test.assertNull(factory2.lastConnection!!.takeWritten())
    }
}
