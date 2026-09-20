package com.tinyggrok.app.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Scenes modelled on a real failure: an A4 sheet photographed on a speckled carpet,
 * with a shadow nearby. Smooth synthetic backgrounds hid that failure, so every scene
 * here is textured.
 */
class PageFinderTest {

    private val width = 640
    private val height = 360

    /** Tilted and perspective-skewed, off-centre, roughly the framing of the real photo. */
    private val truth = DocumentCorners(
        tl = NormPoint(0.235f, 0.135f),
        tr = NormPoint(0.762f, 0.110f),
        br = NormPoint(0.755f, 0.806f),
        bl = NormPoint(0.255f, 0.808f)
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

    /**
     * Carpet: brightness [surface] with coarse speckle (2 px blobs, like pile), a soft
     * dark shadow above the page, and a page of brightness [page] carrying print.
     */
    private fun carpetScene(
        page: Float? = 0.85f,
        surface: Float = 0.40f,
        speckle: Float = 0.45f,
        shape: DocumentCorners = truth,
        seed: Int = 11
    ): LumaImage {
        val rnd = Random(seed)
        val grainW = width / 2 + 1
        val grain = FloatArray(grainW * (height / 2 + 1)) { (rnd.nextFloat() - 0.5f) * speckle }
        val data = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = x / (width - 1f)
                val ny = y / (height - 1f)
                var v = surface + grain[(y / 2) * grainW + x / 2]
                // Shadow blob above the page, as cast by whoever holds the phone.
                val sx = (nx - 0.42f) / 0.16f
                val sy = (ny - 0.03f) / 0.09f
                if (sx * sx + sy * sy < 1f) v -= 0.18f
                if (page != null && inside(shape, nx, ny)) {
                    v = page + (rnd.nextFloat() - 0.5f) * 0.05f
                    // Print: dense lines running the length of the page.
                    if (x % 9 < 3 && ny > 0.2f && ny < 0.72f && nx > 0.3f && nx < 0.7f) v = 0.35f
                }
                data[y * width + x] = v.coerceIn(0f, 1f)
            }
        }
        return LumaImage(width, height, data)
    }

    private fun worstError(found: DocumentCorners, expected: DocumentCorners = truth): Float =
        found.toList().zip(expected.toList()).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }

    @Test
    fun findsAPageOnSpeckledCarpetWithoutAnyHint() {
        val scene = carpetScene()
        val found = findPage(scene)
        assertNotNull("page should be found", found)
        assertTrue("confidence ${found!!.confidence}", found.confidence > 0.8f)
        assertTrue("rough error ${worstError(found.corners)}", worstError(found.corners) < 0.02f)

        val refined = refineCorners(scene, found.corners)
        assertTrue("refined error ${worstError(refined)}", worstError(refined) < 0.006f)
    }

    @Test
    fun findsADarkPageOnALightTexturedSurface() {
        val scene = carpetScene(page = 0.18f, surface = 0.78f)
        val found = findPage(scene)
        assertNotNull(found)
        val refined = refineCorners(scene, found!!.corners)
        assertTrue("refined error ${worstError(refined)}", worstError(refined) < 0.008f)
    }

    @Test
    fun bareCarpetHasNoPage() {
        assertNull(locatePage(carpetScene(page = null)))
    }

    @Test
    fun paperBarelyDifferentFromItsSurfaceIsLeftToGrok() {
        // White sheet on a pale desk: not enough separation to trust a threshold.
        assertNull(locatePage(carpetScene(page = 0.80f, surface = 0.74f, speckle = 0.06f)))
    }

    @Test
    fun aBrightBlobThatIsNotFourSidedIsRejected() {
        val data = FloatArray(width * height) { i ->
            val x = (i % width - width / 2f) / (height * 0.35f)
            val y = (i / width - height / 2f) / (height * 0.35f)
            if (x * x + y * y < 1f) 0.9f else 0.3f
        }
        assertNull(locatePage(LumaImage(width, height, data)))
    }

    @Test
    fun downscalingAveragesAndKeepsProportions() {
        val image = LumaImage(8, 4, FloatArray(32) { if (it % 2 == 0) 0f else 1f })
        val small = image.downscaledTo(4)
        assertEquals(4, small.width)
        assertEquals(2, small.height)
        assertTrue(small.data.all { kotlin.math.abs(it - 0.5f) < 1e-6f })
    }

    // ---- regression: the failure reported from a real scan ----

    @Test
    fun snapNeverDriftsOntoCarpetTexture() {
        // The starting outline is nowhere near the page and the carpet is full of sharp
        // local steps. The old refiner marched outward onto them and announced success
        // with the outline glued to the photo's borders.
        val bare = carpetScene(page = null)
        assertEquals(DocumentCorners.DEFAULT, refineCorners(bare, DocumentCorners.DEFAULT))
    }

    @Test
    fun snapFromRoughCornersHoldsOnCarpet() {
        val rough = DocumentCorners(
            tl = NormPoint(truth.tl.x + 0.018f, truth.tl.y - 0.020f),
            tr = NormPoint(truth.tr.x - 0.020f, truth.tr.y + 0.017f),
            br = NormPoint(truth.br.x + 0.016f, truth.br.y + 0.019f),
            bl = NormPoint(truth.bl.x - 0.021f, truth.bl.y - 0.018f)
        )
        val refined = refineCorners(carpetScene(), rough)
        assertTrue("refined error ${worstError(refined)}", worstError(refined) < 0.006f)
    }
}
