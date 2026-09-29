@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.max.ios

import kotlin.concurrent.AtomicInt
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook

/**
 * Crash reporting hook for the Swift app.
 *
 * An exception that escapes the core (not caught by [MaxIosClient]) terminates the process.
 * [installCrashHandler] passes its text and stack trace to [onCrash] first, so the app can write a
 * crash report before the process ends; the previous hook, if any, runs afterwards. Installing
 * twice keeps the first handler.
 */
object IosDiagnostics {
    private val installed = AtomicInt(0)

    fun installCrashHandler(onCrash: (String) -> Unit) {
        if (!installed.compareAndSet(0, 1)) return
        var previous: ReportUnhandledExceptionHook? = null
        previous = setUnhandledExceptionHook { throwable ->
            try {
                onCrash("Uncaught Kotlin exception: " + throwable.stackTraceToString())
            } catch (t: Throwable) {
                // the report is best effort; the process is terminating anyway
            }
            previous?.invoke(throwable)
        }
    }
}
