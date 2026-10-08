package com.max.core.api

import com.max.core.state.MaxState
import com.max.core.state.StateReducer
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
 * Replays the shared Orbitle fixtures vendored under `src/jvmTest/resources/fixtures/`.
 * Source: fighxy/Orbitle, branch `ios/evening`, commit 454e12d, `test-fixtures/` (see
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
        com.max.core.events.MaxEvent.DraftSaved(d.chatId, null, d.updateTime, d.text, d.elements, d.replyTo, emptyMap<Any?, Any?>(), 152, null)

    private fun discardPush(chatId: Long, time: Long) =
        com.max.core.events.MaxEvent.DraftDiscarded(chatId, null, time, 153, null)

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

}
