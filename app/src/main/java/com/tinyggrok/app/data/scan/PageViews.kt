package com.tinyggrok.app.data.scan

import kotlin.math.abs

/**
 * The renderings of a photo in which a page's edges are looked for, most telling first.
 *
 * Brightness alone cannot tell paper from whatever it lies on, only lighter from darker,
 * and a table has an edge of its own. Reported from real scans: a sheet lying near the
 * front of a varnished wooden table came out with a strip of table along the bottom,
 * because table-against-floor is a straighter, stronger step in brightness than
 * paper-against-table, it faces the same way, and it lay within reach of the paper's edge.
 *
 * What does set paper apart is that it is colourless. Wood, skin, cloth, cork and leather
 * are not, so in [paper] they go dark however brightly lit, the table's own edge all but
 * disappears (dark wood against dark floor), and the paper's edge becomes the only strong
 * step left. Shade, unlike a second surface, takes brightness away without adding colour,
 * so a shadow lying across a page does not turn into an edge.
 *
 * [luma] is kept as the second opinion for the scenes where colour says nothing (grey
 * carpet, marble, concrete read the same in both) or misleads (a pastel sheet on a grey
 * desk is the colourful thing in the picture).
 *
 * [colour] is the last resort, for pages that no single number per pixel sets apart from
 * what they lie on: a full-colour magazine cover on a wooden desk is no lighter, darker or
 * plainer than the desk. It is judged by change of colour, which has no sign, so it lacks
 * the others' best defence against print (a page is lighter, or darker, than its
 * surroundings *all the way round*); see [findPageByEdges] for what it relies on instead.
 */
internal class PageViews(val paper: LumaImage, val luma: LumaImage, val colour: ColourImage) {
    val all: List<LumaImage> get() = listOf(paper, luma)
}

/** Brightness of a pixel, 0..1. */
internal fun lumaOf(r: Int, g: Int, b: Int): Float = (0.299f * r + 0.587f * g + 0.114f * b) / 255f

/**
 * How much a pixel looks like blank paper, 0..1: its brightness less its colourfulness
 * (the spread between its strongest and weakest channel). White paper keeps nearly all of
 * its brightness, even under warm indoor light; greys are unchanged; orange wood, however
 * glossy, falls to almost nothing.
 */
internal fun paperness(r: Int, g: Int, b: Int): Float {
    val chroma = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
    return (lumaOf(r, g, b) - chroma).coerceIn(0f, 1f)
}

/** Both views of a block of ARGB pixels, in one pass. */
internal fun pageViewsOf(argb: IntArray, width: Int, height: Int): PageViews {
    val paper = FloatArray(width * height)
    val luma = FloatArray(width * height)
    val red = FloatArray(width * height)
    val green = FloatArray(width * height)
    val blue = FloatArray(width * height)
    for (i in 0 until width * height) {
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        paper[i] = paperness(r, g, b)
        luma[i] = lumaOf(r, g, b)
        red[i] = r / 255f
        green[i] = g / 255f
        blue[i] = b / 255f
    }
    fun plane(data: FloatArray) = LumaImage(width, height, data)
    return PageViews(plane(paper), plane(luma), ColourImage(plane(red), plane(green), plane(blue)))
}

/** A page located on the phone, and whether it took its edges alone to find it. */
internal class LocatedPage(val corners: DocumentCorners, val byEdgesAlone: Boolean)

/**
 * The first view in which a page can be located and verified: as paper, then by
 * brightness (see [locatePage]), then by nothing but its straight edges (see
 * [locatePageByEdges]).
 */
internal fun locatePageDetailed(views: PageViews): LocatedPage? =
    views.all.firstNotNullOfOrNull { locatePage(it) }?.let { LocatedPage(it, false) }
        ?: locatePageByEdges(views.colour)?.let { LocatedPage(it, true) }

internal fun locatePage(views: PageViews): DocumentCorners? = locatePageDetailed(views)?.corners

/**
 * The colour view's reading of the page that [rough] points at.
 *
 * Tightening a rough outline works, in the other views, by looking up to a few percent
 * either side of it for the strongest step that faces the right way. Colour steps face no
 * way, and on a cover the strongest line near the top edge is the masthead, not the edge:
 * tried exactly so, the outline snapped to the masthead from every starting point. What
 * tells them apart is the whole sheet (see [findPageByEdges]), so that is asked first, and
 * its answer is used if it is the page [rough] was pointing at, and then the final fit
 * looks only a short way. The fit itself no longer lets a station pick its strongest step
 * (see [refineCornersDetailed] for colour), which is what makes a longer look tolerable
 * when the whole-sheet search has nothing to offer.
 */
