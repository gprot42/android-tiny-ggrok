package com.tinyggrok.app.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pages that fill the frame, which is what a sharp scan needs and what the first page
 * finder could not handle: it assumed the photo's border was all background. Reported
 * from a real scan where the page ran off the left of the frame; the finder took the
 * strip of desk along the top to be "the page" and the whole photo came back unstraightened.
 */
class FrameFillingPageTest {

    private val w = 480
    private val h = 640

    /**
     * A sheet turned by [degrees], positioned so that [cx],[cy] is its centre and its
     * half-size is [halfW] x [halfH] pixels. Anything beyond the photo is simply not seen.
     */
    private fun scene(degrees: Float, cx: Float, cy: Float, halfW: Float, halfH: Float, seed: Int = 3): LumaImage {
        val rnd = Random(seed)
        val a = Math.toRadians(degrees.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val data = FloatArray(w * h) { i ->
            val x = i % w - cx
            val y = i / w - cy
            // Into the sheet's own axes.
            val u = x * c + y * s
            val v = -x * s + y * c
            val onPage = abs(u) <= halfW && abs(v) <= halfH
            val base = if (onPage) 0.88f else 0.22f
            var value = base + (rnd.nextFloat() - 0.5f) * 0.06f
            // Print, aligned with the sheet, well inside its margins.
            if (onPage && abs(u) < halfW * 0.75f && abs(v) < halfH * 0.7f && ((v + 1000f).toInt() % 11) < 3) value = 0.3f
            value.coerceIn(0f, 1f)
        }
        return LumaImage(w, h, data)
    }

    private fun angle(a: NormPoint, b: NormPoint): Float =
        Math.toDegrees(atan2(((b.y - a.y) * (h - 1)).toDouble(), ((b.x - a.x) * (w - 1)).toDouble())).toFloat()

    private fun assertAngle(expected: Float, actual: Float, what: String) {
        var d = abs(expected - actual) % 180f
        if (d > 90f) d = 180f - d
        assertTrue("$what: expected $expected, got $actual", d < 0.6f)
    }

    @Test
    fun aPageRunningOffTwoSidesIsFoundAndSquaredUpNotSheared() {
        // Top and right edges are in view; the sheet runs off the left and the bottom.
        val image = scene(degrees = 4f, cx = 190f, cy = 380f, halfW = 270f, halfH = 330f)
        val corners = locatePage(image)
        assertNotNull("a frame-filling page should still be found on-device", corners)
        corners!!

        assertAngle(4f, angle(corners.tl, corners.tr), "top edge")
        assertAngle(94f, angle(corners.tr, corners.br), "right edge")
        // The point of reconstructing the missing sides: they follow the sheet, not the
        // photo's border. Left on the border, the bottom would be at 0 degrees and the
        // warp would straighten the top of the page while leaving the bottom tilted.
        assertAngle(4f, angle(corners.bl, corners.br), "reconstructed bottom edge")
        assertAngle(94f, angle(corners.tl, corners.bl), "reconstructed left edge")

        // Nothing the camera saw of the page may be cut off: the photo's bottom-left
        // corner is on the sheet, so it must be inside the outline.
        assertTrue(SurfaceScenes.inside(corners, 0.01f, 0.99f))
        // And the outline is allowed to reach beyond the photo to do that.
        assertTrue(corners.toList().any { it.x < 0f || it.y > 1f })
    }

    @Test
    fun theStripOfDeskBesideAFrameFillingPageIsNotMistakenForThePage() {
        // Straight on, with only a band of desk along the top: the exact reported case.
        val image = scene(degrees = 0f, cx = 240f, cy = 420f, halfW = 400f, halfH = 330f)
        val corners = locatePage(image)
        assertNotNull(corners)
        // Top of the sheet is at 420 - 330 = 90 px, i.e. 0.14 of the height.
        assertEquals(90f / (h - 1), corners!!.tl.y, 0.01f)
        assertEquals(90f / (h - 1), corners.tr.y, 0.01f)
        assertTrue("outline should cover the page, not the strip", quadArea(corners) > 0.7f)
    }

    @Test
    fun aPhotoThatIsNothingButPaperHasNoOutlineToFind() {
        val image = scene(degrees = 0f, cx = 240f, cy = 320f, halfW = 900f, halfH = 900f)
        assertNull(locatePage(image))
    }

    @Test
    fun anOrdinaryPageInsideTheFrameIsUnaffected() {
        val image = scene(degrees = 3f, cx = 240f, cy = 320f, halfW = 150f, halfH = 210f)
        val corners = locatePage(image)
        assertNotNull(corners)
        assertAngle(3f, angle(corners!!.tl, corners.tr), "top edge")
        assertTrue(corners.toList().all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun brightClutterTouchingThePageDoesNotCostTheWholeOutline() {
        // Reported from a real scan: a cloth and the photographer's hand, both about as
        // bright as paper, touched the bottom of the page. They merged with it in the
        // brightness split, the proposed bottom side ran diagonally through them, and the
        // outline was discarded although the true edge was in plain view.
        val page = scene(degrees = 2f, cx = 240f, cy = 290f, halfW = 200f, halfH = 230f)
        val data = page.data.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                // A bright lump hanging off the bottom-right of the sheet, reaching the photo's edge.
                val dx = (x - 330f) / 190f
                val dy = (y - 600f) / 95f
                if (dx * dx + dy * dy < 1f && data[y * w + x] < 0.5f) data[y * w + x] = 0.80f
            }
        }
        val cluttered = LumaImage(w, h, data)

        val clean = locatePage(page)
        val found = locatePage(cluttered)
        assertNotNull("fixture: the clean page must be found", clean)
        assertNotNull("clutter touching one side must not lose the page", found)
        // Same outline as without the clutter, bottom side included.
        val worst = found!!.toList().zip(clean!!.toList()).maxOf { (a, b) ->
            kotlin.math.hypot(a.x - b.x, a.y - b.y)
        }
        assertTrue("outline moved by $worst because of the clutter", worst < 0.012f)
    }

    @Test
    fun missingSideFollowsItsOppositeAndKeepsAllVisiblePaper() {
        // Direct check of the reconstruction: top, right and bottom known; left unknown.
        val known = DocumentCorners(
            tl = NormPoint(0f, 0.20f), tr = NormPoint(0.90f, 0.10f),
            br = NormPoint(0.95f, 0.90f), bl = NormPoint(0f, 0.98f)
        )
        val done = completeOutOfFrameSides(known, booleanArrayOf(true, true, true, false), 1000, 1000)
        assertNotNull(done)
        assertAngle(angle(known.tr, known.br), angle(done!!.tl, done.bl), "left follows right")
        // Pushed out far enough to keep both of the old side's ends.
        assertTrue(SurfaceScenes.inside(done, 0.002f, 0.21f) && SurfaceScenes.inside(done, 0.002f, 0.97f))
    }
}
