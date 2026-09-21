package com.tinyggrok.app.data.scan

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in check against a real photograph, for tuning on scenes that synthetic images
 * do not capture (carpet, wood grain, shadows). Skipped unless SCAN_FIXTURE points at a
 * raw brightness file: a text header "width height\n" followed by one byte per pixel
 * (or, named .rgb, three bytes per pixel, which also exercises the colour-aware view).
 * Real photos of documents are personal, so fixtures stay out of the repo.
 *
 *   SCAN_FIXTURE=/path/photo.luma ./gradlew :app:testDebugUnitTest --tests "*RealPhoto*" -i
 */
class RealPhotoHarnessTest {

    private fun load(path: String): LumaImage {
        val bytes = File(path).readBytes()
        val headerEnd = bytes.indexOf('\n'.code.toByte())
        val (w, h) = String(bytes, 0, headerEnd).trim().split(" ").map { it.toInt() }
        val data = FloatArray(w * h) { i -> (bytes[headerEnd + 1 + i].toInt() and 0xFF) / 255f }
        return LumaImage(w, h, data)
    }

    private fun show(label: String, c: DocumentCorners?) {
        val text = c?.toList()?.joinToString(" ") { "%.4f,%.4f".format(it.x, it.y) } ?: "none"
        println("HARNESS $label $text")
    }

    /**
     * Opt-in: run the enhancer on a real flattened scan. ENHANCE_FIXTURE is a raw colour
     * file ("width height\n" then RGB bytes); the result is written to ENHANCE_OUT in the
     * same format, and the time taken is printed.
     */
    @Test
    fun enhanceARealScan() {
        val path = System.getenv("ENHANCE_FIXTURE")
        val out = System.getenv("ENHANCE_OUT")
        assumeTrue("ENHANCE_FIXTURE not set", !path.isNullOrBlank() && !out.isNullOrBlank())
        val bytes = File(path!!).readBytes()
        val headerEnd = bytes.indexOf('\n'.code.toByte())
        val (w, h) = String(bytes, 0, headerEnd).trim().split(" ").map { it.toInt() }
        val px = IntArray(w * h) { i ->
            val o = headerEnd + 1 + i * 3
            (0xFF shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
        }
        val started = System.nanoTime()
        DocumentEnhancer.enhance(ArrayPixelGrid(w, h, px), bands = 4) { jobs ->
            jobs.map { Thread(it).apply { start() } }.forEach { it.join() }
        }
        println("HARNESS enhanced ${w}x$h in ${(System.nanoTime() - started) / 1_000_000} ms")
        val result = ByteArray(w * h * 3)
        for (i in px.indices) {
            result[i * 3] = (px[i] shr 16).toByte()
            result[i * 3 + 1] = (px[i] shr 8).toByte()
            result[i * 3 + 2] = px[i].toByte()
        }
        File(out!!).writeBytes("$w $h\n".toByteArray() + result)
    }

    /** Opt-in: print the slant of the text in a real flattened page (SKEW_FIXTURE, raw brightness). */
    @Test
    fun measureTextSkewOnARealPage() {
        val path = System.getenv("SKEW_FIXTURE")
        assumeTrue("SKEW_FIXTURE not set", !path.isNullOrBlank())
        val image = load(path!!)
        println("HARNESS skew upper ${estimateTextSkew(image, 0.08f, 0.45f)}")
        println("HARNESS skew lower ${estimateTextSkew(image, 0.55f, 0.92f)}")
        // Finer bands, to see where on the page a measurement comes from.
        for (k in 0 until 6) {
            val a = 0.05f + k * 0.15f
            println("HARNESS band %.2f-%.2f ${estimateTextSkew(image, a, a + 0.15f)}".format(a, a + 0.15f))
        }
    }

    @Test
    fun locateAndRefineOnARealPhoto() {
        val path = System.getenv("SCAN_FIXTURE")
        assumeTrue("SCAN_FIXTURE not set", !path.isNullOrBlank())
        if (path!!.endsWith(".rgb")) {
            // A colour fixture ("width height\n" then RGB bytes): both views, as the app sees them.
            val views = loadColour(path)
            show("locatePage, brightness view", locatePage(views.luma))
            show("locatePage, paper view", locatePage(views.paper))
            val byEdges = findPageByEdges(views.colour)
            show("findPageByEdges, rough (score=${byEdges?.confidence})", byEdges?.corners)
            show("locatePageByEdges, verified", locatePageByEdges(views.colour))
            val started = System.nanoTime()
            val located = locatePageDetailed(views)
            println("HARNESS took ${(System.nanoTime() - started) / 1_000_000} ms, by edges alone: ${located?.byEdgesAlone}")
            show("locatePage (what the app uses)", located?.corners)
            located?.corners?.let { c ->
                val print = holdsPrint(views.colour, c)
                println("HARNESS holds print: $print")
                if (print) show("levelled to its print", straightenByText(views.luma, c))
            }
            show("snap-from-default (what the app uses)", refineCorners(views, DocumentCorners.DEFAULT))
            // The Grok route: the right page, corners loose by a percent or two either way.
            located?.corners?.let { c ->
                for (by in listOf(0.015f, -0.015f)) {
                    val loose = DocumentCorners(
                        NormPoint(c.tl.x - by, c.tl.y - by), NormPoint(c.tr.x + by, c.tr.y - by),
                        NormPoint(c.br.x + by, c.br.y + by), NormPoint(c.bl.x - by, c.bl.y + by)
                    )
                    show("from an outline loose by $by", squareUp(views, loose) ?: refineCorners(views, loose))
                }
            }
            return
        }
        val image = load(path)
        show("snap-from-default", refineCorners(image, DocumentCorners.DEFAULT))
        val found = findPage(image)
        show("found (conf=${found?.confidence})", found?.corners)
        show("locatePage (what the app uses)", locatePage(image))
    }

    private fun loadColour(path: String): PageViews {
        val bytes = File(path).readBytes()
        val headerEnd = bytes.indexOf('\n'.code.toByte())
        val (w, h) = String(bytes, 0, headerEnd).trim().split(" ").map { it.toInt() }
        val px = IntArray(w * h) { i ->
            val o = headerEnd + 1 + i * 3
            (0xFF shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
        }
        return pageViewsOf(px, w, h)
    }
}
