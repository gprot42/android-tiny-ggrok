package com.tinyggrok.app.data.scan

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** A point in image space, as fractions of width and height (0..1). */
data class NormPoint(val x: Float, val y: Float)

/**
 * The four corners of a document inside a photo, clockwise from top-left, normalized
 * to the image so the same corners apply to a thumbnail and to the full-size bitmap.
 */
data class DocumentCorners(
    val tl: NormPoint,
    val tr: NormPoint,
    val br: NormPoint,
    val bl: NormPoint
) {
    fun toList(): List<NormPoint> = listOf(tl, tr, br, bl)

    fun withCorner(index: Int, p: NormPoint): DocumentCorners = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    companion object {
        /** Starting quad when nothing has been detected: the frame, slightly inset. */
        val DEFAULT = DocumentCorners(
            tl = NormPoint(0.08f, 0.08f),
            tr = NormPoint(0.92f, 0.08f),
            br = NormPoint(0.92f, 0.92f),
            bl = NormPoint(0.08f, 0.92f)
        )
    }
}

private fun quarterTurns(degrees: Int): Int = (((degrees % 360) + 360) % 360) / 90

/**
 * Where a point lands when the image is turned clockwise by [degrees] (a multiple of 90).
 * A clockwise quarter turn sends the top-left of the image to the top-right.
 */
internal fun NormPoint.turnedClockwise(degrees: Int): NormPoint = when (quarterTurns(degrees)) {
    1 -> NormPoint(1f - y, x)
    2 -> NormPoint(1f - x, 1f - y)
    3 -> NormPoint(y, 1f - x)
    else -> this
}

/**
 * The inverse: given a point on an image that has been turned clockwise by [degrees],
 * where it was on the original. Used to carry corners placed on the upright preview back
 * onto the camera's stored image, which is what full-resolution flattening reads.
 */
internal fun NormPoint.beforeTurningClockwise(degrees: Int): NormPoint =
    turnedClockwise(360 - quarterTurns(degrees) * 90)

/**
 * Move every side of a clockwise quad inward by [insetPx], in pixels of a [width] x
 * [height] image, and return the corners where the moved sides meet.
 *
 * Sides are moved parallel to themselves rather than the quad being scaled about its
 * centre, so the same few pixels come off each edge however long or tilted it is. Returns
 * the input unchanged if the inset would collapse or fold the quad.
 */
internal fun insetQuad(corners: DocumentCorners, width: Float, height: Float, insetPx: Float): DocumentCorners {
    if (insetPx <= 0f) return corners
    val p = corners.toList().map { floatArrayOf(it.x * width, it.y * height) }
    val origin = ArrayList<FloatArray>(4)
    val dir = ArrayList<FloatArray>(4)
    for (i in 0 until 4) {
        val a = p[i]
        val b = p[(i + 1) % 4]
        val len = hypot(b[0] - a[0], b[1] - a[1])
        if (len < 1e-3f) return corners
        val d = floatArrayOf((b[0] - a[0]) / len, (b[1] - a[1]) / len)
        // Corners run clockwise with y pointing down, so (-dy, dx) points into the quad.
        origin += floatArrayOf(a[0] - d[1] * insetPx, a[1] + d[0] * insetPx)
        dir += d
    }
    val moved = (0 until 4).map { i ->
        val j = (i + 3) % 4 // corner i is where side i-1 meets side i
        val cross = dir[j][0] * dir[i][1] - dir[j][1] * dir[i][0]
        if (abs(cross) < 1e-5f) return corners
        val dx = origin[i][0] - origin[j][0]
        val dy = origin[i][1] - origin[j][1]
        val t = (dx * dir[i][1] - dy * dir[i][0]) / cross
        NormPoint((origin[j][0] + dir[j][0] * t) / width, (origin[j][1] + dir[j][1] * t) / height)
    }
    val result = DocumentCorners(moved[0], moved[1], moved[2], moved[3])
    // Still a sensible, clockwise quad of nearly the same size?
    val keptArea = quadArea(result) / max(quadArea(corners), 1e-9f)
    return if (isPlausibleQuad(result) && keptArea > 0.5f && keptArea <= 1f) result else corners
}

/** Smallest share of the photo a detected page may cover before we distrust it. */
private const val MIN_AREA_FRACTION = 0.05f

