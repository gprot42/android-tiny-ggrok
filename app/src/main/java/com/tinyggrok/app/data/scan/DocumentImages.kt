package com.tinyggrok.app.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Longest side kept for the working photo. Bounds memory while keeping print legible. */
internal const val SCAN_SOURCE_MAX_SIDE = 2560

/** Longest side of the straightened page that gets attached. */
internal const val SCAN_OUTPUT_MAX_SIDE = 2048

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
internal fun loadUprightBitmap(context: Context, uri: Uri, maxSide: Int = SCAN_SOURCE_MAX_SIDE): Bitmap {
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
    if (rotation == 0f && scale == 1f) return decoded

    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation)
    }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (upright !== decoded) decoded.recycle()
    return upright
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
    val (outW, outH) = flattenedSize(corners, src.width, src.height, maxSide)

    val from = corners.toList().flatMap { listOf(it.x * src.width, it.y * src.height) }.toFloatArray()
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

internal fun scanDirectory(context: Context): File =
    File(context.cacheDir, SCAN_DIR).apply { mkdirs() }

/** Drop captures and scans from earlier sessions; nothing references them any more. */
internal fun purgeOldScans(context: Context, now: Long = System.currentTimeMillis()) {
    scanDirectory(context).listFiles()?.forEach { file ->
        if (now - file.lastModified() > SCAN_FILE_MAX_AGE_MS) file.delete()
    }
}

internal fun saveScanJpeg(context: Context, bitmap: Bitmap): File {
    val file = File(scanDirectory(context), "scan_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    return file
}
