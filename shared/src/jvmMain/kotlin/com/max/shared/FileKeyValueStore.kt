package com.max.shared

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * [KeyValueStore] backed by a `java.util.Properties` file. Every write replaces the file
 * atomically (temp file + move). [put] / [remove] change a copy of the cached content, and the
 * copy becomes the cache only once the move has put it on disk: a write that fails before the
 * move leaves both the file and the cache as they were (the call throws, a retry writes again).
 * If the move succeeded but the owner-only check of the file fails afterwards, the call throws
 * while the cache already matches the new file content. On POSIX the file is created owner-read/write before any token
 * bytes are written; if that permission does not stick, the write fails. A missing parent
 * directory is created owner-only (`rwx------`); an existing one is used as it is and its
 * permissions are never changed (it may be shared, e.g. an app data directory).
 */
class FileKeyValueStore(val file: File) : KeyValueStore {
    private val lock = Any()
    private var cache: Properties? = null
    private val filePerms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private val directoryPerms = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    override fun get(key: String): String? = synchronized(lock) { props().getProperty(key) }

    override fun put(key: String, value: String): Unit = synchronized(lock) {
        val current = props()
        if (current.getProperty(key) == value) return
        write(copyOf(current).apply { setProperty(key, value) })
    }

    override fun remove(key: String): Unit = synchronized(lock) {
        val current = props()
        if (!current.containsKey(key)) return
        write(copyOf(current).apply { remove(key) })
    }

    private fun copyOf(p: Properties): Properties = Properties().also { it.putAll(p) }

    private fun props(): Properties = cache ?: Properties().also { p ->
        if (file.isFile) file.inputStream().use(p::load)
        cache = p
    }

    private fun write(p: Properties) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) {
            // only a directory this store creates is restricted; an existing one is left alone
            Files.createDirectories(parent.toPath())
            ownerOnly(parent.toPath(), directoryPerms)
        }
        val tmp = File(parent, file.name + ".tmp")
        val tmpPath = tmp.toPath()
        val dest = file.toPath()
        try {
            Files.deleteIfExists(tmpPath)
            if (posixSupported(parent?.toPath() ?: tmpPath.toAbsolutePath().parent)) {
                Files.createFile(tmpPath, PosixFilePermissions.asFileAttribute(filePerms))
            }
            tmp.outputStream().use { p.store(it, "max-kmp credentials") }
            Files.move(tmpPath, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            // nothing reached the file: the cache keeps the old content, a retry writes again
            runCatching { Files.deleteIfExists(tmpPath) }
            throw e
        }
        // the new content is on disk: publish it, then report a permission failure (if any)
        cache = p
        ownerOnly(dest, filePerms)
    }

    private fun ownerOnly(path: Path, perms: Set<PosixFilePermission>) {
        if (!posixSupported(path)) return
        Files.setPosixFilePermissions(path, perms)
        val actual = Files.getPosixFilePermissions(path)
        if (actual != perms) throw IOException("could not restrict $path to $perms (got $actual)")
    }

    private fun posixSupported(path: Path?): Boolean {
        if (path == null || !path.fileSystem.supportedFileAttributeViews().contains("posix")) return false
        return runCatching { Files.getFileStore(path).supportsFileAttributeView("posix") }.getOrDefault(true)
    }
}
