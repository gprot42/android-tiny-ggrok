package com.tinyggrok.app.data.scan

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Grayscale image as plain floats (0..1) so the edge maths has no Android types in it
 * and can be unit tested on the JVM against synthetic pages.
 */
internal class LumaImage(val width: Int, val height: Int, val data: FloatArray) {
    init {
        require(data.size == width * height) { "luma buffer does not match dimensions" }
    }

    /** Bilinear sample, clamped at the borders. */
    fun at(x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val top = data[y0 * width + x0] * (1 - fx) + data[y0 * width + x1] * fx
        val bottom = data[y1 * width + x0] * (1 - fx) + data[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    /**
     * Median over a (2r+1) square. Unlike a blur, this removes anything covering less
     * than half of the window outright instead of smearing it into its surroundings:
     * print, plank seams, marble veins and carpet speckle all vanish, while the paper
     * and the surface keep their own brightness right up to the boundary between them.
     */
    fun medianFiltered(r: Int): LumaImage {
        val out = FloatArray(data.size)
        val window = FloatArray((2 * r + 1) * (2 * r + 1))
        for (y in 0 until height) {
            for (x in 0 until width) {
                var n = 0
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -r..r) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        window[n++] = data[yy * width + xx]
                    }
                }
                window.sort(0, n)
                out[y * width + x] = window[n / 2]
            }
        }
        return LumaImage(width, height, out)
    }

    /** 3x3 box blur: takes the sting out of sensor noise and paper texture. */
    fun blurred(): LumaImage {
        val out = FloatArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= width) continue
                        sum += data[yy * width + xx]
                        n++
                    }
                }
                out[y * width + x] = sum / n
            }
        }
        return LumaImage(width, height, out)
    }
}

private data class Vec(val x: Float, val y: Float) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(k: Float) = Vec(x * k, y * k)
    fun length() = hypot(x, y)
    fun normalized(): Vec = length().let { if (it < 1e-6f) this else Vec(x / it, y / it) }
    fun dot(o: Vec) = x * o.x + y * o.y
}

/** A line as a point on it plus a unit direction. */
private data class Line(val point: Vec, val dir: Vec)

/** Samples taken along each side, away from the corners where two edges interfere. */
private const val SAMPLES_PER_EDGE = 40
private const val EDGE_MARGIN = 0.12f

/** How far from the rough edge to look, as a fraction of the image diagonal. */
private const val SEARCH_RADIUS_FRACTION = 0.045f

/** Depth of the two regions compared across a candidate edge, as a fraction of the diagonal. */
private const val REGION_DEPTH_FRACTION = 0.012f

/**
 * Smallest difference in typical brightness (0..1) between the two regions that counts
 * as paper-to-surface. Low, because white paper on white marble differs by about 0.05;
 * safe to keep low only because the statistic is a median (see [probeSide]) and because
 * a side must also fit one tight straight line before it is believed.
 */
private const val MIN_REGION_CONTRAST = 0.035f

/** Refinement corrects an estimate; it must not replace it. Area may change by at most this. */
private const val MAX_AREA_CHANGE = 0.15f

/** A refined corner may not wander further than this from the rough one. */
private const val MAX_CORNER_SHIFT_FRACTION = 0.08f

/** A refined side may not turn further than this from the rough one (degrees). */
private const val MAX_EDGE_TURN_DEGREES = 12.0

/** A side needs this share of its stations to agree before it is believed. */
private const val MIN_INLIER_SHARE = 0.4f
private const val MIN_SAMPLES_FOR_FIT = 8

/** Largest scatter of stations about the fitted side, as a fraction of the diagonal. */
private const val MAX_FIT_RMS_FRACTION = 0.004f

/**
 * One probe across a rough side. [steps] is the robust measure (difference of medians
 * over deep regions) that says *which* boundary is the paper's. [fine] is a sharp measure
 * (difference of means over shallow regions) that says *exactly where* it is.
 */
private class Station(val origin: Vec, val steps: FloatArray, val fine: FloatArray)

/**
 * Corners after refinement, and which sides were confirmed on real paper edges. Side i
 * runs from corner i to corner i+1: top, right, bottom, left.
 */
internal class Refinement(val corners: DocumentCorners, val confirmed: BooleanArray) {
    val confirmedSides: Int get() = confirmed.count { it }
}

/**
 * How far outside the photo a corner may lie, as a fraction of its size. A page that
 * fills the frame has corners the camera never saw; where two real edges would meet just
 * off the photo, clamping the corner to the border would bend the page.
 */
internal const val OUT_OF_FRAME_ALLOWANCE = 0.35f

/** Rows of pixels either side of a position used to pinpoint it once identified. */
private const val FINE_DEPTH = 3

