package com.maxly.core.transport

/** JVM (desktop): `java.net.Socket` + `javax.net.ssl`, see [JavaSocketConnectionFactory]. */
actual fun defaultConnectionFactory(): ConnectionFactory = JavaSocketConnectionFactory()
