package com.max.core.media

import com.max.core.api.ApiException
import com.max.core.api.MalformedReplyException
import com.max.core.api.asLong
import com.max.core.protocol.Opcode

/**
 * An upload failed outside the control connection: HTTP error or non-200 [status], an unusable
 * CDN reply, or no readiness signal in time (PyMax `UploadError`). ERROR replies to the slot
 * requests stay `ServerErrorException`, malformed slot replies [MalformedReplyException].
 */
class UploadException(message: String, val status: Int? = null, cause: Throwable? = null) : ApiException(message, cause)

/**
 * A local file to upload and attach ([MediaApi.uploadAll]). [kind] picks the slot:
 * [Kind.PHOTO] `PHOTO_UPLOAD` 80 (the server recompresses it), [Kind.VIDEO] `VIDEO_UPLOAD` 82,
 * [Kind.FILE] `FILE_UPLOAD` 87 (sent as is, a document). [fileName] is what the recipient sees
 * for a file and the multipart part name for a photo; the last path component when `null`.
 */
data class OutgoingMedia(val path: String, val kind: Kind, val fileName: String? = null) {
    enum class Kind { PHOTO, VIDEO, FILE }
}

/**
 * Photo upload slot: reply to `PHOTO_UPLOAD` 80 `{url}`. [url] is where the image is POSTed; the
 * photo token comes back in that HTTP reply ([photoToken]), not in this one. Older upload URLs
 * carried a `photoIds` query parameter (PyMax `upload_photo`), the key of the token in the CDN
 * reply; current ones may not, so [photoId] is `null` then and the reply's only photo is used.
 */
data class PhotoUploadSlot(val url: String, val photoId: String?, val raw: Map<*, *>) {
    companion object {
        fun from(map: Map<*, *>): PhotoUploadSlot {
            val url = (map["url"] as? String)?.takeIf { it.isNotEmpty() }
                ?: throw MalformedReplyException(Opcode.PHOTO_UPLOAD, "no url", map)
            val photoId = queryParam(url, "photoIds")?.takeIf { it.isNotEmpty() }
            return PhotoUploadSlot(url, photoId, map)
        }
    }
}

/**
 * The photo token from the upload server's JSON reply to the POST of a [PhotoUploadSlot]:
 * `photos[<photoId>].token` when the slot named the photo, otherwise the first non-empty
 * `photos.*.token` (one photo per slot, `count = 1`), otherwise a top-level `photoToken`.
 * `null` when the reply has none.
 */
internal fun photoToken(reply: Map<*, *>, photoId: String?): String? {
    fun token(entry: Any?): String? = ((entry as? Map<*, *>)?.get("token") as? String)?.takeIf { it.isNotEmpty() }
    val photos = reply["photos"] as? Map<*, *>
    photoId?.let { id -> token(photos?.get(id))?.let { return it } }
    photos?.values?.firstNotNullOfOrNull { token(it) }?.let { return it }
    return (reply["photoToken"] as? String)?.takeIf { it.isNotEmpty() }
}

/**
 * The video token in the upload server's reply to a video POST, as Komet reads it
 * (`FileUploader._parseVideoToken`): `[{token}]`, `{videos|video|photos: {<id>: {token}}}` or a
 * top-level `token` / `videoToken` / `photoToken`. `null` for an empty or non-JSON reply.
 */
internal fun videoUploadToken(text: String): String? {
    val json = try {
        MiniJson.parse(text)
    } catch (e: IllegalArgumentException) {
        return null
    }
    fun token(entry: Any?): String? = ((entry as? Map<*, *>)?.get("token") as? String)?.takeIf { it.isNotEmpty() }
    (json as? List<*>)?.firstNotNullOfOrNull(::token)?.let { return it }
    val map = json as? Map<*, *> ?: return null
    for (key in listOf("videos", "video", "photos")) {
        (map[key] as? Map<*, *>)?.values?.firstNotNullOfOrNull(::token)?.let { return it }
    }
    return listOf("token", "videoToken", "photoToken").firstNotNullOfOrNull { k -> (map[k] as? String)?.takeIf { it.isNotEmpty() } }
}

/**
 * File / video / voice upload slot: `info[0] {url, fileId | videoId, token}` of the
 * `FILE_UPLOAD` 87 / `VIDEO_UPLOAD` 82 reply (PyMax `FileUploadResponse`, `VideoUploadResponse`).
 * [id] is `fileId` for files and `videoId` for videos and voice messages.
 */
data class UploadSlot(val url: String, val id: Long, val token: String, val raw: Map<*, *>) {
    companion object {
        fun from(map: Map<*, *>, opcode: Opcode, idKey: String): UploadSlot {
            val info = (map["info"] as? List<*>)?.firstOrNull() as? Map<*, *>
                ?: throw MalformedReplyException(opcode, "no info[0]", map)
            val url = info["url"] as? String ?: throw MalformedReplyException(opcode, "no info[0].url", map)
            val id = info[idKey].asLong() ?: throw MalformedReplyException(opcode, "no info[0].$idKey", map)
            val token = info["token"] as? String ?: throw MalformedReplyException(opcode, "no info[0].token", map)
            return UploadSlot(url, id, token, map)
        }
    }
}

/**
 * Reply to `VIDEO_PLAY` 83 (PyMax `VideoRequest`): [external] is the `EXTERNAL` value (a URL or a
 * flag), [url] is `url` or else the highest-quality `MP4_<n>` entry or else `dynamicUrl`
 * (PyMax `select_video_url`); `null` for external videos.
 */
data class VideoLink(val url: String?, val external: Any?, val cache: Boolean, val raw: Map<*, *>) {
    companion object {
        fun from(map: Map<*, *>): VideoLink {
            val url = map["url"] as? String
                ?: map.entries.mapNotNull { (k, v) ->
                    val q = (k as? String)?.uppercase()?.takeIf { it.startsWith("MP4_") }?.removePrefix("MP4_")?.toIntOrNull()
                    if (q != null && q > 0 && v is String) q to v else null
                }.maxByOrNull { it.first }?.second
                ?: (map["dynamicUrl"] ?: map["dynamic_url"]) as? String
            return VideoLink(url, map["EXTERNAL"], map["cache"] == true, map)
        }
    }
}

/** Reply to `FILE_DOWNLOAD` 88 (PyMax `FileRequest`): `{url, unsafe}`. */
data class FileLink(val url: String, val unsafe: Boolean, val raw: Map<*, *>) {
    companion object {
        fun from(map: Map<*, *>): FileLink {
            val url = map["url"] as? String ?: throw MalformedReplyException(Opcode.FILE_DOWNLOAD, "no url", map)
            return FileLink(url, map["unsafe"] == true, map)
        }
    }
}

/** First value of query parameter [name] in [url], percent-decoded (`+` as space, like `parse_qs`). */
internal fun queryParam(url: String, name: String): String? {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    for (pair in query.split('&', ';')) {
        val key = percentDecode(pair.substringBefore('='))
        if (key == name && '=' in pair) return percentDecode(pair.substringAfter('='))
    }
    return null
}

private fun percentDecode(s: String): String {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
            out += s.substring(i + 1, i + 3).toInt(16).toByte()
            i += 3
        } else {
            (if (c == '+') " " else c.toString()).encodeToByteArray().forEach { out += it }
            i++
        }
    }
    return out.toByteArray().decodeToString()
}