/**
 * Tighten rough corners onto the real paper edges.
 *
 * A vision model localises the page reliably but only to within a percent or two, and
 * that residual error is exactly what shows up as a tilted scan. So each side is handled
 * locally: probe across the rough edge at many stations, find where the paper begins at
 * each, fit a straight line through those points with outliers dropped, and take the
 * intersections of neighbouring lines as the corners. Fitting a line through dozens of
 * samples is what makes the result straight rather than merely close.
 *
 * "Where the paper begins" is judged by comparing the *typical (median) brightness of
 * two regions* either side of a candidate position. Two earlier versions got this wrong
 * in instructive ways. Sharpest local step: carpet, wood grain and print are full of
 * those, and on a speckled carpet the outline drifted outward onto the texture. Mean
 * brightness of the regions: robust to texture, but print drags a mean down, so on white
 * marble (where paper and surface barely differ) the block of text became the strongest
 * boundary and the scan was cropped to it. A median ignores whatever covers less than
 * half of a region, which is true of print, seams, veins and speckle alike, so the only
 * thing left that can move it is a change of the surface itself.
 *
 * Every stage falls back to the rough value rather than guess: a side whose stations do
 * not agree on one straight line keeps its rough line, a corner that would jump too far
 * stays put, and an image with nothing to go on is returned untouched.
 */
internal fun refineCorners(image: LumaImage, rough: DocumentCorners): DocumentCorners =
    refineCornersDetailed(image, rough).corners

/**
 * As [refineCorners], also reporting how many sides were confirmed. A proposed outline
 * whose sides cannot be confirmed is not lying on paper edges, whatever proposed it.
 */
internal fun refineCornersDetailed(image: LumaImage, rough: DocumentCorners): Refinement {
    val unchanged = Refinement(rough, BooleanArray(4))
    val w = image.width
    val h = image.height
    if (w < 16 || h < 16) return unchanged

    val diag = hypot(w.toFloat(), h.toFloat())
    val radius = max(6f, SEARCH_RADIUS_FRACTION * diag).toInt()
    val depth = max(4f, REGION_DEPTH_FRACTION * diag).toInt()

    val corners = rough.toList().map { Vec(it.x * (w - 1), it.y * (h - 1)) }
    val sides = (0 until 4).map { i -> probeSide(image, corners[i], corners[(i + 1) % 4], radius, depth) }

    // Is the page brighter or darker than its surroundings? Decided once for the whole
    // quad, weighted by how decisive each station is, so that texture and print (which
    // step both ways, weakly) cannot outvote the boundary (which steps one way, strongly).
    var vote = 0f
    for (side in sides) {
        for (station in side?.stations.orEmpty()) {
            val strongest = station.steps.maxByOrNull { abs(it) } ?: continue
            if (abs(strongest) >= MIN_REGION_CONTRAST) vote += strongest
        }
    }
    if (vote == 0f) return unchanged
    val polarity = if (vote > 0f) 1f else -1f

    val maxRms = max(1.5f, MAX_FIT_RMS_FRACTION * diag)
    val fitted = sides.map { side -> side?.let { fitSide(it, polarity, radius, maxRms) } }
    // Nothing to go on (blank image, or no page within reach): hand back what came in.
    if (fitted.all { it == null }) return unchanged
    val lines = (0 until 4).map { i ->
        fitted[i] ?: Line(corners[i], (corners[(i + 1) % 4] - corners[i]).normalized())
    }

    // Corner i is where the side arriving at it meets the side leaving it.
    val refined = (0 until 4).map { i ->
        val incoming = lines[(i + 3) % 4]
        val outgoing = lines[i]
        val hit = intersect(incoming, outgoing)
        val roughCorner = corners[i]
        if (hit == null || (hit - roughCorner).length() > MAX_CORNER_SHIFT_FRACTION * diag) {
            roughCorner
        } else {
            hit
        }
    }

    val result = DocumentCorners(
        tl = refined[0].toNorm(w, h),
        tr = refined[1].toNorm(w, h),
        br = refined[2].toNorm(w, h),
        bl = refined[3].toNorm(w, h)
    )
    if (!isPlausibleQuad(result)) return unchanged
    // The rough corners come from something that saw the whole page (a vision model, the
    // on-device finder, or the user's own hand). A result that disagrees with them about
    // how big the page is has found a different rectangle, not a better fit of this one.
    val areaBefore = quadArea(rough)
    if (areaBefore > 0f && abs(quadArea(result) - areaBefore) / areaBefore > MAX_AREA_CHANGE) return unchanged
    return Refinement(result, BooleanArray(4) { fitted[it] != null })
}

private fun Vec.toNorm(w: Int, h: Int) = NormPoint(
    (x / (w - 1)).coerceIn(-OUT_OF_FRAME_ALLOWANCE, 1f + OUT_OF_FRAME_ALLOWANCE),
    (y / (h - 1)).coerceIn(-OUT_OF_FRAME_ALLOWANCE, 1f + OUT_OF_FRAME_ALLOWANCE)
)

