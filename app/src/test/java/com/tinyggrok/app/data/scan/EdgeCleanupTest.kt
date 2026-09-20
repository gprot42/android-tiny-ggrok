package com.tinyggrok.app.data.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeCleanupTest {

    private val w = 600
    private val h = 800
    private val white = -0x1
    private val desk = 0xFF3A3028.toInt()
    private val ink = 0xFF101010.toInt()

    /** band = 1.2% of the shorter side = 7 px here. */
    private val band = 7

    private fun page(paint: (IntArray) -> Unit): IntArray = IntArray(w * h) { white }.also(paint)

    private fun clean(px: IntArray): IntArray = px.copyOf().also { whitenEdgeSlivers(ArrayPixelGrid(w, h, it)) }

    @Test
    fun aWedgeOfDeskAlongOneSideIsWhitened() {
        // Widest at the top, tapering to nothing a third of the way down: what a straight
        // outline leaves against a slightly curled page edge.
        val px = page { p ->
            for (y in 0 until 260) {
                val width = 6 - y / 50
                for (x in 0 until maxOf(0, width)) p[y * w + x] = desk
            }
        }
        val out = clean(px)
        assertTrue("wedge should be gone", out.all { it == white })
    }

    @Test
    fun aUniformRimOnAllFourSidesIsWhitened() {
        val px = page { p ->
            for (y in 0 until h) for (x in 0 until w) {
                if (x < 3 || y < 3 || x >= w - 3 || y >= h - 3) p[y * w + x] = desk
            }
        }
        assertTrue(clean(px).all { it == white })
    }

    @Test
    fun printInsideTheMarginsIsNeverTouched() {
        val px = page { p ->
            for (y in 100 until 700 step 20) for (x in 60 until 540) p[y * w + x] = ink
            // A ruled line starting only 3 px from the edge, but not joined to it.
            for (x in 3 until 300) p[400 * w + x] = ink
        }
        assertArrayEquals(px, clean(px))
    }

    @Test
    fun contentThatReallyRunsToTheEdgeLosesOnlyTheBand() {
        val px = page { p -> for (x in 0 until 300) p[400 * w + x] = ink } // a rule touching the left edge
        val out = clean(px)
        for (x in 0 until band) assertEquals("x=$x", white, out[400 * w + x])
        for (x in band until 300) assertEquals("x=$x", ink, out[400 * w + x])
    }

    @Test
    fun aFullBleedDarkSideIsTheDocumentsOwnAndIsLeftAlone() {
        // A cover or photo running off the left edge: dark right through the band, most of the way down.
        val px = page { p -> for (y in 100 until 700) for (x in 0 until 120) p[y * w + x] = ink }
        assertArrayEquals(px, clean(px))
    }

    @Test
    fun oneDarkSideDoesNotStopTheOthersBeingCleaned() {
        val px = page { p ->
            for (y in 100 until 700) for (x in 0 until 120) p[y * w + x] = ink   // genuine, left
            for (x in 0 until w) for (y in 0 until 3) p[y * w + x] = desk        // rim, top
        }
        val out = clean(px)
        for (x in 200 until 400) assertEquals(white, out[1 * w + x])             // top rim cleaned
        assertEquals(ink, out[400 * w + 10])                                     // left content kept
    }

    @Test
    fun aTinyImageIsLeftAlone() {
        val tiny = IntArray(10 * 10) { desk }
        val copy = tiny.copyOf()
        whitenEdgeSlivers(ArrayPixelGrid(10, 10, copy))
        assertArrayEquals(tiny, copy)
    }
}
