@file:OptIn(ExperimentalForeignApi::class)

package com.max.core.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror
import kotlinx.cinterop.toKString

/** iOS: POSIX `open` + `pread` (thread safe, no shared file pointer). */
actual fun fileUploadSource(path: String): UploadSource = PosixFileUploadSource(path)

/** [UploadSource] over a file descriptor; [filePath] lets `UrlSessionMediaHttp` upload from the file. */
class PosixFileUploadSource(override val filePath: String) : UploadSource {
    private val fd: Int = open(filePath, O_RDONLY).also {
        if (it < 0) throw UploadException("cannot open $filePath: ${strerror(errno)?.toKString()}")
    }

    override val size: Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0 || (st.st_mode.toInt() and S_IFMT) != S_IFREG) {
            platform.posix.close(fd)
            throw UploadException("not a regular file: $filePath")
        }
        st.st_size
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (length == 0) return 0
        val n = buffer.usePinned { pread(fd, it.addressOf(offset), length.toULong(), position) }
        if (n < 0) throw UploadException("read failed on $filePath: ${strerror(errno)?.toKString()}")
        return if (n == 0L) -1 else n.toInt()
    }

    override fun close() {
        platform.posix.close(fd)
    }
}
