package com.max.ios

import com.max.core.api.ChatMember
import com.max.core.api.ChatMembersPage
import com.max.core.api.ChatMembersResult
import com.max.core.api.ChatRoles
import com.max.core.api.MaxMessage
import com.max.core.api.MaxUser
import com.max.core.api.PhoneContact
import com.max.core.api.TextElementType
import com.max.core.calls.CallSignaling
import com.max.core.calls.ConversationParams
import com.max.core.events.MaxEvent
import com.max.core.media.HttpResponse
import com.max.core.media.MediaHttp
import com.max.core.transport.ConnectionClosedException
import com.max.core.transport.ConnectionFactory
import com.max.core.session.UserAgentInfo
import com.max.core.state.MaxState
import com.max.core.state.StateReducer
import com.max.core.transport.TransportConfig
import com.max.shared.CredentialStore
import com.max.shared.InMemoryKeyValueStore
import com.max.shared.MaxClient
import com.max.shared.MaxClientConfig
import com.max.shared.StoredCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The Swift facade reports every failure through its callback; nothing throws or aborts. */
class MaxIosClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }
    private val offline = ConnectionFactory { _, _, _, _ -> throw ConnectionClosedException("offline") }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun offlineClient() = MaxIosClient(scope()) { s ->
        MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), offline, noHttp, s)
    }

    private fun <T> callback(block: (CompletableDeferred<T>) -> Unit): T = runBlocking {
        val done = CompletableDeferred<T>()
        block(done)
        withTimeout(10.seconds) { done.await() }
    }

    @Test
    fun failingClientCreationBecomesAnErrorKind() {
        var attempts = 0
        val c = MaxIosClient(scope()) {
            attempts++
            throw IllegalStateException("keychain read failed")
        }
        assertEquals("failed", c.phaseName())
        assertEquals("", c.currentUserId())
        assertFalse(c.hasStoredToken())
        val (phase, kind) = callback<Pair<String?, String?>> { d -> c.start { p, k, _ -> d.complete(p to k) } }
        assertNull(phase)
        assertEquals("UNKNOWN", kind)
        val chats = callback<Pair<Int, String?>> { d -> c.loadChats { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", chats)
        c.watchState { }.cancel()
        c.watchPinnedChats { }.cancel()
        // creation is retried on every call instead of caching the failure
        assertTrue(attempts >= 5)
        callback<Unit> { d -> c.close { d.complete(Unit) } }
    }

    @Test
    fun networkErrorsAndBadIdsAreDeliveredAsKinds() {
        val c = offlineClient()
        val start = callback<Pair<String?, String?>> { d -> c.start { p, k, _ -> d.complete(p to k) } }
        assertEquals(null to "NETWORK", start)
        val send = callback<Pair<IosMessage?, String?>> { d -> c.sendText("not-a-number", "hi") { m, k, _ -> d.complete(m to k) } }
        assertNull(send.first)
        assertEquals("UNKNOWN", send.second)
        val history = callback<Pair<Int, String?>> { d -> c.loadHistory("x", 0, 10) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", history)
        val around = callback<Pair<Int, String?>> { d -> c.loadHistoryAround("x", "1", 0, 20, 20) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", around)
        val aroundMessage = callback<Pair<Int, String?>> { d -> c.loadHistoryAround("1", "y", 0, 20, 20) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", aroundMessage)
        val read = callback<String?> { d -> c.markRead("1", "2") { k, _ -> d.complete(k) } }
        assertEquals("NETWORK", read)
        val readAt = callback<Pair<Long, String?>> { d -> c.markReadAt("1", "2", 1_700_000_000_000) { r, k, _ -> d.complete(r.mark to k) } }
        assertEquals(0L to "NETWORK", readAt)
        val badReadAt = callback<String?> { d -> c.markReadAt("x", "2", 0) { _, k, _ -> d.complete(k) } }
        assertEquals("UNKNOWN", badReadAt)
        val code = callback<String?> { d -> c.requestCode("+79990000000", false) { _, k, _ -> d.complete(k) } }
        assertEquals("NETWORK", code)
        val badPin = callback<Pair<Int, String?>> { d -> c.setPinnedChats(listOf("1", "x")) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", badPin)
        // no folders yet: the folder resync needs the network
        val pin = callback<Pair<Int, String?>> { d -> c.setPinnedChats(listOf("1")) { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "NETWORK", pin)
        val feed = callback<Pair<Int, String?>> { d -> c.loadStoriesFeed { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "NETWORK", feed)
        val owner = callback<Pair<IosOwnerStories?, String?>> { d -> c.loadOwnerStories("x", 0) { o, k, _ -> d.complete(o to k) } }
        assertEquals(null to "UNKNOWN", owner)
        val story = callback<Pair<IosPublishedStory?, String?>> { d -> c.publishStory("/nope.gif", "gif", 0, 1, { }) { p, k, _ -> d.complete(p to k) } }
        assertEquals(null to "UNKNOWN", story)
        callback<Unit> { d -> c.close { d.complete(Unit) } }
    }

    @Test
    fun typingIsSentWithoutWaitingAndReportsKinds() {
        val c = offlineClient()
        assertEquals("NETWORK", callback<String?> { d -> c.sendTyping("1", IosTypingType.STICKER, "") { k, _ -> d.complete(k) } })
        assertEquals("NETWORK", callback<String?> { d -> c.sendTyping("1", IosTypingType.FILE, "7") { k, _ -> d.complete(k) } })
        assertEquals("UNKNOWN", callback<String?> { d -> c.sendTyping("x", IosTypingType.FILE, "") { k, _ -> d.complete(k) } })
        assertEquals("UNKNOWN", callback<String?> { d -> c.sendTyping("1", IosTypingType.FILE, "y") { k, _ -> d.complete(k) } })
        // the callback-free overload never throws
        c.sendTyping("x", IosTypingType.TEXT)
        callback<Unit> { d -> c.close { d.complete(Unit) } }

        // a reconnecting session is not awaited: a late typing signal is useless
        val kv = InMemoryKeyValueStore()
        CredentialStore(kv, "max.default").save(StoredCredentials("device", "instance", token = "tok", userId = 5))
        val retrying = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = true)
        val r = MaxIosClient(scope(), sessionWaitMs = 2_000) { s ->
            MaxClient(MaxClientConfig(host = "api.test", transport = retrying), kv, offline, noHttp, s)
        }
        callback<String?> { d -> r.start { p, _, _ -> d.complete(p) } }
        val started = TimeSource.Monotonic.markNow()
        assertEquals("NETWORK", callback<String?> { d -> r.sendTyping("1", IosTypingType.STICKER, "") { k, _ -> d.complete(k) } })
        assertTrue(started.elapsedNow() < 1_500.milliseconds, "waited ${started.elapsedNow()}")
        callback<Unit> { d -> r.close { d.complete(Unit) } }
    }

    @Test
    fun readersReportKindsAndAvailabilityOffline() {
        val c = offlineClient()
        val badChat = callback<Pair<Int, String?>> { d -> c.loadMessageReaders("x", "1") { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", badChat)
        val badMessage = callback<Pair<Int, String?>> { d -> c.loadMessageReaders("1", "y") { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "UNKNOWN", badMessage)
        val offline = callback<Pair<Int, String?>> { d -> c.loadMessageReaders("1", "2") { list, k, _ -> d.complete(list.size to k) } }
        assertEquals(0 to "NETWORK", offline)
        // no stored chat, or no id at all: not available, no exception
        assertFalse(c.isReadersAvailable("1"))
        assertFalse(c.isReadersAvailable("x"))
        assertFalse(c.isReadersAvailable(""))
        callback<Unit> { d -> c.close { d.complete(Unit) } }

        // a failing client creation is an error kind too
        val broken = MaxIosClient(scope()) { throw IllegalStateException("keychain read failed") }
        assertEquals(0 to "UNKNOWN", callback<Pair<Int, String?>> { d -> broken.loadMessageReaders("1", "2") { list, k, _ -> d.complete(list.size to k) } })
        assertFalse(broken.isReadersAvailable("1"))
        callback<Unit> { d -> broken.close { d.complete(Unit) } }
    }

    @Test
    fun messageReaderKeepsItsFields() {
        val reader = IosMessageReader("5", null, "👍", 0)
        assertEquals("5", reader.userId)
        assertNull(reader.name)
        assertEquals("👍", reader.reaction)
        assertEquals(0L, reader.readMark)
        val named = IosMessageReader("6", "Ann", null, 1_700_000_000_000)
        assertEquals("Ann", named.name)
        assertNull(named.reaction)
        assertEquals(1_700_000_000_000L, named.readMark)
    }

    @Test
    fun messagesAndEditEventsCarryTheEditTime() {
        fun message(updateTime: Any?) = MaxMessage.from(
            mapOf("id" to 5L, "chatId" to 7L, "sender" to 20L, "time" to 1_000L, "type" to "USER", "text" to "hi", "updateTime" to updateTime),
        )!!
        val edited = messageSnapshot(message(1_500L), "7", MaxState())
        assertEquals(1_500L, edited.updateTime)
        assertEquals(1_000L, edited.timeMs)
        assertEquals(0L, messageSnapshot(message(null), "7", MaxState()).updateTime)
        assertEquals(0L, messageSnapshot(message(0L), "7", MaxState()).updateTime)
        // the Swift initializer is unchanged and starts unedited
        assertEquals(0L, IosMessage("5", "7", "20", "hi", 1_000).updateTime)

        val event = messageEvent("edited", message(1_500L), MaxState(), withReactions = false)
        assertEquals("edited", event.kind)
        assertEquals(1_500L, event.updateTime)
        assertEquals(0L, messageEvent("message", message(null), MaxState(), withReactions = true).updateTime)
        assertEquals(0L, typingEvent(MaxEvent.Typing(10, 20, 129, null)).updateTime)
    }

    @Test
    fun typingEventCarriesTheType() {
        val withType = typingEvent(MaxEvent.Typing(10, 20, 129, null, "STICKER"))
        assertEquals("typing", withType.kind)
        assertEquals("10", withType.chatId)
        assertEquals("20", withType.authorId)
        assertEquals("STICKER", withType.text)
        assertEquals(-1, withType.unread)
        // no type or an unrecognised one is TEXT
        assertEquals("TEXT", typingEvent(MaxEvent.Typing(10, 20, 129, null)).text)
        assertEquals("TEXT", typingEvent(MaxEvent.Typing(10, 20, 129, null, "SOMETHING_NEW")).text)
        assertEquals("VIDEO_MSG", typingEvent(MaxEvent.Typing(10, 20, 129, null, "VIDEO_MSG")).text)
        assertEquals(
            listOf("TEXT", "AUDIO", "VIDEO_MSG", "PHOTO", "VIDEO", "FILE", "STICKER"),
            listOf(IosTypingType.TEXT, IosTypingType.AUDIO, IosTypingType.VIDEO_MSG, IosTypingType.PHOTO, IosTypingType.VIDEO, IosTypingType.FILE, IosTypingType.STICKER),
        )
    }

    @Test
    fun callsWaitForAReconnectingSessionThenFailAsBefore() {
        val kv = InMemoryKeyValueStore()
        CredentialStore(kv, "max.default").save(StoredCredentials("device", "instance", token = "tok", userId = 5))
        val retrying = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = true)
        val c = MaxIosClient(scope(), sessionWaitMs = 600) { s ->
            MaxClient(MaxClientConfig(host = "api.test", transport = retrying), kv, offline, noHttp, s)
        }
        callback<String?> { d -> c.start { p, _, _ -> d.complete(p) } }
        assertTrue(c.phaseName() in setOf("reconnecting", "connecting"), c.phaseName())
        val started = TimeSource.Monotonic.markNow()
        val read = callback<String?> { d -> c.markRead("1", "2") { k, _ -> d.complete(k) } }
        // The call waited for the session, then ran and failed the old way.
        assertTrue(started.elapsedNow() >= 550.milliseconds, "waited ${started.elapsedNow()}")
        assertEquals("NETWORK", read)
        callback<Unit> { d -> c.close { d.complete(Unit) } }
    }

    @Test
    fun throwingCallbacksAndCallsAfterCloseDoNotAbort() {
        val c = offlineClient()
        c.start { _, _, _ -> throw RuntimeException("callback bug") }
        // the scope survives a throwing callback
        assertNotNull(callback<String?> { d -> c.logout { k, _ -> d.complete(k ?: "ok") } })
        callback<Unit> { d -> c.close { d.complete(Unit) } }
        // after close every call still answers exactly once, with an error kind
        val kind = callback<String?> { d -> c.sendText("1", "late") { _, k, _ -> d.complete(k) } }
        assertNotNull(kind)
    }

    @Test
    fun callsAnswerWithErrorKindsOffline() {
        val c = offlineClient()
        val badCallee = callback<Pair<IosCallStart?, String?>> { d -> c.startCall("x", false) { s, k, _ -> d.complete(s to k) } }
        assertEquals(null to "UNKNOWN", badCallee)
        val call = callback<Pair<IosCallStart?, String?>> { d -> c.startCall("5", true) { s, k, _ -> d.complete(s to k) } }
        assertEquals(null to "NETWORK", call)
        val notALink = callback<Pair<IosCallStart?, String?>> { d -> c.joinCall("https://example.com/a b", false) { s, k, _ -> d.complete(s to k) } }
        assertEquals(null to "UNKNOWN", notALink)
        val link = callback<Pair<IosCallLink?, String?>> { d -> c.createCallLink { l, k, _ -> d.complete(l to k) } }
        assertEquals(null to "NETWORK", link)
        val badDelete = callback<String?> { d -> c.deleteCallHistory(listOf("x")) { k, _ -> d.complete(k) } }
        assertEquals("UNKNOWN", badDelete)
        val delete = callback<String?> { d -> c.deleteCallHistory(listOf("1")) { k, _ -> d.complete(k) } }
        assertEquals("NETWORK", delete)
        c.watchIncomingCalls { }.cancel()
        callback<Unit> { d -> c.close { d.complete(Unit) } }
    }

    @Test
    fun incomingCallCarriesTheSignalingAddressAndIceServers() {
        val params = ConversationParams(
            token = "tok",
            wsEndpoint = "wss://sig.test/ws",
            stun = "stun:s.test:3478",
            turn = listOf("turn:t.test:3478?transport=udp", "turn:t.test:443?transport=tcp"),
            turnUser = "1700000000:77",
            turnPassword = "pw",
            expiresAt = 1_700_000_100,
        )
        val event = MaxEvent.CallStart(
            callerId = 5, conversationId = "conv", type = "VIDEO", chatId = 9, isContact = true,
            vcp = null, params = params, opcode = 137, raw = null,
        )
        val call = incomingCallSnapshot(event, params, MaxState(), UserAgentInfo())
        assertEquals("conv", call.conversationId)
        assertEquals("5", call.callerId)
        assertEquals("", call.callerName)
        assertEquals("9", call.chatId)
        assertTrue(call.isVideo)
        assertEquals(77L, call.callsUserId)
        assertEquals(
            "wss://sig.test/ws?userId=77&entityType=USER&conversationId=conv&token=tok&version=5&capabilities=3c02f" +
                "&device=Google%2FPixel%208&platform=ANDROID&clientType=ONE_ME&appVersion=sdk-0.2.1.3&osVersion=34",
            call.ws2Url,
        )
        assertEquals(listOf("stun:s.test:3478"), call.stunUrls)
        assertEquals(2, call.turnUrls.size)
        assertEquals("1700000000:77", call.turnUsername)
        assertEquals("pw", call.turnPassword)
        assertEquals(1_700_000_100_000L, call.expiresAtMs)
    }

    @Test
    fun startedCallOpensTheEndpointAsTheCallSdk() {
        val signal = CallSignaling("conv", "wss://sig.test/ws?userId=3&token=t", callsUserId = 3, peerExternalId = 20, isVideo = false)
        val start = callStart(signal, UserAgentInfo(), joinLink = "")
        assertEquals("conv", start.conversationId)
        assertEquals(3L, start.callsUserId)
        assertEquals(20L, start.peerCallsUserId)
        assertTrue(start.ws2Url.startsWith("wss://sig.test/ws?userId=3&token=t&platform=ANDROID&version=5&capabilities=3c02f"))
        assertTrue(start.ws2Url.endsWith("&tgt=start"))
    }
    @Test
    fun multiSelectFormattingMembersAndContactsReportKindsOffline() {
        val c = offlineClient()
        val badForward = callback<Triple<Int, Int, String?>> { d ->
            c.forwardMessages("1", "2", listOf("3", "x")) { r, k, _ -> d.complete(Triple(r.messages.size, r.failedAt, k)) }
        }
        assertEquals(Triple(0, 0, "UNKNOWN"), badForward)
        assertEquals("UNKNOWN", callback<String?> { d -> c.forwardMessages("1", "2", emptyList()) { _, k, _ -> d.complete(k) } })
        val forward = callback<Pair<Int, String?>> { d -> c.forwardMessages("1", "2", listOf("3", "4")) { r, k, _ -> d.complete(r.failedAt to k) } }
        assertEquals(0 to "NETWORK", forward)
        assertEquals("UNKNOWN", callback<String?> { d -> c.deleteMessages("1", listOf("x"), true) { k, _ -> d.complete(k) } })
        assertEquals("NETWORK", callback<String?> { d -> c.deleteMessages("1", listOf("2", "3"), false) { k, _ -> d.complete(k) } })
        val marks = listOf(IosTextMark(IosTextMarkType.STRONG, 0, 2))
        assertEquals(null to "NETWORK", callback<Pair<IosMessage?, String?>> { d -> c.sendFormattedText("1", "hi", "", marks) { m, k, _ -> d.complete(m to k) } })
        assertEquals(null to "UNKNOWN", callback<Pair<IosMessage?, String?>> { d -> c.editFormattedText("1", "y", "hi", marks) { m, k, _ -> d.complete(m to k) } })
        assertEquals(null to "UNKNOWN", callback<Pair<IosChatMembersPage?, String?>> { d -> c.loadChatMembers("1", "x", 50) { p, k, _ -> d.complete(p to k) } })
        assertEquals(null to "NETWORK", callback<Pair<IosChatMembersPage?, String?>> { d -> c.loadChatMembers("1", "", 0) { p, k, _ -> d.complete(p to k) } })
        assertEquals(null to "UNKNOWN", callback<Pair<IosContact?, String?>> { d -> c.renameContact("x", "Ann", "") { u, k, _ -> d.complete(u to k) } })
        assertEquals("NETWORK", callback<String?> { d -> c.removeContact("5") { k, _ -> d.complete(k) } })
        val noEntries = callback<Pair<Int, String?>> { d -> c.importPhoneBook(listOf(IosPhoneContact(" ", "Ann"))) { l, k, _ -> d.complete(l.size to k) } }
        assertEquals(0 to "UNKNOWN", noEntries)
        val import = callback<Pair<Int, String?>> { d -> c.importPhoneBook(listOf(IosPhoneContact("+79990000001", "Ann"))) { l, k, _ -> d.complete(l.size to k) } }
        assertEquals(0 to "NETWORK", import)
        // the address book needs no network
        c.setAddressBook(listOf(IosPhoneContact("+79990000001", "Ann", "Lee")))
        c.setLocalName("5", "Сосед")
        assertEquals("Сосед", c.displayName("5"))
        assertEquals("", c.displayName("6"))
        assertEquals("", c.displayName("x"))
        callback<Unit> { d -> c.close { d.complete(Unit) } }

        val broken = MaxIosClient(scope()) { throw IllegalStateException("keychain read failed") }
        broken.setAddressBook(listOf(IosPhoneContact("1", "A")))
        assertEquals("", broken.displayName("5"))
        callback<Unit> { d -> broken.close { d.complete(Unit) } }
    }

    @Test
    fun marksTravelBothWays() {
        val elements = textElementsOf(
            listOf(
                IosTextMark(" strong ", 0, 2),
                IosTextMark(IosTextMarkType.LINK, 3, 4, url = "https://max.ru"),
                IosTextMark(IosTextMarkType.LINK, 3, 4),
                IosTextMark(IosTextMarkType.USER_MENTION, 0, 2, entityId = "77"),
                IosTextMark(IosTextMarkType.USER_MENTION, 0, 2, entityId = "x"),
                IosTextMark(IosTextMarkType.ANIMOJI, 0, 2, entityId = "9", lottieUrl = "https://l"),
                IosTextMark(IosTextMarkType.ANIMOJI, 0, 2, entityId = "9"),
                IosTextMark("", 0, 1),
            ),
        )
        assertEquals(listOf("STRONG", "LINK", "USER_MENTION", "ANIMOJI"), elements.map { it.type })
        assertEquals("https://max.ru", elements[1].url)
        assertEquals(77L, elements[2].entityId)

        val message = MaxMessage.from(
            mapOf(
                "id" to 5L, "chatId" to 7L, "sender" to 20L, "time" to 1_000L, "type" to "USER", "text" to "hi there",
                "elements" to listOf(mapOf("type" to "EMPHASIZED", "from" to 0, "length" to 2), mapOf("type" to "LINK", "from" to 3, "length" to 5, "attributes" to mapOf("url" to "https://a"))),
            ),
        )!!
        val snap = messageSnapshot(message, "7", MaxState())
        assertEquals(listOf(TextElementType.EMPHASIZED, TextElementType.LINK), snap.marks.map { it.type })
        assertEquals("https://a", snap.marks[1].url)
        assertEquals(2, messageEvent("edited", message, MaxState(), withReactions = false).marks.size)
        assertTrue(IosMessage("5", "7", "20", "hi", 1_000).marks.isEmpty())
        assertEquals(listOf(PhoneContact("+7999", "Ann", "Lee"), PhoneContact("1", "B")), phoneContactsOf(listOf(IosPhoneContact(" +7999 ", " Ann ", "Lee"), IosPhoneContact("1", "B", " "), IosPhoneContact("2", " "))))
    }

    @Test
    fun namesFollowContactThenAddressBookThenProfile() {
        fun user(id: Long, phone: Long, vararg names: Map<String, Any?>) = MaxUser.from(mapOf("id" to id, "phone" to phone, "names" to names.toList()))!!
        val oneme = mapOf("name" to "Ivan Petrov", "type" to "ONEME")
        var state = StateReducer.putUsers(MaxState(), listOf(user(1, 79990000001, oneme), user(2, 79990000002, mapOf("firstName" to "Vanya", "type" to "CUSTOM"), oneme)))
        state = StateReducer.setAddressBook(state, listOf(PhoneContact("+79990000001", "Brother"), PhoneContact("+79990000002", "Book")))
        val message = MaxMessage.from(mapOf("id" to 5L, "chatId" to 7L, "sender" to 1L, "time" to 1_000L, "type" to "USER", "text" to "hi"))!!
        assertEquals("Brother", messageSnapshot(message, "7", state).authorName)
        assertEquals("Vanya", messageSnapshot(message.copy(sender = 2), "7", state).authorName)
        assertEquals("Brother", nameOf(state.users.getValue(1), state))
        assertEquals("Ivan Petrov", nameOf(state.users.getValue(1), MaxState()))

        val roles = ChatRoles(owner = 1, admins = mapOf(2L to com.max.core.api.ChatAdmin(2, 3, "mod")))
        val page = ChatMembersPage(
            listOf(
                ChatMember(1, mapOf("id" to 1L, "names" to listOf(oneme)), mapOf("seen" to 1_700_000_000L, "status" to 1), emptyMap<String, Any?>()),
                ChatMember(2, mapOf("id" to 2L), null, emptyMap<String, Any?>()),
                ChatMember(3, mapOf("id" to 3L), null, emptyMap<String, Any?>()),
            ),
            0,
            emptyMap<String, Any?>(),
        )
        val members = ChatMembersResult.of(page, 0, roles).members.mapNotNull { groupMemberSnapshot(it, state) }
        assertEquals(listOf("owner", "admin", "member"), members.map { it.role })
        assertEquals(listOf("Brother", "Vanya", "Участник"), members.map { it.name })
        assertEquals("mod", members[1].alias)
        assertEquals(3, members[1].permissions)
        assertEquals(-1, members[2].permissions)
        assertEquals(1_700_000_000_000L, members[0].lastSeenMs)
        assertTrue(members[0].online)
        assertFalse(members[2].online)
    }
}
