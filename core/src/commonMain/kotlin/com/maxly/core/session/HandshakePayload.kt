package com.maxly.core.session

import kotlin.random.Random

/**
 * Builds the `SESSION_INIT` (opcode 6) request body.
 *
 * Mobile shape (kolibri `build_handshake_payload`, PyMax `MobileHandshakePayload`), keys in PyMax
 * field order:
 * ```
 * { "mt_instanceid": <instanceId, omitted if empty>,
 *   "userAgent": { deviceType, appVersion, osVersion, timezone, screen, pushDeviceType?, arch?,
 *                  locale, buildNumber?, deviceName, deviceLocale, release?, headerUserAgent?,
 *                  isPwa? },
 *   "clientSessionId": <omitted if 0>,
 *   "deviceId": <deviceId> }
 * ```
 * Both references send the same key set; they differ in details only: kolibri writes the
 * optional user-agent keys last and omits `arch` / `buildNumber` when empty / zero, PyMax omits
 * `None` values and has `release`; `isPwa` exists only in kolibri. MessagePack map order is not
 * significant to the server as far as either reference shows.
 *
 * Web shape (`deviceType = WEB`, PyMax `WebHandshakePayload.to_payload`): only `userAgent` and
 * `deviceId`; `userAgent` restricted to deviceType, locale, deviceLocale, osVersion, deviceName,
 * headerUserAgent (default [DEFAULT_WEB_HEADER_USER_AGENT]), appVersion, screen, timezone, plus
 * `isPwa` when set (kolibri; PyMax predates it). kolibri has no separate web shape.
 */
object HandshakePayload {
    fun build(device: DeviceInfo, random: Random = Random.Default): Map<String, Any?> {
        val ua = device.userAgent
        if (isWeb(ua)) {
            val web = linkedMapOf<String, Any?>(
                "deviceType" to ua.deviceType,
                "locale" to ua.locale,
                "deviceLocale" to ua.deviceLocale,
                "osVersion" to ua.osVersion,
                "deviceName" to ua.deviceName,
                "headerUserAgent" to (ua.headerUserAgent ?: DEFAULT_WEB_HEADER_USER_AGENT),
                "appVersion" to ua.appVersion,
                "screen" to ua.screen,
                "timezone" to ua.timezone,
            )
            ua.isPwa?.let { web["isPwa"] = it }
            return linkedMapOf("userAgent" to web, "deviceId" to device.deviceId)
        }

        val userAgent = mobileUserAgent(ua)

        val root = linkedMapOf<String, Any?>()
        if (device.instanceId.isNotEmpty()) root["mt_instanceid"] = device.instanceId
        root["userAgent"] = userAgent
        val clientSessionId = device.clientSessionId ?: random.nextLong(1, 71)
        if (clientSessionId != 0L) root["clientSessionId"] = clientSessionId
        root["deviceId"] = device.deviceId
        return root
    }

    /**
     * The mobile `userAgent` map (also sent inside the stored-token `LOGIN`, PyMax
     * `SyncPayload.user_agent`): PyMax field order, `null` / empty / zero optional fields omitted.
     */
    fun mobileUserAgent(ua: UserAgentInfo): LinkedHashMap<String, Any?> {
        val userAgent = linkedMapOf<String, Any?>(
            "deviceType" to ua.deviceType,
            "appVersion" to ua.appVersion,
            "osVersion" to ua.osVersion,
            "timezone" to ua.timezone,
            "screen" to ua.screen,
        )
        ua.pushDeviceType?.let { userAgent["pushDeviceType"] = it }
        ua.arch?.takeIf { it.isNotEmpty() }?.let { userAgent["arch"] = it }
        userAgent["locale"] = ua.locale
        ua.buildNumber?.takeIf { it != 0L }?.let { userAgent["buildNumber"] = it }
        userAgent["deviceName"] = ua.deviceName
        userAgent["deviceLocale"] = ua.deviceLocale
        ua.release?.let { userAgent["release"] = it }
        ua.headerUserAgent?.let { userAgent["headerUserAgent"] = it }
        ua.isPwa?.let { userAgent["isPwa"] = it }
        return userAgent
    }

    /** `true` for PyMax's web device type, which switches handshake and login to the web shapes. */
    fun isWeb(ua: UserAgentInfo): Boolean = ua.deviceType.equals("WEB", ignoreCase = true)
}

/**
 * Fields of the `SESSION_INIT` reply. [payload] keeps the whole map for anything else
 * (`reg-country-code`, `location`, ...).
 *
 * @property callsSeed `callsSeed` (kolibri `HandshakeInfo`, PyMax `HandshakeResponse`); PyMax's
 *   token login (opcode 19) needs it for its device fingerprint. kolibri notes that `IOS` gets no
 *   `callsSeed`.
 * @property deviceName `device_name` (kolibri).
 * @property appUpdateType `app-update-type` (PyMax).
 */
data class HandshakeInfo(
    val callsSeed: Long?,
    val deviceName: String?,
    val appUpdateType: Long?,
    val payload: Map<*, *>?,
) {
    companion object {
        fun from(payload: Any?): HandshakeInfo {
            val map = payload as? Map<*, *>
            return HandshakeInfo(
                callsSeed = (map?.get("callsSeed") as? Number)?.toLong(),
                deviceName = map?.get("device_name") as? String,
                appUpdateType = (map?.get("app-update-type") as? Number)?.toLong(),
                payload = map,
            )
        }
    }
}
