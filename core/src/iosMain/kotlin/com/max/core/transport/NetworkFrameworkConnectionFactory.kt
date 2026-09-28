@file:OptIn(ExperimentalForeignApi::class)

package com.max.core.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFErrorCopyDescription
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Network.NW_CONNECTION_DEFAULT_STREAM_CONTEXT
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_copy_cf_error
import platform.Network.nw_error_domain_dns
import platform.Network.nw_error_domain_posix
import platform.Network.nw_error_domain_tls
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_privacy_context
import platform.Network.nw_parameters_t
import platform.Network.nw_privacy_context_add_proxy
import platform.Network.nw_privacy_context_create
import platform.Network.nw_proxy_config_create_http_connect
import platform.Network.nw_proxy_config_create_socksv5
import platform.Network.nw_proxy_config_set_failover_allowed
import platform.Network.nw_proxy_config_set_username_and_password
import platform.Network.nw_tcp_options_set_connection_timeout
import platform.Network.nw_tcp_options_set_no_delay
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecPolicyCreateSSL
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRef
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly
import platform.Security.SecTrustSetPolicies
import platform.Security.sec_protocol_options_set_tls_server_name
import platform.Security.sec_protocol_options_set_verify_block
import platform.Security.sec_trust_copy_ref
import platform.Security.sec_trust_t
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/*
 * Binding notes (Kotlin/Native 2.1.10 platform klibs, ios_arm64, checked with `klib dump-metadata`):
 * - every nw_* / sec_* / dispatch_* object type (nw_connection_t, nw_parameters_t, nw_error_t,
 *   sec_trust_t, dispatch_data_t, dispatch_queue_t, ...) is a typealias for
 *   `platform.darwin.NSObject?`; CoreFoundation / SecTrust refs are `CPointer<__X>?`;
 * - C blocks are Kotlin function types (`nw_connection_receive` completion is
 *   `(dispatch_data_t, nw_content_context_t, Boolean, nw_error_t) -> Unit`, the state handler is
 *   `(UInt, nw_error_t) -> Unit`, the verify block is
 *   `(sec_protocol_metadata_t, sec_trust_t, ((Boolean) -> Unit)?) -> Unit`);
 * - `nw_proxy_config_*` / `nw_privacy_context_add_proxy` are declared `extern_weak` in the klib
 *   cstubs (API_AVAILABLE ios 17.0 in the SDK headers), so calling them below iOS 17 would jump
 *   to NULL: they are only reached after the [planNativeProxy] OS version gate.
 */

/**
 * iOS [ConnectionFactory] on Apple Network.framework (`nw_connection` with TLS parameters).
 *
 * - TLS: `nw_parameters_create_secure_tcp`; SNI = target host
 *   (`sec_protocol_options_set_tls_server_name`). Trust per [TlsOptions.trustMode]:
 *   [TlsTrustMode.SYSTEM] keeps the default evaluation, [TlsTrustMode.SYSTEM_AND_MINCIFRY]
 *   installs a verify block that evaluates the peer `SecTrust` with an SSL policy for the host and
 *   the [MincifryCa] certificates added as anchors next to the system roots
 *   (`SecTrustSetAnchorCertificates` + `SecTrustSetAnchorCertificatesOnly(false)`), and
 *   [TlsTrustMode.INSECURE] installs a verify block that accepts everything.
 * - Proxy: HTTP CONNECT and SOCKS5 through Network.framework's own proxy support
 *   (`nw_proxy_config_create_http_connect` / `nw_proxy_config_create_socksv5` on an
 *   `nw_privacy_context`), iOS 17+ only; below 17 [open] throws [ProxyException]. TLS still runs
 *   end-to-end to the target through the tunnel, and the target host name is passed to the proxy
 *   (so `socks5` behaves like `socks5h`, matching the common handshake).
 * - I/O: [RawConnection.read] is `nw_connection_receive` (min 1 byte, max
 *   [nativeReceiveMax]), [RawConnection.write] is `nw_connection_send` with the default stream
 *   context, [RawConnection.close] is `nw_connection_cancel`.
 *
 * All callbacks run on one private serial dispatch queue per connection (the certificate check on
 * a global queue, since `SecTrustEvaluateWithError` may block on network fetches).
 */
class NetworkFrameworkConnectionFactory : ConnectionFactory {

