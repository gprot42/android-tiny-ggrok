package com.tinyggrok.app.data.scan

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in check against a real photograph, for tuning on scenes that synthetic images
 * do not capture (carpet, wood grain, shadows). Skipped unless SCAN_FIXTURE points at a
 * raw brightness file: a text header "width height\n" followed by one byte per pixel.
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

    @Test
    fun locateAndRefineOnARealPhoto() {
        val path = System.getenv("SCAN_FIXTURE")
        assumeTrue("SCAN_FIXTURE not set", !path.isNullOrBlank())
        val image = load(path!!)
        show("snap-from-default", refineCorners(image, DocumentCorners.DEFAULT))
        val found = findPage(image)
        show("found (conf=${found?.confidence})", found?.corners)
        show("locatePage (what the app uses)", locatePage(image))
    }
}
