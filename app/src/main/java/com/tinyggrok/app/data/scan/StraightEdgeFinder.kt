package com.tinyggrok.app.data.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Find a page by its four straight edges, whatever is printed on it.
 *
 * [findPage] looks for a region that is lighter (or darker, or less colourful) than its
 * surroundings, which is what a document is. A magazine cover is none of those: reported
 * from a real scan, a full-colour cover on a wooden desk, overhanging onto carpet, was no
 * lighter, darker or plainer than what it lay on, in any one number per pixel. The only
 * things that still said "page" were four long straight lines meeting at four corners.
 *
 * So: measure, everywhere, how much the typical colour changes across a horizontal and
 * across a vertical boundary ([stepMap]); pick out the long straight lines in each map;
 * and choose the four that make the most convincing sheet. Convincing is judged three
 * ways, each of which was needed on the scan that prompted this:
 *
 *  - every side is a step along most of its length. A busy cover is full of steps, so
 *    this alone says little, but it rules out lines that merely pass through clutter;
 *  - the sheet is as large as it can be, since a page's edge encloses everything printed
 *    on it. Without this the outline stopped at the headline, a bolder line than the
 *    cover's pale bottom edge against pale carpet;
 *  - its sides *stop at its corners*. The desk's front edge ran behind the cover and out
 *    both sides; lines of print end short of the page's edges or run into them. Only the
 *    sheet's own edges start and finish at each other.
 *
 * The outline found here is rough (it is worked out on a thumbnail) and unverified; see
 * [locatePageByEdges].
 */
internal fun findPageByEdges(colour: ColourImage): FoundPage? {
    val small = colour.downscaledTo(EDGE_FINDER_MAX_SIDE)
    val w = small.width
    val h = small.height
    if (w < 32 || h < 32) return null

    val planes = small.planes.map { it.data }
    // Near-horizontal lines are looked for directly; near-vertical ones in the transpose,
    // where they are near-horizontal too, so one routine serves both.
    val across = linesIn(stepMap(planes, w, h), w, h)
    val down = linesIn(stepMap(planes.map { transposed(it, w, h) }, h, w), h, w)
    if (across.size < 2 || down.size < 2) return null

    /** Where a near-horizontal line (y about x) meets a near-vertical one (x about y). */
    fun meet(a: EdgeLine, d: EdgeLine): FloatArray {
        val y = (a.centre + a.slope * (d.centre - d.slope * h / 2f - w / 2f)) / (1f - a.slope * d.slope)
        return floatArrayOf(d.centre + d.slope * (y - h / 2f), y)
    }

    var best: Array<FloatArray>? = null
    var bestScore = 0f
    for (ti in across.indices) for (bi in across.indices) {
        val top = across[ti]
        val bottom = across[bi]
        if (bottom.centre - top.centre < MIN_EXTENT * h) continue
        for (li in down.indices) for (ri in down.indices) {
            val left = down[li]
            val right = down[ri]
            if (right.centre - left.centre < MIN_EXTENT * w) continue
            val tl = meet(top, left)
            val tr = meet(top, right)
            val br = meet(bottom, right)
            val bl = meet(bottom, left)
            val quad = arrayOf(tl, tr, br, bl)
            if (quad.any { it[0] < 0f || it[0] > w - 1f || it[1] < 0f || it[1] > h - 1f }) continue
            var twiceArea = 0f
            for (i in 0 until 4) {
                val p = quad[i]
                val q = quad[(i + 1) % 4]
                twiceArea += p[0] * q[1] - q[0] * p[1]
            }
            val area = 0.5f * abs(twiceArea) / (w * h)
            if (area < MIN_EDGE_PAGE_AREA) continue

            val sides = arrayOf(
                top.between(tl[0], tr[0]), right.between(tr[1], br[1]),
                bottom.between(bl[0], br[0]), left.between(tl[1], bl[1])
            )
            if (sides.any { it[0] < MIN_SIDE_SUPPORT }) continue
            var score = area
            for (i in 0 until 4) {
                score *= sides[i][0]
                // Corner i is where side i-1 ends and side i begins. If either carries on
                // past it, the corner is a T, not the L that a sheet's corner is.
                val runsOn = max(sides[(i + 3) % 4][if (i == 0 || i == 3) 1 else 2], sides[i][if (i < 2) 1 else 2])
                score *= 1f - RUN_ON_PENALTY * runsOn
            }
            if (score > bestScore) {
                bestScore = score
                best = quad
            }
        }
    }
    val quad = best ?: return null
    if (bestScore < MIN_EDGE_SCORE) return null
    val corners = orderCorners(quad.map { NormPoint(it[0] / (w - 1f), it[1] / (h - 1f)) }) ?: return null
    return FoundPage(corners, bestScore)
}

