package com.tinyggrok.app.data.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A page located without outside help, and how much to trust it (0..1). */
internal data class FoundPage(val corners: DocumentCorners, val confidence: Float)

/** Working size: small enough to average away texture and print, big enough for corners. */
private const val FINDER_MAX_SIDE = 220

/** Page and surroundings must differ in mean brightness by at least this much (0..1). */
private const val MIN_CLASS_SEPARATION = 0.12f

/** Share of the frame a page may plausibly cover. */
private const val MIN_PAGE_AREA = 0.05f
private const val MAX_PAGE_AREA = 0.985f

/** Share of the photo a page must cover to be believed on the strength of one real edge. */
private const val DOMINANT_PAGE_AREA = 0.5f

/** A side this close to the photo's border, as a fraction of its size, is "cut by the frame". */
private const val FRAME_EDGE_TOLERANCE = 0.02f

/** The page region must be this solid, and this close to a true quadrilateral. */
private const val MIN_SOLIDITY = 0.85f
private const val MIN_QUAD_FIT = 0.88f

/** Below this the finder's own confidence is too low to bother verifying. */
private const val MIN_FINDER_CONFIDENCE = 0.6f

/**
 * Locate and square up a page on-device, or return null to let the caller ask Grok.
 *
 * [findPage] proposes an outline from a brightness split, which a lighting gradient or a
 * cluttered scene can fool while still looking tidy. So the proposal is checked by
 * independent evidence: see [squareUp].
 */
internal fun locatePage(image: LumaImage): DocumentCorners? =
    findPageCandidates(image)
        .filter { it.confidence >= MIN_FINDER_CONFIDENCE }
        .mapNotNull { squareUpDetailed(image, it.corners) }
        // If both readings somehow verify, believe the one resting on more real edges.
        .maxWithOrNull(compareBy({ it.second }, { quadArea(it.first) }))
        ?.first

/**
 * Tighten a rough outline onto the paper's edges and decide whether to believe it.
 *
 * Every side must be accounted for: either confirmed on a real, straight paper edge by
 * [refineCornersDetailed], or lying along the photo's border because the page runs out
 * of frame there. At least one side must be real, and a page with only one real side must
 * dominate the photo. A shape that merely looks like a page (the bright half of an
 * unevenly lit desk) has a side that is neither confirmed nor on the border, and a bare
 * surface has no real side at all; both are rejected.
 *
 * Sides that are out of frame are then reconstructed rather than left on the photo's
 * border. The border is not a paper edge: using it as one makes the perspective warp
 * shear the page, straightening the top while leaving the bottom tilted. A sheet of paper
 * has parallel opposite sides, so a missing side is drawn parallel to its visible
 * opposite (or square to a visible neighbour), far enough out to keep everything the
 * camera saw. The corners this produces can lie a little outside the photo.
 */
internal fun squareUp(image: LumaImage, rough: DocumentCorners): DocumentCorners? =
    squareUpDetailed(image, rough)?.first

/** As [squareUp], with the number of sides confirmed on real edges. */
internal fun squareUpDetailed(image: LumaImage, rough: DocumentCorners): Pair<DocumentCorners, Int>? {
    val refined = refineCornersDetailed(image, rough)
    val corners = refined.corners.toList()
    val cutByFrame = BooleanArray(4) { i ->
        !refined.confirmed[i] && liesOnFrame(corners[i], corners[(i + 1) % 4])
    }
    for (i in 0 until 4) if (!refined.confirmed[i] && !cutByFrame[i]) return null
    val real = refined.confirmedSides
    if (real == 0) return null
    // One real edge is only believable for a page that dominates the photo; a small
    // region touching three borders with one straight side is more likely a table top.
    if (real == 1 && quadArea(refined.corners) < DOMINANT_PAGE_AREA) return null
    if (cutByFrame.none { it }) return refined.corners to real

    val completed = completeOutOfFrameSides(refined.corners, refined.confirmed, image.width, image.height)
    return (completed ?: refined.corners) to real
}

private fun liesOnFrame(a: NormPoint, b: NormPoint): Boolean {
    fun near(v1: Float, v2: Float, edge: Float) =
        kotlin.math.abs(v1 - edge) <= FRAME_EDGE_TOLERANCE && kotlin.math.abs(v2 - edge) <= FRAME_EDGE_TOLERANCE
    return near(a.x, b.x, 0f) || near(a.x, b.x, 1f) || near(a.y, b.y, 0f) || near(a.y, b.y, 1f)
}

