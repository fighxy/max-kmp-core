@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.maxly.core.media

import com.maxly.core.transport.NetworkFrameworkConnectionFactory
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.appendData
import platform.Foundation.create
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failed `NSURLSession` request (transport error, not an HTTP status). */
class UrlSessionException(message: String, val code: Long) : Exception(message)

/**
 * [MediaHttp] on `NSURLSession` (iOS).
 *
 * - One ephemeral session per request (no cookies, no cache), with a delegate; the session is
 *   invalidated when its task finishes. `POST` bodies go through `uploadTaskWithRequest:fromData:`
 *   (or `fromFile:` for a whole file, see [upload]), `GET` through `dataTaskWithRequest:`.
 * - Headers: set with `setValue:forHTTPHeaderField:` in order. `NSURLSession` owns `Content-Length`
 *   (it computes the same value from the body), `Connection` and `Host` — Apple documents that
 *   setting them has no reliable effect, so kolibri's `Connection: close` for video chunks is a
 *   hint only here.
 * - Progress: `URLSession:task:didSendBodyData:totalBytesSent:totalBytesExpectedToSend:` →
 *   [UploadProgress] `(totalBytesSent, body size)`.
 * - Redirects are not followed (`willPerformHTTPRedirection` answers `nil`).
 * - TLS (`didReceiveChallenge` for server trust): default handling, or system roots plus
 *   [com.maxly.core.transport.MincifryCa] via the same evaluation as the socket transport
 *   ([NetworkFrameworkConnectionFactory.evaluateSecTrustWithMincifry]), or accept-all for
 *   [MediaHttpConfig.insecure].
 * - Timeouts: `timeoutIntervalForRequest` (the longest pause without data) =
 *   [MediaHttpConfig.requestTimeout]; the whole transfer has no limit of its own (a week, the
 *   `NSURLSession` default), as in kolibri, so a big upload on a slow network is not cut off.
 *   `NSURLSession` has no separate connect timeout.
 * - Cancelling the calling coroutine cancels the task.
 * - [MediaHttpConfig.proxy] is not supported (the per-session proxy dictionary keys are not
 *   available on iOS): the constructor rejects it.
 *
 * Only type-checked on Linux; compiled and run by the macOS CI job.
 */
class UrlSessionMediaHttp(private val config: MediaHttpConfig = MediaHttpConfig()) : MediaHttp {

    init {
        require(config.proxy == null) { "a media proxy is not supported by the NSURLSession client" }
    }

    override suspend fun request(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        progress: UploadProgress?,
    ): HttpResponse = perform(method, url, headers, body.size.toLong(), progress) { session, request ->
        if (method == "GET" || method == "HEAD") {
            require(body.isEmpty()) { "$method with a body" }
            session.dataTaskWithRequest(request)
        } else {
            session.uploadTaskWithRequest(request, fromData = body.toNSData())
        }
    }

    /**
     * A body that is exactly one whole file ([UploadBody.wholeFilePath], e.g. `MediaApi.uploadFile(path)`)
     * goes through `uploadTaskWithRequest:fromFile:`, so the OS streams it from disk. Other bodies
     * (multipart photos, parallel video chunks of at most the chunk size) are built in memory.
     */
    override suspend fun upload(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: UploadBody,
        progress: UploadProgress?,
    ): HttpResponse {
        val path = body.wholeFilePath
        if (path == null || method == "GET" || method == "HEAD") return request(method, url, headers, body.toByteArray(), progress)
        return perform(method, url, headers, body.contentLength, progress) { session, request ->
            session.uploadTaskWithRequest(request, fromFile = NSURL.fileURLWithPath(path))
        }
    }

    private suspend fun perform(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        bodySize: Long,
        progress: UploadProgress?,
        makeTask: (NSURLSession, NSMutableURLRequest) -> NSURLSessionTask,
    ): HttpResponse {
        val nsUrl = NSURL.URLWithString(url)
            ?.takeIf { (it.scheme?.lowercase() == "http" || it.scheme?.lowercase() == "https") && !it.host.isNullOrEmpty() }
            ?: throw IllegalArgumentException("invalid url: $url")
        val timeout = config.requestTimeout.inWholeMilliseconds / 1000.0
        val request = NSMutableURLRequest(nsUrl, NSURLRequestReloadIgnoringLocalCacheData, timeout)
        request.setHTTPMethod(method)
        for ((name, value) in headers) request.setValue(value, forHTTPHeaderField = name)

        val sessionConfig = NSURLSessionConfiguration.ephemeralSessionConfiguration
        sessionConfig.timeoutIntervalForRequest = timeout
        sessionConfig.timeoutIntervalForResource = WHOLE_TRANSFER_SECONDS
        sessionConfig.HTTPShouldSetCookies = false
        sessionConfig.URLCache = null

        return suspendCancellableCoroutine { cont ->
            val delegate = TaskDelegate(config, bodySize, progress) { result ->
                if (cont.isActive) {
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            }
            val session = NSURLSession.sessionWithConfiguration(sessionConfig, delegate, null)
            val task = makeTask(session, request)
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
            session.finishTasksAndInvalidate()
        }
    }

    /** Collects the response body and completes once; all callbacks run on the session's delegate queue. */
    private class TaskDelegate(
        private val config: MediaHttpConfig,
        private val bodySize: Long,
        private val progress: UploadProgress?,
        private val complete: (Result<HttpResponse>) -> Unit,
    ) : NSObject(), NSURLSessionDataDelegateProtocol {
        private val received = NSMutableData()

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didSendBodyData: Long,
            totalBytesSent: Long,
            totalBytesExpectedToSend: Long,
        ) {
            progress?.onProgress(totalBytesSent, if (bodySize > 0) bodySize else totalBytesExpectedToSend)
        }

        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
            received.appendData(didReceiveData)
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) {
            completionHandler(null)
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didReceiveChallenge: NSURLAuthenticationChallenge,
            completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
        ) {
            val space = didReceiveChallenge.protectionSpace
            val trust = space.serverTrust
            if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null) {
                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                return
            }
            when {
                config.insecure -> completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
                config.trustMincifryCa ->
                    if (NetworkFrameworkConnectionFactory.evaluateSecTrustWithMincifry(trust, space.host)) {
                        completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
                    } else {
                        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                    }
                else -> completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            }
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            if (didCompleteWithError != null) {
                val code = didCompleteWithError.code
                val reason = if (code == NSURLErrorCancelled) "cancelled" else didCompleteWithError.localizedDescription
                complete(Result.failure(UrlSessionException("NSURLSession: $reason", code)))
                return
            }
            val status = (task.response as? NSHTTPURLResponse)?.statusCode?.toInt()
            if (status == null) {
                complete(Result.failure(UrlSessionException("NSURLSession: no HTTP response", 0)))
                return
            }
            complete(Result.success(HttpResponse(status, received.toByteArray())))
        }
    }

    companion object {
        /** The shared default-config instance. */
        val shared: UrlSessionMediaHttp by lazy { UrlSessionMediaHttp() }

        /** `timeoutIntervalForResource`: the `NSURLSession` default of seven days. */
        private const val WHOLE_TRANSFER_SECONDS: Double = 7 * 24 * 60 * 60.0
    }
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

internal fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return bytes?.readBytes(n) ?: ByteArray(0)
}
