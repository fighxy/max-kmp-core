package com.maxly.core

/** JVM / Android: socket, DNS, TLS (`SSLException`) and HTTP client failures are `IOException`s. */
internal actual fun isPlatformIoException(t: Throwable): Boolean = t is java.io.IOException
