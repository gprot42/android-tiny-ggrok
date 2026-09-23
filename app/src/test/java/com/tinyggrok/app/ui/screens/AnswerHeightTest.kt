package com.tinyggrok.app.ui.screens

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerHeightTest {

    /** What Modifier.height(h) builds when the width is left open, as in the chat list. */
    private fun heightConstraints(heightPx: Int) =
        Constraints(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = heightPx, maxHeight = heightPx)

    @Test
    fun `the heights from the phone's crashes cannot be laid out at all`() {
        // Reproduces the recorded crashes exactly: the same exception, the same numbers.
        for (h in listOf(316_076, 434_309)) {
            val error = assertThrows(IllegalArgumentException::class.java) { heightConstraints(h) }
            assertTrue(error.message, error.message!!.contains("height of $h"))
        }
    }

    @Test
    fun `every height an answer can be given can be laid out`() {
        for (h in listOf(0, 1, 42_903, 199_999, 200_000, 262_142, 316_076, 434_309, Int.MAX_VALUE)) {
            heightConstraints(answerViewHeight(h)) // throws if it cannot
        }
    }

    @Test
    fun `ordinary answers keep their exact height`() {
        assertEquals(42_903, answerViewHeight(42_903))
        assertEquals(0, answerViewHeight(-5))
        assertEquals(MAX_ANSWER_HEIGHT_PX, answerViewHeight(434_309))
    }
}
