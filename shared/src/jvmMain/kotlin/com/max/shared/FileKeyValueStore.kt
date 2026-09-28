package com.max.shared

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * [KeyValueStore] backed by a `java.util.Properties` file. Every write replaces the file
 * atomically (temp file + move); on POSIX systems the file is readable by the owner only (it
 * holds the login token).
 */
class FileKeyValueStore(val file: File) : KeyValueStore {
    private val lock = Any()
    private var cache: Properties? = null

    override fun get(key: String): String? = synchronized(lock) { props().getProperty(key) }

    override fun put(key: String, value: String): Unit = synchronized(lock) {
        val p = props()
        if (p.getProperty(key) == value) return
        p.setProperty(key, value)
        write(p)
    }

    override fun remove(key: String): Unit = synchronized(lock) {
        val p = props()
        if (p.remove(key) != null) write(p)
    }

    private fun props(): Properties = cache ?: Properties().also { p ->
        if (file.isFile) file.inputStream().use(p::load)
        cache = p
    }

    private fun write(p: Properties) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { p.store(it, "max-kmp credentials") }
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