/** Grid the model is asked to answer on; integers are easier for it than fractions. */
internal const val CORNER_GRID = 1000f

/**
 * Pull corners out of the model's reply. Tolerant by design: the reply may be wrapped
 * in prose or a code fence, may use a 0-1000 grid or 0-1 fractions, and may label the
 * corners in the wrong order. Anything implausible returns null so the caller keeps
 * the manual quad instead of warping the photo into nonsense.
 */
internal fun parseDocumentCorners(reply: String): DocumentCorners? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null

    val obj = try {
        JsonParser.parseString(reply.substring(start, end + 1)).asJsonObject
    } catch (_: Exception) {
        return null
    }
    if (obj.get("found")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) return null

    // Corners may sit at the top level or under a "corners" object.
    val holder = obj.get("corners")?.takeIf { it.isJsonObject }?.asJsonObject ?: obj
    val raw = listOf("tl", "tr", "br", "bl").map { key -> holder.point(key) ?: return null }

    // 0-1000 grid unless every value already looks like a fraction.
    val scale = if (raw.all { it.x <= 1.5f && it.y <= 1.5f }) 1f else CORNER_GRID
    val points = raw.map {
        NormPoint(
            (it.x / scale).coerceIn(0f, 1f),
            (it.y / scale).coerceIn(0f, 1f)
        )
    }
    return orderCorners(points).takeIf { isPlausibleQuad(it) }
}

