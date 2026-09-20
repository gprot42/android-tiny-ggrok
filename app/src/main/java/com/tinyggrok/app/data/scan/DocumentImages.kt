package com.tinyggrok.app.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Longest side kept for the working photo. Bounds memory while keeping print legible. */
internal const val SCAN_SOURCE_MAX_SIDE = 2560

/**
 * Longest side of the straightened page as saved and shared: A4 at 300 dpi. The page is
 * never enlarged, so this only matters when the capture holds that much detail.
 */
internal const val SCAN_OUTPUT_MAX_SIDE = 3508

/**
 * Longest side of the copy attached to a prompt. Ample for Grok to read small print
 * (A4 at about 175 dpi) while keeping a ten-page request to a sensible upload.
 */
internal const val SCAN_PROMPT_MAX_SIDE = 2048

/**
 * How far inside the detected outline the page is actually cut, as a fraction of its
 * shorter side. The outline is fitted to the *middle* of the paper's slightly blurred
 * edge, so cutting exactly on it keeps half of that edge and a sliver of desk: measured on
 * a real scan, a dark rim three to four pixels thick on every side. Small enough that no
 * document's margin notices.
 */
private const val TRIM_FRACTION = 0.004f

/** The outline moved inward by the trim, in an image of the given size. */
private fun trimmed(corners: DocumentCorners, width: Int, height: Int): DocumentCorners {
    val p = corners.toList()
    fun side(i: Int, j: Int) = hypot((p[i].x - p[j].x) * width, (p[i].y - p[j].y) * height)
    val shorter = min(min(side(0, 1), side(3, 2)), min(side(0, 3), side(1, 2)))
    return insetQuad(corners, width.toFloat(), height.toFloat(), max(2f, TRIM_FRACTION * shorter))
}

/** Most pixels decoded from the capture for one flatten (about 96 MB as ARGB). */
private const val MAX_REGION_PIXELS = 24_000_000L

/** Sizes used for analysis only; the warp always runs on the full working photo. */
private const val DETECTION_MAX_SIDE = 1024
private const val LUMA_MAX_SIDE = 900

private const val SCAN_DIR = "scans"
private const val SCAN_FILE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

/**
 * Decode a photo the right way up. Camera apps usually store the sensor image plus an
 * EXIF rotation; BitmapFactory ignores that tag, so without this step every portrait
 * capture arrives sideways and the corners land on the wrong edges.
 */
internal fun loadUprightBitmap(context: Context, uri: Uri, maxSide: Int = SCAN_SOURCE_MAX_SIDE): Bitmap =
    loadCapture(context, uri, maxSide).preview

/**
 * A capture opened for editing: a reduced, upright [preview] for the screen and for
 * analysis, plus what is needed to go back to the stored file for the final page. The
 * preview is deliberately small; cutting the page out of *it* is what made early scans
 * soft, because a page filling half the frame kept only half of an already reduced image.
 */
internal class Capture(
    val preview: Bitmap,
    /** Size of the image as stored, before any EXIF rotation. */
    val storedWidth: Int,
    val storedHeight: Int,
    /** Clockwise degrees the stored image must be turned to stand upright. */
    val exifRotation: Int
)

