package com.max.shared

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
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
            assertTrue(!File(file.parentFile, file.name + ".tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

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
