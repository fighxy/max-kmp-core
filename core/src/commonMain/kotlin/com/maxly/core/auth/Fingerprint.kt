package com.maxly.core.auth

/**
 * Per-build digests of the official Android app that feed the anti-spoof fingerprint (the
 * `mode` field of `AUTH_REQUEST` and `chatCacheFingerprint` of `LOGIN`).
 *
 * Values come from PyMax `_data/apk_fingerprints.json` (MIT; keys `certificate_meta_sha256`,
 * `dex_meta_sha256`, `so_meta_sha256`, `build_number`). kolibri takes the same three digests from
 * the caller (`kolibri-net/src/auth.rs`) and its `examples/auth_request.rs` hard-codes the
 * 26.20.2 set, which is identical to PyMax's entry for that version. The certificate digest is
 * the same for every build; the dex and native-library digests change with each release, so the
 * set must match `UserAgentInfo.appVersion` / `buildNumber`. Add newer builds with the
 * constructor (PyMax can also fetch them from its remote catalog).
 *
 * @property soSha256 native-library digest per ABI (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`),
 *   selected by `UserAgentInfo.arch`.
 */
data class ApkFingerprint(
    val appVersion: String,
    val buildNumber: Long,
    val certificateSha256: String,
    val dexSha256: String,
    val soSha256: Map<String, String>,
) {
    /**
     * The 96-byte fingerprint: `SHA-256(digest ‖ int64_be(callsSeed) ‖ utf8(deviceId))` for the
     * certificate, dex and [arch] native-library digests, concatenated in that order (PyMax
     * `FingerprintGenerator.generate_fingerprint`, kolibri `chat_cache_fingerprint`).
     *
     * @throws IllegalArgumentException if there is no native-library digest for [arch].
     */
    fun compute(callsSeed: Long, deviceId: String, arch: String = DEFAULT_ARCH): ByteArray {
        val so = requireNotNull(soSha256[arch]) { "no native-library digest for arch '$arch' in build $appVersion" }
        val seed = ByteArray(8) { (callsSeed ushr (56 - 8 * it)).toByte() }
        val device = deviceId.encodeToByteArray()
        return Sha256.digest(hex(certificateSha256), seed, device) +
            Sha256.digest(hex(dexSha256), seed, device) +
            Sha256.digest(hex(so), seed, device)
    }

    companion object {
        /** PyMax's fallback when the user agent has no `arch`. */
        const val DEFAULT_ARCH: String = "arm64-v8a"

        private const val CERTIFICATE = "1684414033eb263e2c615f8b7df5ed8793850a07656304997fbf07e9e21e1e93"

        /** 26.25.0 (6790): PyMax `RECOMMENDED_APP_VERSION`, the [com.maxly.core.session.UserAgentInfo] default. */
        val V26_25_0: ApkFingerprint = ApkFingerprint(
            appVersion = "26.25.0",
            buildNumber = 6790,
            certificateSha256 = CERTIFICATE,
            dexSha256 = "8db68fcc0e85e8f041fe4a875c0a9bcfe542a8f679603728c651ed81b64dd684",
            soSha256 = mapOf(
                "arm64-v8a" to "634ecc42b246784d975f180b4fecf903df235cdf0476da47163a85630eb1a6a8",
                "armeabi-v7a" to "042220bdd481a280d2c1f4f6827f0e4fab7bca61e5af0f6035a0d191aed1350c",
                "x86" to "deffe34d2a9d83584e02cbb3f22ba5a6dbe1b065dbc8a8ea8ca908dae865c5f6",
                "x86_64" to "251b88c27a1c055f27adc110e44a75a1c60408b0d5e20e3844f816aa227212a3",
            ),
        )

        /** 26.20.2 (6758): the build kolibri's examples and bindings default to. */
        val V26_20_2: ApkFingerprint = ApkFingerprint(
            appVersion = "26.20.2",
            buildNumber = 6758,
            certificateSha256 = CERTIFICATE,
            dexSha256 = "0a6265f6e5d8231b9cba641f8c40475e6f3baeb06ed41b804b9bf7307aa4214e",
            soSha256 = mapOf(
                "arm64-v8a" to "90e2fb8745b17b42a10182f8d8ac590e3fca5b311e2ce2d5144fa2c18cb3090d",
                "armeabi-v7a" to "a62e2dfc3dcad88f866b5fcbba4c6d7bf1640118db98740ae22d647474bcce44",
                "x86" to "5723795fef7c3dc2c1f769be4a6b69c7568e3eb8f3279a6911718e902d38005e",
                "x86_64" to "bb097419b05e41eba460d4d1041ec660cc5baa28cbb0e287deb05bf27549e8ca",
            ),
        )

        /** Built-in builds. */
        val KNOWN: List<ApkFingerprint> = listOf(V26_25_0, V26_20_2)

        /** Built-in digests for [appVersion], or `null`. */
        fun forVersion(appVersion: String): ApkFingerprint? = KNOWN.firstOrNull { it.appVersion == appVersion }

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
