package com.tinyggrok.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    fun timeoutDetectionIncludesCallTimeout() {
        assertTrue(isTimeoutFailure(SocketTimeoutException("timeout")))
        assertTrue(isTimeoutFailure(InterruptedIOException("timeout")))
        assertTrue(isTimeoutFailure(RuntimeException("wrap", InterruptedIOException("timeout"))))
        assertFalse(isTimeoutFailure(UnknownHostException("api.x.ai")))
        assertFalse(isTimeoutFailure(InterruptedIOException("thread interrupted")))
    }
}
