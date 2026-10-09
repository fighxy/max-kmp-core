package com.maxly.core.api

import com.maxly.core.state.MaxState
import com.maxly.core.state.StateReducer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the shared Maxly fixtures vendored under `src/jvmTest/resources/fixtures/`.
 * Source: fighxy/Maxly, branch `ios/evening`, commit 8f859e1, `test-fixtures/` (see
 * `fixtures/SOURCE.md`); update the copies, not the cases, when the shared rule changes.
 */
class SharedFixturesTest {
    private fun load(path: String): JsonObject {
        val text = requireNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun cases(path: String): List<JsonObject> = load(path)["cases"]!!.jsonArray.map { it.jsonObject }

    private val JsonObject.name: String get() = this["name"]!!.jsonPrimitive.content

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    /** JSON to the plain values the protocol decoder produces (maps, lists, Long, String...). */
    private fun JsonElement.plain(): Any? = when (this) {
        is JsonNull -> null
        is JsonObject -> entries.associate { (k, v) -> k to v.plain() }
        is JsonArray -> map { it.plain() }
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: content.toDouble()
    }

    @Test
    fun phoneNormalize() {
        val all = cases("names/phone-normalize.json")
        assertTrue(all.size >= 20)
        val failures = all.mapNotNull { c ->
            val got = PhoneNumbers.normalize(c["raw"].str())
            val want = c["expect"].str()
            if (got == want) null else "${c.name}: ${c["raw"]} -> $got, expected $want"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun phoneNormalizeServerNumbers() {
        // The server sends `phone` as a number (`79131234567`): a Russian or Kazakh `+7` number of
        // the fixtures comes out the same from its digits.
        var checked = 0
        for (c in cases("names/phone-normalize.json")) {
            val want = c["expect"].str() ?: continue
            if (!want.startsWith("+7") || want.length != 12) continue
            assertEquals(want, PhoneNumbers.normalize(want.removePrefix("+").toLong()), c.name)
            checked++
        }
        assertTrue(checked > 5)
    }

    private fun book(entries: JsonArray): List<PhoneContact> = entries.flatMap { e ->
        val o = e.jsonObject
        val name = o["name"].str().orEmpty()
        o["phones"]!!.jsonArray.map { PhoneContact(it.jsonPrimitive.content, name) }
    }

    @Test
    fun addressBook() {
        for (c in cases("names/address-book.json")) {
            val state = StateReducer.setAddressBook(MaxState(), book(c["entries"]!!.jsonArray))
            val want = c["expect"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(want, state.addressBook, c.name)
        }
    }

    @Test
    fun displayName() {
        for (c in cases("names/display-name.json")) {
            val userJson = c["user"]!!.jsonObject
            val raw = LinkedHashMap<String, Any?>()
            raw["id"] = 42L
            // the server sends the phone as a number; a formatted string stands for one the device has
            val phone = userJson["phone"].str()
            if (!phone.isNullOrEmpty()) raw["phone"] = phone.filter { it.isDigit() }.toLong()
            raw["names"] = userJson["names"]!!.plain()
            val user = requireNotNull(MaxUser.from(raw)) { c.name }
            val enabled = c["addressBookEnabled"]?.jsonPrimitive?.booleanOrNull ?: true
            var state = StateReducer.putUsers(MaxState(), listOf(user))
            state = StateReducer.setAddressBook(state, if (enabled) book(c["addressBook"]!!.jsonArray) else emptyList())
            val want = c["expect"].str()
            assertEquals(want, state.displayLabel(42L), c.name)
        }
    }

    private fun draft(o: JsonElement?, chatId: Long): MaxDraft? {
        val m = (o as? JsonObject) ?: return null
        val text = m["text"].str().orEmpty()
        val elements = (m["spans"] as? JsonArray).orEmpty().map { s ->
            val so = s.jsonObject
            TextElement(so["type"]!!.jsonPrimitive.content, so["from"]!!.jsonPrimitive.longOrNull!!.toInt(), so["length"]!!.jsonPrimitive.longOrNull!!.toInt())
        }
        return MaxDraft(chatId, text, elements, m["replyTo"].str()?.toLong(), m["updateTime"]!!.jsonPrimitive.longOrNull!!)
    }

    private fun assertDraft(want: MaxDraft?, got: MaxDraft?, name: String) {
        assertEquals(want?.text, got?.text, name)
        assertEquals(want?.updateTime, got?.updateTime, name)
        assertEquals(want?.replyTo, got?.replyTo, name)
        assertEquals(want?.elements, got?.elements, name)
    }

    private fun serverPush(d: MaxDraft) =
        com.maxly.core.events.MaxEvent.DraftSaved(d.chatId, null, d.updateTime, d.text, d.elements, d.replyTo, emptyMap<Any?, Any?>(), 152, null)

    private fun discardPush(chatId: Long, time: Long) =
        com.maxly.core.events.MaxEvent.DraftDiscarded(chatId, null, time, 153, null)

    /**
     * `drafts/merge` as the apps use it: the local draft stays in the app, the server draft
     * arrives as push 152 and the discard as push 153 (both orders), or both in one `LOGIN`
     * snapshot; the composer shows [Drafts.reconcile] of the local draft, the store's draft and
     * the store's discard mark.
     */
    @Test
    fun draftMerge() {
        val chatId = -7000L
        for (c in cases("drafts/merge.json")) {
            val local = draft(c["local"], chatId)
            val server = draft(c["server"], chatId)
            val discardedAt = c["discardedAt"]?.jsonPrimitive?.longOrNull
            val want = draft(c["expect"], chatId)
            val pushes = listOfNotNull(server?.let(::serverPush), discardedAt?.let { discardPush(chatId, it) })
            for ((order, events) in listOf("152,153" to pushes, "153,152" to pushes.reversed())) {
                var state = MaxState()
                for (e in events) state = StateReducer.reduce(state, e, 0L)
                assertDraft(want, Drafts.reconcile(local, state.draftOf(chatId), state.draftDiscardedAt(chatId)), "${c.name} [$order]")
            }
            val snapshot = DraftsSnapshot(listOfNotNull(server).associateBy { it.chatId }, listOfNotNull(discardedAt).associateBy { chatId })
            val state = StateReducer.putDrafts(MaxState(), snapshot)
            assertDraft(want, Drafts.reconcile(local, state.draftOf(chatId), state.draftDiscardedAt(chatId)), "${c.name} [LOGIN]")
        }
    }

    /**
     * `drafts/merge` with the local draft kept in the store too (an own confirmed save), the
     * server draft as push 152 and the discard as push 153: the store alone reaches the result.
     */
    @Test
    fun draftMergeInStore() {
        val chatId = -7000L
        for (c in cases("drafts/merge.json")) {
            var state = MaxState()
            draft(c["local"], chatId)?.takeUnless { it.isEmpty }?.let { state = StateReducer.putDraft(state, it) }
            draft(c["server"], chatId)?.let { state = StateReducer.reduce(state, serverPush(it), 0L) }
            c["discardedAt"]?.jsonPrimitive?.longOrNull?.let { state = StateReducer.reduce(state, discardPush(chatId, it), 0L) }
            assertDraft(draft(c["expect"], chatId), state.drafts[chatId], c.name)
        }
    }

    /** `members/search`: [MemberSearch] over the loaded members (full name and mention name). */
    @Test
    fun memberSearch() {
        for (c in cases("members/search.json")) {
            val members = c["members"]!!.jsonArray.map { it.jsonObject }
            val found = MemberSearch.filter(members, c["query"]!!.jsonPrimitive.content, { it["name"].str() }, { it["mentionName"].str() })
            assertEquals(c["expect"]!!.jsonArray.map { it.jsonPrimitive.content }, found.map { it["id"]!!.jsonPrimitive.content }, c.name)
        }
    }

    private fun JsonElement?.id(): Long? = this.str()?.toLongOrNull()

    /** `formatting/parse-*`: [TextElement.parseAll] of the received `elements` over the text. */
    @Test
    fun formattingParse() {
        val files = listOf("parse-defaults", "parse-overlap", "parse-types", "parse-unknown", "parse-utf16")
        for (f in files) for (c in cases("formatting/$f.json")) {
            val text = c["text"]!!.jsonPrimitive.content
            val got = TextElement.parseAll(c["elements"]?.plain(), text.length)
            val want = c["expect"]!!.jsonObject["spans"]!!.jsonArray.map { span ->
                val o = span.jsonObject
                val known = setOf("type", "from", "length", "url", "text")
                buildMap<String, Any?> {
                    put("type", o["type"]!!.jsonPrimitive.content)
                    put("from", o["from"]!!.jsonPrimitive.longOrNull!!.toInt())
                    put("length", o["length"]!!.jsonPrimitive.longOrNull!!.toInt())
                    o["url"]?.let { put("attributes", mapOf("url" to it.jsonPrimitive.content)) }
                    for ((k, v) in o) if (k !in known) put(k, v.plain())
                }
            }
            assertEquals(wholeNumbers(want), got.map { wholeNumbers(it.toPayload()) }, "$f/${c.name}")
            // what goes back out on an edit is the element as it came (unknown keys included)
            assertEquals(got, TextElementsJson.parse(TextElementsJson.write(got), text.length), "$f/${c.name} round trip")
        }
    }

    /**
     * `drafts/outgoing`: the address ([Drafts.address]) and the request body [DraftsApi] sends for
     * the expected draft. Whether to send (trim, compare with the server draft) is the apps' part.
     */
    @Test
    fun draftOutgoingPayloads() = kotlinx.coroutines.test.runTest {
        for (c in cases("drafts/outgoing.json")) {
            val expect = c["expect"]!!.jsonObject
            val request = expect["request"].str() ?: continue
            val me = c["me"].id()!!
            val chatJson = c["chat"]!!.jsonObject
            val chatId = chatJson["id"].id()!!
            val peer = chatJson["peerId"].id()
            val chat = Chat.from(
                buildMap {
                    put("id", chatId)
                    put("type", chatJson["type"]!!.jsonPrimitive.content)
                    if (peer != null) put("participants", mapOf(me.toString() to 0L, peer.toString() to 0L))
                },
            )
            val sink = ScriptSink(mapOf("time" to 1L))
            val api = DraftsApi(sink)
            val address = Drafts.address(chatId, chat, me)
            val want = (expect["payload"]!!.plain() as Map<*, *>)
            when (request) {
                "DRAFT_SAVE" -> {
                    val d = want["draft"] as Map<*, *>
                    val text = d["text"] as? String ?: ""
                    val elements = TextElement.parseAll(d["elements"], text.length)
                    api.saveDraft(address, text, elements, (d["replyTo"] as? String)?.toLong())
                }
                "DRAFT_DISCARD" -> api.discardDraft(address, want["time"].toString().toLong())
                else -> error("unknown request $request in ${c.name}")
            }
            assertEquals(normalize(want), normalize(sink.sent.single().second), c.name)
        }
    }

    /** Ids as decimal strings, numbers as Long: the fixtures write ids as strings. */
    private fun normalize(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to normalize(x) }
        is List<*> -> v.map(::normalize)
        is Number -> v.toLong().toString()
        else -> v
    }

    private fun member(o: JsonObject): ChatMember =
        ChatMember(o["id"].id(), mapOf("id" to o["id"].id(), "names" to listOf(mapOf("name" to o["name"].str()))), null, emptyMap<Any?, Any?>())

    /** `members/paging`: [ChatMembersResult.of] and [ChatMembersResult.appendTo] over the scripted pages. */
    @Test
    fun membersPaging() {
        for (c in cases("members/paging.json")) {
            val pages = c["pages"]!!.jsonArray.map { it.jsonObject }
            val requests = ArrayList<Long>()
            var loaded = emptyList<ChatMemberEntry>()
            var marker: Long? = 0L
            var i = 0
            while (marker != null && i < pages.size) {
                requests += marker
                val p = pages[i++]
                val page = ChatMembersPage(p["members"]!!.jsonArray.map { member(it.jsonObject) }, p["marker"].str()?.toLongOrNull(), emptyMap<Any?, Any?>())
                val step = ChatMembersResult.of(page, marker, ChatRoles.NONE).appendTo(loaded)
                loaded = step.members
                marker = step.nextMarker
            }
            val expect = c["expect"]!!.jsonObject
            assertEquals(expect["requests"]!!.jsonArray.map { it.jsonPrimitive.longOrNull }, requests, c.name)
            assertEquals(expect["ids"]!!.jsonArray.map { it.id() }, loaded.map { it.userId }, c.name)
        }
    }

    /** `members/roles`: roles from the chat card ([ChatRoles]), list order [ChatMembersResult.byRole], badge text in the test. */
    @Test
    fun membersRoles() {
        for (c in cases("members/roles.json")) {
            val roles = ChatRoles.of(c["chat"]!!.plain() as Map<*, *>)
            val entries = c["members"]!!.jsonArray.map { ChatMemberEntry.of(member(it.jsonObject), roles) }
            val got = ChatMembersResult.byRole(entries).map { e ->
                val badge = when (e.role) {
                    ChatMemberRole.OWNER -> "владелец"
                    ChatMemberRole.ADMIN -> e.admin?.alias ?: "админ"
                    ChatMemberRole.MEMBER -> null
                }
                e.userId.toString() to badge
            }
            val want = c["expect"]!!.jsonArray.map { it.jsonObject["id"].str() to it.jsonObject["badge"].str() }
            assertEquals(want, got, c.name)
        }
    }

    /** `selection/delete`: [MessageDeletion.scope] per message and [MessageDeletion.summary]. */
    @Test
    fun selectionDelete() {
        for (c in cases("selection/delete.json")) {
            val me = c["me"].id()
            val chatJson = c["chat"]!!.jsonObject
            val chatId = chatJson["id"].id()!!
            val kind = DeleteChatKind.of(chatId, Chat.from(mapOf("id" to chatId, "type" to chatJson["type"]!!.jsonPrimitive.content)))
            val admin = chatJson["admin"]?.jsonPrimitive?.booleanOrNull ?: false
            val now = c["now"]!!.jsonPrimitive.longOrNull!!
            val timeout = c["editTimeout"]?.jsonPrimitive?.longOrNull ?: 0L
            val messages = c["messages"]!!.jsonArray.map { it.jsonObject }
            // fixture ids may be local strings: index them
            val scopes = LinkedHashMap<Long, DeleteScope>()
            messages.forEachIndexed { i, m ->
                scopes[i.toLong()] = MessageDeletion.scope(
                    kind = kind,
                    own = m["author"].id() == me,
                    sent = m["state"].str() == "sent",
                    timeMs = m["time"]!!.jsonPrimitive.longOrNull!!,
                    nowMs = now,
                    editTimeoutSeconds = timeout,
                    canDeleteAny = admin,
                )
            }
            val plan = MessageDeletion.summary(kind, scopes)
            val expect = c["expect"]!!.jsonObject
            val wantScopes = expect["scopes"]!!.jsonObject
            assertEquals(messages.map { wantScopes[it["id"].str()]!!.jsonPrimitive.content }, scopes.values.map { it.name.lowercase() }, c.name)
            assertEquals(expect["canDelete"]!!.jsonPrimitive.booleanOrNull, plan.canDelete, "${c.name} canDelete")
            assertEquals(expect["showsForEveryone"]!!.jsonPrimitive.booleanOrNull, plan.showsForEveryone, "${c.name} showsForEveryone")
            assertEquals(expect["forEveryoneByDefault"]!!.jsonPrimitive.booleanOrNull, plan.forEveryoneByDefault, "${c.name} forEveryoneByDefault")
            assertEquals(expect["forcesForEveryone"]!!.jsonPrimitive.booleanOrNull, plan.forcesForEveryone, "${c.name} forcesForEveryone")
        }
    }

    /**
     * `selection/forward`: message order by [ForwardOrder.of]; the comment trimmed and skipped
     * when blank, as `MaxClient.forwardMessages` does per target. Several targets (comment to
     * each first, then each message to every target) are the apps' loop.
     */
    @Test
    fun selectionForward() {
        for (c in cases("selection/forward.json")) {
            val messages = c["messages"]!!.jsonArray.map { it.jsonObject }
            val times = messages.associate { it["id"].id()!! to it["time"]!!.jsonPrimitive.longOrNull!! }
            val order = ForwardOrder.of(messages.map { it["id"].id()!! }, times)
            val targets = c["targets"]!!.jsonArray.map { it.jsonPrimitive.content }.distinct()
            val comment = c["comment"].str()?.trim().orEmpty()
            val got = buildList {
                if (comment.isNotEmpty()) for (t in targets) add(mapOf("target" to t, "comment" to comment))
                for (id in order) for (t in targets) add(mapOf("target" to t, "messageId" to id.toString()))
            }
            val want = c["expect"]!!.jsonObject["requests"]!!.jsonArray.map { r -> r.jsonObject.mapValues { it.value.jsonPrimitive.content } }
            assertEquals(want, got, c.name)
        }
    }

    /** [v] with every integer number as `Long` (the fixture builds `Int`s, decoded JSON has `Long`s). */
    private fun wholeNumbers(v: Any?): Any? = when (v) {
        is Byte, is Short, is Int -> (v as Number).toLong()
        is Map<*, *> -> v.entries.associate { (k, x) -> k to wholeNumbers(x) }
        is List<*> -> v.map(::wholeNumbers)
        else -> v
    }
}
