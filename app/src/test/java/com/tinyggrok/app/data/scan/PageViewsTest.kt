package com.tinyggrok.app.data.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageViewsTest {

    // --- what counts as paper -------------------------------------------------------------

    @Test
    fun `white paper keeps its brightness, even under warm light`() {
        assertTrue(paperness(240, 240, 238) > 0.9f)
        assertTrue("warm indoor light", paperness(232, 222, 200) > 0.7f)
    }

    @Test
    fun `greys read exactly as their brightness`() {
        for (v in listOf(0, 40, 128, 200, 255)) assertEquals(lumaOf(v, v, v), paperness(v, v, v), 1e-6f)
    }

    @Test
    fun `wood goes dark however brightly it is lit`() {
        assertTrue("orange varnished wood", paperness(198, 130, 60) < 0.1f)
        assertTrue("the same wood under a sheen", paperness(245, 185, 95) < 0.3f)
        assertTrue("pale beech", paperness(217, 190, 148) < 0.55f)
        assertEquals("never negative", 0f, paperness(255, 60, 0), 0f)
    }

    @Test
    fun `a colourless photo gives two identical views`() {
        val grey = IntArray(64) { i -> val v = i * 4; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val views = pageViewsOf(grey, 8, 8)
        assertArrayEquals(
            FloatArray(64) { views.luma.at(it % 8f, (it / 8).toFloat()) },
            FloatArray(64) { views.paper.at(it % 8f, (it / 8).toFloat()) },
            1e-6f
        )
    }

    // --- the reported scans: a sheet near the front edge of a wooden table ----------------

    private val gaps = listOf(0.012f, 0.02f, 0.035f)

    private fun assertOnThePaper(label: String, scene: ColourScene, found: DocumentCorners?) {
        assertNotNull("$label: page not found", found)
        val truth = scene.corners.toList()
        found!!.toList().forEachIndexed { i, p ->
            assertTrue(
                "$label: corner $i at (${p.x}, ${p.y}), paper's is at (${truth[i].x}, ${truth[i].y})",
                abs(p.x - truth[i].x) < 0.003f && abs(p.y - truth[i].y) < 0.003f
            )
        }
    }

    @Test
    fun `the page is found without the strip of table below it`() {
        for (gap in gaps) for (tilt in listOf(0f, 1f, -1.5f)) {
            val scene = pageNearTableEdge(gap, tilt)
            val views = pageViewsOf(scene.argb, scene.width, scene.height)
            assertOnThePaper("gap $gap tilt $tilt", scene, locatePage(views))
        }
    }

    /** A vision model's outline: the right page, corners loose by a percent or two. */
    private fun loosened(c: DocumentCorners, by: Float) = DocumentCorners(
        NormPoint(c.tl.x - by, c.tl.y - by), NormPoint(c.tr.x + by, c.tr.y - by),
        NormPoint(c.br.x + by, c.br.y + by), NormPoint(c.bl.x - by, c.bl.y + by)
    )

    @Test
    fun `an outline from Grok is tightened onto the paper, not the table's edge`() {
        for (gap in gaps) for (loose in listOf(0f, 0.012f, 0.025f)) {
            val scene = pageNearTableEdge(gap, 1f)
            val views = pageViewsOf(scene.argb, scene.width, scene.height)
            val rough = loosened(scene.corners, loose)
            assertOnThePaper("gap $gap loose $loose", scene, squareUp(views, rough) ?: refineCorners(views, rough))
            // Snap, from where a user would leave the handles.
            assertOnThePaper("snap, gap $gap loose $loose", scene, refineCorners(views, rough))
        }
    }

    /**
     * Guards the scene, not the app: in brightness alone the table's edge wins, even from
     * a perfect starting outline, which is the fault that was reported. Should brightness
     * alone ever learn to get this right, this test has nothing left to say and can go.
     */
    @Test
    fun `the scene reproduces the fault when only brightness is used`() {
        for (gap in gaps) {
            val scene = pageNearTableEdge(gap, 1f)
            val luma = pageViewsOf(scene.argb, scene.width, scene.height).luma
            val got = squareUp(luma, scene.corners) ?: refineCorners(luma, scene.corners)
            assertTrue("gap $gap", got.br.y - scene.corners.br.y > 0.8f * gap)
        }
    }

    // --- where colour says nothing, or misleads --------------------------------------------

    @Test
    fun `a pastel sheet on a grey desk is still found, by brightness`() {
        // Chosen so that the sheet is no more paper-like than the desk: brightness 0.91
        // less colourfulness 0.39 against a plain grey of 0.52.
        val scene = sheetOnDesk(sheet = intArrayOf(250, 240, 150), desk = intArrayOf(133, 133, 133))
        val views = pageViewsOf(scene.argb, scene.width, scene.height)
        assertNull("the paper view should have nothing to go on here", locatePage(views.paper))
        assertOnThePaper("pastel sheet", scene, locatePage(views))
    }

    @Test
    fun `white paper on pale wood, barely darker than the paper, is found`() {
        val scene = sheetOnDesk(sheet = intArrayOf(236, 234, 228), desk = intArrayOf(226, 204, 166))
        val views = pageViewsOf(scene.argb, scene.width, scene.height)
        assertOnThePaper("pale wood", scene, locatePage(views))
    }
}
