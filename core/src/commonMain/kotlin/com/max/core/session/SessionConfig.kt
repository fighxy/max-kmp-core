package com.max.core.session

import com.max.core.transport.TransportConfig
import kotlin.random.Random

/*
 * Session configuration: transport settings plus the device identity sent in the `SESSION_INIT`
 * (opcode 6) handshake.
 *
 * Sources (docs/protocol.md §C.2):
 * - kolibri `kolibri-net/src/session/config.rs` (`UserAgent`, `HandshakeConfig`) and
 *   `session/manager.rs` `build_handshake_payload`; defaults from the kolibri Kotlin binding
 *   `ru.kolibri.Config` and `examples/handshake.rs`;
 * - PyMax `api/session/payloads.py` (`MobileUserAgentPayload`, `MobileHandshakePayload`,
 *   `WebHandshakePayload`), `config.py` (`generate_user_agent`, `generate_device_id`,
 *   `ANDROID_DEVICES`) and `versions/catalog.py` (`RECOMMENDED_APP_VERSION`).
 */

/** Production endpoint (kolibri examples; PyMax defaults to `api2.oneme.ru`, see protocol.md §B.6). */
const val DEFAULT_HOST: String = "api.oneme.ru"

/** Browser UA PyMax sends as `headerUserAgent` for `deviceType = WEB` when none is set. */
const val DEFAULT_WEB_HEADER_USER_AGENT: String =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

/**
 * The `userAgent` map of the handshake. Nullable fields are omitted from the payload when `null`
 * (PyMax `exclude_none`, kolibri `Option` / empty-string / zero checks).
 *
 * Defaults describe one fixed Android device: app version and build number are PyMax's
 * `RECOMMENDED_APP_VERSION` 26.25.0 / 6790 (kolibri's binding still ships 26.20.2 / 6758), the
 * device is the "Pixel 8" entry of PyMax `ANDROID_DEVICES`, `GCM` / `arm64-v8a` / `ru` /
 * `Europe/Moscow` as in both references. PyMax picks a random device and a random Russian
 * timezone per client instead; callers that want that (or real device values) override the
 * fields. Keep one identity per account: the login token is tied to it.
 *
 * @property deviceType `ANDROID`, `IOS`, `DESKTOP` or `WEB` (PyMax `DeviceType`). `WEB` switches
 *   the payload to PyMax's web shape, see [HandshakePayload].
 * @property release PyMax-only optional field (never set by PyMax defaults).
 * @property headerUserAgent browser UA string (kolibri: optional; PyMax: web only).
 * @property isPwa kolibri-only optional flag (the web client sends it since August 2026).
 */
data class UserAgentInfo(
    val deviceType: String = "ANDROID",
    val appVersion: String = "26.25.0",
    val osVersion: String = "Android 14",
    val timezone: String = "Europe/Moscow",
    val screen: String = "428dpi 428dpi 1080x2400",
    val pushDeviceType: String? = "GCM",
    val arch: String? = "arm64-v8a",
    val locale: String = "ru",
    val buildNumber: Long? = 6790,
    val deviceName: String = "Pixel 8",
    val deviceLocale: String = "ru",
    val release: Long? = null,
    val headerUserAgent: String? = null,
    val isPwa: Boolean? = null,
) {
    /**
     * HTTP User-Agent for media uploads built from the same fields (kolibri
     * `UserAgent::http_user_agent`).
     */
    val httpUserAgent: String get() = "OKMessages/$appVersion ($osVersion; $deviceName; $screen)"
}

/**
 * Device identity for the handshake.
 *
 * @property deviceId `deviceId`. Default: 16 random hex characters (PyMax `generate_device_id`,
 *   `secrets.token_hex(8)`). Persist it and pass it back: the login token belongs to it.
 * @property instanceId `mt_instanceid`, omitted when empty (kolibri). Default: 16 random hex
 *   characters like PyMax (`mt_instance_id` default factory); persist it together with [deviceId].
 * @property clientSessionId `clientSessionId`. `null` (default) sends a fresh random value in
 *   `1..70` with every handshake, as PyMax does (`randint(1, 70)` default factory); `0` omits the
 *   key (kolibri); any other value is sent as is (kolibri binding default: 1 700 000 000).
 */
data class DeviceInfo(
    val deviceId: String = randomHexId(),
    val instanceId: String = randomHexId(),
    val clientSessionId: Long? = null,
    val userAgent: UserAgentInfo = UserAgentInfo(),
)

/**
 * Everything [SessionMachine] needs. [transport] is passed to [com.max.core.transport.MaxTransport]
 * unchanged (host, port, proxy, TLS, timeouts, ping, auto-reconnect).
 */
data class SessionConfig(
    val transport: TransportConfig = TransportConfig(host = DEFAULT_HOST),
    val device: DeviceInfo = DeviceInfo(),
) {
    constructor(host: String, port: Int = 443, device: DeviceInfo = DeviceInfo()) :
        this(TransportConfig(host = host, port = port), device)
}

/** 16 random lowercase hex characters (8 random bytes), like PyMax `secrets.token_hex(8)`. */
fun randomHexId(random: Random = Random.Default): String {
    val digits = "0123456789abcdef"
    return buildString(16) { repeat(16) { append(digits[random.nextInt(16)]) } }
}
