package com.max.core.media

/**
 * The HTTP part of uploads (CDN requests), kept behind an interface so common code is testable
 * and each platform plugs in its client: [defaultMediaHttp] is `OkHttpMediaHttp` on JVM / Android
 * and `UrlSessionMediaHttp` on iOS (commonMain stays free of HTTP dependencies).
 *
 * Implementations send [method] with exactly [headers] (in order) and [body], follow no
 * redirects, and return any status (non-2xx is not an exception). Throwing is reserved for I/O
 * failures. While writing the body they should call [progress] with `(bytesSent, total)` like
 * kolibri's HTTP client (`kolibri-net/src/media/http.rs`, `exchange`: after every 64 KiB write);
 * [MediaApi] reports completion itself if they don't.
 */
fun interface MediaHttp {
    suspend fun request(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray, progress: UploadProgress?): HttpResponse
}

/** POST through [MediaHttp.request]. */
suspend fun MediaHttp.post(url: String, headers: List<Pair<String, String>>, body: ByteArray, progress: UploadProgress? = null): HttpResponse =
    request("POST", url, headers, body, progress)

/**
 * Upload progress callback `(bytesSent, totalBytes)` (kolibri `media::ProgressFn`,
 * `kolibri-net/src/media/mod.rs`). Called from the uploading coroutine(s); for a parallel video
 * upload from several workers, with a monotonically growing `bytesSent`.
 */
fun interface UploadProgress {
    fun onProgress(bytesSent: Long, totalBytes: Long)
}

/** An HTTP response: status and raw body. */
class HttpResponse(val status: Int, val body: ByteArray) {
    val text: String get() = body.decodeToString()
}

/**
 * Request shapes of kolibri's hand-rolled CDN client (`kolibri-net/src/media/upload.rs`), which
 * mirrors the app ("CDN wants exact request shapes").
 */
object UploadRequests {
    /** Content type of a single-POST upload (kolibri `upload_file`). */
    const val BINARY_CONTENT_TYPE: String = "application/x-binary; charset=x-user-defined"

    /**
     * Headers of a single-POST upload (kolibri `upload_file`; `Host` is left to the HTTP client):
     * `Content-Type`, `Content-Disposition: attachment; filename=<encoded>`, `Connection:
     * keep-alive`, `User-Agent: <encoded>`, `Content-Range: bytes 0-<n-1>/<n>`, `Content-Length`.
     * The file name is percent-encoded like PyMax (`quote(file.name)`); kolibri sends it as is.
     */
    fun singlePostHeaders(fileName: String, size: Int, userAgent: String): List<Pair<String, String>> = listOf(
        "Content-Type" to BINARY_CONTENT_TYPE,
        "Content-Disposition" to "attachment; filename=${percentEncode(fileName)}",
        "Connection" to "keep-alive",
        "User-Agent" to percentEncode(userAgent),
        "Content-Range" to "bytes 0-${maxOf(size - 1, 0)}/$size",
        "Content-Length" to size.toString(),
    )

    /**
     * Headers of one request of the parallel video upload (kolibri `ok_cdn_request`, used by
     * `upload_video` for the GET handshake and every chunk POST; `Host` is left to the client):
     * `Content-Type`, `Content-Disposition: attachment; fileName="<name>"` (note the capital
     * `N` and quotes), `Content-Length`, `X-Uploading-Mode: parallel`, `Connection: close`, then
     * `Content-Range` for chunks. kolibri sends no User-Agent here.
     */
    fun parallelChunkHeaders(fileName: String, bodySize: Int, contentRange: String?): List<Pair<String, String>> = buildList {
        add("Content-Type" to BINARY_CONTENT_TYPE)
        add("Content-Disposition" to "attachment; fileName=\"$fileName\"")
        add("Content-Length" to bodySize.toString())
        add("X-Uploading-Mode" to "parallel")
        add("Connection" to "close")
        if (contentRange != null) add("Content-Range" to contentRange)
    }

    /** Headers of a multipart photo upload (kolibri `upload_photo`). */
    fun multipartHeaders(boundary: String, bodySize: Int, userAgent: String): List<Pair<String, String>> = listOf(
        "Content-Type" to "multipart/form-data; boundary=$boundary",
        "Content-Length" to bodySize.toString(),
        "Connection" to "keep-alive",
        "User-Agent" to percentEncode(userAgent),
    )

    /** One `file` part (kolibri `upload_photo`; PyMax `FormData.add_field(name="file", ...)`). */
    fun multipartBody(boundary: String, fileName: String, contentType: String, data: ByteArray): ByteArray {
        val preamble = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $contentType\r\n\r\n"
        val epilogue = "\r\n--$boundary--\r\n"
        return preamble.encodeToByteArray() + data + epilogue.encodeToByteArray()
    }

    /**
     * Image content type by extension (kolibri `content_type_for_filename`; PyMax uses
     * `"image/" + extension`, e.g. `image/jpg`).
     */
    fun imageContentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    /** kolibri `percent_encode` (Dart `Uri.encodeComponent`): unreserved `A-Za-z0-9-_.!~*'()` pass through. */
    fun percentEncode(input: String): String = buildString {
        for (b in input.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/** Minimal JSON reader for CDN replies (objects, arrays, strings, numbers, booleans, null). */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "trailing data at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                ws()
                require(s.getOrNull(i) == '"') { "expected key at $i" }
                val k = str()
                ws()
                require(s.getOrNull(i) == ':') { "expected ':' at $i" }
                i++
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $i")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $i")
                }
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> { require(i + 4 <= s.length) { "bad \\u" }; sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
