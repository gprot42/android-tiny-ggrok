package com.tinyggrok.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Cap the in-memory ring so debug logs cannot grow into gigabytes of RSS. */
internal const val MAX_DEBUG_LOG_ENTRIES = 200

data class DebugLogEntry(
    val id: Long,
    val timestamp: String,
    val timestampMillis: Long,
    val direction: String,
    val summary: String,
    val body: String
)

@Singleton
class DebugLogRepository @Inject constructor() {
    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val nextId = AtomicLong(0)

    fun logOutgoing(summary: String, body: String) {
        append("→ OUT", summary, body)
    }

    fun logIncoming(summary: String, body: String) {
        append("← IN", summary, body)
    }

    /** Voice-specific log entry — always written regardless of chat debug mode. */
    fun logVoice(direction: String, summary: String, body: String) {
        append(direction, summary, body)
    }

    fun clear() {
        _logs.value = emptyList()
    }

    private fun append(direction: String, summary: String, body: String) {
        val now = System.currentTimeMillis()
        val entry = DebugLogEntry(
            id = nextId.incrementAndGet(),
            timestamp = formatter.format(Date(now)),
            timestampMillis = now,
            direction = direction,
            summary = summary,
            body = sanitizeLogBody(body)
        )
        val current = _logs.value
        _logs.value = if (current.size >= MAX_DEBUG_LOG_ENTRIES) {
            current.drop(current.size - (MAX_DEBUG_LOG_ENTRIES - 1)) + entry
        } else {
            current + entry
        }
    }
}
