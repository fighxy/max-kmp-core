package com.maxly.shared

import java.io.File

internal actual fun platformName(): String = "jvm"

/** `<max.kmp.dir or ~/.max-kmp>/<namespace>.properties`. */
internal actual fun defaultKeyValueStore(namespace: String): KeyValueStore {
    val dir = System.getProperty("max.kmp.dir")?.let(::File) ?: File(System.getProperty("user.home"), ".max-kmp")
    return FileKeyValueStore(File(dir, "${safeName(namespace)}.properties"))
}
