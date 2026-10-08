package com.max.ios

import com.max.core.ErrorKind
import com.max.core.MaxError
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.protocol.Opcode
import com.max.core.protocol.decodePayloadPacket
import com.max.core.transport.ConnectionClosedException
import com.max.core.transport.ConnectionFactory
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.TransportConfig
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import com.max.shared.CredentialStore
import com.max.shared.InMemoryKeyValueStore
import com.max.shared.MaxClient
import com.max.shared.MaxClientConfig
import com.max.shared.StoredCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The server's error texts reach Swift through [IosErrors.current] inside the failure callback. */
class IosErrorsTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }
    private val offline = ConnectionFactory { _, _, _, _ -> throw ConnectionClosedException("offline") }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What a callback saw: its kind and key, and [IosErrors.current] read inside it. */
    private class Seen(val kind: String?, val key: String?, val error: IosError?)

    @Test
    fun maxErrorTextsBecomeEmptyStringsWhenMissing() {
        val full = iosErrorOf(
            "SERVER",
            MaxError(ErrorKind.SERVER, "m", "chat.denied", cause = Exception(), title = "T", localizedMessage = "L", description = "D"),
        )
        assertEquals(listOf("SERVER", "chat.denied", "T", "L", "D", "T", "T"), full.fields())
        val localized = iosErrorOf("SERVER", MaxError(ErrorKind.SERVER, "m", "k", cause = Exception(), localizedMessage = "L"))
        assertEquals(listOf("SERVER", "k", "", "L", "", "L", "L"), localized.fields())
        val bare = iosErrorOf("NETWORK", MaxError(ErrorKind.NETWORK, "offline", cause = Exception()))
        assertEquals(listOf("NETWORK", "", "", "", "", "", ""), bare.fields())
    }

    private fun IosError.fields() = listOf(kind, errorKey, title, localizedMessage, description, serverText, displayText)

    @Test
    fun offlineFailuresCarryAnErrorWithoutTexts() = runBlocking {
        val c = MaxIosClient(scope()) { s ->
            MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), offline, noHttp, s)
        }
        val start = CompletableDeferred<Seen>()
        c.start { _, k, e -> start.complete(Seen(k, e, IosErrors.current())) }
        val seen = withTimeout(10.seconds) { start.await() }
        assertEquals("NETWORK", seen.kind)
        assertEquals(listOf("NETWORK", "", "", "", "", "", ""), assertNotNull(seen.error).fields())

        val bad = CompletableDeferred<Seen>()
        c.markRead("x", "2") { k, e -> bad.complete(Seen(k, e, IosErrors.current())) }
        assertEquals("UNKNOWN", assertNotNull(withTimeout(10.seconds) { bad.await() }.error).kind)

        val typing = CompletableDeferred<Seen>()
        c.sendTyping("1", IosTypingType.TEXT, "") { k, e -> typing.complete(Seen(k, e, IosErrors.current())) }
        assertEquals("NETWORK", assertNotNull(withTimeout(10.seconds) { typing.await() }.error).kind)

        // outside a callback there is nothing to read
        assertNull(IosErrors.current())
        val closed = CompletableDeferred<Unit>()
        c.close { closed.complete(Unit) }
        withTimeout(5.seconds) { closed.await() }
    }

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: (Int) -> ByteArray) {
        val (header, _) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(reply(header.seq))
    }

    @Test
    fun serverErrorTextsAreReadableInTheCallback() = runBlocking {
        val kv = InMemoryKeyValueStore()
        CredentialStore(kv, "max.default").save(StoredCredentials("device", "instance", token = "tok", userId = 5))
        val factory = ScriptedConnectionFactory()
        val c = MaxIosClient(scope()) { s ->
            MaxClient(MaxClientConfig(host = "api.test", transport = quiet), kv, factory, noHttp, s)
        }
        val started = CompletableDeferred<String?>()
        c.start { phase, _, _ -> started.complete(phase) }
        val conn = withTimeout(5.seconds) {
            while (factory.lastConnection == null) delay(10)
            factory.lastConnection!!
        }
        conn.answer(Opcode.SESSION_INIT) { ok(it, Opcode.SESSION_INIT.value, mapOf("callsSeed" to 1L)) }
        conn.answer(Opcode.LOGIN) {
            ok(
                it, Opcode.LOGIN.value,
                mapOf(
                    "profile" to mapOf("contact" to mapOf("id" to 5, "names" to listOf(mapOf("firstName" to "Me")))),
                    "chats" to emptyList<Any>(),
                    "time" to 1700L,
                    "config" to mapOf("hash" to "h1", "chats" to mapOf("7" to mapOf("dontDisturbUntil" to -1))),
                ),
            )
        }
        assertEquals("ready", withTimeout(5.seconds) { started.await() })

        // an ERROR reply with every text
        val refused = CompletableDeferred<Seen>()
        c.setChatMuted("8", true) { k, e -> refused.complete(Seen(k, e, IosErrors.current())) }
        conn.answer(Opcode.CONFIG) {
            errorReply(
                it, Opcode.CONFIG.value,
                mapOf(
                    "error" to "chat.denied",
                    "message" to "denied",
                    "title" to "No access",
                    "localizedMessage" to "You cannot change this chat",
                    "description" to "Ask the owner",
                ),
            )
        }
        val seen = withTimeout(5.seconds) { refused.await() }
        assertEquals("SERVER", seen.kind)
        assertEquals("chat.denied", seen.key)
        assertEquals(
            listOf("SERVER", "chat.denied", "No access", "You cannot change this chat", "Ask the owner", "No access", "No access"),
            assertNotNull(seen.error).fields(),
        )

        // an ERROR reply with only a key: empty texts
        val bare = CompletableDeferred<Seen>()
        c.setChatMuted("8", false) { k, e -> bare.complete(Seen(k, e, IosErrors.current())) }
        conn.answer(Opcode.CONFIG) { errorReply(it, Opcode.CONFIG.value, mapOf("error" to "config.invalid")) }
        assertEquals(
            listOf("SERVER", "config.invalid", "", "", "", "", ""),
            assertNotNull(withTimeout(5.seconds) { bare.await() }.error).fields(),
        )

        // success: nothing to read
        val accepted = CompletableDeferred<Seen>()
        c.setChatMuted("8", true) { k, e -> accepted.complete(Seen(k, e, IosErrors.current())) }
        conn.answer(Opcode.CONFIG) { ok(it, Opcode.CONFIG.value, mapOf("hash" to "h2")) }
        val fine = withTimeout(5.seconds) { accepted.await() }
        assertNull(fine.kind)
        assertNull(fine.error)

        val closed = CompletableDeferred<Unit>()
        c.close { closed.complete(Unit) }
        withTimeout(5.seconds) { closed.await() }
    }
}
