package com.tinyggrok.app.data.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Html
import androidx.core.content.FileProvider
import java.io.File

private const val SHARED_INBOX_DIR = "shared_inbox"
private const val SHARED_INBOX_MAX_AGE_MS = 24L * 60L * 60L * 1000L

/** Pull a share/process-text intent into an [IncomingShare], copying images into cache. */
fun Intent.toIncomingShare(context: Context): IncomingShare? {
    val parsed = IncomingShareParser.parse(
        action = action,
        mimeType = type,
        extraText = extraTextOrClipText(),
        extraSubject = getStringExtra(Intent.EXTRA_SUBJECT),
        extraProcessText = getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
        extraStreamUris = extractStreamUris().map { it.toString() },
        clipDataUris = extractClipUris().map { it.toString() }
    ) ?: return null

    if (parsed.imageUris.isEmpty()) return parsed

    val persisted = persistSharedImages(
        context,
        parsed.imageUris.map(Uri::parse)
    )
    return parsed.copy(imageUris = persisted.map { it.toString() })
}

fun startPlainTextShare(context: Context, text: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

/**
 * Offer one of this app's own image files to other apps.
 *
 * Other apps cannot read our cache directory, so the file goes out as a FileProvider
 * content URI with a read grant. The grant is attached through ClipData as well as the
 * flag: from Android 10 the chooser only forwards a grant it can see in the clip, and
 * without it the receiving app gets a URI it is not allowed to open.
 */
fun startImageFileShare(context: Context, file: File, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        // Email apps use this as the subject line; messengers such as Signal and
        // Telegram ignore it. No EXTRA_TEXT: messengers would post it as a caption.
        putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension.replace('_', ' '))
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

internal const val FILE_PROVIDER_AUTHORITY = "com.tinyggrok.app.fileprovider"

fun htmlToPlainText(html: String): String =
    Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()

internal fun persistSharedImages(context: Context, uris: List<Uri>): List<Uri> {
    val dir = File(context.cacheDir, SHARED_INBOX_DIR).apply { mkdirs() }
    pruneOldSharedImages(dir)
    return uris.map { src ->
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    src,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Sender did not grant persistable access; the temporary grant is enough
                // to copy into our cache while we still hold the share intent.
            }
            val dest = File(dir, "share_${System.nanoTime()}")
            context.contentResolver.openInputStream(src)?.use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: return@map src
            if (!dest.exists() || dest.length() == 0L) {
                dest.delete()
                src
            } else {
                Uri.fromFile(dest)
            }
        } catch (_: Exception) {
            src
        }
    }
}

private fun pruneOldSharedImages(dir: File) {
    val cutoff = System.currentTimeMillis() - SHARED_INBOX_MAX_AGE_MS
    dir.listFiles()?.forEach { file ->
        if (file.isFile && file.lastModified() < cutoff) {
            file.delete()
        }
    }
}

private fun Intent.extractStreamUris(): List<Uri> {
    return when (action) {
        Intent.ACTION_SEND -> listOfNotNull(parcelableExtraCompat(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> parcelableArrayListExtraCompat(Intent.EXTRA_STREAM)
        else -> emptyList()
    }
}

private fun Intent.extractClipUris(): List<Uri> {
    val clip = clipData ?: return emptyList()
    return buildList {
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).uri?.let { add(it) }
        }
    }
}

private fun Intent.extraTextOrClipText(): String? {
    val extra = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    if (!extra.isNullOrBlank()) return extra
    val clip = clipData ?: return null
    val parts = buildList {
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(it) }
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

@Suppress("DEPRECATION")
private fun Intent.parcelableExtraCompat(name: String): Uri? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(name, Uri::class.java)
    } else {
        getParcelableExtra(name) as? Uri
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelableArrayListExtraCompat(name: String): List<Uri> {
    val list = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayListExtra(name, Uri::class.java)
    } else {
        getParcelableArrayListExtra<Uri>(name)
    }
    return list.orEmpty()
}
