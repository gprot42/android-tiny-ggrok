package com.tinyggrok.app.data.local

import com.tinyggrok.app.ui.viewmodel.ChatUiMessage
import com.tinyggrok.app.ui.viewmodel.CostInfo
import com.tinyggrok.app.ui.viewmodel.toStored
import com.tinyggrok.app.ui.viewmodel.toUiMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTranscriptTest {

    private fun message(i: Int, length: Int = 10) =
        StoredMessage(id = "m$i", role = if (i % 2 == 0) "user" else "assistant", content = "x".repeat(length))

    @Test
    fun `a normal conversation is kept whole`() {
        val messages = (0 until 12).map { message(it) }
        assertEquals(messages, trimForStorage(messages))
    }

    @Test
    fun `a long conversation keeps its most recent messages`() {
        val messages = (0 until 100).map { message(it) }
        val kept = trimForStorage(messages, maxMessages = 60)
        assertEquals(60, kept.size)
        assertEquals("m99", kept.last().id)
        assertEquals("m40", kept.first().id)
    }

    @Test
    fun `size is bounded by dropping the oldest`() {
        val messages = (0 until 10).map { message(it, length = 1_000) }
        val kept = trimForStorage(messages, maxChars = 3_500)
        assertEquals(listOf("m7", "m8", "m9"), kept.map { it.id })
    }

    @Test
    fun `one answer larger than the whole budget is still kept`() {
        val kept = trimForStorage(listOf(message(0), message(1, length = 1_000_000)), maxChars = 1_000)
        assertEquals(listOf("m1"), kept.map { it.id })
    }

    @Test
    fun `a message survives the trip to disk and back`() {
        val original = ChatUiMessage(
            id = "a1",
            role = "assistant",
            content = "<p>answer</p>",
            costInfo = CostInfo(120, 480, 600, 0.00312),
            model = "grok-4.7",
            usedWebSearch = true,
            citations = listOf("https://example.com/a")
        )
        assertEquals(original, original.toStored().toUiMessage())
    }

    @Test
    fun `a message without cost comes back without cost`() {
        val original = ChatUiMessage(id = "u1", role = "user", content = "hello")
        assertEquals(original, original.toStored().toUiMessage())
    }
}