internal fun loadCapture(context: Context, uri: Uri, maxSide: Int = SCAN_SOURCE_MAX_SIDE): Capture {
    val resolver = context.contentResolver

    // A bounds-only decode returns null by design, so its result says nothing about
    // whether the stream opened; only the stream itself and the sizes it fills in do.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val stream = resolver.openInputStream(uri)
        ?: throw IllegalArgumentException("Cannot open photo")
    stream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("Cannot read photo")
    }

    // Power-of-two subsampling down to at most twice the target, then an exact scale.
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IllegalArgumentException("Cannot decode photo")

    val rotation = resolver.openInputStream(uri)?.use { stream ->
        when (ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f

    val longest = max(decoded.width, decoded.height)
    val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
    val preview = if (rotation == 0f && scale == 1f) {
        decoded
    } else {
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }
    return Capture(preview, bounds.outWidth, bounds.outHeight, rotation.toInt())
}

/**
 * Flatten the page from the capture *as stored*, at the best resolution it holds.
 *
 * [corners] are where the user sees them: on the upright preview, after [turned] degrees
 * of clockwise rotation in total (the EXIF turn plus any taps on Rotate). They are carried
 * back onto the stored image, only the page's bounding box is decoded (so a 50-megapixel
 * capture costs no more memory than the page needs), and the four points are mapped, in
 * the order they appear on screen, onto an upright rectangle. Keeping that order is what
 * makes the result come out the way the preview showed it, with no separate rotate step.
 */
@Suppress("DEPRECATION") // newInstance(InputStream, Boolean): its replacement needs API 31, minSdk is 24.
internal fun flattenFromCapture(
    context: Context,
    uri: Uri,
    capture: Capture,
    corners: DocumentCorners,
    turned: Int,
    maxSide: Int = SCAN_OUTPUT_MAX_SIDE
): Bitmap {
    val w = capture.storedWidth
    val h = capture.storedHeight
    // Carried back onto the stored image (a turn keeps the corners clockwise), then trimmed.
    val onStored = corners.toList().map { it.beforeTurningClockwise(turned) }
    val stored = trimmed(DocumentCorners(onStored[0], onStored[1], onStored[2], onStored[3]), w, h).toList()
    val xs = stored.map { it.x * w }
    val ys = stored.map { it.y * h }

    val region = Rect(
        floor(xs.min()).toInt().coerceIn(0, w - 1),
        floor(ys.min()).toInt().coerceIn(0, h - 1),
        ceil(xs.max()).toInt().coerceIn(1, w),
        ceil(ys.max()).toInt().coerceIn(1, h)
    )
    require(region.width() > 8 && region.height() > 8) { "Page region is empty" }

    // Output size from the page's own edge lengths in stored pixels, in on-screen order.
    fun dist(i: Int, j: Int) = hypot(xs[i] - xs[j], ys[i] - ys[j])
    val naturalW = max(dist(0, 1), dist(3, 2))
    val naturalH = max(dist(0, 3), dist(1, 2))
    val fit = min(1f, maxSide / max(naturalW, naturalH))

    // Subsample while the decode would still exceed the output, or memory, by 2x or more.
    var sample = 1
    while (fit * sample * 2 <= 1f) sample *= 2
    while (region.width().toLong() * region.height() / (sample.toLong() * sample) > MAX_REGION_PIXELS) sample *= 2

    val source = context.contentResolver.openInputStream(uri)?.use { stream ->
        val decoder = BitmapRegionDecoder.newInstance(stream, false)
            ?: throw IllegalStateException("Cannot open capture for region decoding")
        try {
            decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            decoder.recycle()
        }
    } ?: throw IllegalArgumentException("Cannot reopen capture")

    try {
        // The decoder rounds subsampled sizes; measure the scale it actually applied.
        val sx = source.width / region.width().toFloat()
        val sy = source.height / region.height().toFloat()
        val from = FloatArray(8) { i ->
            if (i % 2 == 0) (xs[i / 2] - region.left) * sx else (ys[i / 2] - region.top) * sy
        }
        val outW = max(1, (naturalW * fit).roundToInt())
        val outH = max(1, (naturalH * fit).roundToInt())
        val to = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val matrix = Matrix()
        require(matrix.setPolyToPoly(from, 0, to, 0, 4)) { "Corners do not form a usable quad" }

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    } finally {
        source.recycle()
    }
}

private fun Bitmap.scaledToFit(maxSide: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxSide) return this
    val k = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(this, max(1, (width * k).toInt()), max(1, (height * k).toInt()), true)
}

/** Reduced JPEG for the corner-finding call: small upload, plenty for locating a page. */
internal fun Bitmap.toDetectionJpegBase64(): String {
    val small = scaledToFit(DETECTION_MAX_SIDE)
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 80, out)
    if (small !== this) small.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** Grayscale copy for [refineCorners]. */
