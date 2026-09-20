package com.tinyggrok.app.data.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** The angle of printed lines, clockwise-positive in degrees, and how clearly it stood out. */
internal data class SkewEstimate(val degrees: Float, val confidence: Float)

/** Widest slant looked for. Beyond this the outline is simply wrong, not slightly off. */
private const val MAX_SKEW_DEGREES = 3f
private const val COARSE_STEP = 0.25f
private const val FINE_STEP = 0.05f

/** Vertical brightness change (0..1) that counts as the top or bottom of a line of print. */
private const val MIN_LINE_EDGE = 0.08f

/** How far the best angle must stand out from the typical one before it is believed. */
private const val MIN_SKEW_CONFIDENCE = 0.35f

/** Slant smaller than this is not worth correcting, and is within measurement noise. */
private const val NEGLIGIBLE_SKEW = 0.12f

/** Working width of the flattened page used for measuring. */
private const val MEASURE_WIDTH = 700

/**
 * Measure the slant of printed lines between [top] and [bottom] (fractions of the height).
 *
 * Lines of print have sharp tops and bottoms, i.e. strong *vertical* brightness changes
 * strung out along each line. Sum those changes along rows sheared by a trial angle: at
 * the true angle every line piles into a few rows and the profile is spiky; at any other
 * angle it smears. The spikiest shear wins. Working from brightness *changes* rather than
 * darkness makes it indifferent to uneven lighting, so it needs no clean-up first.
 */
internal fun estimateTextSkew(image: LumaImage, top: Float, bottom: Float): SkewEstimate? {
    val w = image.width
    val h = image.height
    val y0 = max(1, (top * h).toInt())
    val y1 = min(h - 1, (bottom * h).toInt())
    if (y1 - y0 < 24 || w < 48) return null

    // Sparse list of line edges: positions and strengths.
    val xs = ArrayList<Int>()
    val ys = ArrayList<Int>()
    val ws = ArrayList<Float>()
    val d = image.data
    for (y in y0 until y1) {
        for (x in 0 until w) {
            val g = abs(d[(y + 1) * w + x] - d[(y - 1) * w + x])
            if (g >= MIN_LINE_EDGE) { xs += x; ys += y; ws += g }
        }
    }
    if (xs.size < 400) return null // too little print to say anything

    val cx = w / 2f
    val span = (y1 - y0) + (w * tan(Math.toRadians(MAX_SKEW_DEGREES.toDouble())).toFloat()).toInt() + 4
    fun spikiness(degrees: Float): Double {
        val t = tan(Math.toRadians(degrees.toDouble())).toFloat()
        val profile = FloatArray(span)
        val offset = span / 2 - (y0 + y1) / 2
        for (i in xs.indices) {
            val row = (ys[i] - (xs[i] - cx) * t + offset + 0.5f).toInt()
            if (row in 0 until span) profile[row] += ws[i]
        }
        var sum = 0.0
        for (p in profile) sum += p.toDouble() * p
        return sum
    }

    val coarse = ArrayList<Pair<Float, Double>>()
    var a = -MAX_SKEW_DEGREES
    while (a <= MAX_SKEW_DEGREES + 1e-4f) { coarse += a to spikiness(a); a += COARSE_STEP }
    val bestCoarse = coarse.maxByOrNull { it.second }!!

    var best = bestCoarse
    a = bestCoarse.first - COARSE_STEP
    while (a <= bestCoarse.first + COARSE_STEP + 1e-4f) {
        val s = spikiness(a)
        if (s > best.second) best = a to s
        a += FINE_STEP
    }

    val typical = coarse.map { it.second }.sorted()[coarse.size / 2]
    val confidence = if (typical <= 0.0) 0f else (best.second / typical - 1.0).toFloat()
    return SkewEstimate(best.first, confidence)
}

/** A point inside (or just outside) a quad, by bilinear blend of its corners. */
private fun within(c: DocumentCorners, u: Float, v: Float): NormPoint {
    val topX = c.tl.x + (c.tr.x - c.tl.x) * u
    val topY = c.tl.y + (c.tr.y - c.tl.y) * u
    val botX = c.bl.x + (c.br.x - c.bl.x) * u
    val botY = c.bl.y + (c.br.y - c.bl.y) * u
    return NormPoint(topX + (botX - topX) * v, topY + (botY - topY) * v)
}

