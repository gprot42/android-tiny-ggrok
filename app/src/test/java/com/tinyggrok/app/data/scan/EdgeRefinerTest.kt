package com.tinyggrok.app.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class EdgeRefinerTest {

    private val width = 480
    private val height = 360

    /** A tilted, perspective-skewed page: the realistic hard case. */
    private val truth = DocumentCorners(
        tl = NormPoint(0.18f, 0.14f),
        tr = NormPoint(0.83f, 0.20f),
        br = NormPoint(0.88f, 0.86f),
        bl = NormPoint(0.12f, 0.80f)
    )

    private fun inside(c: DocumentCorners, x: Float, y: Float): Boolean {
        val p = c.toList()
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Bright page on a darker desk, with optional printed lines and sensor noise. */
    private fun render(
        page: Float = 0.9f,
        desk: Float = 0.25f,
        textLines: Boolean = false,
        noise: Float = 0f
    ): LumaImage {
        val rnd = Random(7)
        val data = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = x / (width - 1f)
                val ny = y / (height - 1f)
                var v = if (inside(truth, nx, ny)) page else desk
                if (textLines && v == page && y % 14 < 3 && nx > 0.25f && nx < 0.75f) v = 0.2f
                if (noise > 0f) v += (rnd.nextFloat() - 0.5f) * noise
                data[y * width + x] = v.coerceIn(0f, 1f)
            }
        }
        return LumaImage(width, height, data)
    }

    /** Roughly what a vision model hands back: right page, corners a few percent off. */
    private val rough = DocumentCorners(
        tl = NormPoint(truth.tl.x + 0.020f, truth.tl.y - 0.018f),
        tr = NormPoint(truth.tr.x - 0.022f, truth.tr.y + 0.015f),
        br = NormPoint(truth.br.x + 0.017f, truth.br.y + 0.020f),
        bl = NormPoint(truth.bl.x - 0.019f, truth.bl.y - 0.021f)
    )

    private fun worstError(c: DocumentCorners): Float =
        c.toList().zip(truth.toList()).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }

    @Test
    fun snapsRoughCornersOntoThePage() {
        val before = worstError(rough)
        val refined = refineCorners(render(), rough)
        val after = worstError(refined)

        assertTrue("rough corners should start well off ($before)", before > 0.02f)
        // Under half a percent of the frame: below what reads as tilt in the output.
        assertTrue("refined error $after should be < 0.005", after < 0.005f)
    }

    @Test
    fun printedLinesInsideThePageDoNotPullTheEdges() {
        val refined = refineCorners(render(textLines = true), rough)
        assertTrue("error ${worstError(refined)}", worstError(refined) < 0.006f)
    }

    @Test
    fun survivesSensorNoise() {
        val refined = refineCorners(render(noise = 0.12f), rough)
        assertTrue("error ${worstError(refined)}", worstError(refined) < 0.008f)
    }

    @Test
    fun worksForDarkPageOnLightDesk() {
        val refined = refineCorners(render(page = 0.2f, desk = 0.85f), rough)
        assertTrue("error ${worstError(refined)}", worstError(refined) < 0.005f)
    }

    @Test
    fun featurelessImageLeavesCornersAlone() {
        val flat = LumaImage(width, height, FloatArray(width * height) { 0.5f })
        assertEquals(rough, refineCorners(flat, rough))
    }

    @Test
    fun lowContrastPageIsStillFound() {
        // Cream paper on a pale desk: a small step, but a consistent one.
        val refined = refineCorners(render(page = 0.80f, desk = 0.66f), rough)
        assertTrue("error ${worstError(refined)}", worstError(refined) < 0.006f)
    }

    @Test
    fun neverReturnsAnImplausibleQuad() {
        val refined = refineCorners(render(noise = 0.3f), rough)
        assertTrue(isPlausibleQuad(refined))
        assertTrue(refined.toList().all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(abs(quadArea(refined) - quadArea(truth)) < 0.1f)
    }
}