    override suspend fun open(host: String, port: Int, tls: TlsOptions, proxy: ProxyConfig?): RawConnection {
        require(port in 1..65535) { "port out of range: $port" }
        val plan = planNativeProxy(proxy, currentIosMajorVersion())
        if (plan is NativeProxyPlan.Unsupported) throw ProxyException(plan.reason)

        val queue = dispatch_queue_create("com.max.core.transport.nw", null)
            ?: throw TransportException("dispatch_queue_create failed")
        val parameters = secureTcpParameters(host, tls)
            ?: throw TransportException("nw_parameters_create_secure_tcp failed")
        applyProxy(parameters, plan)

        val endpoint = nw_endpoint_create_host(host, port.toString())
            ?: throw TransportException("invalid endpoint $host:$port")
        val connection = nw_connection_create(endpoint, parameters)
            ?: throw TransportException("nw_connection_create failed for $host:$port")
        nw_connection_set_queue(connection, queue)

        val raw = NwRawConnection(connection, queue, "$host:$port")
        raw.start(tls.connectTimeout)
        return raw
    }

    companion object {
        /** Major version of the running iOS (`NSProcessInfo.operatingSystemVersion`). */
        fun currentIosMajorVersion(): Int =
            NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion.toInt() }

        /** `true` on iOS [IOS_PROXY_MIN_MAJOR_VERSION]+ (Network.framework proxies available). */
        fun isProxySupported(): Boolean =
            NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
                cValue<NSOperatingSystemVersion> {
                    majorVersion = IOS_PROXY_MIN_MAJOR_VERSION.toLong()
                    minorVersion = 0
                    patchVersion = 0
                },
            )

        /** TCP (no delay, connect timeout) + TLS (SNI, trust per [TlsOptions.trustMode]). */
        internal fun secureTcpParameters(host: String, tls: TlsOptions): nw_parameters_t {
            val mode = tls.trustMode()
            val timeoutSeconds = nativeConnectTimeoutSeconds(tls.connectTimeout)
            return nw_parameters_create_secure_tcp(
                { tlsOptions ->
                    val sec = nw_tls_copy_sec_protocol_options(tlsOptions)
                    sec_protocol_options_set_tls_server_name(sec, host)
                    when (mode) {
                        TlsTrustMode.SYSTEM -> Unit // Network.framework default evaluation
                        TlsTrustMode.INSECURE -> sec_protocol_options_set_verify_block(
                            sec,
                            { _, _, complete -> complete?.invoke(true) },
                            verifyQueue(),
                        )
                        TlsTrustMode.SYSTEM_AND_MINCIFRY -> sec_protocol_options_set_verify_block(
                            sec,
                            { _, trust, complete -> complete?.invoke(evaluateWithMincifry(trust, host)) },
                            verifyQueue(),
                        )
                    }
                },
                { tcpOptions ->
                    nw_tcp_options_set_no_delay(tcpOptions, true)
                    if (timeoutSeconds > 0) nw_tcp_options_set_connection_timeout(tcpOptions, timeoutSeconds.toUInt())
                },
            )
        }

        /** Installs the iOS 17 proxy configuration for [plan] on [parameters]. */
        private fun applyProxy(parameters: nw_parameters_t, plan: NativeProxyPlan) {
            val proxy = when (plan) {
                NativeProxyPlan.Direct -> return
                is NativeProxyPlan.Unsupported -> throw ProxyException(plan.reason)
                is NativeProxyPlan.HttpConnect -> plan.proxy
                is NativeProxyPlan.Socks5 -> plan.proxy
            }
            // Defensive second gate: the functions below are weak symbols (iOS 17+).
            if (!isProxySupported()) {
                throw ProxyException("proxy not supported on iOS < $IOS_PROXY_MIN_MAJOR_VERSION")
            }
            val proxyEndpoint = nw_endpoint_create_host(proxy.host, proxy.port.toString())
                ?: throw ProxyException("invalid proxy endpoint ${proxy.host}:${proxy.port}")
            val config = when (plan) {
                is NativeProxyPlan.HttpConnect -> nw_proxy_config_create_http_connect(proxyEndpoint, null)
                else -> nw_proxy_config_create_socksv5(proxyEndpoint)
            } ?: throw ProxyException("nw_proxy_config_create failed for $proxy")
            proxy.username?.let { nw_proxy_config_set_username_and_password(config, it, proxy.password) }
            nw_proxy_config_set_failover_allowed(config, false) // never fall back to a direct connection
            val context = nw_privacy_context_create("com.max.core.transport.proxy")
                ?: throw ProxyException("nw_privacy_context_create failed")
            nw_privacy_context_add_proxy(context, config)
            nw_parameters_set_privacy_context(parameters, context)
        }

        private fun verifyQueue(): dispatch_queue_t = dispatch_get_global_queue(0L, 0uL) // DISPATCH_QUEUE_PRIORITY_DEFAULT

        /**
         * Evaluates the peer [trust] with an SSL server policy for [host] and anchors = system
         * roots + [MincifryCa]. Runs on [verifyQueue].
         */
        internal fun evaluateWithMincifry(trust: sec_trust_t, host: String): Boolean {
            val secTrust = sec_trust_copy_ref(trust) ?: return false // +1, released below
            try {
                return evaluateSecTrustWithMincifry(secTrust, host)
            } finally {
                CFRelease(secTrust)
            }
        }

        /**
         * Evaluates [secTrust] (not consumed) with an SSL server policy for [host] and anchors =
         * system roots + [MincifryCa]. Also used by the NSURLSession media client.
         */
        internal fun evaluateSecTrustWithMincifry(secTrust: SecTrustRef, host: String): Boolean {
            val cfHost = CFStringCreateWithCString(null, host, kCFStringEncodingUTF8)
            val policy = SecPolicyCreateSSL(true, cfHost)
            try {
                if (policy != null && SecTrustSetPolicies(secTrust, policy) != 0) return false
            } finally {
                if (policy != null) CFRelease(policy)
                if (cfHost != null) CFRelease(cfHost)
            }
            val anchors = mincifryAnchors ?: return false
            if (SecTrustSetAnchorCertificates(secTrust, anchors) != 0) return false
            // false = the custom anchors are added to, not substituted for, the system roots
            if (SecTrustSetAnchorCertificatesOnly(secTrust, false) != 0) return false
            return SecTrustEvaluateWithError(secTrust, null)
        }

        /** [MincifryCa.derCertificates] as a CFArray of SecCertificate, built once, never freed. */
        private val mincifryAnchors: CFArrayRef? by lazy { createCertificateArray(MincifryCa.derCertificates) }

        /** Builds a CFArray of `SecCertificateRef` from DER blobs; `null` if any fails to parse. */
        internal fun createCertificateArray(ders: List<ByteArray>): CFArrayRef? {
            val array = CFArrayCreateMutable(null, ders.size.toLong(), kCFTypeArrayCallBacks.ptr) ?: return null
            for (der in ders) {
                val cert = der.usePinned { pinned ->
                    val data = CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), der.size.toLong())
                        ?: return@usePinned null
                    try {
                        SecCertificateCreateWithData(null, data)
                    } finally {
                        CFRelease(data)
                    }
                }
                if (cert == null) {
                    CFRelease(array)
                    return null
                }
                CFArrayAppendValue(array, cert) // the array retains it
                CFRelease(cert)
            }
            return array
        }
    }
}