/** The page cut out of [source] and laid flat at [outW] x [outH], for measuring only. */
internal fun flattenLuma(source: LumaImage, corners: DocumentCorners, outW: Int, outH: Int): LumaImage {
    val out = FloatArray(outW * outH)
    val sw = (source.width - 1).toFloat()
    val sh = (source.height - 1).toFloat()
    for (y in 0 until outH) {
        val v = (y + 0.5f) / outH
        for (x in 0 until outW) {
            val p = within(corners, (x + 0.5f) / outW, v)
            out[y * outW + x] = source.at(p.x * sw, p.y * sh)
        }
    }
    return LumaImage(outW, outH, out)
}

/**
 * Correct an outline so that the *print* comes out level, not merely the paper.
 *
 * Paper edges are what the outline is fitted to, and they can mislead: a plastic sleeve
 * or a second sheet shows a straighter, stronger edge just beyond the real one, and
 * printers rarely lay text perfectly square to the sheet anyway. Reported from a real
 * scan whose text sloped +0.75 degrees near the top and +1.05 near the bottom although the
 * outline looked right. Lines of print are the ground truth for "straight", so they get
 * the final say: flatten with the current outline, measure the slant of the print in the
 * upper and lower parts of the page, and turn the top and bottom sides of the outline to
 * match. A slant that differs between top and bottom is a side fitted to the wrong edge,
 * and correcting the two sides separately removes that as well as a plain rotation.
 *
 * Does nothing when there is too little print to measure, when the measurement does not
 * stand out clearly, or when the slant is negligible. So a photograph or a drawing is
 * left exactly as the edges had it.
 */
internal fun straightenByText(source: LumaImage, corners: DocumentCorners): DocumentCorners {
    var current = corners
    repeat(2) {
        val (natW, natH) = flattenedSize(current, source.width, source.height, maxSide = 100_000)
        if (natW < 64 || natH < 64) return current
        val outW = MEASURE_WIDTH
        val outH = (MEASURE_WIDTH.toFloat() * natH / natW).toInt().coerceIn(200, 2400)
        val flat = flattenLuma(source, current, outW, outH)

        val upper = estimateTextSkew(flat, UPPER_TOP, UPPER_BOTTOM)?.takeIf { it.confidence >= MIN_SKEW_CONFIDENCE }
        val lower = estimateTextSkew(flat, LOWER_TOP, LOWER_BOTTOM)?.takeIf { it.confidence >= MIN_SKEW_CONFIDENCE }
        // With print in only one part of the page, assume the same slant throughout.
        val su = (upper ?: lower)?.degrees ?: return current
        val sl = (lower ?: upper)?.degrees ?: return current

        // Slant varies smoothly down the page; read it off at the top and bottom sides.
        val vu = (UPPER_TOP + UPPER_BOTTOM) / 2f
        val vl = (LOWER_TOP + LOWER_BOTTOM) / 2f
        val perV = (sl - su) / (vl - vu)
        val atTop = (su - perV * vu).coerceIn(-MAX_SKEW_DEGREES, MAX_SKEW_DEGREES)
        val atBottom = (su + perV * (1f - vu)).coerceIn(-MAX_SKEW_DEGREES, MAX_SKEW_DEGREES)
        if (max(abs(atTop), abs(atBottom)) < NEGLIGIBLE_SKEW) return current

        // Turn each side about its midpoint to follow the print, in the flattened page,
        // then carry the new end points back onto the photo.
        val riseTop = tan(Math.toRadians(atTop.toDouble())).toFloat() * (outW / 2f) / outH
        val riseBottom = tan(Math.toRadians(atBottom.toDouble())).toFloat() * (outW / 2f) / outH
        val next = DocumentCorners(
            tl = within(current, 0f, -riseTop),
            tr = within(current, 1f, +riseTop),
            br = within(current, 1f, 1f + riseBottom),
            bl = within(current, 0f, 1f - riseBottom)
        )
        if (!isPlausibleQuad(next)) return current
        current = next
    }
    return current
}

private const val UPPER_TOP = 0.08f
private const val UPPER_BOTTOM = 0.45f
private const val LOWER_TOP = 0.55f
private const val LOWER_BOTTOM = 0.92f
