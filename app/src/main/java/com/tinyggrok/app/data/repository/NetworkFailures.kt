package com.tinyggrok.app.data.repository

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal fun Throwable.causeChain(): List<Throwable> =
    generateSequence(this) { it.cause }.distinct().toList()

/** Connect / DNS / reset — worth one immediate retry. Not a long hung responses call. */
internal fun isTransientConnectFailure(error: Throwable): Boolean {
    return error.causeChain().any { t ->
        when (t) {
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException -> true
            is SocketTimeoutException -> {
                val m = t.message.orEmpty().lowercase()
                "failed to connect" in m || "connect timed out" in m || "failed to connect to" in m
            }
            is SocketException -> {
                val m = t.message.orEmpty().lowercase()
                "reset" in m || "broken pipe" in m || "connection abort" in m ||
                    "software caused connection abort" in m
            }
            else -> false
        }
    }
}

/** API rejected the model id (not found / invalid) — safe to retry with the backup model. */
internal fun isUnknownModelFailure(code: Int, body: String): Boolean {
    if (code != 400 && code != 404 && code != 422) return false
    val lower = body.lowercase()
    if ("model" !in lower) return false
    return listOf(
        "not found",
        "unknown",
        "invalid",
        "does not exist",
        "not available",
        "unsupported",
        "not supported"
    ).any { it in lower }
}

/** OkHttp read/connect/call timeouts (callTimeout is InterruptedIOException, not always SocketTimeout). */
internal fun isTimeoutFailure(error: Throwable): Boolean {
    return error.causeChain().any { t ->
        t is SocketTimeoutException ||
            (t is InterruptedIOException &&
                t.message.orEmpty().contains("timeout", ignoreCase = true))
    }
}