/** An `nw_connection_t` as a [RawConnection]. */
internal class NwRawConnection(
    private val connection: nw_connection_t,
    private val queue: dispatch_queue_t,
    private val label: String,
) : RawConnection {
    /** 0 = open, 1 = closed by [close] or by the stack (failed / cancelled). */
    private val closed = AtomicInt(0)

    /** Set once a receive reported the end of the stream. */
    private val eof = AtomicInt(0)

    /**
     * Starts the connection and suspends until it is ready. `failed`, `waiting` (no route,
     * connection refused, ...) and `cancelled` before `ready` throw; [timeout] throws
     * [ConnectTimeoutException]; cancellation of the caller cancels the connection.
     */
    suspend fun start(timeout: Duration) {
        val ready = CompletableDeferred<Unit>()
        nw_connection_set_state_changed_handler(connection) { state, error ->
            when (state) {
                nw_connection_state_ready -> ready.complete(Unit)
                nw_connection_state_waiting -> {
                    // Network.framework would keep retrying on path changes; MaxTransport has its
                    // own reconnect, so fail on the first waiting delivery (usually "no route").
                    val msg = if (error != null) describeNwError(error) else "waiting (no usable path)"
                    if (ready.completeExceptionally(TransportException("connect to $label failed: $msg"))) {
                        cancelConnection()
                    }
                }
                nw_connection_state_failed -> {
                    ready.completeExceptionally(TransportException("connection to $label failed: ${describeNwError(error)}"))
                    cancelConnection()
                }
                nw_connection_state_cancelled -> {
                    closed.value = 1
                    ready.completeExceptionally(ConnectionClosedException("connection to $label cancelled"))
                    // Last event: drop the handler so the block (which references this object)
                    // does not keep a cycle with the connection alive.
                    nw_connection_set_state_changed_handler(connection, null)
                }
                else -> Unit // invalid, preparing
            }
        }
        nw_connection_start(connection)
        try {
            if (timeout.isInfinite()) ready.await() else withTimeout(timeout) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            cancelConnection()
            throw ConnectTimeoutException("connect to $label timed out after $timeout")
        } catch (e: Throwable) {
            cancelConnection()
            throw e
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) { "bad range $offset+$length of ${buffer.size}" }
        if (length == 0) return 0
        if (eof.value == 1) return -1
        if (closed.value == 1) throw ConnectionClosedException("connection to $label closed")
        val max = nativeReceiveMax(length)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancelConnection() }
            nw_connection_receive(connection, 1u, max.toUInt()) { content, _, isComplete, error ->
                // after cancellation the caller no longer owns the buffer: do not touch it
                if (!cont.isActive) return@nw_connection_receive
                val n = if (content != null) copyInto(content, buffer, offset, max) else 0
                when {
                    n > 0 -> {
                        if (isComplete) eof.value = 1
                        cont.resume(n)
                    }
                    error != null -> cont.resumeWithException(
                        ConnectionClosedException("receive from $label failed: ${describeNwError(error)}"),
                    )
                    isComplete -> {
                        eof.value = 1
                        cont.resume(-1)
                    }
                    else -> cont.resume(0) // empty delivery; the caller reads again
                }
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (closed.value == 1) throw ConnectionClosedException("connection to $label closed")
        // destructor = null (DISPATCH_DATA_DESTRUCTOR_DEFAULT): dispatch copies the bytes now
        val data = bytes.usePinned { dispatch_data_create(it.addressOf(0), bytes.size.toULong(), queue, null) }
            ?: throw TransportException("dispatch_data_create failed")
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancelConnection() }
            // STREAM + is_complete=false: keep the TCP send side open (MESSAGE + true would FIN after the first write).
            nw_connection_send(connection, data, NW_CONNECTION_DEFAULT_STREAM_CONTEXT, false) { error ->
                if (error == null) {
                    cont.resume(Unit)
                } else {
                    cont.resumeWithException(ConnectionClosedException("send to $label failed: ${describeNwError(error)}"))
                }
            }
        }
    }

    override suspend fun close() = cancelConnection()

    /** Idempotent `nw_connection_cancel`; pending receives / sends then complete with an error. */
    private fun cancelConnection() {
        if (closed.compareAndSet(0, 1)) nw_connection_cancel(connection)
    }
}