/**
 * Replace each unconfirmed side with a line parallel to its confirmed opposite, or, when
 * the opposite is missing too, square to a confirmed neighbour. The line is pushed out
 * to whichever end of the old side is further from the page, so no visible paper is cut.
 * Works in pixels, because angles are not preserved in normalized coordinates.
 */
internal fun completeOutOfFrameSides(
    corners: DocumentCorners,
    confirmed: BooleanArray,
    width: Int,
    height: Int
): DocumentCorners? {
    val w = (width - 1).toFloat()
    val h = (height - 1).toFloat()
    val pts = corners.toList().map { floatArrayOf(it.x * w, it.y * h) }

    // Each side as a point and a unit direction.
    val origin = Array(4) { pts[it] }
    val dir = Array(4) { i ->
        val a = pts[i]
        val b = pts[(i + 1) % 4]
        val len = kotlin.math.hypot(b[0] - a[0], b[1] - a[1])
        if (len < 1e-3f) return null
        floatArrayOf((b[0] - a[0]) / len, (b[1] - a[1]) / len)
    }

    for (i in 0 until 4) {
        if (confirmed[i]) continue
        val opposite = (i + 2) % 4
        val neighbour = listOf((i + 1) % 4, (i + 3) % 4).firstOrNull { confirmed[it] }
        val newDir = when {
            // Opposite sides run in opposite senses round the quad.
            confirmed[opposite] -> floatArrayOf(-dir[opposite][0], -dir[opposite][1])
            neighbour != null -> {
                // Square to the neighbour, turned to keep this side's original sense.
                val n = dir[neighbour]
                val square = floatArrayOf(-n[1], n[0])
                val same = square[0] * dir[i][0] + square[1] * dir[i][1] >= 0f
                if (same) square else floatArrayOf(-square[0], -square[1])
            }
            else -> continue
        }
        // Outward normal of side i (corners run clockwise, so inward is (-dy, dx)).
        val outward = floatArrayOf(newDir[1], -newDir[0])
        val a = pts[i]
        val b = pts[(i + 1) % 4]
        val da = a[0] * outward[0] + a[1] * outward[1]
        val db = b[0] * outward[0] + b[1] * outward[1]
        origin[i] = if (da >= db) a else b
        dir[i] = newDir
    }

    fun meet(i: Int, j: Int): FloatArray? {
        val cross = dir[i][0] * dir[j][1] - dir[i][1] * dir[j][0]
        if (kotlin.math.abs(cross) < 1e-4f) return null
        val dx = origin[j][0] - origin[i][0]
        val dy = origin[j][1] - origin[i][1]
        val t = (dx * dir[j][1] - dy * dir[j][0]) / cross
        return floatArrayOf(origin[i][0] + dir[i][0] * t, origin[i][1] + dir[i][1] * t)
    }

    // Corner i is where side i-1 meets side i.
    val out = (0 until 4).map { i ->
        val p = meet((i + 3) % 4, i) ?: return null
        NormPoint(
            (p[0] / w).coerceIn(-OUT_OF_FRAME_ALLOWANCE, 1f + OUT_OF_FRAME_ALLOWANCE),
            (p[1] / h).coerceIn(-OUT_OF_FRAME_ALLOWANCE, 1f + OUT_OF_FRAME_ALLOWANCE)
        )
    }
    val result = DocumentCorners(out[0], out[1], out[2], out[3])
    return result.takeIf { isPlausibleQuad(it) }
}

/** Box-average down so that the longest side is at most [maxSide]. */
internal fun LumaImage.downscaledTo(maxSide: Int): LumaImage {
    val longest = max(width, height)
    if (longest <= maxSide) return this
    val factor = (longest + maxSide - 1) / maxSide
    val w = max(1, width / factor)
    val h = max(1, height / factor)
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var sum = 0f
            for (dy in 0 until factor) {
                val row = (y * factor + dy) * width
                for (dx in 0 until factor) sum += data[row + x * factor + dx]
            }
            out[y * w + x] = sum / (factor * factor)
        }
    }
    return LumaImage(w, h, out)
}

