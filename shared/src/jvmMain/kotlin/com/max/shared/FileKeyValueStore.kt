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
 * atomically (temp file + move). On POSIX the file is created owner-read/write before any token
 * bytes are written, and its directory is owner-only. If those permissions do not stick, the
 * write fails.
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
        val parent = file.parentFile
        if (parent != null) {
            parent.mkdirs()
            ownerOnly(parent.toPath(), directoryPerms)
        }
        val tmp = File(parent, file.name + ".tmp")
        val tmpPath = tmp.toPath()
        Files.deleteIfExists(tmpPath)
        if (posixSupported(parent?.toPath() ?: tmpPath.toAbsolutePath().parent)) {
            Files.createFile(tmpPath, PosixFilePermissions.asFileAttribute(filePerms))
        }
        tmp.outputStream().use { p.store(it, "max-kmp credentials") }
        val dest = file.toPath()
        Files.move(tmpPath, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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
