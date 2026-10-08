package com.max.core.transport

/**
 * A server `RECONNECT` (opcode 3) push and what [MaxTransport] made of it ([MaxTransport.lastRedirect]).
 *
 * Payload, as the Android app reads it (`opf`, handled in `vpc`): `{redirectHost: "host:port",
 * tls: bool}`, `tls` `true` when missing. The app saves host, port and `tls` and restarts its
 * session; with an empty `redirectHost` it only restarts. It does not check the host.
 *
 * This core follows the push only when it is safe:
 * - empty or missing `redirectHost`: reconnect at once to the current host ([host] `null`);
 * - `host:port` with a host on one of [TransportConfig.redirectDomains] (the host itself or a
 *   subdomain, `oneme.ru` by default) and a port in `1..65535`: switch to it and reconnect at once;
 * - anything else is ignored ([accepted] `false`, [reason] says why) and the connection stays:
 *   a host outside the domains, an IP address, a missing or bad port, `tls: false` (the core
 *   never talks to the server without TLS), or a transport without
 *   [TransportConfig.autoReconnect].
 *
 * @property redirectHost the raw `redirectHost` value.
 * @property tls the `tls` value (`true` when missing).
 * @property host the host switched to, `null` for a restart on the current host or an ignored push.
 * @property port the port switched to, `null` like [host].
 * @property accepted whether the transport reconnected for this push.
 * @property reason why the push was ignored, `null` when [accepted].
 */
data class ServerRedirect(
    val redirectHost: String?,
    val tls: Boolean,
    val host: String?,
    val port: Int?,
    val accepted: Boolean,
    val reason: String?,
) {
    companion object {
        /** Default [TransportConfig.redirectDomains]: the domain of every API host of the app. */
        val DEFAULT_DOMAINS: Set<String> = setOf("oneme.ru")

        private val HOST_NAME = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

        /**
         * Reads a `RECONNECT` [payload] and decides whether it may be followed with [domains]
         * (see the class description). Does not look at `autoReconnect`.
         */
        fun evaluate(payload: Any?, domains: Set<String>): ServerRedirect {
            val map = payload as? Map<*, *>
            val raw = map?.get("redirectHost") as? String
            val tls = map?.get("tls") as? Boolean ?: true
            fun ignored(reason: String) = ServerRedirect(raw, tls, null, null, accepted = false, reason = reason)
            if (raw.isNullOrBlank()) return ServerRedirect(raw, tls, null, null, accepted = true, reason = null)
            if (!tls) return ignored("tls: false is not supported")
            val colon = raw.indexOf(':')
            if (colon <= 0) return ignored("no port in redirectHost")
            val host = raw.substring(0, colon).trim().lowercase().trimEnd('.')
            val port = raw.substring(colon + 1).trim().toIntOrNull()
            if (port == null || port !in 1..65535) return ignored("bad port in redirectHost")
            if (!isAllowedHost(host, domains)) return ignored("host outside ${domains.sorted().joinToString()}")
            return ServerRedirect(raw, tls, host, port, accepted = true, reason = null)
        }

        /**
         * `true` when [host] is a DNS name equal to one of [domains] or below one of them
         * (`api2.oneme.ru` for `oneme.ru`); IP addresses and look-alikes (`oneme.ru.evil.com`,
         * `evil-oneme.ru`) are not.
         */
        fun isAllowedHost(host: String, domains: Set<String>): Boolean {
            val h = host.lowercase().trimEnd('.')
            if (!HOST_NAME.matches(h) || h.substringAfterLast('.').all { it.isDigit() }) return false
            return domains.any { d ->
                val domain = d.lowercase().trim().trimEnd('.')
                domain.isNotEmpty() && (h == domain || h.endsWith(".$domain"))
            }
        }
    }
}
