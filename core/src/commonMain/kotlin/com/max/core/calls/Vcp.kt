package com.max.core.calls

import com.max.core.protocol.Lz4
import com.max.core.session.UserAgentInfo
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * ICE server in the shape a WebRTC stack expects (kolibri `IceServer`).
 */
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/**
 * ws2 query params that do not come from the server (kolibri `Ws2ClientInfo`).
 *
 * Device identity is the Android Pixel 8 profile ([UserAgentInfo] defaults), not the kolibri-net
 * SDK strings. `capabilities` and `clientType` stay the ws2 signaling constants.
 */
data class Ws2ClientInfo(
    val capabilities: String = DEFAULT.capabilities,
    val device: String = DEFAULT.device,
    val platform: String = DEFAULT.platform,
    val clientType: String = DEFAULT.clientType,
    val appVersion: String = DEFAULT.appVersion,
    val osVersion: String = DEFAULT.osVersion,
) {
    companion object {
        private val profile = UserAgentInfo()

        val DEFAULT = Ws2ClientInfo(
            capabilities = "3c03f",
            device = profile.deviceName,
            platform = "ANDROID",
            clientType = "ONE_ME",
            appVersion = profile.appVersion,
            osVersion = profile.osVersion,
        )
    }
}

/**
 * Call connection params (`vcp`), sent in the incoming-call push (`NOTIF_CALL_START` 137)
 * and the outgoing-call response.
 *
 * Wire format (kolibri `kolibri-net/src/calls/vcp.rs`): `<rawLen>:<base64(LZ4-block(JSON))>`.
 * JSON keys are short: `tkn`, `wse` required; `wsip`, `wte`, `vcae`, `srcp`, `et`, `stne`,
 * `trne` (CSV), `trnu`, `trnp`, `iv` optional. PyMax has no `vcp` decoder.
 */
