package com.tinyggrok.app.data.scan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic table tops for scanner tests: the surfaces people actually put paper on.
 * Each is built to include the feature most likely to fool an edge finder, not just the
 * right average colour: plank seams and grain for wood, long veins for marble, speckle
 * for granite, and a lighting gradient such as window light from one side.
 */
internal object SurfaceScenes {
    const val WIDTH = 640
    const val HEIGHT = 360

    /** Tilted and perspective-skewed, off-centre. */
    val PAGE = DocumentCorners(
        tl = NormPoint(0.235f, 0.135f),
        tr = NormPoint(0.762f, 0.110f),
        br = NormPoint(0.755f, 0.806f),
        bl = NormPoint(0.255f, 0.808f)
    )

    enum class Surface { DARK_WOOD, LIGHT_WOOD, WHITE_MARBLE, DARK_GRANITE, GREY_CONCRETE }

    fun inside(c: DocumentCorners, x: Float, y: Float): Boolean {
        val p = c.toList()
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Distance (normalized units) from a point inside the page to its nearest edge. */
    private fun depthInside(c: DocumentCorners, x: Float, y: Float): Float {
        val p = c.toList()
        var best = Float.MAX_VALUE
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            val d = abs((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)) / len
            if (d < best) best = d
        }
        return best
    }

    fun render(
        surface: Surface,
        paper: Float = 0.90f,
        lightingGradient: Boolean = false,
        seed: Int = 5,
        woodGrain: Boolean = true,
        print: Boolean = true,
        contactShadow: Boolean = true
    ): LumaImage {
        val rnd = Random(seed)
        val w = WIDTH
        val h = HEIGHT

        // Veins / cracks: meandering polylines across the whole frame.
        data class Vein(val x0: Float, val y0: Float, val angle: Float, val wobble: Float, val phase: Float)
        val veins = List(7) {
            Vein(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * PI.toFloat(), 0.02f + rnd.nextFloat() * 0.03f, rnd.nextFloat() * 6f)
        }
        fun veinStrength(nx: Float, ny: Float): Float {
            var s = 0f
            for (v in veins) {
                val dx = nx - v.x0
                val dy = ny - v.y0
                val along = dx * cos(v.angle) + dy * sin(v.angle)
                val across = -dx * sin(v.angle) + dy * cos(v.angle) - v.wobble * sin(along * 14f + v.phase)
                val t = 1f - abs(across) / 0.006f
                if (t > s) s = t
            }
            return s.coerceIn(0f, 1f)
        }

        val grainW = w / 2 + 1
        val grain = FloatArray(grainW * (h / 2 + 1)) { rnd.nextFloat() - 0.5f }
        val data = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = x / (w - 1f)
                val ny = y / (h - 1f)
                val speck = grain[(y / 2) * grainW + x / 2]
                var v = when (surface) {
                    Surface.DARK_WOOD, Surface.LIGHT_WOOD -> {
                        val base = if (surface == Surface.DARK_WOOD) 0.30f else 0.68f
                        // Grain: streaks running along x, drifting slowly.
                        val streak = if (!woodGrain) 0f else 0.05f * sin(ny * 90f + 3f * sin(nx * 5f)) +
                            0.03f * sin(ny * 310f + nx * 9f)
                        // Plank seams: thin dark lines, parallel to the page's long edges
                        // and placed close to them, the worst place they could be.
                        val seam = if (y % 84 < 2) -0.22f else 0f
                        base + streak + seam + speck * 0.05f
                    }
                    Surface.WHITE_MARBLE -> {
                        val cloud = 0.035f * sin(nx * 7f + 1.3f) * cos(ny * 5f + 0.4f)
                        0.85f + cloud + speck * 0.03f - 0.26f * veinStrength(nx, ny)
                    }
                    Surface.DARK_GRANITE ->
                        0.24f + speck * 0.30f + 0.30f * veinStrength(nx, ny)
                    Surface.GREY_CONCRETE ->
                        0.52f + speck * 0.10f + 0.04f * sin(nx * 11f) * sin(ny * 13f)
                }
                if (inside(PAGE, nx, ny)) {
                    v = paper + (rnd.nextFloat() - 0.5f) * 0.03f
                    // Print.
                    if (print && x % 9 < 3 && ny > 0.2f && ny < 0.72f && nx > 0.3f && nx < 0.7f) v = 0.35f
                } else {
                    // Contact shadow: paper lifts slightly, so a soft dark rim hugs the two
                    // edges away from the light. Real, and present even on white marble.
                    val shifted = inside(PAGE, nx - 0.006f, ny - 0.010f)
                    if (shifted && contactShadow) v -= 0.16f
                }
                if (lightingGradient) v *= 0.72f + 0.40f * nx
                data[y * w + x] = v.coerceIn(0f, 1f)
            }
        }
        return LumaImage(w, h, data)
    }

    fun worstError(found: DocumentCorners, expected: DocumentCorners = PAGE): Float =
        found.toList().zip(expected.toList()).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }

    /** What a vision model typically hands back: right page, corners ~2% off. */
    val GROK_LIKE = DocumentCorners(
        tl = NormPoint(PAGE.tl.x + 0.018f, PAGE.tl.y - 0.020f),
        tr = NormPoint(PAGE.tr.x - 0.020f, PAGE.tr.y + 0.017f),
        br = NormPoint(PAGE.br.x + 0.016f, PAGE.br.y + 0.019f),
        bl = NormPoint(PAGE.bl.x - 0.021f, PAGE.bl.y - 0.018f)
    )
}
