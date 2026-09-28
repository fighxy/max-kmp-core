package com.max.shared

/**
 * Platform wiring for [MaxClient]: where credentials are stored.
 *
 * - Android: `SharedPreferences` (private mode); call `PlatformSession.init(context)` once.
 * - iOS: Keychain generic passwords (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
 * - JVM: a properties file under `~/.max-kmp/` (or the `max.kmp.dir` system property).
 *
 * Nothing here reads device data for the protocol: the client always sends the Android profile
 * of [DeviceProfile].
 */
object PlatformSession {
    /** `android`, `ios` or `jvm` (local information only, never sent). */
    val name: String get() = platformName()

    /** Persistent store for [namespace]. */
    fun defaultStore(namespace: String): KeyValueStore = defaultKeyValueStore(namespace)
}

internal expect fun platformName(): String

internal expect fun defaultKeyValueStore(namespace: String): KeyValueStore

/** File / preference name part for [namespace]. */
internal fun safeName(namespace: String): String = namespace.replace(Regex("[^A-Za-z0-9._-]"), "_")
