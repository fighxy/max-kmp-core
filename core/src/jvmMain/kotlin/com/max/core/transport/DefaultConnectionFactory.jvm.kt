package com.max.core.transport

/** JVM (desktop): `java.net.Socket` + `javax.net.ssl`, see [JavaSocketConnectionFactory]. */
actual fun defaultConnectionFactory(): ConnectionFactory = JavaSocketConnectionFactory()