/**
 * Find a sheet of paper lying on a surface of clearly different brightness, entirely
 * on-device: no model call, so it is instant, free, private and works offline.
 *
 * This covers the everyday case (a light page on a desk, floor or carpet, or a dark one
 * on a light surface), which is also the case where waiting several seconds for a vision
 * model feels absurd. Anything it is not sure about returns null, and the caller asks
 * Grok instead: white paper on a white desk, a cluttered scene, a page cut by the frame.
 *
 * Method: shrink and median-filter until texture and print drop out, split the image into
 * two brightness classes (Otsu), take whichever class the frame's border is *not* made
 * of as "page", keep its largest connected region, and reduce that region's convex hull
 * to four corners. The corners are only pixel-accurate at this small size; callers pass
 * them through [refineCorners] to square them up at full resolution.
 */
internal fun findPage(image: LumaImage): FoundPage? = findPageCandidates(image).firstOrNull()

/**
 * Both readings of the brightness split, likelier first: the page is the bright class,
 * or the page is the dark class. Each must already look like a sheet (solid, four-sided,
 * a plausible size).
 *
 * Which class is the page is deliberately *not* decided here. Every up-front rule tried
 * failed somewhere: "the page is whatever the photo's border is not made of" breaks the
 * moment a page fills the frame and runs off a side, which is what a sharp scan needs
 * (a real scan came back unstraightened because the strip of desk along the top was taken
 * for the page); "the page is whatever is in the middle" turns a bare carpet into a
 * full-frame page. The edge verification in [squareUp] can tell them apart, because only
 * a real page is bounded by straight paper edges, so the decision is left to it.
 */
internal fun findPageCandidates(image: LumaImage): List<FoundPage> {
    // Median, not blur: blurring mixes print into the paper around it, and a page of
    // text then averages out to much the same grey as pale wood and disappears.
    val small = image.downscaledTo(FINDER_MAX_SIDE).medianFiltered(2)
    val w = small.width
    val h = small.height
    if (w < 24 || h < 24) return emptyList()
    val px = small.data

    // --- two brightness classes ---
    val threshold = otsuThreshold(px) ?: return emptyList()
    var darkSum = 0.0
    var darkCount = 0
    var brightSum = 0.0
    var brightCount = 0
    for (v in px) {
        if (v > threshold) { brightSum += v; brightCount++ } else { darkSum += v; darkCount++ }
    }
    if (darkCount == 0 || brightCount == 0) return emptyList()
    val separation = (brightSum / brightCount - darkSum / darkCount).toFloat()
    if (separation < MIN_CLASS_SEPARATION) return emptyList()

    // Likelier reading first: the class the photo's border is *not* made of.
    val ring = max(2, min(w, h) * 3 / 100)
    var borderBright = 0
    var borderTotal = 0
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (x < ring || y < ring || x >= w - ring || y >= h - ring) {
                borderTotal++
                if (px[y * w + x] > threshold) borderBright++
            }
        }
    }
    val brightFirst = borderBright * 2 < borderTotal
    return listOf(brightFirst, !brightFirst).mapNotNull { pageIsBright ->
        sheetFrom(px, w, h, threshold, pageIsBright, separation)
    }
}

/** The largest region of one brightness class, if it has the shape of a sheet of paper. */
private fun sheetFrom(
    px: FloatArray,
    w: Int,
    h: Int,
    threshold: Float,
    pageIsBright: Boolean,
    separation: Float
): FoundPage? {
    val mask = BooleanArray(w * h) { (px[it] > threshold) == pageIsBright }

    // --- largest connected region of "page" ---
    val region = largestRegion(mask, w, h) ?: return null
    val areaFraction = region.size.toFloat() / (w * h)
    if (areaFraction < MIN_PAGE_AREA || areaFraction > MAX_PAGE_AREA) return null

    // --- its outline, as a convex hull reduced to four corners ---
    val inRegion = BooleanArray(w * h)
    for (i in region) inRegion[i] = true
    val outline = ArrayList<IntArray>()
    for (i in region) {
        val x = i % w
        val y = i / w
        val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
            !inRegion[i - 1] || !inRegion[i + 1] || !inRegion[i - w] || !inRegion[i + w]
        if (edge) outline += intArrayOf(x, y)
    }
    val hull = convexHull(outline)
    if (hull.size < 4) return null
    val hullArea = polygonArea(hull)
    if (hullArea <= 0f) return null

    val quad = reduceToQuad(hull)
    val quadArea = polygonArea(quad)

    // A sheet of paper fills its own hull and that hull is four-sided. A hand, a cable
    // or two overlapping sheets fail one test or the other.
    val solidity = min(1f, region.size / hullArea)
    val quadFit = min(quadArea / hullArea, hullArea / quadArea)
    if (solidity < MIN_SOLIDITY || quadFit < MIN_QUAD_FIT) return null

    val corners = orderCorners(quad.map { NormPoint(it[0] / (w - 1f), it[1] / (h - 1f)) })
    if (!isPlausibleQuad(corners)) return null

    val confidence = min(solidity, quadFit) * min(1f, separation / 0.3f)
    return FoundPage(corners, confidence)
}

