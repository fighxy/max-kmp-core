package com.maxly.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException

/*
 * JVM + Android connection factory. This file lives in src/jvmAndroidShared and is added as an
 * extra source directory to both jvmMain and androidMain (core/build.gradle.kts): KMP cannot share
 * a source set between a JVM and an Android target, and this code only needs java.net / javax.net.ssl,
 * which both have.
 */

/**
 * Opens `java.net.Socket` (to the target or to the proxy), runs the proxy handshake over the plain
 * socket if needed, then layers TLS on it with `SSLSocketFactory.createSocket(socket, host, port,
 * autoClose)`, so SNI and the certificate check use the target [host] even through a tunnel.
 *
 * Trust: system roots ([TrustManagerFactory] default) plus, with [TlsOptions.trustMincifryCa], the
 * [MincifryCa] certificates; [TlsOptions.insecure] trusts everything and skips the host name
 * check. Host names are checked with endpoint identification `HTTPS` and, if given,
 * [hostnameVerifier] (Android passes its default verifier).
 *
 * Blocking socket calls run on [Dispatchers.IO]; cancelling the calling coroutine closes the
 * socket, which aborts the blocked call.
 */
class JavaSocketConnectionFactory(
    private val hostnameVerifier: HostnameVerifier? = null,
) : ConnectionFactory {

    override suspend fun open(host: String, port: Int, tls: TlsOptions, proxy: ProxyConfig?): RawConnection {
        val timeoutMs = tls.connectTimeout.inWholeMilliseconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val socket = Socket()
        try {
            blockingIo(onCancel = { closeQuietly(socket) }) {
                socket.tcpNoDelay = true
                val address = if (proxy != null) InetSocketAddress(proxy.host, proxy.port) else InetSocketAddress(host, port)
                socket.connect(address, timeoutMs)
                socket.soTimeout = timeoutMs
            }
            if (proxy != null) {
                performProxyHandshake(JavaSocketConnection(socket), proxy, host, port)
            }
            val ssl = sslContext(tls).socketFactory.createSocket(socket, host, port, true) as SSLSocket
            if (!tls.insecure) {
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            blockingIo(onCancel = { closeQuietly(ssl) }) {
                ssl.startHandshake()
                if (!tls.insecure && hostnameVerifier != null && !hostnameVerifier.verify(host, ssl.session)) {
                    throw SSLPeerUnverifiedException("certificate does not match host $host")
                }
                ssl.soTimeout = 0
            }
            return JavaSocketConnection(ssl)
        } catch (e: Throwable) {
            closeQuietly(socket)
            throw e
        }
    }

    companion object {
        /** SSLContext for [tls]: trust-all, or system roots (+ Минцифры CA). */
        fun sslContext(tls: TlsOptions): SSLContext {
            val trustManager: X509TrustManager = when {
                tls.insecure -> TrustAllManager
                tls.trustMincifryCa -> CompositeTrustManager(listOf(systemTrustManager(), mincifryTrustManager()))
                else -> systemTrustManager()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }
        }

        /** The platform default trust manager (system CA store). */
        fun systemTrustManager(): X509TrustManager = trustManagerFor(null)

        /** A trust manager whose only anchors are the [MincifryCa] certificates. */
        fun mincifryTrustManager(): X509TrustManager {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            mincifryCertificates().forEachIndexed { i, cert -> store.setCertificateEntry("mincifry-$i", cert) }
            return trustManagerFor(store)
        }

        /** [MincifryCa] parsed as X.509 certificates. */
        fun mincifryCertificates(): List<X509Certificate> {
            val cf = CertificateFactory.getInstance("X.509")
            return MincifryCa.derCertificates.map { cf.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        }

        private fun trustManagerFor(store: KeyStore?): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(store)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/** A socket (plain or TLS) as a [RawConnection]. */
internal class JavaSocketConnection(private val socket: Socket) : RawConnection {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        blockingIo(onCancel = { closeQuietly(socket) }) { input.read(buffer, offset, length) }

    override suspend fun write(bytes: ByteArray) =
        blockingIo(onCancel = { closeQuietly(socket) }) {
            output.write(bytes)
            output.flush()
        }

    override suspend fun close() = closeQuietly(socket)
}

/** Accepts the chain if any delegate accepts it; issuers are the union of all delegates. */
internal class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        firstAccepting { it.checkClientTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
        firstAccepting { it.checkServerTrusted(chain, authType) }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegates.flatMap { it.acceptedIssuers.asList() }.toTypedArray()

    private inline fun firstAccepting(check: (X509TrustManager) -> Unit) {
        var first: CertificateException? = null
        for (delegate in delegates) {
            try {
                check(delegate)
                return
            } catch (e: CertificateException) {
                if (first == null) first = e else first.addSuppressed(e)
            }
        }
        throw first ?: CertificateException("no trust managers")
    }
}

/** Trusts every certificate. Debug only ([TlsOptions.insecure]). */
internal object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Runs a blocking call on [Dispatchers.IO] and suspends until it returns. If the caller is
 * cancelled, [onCancel] runs right away (closing the socket makes the blocked call throw) and the
 * call's late result is ignored.
 */
internal suspend fun <T> blockingIo(onCancel: () -> Unit, block: () -> T): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { onCancel() }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            try {
                val value = block()
                cont.resumeWith(Result.success(value))
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

private fun closeQuietly(socket: Socket) {
    try {
        socket.close()
    } catch (_: Exception) {
    }
}