private class SideProbe(val dir: Vec, val normal: Vec, val stations: List<Station>)

/**
 * Probe across the rough side a→b. For every station and every offset along the normal,
 * record median brightness just inside minus median brightness just outside. The normal
 * points into the page (corners run clockwise), so a bright page on a dark surface
 * gives positive steps.
 */
private fun probeSide(img: LumaImage, a: Vec, b: Vec, radius: Int, depth: Int): SideProbe? {
    val span = b - a
    if (span.length() < 8f) return null
    val dir = span.normalized()
    val normal = Vec(-dir.y, dir.x)
    val reach = radius + depth

    val stations = (0 until SAMPLES_PER_EDGE).map { k ->
        val t = EDGE_MARGIN + (1f - 2f * EDGE_MARGIN) * k / (SAMPLES_PER_EDGE - 1)
        val origin = a + span * t

        // Brightness samples: for each offset across the side, a short run along it.
        val taps = 5
        val samples = FloatArray((2 * reach + 1) * taps)
        for (i in 0..2 * reach) {
            val across = (i - reach).toFloat()
            for (k in 0 until taps) {
                val along = (k - taps / 2) * 2f
                val p = origin + dir * along + normal * across
                samples[i * taps + k] = img.at(p.x, p.y)
            }
        }
        val window = FloatArray(depth * taps)
        fun medianOf(firstRow: Int): Float {
            System.arraycopy(samples, firstRow * taps, window, 0, window.size)
            window.sort()
            return window[window.size / 2]
        }

        fun meanOf(firstRow: Int, rows: Int): Float {
            var sum = 0f
            for (i in firstRow * taps until (firstRow + rows) * taps) sum += samples[i]
            return sum / (rows * taps)
        }

        // steps[j] describes a boundary lying between offsets (j - radius - 1) and (j - radius).
        val steps = FloatArray(2 * radius + 1) { j ->
            val at = j + depth // row of the first "inside" sample
            medianOf(at) - medianOf(at - depth)
        }
        val fine = FloatArray(2 * radius + 1) { j ->
            val at = j + depth
            meanOf(at, FINE_DEPTH) - meanOf(at - FINE_DEPTH, FINE_DEPTH)
        }
        Station(origin, steps, fine)
    }
    return SideProbe(dir, normal, stations)
}

/** Fit the true side, or null when the stations do not agree on one straight line. */
private fun fitSide(side: SideProbe, polarity: Float, radius: Int, maxRms: Float): Line? {
    val points = ArrayList<Vec>(side.stations.size)
    val offsets = ArrayList<Float>(side.stations.size)
    for (station in side.stations) {
        val offset = boundaryOffset(station, polarity, radius) ?: continue
        offsets += offset
        points += station.origin + side.normal * offset
    }
    val needed = max(MIN_SAMPLES_FOR_FIT, (MIN_INLIER_SHARE * side.stations.size).toInt())
    if (offsets.size < needed) return null

    // Drop stations that locked onto something else (a shadow, the table edge, a fold):
    // anything far from the median offset, measured against the typical spread.
    val median = offsets.sorted()[offsets.size / 2]
    val mad = offsets.map { abs(it - median) }.sorted()[offsets.size / 2]
    val tolerance = max(2.5f, 3f * mad)
    var inliers = points.indices.filter { abs(offsets[it] - median) <= tolerance }.map { points[it] }
    if (inliers.size < needed) return null

    var line = leastSquaresLine(inliers) ?: return null

    // Refit on the stations that agree closely with the first fit. Wherever something
    // interrupts the edge (a finger, a paperclip, a dog-ear, a dark band of print running
    // into a dark desk) the stations there land a few pixels off: too near to be thrown
    // out above, yet bunched together, so they lever the line round. Clean stations agree
    // to a fraction of a pixel, which makes the two populations easy to tell apart.
    repeat(2) {
        val normal = Vec(-line.dir.y, line.dir.x)
        val residuals = inliers.map { abs((it - line.point).dot(normal)) }
        val typical = residuals.sorted()[residuals.size / 2]
        val limit = max(0.75f, 2.5f * typical)
        val kept = inliers.indices.filter { residuals[it] <= limit }.map { inliers[it] }
        if (kept.size < needed || kept.size == inliers.size) return@repeat
        inliers = kept
        line = leastSquaresLine(kept) ?: return null
    }

    // Stations scattered at random (texture, no real boundary in reach) still produce
    // *a* line. A real paper edge produces a tight one; insist on that.
    val lineNormal = Vec(-line.dir.y, line.dir.x)
    val rms = sqrt(inliers.sumOf { ((it - line.point).dot(lineNormal)).toDouble().let { d -> d * d } } / inliers.size)
    if (rms > maxRms) return null

    // A fit that swings away from the rough side has latched onto a different feature.
    val cos = abs(line.dir.dot(side.dir)).coerceIn(0f, 1f)
    if (Math.toDegrees(acos(cos).toDouble()) > MAX_EDGE_TURN_DEGREES) return null
    return line
}

