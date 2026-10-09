package com.maxly.shared

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKeyValueStoreTest {
    @Test
    fun persistsAcrossInstancesOwnerOnly() {
        val dir = Files.createTempDirectory("maxkv").toFile()
        try {
            val file = File(dir, "nested/default.properties")
            val a = FileKeyValueStore(file)
            assertNull(a.get("k"))
            a.put("k", "v1")
            a.put("token", "секрет=:#")
            assertEquals("v1", FileKeyValueStore(file).get("k"))
            assertEquals("секрет=:#", FileKeyValueStore(file).get("token"))
            a.remove("k")
            assertNull(FileKeyValueStore(file).get("k"))
            val perms = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
            if (perms != null) assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
            val dirPerms = runCatching { Files.getPosixFilePermissions(file.parentFile.toPath()) }.getOrNull()
            if (dirPerms != null) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                    dirPerms,
                )
            }
            assertTrue(!File(file.parentFile, file.name + ".tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun existingParentKeepsItsPermissions() {
        val dir = Files.createTempDirectory("maxkv3").toFile()
        try {
            if (!posix(dir)) return // POSIX-only
            val shared = File(dir, "shared").also { it.mkdir() }
            val rwxrxrx = PosixFilePermissions.fromString("rwxr-xr-x")
            Files.setPosixFilePermissions(shared.toPath(), rwxrxrx)
            val file = File(shared, "default.properties")
            FileKeyValueStore(file).put("token", "t")
            FileKeyValueStore(file).put("userId", "5")
            assertEquals(rwxrxrx, Files.getPosixFilePermissions(shared.toPath()))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
            assertEquals("t", FileKeyValueStore(file).get("token"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedWriteLeavesNoFalseCache() {
        val dir = Files.createTempDirectory("maxkv4").toFile()
        try {
            val file = File(dir, "default.properties")
            val store = FileKeyValueStore(file)
            store.put("keep", "1")
            // a non-empty directory where the temp file goes: the write fails before the move
            val obstacle = File(dir, "default.properties.tmp").also { it.mkdir() }
            File(obstacle, "x").writeText("x")
            assertFails { store.put("token", "t1") }
            assertNull(store.get("token"))
            assertNull(FileKeyValueStore(file).get("token"))
            assertFails { store.remove("keep") }
            assertEquals("1", store.get("keep"))

            // access restored: the same calls are not short-circuited by a stale cache
            obstacle.deleteRecursively()
            store.put("token", "t1")
            store.remove("keep")
            val fresh = FileKeyValueStore(file)
            assertEquals("t1", fresh.get("token"))
            assertNull(fresh.get("keep"))
            assertTrue(!File(dir, "default.properties.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun posix(dir: File): Boolean = dir.toPath().fileSystem.supportedFileAttributeViews().contains("posix")

    @Test
    fun defaultStoreHonoursDirProperty() {
        val dir = Files.createTempDirectory("maxkv2").toFile()
        val old = System.getProperty("max.kmp.dir")
        try {
            System.setProperty("max.kmp.dir", dir.path)
            val s = PlatformSession.defaultStore("acc/1")
            s.put("a", "b")
            assertTrue(File(dir, "acc_1.properties").isFile)
            assertEquals("jvm", PlatformSession.name)
        } finally {
            if (old == null) System.clearProperty("max.kmp.dir") else System.setProperty("max.kmp.dir", old)
            dir.deleteRecursively()
        }
    }
}
