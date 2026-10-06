package com.max.core.calls

import com.max.core.events.EventParser
import com.max.core.events.MaxEvent
import com.max.core.protocol.Lz4
import com.max.core.protocol.Opcode
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@OptIn(ExperimentalEncodingApi::class)
class VcpTest {

    private fun encodeVcp(json: String): String {
        val raw = json.encodeToByteArray()
        val compressed = Lz4.compressBlock(raw)
        return "${raw.size}:${Base64.encode(compressed)}"
    }

    private fun sampleJson(): String = buildJsonObject {
        put("tkn", "sig-token")
        put("wse", "wss://sig.example/websocket?drop=1")
        putJsonArray("wsip") {
            add(kotlinx.serialization.json.JsonPrimitive("1.2.3.4"))
            add(kotlinx.serialization.json.JsonPrimitive("5.6.7.8"))
        }
        put("wte", "https://wt.example")
        put("vcae", "https://calls.okcdn.ru/fb.do")
        put("srcp", "SDK")
        put("et", 1_800_000_000L)
        put("stne", "stun:stun.example:3478")
        put("trne", "turn:turn1.example:3478, turn:turn2.example:3478")
        put("trnu", "ok:42")
        put("trnp", "secret+pass")
        put("iv", true)
    }.toString()

    @Test
    fun decodeRoundTrip() {
        val params = ConversationParams.decode(encodeVcp(sampleJson()))!!
        assertEquals("sig-token", params.token)
        assertEquals("wss://sig.example/websocket?drop=1", params.wsEndpoint)
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), params.wsIps)
        assertEquals("https://wt.example", params.wtEndpoint)
        assertEquals("https://calls.okcdn.ru/fb.do", params.callsApiEndpoint)
        assertEquals("SDK", params.clientType)
        assertEquals(1_800_000_000L, params.expiresAt)
        assertEquals("stun:stun.example:3478", params.stun)
        assertEquals(listOf("turn:turn1.example:3478", "turn:turn2.example:3478"), params.turn)
        assertEquals("ok:42", params.turnUser)
        assertEquals("secret+pass", params.turnPassword)
        assertTrue(params.isVideo)
        assertEquals(42L, params.userId())
        assertFalse(params.isExpired(1_799_999_990))
        assertTrue(params.isExpired(1_799_999_995))
        val ice = params.iceServers()
        assertEquals(2, ice.size)
        assertEquals(listOf("stun:stun.example:3478"), ice[0].urls)
        assertNull(ice[0].username)
        assertEquals(listOf("turn:turn1.example:3478", "turn:turn2.example:3478"), ice[1].urls)
        assertEquals("ok:42", ice[1].username)
        assertEquals("secret+pass", ice[1].credential)
    }

    @Test
    fun incomingWs2UrlReplacesQuery() {
        val params = ConversationParams(
            token = "tok a",
            wsEndpoint = "wss://sig.example/websocket?drop=1",
            turnUser = "realm:99",
        )
        val url = params.ws2Url("conv-id")
        assertEquals(
            "wss://sig.example/websocket?userId=99&entityType=USER&conversationId=conv-id&token=tok%20a&version=5&capabilities=3c03f&device=Pixel%208&platform=ANDROID&clientType=ONE_ME&appVersion=26.25.0&osVersion=Android%2014",
            url,
        )
    }

    @Test
    fun outgoingWs2UrlMergesQuery() {
        val url = ws2UrlFromEndpoint("wss://sig.example/websocket?userId=1&token=abc&platform=WEB")
        assertTrue(url.startsWith("wss://sig.example/websocket?"))
        assertTrue(url.contains("userId=1"))
        assertTrue(url.contains("token=abc"))
        assertTrue(url.contains("platform=ANDROID"))
        assertTrue(url.contains("device=Pixel%208"))
        assertTrue(url.contains("appVersion=26.25.0"))
        assertTrue(url.contains("tgt=start"))
        assertTrue(url.contains("osVersion=Android%2014"))
        assertTrue(url.contains("version=5"))
        assertFalse(url.contains("platform=WEB"))
    }

    @Test
    fun encodeQueryUppercaseHex() {
        assertEquals("a-b_c.d~e", encodeQuery("a-b_c.d~e"))
        assertEquals("tok%20a%2Bb", encodeQuery("tok a+b"))
    }

    @Test
    fun decodeRejectsMalformed() {
        assertNull(ConversationParams.decode(""))
        assertNull(ConversationParams.decode(":abc"))
        assertNull(ConversationParams.decode("0:abc"))
        assertNull(ConversationParams.decode("4:@@@"))
        val blob = Base64.encode(byteArrayOf(0))
        assertNull(ConversationParams.decode("${Int.MAX_VALUE}:$blob"))
        assertNull(ConversationParams.decode("${ConversationParams.MAX_RAW_BYTES + 1}:$blob"))
        val missingTkn = encodeVcp(buildJsonObject { put("wse", "wss://x") }.toString())
        assertNull(ConversationParams.decode(missingTkn))
        val missingWse = encodeVcp(buildJsonObject { put("tkn", "t") }.toString())
        assertNull(ConversationParams.decode(missingWse))
    }

    @Test
    fun defaultsOmitVideoAndUserId() {
        val params = ConversationParams.decode(
            encodeVcp(buildJsonObject { put("tkn", "t"); put("wse", "wss://x") }.toString()),
        )!!
        assertFalse(params.isVideo)
        assertEquals(0L, params.userId())
        assertFalse(params.isExpired(0))
        assertTrue(params.iceServers().isEmpty())
    }

    @Test
    fun callStartEventDecodesVcp() {
        val vcp = encodeVcp(sampleJson())
        val raw = mapOf(
            "callerId" to 9001L,
            "conversationId" to "conv-uuid",
            "type" to "VIDEO",
            "chatId" to 0L,
            "isContact" to true,
            "vcp" to vcp,
        )
        val e = assertIs<MaxEvent.CallStart>(EventParser.parse(Opcode.NOTIF_CALL_START.value, 0, raw))
        assertEquals(9001L, e.callerId)
        assertEquals("conv-uuid", e.conversationId)
        assertEquals("VIDEO", e.type)
        assertEquals(0L, e.chatId)
        assertEquals(true, e.isContact)
        assertEquals(vcp, e.vcp)
        assertEquals("sig-token", e.params!!.token)
        assertTrue(e.params!!.isVideo)
    }

    @Test
    fun callStartKeepsTypedEventWhenVcpIsCorrupt() {
        val raw = mapOf("callerId" to 1, "conversationId" to "c", "vcp" to "not-a-vcp")
        val e = assertIs<MaxEvent.CallStart>(EventParser.parse(137, 0, raw))
        assertNull(e.params)
        assertEquals("not-a-vcp", e.vcp)
        assertIs<MaxEvent.Unknown>(EventParser.parse(137, 0, mapOf("callerId" to 1)))
        assertIs<MaxEvent.Unknown>(EventParser.parse(137, 1, mapOf("callerId" to 1, "conversationId" to "c")))
    }

    @Test
    fun callsClientInfoLooksLikeTheCallSdkOnTheProfileDevice() {
        val info = Ws2ClientInfo.forCalls()
        assertEquals("3c02f", info.capabilities)
        assertEquals("Google/Pixel 8", info.device)
        assertEquals("ANDROID", info.platform)
        assertEquals("ONE_ME", info.clientType)
        assertEquals("sdk-0.2.1.3", info.appVersion)
        assertEquals("34", info.osVersion)
        assertEquals("Samsung/Galaxy S24", Ws2ClientInfo.callsDevice("Samsung Galaxy S24"))
        assertEquals("33", Ws2ClientInfo.androidApiLevel("Android 13").toString())
        assertEquals(36, Ws2ClientInfo.androidApiLevel("Android 16"))
        assertEquals(34, Ws2ClientInfo.androidApiLevel("iOS 18"))
        val url = ws2UrlFromEndpoint("wss://sig/ws?userId=1&token=t", info)
        assertEquals(
            "wss://sig/ws?userId=1&token=t&platform=ANDROID&version=5&capabilities=3c02f&clientType=ONE_ME&appVersion=sdk-0.2.1.3&device=Google%2FPixel%208&osVersion=34&tgt=start",
            url,
        )
    }
}
