package com.max.core.media

/**
 * iOS: `NSURLSession`, see [UrlSessionMediaHttp]. A [MediaHttpConfig.proxy] is rejected
 * (`IllegalArgumentException`).
 */
actual fun defaultMediaHttp(config: MediaHttpConfig): MediaHttp =
    if (config == MediaHttpConfig()) UrlSessionMediaHttp.shared else UrlSessionMediaHttp(config)