/** Otsu's method on a 64-bin histogram: the split that best separates two classes. */
private fun otsuThreshold(px: FloatArray): Float? {
    val bins = 64
    val hist = IntArray(bins)
    for (v in px) hist[(v.coerceIn(0f, 1f) * (bins - 1)).toInt()]++
    val total = px.size
    var sumAll = 0.0
    for (b in 0 until bins) sumAll += b.toDouble() * hist[b]

    var weightLow = 0
    var sumLow = 0.0
    var bestVariance = -1.0
    var best = -1
    for (b in 0 until bins) {
        weightLow += hist[b]
        if (weightLow == 0) continue
        val weightHigh = total - weightLow
        if (weightHigh == 0) break
        sumLow += b.toDouble() * hist[b]
        val meanLow = sumLow / weightLow
        val meanHigh = (sumAll - sumLow) / weightHigh
        val variance = weightLow.toDouble() * weightHigh * (meanLow - meanHigh) * (meanLow - meanHigh)
        if (variance > bestVariance) {
            bestVariance = variance
            best = b
        }
    }
    return if (best < 0) null else (best + 0.5f) / (bins - 1)
}

/** Pixel indices of the largest 4-connected region of the mask. */
private fun largestRegion(mask: BooleanArray, w: Int, h: Int): IntArray? {
    val seen = BooleanArray(mask.size)
    val stack = IntArray(mask.size)
    var best: IntArray? = null
    for (start in mask.indices) {
        if (!mask[start] || seen[start]) continue
        var top = 0
        stack[top++] = start
        seen[start] = true
        val members = ArrayList<Int>()
        while (top > 0) {
            val i = stack[--top]
            members += i
            val x = i % w
            val y = i / w
            if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[top++] = i - 1 }
            if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[top++] = i + 1 }
            if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[top++] = i - w }
            if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[top++] = i + w }
        }
        if (best == null || members.size > best.size) best = members.toIntArray()
    }
    return best
}

/** Andrew's monotone chain. Returns hull vertices in order, without repeating the first. */
private fun convexHull(points: List<IntArray>): List<IntArray> {
    val sorted = points.sortedWith(compareBy({ it[0] }, { it[1] }))
    if (sorted.size < 3) return sorted
    fun cross(o: IntArray, a: IntArray, b: IntArray): Long =
        (a[0] - o[0]).toLong() * (b[1] - o[1]) - (a[1] - o[1]).toLong() * (b[0] - o[0])

    val hull = ArrayList<IntArray>()
    for (p in sorted) {
        while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
        hull += p
    }
    val lowerSize = hull.size + 1
    for (k in sorted.size - 2 downTo 0) {
        val p = sorted[k]
        while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
        hull += p
    }
    hull.removeAt(hull.size - 1)
    return hull
}

private fun polygonArea(poly: List<IntArray>): Float {
    var sum = 0L
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        sum += a[0].toLong() * b[1] - b[0].toLong() * a[1]
    }
    return abs(sum) / 2f
}

/**
 * Collapse a convex polygon to four vertices by repeatedly deleting the vertex whose
 * removal costs the least area. Points along a straight side cost almost nothing and
 * go first; the true corners cost the most and are what remains.
 */
private fun reduceToQuad(hull: List<IntArray>): List<IntArray> {
    val poly = ArrayList(hull)
    while (poly.size > 4) {
        var cheapest = 0
        var cheapestCost = Long.MAX_VALUE
        for (i in poly.indices) {
            val a = poly[(i + poly.size - 1) % poly.size]
            val b = poly[i]
            val c = poly[(i + 1) % poly.size]
            val cost = abs((b[0] - a[0]).toLong() * (c[1] - a[1]) - (b[1] - a[1]).toLong() * (c[0] - a[0]))
            if (cost < cheapestCost) {
                cheapestCost = cost
                cheapest = i
            }
        }
        poly.removeAt(cheapest)
    }
    return poly
}
