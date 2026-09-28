package com.max.shared

internal actual fun platformName(): String = "ios"

/** Keychain generic passwords with service `com.max.kmp.<namespace>`. */
internal actual fun defaultKeyValueStore(namespace: String): KeyValueStore = KeychainKeyValueStore("com.max.kmp.$namespace")
