package com.tinyggrok.app.data.scan

import kotlin.math.max
import kotlin.math.min

/** How far in from an edge a sliver may reach, as a fraction of the page's shorter side. */
private const val BAND_FRACTION = 0.012f

/** Below this brightness a pixel is "not paper" once the page has been enhanced to white. */
private const val NOT_PAPER = 0.80f

/**
 * If more than this share of an edge is dark all the way through the band, the darkness
 * is the document's own (a full-bleed photo, a cover, a black border) and is left alone.
 */
private const val GENUINE_CONTENT_SHARE = 0.40f

private const val WHITE = -0x1 // 0xFFFFFFFF

/**
 * Whiten thin slivers of desk left along the edges of an enhanced page.
 *
 * A straight outline cannot hug an edge that is slightly curled, or that has a plastic
 * sleeve showing just beyond it, so a wedge of background a few pixels wide survives at
 * one end of a side. Measured on a real scan: the top and bottom rims were gone within
 * four pixels, but the left edge was still 10% dark ten pixels in. Trimming far enough to
 * remove a wedge would cost real margin on every scan, so the trim stays small and what
 * remains is cleaned here instead.
 *
 * The rule is deliberately narrow. Working inward along each row or column from the very
 * edge, pixels are whitened only while they are darker than paper, and only within a thin
 * band. So nothing is touched unless it is joined to the edge of the scan: print, which
 * sits inside the margins, is never reached, and content that really does run to the
 * edge loses at most the band's depth. A side that is dark right through the band along
 * much of its length is the document's own and is skipped entirely.
 *
 * Only meaningful after enhancement, when paper is white; on an unenhanced page the fill
 * would not match the paper.
 */
internal fun whitenEdgeSlivers(grid: PixelGrid) {
    val w = grid.width
    val h = grid.height
    val band = max(2, (BAND_FRACTION * min(w, h)).toInt())
    if (w < band * 4 || h < band * 4) return

    // ---- top and bottom: a block of `band` rows each ----
    for (top in listOf(true, false)) {
        val y0 = if (top) 0 else h - band
        val block = IntArray(band * w)
        grid.readRows(y0, band, block)
        val depth = IntArray(w) { x ->
            var d = 0
            while (d < band && isNotPaper(block[(if (top) d else band - 1 - d) * w + x])) d++
            d
        }
        if (depth.count { it >= band } > GENUINE_CONTENT_SHARE * w) continue
        var changed = false
        for (x in 0 until w) {
            for (d in 0 until depth[x]) {
                block[(if (top) d else band - 1 - d) * w + x] = WHITE
                changed = true
            }
        }
        if (changed) grid.writeRows(y0, band, block)
    }

    // ---- left and right: the ends of every row ----
    for (left in listOf(true, false)) {
        // First pass measures, so that the decision to skip is made before anything is changed.
        val depth = IntArray(h)
        val row = IntArray(w)
        for (y in 0 until h) {
            grid.readRows(y, 1, row)
            var d = 0
            while (d < band && isNotPaper(row[if (left) d else w - 1 - d])) d++
            depth[y] = d
        }
        if (depth.count { it >= band } > GENUINE_CONTENT_SHARE * h) continue
        for (y in 0 until h) {
            if (depth[y] == 0) continue
            grid.readRows(y, 1, row)
            for (d in 0 until depth[y]) row[if (left) d else w - 1 - d] = WHITE
            grid.writeRows(y, 1, row)
        }
    }
}

private fun isNotPaper(argb: Int): Boolean {
    val luma = (0.299f * ((argb shr 16) and 0xFF) + 0.587f * ((argb shr 8) and 0xFF) + 0.114f * (argb and 0xFF)) / 255f
    return luma < NOT_PAPER
}
