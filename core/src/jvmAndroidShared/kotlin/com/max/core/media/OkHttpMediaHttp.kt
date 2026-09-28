package com.max.core.media

import com.max.core.transport.CompositeTrustManager
import com.max.core.transport.JavaSocketConnectionFactory
import com.max.core.transport.ProxyKind
import com.max.core.transport.TrustAllManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [MediaHttp] on OkHttp (JVM desktop and Android).
 *
 * - Headers are sent as given, in order. The request body has no OkHttp media type, so the
 *   caller's `Content-Type` goes out verbatim; OkHttp's bridge sets `Content-Length` from the body
 *   (same value as [UploadRequests] builds; it moves to the end of the header list) and adds
 *   `Host` (kolibri sends it too) and `Accept-Encoding: gzip` (an extra kolibri does not send;
 *   gzip replies are decoded transparently). A caller `Connection: close` is honored (the
 *   connection is not reused). `User-Agent` is the caller's (OkHttp adds its own only when absent).
 * - `GET` is sent without a body (the `Content-Length: 0` header of the kolibri handshake stays).
 * - Bodies are streamed ([upload] with an [UploadBody]): file ranges are read from their
 *   [UploadSource] segment by segment while writing, so a large file is never held in memory.
 * - Progress: the body is written in 64 KiB segments (like kolibri `http.rs`) and
 *   [UploadProgress] gets `(bytesWritten, total)` after each; if OkHttp rewrites the body on a
 *   retry, only values above the previous maximum are reported.
 * - Cancelling the calling coroutine cancels the OkHttp call.
 * - TLS: system roots plus [com.max.core.transport.MincifryCa] (the same trust managers as
 *   [JavaSocketConnectionFactory]); [MediaHttpConfig.insecure] trusts everything.
 * - Redirects are not followed; non-2xx statuses are returned, not thrown.
 */
class OkHttpMediaHttp(val client: OkHttpClient) : MediaHttp {

    constructor(config: MediaHttpConfig = MediaHttpConfig()) : this(buildClient(config))

    override suspend fun request(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        progress: UploadProgress?,
    ): HttpResponse = upload(method, url, headers, UploadBody.of(body), progress)

    /** Streams [body] (files are read in 64 KiB segments on OkHttp's thread, never fully loaded). */
    override suspend fun upload(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: UploadBody,
        progress: UploadProgress?,
    ): HttpResponse {
        val hb = Headers.Builder()
        for ((name, value) in headers) hb.addUnsafeNonAscii(name, value)
        val requestBody = if (method == "GET" || method == "HEAD") {
            require(body.contentLength == 0L) { "$method with a body" }
            null
        } else {
            ProgressBody(body, progress)
        }
        val request = Request.Builder().url(url).headers(hb.build()).method(method, requestBody).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { HttpResponse(it.code, it.body?.bytes() ?: ByteArray(0)) }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    /** Fixed-length body that reports progress per written segment. */
    private class ProgressBody(private val body: UploadBody, private val progress: UploadProgress?) : RequestBody() {
        @Volatile private var reported = -1L

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = body.contentLength

        override fun writeTo(sink: BufferedSink) {
            val total = body.contentLength
            try {
                body.writeTo(SEGMENT, written = { report(it, total) }) { b, off, n ->
                    sink.write(b, off, n)
                    sink.flush()
                }
            } catch (e: UploadException) {
                // OkHttp only routes IOExceptions to onFailure (others escape the dispatcher thread)
                throw IOException(e.message, e)
            }
        }

        private fun report(sent: Long, total: Long) {
            val p = progress ?: return
            synchronized(this) {
                if (sent <= reported) return
                reported = sent
            }
            p.onProgress(sent, total)
        }
    }

    companion object {
        private const val SEGMENT = 64 * 1024

        /** The shared default-config instance ([defaultMediaHttp] with no arguments). */
        val shared: OkHttpMediaHttp by lazy { OkHttpMediaHttp(MediaHttpConfig()) }

        /** An [OkHttpClient] for [config]: timeouts, trust, proxy, no redirects, no retries. */
        fun buildClient(config: MediaHttpConfig): OkHttpClient {
            val b = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .callTimeout(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .readTimeout(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .writeTimeout(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                // an upload chunk must not be silently re-sent
                .retryOnConnectionFailure(false)
            val trust: X509TrustManager? = when {
                config.insecure -> TrustAllManager
                config.trustMincifryCa -> CompositeTrustManager(
                    listOf(JavaSocketConnectionFactory.systemTrustManager(), JavaSocketConnectionFactory.mincifryTrustManager()),
                )
                else -> null
            }
            if (trust != null) {
                val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
                b.sslSocketFactory(ctx.socketFactory, trust)
            }
            if (config.insecure) b.hostnameVerifier { _, _ -> true }
            config.proxy?.let { p ->
                when (p.kind) {
                    ProxyKind.HTTP -> {
                        b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(p.host, p.port)))
                        if (p.username != null) {
                            val credential = Credentials.basic(p.username, p.password ?: "")
                            b.proxyAuthenticator { _, response ->
                                if (response.request.header("Proxy-Authorization") != null) {
                                    null
                                } else {
                                    response.request.newBuilder().header("Proxy-Authorization", credential).build()
                                }
                            }
                        }
                    }
                    ProxyKind.SOCKS5, ProxyKind.SOCKS5H -> {
                        require(p.username == null) { "SOCKS5 proxy credentials are not supported by the OkHttp media client" }
                        b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(p.host, p.port)))
                    }
                }
            }
            return b.build()
        }
    }
}

/** Shared by the JVM and Android actuals. */
internal fun okHttpMediaHttp(config: MediaHttpConfig): MediaHttp =
    if (config == MediaHttpConfig()) OkHttpMediaHttp.shared else OkHttpMediaHttp(config)
