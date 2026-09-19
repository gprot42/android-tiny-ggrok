package com.tinyggrok.app.data.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * A page as a phone really captures it: warm, unevenly lit paper; print that the camera
 * has softened so it never gets darker than mid-grey; a blue pen stroke; colour noise.
 */
class DocumentEnhancerTest {

    private val w = 300
    private val h = 900

    private val textRow = 120        // centre of a horizontal "line of print"
    private val blueRow = 300        // centre of a blue pen stroke
    private val blackTop = 600       // a large solid black block (photo / header)
    private val blackBottom = 760

    private fun page(seed: Int = 1): IntArray {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Lit from the right: left edge noticeably darker. Warm cast.
                val light = 0.62f + 0.30f * x / (w - 1f)
                var r = light * 1.00f
                var g = light * 0.95f
                var b = light * 0.90f
                // Soft grey print: a Gaussian-profile stroke that only reaches ~55% of paper.
                for (row in intArrayOf(textRow, textRow + 40, textRow + 80)) {
                    val d = (y - row).toFloat()
                    val ink = 0.45f * exp(-(d * d) / (2f * 2.2f * 2.2f))
                    if (x in 30 until 270) { r *= 1 - ink; g *= 1 - ink; b *= 1 - ink }
                }
                // Blue pen stroke, similarly soft.
                val d = (y - blueRow).toFloat()
                val pen = exp(-(d * d) / (2f * 2.5f * 2.5f))
                if (x in 30 until 270) { r *= 1 - 0.55f * pen; g *= 1 - 0.45f * pen; b *= 1 - 0.15f * pen }
                // Solid black block.
                if (y in blackTop until blackBottom && x in 60 until 240) { r = 0.06f; g = 0.06f; b = 0.06f }
                // Colour noise: independent per channel, as sensors produce in dim light.
                r += (rnd.nextFloat() - 0.5f) * 0.05f
                g += (rnd.nextFloat() - 0.5f) * 0.05f
                b += (rnd.nextFloat() - 0.5f) * 0.05f
                px[y * w + x] = argb(r, g, b)
            }
        }
        return px
    }

    private fun argb(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun r(p: Int) = ((p shr 16) and 0xFF) / 255f
    private fun g(p: Int) = ((p shr 8) and 0xFF) / 255f
    private fun b(p: Int) = (p and 0xFF) / 255f
    private fun luma(p: Int) = 0.299f * r(p) + 0.587f * g(p) + 0.114f * b(p)

    private fun enhanced(bands: Int = 1): IntArray {
        val px = page()
        DocumentEnhancer.enhance(ArrayPixelGrid(w, h, px), bands)
        return px
    }

    /** Mean over a small horizontal run, to read through the noise. */
    private fun mean(px: IntArray, y: Int, x0: Int, x1: Int, f: (Int) -> Float): Float {
        var s = 0f
        for (x in x0 until x1) s += f(px[y * w + x])
        return s / (x1 - x0)
    }

    @Test
    fun paperBecomesEvenlyWhiteAndNeutral() {
        val before = page()
        val after = enhanced()
        val y = 40 // bare paper
        val darkSide = mean(before, y, 10, 40, ::luma)
        val brightSide = mean(before, y, 260, 290, ::luma)
        assertTrue("fixture should be unevenly lit", brightSide - darkSide > 0.2f)

        for (x0 in intArrayOf(10, 140, 260)) {
            assertTrue("paper at x=$x0", mean(after, y, x0, x0 + 30, ::luma) > 0.97f)
            val cast = mean(after, y, x0, x0 + 30, ::r) - mean(after, y, x0, x0 + 30, ::b)
            assertTrue("cast at x=$x0 is $cast", abs(cast) < 0.03f)
        }
    }

    @Test
    fun softGreyPrintBecomesDarkOnBothSidesOfThePage() {
        val before = page()
        val after = enhanced()
        assertTrue("fixture ink should be weak", mean(before, textRow, 40, 100, ::luma) > 0.33f)
        // Same ink, dim side and bright side: equally dark once the light is flattened.
        val left = mean(after, textRow, 40, 100, ::luma)
        val right = mean(after, textRow, 200, 260, ::luma)
        assertTrue("left ink $left", left < 0.22f)
        assertTrue("right ink $right", right < 0.22f)
        assertTrue("ink should match across the page: $left vs $right", abs(left - right) < 0.08f)
    }

    @Test
    fun edgesGetSteeper() {
        fun steepest(px: IntArray): Float {
            var best = 0f
            for (y in textRow - 8 until textRow) {
                best = maxOf(best, abs(mean(px, y + 1, 100, 200, ::luma) - mean(px, y, 100, 200, ::luma)))
            }
            return best
        }
        val gain = steepest(enhanced()) / steepest(page())
        assertTrue("edge steepness gain $gain", gain > 2.5f)
    }

    @Test
    fun printStaysNeutralDespiteColourNoise() {
        val after = enhanced()
        // Per-pixel, not averaged: speckle is exactly what averaging would hide.
        var worst = 0f
        for (x in 40 until 260) {
            val p = after[textRow * w + x]
            worst = maxOf(worst, abs(r(p) - b(p)))
        }
        assertTrue("worst red/blue split on a letter stroke: $worst", worst < 0.12f)
    }

    @Test
    fun aBluePenStrokeStaysBlue() {
        val after = enhanced()
        val blueness = mean(after, blueRow, 60, 240, ::b) - mean(after, blueRow, 60, 240, ::r)
        assertTrue("blue minus red on the pen stroke: $blueness", blueness > 0.15f)
    }

    @Test
    fun aLargeBlackAreaIsNotBleached() {
        val after = enhanced()
        val inside = mean(after, (blackTop + blackBottom) / 2, 100, 200, ::luma)
        assertTrue("middle of a solid black block came out at $inside", inside < 0.12f)
        // And the paper right beside it is still white, not dragged grey by the block.
        assertTrue(mean(after, (blackTop + blackBottom) / 2, 5, 40, ::luma) > 0.95f)
    }

    @Test
    fun aSolidColourBlockStaysEven() {
        // A logo or coloured header. An uneven paper estimate inside it shows up as a
        // glowing centre, which is how this was first noticed on a device.
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 60 until 240 && y in 300 until 520) argb(0.12f, 0.24f, 0.86f) else argb(0.90f, 0.87f, 0.83f)
        }
        DocumentEnhancer.enhance(ArrayPixelGrid(w, h, px))
        val centre = mean(px, 410, 140, 160, ::luma)
        val nearEdge = mean(px, 410, 66, 86, ::luma)
        assertTrue("centre $centre vs near edge $nearEdge", abs(centre - nearEdge) < 0.03f)
        assertTrue("still blue", mean(px, 410, 140, 160, ::b) - mean(px, 410, 140, 160, ::r) > 0.5f)
    }

    @Test
    fun resultDoesNotDependOnHowTheWorkIsSplit() {
        // Bands are processed in place, each borrowing a few original rows from its
        // neighbours. Any mistake there shows up as a seam; identical output proves none.
        val whole = enhanced(bands = 1)
        assertArrayEquals(whole, enhanced(bands = 2))
        assertArrayEquals(whole, enhanced(bands = 4))

        val threaded = page()
        DocumentEnhancer.enhance(ArrayPixelGrid(w, h, threaded), bands = 4) { jobs ->
            jobs.map { Thread(it).apply { start() } }.forEach { it.join() }
        }
        assertArrayEquals(whole, threaded)
    }
}