/**
 * [findPageByEdges], checked where it matters: every side must then be confirmed as one
 * tight straight boundary at full working resolution, looking only a short way either
 * side of the proposal. A short reach because colour steps carry no sign (see
 * [refineCornersDetailed] for colour): a cover's masthead is a bolder straight line than
 * its own edge, and must stay out of reach rather than be argued with.
 */
internal fun locatePageByEdges(colour: ColourImage): DocumentCorners? {
    val rough = findPageByEdges(colour) ?: return null
    val refined = refineCornersDetailed(colour, rough.corners, EDGE_FINDER_REACH)
    return refined.corners.takeIf { refined.confirmedSides == 4 }
}

/** Long side of the thumbnail the search runs on. */
private const val EDGE_FINDER_MAX_SIDE = 256

/**
 * The two regions compared across a boundary: long and shallow. Long, so that print,
 * sequins, wood grain and carpet pile (all under half of any such strip) drop out of the
 * median; shallow, so that a slightly tilted edge still falls between the two.
 */
private const val STRIP_HALF_LENGTH = 5
private const val STRIP_DEPTH = 3

/** Sides may lean this far from the photo's axes (as a slope, about 15 degrees). */
private const val MAX_SLOPE = 0.27f
private const val SLOPE_STEPS = 41

/** Lines carried forward from each map. Generous: a busy cover crowds out its own edges. */
private const val LINES_KEPT = 30

/** A change of typical colour (0..1) below this is never a boundary. */
private const val MIN_COLOUR_STEP = 0.05f
private const val STEP_PERCENTILE = 0.70f

private const val MIN_LINE_SUPPORT = 0.2f
private const val MIN_SIDE_SUPPORT = 0.55f
private const val MIN_EXTENT = 0.25f
private const val MIN_EDGE_PAGE_AREA = 0.12f

/** How much of its score a sheet loses at a corner where a side runs straight on past it. */
private const val RUN_ON_PENALTY = 0.8f

/** Below this the best sheet is a coincidence of clutter, or a page only partly in view. */
private const val MIN_EDGE_SCORE = 0.1f

/** Reach of the verifying refinement, as a fraction of the diagonal. */
internal const val EDGE_FINDER_REACH = 0.015f

/** A near-horizontal line: row = centre + slope * (column - width / 2). */
private class EdgeLine(val centre: Float, val slope: Float, onStep: BooleanArray) {
    private val length = onStep.size
    private val cumulative = IntArray(length + 1).also { for (i in 0 until length) it[i + 1] = it[i] + if (onStep[i]) 1 else 0 }

    private fun share(from: Float, to: Float): Float {
        val a = from.roundToInt().coerceIn(0, length)
        val b = to.roundToInt().coerceIn(0, length)
        return if (b - a < 2) 0f else (cumulative[b] - cumulative[a]).toFloat() / (b - a)
    }

    /**
     * For a side running from [p] to [q] along this line: the share of it lying on a step
     * (corners excluded, where two edges interfere), and the same for the stretch just
     * before [p] and just after [q], which for a real side is bare surface.
     */
    fun between(p: Float, q: Float): FloatArray {
        val n = q - p
        val margin = 0.08f * n
        val beyond = max(6f, 0.12f * n)
        val gap = 3f
        return floatArrayOf(share(p + margin, q - margin), share(p - gap - beyond, p - gap), share(q + gap, q + gap + beyond))
    }
}

private fun ColourImage.downscaledTo(maxSide: Int) =
    ColourImage(r.downscaledTo(maxSide), g.downscaledTo(maxSide), b.downscaledTo(maxSide))

private fun transposed(plane: FloatArray, w: Int, h: Int): FloatArray =
    FloatArray(w * h) { i -> plane[(i % h) * w + i / h] }

/**
 * For every pixel, how far the typical colour of the strip just below it is from that of
 * the strip just above: a step map for horizontal boundaries. "Typical" is the median per
 * plane, for the reason given at [refineCorners].
 */
