package com.maxly.core.transport

import javax.net.ssl.HttpsURLConnection

/**
 * Android: the same `java.net.Socket` + `javax.net.ssl` factory as on the JVM
 * ([JavaSocketConnectionFactory], Conscrypt underneath), plus Android's default host name verifier
 * after the handshake. Needs the `android.permission.INTERNET` permission in the app.
 *
 * `androidMain` is compiled in CI (`:core:compileDebugKotlinAndroid`) and is not run on a device.
 * `:android` is compiled there too (`:android:compileDebugKotlin`); see docs/architecture.md.
 */
actual fun defaultConnectionFactory(): ConnectionFactory =
    JavaSocketConnectionFactory(hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier())
