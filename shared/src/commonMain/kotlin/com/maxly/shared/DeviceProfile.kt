package com.maxly.shared

import com.maxly.core.session.UserAgentInfo

/**
 * The client always presents itself as the default Android device (Pixel 8 profile of
 * [UserAgentInfo]), on every platform — also when the host app runs on an iPhone. No real device
 * data of the host is read or sent.
 */
object DeviceProfile {
    /** The Android profile sent in the handshake and `LOGIN` (PyMax `ANDROID_DEVICES` "Pixel 8"). */
    val android: UserAgentInfo = UserAgentInfo()

    private val appleMarkers = listOf("iphone", "ipad", "ipod", "ios", "darwin", "mac os", "macos", "apple")

    /**
     * Throws [IllegalArgumentException] unless [ua] is an Android profile: `deviceType = ANDROID`,
     * an `Android ...` OS version, a non-APNS push type, and no Apple device / OS names in any
     * string field.
     */
    fun requireAndroid(ua: UserAgentInfo) {
        require(ua.deviceType == "ANDROID") { "deviceType must be ANDROID (got ${ua.deviceType})" }
        require(ua.osVersion.startsWith("Android")) { "osVersion must be an Android version (got ${ua.osVersion})" }
        require(ua.pushDeviceType?.uppercase() != "APNS") { "pushDeviceType APNS is an iOS value" }
        val fields = listOfNotNull(ua.deviceName, ua.osVersion, ua.headerUserAgent, ua.arch, ua.screen)
        val apple = fields.firstOrNull { f -> appleMarkers.any { f.lowercase().contains(it) } }
        require(apple == null) { "user agent contains Apple device data: $apple" }
    }
}