private fun stepMap(planes: List<FloatArray>, w: Int, h: Int): FloatArray {
    val d = STRIP_DEPTH
    val t = STRIP_HALF_LENGTH
    val window = FloatArray((2 * t + 1) * d)
    // typical[c][(row + d) * w + x]: median of plane c over rows row until row + d,
    // columns x - t..x + t, for row from -d (clamped at the borders).
    val typical = planes.map { plane ->
        FloatArray((h + d) * w).also { out ->
            for (row in -d until h) for (x in 0 until w) {
                var n = 0
                for (dy in 0 until d) {
                    val yy = (row + dy).coerceIn(0, h - 1)
                    for (dx in -t..t) window[n++] = plane[yy * w + (x + dx).coerceIn(0, w - 1)]
                }
                window.sort()
                out[(row + d) * w + x] = window[n / 2]
            }
        }
    }
    return FloatArray(w * h) { i ->
        val below = i + d * w // strip starting on this row
        val above = i         // strip ending just above it
        var sum = 0f
        for (c in typical) sum += (c[below] - c[above]).let { it * it }
        sqrt(sum / typical.size)
    }
}

/** The best-supported near-horizontal lines in a step map. */
private fun linesIn(steps: FloatArray, w: Int, h: Int): List<EdgeLine> {
    val sorted = steps.copyOf().also { it.sort() }
    val tau = max(MIN_COLOUR_STEP, sorted[((sorted.size - 1) * STEP_PERCENTILE).toInt()])

    // A median reads the same for as far either side of a boundary as its strip is deep,
    // so a step shows up as a ridge several rows thick. Keep the crest only (largest
    // within the strip's depth, and the middle row where that is a tie), then allow a row
    // either way for the coarseness of the thumbnail.
    val crest = BooleanArray(w * h)
    for (x in 0 until w) {
        var y = 0
        while (y < h) {
            fun isPeak(row: Int): Boolean {
                val v = steps[row * w + x]
                if (v < tau) return false
                for (k in 1..STRIP_DEPTH) {
                    if (row - k >= 0 && steps[(row - k) * w + x] > v) return false
                    if (row + k < h && steps[(row + k) * w + x] > v) return false
                }
                return true
            }
            if (!isPeak(y)) { y++; continue }
            var end = y
            while (end + 1 < h && isPeak(end + 1)) end++
            crest[((y + end) / 2) * w + x] = true
            y = end + 1
        }
    }
    val on = BooleanArray(w * h) { i ->
        crest[i] || (i >= w && crest[i - w]) || (i + w < w * h && crest[i + w])
    }

    val slopes = FloatArray(SLOPE_STEPS) { -MAX_SLOPE + 2f * MAX_SLOPE * it / (SLOPE_STEPS - 1) }
    val offsets = Array(SLOPE_STEPS) { si -> IntArray(w) { x -> (slopes[si] * (x - w / 2f)).roundToInt() } }
    val votes = Array(h) { IntArray(SLOPE_STEPS) }
    for (c in 0 until h) for (si in 0 until SLOPE_STEPS) {
        val off = offsets[si]
        var n = 0
        for (x in 0 until w) {
            val row = c + off[x]
            if (row in 0 until h && on[row * w + x]) n++
        }
        votes[c][si] = n
    }

    val found = ArrayList<EdgeLine>(LINES_KEPT)
    repeat(LINES_KEPT) {
        var bestC = -1
        var bestS = -1
        var most = (MIN_LINE_SUPPORT * w).toInt()
        for (c in 0 until h) for (si in 0 until SLOPE_STEPS) {
            if (votes[c][si] > most) {
                most = votes[c][si]
                bestC = c
                bestS = si
            }
        }
        if (bestC < 0) return@repeat
        val off = offsets[bestS]
        val onStep = BooleanArray(w) { x ->
            val row = bestC + off[x]
            row in 0 until h && on[row * w + x]
        }
        found += EdgeLine(bestC.toFloat(), slopes[bestS], onStep)
        for (c in max(0, bestC - 2)..min(h - 1, bestC + 2)) {
            for (si in max(0, bestS - 4)..min(SLOPE_STEPS - 1, bestS + 4)) votes[c][si] = 0
        }
    }
    return found.sortedBy { it.centre }
}
