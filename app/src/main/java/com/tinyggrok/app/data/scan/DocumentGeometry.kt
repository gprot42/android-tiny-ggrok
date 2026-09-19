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
 * Size of the straightened page in pixels. Opposite edges differ under perspective, so
 * the longer of each pair is used to avoid losing detail, then capped to [maxSide].
 */
internal fun flattenedSize(
    c: DocumentCorners,
    srcWidth: Int,
    srcHeight: Int,
    maxSide: Int
): Pair<Int, Int> {
    fun dist(a: NormPoint, b: NormPoint): Float =
        hypot((a.x - b.x) * srcWidth, (a.y - b.y) * srcHeight)

    val w = max(dist(c.tl, c.tr), dist(c.bl, c.br))
    val h = max(dist(c.tl, c.bl), dist(c.tr, c.br))
    val scale = if (max(w, h) > maxSide) maxSide / max(w, h) else 1f
    return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
}
