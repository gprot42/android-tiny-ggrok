package com.tinyggrok.app.data.repository

/** Max stored body size per debug log entry. */
internal const val MAX_DEBUG_LOG_BODY_CHARS = 8_192

/** Runs of base64-like characters at or above this length are redacted. */
private const val BASE64_RUN_THRESHOLD = 160

/** Insert a newline this often in leftover long lines so text layout stays cheap. */
private const val HARD_WRAP_WIDTH = 200

/**
 * data: URLs with a base64 payload (chat image attachments).
 * The payload may be a single line or pretty-printed with whitespace.
 */
private val DATA_URI_BASE64 = Regex(
    """data:[a-zA-Z0-9.+/-]+;base64,[A-Za-z0-9+/=\s]{32,}""",
    RegexOption.IGNORE_CASE
)

/** Raw base64 / base64url blobs (voice audio deltas, unprefixed image payloads). */
private val LONG_BASE64_RUN = Regex(
    """[A-Za-z0-9+/_\-]{$BASE64_RUN_THRESHOLD,}={0,2}"""
)

/**
 * Make a debug-log body safe to keep in memory and to lay out in Compose.
 *
 * Untruncated request JSON with image attachments is often hundreds of KB to
 * several MB of unbroken base64. Compose [Text] measures that on the main thread
 * via minikin line-breaking, which ANRs the activity.
 */
internal fun sanitizeLogBody(
    body: String,
    maxChars: Int = MAX_DEBUG_LOG_BODY_CHARS
): String {
    if (body.isEmpty()) return body

    var out = DATA_URI_BASE64.replace(body) { match ->
        val value = match.value
        val comma = value.indexOf(',')
        val prefix = if (comma >= 0) value.substring(0, comma + 1) else "data:;base64,"
        val payloadLen = value.length - prefix.length
        "${prefix}<omitted $payloadLen chars>"
    }
    out = LONG_BASE64_RUN.replace(out) { match ->
        "<base64 omitted ${match.value.length} chars>"
    }
    if (out.length > maxChars) {
        val omitted = out.length - maxChars
        out = out.take(maxChars) + "\n… [truncated, $omitted more chars]"
    }
    return hardWrapLongLines(out, HARD_WRAP_WIDTH)
}

/** Break giant unwrapped lines so minikin does not scan tens of thousands of glyphs. */
internal fun hardWrapLongLines(text: String, width: Int): String {
    if (width <= 0 || text.length <= width) return text
    val sb = StringBuilder(text.length + text.length / width)
    var col = 0
    for (ch in text) {
        if (ch == '\n') {
            sb.append(ch)
            col = 0
        } else {
            if (col >= width) {
                sb.append('\n')
                col = 0
            }
            sb.append(ch)
            col++
        }
    }
    return sb.toString()
}
