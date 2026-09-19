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

    @Test
    fun withCornerReplacesOnlyThatCorner() {
        val moved = DocumentCorners.DEFAULT.withCorner(2, NormPoint(0.5f, 0.5f))
        assertEquals(NormPoint(0.5f, 0.5f), moved.br)
        assertEquals(DocumentCorners.DEFAULT.tl, moved.tl)
        assertEquals(DocumentCorners.DEFAULT.bl, moved.bl)
    }
}
