package com.max.core.transport

import javax.net.ssl.HttpsURLConnection

/**
 * Android: the same `java.net.Socket` + `javax.net.ssl` factory as on the JVM
 * ([JavaSocketConnectionFactory], Conscrypt underneath), plus Android's default host name verifier
 * after the handshake. Needs the `android.permission.INTERNET` permission in the app.
 *
 * Not compiled or run in this repository's CI yet (no Android SDK); see docs/architecture.md.
 */
actual fun defaultConnectionFactory(): ConnectionFactory =
    JavaSocketConnectionFactory(hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier())
