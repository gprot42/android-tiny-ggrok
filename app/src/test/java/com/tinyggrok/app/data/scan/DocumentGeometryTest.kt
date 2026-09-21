package com.tinyggrok.app.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentGeometryTest {

    @Test
    fun parsesGridCoordinates() {
        val c = parseDocumentCorners(
            """{"found":true,"tl":[100,120],"tr":[900,100],"br":[920,880],"bl":[80,900]}"""
        )
        assertNotNull(c)
        assertEquals(0.1f, c!!.tl.x, 1e-4f)
        assertEquals(0.12f, c.tl.y, 1e-4f)
        assertEquals(0.92f, c.br.x, 1e-4f)
    }

    @Test
    fun toleratesProseCodeFencesAndNestedCorners() {
        val reply = """
            Here are the corners:
            ```json
            {"found": true, "corners": {"tl": {"x": 0.1, "y": 0.1}, "tr": {"x": 0.9, "y": 0.1},
             "br": {"x": 0.9, "y": 0.9}, "bl": {"x": 0.1, "y": 0.9}}}
            ```
        """.trimIndent()
        val c = parseDocumentCorners(reply)
        assertNotNull(c)
        assertEquals(0.9f, c!!.tr.x, 1e-4f)
    }

    @Test
    fun mislabelledCornersAreReordered() {
        // Model swapped every label; geometry, not labels, decides the order.
        val c = parseDocumentCorners(
            """{"tl":[900,900],"tr":[100,900],"br":[100,100],"bl":[900,100]}"""
        )!!
        assertEquals(0.1f, c.tl.x, 1e-4f)
        assertEquals(0.1f, c.tl.y, 1e-4f)
        assertEquals(0.9f, c.br.x, 1e-4f)
        assertEquals(0.9f, c.tr.x, 1e-4f)
        assertEquals(0.1f, c.tr.y, 1e-4f)
    }

    @Test
    fun outOfFrameValuesAreClamped() {
        val c = parseDocumentCorners(
            """{"tl":[-40,50],"tr":[1100,60],"br":[1050,990],"bl":[10,1200]}"""
        )!!
        assertEquals(0f, c.tl.x, 1e-4f)
        assertEquals(1f, c.tr.x, 1e-4f)
        assertEquals(1f, c.bl.y, 1e-4f)
    }

    @Test
    fun rejectsNotFoundGarbageAndImplausibleQuads() {
        assertNull(parseDocumentCorners("""{"found":false}"""))
        assertNull(parseDocumentCorners("I could not find a document."))
        assertNull(parseDocumentCorners("""{"tl":[1,2],"tr":[3,4]}"""))
        // A sliver far too small to be a page.
        assertNull(parseDocumentCorners("""{"tl":[500,500],"tr":[520,500],"br":[520,520],"bl":[500,520]}"""))
        // Three corners in a line: degenerate.
        assertNull(parseDocumentCorners("""{"tl":[0,0],"tr":[500,0],"br":[1000,0],"bl":[0,1000]}"""))
    }

    @Test
    fun plausibilityRequiresConvexity() {
        assertTrue(isPlausibleQuad(DocumentCorners.DEFAULT))
        val bowTie = DocumentCorners(
            NormPoint(0.1f, 0.1f), NormPoint(0.9f, 0.9f),
            NormPoint(0.9f, 0.1f), NormPoint(0.1f, 0.9f)
        )
        assertFalse(isPlausibleQuad(bowTie))
    }

    @Test
    fun areaOfFullFrameIsOne() {
        val full = DocumentCorners(
            NormPoint(0f, 0f), NormPoint(1f, 0f), NormPoint(1f, 1f), NormPoint(0f, 1f)
        )
        assertEquals(1f, quadArea(full), 1e-5f)
    }

    @Test
    fun flattenedSizeUsesLongerEdgesAndCaps() {
        val full = DocumentCorners(
            NormPoint(0f, 0f), NormPoint(1f, 0f), NormPoint(1f, 1f), NormPoint(0f, 1f)
        )
        assertEquals(4000 to 3000, flattenedSize(full, 4000, 3000, maxSide = 5000))
        // Capped on the long side, aspect preserved.
        assertEquals(2000 to 1500, flattenedSize(full, 4000, 3000, maxSide = 2000))

        // Trapezoid: bottom edge is wider than the top; the wider one wins.
        val trapezoid = DocumentCorners(
            NormPoint(0.25f, 0f), NormPoint(0.75f, 0f), NormPoint(1f, 1f), NormPoint(0f, 1f)
        )
        val (w, _) = flattenedSize(trapezoid, 1000, 1000, maxSide = 5000)
        assertEquals(1000, w)
    }

    // --- proportions of a page photographed at an angle ------------------------------------

    /**
     * Corners of an A4 sheet as a pinhole camera sees it: the phone tilted [tilt] degrees
     * back from straight down and turned [yaw] degrees sideways, its focal length [focal]
     * times the photo's long side. The sheet fills about two thirds of a 3000 x 4000 photo.
     */
    private fun photographedA4(tilt: Double, yaw: Double = 0.0, focal: Double = 0.72): DocumentCorners {
        val pw = 3000.0
        val ph = 4000.0
        val f = focal * ph
        val halfW = 105.0
        val halfH = 148.5
        val distance = f * (2 * halfH) / (0.66 * ph)
        val t = Math.toRadians(tilt)
        val y = Math.toRadians(yaw)
        fun project(u: Double, v: Double): NormPoint {
            // Turn about the vertical axis, then tip about the horizontal one.
            val x1 = u * Math.cos(y)
            val z1 = -u * Math.sin(y)
            val y2 = v * Math.cos(t) - z1 * Math.sin(t)
            val z2 = v * Math.sin(t) + z1 * Math.cos(t)
            val z = distance - z2
            return NormPoint(((f * x1 / z + pw / 2) / pw).toFloat(), ((f * y2 / z + ph / 2) / ph).toFloat())
        }
        return DocumentCorners(project(-halfW, -halfH), project(halfW, -halfH), project(halfW, halfH), project(-halfW, halfH))
    }

    private fun sideLengthRatio(c: DocumentCorners): Float {
        fun d(a: NormPoint, b: NormPoint) = Math.hypot((a.x - b.x) * 3000.0, (a.y - b.y) * 4000.0)
        return (maxOf(d(c.tl, c.bl), d(c.tr, c.br)) / maxOf(d(c.tl, c.tr), d(c.bl, c.br))).toFloat()
    }

    @Test
    fun `a page photographed at an angle keeps its proportions`() {
        for (tilt in listOf(0.0, 12.0, 25.0, 35.0)) for (yaw in listOf(0.0, 10.0)) {
            val corners = photographedA4(tilt, yaw)
            val ratio = pageProportions(corners, 3000, 4000)!!
            assertEquals("tilt $tilt yaw $yaw", 1.414f, ratio, 0.012f)
            val (w, h) = flattenedSize(corners, 3000, 4000, maxSide = 100_000)
            assertEquals("tilt $tilt yaw $yaw, as flattened", 1.414f, h.toFloat() / w, 0.012f)
        }
        // The fault this replaces: side lengths alone make a tilted page squat.
        assertTrue(sideLengthRatio(photographedA4(25.0)) < 1.33f)
    }

    @Test
    fun `the focal length is assumed, and across phone cameras that costs little`() {
        // 24 mm and 28 mm equivalent, the ends of the range for a phone's main camera.
        for (focal in listOf(0.69, 0.81)) {
            val ratio = pageProportions(photographedA4(tilt = 25.0, focal = focal), 3000, 4000)!!
            assertEquals("focal $focal", 1.414f, ratio, 0.035f)
        }
        // From straight above it costs nothing at all.
        assertEquals(1.414f, pageProportions(photographedA4(tilt = 0.0, focal = 0.5), 3000, 4000)!!, 0.005f)
    }

    @Test
    fun `flattening never throws away detail to get the proportions right`() {
        val corners = photographedA4(30.0)
        val (w, h) = flattenedSize(corners, 3000, 4000, maxSide = 100_000)
        fun d(a: NormPoint, b: NormPoint) = Math.hypot((a.x - b.x) * 3000.0, (a.y - b.y) * 4000.0)
        assertTrue(w >= maxOf(d(corners.tl, corners.tr), d(corners.bl, corners.br)).toInt())
        assertTrue(h >= maxOf(d(corners.tl, corners.bl), d(corners.tr, corners.br)).toInt())
    }

    @Test
    fun `an outline that implies an absurd tilt falls back to its side lengths`() {
        // A wild trapezoid: the top a tenth as wide as the bottom.
        val wild = DocumentCorners(NormPoint(0.45f, 0.1f), NormPoint(0.55f, 0.1f), NormPoint(1f, 0.9f), NormPoint(0f, 0.9f))
        val (w, h) = flattenedSize(wild, 1000, 1000, maxSide = 100_000)
        assertEquals(1000, w)
        assertEquals(sideLengthRatio1000(wild), h.toFloat() / w, 0.01f)
    }

    private fun sideLengthRatio1000(c: DocumentCorners): Float {
        fun d(a: NormPoint, b: NormPoint) = Math.hypot((a.x - b.x) * 1000.0, (a.y - b.y) * 1000.0)
        return (maxOf(d(c.tl, c.bl), d(c.tr, c.br)) / maxOf(d(c.tl, c.tr), d(c.bl, c.br))).toFloat()
    }

    private fun assertPoint(expected: NormPoint, actual: NormPoint) {
        assertEquals(expected.x, actual.x, 1e-6f)
        assertEquals(expected.y, actual.y, 1e-6f)
    }

    @Test
    fun aClockwiseQuarterTurnSendsTopLeftToTopRight() {
        val topLeft = NormPoint(0f, 0f)
        assertPoint(NormPoint(1f, 0f), topLeft.turnedClockwise(90))
        assertPoint(NormPoint(1f, 1f), topLeft.turnedClockwise(180))
        assertPoint(NormPoint(0f, 1f), topLeft.turnedClockwise(270))
        assertPoint(topLeft, topLeft.turnedClockwise(0))
        assertPoint(topLeft, topLeft.turnedClockwise(360))
    }

    @Test
    fun anOffCentrePointTurnsAsTheImageDoes() {
        // Near the left edge, a quarter of the way down. After a clockwise quarter turn
        // the left edge has become the top, so the point is near the top, and what was a
        // quarter of the way down is now a quarter of the way in from the right.
        assertPoint(NormPoint(0.75f, 0.1f), NormPoint(0.1f, 0.25f).turnedClockwise(90))
    }

    @Test
    fun turningBackUndoesTurningForEveryRotation() {
        val p = NormPoint(0.13f, 0.71f)
        for (degrees in listOf(0, 90, 180, 270, 450, -90)) {
            assertPoint(p, p.turnedClockwise(degrees).beforeTurningClockwise(degrees))
            assertPoint(p, p.beforeTurningClockwise(degrees).turnedClockwise(degrees))
        }
    }

    @Test
    fun turningKeepsCornersClockwise() {
        // The warp maps corners in order onto a rectangle; if a turn flipped their sense
        // the page would come out mirrored.
        for (degrees in listOf(90, 180, 270)) {
            val turned = DocumentCorners.DEFAULT.toList().map { it.beforeTurningClockwise(degrees) }
            var twiceArea = 0f
            for (i in 0 until 4) {
                val a = turned[i]
                val b = turned[(i + 1) % 4]
                twiceArea += a.x * b.y - b.x * a.y
            }
            assertTrue("rotation $degrees reversed the corner order", twiceArea > 0f)
        }
    }

    @Test
    fun insetMovesEverySideByTheSameNumberOfPixels() {
        val box = DocumentCorners(
            NormPoint(0.1f, 0.2f), NormPoint(0.9f, 0.2f), NormPoint(0.9f, 0.8f), NormPoint(0.1f, 0.8f)
        )
        val inner = insetQuad(box, width = 1000f, height = 2000f, insetPx = 10f)
        // 10 px is 0.010 of a 1000 px width but 0.005 of a 2000 px height.
        assertPoint(NormPoint(0.11f, 0.205f), inner.tl)
        assertPoint(NormPoint(0.89f, 0.795f), inner.br)
    }

    @Test
    fun insetOfATiltedQuadStaysParallelAndInside() {
        val tilted = DocumentCorners(
            NormPoint(0.12f, 0.10f), NormPoint(0.88f, 0.16f), NormPoint(0.92f, 0.90f), NormPoint(0.08f, 0.84f)
        )
        val w = 1500f
        val h = 2000f
        val inner = insetQuad(tilted, w, h, insetPx = 12f)
        val outer = tilted.toList()
        val moved = inner.toList()
        for (i in 0 until 4) {
            // Each new corner lies exactly 12 px inside both of the old sides that met there.
            for (side in listOf(i, (i + 3) % 4)) {
                val a = outer[side]
                val b = outer[(side + 1) % 4]
                val len = kotlin.math.hypot((b.x - a.x) * w, (b.y - a.y) * h)
                val dist = ((b.x - a.x) * w * (moved[i].y - a.y) * h - (b.y - a.y) * h * (moved[i].x - a.x) * w) / len
                assertEquals("corner $i from side $side", 12f, dist, 0.05f)
            }
        }
        assertTrue(isPlausibleQuad(inner))
        assertTrue(quadArea(inner) < quadArea(tilted))
    }

    @Test
    fun anInsetThatWouldCollapseTheQuadIsIgnored() {
        val small = DocumentCorners(
            NormPoint(0.40f, 0.40f), NormPoint(0.60f, 0.40f), NormPoint(0.60f, 0.60f), NormPoint(0.40f, 0.60f)
        )
        assertEquals(small, insetQuad(small, 100f, 100f, insetPx = 40f))
        assertEquals(small, insetQuad(small, 100f, 100f, insetPx = 0f))
    }

    @Test
    fun withCornerReplacesOnlyThatCorner() {
        val moved = DocumentCorners.DEFAULT.withCorner(2, NormPoint(0.5f, 0.5f))
        assertEquals(NormPoint(0.5f, 0.5f), moved.br)
        assertEquals(DocumentCorners.DEFAULT.tl, moved.tl)
        assertEquals(DocumentCorners.DEFAULT.bl, moved.bl)
    }
}