private fun refineInColour(colour: ColourImage, rough: DocumentCorners): Refinement {
    val sheet = findPageByEdges(colour)?.corners?.takeIf { found ->
        found.toList().zip(rough.toList()).all { (f, r) ->
            abs(f.x - r.x) <= SAME_PAGE_TOLERANCE && abs(f.y - r.y) <= SAME_PAGE_TOLERANCE
        }
    }
    return refineCornersDetailed(colour, sheet ?: rough, if (sheet != null) EDGE_FINDER_REACH else LOOSE_OUTLINE_REACH)
}

/**
 * How far to look, as a fraction of the diagonal, when all there is to go on is a loose
 * outline (a vision model's, or the user's hand) and the whole-sheet search has nothing
 * to add. Wide enough for their usual error, short of the masthead on a typical cover.
 */
private const val LOOSE_OUTLINE_REACH = 0.03f

/** Corners this close (as a fraction of the photo) are taken to mean the same page. */
private const val SAME_PAGE_TOLERANCE = 0.1f

/**
 * Whether what lies inside [rough] is print on paper (see [paperShare]) rather than a
 * cover or a photograph. It decides which view is believed first near a rough outline. The
 * paper and brightness views take a page to be one plain surface with print on it; shown a
 * busy cover they still find straight, same-facing steps to settle on (tried: the top of
 * the outline went to the masthead and was "verified" there), so for such a page the
 * colour view, which reasons about the whole sheet, is asked before them.
 */
internal fun holdsPrint(colour: ColourImage, rough: DocumentCorners): Boolean {
    val n = 48
    val samples = IntArray(n * n)
    val c = rough
    for (j in 0 until n) for (i in 0 until n) {
        // Stay a little inside the outline: it is rough, and the desk is not the page.
        val u = 0.06f + 0.88f * i / (n - 1)
        val v = 0.06f + 0.88f * j / (n - 1)
        val x = ((c.tl.x * (1 - u) + c.tr.x * u) * (1 - v) + (c.bl.x * (1 - u) + c.br.x * u) * v) * (colour.width - 1)
        val y = ((c.tl.y * (1 - u) + c.tr.y * u) * (1 - v) + (c.bl.y * (1 - u) + c.br.y * u) * v) * (colour.height - 1)
        fun channel(plane: LumaImage) = (plane.at(x, y) * 255f).toInt().coerceIn(0, 255)
        samples[j * n + i] = (channel(colour.r) shl 16) or (channel(colour.g) shl 8) or channel(colour.b)
    }
    return paperShareOf(samples) >= MIN_PAPER_SHARE
}

/** Ways of tightening [rough], in the order they should be believed for the page it holds. */
private fun attemptsNear(views: PageViews, rough: DocumentCorners): List<() -> Refinement> {
    val grey = views.all.map { view -> { refineCornersDetailed(view, rough) } }
    val colour = listOf { refineInColour(views.colour, rough) }
    return if (holdsPrint(views.colour, rough)) grey + colour else colour + grey
}

/**
 * The first view in which [rough] can be verified; see [squareUp]. In colour that means
 * all four sides confirmed: a page that needs this view is not one whose missing sides
 * can be reasoned about.
 */
internal fun squareUp(views: PageViews, rough: DocumentCorners): DocumentCorners? {
    fun inColour() = refineInColour(views.colour, rough).takeIf { it.confirmedSides == 4 }?.corners
    fun inGrey() = views.all.firstNotNullOfOrNull { squareUp(it, rough) }
    return if (holdsPrint(views.colour, rough)) inGrey() ?: inColour() else inColour() ?: inGrey()
}

/**
 * [rough] tightened in whichever view confirms the most real edges near it, stopping at
 * the first that confirms all four; earlier views win ties.
 */
internal fun refineCorners(views: PageViews, rough: DocumentCorners): DocumentCorners {
    var best: Refinement? = null
    for (attempt in attemptsNear(views, rough)) {
        val refined = attempt()
        if (best == null || refined.confirmedSides > best.confirmedSides) best = refined
        if (refined.confirmedSides == 4) break
    }
    return best!!.corners
}
