package com.tinyggrok.app.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TextSkewTest {

    private val w = 800
    private val h = 1100

    /**
     * A page of "print": lines of word-like blocks, turned clockwise by [degrees] about
     * the centre, under lighting that falls off to one side.
     */
    private fun printedPage(degrees: Float, lines: Boolean = true, seed: Int = 9): LumaImage {
        val rnd = Random(seed)
        val a = Math.toRadians(degrees.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        // Word boundaries per line, so that lines are ragged like real text.
        val gaps = Array(200) { BooleanArray(64) { rnd.nextFloat() < 0.18f } }
        val data = FloatArray(w * h) { i ->
            val x = i % w - w / 2f
            val y = i / w - h / 2f
            // Into the text's own axes.
            val u = x * c + y * s
            val v = -x * s + y * c
            val light = 0.95f - 0.25f * (i % w) / w
            var value = light
            if (lines && abs(u) < w * 0.40f && abs(v) < h * 0.42f) {
                val line = ((v + h) / 18f).toInt()
                val within = (v + h) - line * 18f
                val word = ((u + w) / 13f).toInt()
                if (within in 4f..11f && !gaps[line % 200][word % 64]) value = light * 0.25f
            }
            (value + (rnd.nextFloat() - 0.5f) * 0.04f).coerceIn(0f, 1f)
        }
        return LumaImage(w, h, data)
    }

    private fun sideAngle(a: NormPoint, b: NormPoint): Float =
        Math.toDegrees(atan2(((b.y - a.y) * (h - 1)).toDouble(), ((b.x - a.x) * (w - 1)).toDouble())).toFloat()

    @Test
    fun measuresClockwiseSlantAsPositive() {
        for (truth in listOf(-1.5f, -0.4f, 0f, 0.75f, 2f)) {
            val estimate = estimateTextSkew(printedPage(truth), 0.1f, 0.9f)
            assertNotNull("print at $truth degrees", estimate)
            assertEquals("print at $truth degrees", truth, estimate!!.degrees, 0.11f)
            assertTrue("confidence ${estimate.confidence} at $truth", estimate.confidence > 0.35f)
        }
    }

    @Test
    fun unevenLightingDoesNotReadAsSlant() {
        // The fixture is lit from one side; level print must still measure level.
        assertEquals(0f, estimateTextSkew(printedPage(0f), 0.1f, 0.9f)!!.degrees, 0.06f)
    }

    @Test
    fun aPageWithoutPrintGivesNoConfidentAnswer() {
        val estimate = estimateTextSkew(printedPage(0f, lines = false), 0.1f, 0.9f)
        assertTrue(estimate == null || estimate.confidence < 0.35f)
    }

    @Test
    fun anOutlineSquareToThePaperButNotToThePrintIsTurnedToThePrint() {
        // Printers rarely lay text square to the sheet. The outline here is perfectly
        // level (as if fitted to perfect paper edges) while the print slopes by a degree.
        val page = printedPage(1f)
        val level = DocumentCorners(
            NormPoint(0.04f, 0.04f), NormPoint(0.96f, 0.04f), NormPoint(0.96f, 0.96f), NormPoint(0.04f, 0.96f)
        )
        val fixed = straightenByText(page, level)
        assertEquals("top side", 1f, sideAngle(fixed.tl, fixed.tr), 0.15f)
        assertEquals("bottom side", 1f, sideAngle(fixed.bl, fixed.br), 0.15f)

        // The proof that matters: flattened with the corrected outline, the print is level.
        val flat = flattenLuma(page, fixed, 700, 960)
        assertEquals(0f, estimateTextSkew(flat, 0.08f, 0.45f)!!.degrees, 0.12f)
        assertEquals(0f, estimateTextSkew(flat, 0.55f, 0.92f)!!.degrees, 0.12f)
    }

    @Test
    fun aSideFittedToTheWrongEdgeIsCorrectedWithoutDisturbingTheOther() {
        // The reported case: the bottom side ran along a plastic sleeve instead of the
        // paper, so the slant grew down the page. Top side right, bottom side 1.2 degrees out.
        val page = printedPage(0f)
        val drop = kotlin.math.tan(Math.toRadians(1.2)).toFloat() * 0.92f * (w - 1) / (h - 1) / 2f
        val wrongBottom = DocumentCorners(
            NormPoint(0.04f, 0.04f), NormPoint(0.96f, 0.04f),
            NormPoint(0.96f, 0.96f - drop), NormPoint(0.04f, 0.96f + drop)
        )
        assertEquals("fixture: bottom side is out", -1.2f, sideAngle(wrongBottom.bl, wrongBottom.br), 0.05f)

        val fixed = straightenByText(page, wrongBottom)
        assertEquals("bottom side", 0f, sideAngle(fixed.bl, fixed.br), 0.2f)
        assertEquals("top side stays", 0f, sideAngle(fixed.tl, fixed.tr), 0.2f)
    }

    @Test
    fun levelPrintLeavesTheOutlineExactlyAsItWas() {
        val level = DocumentCorners(
            NormPoint(0.04f, 0.04f), NormPoint(0.96f, 0.04f), NormPoint(0.96f, 0.96f), NormPoint(0.04f, 0.96f)
        )
        assertEquals(level, straightenByText(printedPage(0f), level))
    }

    @Test
    fun aPageWithoutPrintLeavesTheOutlineExactlyAsItWas() {
        val tilted = DocumentCorners(
            NormPoint(0.05f, 0.06f), NormPoint(0.95f, 0.04f), NormPoint(0.96f, 0.95f), NormPoint(0.04f, 0.97f)
        )
        assertEquals(tilted, straightenByText(printedPage(0f, lines = false), tilted))
    }
}
