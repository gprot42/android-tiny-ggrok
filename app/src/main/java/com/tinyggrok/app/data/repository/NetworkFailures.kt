package com.tinyggrok.app.data.repository

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal fun Throwable.causeChain(): List<Throwable> =
    generateSequence(this) { it.cause }.distinct().toList()

/** Connect / DNS / reset / TLS handshake — worth another attempt. Not a long hung responses call. */
internal fun isTransientConnectFailure(error: Throwable): Boolean {
    return error.causeChain().any { t ->
        when (t) {
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException -> true
            is SSLPeerUnverifiedException -> false
            is SSLHandshakeException -> {
                // Handshake reset mid-way (captive portal, flaky radio) — not a cert failure.
                val m = t.message.orEmpty().lowercase()
                "reset" in m || "closed" in m || "eof" in m || "broken pipe" in m ||
                    "timed out" in m || "handshake failed" in m
            }
            is SSLException -> {
                val m = t.message.orEmpty().lowercase()
                "reset" in m || "closed" in m || "broken pipe" in m || "eof" in m
            }
            is SocketTimeoutException -> {
                val m = t.message.orEmpty().lowercase()
                "failed to connect" in m || "connect timed out" in m || "failed to connect to" in m
            }
            is SocketException -> {
                val m = t.message.orEmpty().lowercase()
                "reset" in m || "broken pipe" in m || "connection abort" in m ||
                    "software caused connection abort" in m || "network is unreachable" in m
            }
            else -> false
        }
    }
}

/**
 * Stream/connection dropped by the server or network (HTTP/2 GOAWAY/RST, EOF, reset).
 * Safe to retry only when nothing has been received yet — the caller decides that.
 */
internal fun isStreamInterruption(error: Throwable): Boolean {
    if (isTransientConnectFailure(error)) return true
    return error.causeChain().any { t ->
        when (t) {
            is EOFException -> true
            is SocketException -> true
            is IOException -> {
                val m = t.message.orEmpty().lowercase()
                "stream was reset" in m || "goaway" in m || "unexpected end of stream" in m ||
                    "canceled" == m || "connection closed" in m || "reset" in m
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

/**
 * The API rejected a tuning parameter we added (reasoning effort / max turns) rather
 * than the prompt itself. Safe to retry once with those fields stripped, so an API
 * change can never make chat unusable.
 */
internal fun isUnsupportedParameterFailure(code: Int, body: String): Boolean {
    if (code != 400 && code != 422) return false
    val lower = body.lowercase()
    val mentionsOurFields = listOf("reasoning", "max_turns", "maxturns", "effort")
        .any { it in lower }
    if (!mentionsOurFields) return false
    return listOf(
        "unknown",
        "unsupported",
        "not supported",
        "unrecognized",
        "unrecognised",
        "unexpected",
        "not allowed",
        "not permitted",
        "invalid"
    ).any { it in lower }
}

/**
 * HTTP statuses that are worth retrying with a short back-off. These are returned
 * before any tokens are generated, so a retry cannot double-bill.
 */
internal fun isRetryableHttpStatus(code: Int): Boolean =
    code == 408 || code == 425 || code == 429 || code == 500 || code == 502 ||
        code == 503 || code == 504 || code == 529

/**
 * Back-off before retry number [attempt] (1-based: first retry = 1).
 * Honours a `Retry-After` header (seconds) when present and reasonable.
 */
internal fun retryDelayMs(attempt: Int, retryAfterHeader: String? = null): Long {
    val base = when (attempt) {
        1 -> 500L
        2 -> 1_500L
        else -> 3_000L
    }
    val retryAfter = retryAfterHeader?.trim()?.toLongOrNull()
        ?.takeIf { it in 0..MAX_RETRY_AFTER_S }
        ?.let { it * 1_000L }
    return maxOf(base, retryAfter ?: 0L)
}

internal const val MAX_RETRY_AFTER_S = 10L

/** OkHttp read/connect/call timeouts (callTimeout is InterruptedIOException, not always SocketTimeout). */
internal fun isTimeoutFailure(error: Throwable): Boolean {
    return error.causeChain().any { t ->
        t is SocketTimeoutException ||
            (t is InterruptedIOException &&
                t.message.orEmpty().contains("timeout", ignoreCase = true))
    }
}

/** User-facing explanation for a DNS failure, with the fixes that actually work on Android. */
internal fun dnsFailureMessage(host: String): String =
    "Can't reach $host: DNS lookup failed on this network (tried system DNS and " +
        "DNS-over-HTTPS). Toggle Wi-Fi/mobile data, or check Private DNS and VPN settings."
