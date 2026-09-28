@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.max.core.media

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `fileUploadSource` on iOS (POSIX `pread`), run by `gradle :core:iosSimulatorArm64Test`. */
class PosixFileUploadSourceTest {

    @Test
    fun readsRangesOfATempFile() {
        val path = NSTemporaryDirectory() + "max-upload-test.bin"
        val data = ByteArray(100_000) { (it * 7).toByte() }
        data.toNSData().writeToFile(path, atomically = true)
        try {
            fileUploadSource(path).use { src ->
                assertEquals(100_000L, src.size)
                assertEquals(path, src.filePath)
                assertContentEquals(data.copyOfRange(90_000, 91_000), src.readFully(90_000, 1_000))
                assertEquals(-1, src.read(100_000, ByteArray(1), 0, 1))
                assertContentEquals(data, UploadBody.of(src).toByteArray())
                assertEquals(path, UploadBody.of(src).wholeFilePath)
            }
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
        assertFailsWith<UploadException> { fileUploadSource(NSTemporaryDirectory() + "missing-max-upload.bin") }
    }
}
