package com.max.core.media

import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** JVM / Android: positional [FileChannel] reads (thread safe, no shared file pointer). */
actual fun fileUploadSource(path: String): UploadSource = FileChannelUploadSource(File(path))

/** [UploadSource] over a file; [read] uses `FileChannel.read(dst, position)`. */
class FileChannelUploadSource(file: File) : UploadSource {
    private val channel: FileChannel = if (file.isFile) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ)
    } else {
        throw FileNotFoundException("not a file: $file")
    }

    override val size: Long = channel.size()

    override val filePath: String = file.path

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        return channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    }

    override fun close() {
        channel.close()
    }
}
