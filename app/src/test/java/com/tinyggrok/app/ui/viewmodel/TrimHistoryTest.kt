package com.tinyggrok.app.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimHistoryTest {

    private fun exchange(i: Int, replyChars: Int = 100): List<ChatUiMessage> = listOf(
        ChatUiMessage(role = "user", content = "q$i"),
        ChatUiMessage(role = "assistant", content = "a$i:" + "x".repeat(replyChars))
    )

    @Test
    fun keepsAtMostTenAssistantTurns() {
        val all = (1..15).flatMap { exchange(it) }
        val kept = trimHistory(all, maxChars = Int.MAX_VALUE)
        assertEquals(20, kept.size)
        assertEquals("q6", kept.first().content)
        assertTrue(kept.last().content.startsWith("a15"))
    }

    @Test
    fun dropsOldestUntilUnderCharBudget() {
        val all = (1..10).flatMap { exchange(it, replyChars = 1_000) }
        val kept = trimHistory(all, maxChars = 3_100)
        // Three ~1 KB exchanges fit; the fourth would not.
        assertEquals(6, kept.size)
        assertEquals("q8", kept.first().content)
    }

    @Test
    fun alwaysKeepsNewestExchangeEvenIfOverBudget() {
        val all = exchange(1, 5_000) + exchange(2, 5_000)
        val kept = trimHistory(all, maxChars = 10)
        assertEquals(listOf("q2"), kept.filter { it.role == "user" }.map { it.content })
        assertEquals(2, kept.size)
    }

    @Test
    fun emptyInputIsFine() {
        assertTrue(trimHistory(emptyList()).isEmpty())
    }
}
