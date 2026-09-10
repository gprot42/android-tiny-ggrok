package com.tinyggrok.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class NetworkFailuresTest {

    @Test
    fun connectAndDnsAreTransient() {
        assertTrue(isTransientConnectFailure(UnknownHostException("api.x.ai")))
        assertTrue(isTransientConnectFailure(ConnectException("failed to connect")))
        assertTrue(isTransientConnectFailure(SocketException("Connection reset")))
        assertTrue(
            isTransientConnectFailure(
                SocketTimeoutException("failed to connect to api.x.ai/1.2.3.4 (port 443) after 60000ms")
            )
        )
    }

    @Test
    fun longReadTimeoutIsNotAConnectRetry() {
        assertFalse(isTransientConnectFailure(SocketTimeoutException("timeout")))
        assertFalse(isTransientConnectFailure(InterruptedIOException("timeout")))
        assertFalse(isTransientConnectFailure(RuntimeException("HTTP 429")))
    }

    @Test
    fun unknownModelFailure() {
        assertTrue(isUnknownModelFailure(404, """{"error":"model grok-4.6 not found"}"""))
        assertTrue(isUnknownModelFailure(400, "Invalid model: grok-4.6"))
        assertTrue(isUnknownModelFailure(422, "The model is not available for this team"))
        assertFalse(isUnknownModelFailure(429, "rate limit exceeded for model grok-4.6"))
        assertFalse(isUnknownModelFailure(400, "invalid temperature"))
        assertFalse(isUnknownModelFailure(500, "model overloaded"))
    }

    @Test
    fun tlsHandshakeResetIsTransientButBadCertIsNot() {
        assertTrue(
            isTransientConnectFailure(
                SSLHandshakeException("Connection closed by peer")
            )
        )
        assertTrue(isTransientConnectFailure(SSLException("Read error: ssl=0x7: I/O error during system call, Connection reset by peer")))
        assertFalse(isTransientConnectFailure(SSLPeerUnverifiedException("Hostname api.x.ai not verified")))
    }

    @Test
    fun streamInterruptionCoversHttp2ResetAndEof() {
        assertTrue(isStreamInterruption(java.io.EOFException()))
        assertTrue(isStreamInterruption(java.io.IOException("stream was reset: CANCEL")))
        assertTrue(isStreamInterruption(RuntimeException("wrap", SocketException("Connection reset"))))
        assertTrue(isStreamInterruption(UnknownHostException("api.x.ai")))
        assertFalse(isStreamInterruption(SocketTimeoutException("timeout")))
        assertFalse(isStreamInterruption(IllegalStateException("Empty stream from api.x.ai")))
    }

    @Test
    fun retryableHttpStatuses() {
        for (code in listOf(408, 425, 429, 500, 502, 503, 504, 529)) {
            assertTrue("expected $code retryable", isRetryableHttpStatus(code))
        }
        for (code in listOf(400, 401, 402, 403, 404, 413, 422)) {
            assertFalse("expected $code not retryable", isRetryableHttpStatus(code))
        }
    }

    @Test
    fun retryDelayGrowsAndHonoursRetryAfter() {
        assertEquals(500L, retryDelayMs(1))
        assertEquals(1_500L, retryDelayMs(2))
        assertEquals(3_000L, retryDelayMs(3))
        assertEquals(2_000L, retryDelayMs(1, "2"))
        // Never shorter than the base back-off, never longer than the cap.
        assertEquals(1_500L, retryDelayMs(2, "0"))
        assertEquals(500L, retryDelayMs(1, "3600"))
        assertEquals(500L, retryDelayMs(1, "Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun unsupportedTuningParametersAreDetected() {
        assertTrue(isUnsupportedParameterFailure(400, """{"error":"Unknown field: max_turns"}"""))
        assertTrue(isUnsupportedParameterFailure(400, "unsupported parameter: reasoning.effort"))
        assertTrue(isUnsupportedParameterFailure(422, "invalid value for effort"))
        // A rejection about the prompt itself must not strip our tuning fields.
        assertFalse(isUnsupportedParameterFailure(400, "invalid temperature"))
        assertFalse(isUnsupportedParameterFailure(400, """{"error":"model grok-4.6 not found"}"""))
        assertFalse(isUnsupportedParameterFailure(429, "unknown reasoning field"))
    }

    @Test
    fun timeoutDetectionIncludesCallTimeout() {
        assertTrue(isTimeoutFailure(SocketTimeoutException("timeout")))
        assertTrue(isTimeoutFailure(InterruptedIOException("timeout")))
        assertTrue(isTimeoutFailure(RuntimeException("wrap", InterruptedIOException("timeout"))))
        assertFalse(isTimeoutFailure(UnknownHostException("api.x.ai")))
        assertFalse(isTimeoutFailure(InterruptedIOException("thread interrupted")))
    }
}
