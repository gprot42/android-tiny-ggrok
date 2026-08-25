package com.tinyggrok.app.data.repository

import com.tinyggrok.app.data.model.InputContent
import com.tinyggrok.app.data.model.InputMessage
import com.tinyggrok.app.data.model.ResponsesRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun emptyBodyUnchanged() {
        assertEquals("", sanitizeLogBody(""))
    }

    @Test
    fun shortPlainTextUnchangedExceptExistingNewlines() {
        val body = "HTTP 200\nusage=12 tokens"
        assertEquals(body, sanitizeLogBody(body))
    }

    @Test
    fun redactsDataUriBase64Payload() {
        val payload = "A".repeat(400)
        val body = """{"image_url":"data:image/jpeg;base64,$payload"}"""
        val out = sanitizeLogBody(body)
        assertFalse(out.contains(payload))
        assertTrue(out.contains("data:image/jpeg;base64,<omitted ${payload.length} chars>"))
    }

    @Test
    fun redactsRawLongBase64Runs() {
        val audio = "B".repeat(512) + "=="
        val body = """{"type":"response.audio.delta","delta":"$audio"}"""
        val out = sanitizeLogBody(body)
        assertFalse(out.contains(audio))
        assertTrue(out.contains("<base64 omitted"))
    }

    @Test
    fun truncatesOversizedBodies() {
        val body = "hello world ".repeat(2_000)
        val out = sanitizeLogBody(body, maxChars = 200)
        assertTrue(out.length < body.length)
        assertTrue(out.contains("truncated"))
        assertTrue(out.startsWith("hello world"))
    }

    @Test
    fun hardWrapsLongUnbrokenLines() {
        val line = "abcdefghij".repeat(30) // 300 chars, no spaces
        val wrapped = hardWrapLongLines(line, width = 80)
        val rows = wrapped.split('\n')
        assertTrue(rows.size > 1)
        assertTrue(rows.all { it.length <= 80 })
        assertEquals(line, wrapped.replace("\n", ""))
    }

    @Test
    fun redactImagesForLogStripsDataUrls() {
        val image = "data:image/jpeg;base64," + "X".repeat(8_000)
        val request = ResponsesRequest(
            input = listOf(
                InputMessage(
                    role = "user",
                    content = listOf(
                        InputContent(type = "input_image", imageUrl = image),
                        InputContent(type = "input_text", text = "what is this?")
                    )
                )
            )
        )
        val redacted = redactImagesForLog(request)
        val parts = redacted.input.single().content as List<*>
        val imagePart = parts[0] as InputContent
        val textPart = parts[1] as InputContent
        assertTrue(imagePart.imageUrl!!.contains("omitted ${image.length} chars"))
        assertFalse(imagePart.imageUrl!!.contains("XXXX"))
        assertEquals("what is this?", textPart.text)
    }
}
