package com.maxly.shared

internal actual fun platformName(): String = "ios"

/**
 * Keychain generic passwords with service `com.max.kmp.<namespace>`. The service keeps its
 * pre-rebrand name so saved sessions survive the update.
 */
internal actual fun defaultKeyValueStore(namespace: String): KeyValueStore = KeychainKeyValueStore("com.max.kmp.$namespace")
