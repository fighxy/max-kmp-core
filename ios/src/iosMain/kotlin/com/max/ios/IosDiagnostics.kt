@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.max.ios

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook

/**
 * Crash reporting hook for the Swift app.
 *
 * An exception that escapes the core (not caught by [MaxIosClient]) terminates the process.
 * [installCrashHandler] passes its text and stack trace to [onCrash] first, so the app can write a
 * crash report before the process ends; the previous hook, if any, runs afterwards. Installing
 * twice keeps the first handler.
 *
 * [installErrorLogger] passes a one-line description of every failure a [MaxIosClient] call reports
 * (error kind, exception and its causes) to the app log: the callback itself carries only the kind.
 *
 * [installDiagnosticLogger] gets diagnostics lines that are not failures, today one per push
 * `NOTIF_DRAFT` 152 / `NOTIF_DRAFT_DISCARD` 153 with its raw payload redacted
 * ([com.max.core.events.DiagnosticLog]: keys, ids, times and types kept, a draft `text` reduced
 * to its length and first 2 characters, at most 2000 characters per line), prefixed `DIAG `.
 * Without it these lines go to the error logger, if one is installed.
 */
object IosDiagnostics {
    private val installed = AtomicInt(0)
    private val errorLogger = AtomicReference<((String) -> Unit)?>(null)

    fun installErrorLogger(onError: (String) -> Unit) {
        errorLogger.value = onError
    }

    private val diagnosticLogger = AtomicReference<((String) -> Unit)?>(null)

    fun installDiagnosticLogger(onLine: (String) -> Unit) {
        diagnosticLogger.value = onLine
    }

    internal fun reportDiagnostic(line: String) {
        val logger = diagnosticLogger.value ?: errorLogger.value ?: return
        try {
            logger("DIAG $line")
        } catch (e: Throwable) {
            // logging is best effort
        }
    }

    internal fun reportFailure(kind: String, t: Throwable) {
        val logger = errorLogger.value ?: return
        val causes = generateSequence(t) { it.cause }.take(4).joinToString(" <- ") { it.toString() }
        val frames = if (kind == "UNKNOWN") {
            t.stackTraceToString().lineSequence().drop(1).take(8).joinToString("\n") { it.trim() }
        } else {
            ""
        }
        try {
            logger("$kind: $causes" + if (frames.isEmpty()) "" else "\n$frames")
        } catch (e: Throwable) {
            // logging is best effort
        }
    }

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
