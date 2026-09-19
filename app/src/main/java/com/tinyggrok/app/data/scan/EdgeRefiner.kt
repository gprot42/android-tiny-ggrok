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

/** Weakest brightness step (0..1) accepted as a paper edge. */
private const val MIN_EDGE_CONTRAST = 0.05f

/** A step counts as the boundary if it is at least this share of the scanline's sharpest. */
private const val SIGNIFICANT_STEP_SHARE = 0.25f

/** A refined corner may not wander further than this from the rough one. */
private const val MAX_CORNER_SHIFT_FRACTION = 0.08f

/** A refined side may not turn further than this from the rough one (degrees). */
private const val MAX_EDGE_TURN_DEGREES = 12.0

private const val MIN_SAMPLES_FOR_FIT = 8

/**
 * Tighten rough corners onto the real paper edges.
 *
 * A vision model localises the page reliably but only to within a percent or two, and
 * that residual error is exactly what shows up as a tilted scan. The model is weak
 * where classic image analysis is strong, so each side is handled locally: walk along
 * the rough edge, find the strongest brightness step across it at each station, fit a
 * straight line through those steps with outliers dropped, and take the intersections
 * of neighbouring lines as the corners. Fitting a line through dozens of samples is
 * what makes the result straight rather than merely close.
 *
 * Every stage falls back to the rough value rather than guess: a side with too few
 * confident samples keeps its rough line, a corner that would jump too far stays put.
 */
internal fun refineCorners(image: LumaImage, rough: DocumentCorners): DocumentCorners {
    val w = image.width
    val h = image.height
    if (w < 16 || h < 16) return rough

    val img = image.blurred()
    val diag = hypot(w.toFloat(), h.toFloat())
    val radius = max(6f, SEARCH_RADIUS_FRACTION * diag)

    val corners = rough.toList().map { Vec(it.x * (w - 1), it.y * (h - 1)) }
    val fitted = (0 until 4).map { i -> fitEdge(img, corners[i], corners[(i + 1) % 4], radius) }
    // Nothing to go on (blank or hopelessly soft image): hand back exactly what came in.
    if (fitted.all { it == null }) return rough
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
    return if (isPlausibleQuad(result)) result else rough
}

private fun Vec.toNorm(w: Int, h: Int) = NormPoint(
    (x / (w - 1)).coerceIn(0f, 1f),
    (y / (h - 1)).coerceIn(0f, 1f)
)

/** Find the true edge near the rough side a→b, or null when the evidence is thin. */
private fun fitEdge(img: LumaImage, a: Vec, b: Vec, radius: Float): Line? {
    val span = b - a
    if (span.length() < 8f) return null
    val dir = span.normalized()
    val normal = Vec(-dir.y, dir.x)

    val offsets = ArrayList<Float>(SAMPLES_PER_EDGE)
    val stations = ArrayList<Vec>(SAMPLES_PER_EDGE)
    for (k in 0 until SAMPLES_PER_EDGE) {
        val t = EDGE_MARGIN + (1f - 2f * EDGE_MARGIN) * k / (SAMPLES_PER_EDGE - 1)
        val station = a + span * t
        val offset = strongestStep(img, station, dir, normal, radius) ?: continue
        offsets += offset
        stations += station
    }
    if (offsets.size < MIN_SAMPLES_FOR_FIT) return null

    // Drop stations that locked onto something else (text, a shadow, the table edge):
    // anything far from the median offset, measured against the typical spread.
    val median = offsets.sorted()[offsets.size / 2]
    val mad = offsets.map { abs(it - median) }.sorted()[offsets.size / 2]
    val tolerance = max(2.5f, 3f * mad)
    val points = offsets.indices
        .filter { abs(offsets[it] - median) <= tolerance }
        .map { stations[it] + normal * offsets[it] }
    if (points.size < MIN_SAMPLES_FOR_FIT) return null

    val line = leastSquaresLine(points) ?: return null

    // A fit that swings away from the rough side has latched onto a different feature.
    val cos = abs(line.dir.dot(dir)).coerceIn(0f, 1f)
    if (Math.toDegrees(acos(cos).toDouble()) > MAX_EDGE_TURN_DEGREES) return null
    return line
}

/**
 * Offset along [normal] (pixels, relative to [station]) of the paper boundary, or null
 * if nothing on this scanline is sharp enough to be paper against desk.
 *
 * The boundary is taken to be the *outermost* significant step, not the strongest one.
 * Print is often higher contrast than paper-on-desk, and a heading or ruled line that
 * runs parallel to the page edge would otherwise outvote it at most stations. The page
 * encloses its content, so walking inward from outside meets the boundary first.
 * [normal] points into the page (corners are clockwise), so negative offsets are outside.
 */
private fun strongestStep(
    img: LumaImage,
    station: Vec,
    dir: Vec,
    normal: Vec,
    radius: Float
): Float? {
    val r = radius.toInt()
    val profile = FloatArray(2 * r + 1)
    var peak = 0f
    for (i in profile.indices) {
        val s = (i - r).toFloat()
        // Difference across the edge, averaged over three taps along it to beat noise.
        var contrast = 0f
        for (along in -2..2 step 2) {
            val p = station + dir * along.toFloat() + normal * s
            val ahead = p + normal * 1.5f
            val behind = p - normal * 1.5f
            contrast += abs(img.at(ahead.x, ahead.y) - img.at(behind.x, behind.y))
        }
        profile[i] = contrast / 3f
        if (profile[i] > peak) peak = profile[i]
    }
    if (peak < MIN_EDGE_CONTRAST) return null

    // First step from the outside that is a meaningful share of the sharpest one here.
    val threshold = max(MIN_EDGE_CONTRAST, SIGNIFICANT_STEP_SHARE * peak)
    var i = 0
    while (i < profile.size && profile[i] < threshold) i++
    if (i >= profile.size) return null
    // Climb to the top of that step rather than stopping on its leading slope.
    while (i + 1 < profile.size && profile[i + 1] > profile[i]) i++

    // Sub-pixel position from a parabola through the peak and its neighbours.
    var offset = (i - r).toFloat()
    if (i in 1 until profile.size - 1) {
        val a = profile[i - 1]
        val b = profile[i]
        val c = profile[i + 1]
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
