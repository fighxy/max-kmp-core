package com.max.core

import com.max.core.media.UrlSessionException

/** iOS: the socket transport throws `TransportException`s; the CDN client `UrlSessionException`. */
internal actual fun isPlatformIoException(t: Throwable): Boolean = t is UrlSessionException
