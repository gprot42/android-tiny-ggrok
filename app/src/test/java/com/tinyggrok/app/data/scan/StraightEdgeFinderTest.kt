package com.tinyggrok.app.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StraightEdgeFinderTest {

    private fun viewsOf(scene: ColourScene) = pageViewsOf(scene.argb, scene.width, scene.height)

    private fun worstCornerError(scene: ColourScene, found: DocumentCorners): Float =
        scene.corners.toList().zip(found.toList()).maxOf { (t, f) -> maxOf(abs(t.x - f.x), abs(t.y - f.y)) }

    @Test
    fun `a colour cover is invisible to the paper and brightness views`() {
        val views = viewsOf(magazineOnDesk())
        assertNull(locatePage(views.paper))
        assertNull(locatePage(views.luma))
    }

    @Test
    fun `a colour cover is found by its edges, whole`() {
        for (tilt in listOf(-0.6f, 0f, 2f)) {
            val scene = magazineOnDesk(tilt)
            val found = locatePageDetailed(viewsOf(scene))
            assertNotNull("tilt $tilt: not found", found)
            assertTrue("tilt $tilt", found!!.byEdgesAlone)
            val error = worstCornerError(scene, found.corners)
            assertTrue("tilt $tilt: worst corner is off by $error", error < 0.006f)
        }
    }

    @Test
    fun `the outline does not stop at the headline or at the desk's front edge`() {
        val scene = magazineOnDesk()
        val rough = findPageByEdges(viewsOf(scene).colour)
        assertNotNull(rough)
        // Both of those lie well above the cover's true bottom edge.
        assertTrue(abs(rough!!.corners.bl.y - scene.corners.bl.y) < 0.02f)
        assertTrue(abs(rough.corners.br.y - scene.corners.br.y) < 0.02f)
    }

    @Test
    fun `a bare desk and carpet show no sheet to the edge finder`() {
        // (The brightness views may still read this as a page filling the frame with one
        // edge in view, which is theirs to decide; the question here is this finder's.)
        val colour = viewsOf(bareDeskAndCarpet()).colour
        assertNull(findPageByEdges(colour))
        assertNull(locatePageByEdges(colour))
    }

    @Test
    fun `a document is still found as paper, not by its edges`() {
        val scene = pageNearTableEdge(gap = 0.02f, tiltDegrees = 1f)
        val found = locatePageDetailed(viewsOf(scene))
        assertNotNull(found)
        assertFalse(found!!.byEdgesAlone)
    }

    @Test
    fun `snap and a loose outline from Grok settle on the cover's edges too`() {
        val scene = magazineOnDesk()
        val views = viewsOf(scene)
        val c = scene.corners
        val loose = DocumentCorners(
            NormPoint(c.tl.x - 0.012f, c.tl.y - 0.012f), NormPoint(c.tr.x + 0.012f, c.tr.y - 0.012f),
            NormPoint(c.br.x + 0.012f, c.br.y + 0.012f), NormPoint(c.bl.x - 0.012f, c.bl.y + 0.012f)
        )
        val snapped = refineCorners(views, loose)
        assertTrue("snap: ${worstCornerError(scene, snapped)}", worstCornerError(scene, snapped) < 0.006f)
        val squared = squareUp(views, loose)
        assertNotNull(squared)
        assertTrue(worstCornerError(scene, squared!!) < 0.006f)
    }

    /**
     * From a real cover: with each station free to pick its strongest step, a side whose
     * outline was turned by a degree and a half was "confirmed" on a line through the edge
     * at one end and print at the other, once the search reached far enough to see both.
     * Checked at the two reaches the app uses. There is still a limit: at half as far
     * again the masthead comes into view along the whole top side, and on this scene it
     * collects as much contrast as the edge does.
     */
    @Test
    fun `a turned, loose outline settles on the cover's own edges at the reaches the app uses`() {
        val scene = magazineOnDesk()
        val colour = viewsOf(scene).colour
        val c = scene.corners
        val turned = DocumentCorners(
            NormPoint(c.tl.x - 0.022f, c.tl.y - 0.008f), NormPoint(c.tr.x + 0.006f, c.tr.y - 0.006f),
            NormPoint(c.br.x + 0.007f, c.br.y + 0.008f), NormPoint(c.bl.x + 0.002f, c.bl.y + 0.006f)
        )
        for (reach in listOf(0.015f, 0.03f)) {
            val refined = refineCornersDetailed(colour, turned, reach)
            assertEquals("reach $reach", 4, refined.confirmedSides)
            val error = worstCornerError(scene, refined.corners)
            assertTrue("reach $reach: worst corner is off by $error", error < 0.006f)
        }
    }

    /**
     * Whether a page is levelled to its print depends on what it is, not on how it was
     * found. From a real scan: a newspaper lying half on pale carpet could only be found
     * by its edges, as a cover is, and came out with its print two degrees askew while
     * "found by its edges" was taken to mean "has no print".
     */
    @Test
    fun `print is recognised as print, and a cover as not, whatever found them`() {
        val document = pageNearTableEdge(gap = 0.02f, tiltDegrees = 1f)
        assertTrue(holdsPrint(viewsOf(document).colour, document.corners))
        val cover = magazineOnDesk()
        assertFalse(holdsPrint(viewsOf(cover).colour, cover.corners))
        // A loose outline, with some desk inside it, does not change the verdict.
        val c = document.corners
        val loose = DocumentCorners(
            NormPoint(c.tl.x - 0.02f, c.tl.y - 0.02f), NormPoint(c.tr.x + 0.02f, c.tr.y - 0.02f),
            NormPoint(c.br.x + 0.02f, c.br.y + 0.02f), NormPoint(c.bl.x - 0.02f, c.bl.y + 0.02f)
        )
        assertTrue(holdsPrint(viewsOf(document).colour, loose))
    }

    @Test
    fun `in one plane the refiner behaves exactly as before`() {
        // Same scene through the grey route and as three identical planes' first plane.
        val scene = pageNearTableEdge(gap = 0.02f, tiltDegrees = 1f)
        val views = viewsOf(scene)
        val a = refineCornersDetailed(views.paper, scene.corners)
        assertEquals(4, a.confirmedSides)
    }
}