/**
 * Copies [data] (at most [max] bytes, which `nw_connection_receive` guarantees) into [buffer] at
 * [offset]; returns the byte count.
 */
private fun copyInto(data: dispatch_data_t, buffer: ByteArray, offset: Int, max: Int): Int {
    val size = dispatch_data_get_size(data).toLong().coerceAtMost(max.toLong()).toInt()
    if (size <= 0) return 0
    var copied = 0
    dispatch_data_apply(data) { _, _, region, regionSize ->
        val n = minOf(regionSize.toLong(), (size - copied).toLong()).toInt()
        if (region != null && n > 0) {
            region.readBytes(n).copyInto(buffer, offset + copied)
            copied += n
        }
        copied < size // false stops the traversal
    }
    return copied
}

/** "TLS error -9808: <description>" for an `nw_error_t`. */
internal fun describeNwError(error: nw_error_t): String {
    if (error == null) return "no error details"
    val domain = when (nw_error_get_error_domain(error)) {
        nw_error_domain_posix -> "POSIX"
        nw_error_domain_dns -> "DNS"
        nw_error_domain_tls -> "TLS"
        else -> "unknown"
    }
    val code = nw_error_get_error_code(error)
    val description = nw_error_copy_cf_error(error)?.let { cfError ->
        try {
            CFErrorCopyDescription(cfError)?.let { CFBridgingRelease(it) as? String }
        } finally {
            CFRelease(cfError)
        }
    }
    return if (description.isNullOrBlank()) "$domain error $code" else "$domain error $code: $description"
}