data class ConversationParams(
    val token: String,
    val wsEndpoint: String,
    val wsIps: List<String> = emptyList(),
    val wtEndpoint: String? = null,
    val callsApiEndpoint: String? = null,
    val clientType: String? = null,
    val expiresAt: Long? = null,
    val stun: String? = null,
    val turn: List<String> = emptyList(),
    val turnUser: String? = null,
    val turnPassword: String? = null,
    val isVideo: Boolean = false,
) {
    fun iceServers(): List<IceServer> {
        val servers = ArrayList<IceServer>(2)
        stun?.takeIf { it.isNotEmpty() }?.let { servers += IceServer(urls = listOf(it)) }
        if (turn.isNotEmpty()) {
            servers += IceServer(urls = turn, username = turnUser, credential = turnPassword)
        }
        return servers
    }

    /** Treats "expires within 5s" as expired. [nowSecs] is unix time in seconds. */
    fun isExpired(nowSecs: Long): Boolean = expiresAt?.let { nowSecs >= it - 5 } ?: false

    /** Calls user id: the part after the last `:` in the TURN username, or 0. */
    fun userId(): Long =
        turnUser?.substringAfterLast(':')?.toLongOrNull() ?: 0L

    /** ws2 connect URL for an incoming call (kolibri `ConversationParams::ws2_url`). */
    fun ws2Url(conversationId: String, client: Ws2ClientInfo = Ws2ClientInfo.DEFAULT): String =
        setQuery(
            wsEndpoint,
            listOf(
                "userId" to userId().toString(),
                "entityType" to "USER",
                "conversationId" to conversationId,
                "token" to token,
                "version" to "5",
                "capabilities" to client.capabilities,
                "device" to client.device,
                "platform" to client.platform,
                "clientType" to client.clientType,
                "appVersion" to client.appVersion,
                "osVersion" to client.osVersion,
            ),
        )

    companion object {
        /**
         * Advertised decompressed size above this is rejected before Base64 or LZ4 run.
         * Call JSON is a few kilobytes; the prefix must not choose the allocation size.
         */
        const val MAX_RAW_BYTES: Int = 64 * 1024

        /**
         * Parses a `vcp` string. Returns `null` on a missing colon, a non-positive or oversized
         * length, bad Base64, a corrupt LZ4 block, invalid JSON, or a missing `tkn` / `wse`.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun decode(vcp: String): ConversationParams? {
            val sep = vcp.indexOf(':')
            if (sep <= 0) return null
            val rawLen = vcp.substring(0, sep).toIntOrNull() ?: return null
            if (rawLen <= 0 || rawLen > MAX_RAW_BYTES) return null
            val compressed = runCatching { Base64.decode(vcp.substring(sep + 1)) }.getOrNull() ?: return null
            val decompressed = runCatching { Lz4.decompressBlock(compressed, rawLen) }.getOrNull() ?: return null
            val bytes = if (decompressed.size > rawLen) decompressed.copyOf(rawLen) else decompressed
            val obj = runCatching {
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            }.getOrNull() ?: return null
            val token = obj.str("tkn") ?: return null
            val wsEndpoint = obj.str("wse") ?: return null
            return ConversationParams(
                token = token,
                wsEndpoint = wsEndpoint,
                wsIps = obj.stringList("wsip"),
                wtEndpoint = obj.str("wte"),
                callsApiEndpoint = obj.str("vcae"),
                clientType = obj.str("srcp"),
                expiresAt = obj.long("et"),
                stun = obj.str("stne"),
                turn = obj.csv("trne"),
                turnUser = obj.str("trnu"),
                turnPassword = obj.str("trnp"),
                isVideo = obj.bool("iv") ?: false,
            )
        }
    }
}

/**
 * Append client params to an outgoing-call `endpoint` (already carries token and
 * conversation/user ids), overriding on key clash (kolibri `ws2_url_from_endpoint`).
 */
fun ws2UrlFromEndpoint(endpoint: String, client: Ws2ClientInfo = Ws2ClientInfo.DEFAULT): String =
    mergeQuery(
        endpoint,
        listOf(
            "platform" to client.platform,
            "version" to "5",
            "capabilities" to client.capabilities,
            "clientType" to client.clientType,
            "appVersion" to client.appVersion,
            "device" to client.device,
            "tgt" to "start",
        ),
    )

private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun kotlinx.serialization.json.JsonObject.long(key: String): Long? =
    runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

private fun kotlinx.serialization.json.JsonObject.bool(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
    runCatching {
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    }.getOrNull() ?: emptyList()

private fun kotlinx.serialization.json.JsonObject.csv(key: String): List<String> =
    str(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/** Replace the whole query of [base] with [params] (kolibri `set_query`). */
internal fun setQuery(base: String, params: List<Pair<String, String>>): String {
    val path = base.substringBefore('?')
    val query = params.joinToString("&") { (k, v) -> "$k=${encodeQuery(v)}" }
    return "$path?$query"
}

/** Merge [extra] into the existing query of [base]; extra wins on clash (kolibri `merge_query`). */
internal fun mergeQuery(base: String, extra: List<Pair<String, String>>): String {
    val path = base.substringBefore('?')
    val existing = base.substringAfter('?', missingDelimiterValue = "")
    val pairs = ArrayList<Pair<String, String>>()
    for (pair in existing.split('&').filter { it.isNotEmpty() }) {
        val eq = pair.indexOf('=')
        pairs += if (eq < 0) pair to "" else pair.substring(0, eq) to pair.substring(eq + 1)
    }
    for ((k, v) in extra) {
        val encoded = encodeQuery(v)
        val i = pairs.indexOfFirst { it.first == k }
        if (i >= 0) pairs[i] = k to encoded else pairs += k to encoded
    }
    val query = pairs.joinToString("&") { (k, v) -> "$k=$v" }
    return if (query.isEmpty()) path else "$path?$query"
}

/** RFC 3986 unreserved pass through; everything else `%XX` uppercase (kolibri `encode_query`). */
internal fun encodeQuery(s: String): String {
    val out = StringBuilder(s.length)
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ok = c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (ok) out.append(c.toChar()) else out.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
    return out.toString()
}