/**
 * Offset (pixels along the inward normal) of the paper boundary at one station, or null
 * if no position there separates two regions of clearly different brightness.
 *
 * Two stages, because robust and precise pull in opposite directions. A median ignores
 * anything covering under half of its region, which is what makes it immune to print and
 * texture, but for the same reason it reads the same across a band as wide as the region
 * is deep: it identifies the boundary without locating it. So the median picks the band,
 * and within that band the sharp measure picks the pixel.
 */
private fun boundaryOffset(station: Station, polarity: Float, radius: Int): Float? {
    val steps = station.steps
    var best = -1
    var bestScore = 0f
    for (j in steps.indices) {
        val contrast = polarity * steps[j]
        if (contrast < MIN_REGION_CONTRAST) continue
        // Mild preference for positions near the rough side.
        val score = contrast * (1f - 0.25f * abs(j - radius) / radius)
        if (score > bestScore) {
            bestScore = score
            best = j
        }
    }
    if (best < 0) return null

    // A median flips once half its region has crossed the boundary, so its reading is
    // flat for the same distance either side of the true edge and falls away sharply
    // beyond. The middle of that flat stretch is therefore the edge, and unlike any
    // sharp measure it cannot be pulled by thin clutter nearby (a marble vein, a seam).
    // Half the best reading marks the ends: wood grain makes the top wobble by a
    // quarter, which a tighter level would mistake for the end of the stretch.
    val level = 0.5f * polarity * steps[best]
    var first = best
    var last = best
    while (first > 0 && polarity * steps[first - 1] >= level) first--
    while (last < steps.size - 1 && polarity * steps[last + 1] >= level) last++
    val centre = (first + last) / 2f

    // Then pinpoint within a few pixels of that centre: enough to absorb the small bias
    // that content printed right up to the paper's edge puts on the centre, too little to
    // reach a neighbouring feature. Inside that window take the *outermost* strong step,
    // not the strongest: a page's edge lies outside its own print by definition, and
    // full-bleed print is often higher contrast than paper against desk.
    val reachFine = max(2, (last - first) / 4)
    val lo = max(0, centre.toInt() - reachFine)
    val hi = min(steps.size - 1, centre.toInt() + reachFine + 1)

    val fine = station.fine
    var strongest = 0f
    for (j in lo..hi) strongest = max(strongest, polarity * fine[j])
    var at = lo
    while (at < hi && polarity * fine[at] < 0.6f * strongest) at++
    while (at < hi && polarity * fine[at + 1] > polarity * fine[at]) at++

    // Sub-pixel position from a parabola through the peak and its neighbours.
    var offset = (at - radius).toFloat() - 0.5f
    if (at in 1 until fine.size - 1) {
        val a = polarity * fine[at - 1]
        val b = polarity * fine[at]
        val c = polarity * fine[at + 1]
        val curvature = a - 2f * b + c
        if (abs(curvature) > 1e-6f) offset += (0.5f * (a - c) / curvature).coerceIn(-1f, 1f)
    }
    return offset
}

/** Total least squares: centroid plus the principal axis of the point cloud. */
private fun leastSquaresLine(points: List<Vec>): Line? {
    val n = points.size
    if (n < 2) return null
    val cx = points.sumOf { it.x.toDouble() } / n
    val cy = points.sumOf { it.y.toDouble() } / n
    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    for (p in points) {
        val dx = p.x - cx
        val dy = p.y - cy
        sxx += dx * dx
        sxy += dx * dy
        syy += dy * dy
    }
    // Principal eigenvector of the 2x2 covariance matrix.
    val trace = sxx + syy
    val det = sxx * syy - sxy * sxy
    val lambda = trace / 2 + sqrt(max(0.0, trace * trace / 4 - det))
    val dir = if (abs(sxy) > 1e-9) {
        Vec((lambda - syy).toFloat(), sxy.toFloat())
    } else if (sxx >= syy) {
        Vec(1f, 0f)
    } else {
        Vec(0f, 1f)
    }
    if (dir.length() < 1e-9f) return null
    return Line(Vec(cx.toFloat(), cy.toFloat()), dir.normalized())
}

private fun intersect(l1: Line, l2: Line): Vec? {
    val cross = l1.dir.x * l2.dir.y - l1.dir.y * l2.dir.x
    if (abs(cross) < 1e-4f) return null // near-parallel sides: no stable corner
    val d = l2.point - l1.point
    val t = (d.x * l2.dir.y - d.y * l2.dir.x) / cross
    return l1.point + l1.dir * t
}
