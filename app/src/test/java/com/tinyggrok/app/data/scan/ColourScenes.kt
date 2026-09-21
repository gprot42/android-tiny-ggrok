package com.tinyggrok.app.data.scan

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A colour photo built for a test, with the true corners of the page in it. */
internal class ColourScene(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val corners: DocumentCorners,
    val tableEdgeY: Float
)

/**
 * A printed sheet lying near the front of a varnished wooden table, as photographed from
 * a chair: wood all round the page, and below the table's front edge the dark of the floor.
 * [gap] is the width of table showing between the paper's bottom edge and the table's
 * edge, as a fraction of the photo's height; [tiltDegrees] turns the page on the table.
 */
internal fun pageNearTableEdge(gap: Float, tiltDegrees: Float, width: Int = 506, height: Int = 900): ColourScene {
    val rnd = Random(11)
    val pageW = 0.80f * width
    val pageH = pageW * 1.414f
    val cx = 0.5f * width
    val cy = 0.08f * height + pageH / 2f
    val a = Math.toRadians(tiltDegrees.toDouble())
    val ca = cos(a).toFloat()
    val sa = sin(a).toFloat()

    fun corner(u: Float, v: Float): NormPoint {
        val x = cx + u * ca - v * sa
        val y = cy + u * sa + v * ca
        return NormPoint(x / (width - 1), y / (height - 1))
    }
    val corners = DocumentCorners(
        tl = corner(-pageW / 2, -pageH / 2), tr = corner(pageW / 2, -pageH / 2),
        br = corner(pageW / 2, pageH / 2), bl = corner(-pageW / 2, pageH / 2)
    )
    val lowestPaper = maxOf(corners.bl.y, corners.br.y) * (height - 1)
    val tableEdge = lowestPaper + gap * height

    val argb = IntArray(width * height) { i ->
        val x = (i % width).toFloat()
        val y = (i / width).toFloat()
        val dx = x - cx
        val dy = y - cy
        val u = dx * ca + dy * sa
        val v = -dx * sa + dy * ca
        val onPage = kotlin.math.abs(u) <= pageW / 2 && kotlin.math.abs(v) <= pageH / 2
        val noise = (rnd.nextFloat() - 0.5f) * 10f
        var r: Float
        var g: Float
        var b: Float
        if (onPage) {
            // Warm indoor light: paper is not quite neutral.
            r = 232f; g = 228f; b = 218f
            val row = ((v + pageH / 2) / pageH)
            val col = ((u + pageW / 2) / pageW)
            val inText = row in 0.12f..0.88f && col in 0.1f..0.9f
            if (inText && ((v + pageH / 2).toInt() % 11) < 3 && rnd.nextFloat() < 0.7f) { r = 60f; g = 60f; b = 62f }
        } else if (y < tableEdge) {
            // Varnished wood: orange, with grain running across and a sheen towards the front.
            val grain = 18f * sin(y * 0.55f + 3f * sin(x * 0.013f)) + 8f * sin(x * 0.21f + y * 0.07f)
            val sheen = 40f * (y / height)
            r = 196f + grain + sheen; g = 128f + 0.8f * grain + sheen; b = 58f + 0.4f * grain + 0.6f * sheen
        } else {
            r = 26f; g = 28f; b = 30f
        }
        fun ch(c: Float) = (c + noise).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
    return ColourScene(width, height, argb, corners, tableEdge / (height - 1))
}

/**
 * A plain sheet of one colour lying square on a desk of another, with a few lines of
 * print. For scenes where the colours themselves are the point.
 */
internal fun sheetOnDesk(sheet: IntArray, desk: IntArray, width: Int = 506, height: Int = 900): ColourScene {
    val rnd = Random(5)
    val left = 0.14f; val right = 0.86f; val top = 0.12f; val bottom = 0.84f
    val argb = IntArray(width * height) { i ->
        val fx = (i % width) / (width - 1f)
        val fy = (i / width) / (height - 1f)
        val onSheet = fx in left..right && fy in top..bottom
        var c = if (onSheet) sheet else desk
        val inText = fx in (left + 0.08f)..(right - 0.08f) && fy in (top + 0.08f)..(bottom - 0.08f)
        if (onSheet && inText && (i / width) % 12 < 3 && rnd.nextFloat() < 0.7f) c = intArrayOf(55, 55, 58)
        val noise = ((rnd.nextFloat() - 0.5f) * 10f).toInt()
        fun ch(v: Int) = (v + noise).coerceIn(0, 255)
        (0xFF shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
    }
    val corners = DocumentCorners(NormPoint(left, top), NormPoint(right, top), NormPoint(right, bottom), NormPoint(left, bottom))
    return ColourScene(width, height, argb, corners, 1f)
}

/**
 * A full-colour magazine cover lying on a wooden desk and overhanging its front edge onto
 * pale carpet, after the scan that was reported. Everything that made that photo hard is
 * here: the cover is no lighter, darker or plainer than what it lies on; it is busy all
 * over (rows of sequins, a masthead, a bold headline across the lower part, which is a
 * straighter, louder line than the cover's own bottom edge); the desk's front edge runs
 * behind it and out both sides; and its pale lower right corner barely differs from the
 * carpet, with a shadow under the left of the bottom edge only.
 */
internal fun magazineOnDesk(tiltDegrees: Float = -0.6f, width: Int = 506, height: Int = 900): ColourScene {
    val rnd = Random(21)
    val pageW = 0.76f * width
    val pageH = 0.74f * height
    val cx = 0.5f * width
    val cy = 0.515f * height
    val a = Math.toRadians(tiltDegrees.toDouble())
    val ca = cos(a).toFloat()
    val sa = sin(a).toFloat()
    fun corner(u: Float, v: Float) =
        NormPoint((cx + u * ca - v * sa) / (width - 1), (cy + u * sa + v * ca) / (height - 1))
    val corners = DocumentCorners(
        corner(-pageW / 2, -pageH / 2), corner(pageW / 2, -pageH / 2),
        corner(pageW / 2, pageH / 2), corner(-pageW / 2, pageH / 2)
    )
    val deskEdge = 0.785f * height

    val argb = IntArray(width * height) { i ->
        val x = (i % width).toFloat()
        val y = (i / width).toFloat()
        val dx = x - cx
        val dy = y - cy
        val u = dx * ca + dy * sa
        val v = -dx * sa + dy * ca
        val col = u / pageW + 0.5f
        val row = v / pageH + 0.5f
        var r: Float
        var g: Float
        var b: Float
        if (col in 0f..1f && row in 0f..1f) {
            // Backdrop: gold on the left running to purple on the right, in rows of sequins.
            val mix = col
            r = 215f * (1 - mix) + 150f * mix
            g = 170f * (1 - mix) + 70f * mix
            b = 60f * (1 - mix) + 160f * mix
            val cell = 9f
            val inDisc = ((u % cell + cell) % cell - cell / 2).let { it * it } +
                ((v % cell + cell) % cell - cell / 2).let { it * it } < 10f
            if (inDisc) { r += 45f; g += 40f; b += 45f }
            // Masthead: big white letters across the top.
            if (row in 0.05f..0.17f && ((col * 9f) % 1f) < 0.7f && col in 0.04f..0.96f) { r = 245f; g = 243f; b = 240f }
            // Two figures: skin and pale dresses down the middle.
            if (row in 0.2f..0.92f && (kotlin.math.abs(col - 0.38f) < 0.07f || kotlin.math.abs(col - 0.62f) < 0.07f)) {
                r = 222f; g = 180f; b = 160f
            }
            // The backdrop fades into a pale foot, as printed covers do (no hard line), and
            // towards its lower right the foot all but matches the carpet beyond it.
            val fade = ((row - 0.78f) / 0.12f).coerceIn(0f, 1f)
            val glare = ((col - 0.55f) / 0.2f).coerceIn(0f, 1f) * ((row - 0.9f) / 0.06f).coerceIn(0f, 1f)
            val footR = 188f + 18f * glare
            val footG = 204f - 2f * glare
            val footB = 184f + 8f * glare
            r += (footR - r) * fade; g += (footG - g) * fade; b += (footB - b) * fade
            // Headline: bold dark blue letters right across, three quarters of the way down.
            if (row in 0.72f..0.80f && ((col * 14f) % 1f) < 0.72f && col in 0.02f..0.98f) { r = 50f; g = 60f; b = 140f }
        } else if (y < deskEdge) {
            val grain = 14f * sin(y * 0.5f + 3f * sin(x * 0.012f)) + 6f * sin(x * 0.2f + y * 0.06f)
            r = 176f + grain; g = 126f + 0.8f * grain; b = 74f + 0.5f * grain
        } else {
            // Pale carpet, in shadow under the left part of the overhanging cover.
            val pile = (rnd.nextFloat() - 0.5f) * 26f
            r = 214f + pile; g = 203f + pile; b = 195f + pile
            val below = v - pageH / 2
            if (below in 0f..26f && col in -0.02f..0.62f) {
                val shade = 0.55f + 0.45f * (below / 26f)
                r *= shade; g *= shade; b *= shade
            }
        }
        val noise = (rnd.nextFloat() - 0.5f) * 8f
        fun ch(c: Float) = (c + noise).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
    return ColourScene(width, height, argb, corners, deskEdge / (height - 1))
}

/** The same desk and carpet with nothing on them: nothing here may be taken for a page. */
internal fun bareDeskAndCarpet(width: Int = 506, height: Int = 900): ColourScene {
    val scene = magazineOnDesk(width = width, height = height)
    val rnd = Random(22)
    val deskEdge = 0.785f * height
    val argb = IntArray(width * height) { i ->
        val x = (i % width).toFloat()
        val y = (i / width).toFloat()
        val noise = (rnd.nextFloat() - 0.5f) * 8f
        val (r, g, b) = if (y < deskEdge) {
            val grain = 14f * sin(y * 0.5f + 3f * sin(x * 0.012f)) + 6f * sin(x * 0.2f + y * 0.06f)
            Triple(176f + grain, 126f + 0.8f * grain, 74f + 0.5f * grain)
        } else {
            val pile = (rnd.nextFloat() - 0.5f) * 26f
            Triple(214f + pile, 203f + pile, 195f + pile)
        }
        fun ch(c: Float) = (c + noise).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
    return ColourScene(width, height, argb, scene.corners, deskEdge / (height - 1))
}