internal fun Bitmap.toLumaImage(): LumaImage {
    val small = scaledToFit(LUMA_MAX_SIDE)
    val w = small.width
    val h = small.height
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    if (small !== this) small.recycle()
    val luma = FloatArray(w * h) { i ->
        val p = pixels[i]
        (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
    }
    return LumaImage(w, h, luma)
}

/**
 * Flatten the quad [corners] of [src] into an upright rectangle.
 *
 * Android's own [Matrix.setPolyToPoly] solves the four-point perspective transform, so
 * no native vision library is needed for the straightening itself. Mapping the quad to
 * an exact rectangle is what squares the page; how square depends only on the corners.
 */
internal fun warpDocument(
    src: Bitmap,
    corners: DocumentCorners,
    maxSide: Int = SCAN_OUTPUT_MAX_SIDE
): Bitmap {
    val cut = trimmed(corners, src.width, src.height)
    val (outW, outH) = flattenedSize(cut, src.width, src.height, maxSide)

    val from = cut.toList().flatMap { listOf(it.x * src.width, it.y * src.height) }.toFloatArray()
    val to = floatArrayOf(
        0f, 0f,
        outW.toFloat(), 0f,
        outW.toFloat(), outH.toFloat(),
        0f, outH.toFloat()
    )
    val matrix = Matrix()
    require(matrix.setPolyToPoly(from, 0, to, 0, 4)) { "Corners do not form a usable quad" }

    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        drawColor(Color.WHITE)
        drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }
    return out
}

private class BitmapPixelGrid(private val bitmap: Bitmap) : PixelGrid {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun readRows(y: Int, rows: Int, into: IntArray) {
        bitmap.getPixels(into, 0, width, 0, y, width, rows)
    }

    override fun writeRows(y: Int, rows: Int, from: IntArray) {
        bitmap.setPixels(from, 0, width, 0, y, width, rows)
    }
}

/**
 * Make a flattened page read like a scan: white paper, dark ink, crisp edges. In place,
 * split across a few threads; see [DocumentEnhancer] for what it does and why.
 */
internal fun enhanceDocument(page: Bitmap) {
    val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    DocumentEnhancer.enhance(BitmapPixelGrid(page), bands = workers) { jobs ->
        if (jobs.size == 1) {
            jobs[0]()
        } else {
            var failure: Throwable? = null
            jobs.map { job ->
                Thread {
                    try {
                        job()
                    } catch (t: Throwable) {
                        failure = t
                    }
                }.apply { start() }
            }.forEach { it.join() }
            failure?.let { throw it }
        }
    }
}

/** Whiten slivers of desk left along the edges. Only for an enhanced (white-paper) page. */
internal fun cleanEdges(page: Bitmap) = whitenEdgeSlivers(BitmapPixelGrid(page))

internal fun scanDirectory(context: Context): File =
    File(context.cacheDir, SCAN_DIR).apply { mkdirs() }

/** Drop captures and scans from earlier sessions; nothing references them any more. */
internal fun purgeOldScans(context: Context, now: Long = System.currentTimeMillis()) {
    scanDirectory(context).listFiles()?.forEach { file ->
        if (now - file.lastModified() > SCAN_FILE_MAX_AGE_MS) file.delete()
    }
}

/**
 * A copy of a saved scan no larger than [maxSide], for attaching to a prompt. Returns
 * [file] itself when it is already small enough.
 */
internal fun promptSizedCopy(context: Context, file: File, maxSide: Int = SCAN_PROMPT_MAX_SIDE): File {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (max(bounds.outWidth, bounds.outHeight) <= maxSide) return file

    val full = BitmapFactory.decodeFile(file.path) ?: return file
    val small = full.scaledToFit(maxSide)
    val copy = File(scanDirectory(context), file.nameWithoutExtension + "_prompt.jpg")
    copy.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    if (small !== full) small.recycle()
    full.recycle()
    return copy
}

internal fun saveScanJpeg(context: Context, bitmap: Bitmap): File {
    // Shared files keep this name in the receiving app, so make it readable there.
    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    val file = File(scanDirectory(context), "Scan_$stamp.jpg")
    // 95: JPEG rings around fine dark strokes, and print is nothing but those.
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    return file
}
