package com.maxly.core.media

/**
 * Random-access bytes to upload, so large files go to the CDN without being loaded into memory:
 * single POSTs stream the source, parallel video chunks read only their own range.
 *
 * [read] is blocking and must be safe to call from several threads at once for different
 * positions (parallel chunk workers). Implementations: [ByteArrayUploadSource] and
 * [fileUploadSource] (JVM / Android: positional `FileChannel` reads; iOS: POSIX `pread`).
 */
interface UploadSource : AutoCloseable {
    /** Total size in bytes. */
    val size: Long

    /**
     * Local file path when the source is a whole file, so a platform client can hand the file to
     * the OS (iOS `uploadTaskWithRequest:fromFile:`); `null` otherwise.
     */
    val filePath: String? get() = null

    /** Reads up to [length] bytes at [position] into [buffer]; returns the count or `-1` at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    override fun close() {}
}

/** An in-memory [UploadSource] (the `ByteArray` overloads of [MediaApi]). */
class ByteArrayUploadSource(private val bytes: ByteArray) : UploadSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
        return n
    }
}

/**
 * Opens the file at [path] for uploading (read only). Throws if it does not exist or is not
 * readable. Close it after the upload ([MediaApi] path overloads do).
 */
expect fun fileUploadSource(path: String): UploadSource

/** Reads exactly [length] bytes at [position]; throws [UploadException] if the source is shorter. */
fun UploadSource.readFully(position: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    var done = 0
    while (done < length) {
        val n = read(position + done, out, done, length - done)
        if (n <= 0) throw UploadException("upload source ended at ${position + done}, expected ${position + length}")
        done += n
    }
    return out
}

/**
 * An HTTP request body made of in-memory parts and source ranges (a multipart preamble, the file,
 * the epilogue), written in segments without copying the source into memory.
 */
class UploadBody(val parts: List<Part>) {
    sealed class Part {
        abstract val length: Long

        class Bytes(val bytes: ByteArray) : Part() {
            override val length: Long get() = bytes.size.toLong()
        }

        class Range(val source: UploadSource, val start: Long, override val length: Long) : Part() {
            init {
                require(start >= 0 && length >= 0 && start + length <= source.size) { "range $start+$length outside source of ${source.size}" }
            }
        }
    }

    /** Total body size (the `Content-Length`). */
    val contentLength: Long = parts.sumOf { it.length }

    /** The whole of one file source, if that is all the body is (for OS file uploads). */
    val wholeFilePath: String?
        get() = (parts.singleOrNull() as? Part.Range)?.takeIf { it.start == 0L && it.length == it.source.size }?.source?.filePath

    /**
     * Writes the body to [write] in segments of at most [segment] bytes, calling [written] with the
     * running total after each (kolibri `http.rs` reports after every 64 KiB write).
     */
    fun writeTo(segment: Int = SEGMENT, written: ((Long) -> Unit)? = null, write: (ByteArray, Int, Int) -> Unit) {
        var total = 0L
        val buffer = ByteArray(segment)
        for (part in parts) {
            when (part) {
                is Part.Bytes -> {
                    var o = 0
                    while (o < part.bytes.size) {
                        val n = minOf(segment, part.bytes.size - o)
                        write(part.bytes, o, n)
                        o += n
                        total += n
                        written?.invoke(total)
                    }
                }
                is Part.Range -> {
                    var pos = part.start
                    val end = part.start + part.length
                    while (pos < end) {
                        val n = part.source.read(pos, buffer, 0, minOf(segment.toLong(), end - pos).toInt())
                        if (n <= 0) throw UploadException("upload source ended at $pos, expected $end")
                        write(buffer, 0, n)
                        pos += n
                        total += n
                        written?.invoke(total)
                    }
                }
            }
        }
    }

    /** The whole body in memory (the default [MediaHttp.upload]). */
    fun toByteArray(): ByteArray {
        require(contentLength <= Int.MAX_VALUE) { "body of $contentLength bytes does not fit in memory" }
        val out = ByteArray(contentLength.toInt())
        var o = 0
        writeTo { b, off, n -> b.copyInto(out, o, off, off + n); o += n }
        return out
    }

    companion object {
        /** Segment size of [writeTo] (kolibri: 64 KiB). */
        const val SEGMENT: Int = 64 * 1024

        val EMPTY: UploadBody = UploadBody(emptyList())

        fun of(bytes: ByteArray): UploadBody = UploadBody(listOf(Part.Bytes(bytes)))

        /** The whole [source]. */
        fun of(source: UploadSource): UploadBody = UploadBody(listOf(Part.Range(source, 0, source.size)))

        fun range(source: UploadSource, start: Long, length: Long): UploadBody = UploadBody(listOf(Part.Range(source, start, length)))
    }
}