private fun JsonObject.point(key: String): NormPoint? {
    val el = get(key) ?: return null
    return try {
        when {
            el.isJsonArray -> {
                val a: JsonArray = el.asJsonArray
                if (a.size() < 2) null else NormPoint(a[0].asFloat, a[1].asFloat)
            }
            el.isJsonObject -> {
                val o = el.asJsonObject
                NormPoint(o.get("x").asFloat, o.get("y").asFloat)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Put four points in clockwise order from top-left, whatever labels they came with.
 * Top-left minimises x+y and bottom-right maximises it; top-right maximises x-y and
 * bottom-left minimises it.
 */
internal fun orderCorners(points: List<NormPoint>): DocumentCorners {
    require(points.size == 4) { "need exactly four corners" }
    val tl = points.minByOrNull { it.x + it.y }!!
    val br = points.maxByOrNull { it.x + it.y }!!
    val rest = points.filter { it !== tl && it !== br }
    val tr = rest.maxByOrNull { it.x - it.y }!!
    val bl = rest.first { it !== tr }
    return DocumentCorners(tl, tr, br, bl)
}

/** Signed area by the shoelace formula, in normalized units (the whole image is 1). */
internal fun quadArea(c: DocumentCorners): Float {
    val p = c.toList()
    var sum = 0f
    for (i in p.indices) {
        val a = p[i]
        val b = p[(i + 1) % p.size]
        sum += a.x * b.y - b.x * a.y
    }
    return abs(sum) / 2f
}

/** Convex, and large enough to be a page rather than a stray rectangle. */
internal fun isPlausibleQuad(c: DocumentCorners): Boolean {
    val p = c.toList()
    var sign = 0
    for (i in p.indices) {
        val a = p[i]
        val b = p[(i + 1) % 4]
        val d = p[(i + 2) % 4]
        val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
        if (abs(cross) < 1e-6f) return false // collinear: degenerate quad
        val s = if (cross > 0) 1 else -1
        if (sign == 0) sign = s else if (s != sign) return false
    }
    return quadArea(c) >= MIN_AREA_FRACTION
}

/**
 * Height over width of the flat rectangle whose photograph has these corners, or null
 * when the corners give no sensible answer.
 *
 * The lengths of a page's sides in a photo do not give its proportions: a phone tilted
 * over a page sees the far end smaller *and the whole page shorter*, and measuring sides
 * keeps the first effect's correction while ignoring the second. Measured on real scans,
 * an A4 letter (1.414) came out at 1.387 and a tabloid front page about 8% squat, which
 * shows in type and in faces.
 *
 * The classic remedy (Zhang and He, whiteboard scanning) recovers the camera's focal
 * length from the page's two vanishing points and the proportions from that. It cannot be
 * used as published: a phone is nearly always tilted one way only, which leaves one pair
 * of sides parallel in the photo, one vanishing point at infinity, and the focal length
 * 0/0. Tried on five real outlines it returned nothing at all, or a focal length of seven
 * to sixteen times the photo. But the focal length is not really unknown. Phone main
 * cameras are all much alike (24 to 28 mm equivalent, which is 0.69 to 0.81 of the photo's
 * long side), so it is assumed. With it the same real A4 photos give 1.412, and 0.708 for
 * a sheet lying sideways (0.707). How much the assumption matters grows with the tilt:
 * nothing from straight above, 0.3% at 10 degrees, and 2% at 25 degrees for a lens at
 * either end of that range. Side lengths are out by 9% and 23% at those same tilts.
 */
internal fun pageProportions(c: DocumentCorners, srcWidth: Int, srcHeight: Int): Float? {
    // Homogeneous image points, relative to the middle of the photo.
    fun point(p: NormPoint) = doubleArrayOf((p.x - 0.5) * srcWidth, (p.y - 0.5) * srcHeight, 1.0)
    fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    val m1 = point(c.tl)
    val m2 = point(c.tr)
    val m3 = point(c.bl)
    val m4 = point(c.br)
    val d2 = dot(cross(m2, m4), m3)
    val d3 = dot(cross(m3, m4), m2)
    if (abs(d2) < 1e-9 || abs(d3) < 1e-9) return null
    val k2 = dot(cross(m1, m4), m3) / d2
    val k3 = dot(cross(m1, m4), m2) / d3
    // Directions of the page's width and height, up to the camera's focal length.
    val across = DoubleArray(3) { k2 * m2[it] - m1[it] }
    val down = DoubleArray(3) { k3 * m3[it] - m1[it] }

    val f = ASSUMED_FOCAL_LENGTH * max(srcWidth, srcHeight)
    fun lengthSquared(n: DoubleArray) = (n[0] * n[0] + n[1] * n[1]) / (f * f) + n[2] * n[2]
    val width = lengthSquared(across)
    val height = lengthSquared(down)
    if (width <= 0.0 || height <= 0.0) return null
    val ratio = Math.sqrt(height / width).toFloat()
    return ratio.takeIf { it.isFinite() && it > 0f }
}

/** Focal length of a phone's main camera, as a fraction of the photo's long side. */
private const val ASSUMED_FOCAL_LENGTH = 0.72

/**
 * The proportions from perspective may differ from those of the side lengths by this
 * factor at most. Generous, because a page photographed from a chair really is that
 * foreshortened: at 35 degrees an A4 sheet measures 0.95 by its sides.
 */
private const val MAX_PROPORTION_CORRECTION = 2f

/**
 * Size of the straightened page in photo pixels, before any cap: as wide and as tall as
 * its longer sides in the photo, so no detail is thrown away, then stretched along
 * whichever way [pageProportions] says the page has been foreshortened.
 */
internal fun naturalPageSize(c: DocumentCorners, srcWidth: Int, srcHeight: Int): Pair<Float, Float> {
    fun dist(a: NormPoint, b: NormPoint): Float =
        hypot((a.x - b.x) * srcWidth, (a.y - b.y) * srcHeight)

    val w = max(dist(c.tl, c.tr), dist(c.bl, c.br))
    val h = max(dist(c.tl, c.bl), dist(c.tr, c.br))
    if (w < 1f || h < 1f) return w to h
    val measured = h / w
    // An outline that implies an absurd tilt is a bad outline, not a steep photo.
    val ratio = pageProportions(c, srcWidth, srcHeight)
        ?.takeIf { it / measured in (1f / MAX_PROPORTION_CORRECTION)..MAX_PROPORTION_CORRECTION }
        ?: return w to h
    val width = max(w, h / ratio)
    return width to width * ratio
}

/** Size of the straightened page in pixels: [naturalPageSize], capped to [maxSide]. */
internal fun flattenedSize(
    c: DocumentCorners,
    srcWidth: Int,
    srcHeight: Int,
    maxSide: Int
): Pair<Int, Int> {
    val (w, h) = naturalPageSize(c, srcWidth, srcHeight)
    val scale = if (max(w, h) > maxSide) maxSide / max(w, h) else 1f
    return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
}
